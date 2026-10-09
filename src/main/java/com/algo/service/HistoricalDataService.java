package com.algo.service;

import com.algo.core.Candle;
import com.algo.core.MarketUtils;
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
    }

    // "10kV" and "50kV" removed from sync loop — volume candles are disabled.
    private static final String[] TIMEFRAMES = { "5min", "3min", "1min" /* , "10kV", "50kV" */ };

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
            boolean isOption = isOptionSymbol(symbol);
            System.out.println("Checking historical data for " + symbol
                    + (isOption ? " [OPTION — fetching all available candles]" : "")
                    + (targetTimeframe != null ? " [" + targetTimeframe + "]" : ""));

            for (String tf : TIMEFRAMES) {
                if (targetTimeframe != null && !targetTimeframe.equals(tf) && !isOption)
                    continue;

                if (isOption) {
                    // Options have a limited life (current expiry cycle).
                    // Always fetch ALL available candles — go back 100 days to cover the
                    // full expiry cycle and listing lifetime.
                    fetchAndSave(symbol, token, tf, 100);
                } else {
                    // Regular index/equity: tiered loading is sufficient
                    int requiredCount = 1000;
                    if (shouldFetch(symbol, tf, requiredCount)) {
                        // 1min : 7 days  → ~2600 candles
                        // 3min : 15 days → ~1800 candles
                        // 5min : 30 days → ~2250 candles
                        int days = 30;
                        if (tf.equals("1min"))
                            days = 7;
                        else if (tf.equals("3min"))
                            days = 15;

                        fetchAndSave(symbol, token, tf, days);
                    }
                }
            }
        } finally {
            syncingSyncs.remove(lockKey);
        }
    }

    /**
     * Returns true if the symbol is an options contract (CE/PE suffix or
     * exchange-prefixed BFO/NFO option pattern).
     */
    private boolean isOptionSymbol(String symbol) {
        if (symbol == null) return false;
        String s = symbol.trim().toUpperCase();
        return s.endsWith("CE") || s.endsWith("PE")
                || s.endsWith("CALL") || s.endsWith("PUT")
                || s.contains(" CE") || s.contains(" PE");
    }

    private boolean shouldFetch(String symbol, String tf, int requiredCount) {
        int existingCount = repository.getCandleCount(symbol, tf, requiredCount);
        if (existingCount < requiredCount) {
            return true;
        }
        // Even if count is sufficient, check if data is STALE (gap older than 1 day).
        // This handles the case where the app was offline for days/weeks.
        LocalDateTime lastCandle = repository.getLastCandleTime(symbol, tf);
        if (lastCandle == null || lastCandle.isBefore(LocalDateTime.now().minusDays(1))) {
            System.out.println("  [" + tf + "] Data is stale (last candle: " + lastCandle + "). Re-fetching...");
            return true;
        }
        System.out.println(
                "  [" + tf + "] Already has " + existingCount + " candles and data is fresh. Skipping initial fetch.");
        return false;
    }

    private void fetchAndSave(String symbol, String token, String timeframe, int days) {
        String zerodhaInterval = mapToZerodhaInterval(timeframe);
        if (zerodhaInterval == null)
            return;

        try {
            System.out.println("  [" + timeframe + "] Fetching " + days + " days from Zerodha API...");
            String exchange = resolveExchange(symbol);

            LocalDateTime targetTime = MarketUtils.getTargetSyncTime();
            LocalDateTime startTime = targetTime.minusDays(days);

            List<Candle> candles = zerodhaClient.fetchHistory(token, zerodhaInterval, startTime, targetTime,
                    exchange);

            if (candles != null && !candles.isEmpty()) {
                // Filter out any candles that fall outside standard market hours (e.g.
                // pre-market ghost data)
                candles.removeIf(c -> !MarketUtils.isValidCandleTime(c.getTimestamp()));

                if (!candles.isEmpty()) {
                    calculateAndPopulateIndicators(candles, symbol, timeframe);
                    int saved = repository.saveCandles(candles, symbol, timeframe);
                    System.out.println("  [" + timeframe + "] Saved " + saved + " candles to TimescaleDB");
                }
            }

            // Rate limit buffer
            Thread.sleep(500);
        } catch (Exception e) {
            System.err.println("  [" + timeframe + "] Error: " + e.getMessage());
        }
    }

    private String resolveExchange(String symbol) {
        if (symbol.equals("SENSEX") || symbol.equals("BANKEX") || symbol.startsWith("BSE:"))
            return "BSE";
        if (symbol.startsWith("SENSEX") || symbol.startsWith("BANKEX"))
            return "BFO";
        if (symbol.startsWith("NIFTY") || symbol.startsWith("BANKNIFTY") || symbol.startsWith("FINNIFTY")
                || symbol.endsWith("CE") || symbol.endsWith("PE") || symbol.endsWith("FUT"))
            return "NFO";
        if (symbol.startsWith("MCX"))
            return "MCX";
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

            LocalDateTime targetTime = MarketUtils.getTargetSyncTime();
            // Use interval-aware tolerance: 5min data that's 4 min old is still fresh
            int toleranceMins = 1;
            if (timeframe != null && timeframe.endsWith("min")) {
                try {
                    toleranceMins = Integer.parseInt(timeframe.replace("min", ""));
                } catch (Exception ignored) {
                }
            }
            if (!lastTime.isBefore(targetTime.minusMinutes(toleranceMins))) {
                return; // Already fresh
            }

            String exchange = resolveExchange(symbol);
            List<Candle> newCandles = zerodhaClient.fetchHistory(token, zerodhaInterval, lastTime, targetTime,
                    exchange);

            if (newCandles != null && !newCandles.isEmpty()) {
                // Filter out pre-market data if any
                newCandles.removeIf(c -> !MarketUtils.isValidCandleTime(c.getTimestamp()));

                if (!newCandles.isEmpty()) {
                    calculateAndPopulateIndicators(newCandles, symbol, timeframe);
                    int saved = repository.saveCandles(newCandles, symbol, timeframe);
                    System.out.println("Synced " + saved + " new candles for " + symbol + " (" + timeframe + ")");
                }
            }
        } catch (Exception e) {
            System.err.println("Error syncing " + symbol + " (" + timeframe + "): " + e.getMessage());
        } finally {
            syncingSyncs.remove(lockKey);
        }
    }

    public void calculateAndPopulateIndicators(List<Candle> candles, String symbol, String timeframe) {
        if (candles == null || candles.isEmpty())
            return;

        // Pass timeframe so SupertrendMtfHeikinAshi (stHa) resolves the correct HTF
        String tf = (timeframe != null && !timeframe.isEmpty()) ? timeframe : "1min";
        com.algo.core.Strategy strategy = new com.algo.core.Strategy(indicatorConfig, tf);

        // Warm up if we have prior context
        if (symbol != null && timeframe != null) {
            List<Candle> priorHistory = repository.getLatestCandles(symbol, timeframe, 2000);
            if (priorHistory != null) {
                for (int i = 0; i < priorHistory.size(); i++) {
                    Candle prev = i > 0 ? priorHistory.get(i - 1) : null;
                    strategy.onNewCandle(prev, priorHistory.get(i), 0); // Warm up uses neutral bias
                }
            }
        }

        for (int i = 0; i < candles.size(); i++) {
            Candle candle = candles.get(i);
            Candle prev = i > 0 ? candles.get(i - 1) : null;
            strategy.onNewCandle(prev, candle, 0);
        }
    }

    public List<Candle> buildVolumeHistory(String symbol, String volumeInterval) {
        double targetVol = Double.parseDouble(volumeInterval.replace("kV", "")) * 1000;
        
        List<Candle> baseCandles = repository.getLatestCandles(symbol, "1min", 5000);
        if (baseCandles == null || baseCandles.isEmpty()) {
            return new ArrayList<>();
        }
        
        // Safety check for indices: if there is zero volume across the board, volume candles cannot form.
        double totalBaseVol = baseCandles.stream().mapToDouble(Candle::getVolume).sum();
        if (totalBaseVol == 0) {
            return new ArrayList<>();
        }
        
        List<Candle> volCandles = new ArrayList<>();
        Candle currentVolCandle = null;
        
        for (Candle c : baseCandles) {
            if (currentVolCandle == null) {
                currentVolCandle = new Candle(c.getOpen(), c.getHigh(), c.getLow(), c.getClose(), c.getVolume(), c.getTimestamp());
            } else {
                currentVolCandle.setHigh(Math.max(currentVolCandle.getHigh(), c.getHigh()));
                currentVolCandle.setLow(Math.min(currentVolCandle.getLow(), c.getLow()));
                currentVolCandle.setClose(c.getClose());
                currentVolCandle.setVolume(currentVolCandle.getVolume() + c.getVolume());
            }
            
            if (currentVolCandle.getVolume() >= targetVol) {
                volCandles.add(currentVolCandle);
                currentVolCandle = null;
            }
        }
        
        if (currentVolCandle != null) {
            volCandles.add(currentVolCandle);
        }
        
        calculateAndPopulateIndicators(volCandles, symbol, volumeInterval);
        return volCandles;
    }

    public void syncAllSymbolsToRedis(com.algo.core.Engine engine) {
        for (String symbol : engine.getSymbolStates().keySet()) {
            com.algo.core.SymbolState state = engine.getState(symbol);
            if (state == null)
                continue;

            for (String tf : TIMEFRAMES) {
                // Use a consistent lock key that matches syncData's guard to prevent
                // concurrent syncs for the same symbol/timeframe from racing.
                String lockKey = symbol + "_" + tf + "_SYNC";
                if (!syncingSyncs.add(lockKey))
                    continue;

                try {
                    List<Candle> history = repository.getLatestCandles(symbol, tf, 2500);
                    if (history != null && !history.isEmpty()) {
                        // Only seed the Strategy in-memory state (warm up EMAs & indicators).
                        // Do NOT re-calculate indicators or re-save to DB — those are already stored.
                        state.seedHistory(tf, history);
                    }
                } finally {
                    syncingSyncs.remove(lockKey);
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
            default:
                return null;
        }
    }
}
