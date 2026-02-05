package com.algo.core;

import com.algo.data.TokenManager;
import com.algo.data.RedisClient;
import com.algo.data.TimescaleRepository;
import com.algo.web.LiveUpdateBroadcaster;
import org.json.JSONObject;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;

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

    public Engine(TokenManager tokenManager, RedisClient redisClient, TimescaleRepository timescaleRepository,
            LiveUpdateBroadcaster broadcaster) {
        this.tokenManager = tokenManager;
        this.redisClient = redisClient;
        this.timescaleRepository = timescaleRepository;
        this.broadcaster = broadcaster;

        // Initialize state for all tracked symbols
        for (String symbol : tokenManager.getAllSymbols()) {
            symbolStates.put(symbol, new SymbolState(symbol));
        }
    }

    public void onTick(String token, double price) {
        String symbol = tokenManager.getSymbol(token);
        if (symbol == null)
            return;

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
     * Call this at 3:16 PM to flush all accumulated session data to database.
     */
    public void flushSessionToDatabase() {
        if (timescaleRepository == null)
            return;

        System.out.println("Engine: Flushing full session data to TimescaleDB...");
        for (SymbolState state : symbolStates.values()) {
            Map<String, List<Candle>> sessionData = state.getAndClearSessionCandles();
            for (String tf : sessionData.keySet()) {
                List<Candle> candles = sessionData.get(tf);
                if (!candles.isEmpty()) {
                    System.out.println(
                            "  Flushing " + candles.size() + " candles for " + state.getSymbol() + " (" + tf + ")");
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
