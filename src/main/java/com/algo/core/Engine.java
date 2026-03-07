package com.algo.core;

import com.algo.data.TokenManager;
import com.algo.data.RedisClient;
import com.algo.data.TimescaleRepository;
import com.algo.web.LiveUpdateBroadcaster;
import org.json.JSONObject;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.Collections;

/**
 * The central brain of the application.
 * Receives ticks and updates all SymbolStates.
 */
public class Engine {
    private final Map<String, SymbolState> symbolStates = new ConcurrentHashMap<>();
    private final TokenManager tokenManager;
    private final RedisClient redisClient;
    private final TimescaleRepository timescaleRepository;
    private final LiveUpdateBroadcaster broadcaster;
    private final IndicatorConfig indicatorConfig;

    public Engine(TokenManager tokenManager, RedisClient redisClient, TimescaleRepository timescaleRepository,
            LiveUpdateBroadcaster broadcaster, IndicatorConfig indicatorConfig) {
        this.tokenManager = tokenManager;
        this.redisClient = redisClient;
        this.timescaleRepository = timescaleRepository;
        this.broadcaster = broadcaster;
        this.indicatorConfig = indicatorConfig;

        System.out.println("Engine: Using " + indicatorConfig);

        // Initialize state for all tracked symbols
        for (String symbol : tokenManager.getAllSymbols()) {
            registerSymbol(symbol);
        }
    }

    /**
     * Initializes state for a new symbol.
     */
    public void registerSymbol(String symbol) {
        if (!symbolStates.containsKey(symbol)) {
            symbolStates.put(symbol, new SymbolState(symbol, this::onCandleComplete, indicatorConfig));
            System.out.println("Engine: Registered " + symbol);
        }
    }

    /**
     * Removes state for a symbol.
     */
    public void unregisterSymbol(String symbol) {
        symbolStates.remove(symbol);
        System.out.println("Engine: Unregistered " + symbol);
    }

    /**
     * Callback triggered by SymbolState when a timeframe interval closes.
     */
    private void onCandleComplete(String symbol, String timeframe, Candle completedCandle) {
        if (timescaleRepository == null)
            return;

        // Save the completed candle to the database asynchronously to avoid blocking
        // the tick thread
        CompletableFuture.runAsync(() -> {
            try {
                if (symbol != null) {
                    timescaleRepository.saveCandles(Collections.singletonList(completedCandle), symbol, timeframe);
                    System.out.println("Live sync: Saved 1 complete candle for " + symbol + " (" + timeframe + ")");
                }
            } catch (Exception e) {
                System.err.println("Error async saving continuous candle: " + e.getMessage());
            }
        });
    }

    public void onTick(String token, double price) {
        String symbol = tokenManager.getSymbol(token);
        if (symbol == null) {
            return;
        }

        // Temporary diagnostic for symbol cross-pollination
        if (price > 1000 && !symbol.equals("RELIANCE")) {
            System.out.println("Engine DIAGNOSTIC: Token " + token + " (" + symbol + ") received high price: " + price);
        }

        SymbolState state = symbolStates.get(symbol);
        if (state != null) {
            state.update(price, LocalDateTime.now());

            // Sync to Redis (Throttled or sampled)
            syncToRedis(state);
        }
    }

    private void syncToRedis(SymbolState state) {
        if (redisClient == null)
            return;

        for (String interval : state.getCurrentCandles().keySet()) {
            Candle candle = state.getCurrentCandles().get(interval);
            JSONObject ohlc = new JSONObject()
                    .put("o", candle.getOpen())
                    .put("h", candle.getHigh())
                    .put("l", candle.getLow())
                    .put("c", candle.getClose())
                    .put("t", candle.getTimestamp().toString());

            Map<String, Double> indicators = state.getIndicatorValues().get(interval);
            JSONObject indJson = new JSONObject(indicators);

            redisClient.pushState(state.getSymbol(), interval, ohlc, indJson);

            // Broadcast via WebSocket
            if (broadcaster != null) {
                broadcaster.broadcastUpdate(state.getSymbol(), interval, ohlc, indJson);
            }
        }
    }

    /**
     * Called at 3:30 PM (market close) to flush all active/incomplete session data
     * to database.
     */
    public void flushSessionToDatabase() {
        if (timescaleRepository == null)
            return;

        System.out.println("Engine: Flushing active final candles to TimescaleDB...");
        for (SymbolState state : symbolStates.values()) {
            Map<String, List<Candle>> activeData = state.getActiveCandles();
            for (String tf : activeData.keySet()) {
                List<Candle> candles = activeData.get(tf);
                if (!candles.isEmpty()) {
                    System.out.println(
                            "  Flushing " + candles.size() + " partial/final candle(s) for " + state.getSymbol() + " ("
                                    + tf + ")");
                    int saved = timescaleRepository.saveCandles(candles, state.getSymbol(), tf);
                    System.out.println("  Saved " + saved + " candles.");
                }
            }
        }
    }

    /**
     * Call this at 3:30 PM or when stopping the app.
     */
    public void flushToTimescale() {
        flushSessionToDatabase();
    }

    public Map<String, SymbolState> getSymbolStates() {
        return symbolStates;
    }

    public SymbolState getState(String symbol) {
        return symbolStates.get(symbol);
    }
}
