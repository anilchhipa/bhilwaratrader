package com.algo.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the state (Candles & Indicators) for a single symbol across multiple
 * timeframes.
 */
public class SymbolState {
    private final String symbol;
    private final String[] timeframes = { "1min", "3min", "5min", "10min", "15min", "30min", "1h" };

    // Timeframe -> Candle
    private final Map<String, Candle> currentCandles = new ConcurrentHashMap<>();

    // Timeframe -> (IndicatorName -> Value)
    private final Map<String, Map<String, Double>> indicatorValues = new ConcurrentHashMap<>();

    // Timeframe -> Strategy (Original Desktop Logic)
    private final Map<String, Strategy> strategies = new ConcurrentHashMap<>();

    // Timeframe -> List of candles generated during the current session (for
    // flushing)
    private final Map<String, java.util.List<Candle>> sessionCandles = new ConcurrentHashMap<>();

    public SymbolState(String symbol) {
        this.symbol = symbol;
        for (String tf : timeframes) {
            indicatorValues.put(tf, new ConcurrentHashMap<>());
            strategies.put(tf, new Strategy(15, 7, 3.0));
            sessionCandles.put(tf, java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
        }
    }

    public void update(double price, java.time.LocalDateTime time) {
        for (String tf : timeframes) {
            updateTimeframe(tf, price, time);
        }
    }

    private void updateTimeframe(String tf, double price, java.time.LocalDateTime time) {
        java.time.LocalDateTime start = getIntervalStartTime(time, tf);
        Candle current = currentCandles.get(tf);

        if (current == null || start.isAfter(current.getTimestamp())) {
            // New Candle Started
            if (current != null) {
                // Before starting a new candle, add the previous one to session collection
                sessionCandles.get(tf).add(current);
            }

            current = new Candle(price, price, price, price, 0, start);
            currentCandles.put(tf, current);

            // Update Strategy (New Candle Mode)
            strategies.get(tf).onNewCandle(current);
        } else {
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
     * Gets and clears the session candles for flushing to DB.
     * Note: This includes the current incomplete candle if desired,
     * but we usually only flush closed candles or all at session end.
     */
    public Map<String, java.util.List<Candle>> getAndClearSessionCandles() {
        Map<String, java.util.List<Candle>> result = new java.util.HashMap<>();
        for (String tf : timeframes) {
            java.util.List<Candle> candles = sessionCandles.get(tf);
            synchronized (candles) {
                // Add the latest (possibly incomplete) candle as well if it exists
                Candle current = currentCandles.get(tf);
                java.util.List<Candle> toFlush = new java.util.ArrayList<>(candles);
                if (current != null && !toFlush.contains(current)) {
                    toFlush.add(current);
                }
                result.put(tf, toFlush);
                candles.clear();
            }
        }
        return result;
    }

    private void updateIndicatorMap(String tf, Candle current) {
        Strategy strategy = strategies.get(tf);
        Map<String, Double> values = indicatorValues.get(tf);

        // Populate cascaded EMAs
        values.put("EMA15", strategy.getEma15());
        values.put("EMA15_5", strategy.getEma15_5());
        values.put("EMA15_15", strategy.getEma15_15());
        values.put("EMA15_15_15", strategy.getEma15_15_15());
        values.put("EMA15_30", strategy.getEma15_15_15_30());

        // Populate additional indicators
        values.put("EMA50", strategy.getEma15()); // Note: Strategy seems to use ema15 field internally differently?
                                                  // Checking getEma50() would be better but Strategy doesn't have it as
                                                  // a direct getter for all.
                                                  // Actually Strategy has fields ema50, ema100. Let's stick to what's
                                                  // in populateCandleIndicators

        values.put("EMA50", current.getEma50());
        values.put("EMA100", current.getEma100());
        values.put("MACD", current.getMacdLine());
        values.put("SIGNAL", current.getSignalLine());
        values.put("MACD5", current.getMacd5());
        values.put("MACD55", current.getMacd55());
        values.put("ST_MA", current.getStMa());
        values.put("ST_UP", current.getStUp());
        values.put("ST_DN", current.getStDn());
        values.put("ST_TREND", (double) (current.getStTrend() != null ? current.getStTrend() : 0));
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
            case "10min":
                minutes = 10;
                break;
            case "15min":
                minutes = 15;
                break;
            case "30min":
                minutes = 30;
                break;
            case "1h":
                minutes = 60;
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
