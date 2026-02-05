package com.algo.service;

import com.algo.core.Candle;
import com.algo.data.AngelClient;
import com.algo.data.TimescaleRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

/**
 * Service for loading and syncing historical data to TimescaleDB
 * Inspired by HistoricalDataLoader but designed for TimescaleDB
 */
public class HistoricalDataService {
    private final AngelClient angelClient;
    private final TimescaleRepository repository;

    private static final Map<String, Integer> INTERVAL_MINUTES = new HashMap<>();
    static {
        INTERVAL_MINUTES.put("ONE_MINUTE", 1);
        INTERVAL_MINUTES.put("THREE_MINUTE", 3);
        INTERVAL_MINUTES.put("FIVE_MINUTE", 5);
        INTERVAL_MINUTES.put("TEN_MINUTE", 10);
        INTERVAL_MINUTES.put("FIFTEEN_MINUTE", 15);
        INTERVAL_MINUTES.put("THIRTY_MINUTE", 30);
        INTERVAL_MINUTES.put("ONE_HOUR", 60);
        INTERVAL_MINUTES.put("TWO_HOUR", 120);
        INTERVAL_MINUTES.put("ONE_DAY", 1440);
    }

    // Supported timeframes
    private static final String[] TIMEFRAMES = { "1min", "3min", "5min", "10min", "15min", "1hour", "1day" };

    public HistoricalDataService(AngelClient angelClient, TimescaleRepository repository) {
        this.angelClient = angelClient;
        this.repository = repository;
    }

    /**
     * Load historical data for all supported timeframes for a given symbol
     */
    public void loadInitialData(String symbol, String token) {
        int requiredCount = 500; // Minimum candles required

        System.out.println("Loading historical data for " + symbol);

        for (String timeframe : TIMEFRAMES) {
            String angelInterval = mapToAngelInterval(timeframe);
            if (angelInterval == null)
                continue;

            try {
                LocalDateTime lastTime = repository.getLastCandleTime(symbol, timeframe);

                // Check if we need to sync
                if (lastTime != null) {
                    int existingCount = repository.getCandleCount(symbol, timeframe);
                    if (existingCount >= requiredCount) {
                        System.out
                                .println("  [" + timeframe + "] Already has " + existingCount + " candles. Skipping.");
                        continue;
                    }
                }

                // Fetch from API
                System.out.println("  [" + timeframe + "] Fetching from Angel One API...");
                // Determine Exchange based on symbol characteristics
                String exchange = "NSE"; // Default
                if (symbol.startsWith("SENSEX") || symbol.startsWith("BANKEX")) {
                    exchange = "BFO";
                } else if (symbol.startsWith("NIFTY") || symbol.startsWith("BANKNIFTY") || symbol.startsWith("FINNIFTY")
                        || symbol.endsWith("CE") || symbol.endsWith("PE") || symbol.endsWith("FUT")) {
                    exchange = "NFO";
                } else if (symbol.startsWith("MCX")) {
                    exchange = "MCX";
                } else if (symbol.startsWith("BSE:")) {
                    exchange = "BSE";
                }

                List<Candle> candles = angelClient.fetchHistory(token, angelInterval, exchange);

                if (candles != null && !candles.isEmpty()) {
                    // Calculate indicators
                    calculateAndPopulateIndicators(candles);

                    // Save to database
                    int saved = repository.saveCandles(candles, symbol, timeframe);
                    System.out.println("  [" + timeframe + "] Saved " + saved + " candles to TimescaleDB");
                } else {
                    System.out.println("  [" + timeframe + "] No data returned from API");
                }

                // Rate limiting
                Thread.sleep(1500);

            } catch (Exception e) {
                System.err.println("  [" + timeframe + "] Error: " + e.getMessage());
                e.printStackTrace();
            }
        }

        System.out.println("Completed loading data for " + symbol);
    }

    /**
     * Sync new candles since the last stored timestamp
     */
    public void syncData(String symbol, String token, String timeframe) {
        String angelInterval = mapToAngelInterval(timeframe);
        if (angelInterval == null)
            return;

        try {
            LocalDateTime lastTime = repository.getLastCandleTime(symbol, timeframe);
            if (lastTime == null) {
                System.out.println(
                        "No existing data for " + symbol + " (" + timeframe + "). Use loadInitialData() first.");
                return;
            }

            LocalDateTime targetTime = LocalDateTime.now();

            // If already up to date, skip
            if (!lastTime.isBefore(targetTime.minusMinutes(INTERVAL_MINUTES.getOrDefault(angelInterval, 1)))) {
                System.out.println("Data for " + symbol + " (" + timeframe + ") is already up to date.");
                return;
            }

            // Fetch new data
            // Determine Exchange based on symbol characteristics
            String exchange = "NSE"; // Default
            if (symbol.startsWith("SENSEX") || symbol.startsWith("BANKEX")) {
                exchange = "BFO";
            } else if (symbol.startsWith("NIFTY") || symbol.startsWith("BANKNIFTY") || symbol.startsWith("FINNIFTY")
                    || symbol.endsWith("CE") || symbol.endsWith("PE") || symbol.endsWith("FUT")) {
                exchange = "NFO";
            } else if (symbol.startsWith("MCX")) {
                exchange = "MCX";
            } else if (symbol.startsWith("BSE:")) {
                exchange = "BSE";
            }

            List<Candle> newCandles = angelClient.fetchHistory(token, angelInterval, lastTime, targetTime, exchange);

            if (newCandles != null && !newCandles.isEmpty()) {
                calculateAndPopulateIndicators(newCandles);
                int saved = repository.saveCandles(newCandles, symbol, timeframe);
                System.out.println("Synced " + saved + " new candles for " + symbol + " (" + timeframe + ")");
            }

        } catch (Exception e) {
            System.err.println("Error syncing data for " + symbol + " (" + timeframe + "): " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Calculate indicators for a list of candles and populate them
     */
    private void calculateAndPopulateIndicators(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return;
        }

        // Create a Strategy instance to calculate indicators
        com.algo.core.Strategy strategy = new com.algo.core.Strategy(15, 7, 3.0);

        // Process each candle through the strategy
        for (Candle candle : candles) {
            strategy.onNewCandle(candle);
            // Indicators are automatically populated in the candle by Strategy.onNewCandle
        }
    }

    /**
     * Push initial historical indicators for all symbols to Redis via Engine
     */
    public void syncAllSymbolsToRedis(com.algo.core.Engine engine) {
        System.out.println("Syncing all historical data to Redis for live display...");
        for (String symbol : engine.getSymbolStates().keySet()) {
            com.algo.core.SymbolState state = engine.getState(symbol);
            if (state == null)
                continue;

            for (String tf : TIMEFRAMES) {
                List<Candle> history = repository.getLatestCandles(symbol, tf, 1);
                if (history != null && !history.isEmpty()) {
                    Candle last = history.get(0);
                    // Update current state in memory
                    state.update(last.getClose(), last.getTimestamp());
                }
            }
        }
        System.out.println("Historical data sync to Redis complete.");
    }

    /**
     * Maps user timeframe string to Angel One API interval string
     */
    private String mapToAngelInterval(String timeframe) {
        switch (timeframe.toLowerCase()) {
            case "1min":
                return "ONE_MINUTE";
            case "3min":
                return "THREE_MINUTE";
            case "5min":
                return "FIVE_MINUTE";
            case "10min":
                return "TEN_MINUTE";
            case "15min":
                return "FIFTEEN_MINUTE";
            case "30min":
                return "THIRTY_MINUTE";
            case "1hour":
                return "ONE_HOUR";
            case "2hour":
                return "TWO_HOUR";
            case "1day":
                return "ONE_DAY";
            default:
                return null;
        }
    }
}
