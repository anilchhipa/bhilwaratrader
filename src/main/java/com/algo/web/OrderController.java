package com.algo.web;

import com.algo.core.Engine;
import com.algo.core.MarketUtils;
import com.algo.data.RedisClient;
import com.algo.data.TimescaleRepository;
import com.algo.data.TokenManager;
import com.algo.data.ZerodhaClient;
import com.algo.service.RiskManagerService;
import com.zerodhatech.models.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controller dedicated to Limit Order operations, Order modifications,
 * and Safety/Kill-Switch controls.
 */
@RestController
@RequestMapping("/api/order")
@CrossOrigin(origins = "*")
public class OrderController {
    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final ZerodhaClient zerodhaClient;
    private final TokenManager tokenManager;
    private final RiskManagerService riskManagerService;
    private final TimescaleRepository timescaleRepository;
    private final RedisClient redisClient;
    private final Engine engine;

    public OrderController(ZerodhaClient zerodhaClient,
                           TokenManager tokenManager,
                           RiskManagerService riskManagerService,
                           TimescaleRepository timescaleRepository,
                           RedisClient redisClient,
                           Engine engine) {
        this.zerodhaClient = zerodhaClient;
        this.tokenManager = tokenManager;
        this.riskManagerService = riskManagerService;
        this.timescaleRepository = timescaleRepository;
        this.redisClient = redisClient;
        this.engine = engine;
    }

    /**
     * POST /api/order/place
     * Places a limit order with automatic Risk Management validation.
     */
    @PostMapping("/place")
    public ResponseEntity<?> placeOrder(@RequestBody Map<String, Object> req) {
        try {
            String symbol = (String) req.get("symbol");
            String uiExchange = (String) req.get("exchange");
            String exchange = tokenManager != null && symbol != null ? tokenManager.getExchangeType(symbol) : "NSE";
            if (uiExchange != null && !uiExchange.equals(exchange)) {
                logger.warn("Exchange warning for {}: Using backend map ({}) over UI provided ({})",
                        symbol, exchange, uiExchange);
            }
            String txnType = (String) req.get("txnType"); // "BUY" or "SELL"
            int qty = ((Number) req.get("qty")).intValue();
            double price = ((Number) req.get("price")).doubleValue();
            String product = (String) req.getOrDefault("product", "MIS");

            String reqVariety = (String) req.get("variety");
            String variety = reqVariety != null ? reqVariety
                    : (MarketUtils.isMarketOpen() ? "regular" : "amo");

            if (symbol == null || txnType == null || qty <= 0 || price <= 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing required fields: symbol, txnType, qty, price"));
            }

            // Round price to nearest 0.05 (NSE/BSE tick size requirement)
            price = Math.round(price * 20.0) / 20.0;

            // Round quantity to nearest multiple of Lot Size
            int lotSize = zerodhaClient.getLotSize(symbol);
            if (lotSize > 1) {
                int roundedQty = (qty / lotSize) * lotSize;
                if (roundedQty == 0)
                    roundedQty = lotSize; // Minimum 1 lot
                if (roundedQty != qty) {
                    logger.info("Rounding quantity for {}: {} -> {} (Lot Size: {})", symbol, qty, roundedQty, lotSize);
                    qty = roundedQty;
                }
            }

            // ── RISK MANAGEMENT VALIDATION (P0) ──
            // Checks: Kill-Switch status, Max Single Order Value, Rapid-fire Debounce
            riskManagerService.validateOrder(symbol, txnType, qty, price);

            String orderId = zerodhaClient.placeLimitOrder(symbol, exchange, txnType, qty, price, product, variety);
            return ResponseEntity.ok(Map.of(
                    "orderId", orderId,
                    "symbol", symbol,
                    "txnType", txnType,
                    "qty", qty,
                    "price", price,
                    "product", product,
                    "status", "PLACED"));
        } catch (IllegalStateException | IllegalArgumentException e) {
            logger.warn("Risk rejection for order: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "riskViolation", true));
        } catch (Exception e) {
            logger.error("Error placing order: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PUT /api/order/modify
     * Modifies the price/quantity of an existing pending limit order.
     */
    @PutMapping("/modify")
    public ResponseEntity<?> modifyOrder(@RequestBody Map<String, Object> req) {
        try {
            String orderId = (String) req.get("orderId");
            double newPrice = ((Number) req.get("price")).doubleValue();
            int qty = ((Number) req.get("qty")).intValue();

            if (orderId == null || newPrice <= 0 || qty <= 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing required fields: orderId, price, qty"));
            }

            // Round price to nearest 0.05
            newPrice = Math.round(newPrice * 20.0) / 20.0;

            String reqVariety = (String) req.get("variety");
            String variety = reqVariety != null ? reqVariety
                    : (MarketUtils.isMarketOpen() ? "regular" : "amo");

            String updated = zerodhaClient.modifyLimitOrder(orderId, newPrice, qty, variety);
            return ResponseEntity.ok(Map.of(
                    "orderId", updated,
                    "newPrice", newPrice,
                    "status", "MODIFIED"));
        } catch (Exception e) {
            logger.error("Error modifying order: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/order/cancel/{orderId}
     * Cancels a pending limit order.
     */
    @DeleteMapping("/cancel/{orderId}")
    public ResponseEntity<?> cancelOrder(@PathVariable String orderId) {
        try {
            boolean cancelled = zerodhaClient.cancelOrder(orderId);
            if (cancelled) {
                return ResponseEntity.ok(Map.of("orderId", orderId, "status", "CANCELLED"));
            } else {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "Cancellation failed. Order may be already filled."));
            }
        } catch (Exception e) {
            logger.error("Error cancelling order: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * GET /api/order/position/{symbol}
     * Returns net position for the given symbol to support smart exit orders.
     */
    @GetMapping("/position/{symbol}")
    public ResponseEntity<?> getPositionForSymbol(@PathVariable String symbol) {
        try {
            Map<String, List<Position>> positionsMap = zerodhaClient.getPositions();
            if (positionsMap != null && positionsMap.containsKey("net")) {
                for (Position p : positionsMap.get("net")) {
                    if (p.tradingSymbol != null && p.tradingSymbol.equalsIgnoreCase(symbol)) {
                        return ResponseEntity.ok(Map.of(
                                "symbol", symbol,
                                "netQty", p.netQuantity,
                                "buyQty", p.buyQuantity,
                                "sellQty", p.sellQuantity,
                                "buyPrice", p.buyPrice != null ? p.buyPrice : 0.0,
                                "sellPrice", p.sellPrice != null ? p.sellPrice : 0.0,
                                "product", p.product != null ? p.product : "MIS"
                        ));
                    }
                }
            }
            return ResponseEntity.ok(Map.of("symbol", symbol, "netQty", 0));
        } catch (Exception e) {
            logger.error("Error fetching position for {}: {}", symbol, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage(), "netQty", 0));
        }
    }

    /**
     * POST /api/order/flush-all
     * Resets in-memory and database states for all symbols.
     */
    @PostMapping("/flush-all")
    public ResponseEntity<?> flushAll() {
        int count = 0;
        for (String symbol : new java.util.ArrayList<>(engine.getSymbolStates().keySet())) {
            timescaleRepository.deleteCandleData(symbol);
            redisClient.clearSymbolData(symbol);
            engine.unregisterSymbol(symbol);
            engine.registerSymbol(symbol);
            count++;
        }
        logger.info("flushAll: Cleared data for {} symbols.", count);
        return ResponseEntity.ok(Map.of("message", "ALL data flushed", "symbolsCleared", count));
    }

    /**
     * GET /api/order/risk-status
     * Returns current Risk Manager status, daily MTM, and Kill-Switch status.
     */
    @GetMapping("/risk-status")
    public ResponseEntity<?> getRiskStatus() {
        return ResponseEntity.ok(riskManagerService.getStatus());
    }

    /**
     * POST /api/order/kill-switch/trigger
     * Emergency Kill-Switch manual trigger.
     */
    @PostMapping("/kill-switch/trigger")
    public ResponseEntity<?> manualKillSwitch(@RequestBody(required = false) Map<String, String> body) {
        String reason = body != null && body.containsKey("reason") ? body.get("reason") : "Manual Emergency Trigger by User";
        riskManagerService.triggerKillSwitch(reason);
        return ResponseEntity.ok(Map.of("message", "Kill-Switch triggered successfully", "reason", reason));
    }

    /**
     * POST /api/order/kill-switch/reset
     * Resets the Kill-Switch to resume trading.
     */
    @PostMapping("/kill-switch/reset")
    public ResponseEntity<?> resetKillSwitch() {
        riskManagerService.resetKillSwitch();
        return ResponseEntity.ok(Map.of("message", "Kill-Switch reset. Trading re-enabled."));
    }
}
