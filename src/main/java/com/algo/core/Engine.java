package com.algo.core;

import com.algo.data.TokenManager;
import com.algo.data.RedisClient;
import com.algo.data.TimescaleRepository;
import com.algo.web.LiveUpdateBroadcaster;
import org.json.JSONObject;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.Collections;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The central brain of the application.
 * Receives ticks and updates all SymbolStates.
 */
public class Engine {
    private static final Logger logger = LoggerFactory.getLogger(Engine.class);
    private final Map<String, SymbolState> symbolStates = new ConcurrentHashMap<>();
    private final TokenManager tokenManager;
    private final RedisClient redisClient;
    private final TimescaleRepository timescaleRepository;
    private final LiveUpdateBroadcaster broadcaster;
    private final com.algo.service.TelegramService telegramService;
    private final IndicatorConfig indicatorConfig;
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // Leveraging Java 21+ Virtual Threads (Project Loom) for infinite scalability
    // and true non-blocking I/O during Redis/Postgres/WebSocket broadcasting
    private final ExecutorService asyncPool = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, Long> lastTickQueueTime = new ConcurrentHashMap<>();

    public Engine(TokenManager tokenManager, RedisClient redisClient, TimescaleRepository timescaleRepository,
            LiveUpdateBroadcaster broadcaster, com.algo.service.TelegramService telegramService,
            IndicatorConfig indicatorConfig) {
        this.tokenManager = tokenManager;
        this.redisClient = redisClient;
        this.timescaleRepository = timescaleRepository;
        this.broadcaster = broadcaster;
        this.telegramService = telegramService;
        this.indicatorConfig = indicatorConfig;

        logger.info("Engine: Using {}", indicatorConfig);

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
            symbolStates.put(symbol,
                    new SymbolState(symbol, this::onCandleComplete, this::onTrendFlip, indicatorConfig));
            logger.info("Engine: Registered {}", symbol);
        }
    }

    /**
     * Removes state for a symbol.
     */
    public void unregisterSymbol(String symbol) {
        symbolStates.remove(symbol);
        logger.info("Engine: Unregistered {}", symbol);
    }

    /**
     * Callback triggered by SymbolState when a timeframe interval closes.
     */
    private void onCandleComplete(String symbol, String timeframe, Candle completedCandle) {
        if (timescaleRepository == null)
            return;

        // Save the completed candle to the database asynchronously via Virtual Threads
        // preventing ForkJoinPool starvation during heavy DB I/O
        CompletableFuture.runAsync(() -> {
            try {
                if (symbol != null) {
                    timescaleRepository.saveCandles(Collections.singletonList(completedCandle), symbol, timeframe);
                    logger.debug("Live sync: Saved 1 complete candle for {} ({})", symbol, timeframe);
                }
            } catch (Exception e) {
                logger.error("Error async saving continuous candle: {}", e.getMessage());
            }
        }, asyncPool);
    }

    /**
     * Callback triggered by SymbolState when a confirmed trend flip occurs on
     * candle
     * close.
     */
    private void onTrendFlip(String symbol, String timeframe, int trend) {
        // Dispatch alert to mobile (Telegram) asynchronously
        CompletableFuture.runAsync(() -> {
            try {
                if (telegramService != null) {
                    telegramService.sendTrendFlipAlert(symbol, timeframe, trend);
                }
            } catch (Exception e) {
                logger.error("Error sending Telegram alert: {}", e.getMessage());
            }
        }, asyncPool);
    }

    /**
     * Sends a test alert on startup to confirm the Telegram Bot is active.
     */
    public void sendStartupMessage() {
        if (telegramService != null) {
            telegramService.sendMessage("🚀 *Algo Engine Online*\nBhilwaraTrader bot is monitoring 80+ symbols.");
        }
    }

    public void onTick(String token, double price, double delta) {
        if (!MarketUtils.isMarketOpen()) {
            return;
        }

        String symbol = tokenManager.getSymbol(token);
        if (symbol == null) {
            return;
        }

        SymbolState state = symbolStates.get(symbol);
        if (state != null) {
            state.update(price, delta, LocalDateTime.now());

            long now = System.currentTimeMillis();
            long lastQueue = lastTickQueueTime.getOrDefault(symbol, 0L);

            // Throttle async dispatch to max 4 times per second (250ms) per symbol
            // This provides a much more responsive "live" feel while still protecting
            // downstream Redis and WebSocket buffers from excessive tick flooding.
            if (now - lastQueue >= 250) {
                lastTickQueueTime.put(symbol, now);
                CompletableFuture.runAsync(() -> syncToRedis(state), asyncPool);
            }
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
                    .put("d", candle.getDelta())
                    .put("v", candle.getVolume())
                    .put("t", candle.getTimestamp().format(ISO_FORMATTER));

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

        logger.info("Engine: Flushing active final candles to TimescaleDB...");
        for (SymbolState state : symbolStates.values()) {
            Map<String, List<Candle>> activeData = state.getActiveCandles();
            for (String tf : activeData.keySet()) {
                List<Candle> candles = activeData.get(tf);
                if (!candles.isEmpty()) {
                    logger.info("  Flushing {} partial/final candle(s) for {} ({})", candles.size(), state.getSymbol(),
                            tf);
                    int saved = timescaleRepository.saveCandles(candles, state.getSymbol(), tf);
                    logger.info("  Saved {} candles.", saved);
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
