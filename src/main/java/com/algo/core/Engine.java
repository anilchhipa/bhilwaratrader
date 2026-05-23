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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jctools.queues.MpscArrayQueue;

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

    // Leveraging Java 21+ Virtual Threads (Project Loom) for scalability
    private final ExecutorService asyncPool = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, Long> lastTickQueueTime = new ConcurrentHashMap<>();

    // --- ULTRA-LOW LATENCY INGESTION PIPELINE ---
    // Object Pool for QueuedTick to eliminate per-tick GC allocations
    private final QueuedTick[] tickPool = new QueuedTick[262144];

    private final MpscArrayQueue<QueuedTick> tickQueue = new MpscArrayQueue<>(131072);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExecutorService processorPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "TickProcessor-0");
        t.setPriority(Thread.MAX_PRIORITY);
        return t;
    });

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

        startTickProcessor();
    }

    private void startTickProcessor() {
        processorPool.submit(() -> {
            logger.info("Engine: Starting Ultra-Low Latency Tick Processor...");
            while (running.get() || !tickQueue.isEmpty()) {
                QueuedTick qTick = tickQueue.poll();
                if (qTick != null) {
                    try {
                        processTick(qTick);
                    } catch (Exception e) {
                        logger.error("Engine: Critical error in TickProcessor: {}", e.getMessage(), e);
                    }
                } else {
                    Thread.onSpinWait();
                }
            }
            logger.info("Engine: Tick Processor shutdown complete.");
        });
    }

    public void registerSymbol(String symbol) {
        if (!symbolStates.containsKey(symbol)) {
            symbolStates.put(symbol,
                    new SymbolState(symbol, this::onCandleComplete, this::onTrendFlip, indicatorConfig));
            logger.info("Engine: Registered {}", symbol);
        }
    }

    public void unregisterSymbol(String symbol) {
        symbolStates.remove(symbol);
        logger.info("Engine: Unregistered {}", symbol);
    }

    private void onCandleComplete(String symbol, String timeframe, Candle completedCandle) {
        if (timescaleRepository == null)
            return;

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

    private void onTrendFlip(String symbol, String timeframe, int trend, boolean isSecondary) {
        CompletableFuture.runAsync(() -> {
            try {
                if (telegramService != null) {
                    telegramService.sendTrendFlipAlert(symbol, timeframe, trend, isSecondary);
                }
            } catch (Exception e) {
                logger.error("Error sending Telegram alert: {}", e.getMessage());
            }
        }, asyncPool);
    }

    public void sendStartupMessage() {
        if (telegramService != null) {
            telegramService.sendMessage("🚀 *Market Monitor Online*\nSystem is tracking active symbols.");
        }
    }

    /**
     * Producer: Reuses an object from the pool and drops it into the queue.
     * Zero-allocation in the hot path.
     */
    public void onTick(String token, double price, double quantity) {
        if (!MarketUtils.isMarketOpen()) {
            return;
        }

        // Circular pool to avoid new object creation
        int idx = (int) (System.nanoTime() % tickPool.length);
        QueuedTick qTick = tickPool[idx];
        if (qTick == null) {
            qTick = new QueuedTick();
            tickPool[idx] = qTick;
        }

        qTick.set(token, price, quantity);

        if (!tickQueue.offer(qTick)) {
            logger.warn("Engine: Ingestion Overflow! Queue full, dropping tick for token {}", token);
        }
    }

    /**
     * Consumer: The dedicated math thread processes the tick here.
     */
    private void processTick(QueuedTick qTick) {
        String symbol = tokenManager.getSymbol(qTick.token());
        if (symbol == null)
            return;

        SymbolState state = symbolStates.get(symbol);
        if (state != null) {
            java.util.Set<String> dirtyIntervals = state.update(qTick.price(), qTick.quantity(), LocalDateTime.now());
            if (dirtyIntervals.isEmpty())
                return;

            long now = System.currentTimeMillis();
            long lastQueue = lastTickQueueTime.getOrDefault(symbol, 0L);

            if (now - lastQueue >= 50) {
                lastTickQueueTime.put(symbol, now);
                final java.util.Set<String> toSync = dirtyIntervals;
                CompletableFuture.runAsync(() -> syncAndBroadcast(state, toSync), asyncPool);
            }
        }
    }

    /**
     * Optimized synchronization: Batch Redis writes via Pipeline and Direct
     * Protobuf Broadcast.
     * ZERO allocations for intermediate JSON objects.
     */
    private void syncAndBroadcast(SymbolState state, java.util.Set<String> intervals) {
        if (intervals == null || intervals.isEmpty())
            return;

        // 1. Pipelined Redis Update (O(1) round-trip for all timeframes)
        if (redisClient != null) {
            redisClient.pushStatePipelined(state.getSymbol(), intervals, state.getCurrentCandles(),
                    state.getIndicatorValues());
        }

        // 2. Binary Protobuf Broadcast for UI
        if (broadcaster != null) {
            for (String interval : intervals) {
                Candle candle = state.getCurrentCandles().get(interval);
                Map<String, Double> indicators = state.getIndicatorValues().get(interval);
                List<RenkoBrick> renko = state.getRenkoBricks();
                if (candle != null && indicators != null) {
                    broadcaster.broadcastUpdateOptimized(state.getSymbol(), interval, candle, indicators, renko);
                }
            }
        }
    }

    public void flushSessionToDatabase() {
        if (timescaleRepository == null)
            return;

        logger.info("Engine: Flushing active final candles to TimescaleDB...");
        for (SymbolState state : symbolStates.values()) {
            Map<String, List<Candle>> activeData = state.getActiveCandles();
            for (String tf : activeData.keySet()) {
                List<Candle> candles = activeData.get(tf);
                if (!candles.isEmpty()) {
                    timescaleRepository.saveCandles(candles, state.getSymbol(), tf);
                }
            }
        }
    }

    public void shutdown() {
        running.set(false);
        processorPool.shutdown();
        try {
            if (!processorPool.awaitTermination(5, TimeUnit.SECONDS)) {
                processorPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            processorPool.shutdownNow();
        }
        asyncPool.shutdown();
    }

    public void flushToTimescale() {
        flushSessionToDatabase();
    }

    public Map<String, SymbolState> getSymbolStates() {
        return symbolStates;
    }

    public SymbolState getState(String symbol) {
        return symbolStates.get(symbol);
    }

    /**
     * Mutable QueuedTick for object pooling.
     */
    private static class QueuedTick {
        private String token;
        private double price;
        private double quantity;

        public void set(String token, double price, double quantity) {
            this.token = token;
            this.price = price;
            this.quantity = quantity;
        }

        public String token() {
            return token;
        }

        public double price() {
            return price;
        }

        public double quantity() {
            return quantity;
        }
    }
}
