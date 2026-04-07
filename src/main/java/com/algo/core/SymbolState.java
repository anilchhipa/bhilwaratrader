package com.algo.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the state (Candles & Indicators) for a single symbol across multiple
 * timeframes.
 */
public class SymbolState {
    private static final Logger logger = LoggerFactory.getLogger(SymbolState.class);
    private final String symbol;
    private final String[] timeframes = { "1min", "3min", "5min", "15min" };

    // Timeframe -> List of historical Candles (The "Hot Buffer")
    private final Map<String, java.util.List<Candle>> historyBuffers = new ConcurrentHashMap<>();
    private static final int MAX_HOT_CANDLES = 1000;

    // Per-timeframe locks for fine-grained concurrency (avoids one global
    // synchronized)
    private final Map<String, ReentrantLock> tfLocks = new ConcurrentHashMap<>();

    // Timeframe -> Candle
    private final Map<String, Candle> currentCandles = new ConcurrentHashMap<>();

    // Keep track of the last stable price from the last closed candle for outlier
    // detection
    private final Map<String, Double> lastStablePrices = new ConcurrentHashMap<>();

    // Timeframe -> (IndicatorName -> Value)
    private final Map<String, Map<String, Double>> indicatorValues = new ConcurrentHashMap<>();

    // Timeframe -> Strategy (Original Desktop Logic)
    private final Map<String, Strategy> strategies = new ConcurrentHashMap<>();

    // Timeframe -> Last Trend Flip Time
    private final Map<String, java.time.LocalDateTime> lastFlipTimes = new ConcurrentHashMap<>();

    // Timeframe -> Last Trend confirmed at candle close
    private final Map<String, Integer> confirmedTrendMap = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface CandleCompleteListener {
        void onComplete(String symbol, String timeframe, Candle candle);
    }

    @FunctionalInterface
    public interface TrendFlipListener {
        void onFlip(String symbol, String timeframe, int trend);
    }

    // Callback when a candle is completed
    private CandleCompleteListener onCandleComplete;
    private TrendFlipListener onTrendFlip;

    public SymbolState(String symbol, CandleCompleteListener onCandleComplete, TrendFlipListener onTrendFlip,
            IndicatorConfig indicatorConfig) {
        this.symbol = symbol;
        this.onCandleComplete = onCandleComplete;
        this.onTrendFlip = onTrendFlip;
        for (String tf : timeframes) {
            indicatorValues.put(tf, new ConcurrentHashMap<>());
            strategies.put(tf, new Strategy(indicatorConfig));
            historyBuffers.put(tf, java.util.Collections.synchronizedList(new java.util.ArrayList<>()));
            tfLocks.put(tf, new ReentrantLock());
        }
    }

    /**
     * Process a live tick. Uses per-timeframe locking instead of a single
     * class-level synchronized block, allowing parallel processing across symbols.
     * Returns the set of timeframes that were actually modified (for selective
     * broadcast).
     */
    public java.util.Set<String> update(double price, double delta, java.time.LocalDateTime time) {
        java.util.Set<String> dirty = new java.util.HashSet<>(4);
        for (String tf : timeframes) {
            ReentrantLock lock = tfLocks.get(tf);
            lock.lock();
            try {
                if (updateTimeframe(tf, price, delta, time)) {
                    dirty.add(tf);
                }
            } finally {
                lock.unlock();
            }
        }
        return dirty;
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

        // Find the most recent candle that has indicator data
        Candle bestPrimal = null;
        for (int i = historicalCandles.size() - 1; i >= 0; i--) {
            Candle c = historicalCandles.get(i);
            if (c.getEma15() != null && c.getEma15() > 0) {
                bestPrimal = c;
                break;
            }
        }

        if (bestPrimal != null) {
            // INSTANT SYNC: Adopt the state from the DB directly.
            // This ensures a perfectly smooth line with zero "cold start" delay.
            strategy.adoptState(bestPrimal);
            logger.info("SymbolState [{} {}]: Primed strategy from DB baseline at {}", symbol, tf,
                    bestPrimal.getTimestamp());

            int startIndex = historicalCandles.indexOf(bestPrimal) + 1;
            for (int i = startIndex; i < historicalCandles.size(); i++) {
                Candle prev = i > 0 ? historicalCandles.get(i - 1) : null;
                strategy.onNewCandle(prev, historicalCandles.get(i), 0); // 0 = Neutral/No Filter during initial history
                                                                         // seed
            }
        } else {
            // Fallback: Slow warm-up by re-calculating everything
            logger.info("SymbolState [{} {}]: No indicator baseline in DB. Re-calculating from {} candles.", symbol, tf,
                    historicalCandles.size());
            for (int i = 0; i < historicalCandles.size(); i++) {
                Candle prev = i > 0 ? historicalCandles.get(i - 1) : null;
                strategy.onNewCandle(prev, historicalCandles.get(i), 0);
            }
        }

        // Set the very last candle as the current baseline for upcoming live ticks
        Candle last = historicalCandles.get(historicalCandles.size() - 1);
        currentCandles.put(tf, last);
        lastStablePrices.put(tf, last.getClose());

        // CRITICAL: Populate the indicator map immediately so the Dashboard is ready!
        updateIndicatorMap(tf, last);

        // Find when the trend last flipped for this timeframe
        Integer currentTrend = last.getStTrend();
        if (currentTrend != null) {
            confirmedTrendMap.put(tf, currentTrend);
            for (int i = historicalCandles.size() - 2; i >= 0; i--) {
                Candle prev = historicalCandles.get(i);
                if (prev.getStTrend() != null && !prev.getStTrend().equals(currentTrend)) {
                    // Trend changed at historicalCandles.get(i + 1)
                    lastFlipTimes.put(tf, historicalCandles.get(i + 1).getTimestamp());
                    break;
                }
            }
            if (!lastFlipTimes.containsKey(tf)) {
                // If no flip found in buffered history, use the oldest candle's time as start
                lastFlipTimes.put(tf, historicalCandles.get(0).getTimestamp());
            }
        }
        // Hydrate the RAM buffer
        java.util.List<Candle> buffer = historyBuffers.get(tf);
        if (buffer != null) {
            synchronized (buffer) {
                buffer.clear();
                buffer.addAll(historicalCandles);
            }
        }
    }

    /** Returns true if any state changed (price/indicator updated). */
    private boolean updateTimeframe(String tf, double price, double delta, java.time.LocalDateTime time) {
        java.time.LocalDateTime start = getIntervalStartTime(time, tf);
        Candle current = currentCandles.get(tf);

        if (current == null || start.isAfter(current.getTimestamp())) {
            // New Candle Started
            if (current != null) {
                lastStablePrices.put(tf, current.getClose());

                // ADD COMPLETED CANDLE TO RAM BUFFER (Prevent duplicates during live/history
                // transition)
                java.util.List<Candle> buffer = historyBuffers.get(tf);
                if (buffer != null) {
                    synchronized (buffer) {
                        if (buffer.isEmpty()
                                || current.getTimestamp().isAfter(buffer.get(buffer.size() - 1).getTimestamp())) {
                            buffer.add(current);
                        } else if (current.getTimestamp().equals(buffer.get(buffer.size() - 1).getTimestamp())) {
                            // Update the existing partial candle with the final completed state
                            buffer.set(buffer.size() - 1, current);
                        }

                        if (buffer.size() > MAX_HOT_CANDLES) {
                            buffer.subList(0, buffer.size() - MAX_HOT_CANDLES).clear();
                        }
                    }
                }

                if (onCandleComplete != null) {
                    onCandleComplete.onComplete(this.symbol, tf, current);
                }
            }

            double tickQty = Math.abs(delta);
            Candle newCandle = new Candle(price, price, price, price, tickQty, start);
            newCandle.setDelta(delta); // First tick of new candle

            Strategy macroStrategy = strategies.get("15min");
            int macroBias = tf.equals("15min") ? 999 : ((macroStrategy != null) ? macroStrategy.getBias() : 0);
            strategies.get(tf).onNewCandle(current, newCandle, macroBias);

            // Confirmed Trend Flip Detection (only on candle close)
            Integer trendAtClose = current.getStTrend();
            Integer lastConfirmed = confirmedTrendMap.get(tf);
            if (trendAtClose != null && lastConfirmed != null && !trendAtClose.equals(lastConfirmed)) {
                lastFlipTimes.put(tf, current.getTimestamp());
                // Alert for mobile notification
                if (onTrendFlip != null) {
                    onTrendFlip.onFlip(this.symbol, tf, trendAtClose);
                }
            }
            if (trendAtClose != null) {
                confirmedTrendMap.put(tf, trendAtClose);
            }

            current = newCandle;
            currentCandles.put(tf, current);
        } else {
            Double stablePrice = lastStablePrices.get(tf);
            if (stablePrice != null && stablePrice > 0) {
                double deviation = Math.abs(price - stablePrice) / stablePrice;
                if (deviation > 0.5) {
                    logger.error("SymbolState [{} {}]: Ignored outlier tick {} (deviation: {}% from stable {})", symbol,
                            tf, price, (deviation * 100), stablePrice);
                    return false;
                }
            }

            // Only update if price actually changed (skip identical tick noise)
            boolean priceChanged = (price != current.getClose());
            if (!priceChanged && delta == 0)
                return false;

            current.setHigh(Math.max(current.getHigh(), price));
            current.setLow(Math.min(current.getLow(), price));
            current.setClose(price);

            // Accumulate Volume and Delta per tick
            double tickQty = Math.abs(delta);
            current.setVolume(current.getVolume() + tickQty);
            current.setDelta(current.getDelta() + delta);

            strategies.get(tf).updateLastCandle(current);
        }

        updateIndicatorMap(tf, current);
        return true;
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
        // Keys must exactly match Candle.java field names for frontend consistency
        safePut(values, "ema15", strategy.getEma15());
        safePut(values, "ema15_5", strategy.getEma15_5());
        safePut(values, "ema15_15", strategy.getEma15_15());
        safePut(values, "ema15_15_15", strategy.getEma15_15_15());
        safePut(values, "ema15_15_15_15", strategy.getEma15_15_15_15());
        safePut(values, "ema15_15_15_15_30", strategy.getEma15_15_15_15_30());

        safePut(values, "stMa", current.getStMa());
        safePut(values, "stUp", current.getStUp());
        safePut(values, "stDn", current.getStDn());
        safePut(values, "stTrend", (double) (current.getStTrend() != null ? current.getStTrend() : 0));

        // SuperTrend 2 (ST100)
        safePut(values, "stMa2", current.getStMa2());
        safePut(values, "stUp2", current.getStUp2());
        safePut(values, "stDn2", current.getStDn2());
        safePut(values, "stTrend2", (double) (current.getStTrend2() != null ? current.getStTrend2() : 0));
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

    public Map<String, java.time.LocalDateTime> getLastFlipTimes() {
        return lastFlipTimes;
    }

    /**
     * The heart of "Hot Charts": Returns the full history for a timeframe directly
     * from RAM.
     */
    public java.util.List<Candle> getHistory(String timeframe) {
        java.util.List<Candle> buffer = historyBuffers.get(timeframe);
        if (buffer == null)
            return java.util.Collections.emptyList();
        synchronized (buffer) {
            return new java.util.ArrayList<>(buffer);
        }
    }
}
