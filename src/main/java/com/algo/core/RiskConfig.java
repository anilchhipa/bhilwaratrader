package com.algo.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Configuration for Risk Management and Order Safety.
 */
@Component
public class RiskConfig {

    /** Maximum allowed value (qty * price) for a single limit order in INR. */
    @Value("${risk.max-order-value:200000.0}")
    private double maxOrderValue;

    /** Maximum cumulative daily loss threshold in INR before Kill-Switch fires. */
    @Value("${risk.max-daily-loss:50000.0}")
    private double maxDailyLoss;

    /** Debounce interval in milliseconds between identical order placements. */
    @Value("${risk.order-debounce-ms:1500}")
    private long orderDebounceMs;

    /** Master flag to enable or disable automated Kill-Switch on MTM loss. */
    @Value("${risk.kill-switch-enabled:true}")
    private boolean killSwitchEnabled;

    public double getMaxOrderValue() {
        return maxOrderValue;
    }

    public void setMaxOrderValue(double maxOrderValue) {
        this.maxOrderValue = maxOrderValue;
    }

    public double getMaxDailyLoss() {
        return maxDailyLoss;
    }

    public void setMaxDailyLoss(double maxDailyLoss) {
        this.maxDailyLoss = maxDailyLoss;
    }

    public long getOrderDebounceMs() {
        return orderDebounceMs;
    }

    public void setOrderDebounceMs(long orderDebounceMs) {
        this.orderDebounceMs = orderDebounceMs;
    }

    public boolean isKillSwitchEnabled() {
        return killSwitchEnabled;
    }

    public void setKillSwitchEnabled(boolean killSwitchEnabled) {
        this.killSwitchEnabled = killSwitchEnabled;
    }

    /**
     * Maximum lifetime of a pending limit order in seconds before it is
     * automatically cancelled by the fail-fast watcher.
     * Default: 30s (one ~1min candle's worth of time is more than enough;
     * if the price hasn't touched your level in 30s the setup is likely stale).
     */
    @Value("${risk.limit-order-ttl-seconds:30}")
    private int limitOrderTtlSeconds;

    public int getLimitOrderTtlSeconds() {
        return limitOrderTtlSeconds;
    }

    public void setLimitOrderTtlSeconds(int limitOrderTtlSeconds) {
        this.limitOrderTtlSeconds = limitOrderTtlSeconds;
    }

    @Override
    public String toString() {
        return "RiskConfig{" +
                "maxOrderValue=" + maxOrderValue +
                ", maxDailyLoss=" + maxDailyLoss +
                ", orderDebounceMs=" + orderDebounceMs +
                ", killSwitchEnabled=" + killSwitchEnabled +
                '}';
    }
}
