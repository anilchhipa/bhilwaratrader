package com.algo.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the state (Candles & Indicators) for a single symbol across multiple
 * timeframes.
 */
public class SymbolState {
    private final String symbol;
    private final String[] timeframes = { "1min", "3min", "5min", "15min" };

    // Timeframe -> Candle
    private final Map<String, Candle> currentCandles = new ConcurrentHashMap<>();

    // Keep track of the last stable price from the last closed candle for outlier
    // detection
    private final Map<String, Double> lastStablePrices = new ConcurrentHashMap<>();

    // Timeframe -> (IndicatorName -> Value)
    private final Map<String, Map<String, Double>> indicatorValues = new ConcurrentHashMap<>();

    // Timeframe -> Strategy (Original Desktop Logic)
    private final Map<String, Strategy> strategies = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface CandleCompleteListener {
        void onComplete(String symbol, String timeframe, Candle candle);
    }

    // Callback when a candle is completed
    private CandleCompleteListener onCandleComplete;

    public SymbolState(String symbol, CandleCompleteListener onCandleComplete, IndicatorConfig indicatorConfig) {
        this.symbol = symbol;
        this.onCandleComplete = onCandleComplete;
        for (String tf : timeframes) {
            indicatorValues.put(tf, new ConcurrentHashMap<>());
            strategies.put(tf, new Strategy(indicatorConfig));
        }
    }

    public void update(double price, java.time.LocalDateTime time) {
        for (String tf : timeframes) {
            updateTimeframe(tf, price, time);
        }
    }

    /**
     * Seeds historical candles into the Strategy object to warm up EMAs, MACD, etc.
     * This avoids firing the onCandleComplete callback for past data.
     */
    public void seedHistory(String tf, java.util.List<Candle> historicalCandles) {
        Strategy strategy = strategies.get(tf);
        if (strategy == null || historicalCandles == null || historicalCandles.isEmpty()) {
            return;
        }

        for (Candle c : historicalCandles) {
            // Feed into strategy to warm up the state (EMAs, MACD, etc).
            strategy.onNewCandle(c);

            // Set as the current active candle baseline
            currentCandles.put(tf, c);

            // SEED STABLE PRICE for Outlier Filter immediately from history
            lastStablePrices.put(tf, c.getClose());
        }
    }

    private void updateTimeframe(String tf, double price, java.time.LocalDateTime time) {
        java.time.LocalDateTime start = getIntervalStartTime(time, tf);
        Candle current = currentCandles.get(tf);

        if (current == null || start.isAfter(current.getTimestamp())) {
            // New Candle Started
            if (current != null) {
                lastStablePrices.put(tf, current.getClose());
                if (onCandleComplete != null) {
                    // Emit the completed candle immediately
                    onCandleComplete.onComplete(this.symbol, tf, current);
                }
            }

            // Removal of the redundant first-tick-seeding block in updateTimeframe
            // as it's now handled by seedHistory or will be naturally set on first candle
            // close.

            current = new Candle(price, price, price, price, 0, start);
            currentCandles.put(tf, current);

            // Update Strategy (New Candle Mode)
            strategies.get(tf).onNewCandle(current);
        } else {
            // Price Spike Filter: Ignore ticks that deviate more than 50% from the last
            // closed
            // candle's price.
            // This prevents "random fluctuations" (bad ticks) from permanently skewing
            // High/Low.
            Double stablePrice = lastStablePrices.get(tf);
            if (stablePrice != null && stablePrice > 0) {
                double deviation = Math.abs(price - stablePrice) / stablePrice;
                if (deviation > 0.5) {
                    System.err.println("SymbolState [" + symbol + " " + tf + "]: Ignored outlier tick " + price
                            + " (deviation: " + (deviation * 100) + "% from stable " + stablePrice + ")");
                    return;
                }
            }

            // Update existing candle
            current.setHigh(Math.max(current.getHigh(), price));
            current.setLow(Math.min(current.getLow(), price));
            current.setClose(price);

            // Update Strategy (Live Update Mode)
            strategies.get(tf).updateLastCandle(current);
        }

        // Pull indicators from Strategy into the indicatorValues map for
        // API/Broadcasting
        updateIndicatorMap(tf, current);
    }

    /**
     * Gets the active (current) candles for all timeframes.
     * Useful for forcing a flush of the final incomplete candles at session close.
     */
    public Map<String, java.util.List<Candle>> getActiveCandles() {
        Map<String, java.util.List<Candle>> result = new java.util.HashMap<>();
        for (String tf : timeframes) {
            Candle current = currentCandles.get(tf);
            java.util.List<Candle> list = new java.util.ArrayList<>();
            if (current != null) {
                list.add(current);
            }
            result.put(tf, list);
        }
        return result;
    }

    private void updateIndicatorMap(String tf, Candle current) {
        Strategy strategy = strategies.get(tf);
        Map<String, Double> values = indicatorValues.get(tf);

        // Populate indicators using a safe put method for ConcurrentHashMap
        safePut(values, "EMA15", strategy.getEma15());
        safePut(values, "EMA15_5", strategy.getEma15_5());
        safePut(values, "EMA15_15", strategy.getEma15_15());
        safePut(values, "EMA15_15_15", strategy.getEma15_15_15());
        safePut(values, "EMA15_15_15_15", strategy.getEma15_15_15_15());
        safePut(values, "EMA15_15_15_15_30", strategy.getEma15_15_15_15_30());

        safePut(values, "EMA50", current.getEma50());
        safePut(values, "EMA100", current.getEma100());
        safePut(values, "MACD", current.getMacdLine());
        safePut(values, "SIGNAL", current.getSignalLine());
        safePut(values, "MACD5", current.getMacd5());
        safePut(values, "MACD55", current.getMacd55());
        safePut(values, "EMA5", current.getEma15_15_15_15_30());
        safePut(values, "ST_MA", current.getStMa());
        safePut(values, "ST_UP", current.getStUp());
        safePut(values, "ST_DN", current.getStDn());
        safePut(values, "ST_TREND", (double) (current.getStTrend() != null ? current.getStTrend() : 0));
    }

    private void safePut(Map<String, Double> map, String key, Double value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private java.time.LocalDateTime getIntervalStartTime(java.time.LocalDateTime time, String interval) {
        int minutes = 1;
        switch (interval) {
            case "1min":
                minutes = 1;
                break;
            case "3min":
                minutes = 3;
                break;
            case "5min":
                minutes = 5;
                break;
            case "15min":
                minutes = 15;
                break;
            default:
                minutes = 1;
        }
        int currentMinute = time.getMinute() + (time.getHour() * 60);
        int candleStartTotalMinutes = (currentMinute / minutes) * minutes;

        int h = candleStartTotalMinutes / 60;
        int m = candleStartTotalMinutes % 60;
        return time.withHour(h).withMinute(m).withSecond(0).withNano(0);
    }

    public String getSymbol() {
        return symbol;
    }

    public Map<String, Candle> getCurrentCandles() {
        return currentCandles;
    }

    public Map<String, Map<String, Double>> getIndicatorValues() {
        return indicatorValues;
    }
}
