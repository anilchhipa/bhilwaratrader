package com.algo.service;

import com.algo.core.MarketUtils;
import com.algo.core.RiskConfig;
import com.algo.data.ZerodhaClient;
import com.zerodhatech.models.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import com.zerodhatech.models.Order;

/**
 * Enterprise Risk Management & Safety Engine.
 * Provides:
 * 1. Single order value guardrails (rejects fat-finger orders).
 * 2. Rapid-fire order debounce (rejects accidental double-clicks / UI loop spams).
 * 3. Daily Mark-to-Market (MTM) Loss Kill-Switch with emergency auto-cancellation & Telegram alerts.
 */
@Service
public class RiskManagerService {
    private static final Logger logger = LoggerFactory.getLogger(RiskManagerService.class);

    private final RiskConfig riskConfig;
    private final ZerodhaClient zerodhaClient;
    private final TelegramService telegramService;

    // Kill switch state
    private final AtomicBoolean killSwitchActive = new AtomicBoolean(false);
    private volatile String killSwitchReason = null;
    private volatile double lastCalculatedMtm = 0.0;

    // Order debounce tracking: key -> timestamp (epoch millis)
    private final ConcurrentHashMap<String, Long> lastOrderTimestamps = new ConcurrentHashMap<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Fail-Fast Limit Order Watcher
    // Tracks pending orders and auto-cancels them if not filled within TTL.
    // ─────────────────────────────────────────────────────────────────────────
    private static class PendingOrder {
        final String orderId;
        final String symbol;
        final String txnType;
        final long expiresAtMs;

        PendingOrder(String orderId, String symbol, String txnType, long expiresAtMs) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.txnType = txnType;
            this.expiresAtMs = expiresAtMs;
        }
    }
    // orderId -> PendingOrder
    private final ConcurrentHashMap<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();

    public RiskManagerService(RiskConfig riskConfig, ZerodhaClient zerodhaClient, TelegramService telegramService) {
        this.riskConfig = riskConfig;
        this.zerodhaClient = zerodhaClient;
        this.telegramService = telegramService;
        logger.info("RiskManagerService initialized with config: {}", riskConfig);
    }

    /**
     * Validates order placement against all risk rules before sending to broker.
     * Throws IllegalArgumentException or IllegalStateException if rejected.
     */
    public void validateOrder(String symbol, String txnType, int qty, double price) {
        // 1. Kill-Switch check
        if (killSwitchActive.get()) {
            String msg = "Order REJECTED: Kill-Switch is ACTIVE. Reason: " + killSwitchReason;
            logger.warn(msg);
            throw new IllegalStateException(msg);
        }

        // 2. Max Order Value Check (Fat-finger protection)
        double orderValue = qty * price;
        double maxAllowed = riskConfig.getMaxOrderValue();
        if (orderValue > maxAllowed) {
            String msg = String.format(
                    "Order REJECTED: Order value (₹%.2f) exceeds maximum permitted limit of ₹%.2f for %s %d @ ₹%.2f",
                    orderValue, maxAllowed, symbol, qty, price);
            logger.warn(msg);
            throw new IllegalArgumentException(msg);
        }

        // 3. Rapid-fire Debounce Check
        long now = System.currentTimeMillis();
        String debounceKey = (symbol + ":" + txnType).toUpperCase();
        long debounceWindow = riskConfig.getOrderDebounceMs();

        Long lastTime = lastOrderTimestamps.put(debounceKey, now);
        if (lastTime != null && (now - lastTime) < debounceWindow) {
            String msg = String.format(
                    "Order REJECTED: Duplicate order throttled for %s (%s). Please wait %d ms between orders.",
                    symbol, txnType, debounceWindow);
            logger.warn(msg);
            throw new IllegalStateException(msg);
        }

        logger.debug("Order passed risk validation: {} {} x {} @ ₹{} (Value: ₹{})",
                txnType, qty, symbol, price, orderValue);
    }

    /**
     * Registers a placed limit order for fail-fast tracking.
     * Call this immediately after placeLimitOrder() returns successfully.
     *
     * The watcher will poll Zerodha every 5 seconds and cancel the order
     * if it is still OPEN after {@code risk.limit-order-ttl-seconds} (default: 30s).
     *
     * @param orderId  Zerodha order ID returned by placeLimitOrder()
     * @param symbol   Trading symbol (for logging/alerts only)
     * @param txnType  "BUY" or "SELL" (for logging/alerts only)
     */
    public void trackOrder(String orderId, String symbol, String txnType) {
        if (orderId == null || orderId.isBlank()) return;
        long ttlMs = (long) riskConfig.getLimitOrderTtlSeconds() * 1000L;
        PendingOrder po = new PendingOrder(orderId, symbol, txnType, System.currentTimeMillis() + ttlMs);
        pendingOrders.put(orderId, po);
        logger.info("FailFast: Tracking order {} ({} {}) — will auto-cancel if OPEN after {}s",
                orderId, txnType, symbol, riskConfig.getLimitOrderTtlSeconds());
    }

    /**
     * Automated periodic MTM loss monitor. Runs every 5 seconds.
     */
    @Scheduled(fixedDelay = 5000)
    public void monitorDailyMtm() {
        if (!riskConfig.isKillSwitchEnabled() || killSwitchActive.get()) {
            return;
        }

        if (!MarketUtils.isMarketOpen() || !zerodhaClient.isLoggedIn()) {
            return;
        }

        checkDailyLoss();
    }

    /**
     * Fail-fast watcher: runs every 5 seconds and cancels any limit order
     * that has been OPEN longer than its TTL.
     *
     * Flow:
     *  1. Scan pendingOrders for expired entries.
     *  2. Fetch current order status from Zerodha.
     *  3. If still OPEN → cancel and alert.
     *  4. If already COMPLETE / CANCELLED → remove from tracking silently.
     */
    @Scheduled(fixedDelay = 5000)
    public void watchPendingOrders() {
        if (pendingOrders.isEmpty()) return;
        if (!zerodhaClient.isLoggedIn()) return;

        long now = System.currentTimeMillis();
        for (PendingOrder po : pendingOrders.values()) {
            try {
                // 1. Check live status from Zerodha
                String status = zerodhaClient.getOrderStatus(po.orderId);

                // 2. Already terminal — remove from tracking
                if (status == null
                        || status.equalsIgnoreCase("COMPLETE")
                        || status.equalsIgnoreCase("CANCELLED")
                        || status.equalsIgnoreCase("REJECTED")) {
                    pendingOrders.remove(po.orderId);
                    logger.debug("FailFast: Order {} reached terminal status [{}]. Removed from tracking.",
                            po.orderId, status);
                    continue;
                }

                // 3. Still OPEN — check if TTL has expired
                if (status.equalsIgnoreCase("OPEN") && now >= po.expiresAtMs) {
                    logger.warn("FailFast: Order {} ({} {}) has been OPEN for >{}s — auto-cancelling!",
                            po.orderId, po.txnType, po.symbol, riskConfig.getLimitOrderTtlSeconds());

                    boolean cancelled = zerodhaClient.cancelOrder(po.orderId);
                    pendingOrders.remove(po.orderId);

                    String alertMsg = String.format(
                            "⏱ *Limit Order Auto-Cancelled*\n\n" +
                            "📌 *Symbol:* %s\n" +
                            "📊 *Type:* %s\n" +
                            "🆔 *Order ID:* %s\n" +
                            "⏳ *Reason:* Not filled within %ds\n" +
                            "✅ *Cancelled:* %s",
                            po.symbol, po.txnType, po.orderId,
                            riskConfig.getLimitOrderTtlSeconds(),
                            cancelled ? "Yes" : "Failed — check manually!");
                    telegramService.sendMessage(alertMsg);
                }
            } catch (Exception e) {
                logger.error("FailFast: Error watching order {}: {}", po.orderId, e.getMessage());
            }
        }
    }

    /**
     * Inspects active positions and triggers Kill-Switch if daily loss exceeds threshold.
     */
    public synchronized boolean checkDailyLoss() {
        try {
            Map<String, List<Position>> positionsMap = zerodhaClient.getPositions();
            if (positionsMap == null || positionsMap.isEmpty()) {
                return false;
            }

            List<Position> netPositions = positionsMap.get("net");
            if (netPositions == null || netPositions.isEmpty()) {
                return false;
            }

            double totalMtm = 0.0;
            for (Position pos : netPositions) {
                // pnl is unrealised + realised
                totalMtm += pos.pnl;
            }
            this.lastCalculatedMtm = totalMtm;

            double maxLoss = riskConfig.getMaxDailyLoss();
            if (totalMtm <= -Math.abs(maxLoss)) {
                String reason = String.format("Max daily loss breached! Current MTM: ₹%.2f (Limit: -₹%.2f)",
                        totalMtm, Math.abs(maxLoss));
                triggerKillSwitch(reason);
                return true;
            }

            return false;
        } catch (Exception e) {
            logger.error("RiskManager: Error calculating daily MTM: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Triggers the Emergency Kill-Switch:
     * 1. Blocks new orders.
     * 2. Cancels all pending/open orders on Zerodha.
     * 3. Dispatches high-priority Telegram alert.
     */
    public void triggerKillSwitch(String reason) {
        if (killSwitchActive.compareAndSet(false, true)) {
            this.killSwitchReason = reason;
            logger.error("🚨 EMERGENCY KILL-SWITCH ACTIVATED! {}", reason);

            // Cancel all open orders immediately
            int cancelledCount = zerodhaClient.cancelAllOpenOrders();

            // Alert via Telegram
            String alertMessage = String.format(
                    "🚨 *EMERGENCY KILL-SWITCH ACTIVATED*\n\n" +
                    "⚠️ *Reason:* %s\n" +
                    "📊 *Cancelled Orders:* %d\n" +
                    "🛑 *Status:* All further orders are BLOCKED.",
                    reason, cancelledCount);
            telegramService.sendMessage(alertMessage);
        }
    }

    /**
     * Resets the Kill-Switch (manual trader override).
     */
    public void resetKillSwitch() {
        if (killSwitchActive.compareAndSet(true, false)) {
            logger.info("Kill-Switch manually RESET. Trading re-enabled.");
            this.killSwitchReason = null;
            telegramService.sendMessage("✅ *Risk Alert:* Kill-Switch manually RESET. Trading has been re-enabled.");
        }
    }

    public boolean isKillSwitchActive() {
        return killSwitchActive.get();
    }

    public String getKillSwitchReason() {
        return killSwitchReason;
    }

    public double getLastCalculatedMtm() {
        return lastCalculatedMtm;
    }

    public Map<String, Object> getStatus() {
        return Map.of(
                "killSwitchActive", killSwitchActive.get(),
                "killSwitchReason", killSwitchReason != null ? killSwitchReason : "",
                "currentMtm", lastCalculatedMtm,
                "maxDailyLoss", riskConfig.getMaxDailyLoss(),
                "maxOrderValue", riskConfig.getMaxOrderValue(),
                "orderDebounceMs", riskConfig.getOrderDebounceMs(),
                "killSwitchEnabled", riskConfig.isKillSwitchEnabled()
        );
    }
}
