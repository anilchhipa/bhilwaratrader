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
    // Volume candles (10kV, 50kV) commented out — they are rebuilt from 1-min bars
    // on every startup which causes live vs after-market discrepancies. Re-add when
    // a proper tick-accurate persistence layer is in place.
    public static final String[] TIMEFRAMES = { "1min", "3min", "5min" /* , "10kV", "50kV" */ };
    private final IndicatorConfig indicatorConfig;

    // Timeframe -> List of historical Candles (The "Hot Buffer")
    private final Map<String, java.util.List<Candle>> historyBuffers = new ConcurrentHashMap<>();

    // Per-timeframe candle limits — tuned so each timeframe holds ~6-10 trading days
    // in RAM without exhausting heap memory.
    private static final java.util.Map<String, Integer> MAX_HOT_CANDLES = java.util.Map.of(
        "1min",  2500,  // ~6.6 trading days (375 candles/day)
        "3min",  1200,  // ~9.6 trading days (125 candles/day)
        "5min",  800    // ~10.6 trading days (75 candles/day)
    );

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

    // Cache for interval start times to avoid new LocalDateTime allocations per tick
    private final Map<String, java.time.LocalDateTime> nextExpectedIntervals = new ConcurrentHashMap<>();
    private final Map<String, java.time.LocalDateTime> currentIntervalStarts = new ConcurrentHashMap<>();

    // Timeframe -> Last Trend confirmed at candle close
    private final Map<String, Integer> confirmedTrendMap1 = new ConcurrentHashMap<>();
    private final Map<String, Integer> confirmedTrendMap2 = new ConcurrentHashMap<>();


    // ── API Optimization Caches ──────────────────────────────────────────────
    // Updated lock-free on every tick. Prevents TradingController from allocating
    // thousands of HashMaps on every /matrix or /monitor/all polling request.
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> stTrendByTfCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> stTrend2ByTfCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> emaBiasByTfCache = new java.util.concurrent.ConcurrentHashMap<>();

    @FunctionalInterface
    public interface CandleCompleteListener {
        void onComplete(String symbol, String timeframe, Candle candle);
    }

    @FunctionalInterface
    public interface TrendFlipListener {
        void onFlip(String symbol, String timeframe, int trend, boolean isSecondary);
    }

    // Callback when a candle is completed
    private CandleCompleteListener onCandleComplete;
    private TrendFlipListener onTrendFlip;

    public SymbolState(String symbol, CandleCompleteListener onCandleComplete, TrendFlipListener onTrendFlip, IndicatorConfig indicatorConfig) {
        this.symbol = symbol;
        this.onCandleComplete = onCandleComplete;
        this.onTrendFlip = onTrendFlip;
        this.indicatorConfig = indicatorConfig;
        

        
        for (String tf : TIMEFRAMES) {
            indicatorValues.put(tf, new ConcurrentHashMap<>());
            strategies.put(tf, new Strategy(indicatorConfig, tf));
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
    public int update(double price, double quantity, java.time.LocalDateTime time) {
        int dirty = 0;
        
        long epochMillis = System.currentTimeMillis();
        

        
        for (int i = 0; i < TIMEFRAMES.length; i++) {
            String tf = TIMEFRAMES[i];
            ReentrantLock lock = tfLocks.get(tf);
            lock.lock();
            try {
                if (updateTimeframe(tf, price, quantity, time)) {
                    dirty |= (1 << i);
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



        // Warm up Supertrend MTF Heikin Ashi on all historical candles so chart history and live stream are aligned
        if (strategy.getStHa() != null) {
            java.util.List<com.algo.indicators.SupertrendMtfHeikinAshi.SupertrendPoint> haPoints =
                    com.algo.indicators.SupertrendMtfHeikinAshi.calculate(historicalCandles, tf);
            if (haPoints != null && !haPoints.isEmpty()) {
                for (int i = 0; i < historicalCandles.size() && i < haPoints.size(); i++) {
                    Candle c = historicalCandles.get(i);
                    com.algo.indicators.SupertrendMtfHeikinAshi.SupertrendPoint pt = haPoints.get(i);
                    c.setHaTrailingSl(pt.trailingSl);
                    c.setHaTrend(pt.trend);
                    c.setHtfTrailingSl(pt.htfTrailingSl);
                    c.setHtfTrend(pt.htfTrend);
                }
                com.algo.indicators.SupertrendMtfHeikinAshi.SupertrendPoint lastHaPt = haPoints.get(haPoints.size() - 1);
                // Prime the streaming instance directly without re-executing 50,000 updates and snapshots
                strategy.getStHa().adoptState(lastHaPt, haPoints);
            }
        }


        // Find the most recent candle that has indicator data, capturing its index
        // in a single backward pass so we don't need a second O(N) indexOf() call later.
        Candle bestPrimal = null;
        int bestPrimalIdx = -1;
        for (int i = historicalCandles.size() - 1; i >= 0; i--) {
            Candle c = historicalCandles.get(i);
            if (c.getEma15() != null && c.getEma15() > 0) {
                bestPrimal = c;
                bestPrimalIdx = i;
                break;
            }
        }

        if (bestPrimal != null) {
            // INSTANT SYNC: Adopt the state from the DB directly.
            // This ensures a perfectly smooth line with zero "cold start" delay.
            strategy.adoptState(bestPrimal);



            logger.info("SymbolState [{} {}]: Primed strategy from DB baseline at {}", symbol, tf,
                    bestPrimal.getTimestamp());

            int startIndex = bestPrimalIdx + 1;
            for (int i = startIndex; i < historicalCandles.size(); i++) {
                Candle c = historicalCandles.get(i);
                Candle prev = i > 0 ? historicalCandles.get(i - 1) : null;
                strategy.onNewCandle(prev, c, 0);
            }
        } else {
            // Fallback: Slow warm-up by re-calculating everything
            logger.info("SymbolState [{} {}]: No indicator baseline in DB. Re-calculating from {} candles.", symbol, tf,
                    historicalCandles.size());
            for (int i = 0; i < historicalCandles.size(); i++) {
                Candle c = historicalCandles.get(i);
                Candle prev = i > 0 ? historicalCandles.get(i - 1) : null;
                strategy.onNewCandle(prev, c, 0);
            }
        }

        // Set the very last candle as the current baseline for upcoming live ticks
        Candle last = historicalCandles.get(historicalCandles.size() - 1);
        currentCandles.put(tf, last);
        lastStablePrices.put(tf, last.getClose());

        currentIntervalStarts.put(tf, last.getTimestamp());
        nextExpectedIntervals.put(tf, last.getTimestamp().plusMinutes(getMinutesForInterval(tf)));

        // CRITICAL: Populate the indicator map immediately so the Dashboard is ready!
        updateIndicatorMap(tf, last);

        // Find when the trend last flipped for this timeframe
        Integer currentTrend1 = last.getStTrend();
        Integer currentTrend2 = last.getStTrend2();
        if (currentTrend1 != null) {
            confirmedTrendMap1.put(tf, currentTrend1);
        }
        if (currentTrend2 != null) {
            confirmedTrendMap2.put(tf, currentTrend2);
        }

        // We use confirmedTrendMap1 for the primary timestamp tracking in the UI
        if (currentTrend1 != null) {
            for (int i = historicalCandles.size() - 2; i >= 0; i--) {
                Candle prev = historicalCandles.get(i);
                if (prev.getStTrend() != null && !prev.getStTrend().equals(currentTrend1)) {
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
        // Hydrate the RAM buffer (bounded to MAX_HOT_CANDLES to protect JVM heap)
        java.util.List<Candle> buffer = historyBuffers.get(tf);
        if (buffer != null) {
            synchronized (buffer) {
                buffer.clear();
                int limit = MAX_HOT_CANDLES.getOrDefault(tf, 2500);
                int start = Math.max(0, historicalCandles.size() - limit);
                buffer.addAll(historicalCandles.subList(start, historicalCandles.size()));
            }
        }


    }

    /** Returns true if any state changed (price/indicator updated). */
    private boolean updateTimeframe(String tf, double price, double quantity, java.time.LocalDateTime time) {
        Candle current = currentCandles.get(tf);
        // Volume candle branch disabled — see TIMEFRAMES comment above.
        // boolean isVolumeCandle = tf.endsWith("V");
        boolean newCandleStarted = false;
        java.time.LocalDateTime start = time;

        // if (isVolumeCandle) {
        //     double targetVol = Double.parseDouble(tf.replace("kV", "")) * 1000;
        //     if (current == null || current.getVolume() >= targetVol) {
        //         newCandleStarted = true;
        //         start = time;
        //     } else {
        //         start = current.getTimestamp();
        //     }
        // } else {
            start = getIntervalStartTimeCached(time, tf);
            if (current == null || start.isAfter(current.getTimestamp())) {
                newCandleStarted = true;
            }
        // }

        if (newCandleStarted) {
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

                        // Batch trim the head with a single System.arraycopy via subList().clear()
                        // rather than repeated O(N) buffer.remove(0) calls in a loop.
                        int limit = MAX_HOT_CANDLES.getOrDefault(tf, 1000);
                        int excess = buffer.size() - limit;
                        if (excess > 0) {
                            buffer.subList(0, excess).clear();
                        }
                    }
                }



                if (onCandleComplete != null) {
                    onCandleComplete.onComplete(this.symbol, tf, current);
                }
            }

            Candle newCandle = new Candle(price, price, price, price, quantity, start);

            strategies.get(tf).onNewCandle(current, newCandle, 0);

            current = newCandle;
            currentCandles.put(tf, current);
        } else {
            if (!isSensexOption()) {
                Double stablePrice = lastStablePrices.get(tf);
                if (stablePrice != null && stablePrice > 0) {
                    double deviation = Math.abs(price - stablePrice) / stablePrice;
                    if (deviation > 0.5) {
                        logger.error("SymbolState [{} {}]: Ignored outlier tick {} (deviation: {}% from stable {})", symbol,
                                tf, price, (deviation * 100), stablePrice);
                        return false;
                    }
                }
            }

            // Only update if price actually changed (skip identical tick noise)
            boolean priceChanged = (price != current.getClose());
            if (!priceChanged && quantity == 0)
                return false;

            current.setHigh(Math.max(current.getHigh(), price));
            current.setLow(Math.min(current.getLow(), price));
            current.setClose(price);
            current.setVolume(current.getVolume() + quantity);

            // LIVE UPDATE: Update indicators for the current candle on every price change
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
        for (String tf : TIMEFRAMES) {
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

        safePut(values, "stMa", current.getStMa());
        safePut(values, "stUp", current.getStUp());
        safePut(values, "stDn", current.getStDn());
        safePut(values, "stTrend", current.getStTrend());

        // SuperTrend 2 (ST100)
        safePut(values, "stMa2", current.getStMa2());
        safePut(values, "stUp2", current.getStUp2());
        safePut(values, "stDn2", current.getStDn2());
        safePut(values, "stTrend2", current.getStTrend2());

        // ATR Volatility
        safePut(values, "atr", current.getAtr());

        // SuperTrend MTF Heikin Ashi
        safePut(values, "haTrailingSl", current.getHaTrailingSl());
        safePut(values, "haTrend", current.getHaTrend());
        safePut(values, "htfTrailingSl", current.getHtfTrailingSl());
        safePut(values, "htfTrend", current.getHtfTrend());

        
        // Update Matrix API Caches (Zero-allocation path for /matrix endpoint)
        if (current.getStTrend() != null) stTrendByTfCache.put(tf, current.getStTrend());
        if (current.getStTrend2() != null) stTrend2ByTfCache.put(tf, current.getStTrend2());
        
        Double e15 = strategy.getEma15();
        Double e15_5 = strategy.getEma15_5();
        emaBiasByTfCache.put(tf, (e15 != null && e15_5 != null) ? (e15 > e15_5 ? 1 : -1) : 0);
    }

    /**
     * Primitive-aware safePut to minimize map mutations and object retention.
     */
    private void safePut(Map<String, Double> map, String key, Double value) {
        if (value == null)
            return;
        Double existing = map.get(key);
        // Only put if the value is different (avoids ConcurrentHashMap internal
        // locking/volatile writes)
        if (existing == null || !existing.equals(value)) {
            map.put(key, value);
        }
    }

    private void safePut(Map<String, Double> map, String key, Integer value) {
        if (value == null)
            return;
        double dv = value.doubleValue();
        Double existing = map.get(key);
        if (existing == null || existing != dv) {
            map.put(key, dv);
        }
    }

    private void safePut(Map<String, Double> map, String key, double value) {
        Double existing = map.get(key);
        if (existing == null || existing != value) {
            map.put(key, value);
        }
    }

    /**
     * Optimized interval start time calculation with caching to avoid heavy
     * LocalDateTime allocations.
     */
    private java.time.LocalDateTime getIntervalStartTimeCached(java.time.LocalDateTime time, String interval) {
        java.time.LocalDateTime cachedNext = nextExpectedIntervals.get(interval);
        if (cachedNext != null && time.isBefore(cachedNext)) {
            return currentIntervalStarts.get(interval);
        }

        java.time.LocalDateTime start = getIntervalStartTime(time, interval);
        int mins = getMinutesForInterval(interval);
        currentIntervalStarts.put(interval, start);
        nextExpectedIntervals.put(interval, start.plusMinutes(mins));
        return start;
    }

    private int getMinutesForInterval(String interval) {
        switch (interval) {
            case "1min":
                return 1;
            case "3min":
                return 3;
            case "5min":
                return 5;
            default:
                return 1;
        }
    }

    private java.time.LocalDateTime getIntervalStartTime(java.time.LocalDateTime time, String interval) {
        int minutes = getMinutesForInterval(interval);
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

    public java.util.Map<String, Integer> getStTrendByTfCache() {
        return stTrendByTfCache;
    }

    public java.util.Map<String, Integer> getStTrend2ByTfCache() {
        return stTrend2ByTfCache;
    }

    public java.util.Map<String, Integer> getEmaBiasByTfCache() {
        return emaBiasByTfCache;
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

    public boolean isSensexOption() {
        if (symbol == null) return false;
        String s = symbol.trim().toUpperCase();
        if (s.equals("SENSEX") || s.equals("NIFTY 50") || s.equals("BANKEX")) {
            return false;
        }
        return s.endsWith("CE") || s.endsWith("PE")
                || s.endsWith("CALL") || s.endsWith("PUT")
                || s.contains(" CE") || s.contains(" PE")
                || (s.startsWith("SENSEX") && (s.contains("CE") || s.contains("PE")));
    }

}
