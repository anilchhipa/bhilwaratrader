package com.algo.service;

import com.algo.core.Candle;
import com.algo.data.ZerodhaClient;
import com.algo.data.TimescaleRepository;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for loading and syncing historical data to TimescaleDB.
 * Optimized for targeted timeframe syncing and thread-safety.
 */
public class HistoricalDataService {
    private final ZerodhaClient zerodhaClient;
    private final TimescaleRepository repository;
    private final com.algo.core.IndicatorConfig indicatorConfig;

    // Track ongoing syncs to prevent redundant parallel API calls for the same
    // symbol/timeframe
    private final Set<String> syncingSyncs = Collections.synchronizedSet(new HashSet<>());

    private static final Map<String, Integer> INTERVAL_MINUTES = new HashMap<>();
    static {
        INTERVAL_MINUTES.put("1min", 1);
        INTERVAL_MINUTES.put("3min", 3);
        INTERVAL_MINUTES.put("5min", 5);
        INTERVAL_MINUTES.put("15min", 15);
    }

    private static final String[] TIMEFRAMES = { "1min", "3min", "5min", "15min" };

    public HistoricalDataService(ZerodhaClient zerodhaClient, TimescaleRepository repository,
            com.algo.core.IndicatorConfig indicatorConfig) {
        this.zerodhaClient = zerodhaClient;
        this.repository = repository;
        this.indicatorConfig = indicatorConfig;
    }

    /**
     * Load historical data for a symbol. If targetTimeframe is null, syncs all.
     */
    public void loadInitialData(String symbol, String token) {
        loadInitialData(symbol, token, null);
    }

    public void loadInitialData(String symbol, String token, String targetTimeframe) {
        String lockKey = symbol + "_" + (targetTimeframe != null ? targetTimeframe : "ALL");
        if (!syncingSyncs.add(lockKey))
            return;

        try {
            // Require 2000 candles minimum to ensure cascaded EMAs are properly
            // warmed up. The deepest EMA cascade (6 levels) needs ~3000+ candles.
            int requiredCount = 2000;
            System.out.println("Checking historical data for " + symbol
                    + (targetTimeframe != null ? " [" + targetTimeframe + "]" : ""));

            for (String tf : TIMEFRAMES) {
                if (targetTimeframe != null && !targetTimeframe.equals(tf))
                    continue;

                if (shouldFetch(symbol, tf, requiredCount)) {
                    // 1min : 60 days → ~4500 candles (cascaded EMAs need 3000+)
                    // 3min : 30 days → ~1500 candles
                    // 5min : 60 days → ~2250 candles
                    // 15min: 60 days → ~750 candles
                    int days = 60; // default for 1min and 15min
                    if (tf.equals("3min"))
                        days = 30;
                    if (tf.equals("5min"))
                        days = 60;
                    fetchAndSave(symbol, token, tf, days);
                }
            }
        } finally {
            syncingSyncs.remove(lockKey);
        }
    }

    private boolean shouldFetch(String symbol, String tf, int requiredCount) {
        int existingCount = repository.getCandleCount(symbol, tf);
        if (existingCount < requiredCount) {
            return true;
        }
        System.out.println("  [" + tf + "] Already has " + existingCount + " candles. Skipping initial fetch.");
        return false;
    }

    private void fetchAndSave(String symbol, String token, String timeframe, int days) {
        String zerodhaInterval = mapToZerodhaInterval(timeframe);
        if (zerodhaInterval == null)
            return;

        try {
            System.out.println("  [" + timeframe + "] Fetching " + days + " days from Zerodha API...");
            String exchange = resolveExchange(symbol);

            List<Candle> candles = zerodhaClient.fetchHistory(token, zerodhaInterval, exchange, days);

            if (candles != null && !candles.isEmpty()) {
                calculateAndPopulateIndicators(candles, null, null);
                int saved = repository.saveCandles(candles, symbol, timeframe);
                System.out.println("  [" + timeframe + "] Saved " + saved + " candles to TimescaleDB");
            }

            // Rate limit buffer
            Thread.sleep(500);
        } catch (Exception e) {
            System.err.println("  [" + timeframe + "] Error: " + e.getMessage());
        }
    }

    private String resolveExchange(String symbol) {
        if (symbol.startsWith("SENSEX") || symbol.startsWith("BANKEX"))
            return "BFO";
        if (symbol.startsWith("NIFTY") || symbol.startsWith("BANKNIFTY") || symbol.startsWith("FINNIFTY")
                || symbol.endsWith("CE") || symbol.endsWith("PE") || symbol.endsWith("FUT"))
            return "NFO";
        if (symbol.startsWith("MCX"))
            return "MCX";
        if (symbol.startsWith("BSE:"))
            return "BSE";
        return "NSE";
    }

    public void syncData(String symbol, String token, String timeframe) {
        String lockKey = symbol + "_" + timeframe + "_SYNC";
        if (!syncingSyncs.add(lockKey))
            return;

        try {
            String zerodhaInterval = mapToZerodhaInterval(timeframe);
            if (zerodhaInterval == null)
                return;

            LocalDateTime lastTime = repository.getLastCandleTime(symbol, timeframe);
            if (lastTime == null) {
                loadInitialData(symbol, token, timeframe);
                return;
            }

            LocalDateTime targetTime = LocalDateTime.now();
            if (!lastTime.isBefore(targetTime.minusMinutes(1))) {
                return; // Already fresh
            }

            String exchange = resolveExchange(symbol);
            List<Candle> newCandles = zerodhaClient.fetchHistory(token, zerodhaInterval, lastTime, targetTime,
                    exchange);

            if (newCandles != null && !newCandles.isEmpty()) {
                calculateAndPopulateIndicators(newCandles, symbol, timeframe);
                int saved = repository.saveCandles(newCandles, symbol, timeframe);
                System.out.println("Synced " + saved + " new candles for " + symbol + " (" + timeframe + ")");
            }
        } catch (Exception e) {
            System.err.println("Error syncing " + symbol + " (" + timeframe + "): " + e.getMessage());
        } finally {
            syncingSyncs.remove(lockKey);
        }
    }

    private void calculateAndPopulateIndicators(List<Candle> candles, String symbol, String timeframe) {
        if (candles == null || candles.isEmpty())
            return;

        com.algo.core.Strategy strategy = new com.algo.core.Strategy(indicatorConfig);

        // Warm up if we have prior context
        if (symbol != null && timeframe != null) {
            List<Candle> priorHistory = repository.getLatestCandles(symbol, timeframe, 3000);
            if (priorHistory != null) {
                for (Candle c : priorHistory)
                    strategy.onNewCandle(c);
            }
        }

        for (Candle candle : candles) {
            strategy.onNewCandle(candle);
        }
    }

    public void syncAllSymbolsToRedis(com.algo.core.Engine engine) {
        for (String symbol : engine.getSymbolStates().keySet()) {
            com.algo.core.SymbolState state = engine.getState(symbol);
            if (state == null)
                continue;

            for (String tf : TIMEFRAMES) {
                List<Candle> history = repository.getLatestCandles(symbol, tf, 3000);
                if (history != null && !history.isEmpty()) {
                    state.seedHistory(tf, history);
                    // Recalculate indicators during seed to ensure consistency
                    calculateAndPopulateIndicators(history, null, null);
                    repository.saveCandles(history, symbol, tf);
                }
            }
        }
    }

    private String mapToZerodhaInterval(String timeframe) {
        switch (timeframe.toLowerCase()) {
            case "1min":
                return "minute";
            case "3min":
                return "3minute";
            case "5min":
                return "5minute";
            case "15min":
                return "15minute";
            default:
                return null;
        }
    }
}
