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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
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
    // Use long[] (length-1 mutable holder) to avoid autoboxing Long on every tick
    private final ConcurrentHashMap<String, long[]> lastTickQueueTime = new ConcurrentHashMap<>();

    // --- ULTRA-LOW LATENCY INGESTION PIPELINE ---
    // Immutable Java 21 Record for thread-safe zero-race ingestion
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
                    // 1 µs park: releases the CPU slice instead of burning a full core
                    // with Thread.onSpinWait(). Latency impact is negligible at SENSEX
                    // tick rates (~5–50 ticks/sec per symbol).
                    LockSupport.parkNanos(1_000L);
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
        // Signal generation temporarily disabled pending new calculation model
    }

    public void sendStartupMessage() {
        if (telegramService != null) {
            telegramService.sendMessage("🚀 *Market Monitor Online*\nSystem is tracking active symbols.");
        }
    }

    /**
     * Producer: Thread-safe ingestion using immutable Java 21 record.
     */
    // Counter for logging dropped ticks (avoids log spam)
    private final AtomicInteger droppedTickCounter = new AtomicInteger(0);

    public void onTick(String token, double price, double quantity) {
        if (!MarketUtils.isMarketOpen()) {
            // Log every 500th dropped tick so timezone/time issues are visible in logs
            if (droppedTickCounter.incrementAndGet() % 500 == 0) {
                logger.warn("Engine: Ticks are being dropped — market reported as closed. " +
                    "Current IST time check: {}. If this is wrong, verify server timezone. " +
                    "Total dropped so far: {}", java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")), droppedTickCounter.get());
            }
            return;
        }
        droppedTickCounter.set(0); // reset on valid tick

        QueuedTick qTick = new QueuedTick(token, price, quantity, java.time.LocalDateTime.now());

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
            // Use the timestamp captured at enqueue time — no new LocalDateTime allocation here.
            int dirtyMask = state.update(qTick.price(), qTick.quantity(), qTick.time());
            if (dirtyMask == 0)
                return;

            long now = System.currentTimeMillis();
            // long[] holder avoids autoboxing a new Long object on every tick
            long[] holder = lastTickQueueTime.computeIfAbsent(symbol, k -> new long[]{0L});
            long lastQueue = holder[0];

            if (now - lastQueue >= 50) {
                holder[0] = now;
                final java.util.Set<String> toSync = new java.util.HashSet<>(6);
                for (int i = 0; i < SymbolState.TIMEFRAMES.length; i++) {
                    if ((dirtyMask & (1 << i)) != 0) {
                        toSync.add(SymbolState.TIMEFRAMES[i]);
                    }
                }
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

        // Binary Protobuf Broadcast for UI (direct low-latency WebSocket push)
        if (broadcaster != null) {
            for (String interval : intervals) {
                Candle candle = state.getCurrentCandles().get(interval);
                Map<String, Double> indicators = state.getIndicatorValues().get(interval);
                if (candle != null && indicators != null) {
                    broadcaster.broadcastUpdateOptimized(state.getSymbol(), interval, candle, indicators);
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
            // entrySet() avoids a second map lookup compared to keySet() + get()
            for (Map.Entry<String, List<Candle>> entry : activeData.entrySet()) {
                List<Candle> candles = entry.getValue();
                if (!candles.isEmpty()) {
                    timescaleRepository.saveCandles(candles, state.getSymbol(), entry.getKey());
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
     * Immutable Java 21 Record representing a high-throughput market tick.
     * Guarantees thread-safe lock-free memory visibility across threads.
     */
    public record QueuedTick(String token, double price, double quantity, java.time.LocalDateTime time) {}
}
