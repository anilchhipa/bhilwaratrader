package com.algo.web;

import com.algo.core.Engine;
import com.algo.core.SymbolState;
import com.algo.core.Candle;
import com.algo.data.ZerodhaClient;
import com.algo.data.TokenManager;
import com.algo.data.TimescaleRepository;
import com.algo.service.HistoricalDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api")
@org.springframework.web.bind.annotation.CrossOrigin(origins = "*")
public class TradingController {

    private static final Logger logger = LoggerFactory.getLogger(TradingController.class);

    // Singleton ObjectMapper — reused across all requests.
    // new ObjectMapper() is expensive (~3ms per call); creating one per /history request
    // was a significant performance regression under concurrent chart load.
    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules(); // registers JavaTimeModule, etc.

    @Autowired
    private Engine engine;

    @Autowired
    private ZerodhaClient zerodhaClient;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private TimescaleRepository timescaleRepository;

    @Autowired
    private HistoricalDataService historicalDataService;

    @Autowired
    private com.algo.core.IndicatorConfig indicatorConfig;

    @Autowired
    private com.algo.data.LiveFeed liveFeed;

    @Autowired
    private com.algo.data.RedisClient redisClient;

    @Autowired
    private com.algo.core.MarketSessionManager marketSessionManager;

    @GetMapping("/config")
    public com.algo.core.IndicatorConfig getConfig() {
        return indicatorConfig;
    }

    /**
     * Manual data sync trigger — fills historical gaps regardless of market hours.
     * Call this whenever the chart shows stale/missing data.
     */
    @PostMapping("/data/sync")
    public ResponseEntity<?> triggerDataSync() {
        new Thread(() -> marketSessionManager.triggerManualPreMarketSync(), "manual-sync").start();
        return ResponseEntity.ok(java.util.Map.of("status", "sync started", "message",
                "Fetching missing historical data in background..."));
    }

    @GetMapping("/matrix")
    public Map<String, Object> getMatrixState() {
        Map<String, Object> result = new java.util.HashMap<>();
        for (Map.Entry<String, SymbolState> entry : engine.getSymbolStates().entrySet()) {
            String sym = entry.getKey();
            SymbolState state = entry.getValue();

            Map<String, Object> symData = new java.util.HashMap<>();
            Map<String, Candle> currentCandles = state.getCurrentCandles();

            // Get absolute latest price from any timeframe
            Candle latest = currentCandles.get("1min");
            if (latest == null)
                latest = currentCandles.get("3min");
            if (latest == null)
                latest = currentCandles.get("5min");

            if (latest != null) {
                symData.put("price", latest.getClose());
                symData.put("isGreen", latest.getClose() >= latest.getOpen());
            }

            // Optimization: Backend pre-calculates the matrix values
            symData.put("stTrendByTf", state.getStTrendByTfCache());
            symData.put("emaBiasByTf", state.getEmaBiasByTfCache());
            symData.put("lastFlipTimes", state.getLastFlipTimes());

            result.put(sym, symData);
        }
        return result;
    }

    @GetMapping("/state/{symbol}")
    public SymbolState getSymbolState(@PathVariable String symbol) {
        return engine.getState(symbol);
    }

    @GetMapping("/history/{symbol}/{interval}")
    public List<Candle> getHistory(@PathVariable String symbol, @PathVariable String interval,
            @RequestParam(required = false) Integer limit) {
        String token = tokenManager.getToken(symbol);
        if (token == null)
            return new ArrayList<>();

        // Map frontend intervals to database intervals (already in correct format)
        String dbInterval = interval; // 1min, 3min, 5min, etc.

        try {
            int candleLimit = 2500;
            if (limit != null && limit > 0) {
                candleLimit = limit;
            }

            int needed = (limit != null && limit > 0) ? limit : candleLimit;

            // Hot RAM Buffer Check: The fastest path (0ms)
            SymbolState state = engine.getState(symbol);
            if (state != null) {
                List<Candle> ramHistory = state.getHistory(interval);
                if (ramHistory != null && !ramHistory.isEmpty() && ramHistory.size() >= needed) {
                    int start = Math.max(0, ramHistory.size() - needed);
                    List<Candle> result = new ArrayList<>(ramHistory.subList(start, ramHistory.size()));
                    appendActiveCandle(symbol, dbInterval, result);
                    logger.debug("Serving {} candles from RAM buffer for {} ({}) [0ms]", result.size(), symbol, interval);
                    return result;
                }
            }

            // Redis Cache Check (for smaller scanner limits)
            String cacheKey = "history_cache:" + symbol + ":" + dbInterval + ":" + candleLimit;
            if (candleLimit <= 1000) {
                String cachedData = redisClient.get(cacheKey);
                if (cachedData != null) {
                    try {
                        List<Candle> cached = OBJECT_MAPPER.readValue(
                                cachedData,
                                new com.fasterxml.jackson.core.type.TypeReference<List<Candle>>() {});
                        if (cached != null) return cached;
                    } catch (Exception ignored) {
                    }
                }
            }

            // Try to fetch from TimescaleDB (Disk fallback)
            // Volume candle fetch disabled — 10kV/50kV removed from TIMEFRAMES.
            // if (dbInterval.endsWith("V")) {
            //     history = historicalDataService.buildVolumeHistory(symbol, dbInterval);
            // } else {
                List<Candle> history = timescaleRepository.getLatestCandles(symbol, dbInterval, candleLimit);
            // }

            // Freshness Check: Only trigger broker sync during market hours if data is stale.
            if (com.algo.core.MarketUtils.isMarketOpen() && history != null && !history.isEmpty()) {
                Candle latest = history.get(history.size() - 1);
                LocalDateTime targetTime = com.algo.core.MarketUtils.getTargetSyncTime();

                int toleranceMins = 1;
                if (interval.endsWith("min")) {
                    try {
                        toleranceMins = Integer.parseInt(interval.replace("min", ""));
                    } catch (Exception ignored) {
                    }
                }

                if (latest.getTimestamp().isBefore(targetTime.minusMinutes(toleranceMins))) {
                    logger.info("Data for {} ({}) is stale (last: {}). Syncing...", symbol, interval,
                            latest.getTimestamp());
                    // Volume stale re-build disabled.
                    // if (dbInterval.endsWith("V")) {
                    //     historicalDataService.syncData(symbol, token, "1min");
                    //     history = historicalDataService.buildVolumeHistory(symbol, dbInterval);
                    // } else {
                    final String s = symbol;
                    final String tok = token;
                    final String iv = interval;
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            historicalDataService.syncData(s, tok, iv);
                        } catch (Exception e) {
                            logger.warn("Background sync failed for {}: {}", s, e.getMessage());
                        }
                    });
                    // }
                }

                // Sync the freshly loaded DB history back into the SymbolState RAM buffer only if empty
                if (state != null && history != null && !history.isEmpty()) {
                    if (state.getHistory(interval).isEmpty()) {
                        state.seedHistory(interval, history);
                    }
                }
                if (history != null && !history.isEmpty() && (history.get(0).getHaTrailingSl() == null || history.get(0).getHaTrailingSl() == 0)) {
                    List<com.algo.indicators.SupertrendMtfHeikinAshi.SupertrendPoint> haPoints =
                            com.algo.indicators.SupertrendMtfHeikinAshi.calculate(history, dbInterval);
                    for (int i = 0; i < history.size() && i < haPoints.size(); i++) {
                        Candle c = history.get(i);
                        com.algo.indicators.SupertrendMtfHeikinAshi.SupertrendPoint pt = haPoints.get(i);
                        c.setHaTrailingSl(pt.trailingSl);
                        c.setHaTrend(pt.trend);
                        c.setHtfTrailingSl(pt.htfTrailingSl);
                        c.setHtfTrend(pt.htfTrend);
                    }
                }

                // Append the current active/incomplete candle from memory
                appendActiveCandle(symbol, dbInterval, history);

                // Indicators are already pre-computed and stored in TimescaleDB by the
                // data ingestion pipeline. Re-calculating here on every request was a
                // major performance bottleneck. Skip it.

                logger.debug("Serving {} candles from TimescaleDB for {} ({})", history.size(), symbol, interval);

                // Cache in Redis
                if (history != null && candleLimit <= 1000) {
                    try {
                        String json = OBJECT_MAPPER.writeValueAsString(history);
                        redisClient.setEx(cacheKey, 30, json); // 30s TTL
                    } catch (Exception ignored) {
                    }
                }
                return history;
            }

            // If database is empty, trigger data load ONLY for this interval and retry
            logger.info("No data in TimescaleDB for {} ({}). Loading from API...", symbol, interval);
            // Volume fallback disabled.
            // if (dbInterval.endsWith("V")) {
            //     historicalDataService.loadInitialData(symbol, token, "1min");
            //     history = historicalDataService.buildVolumeHistory(symbol, dbInterval);
            // } else {
                historicalDataService.loadInitialData(symbol, token, interval);
                history = timescaleRepository.getLatestCandles(symbol, dbInterval, candleLimit);
            // }
            if (history != null && !history.isEmpty()) {
                if (state != null) {
                    state.seedHistory(interval, history);
                } else {
                    List<com.algo.indicators.SupertrendMtfHeikinAshi.SupertrendPoint> haPoints =
                            com.algo.indicators.SupertrendMtfHeikinAshi.calculate(history, dbInterval);
                    for (int i = 0; i < history.size() && i < haPoints.size(); i++) {
                        Candle c = history.get(i);
                        com.algo.indicators.SupertrendMtfHeikinAshi.SupertrendPoint pt = haPoints.get(i);
                        c.setHaTrailingSl(pt.trailingSl);
                        c.setHaTrend(pt.trend);
                        c.setHtfTrailingSl(pt.htfTrailingSl);
                        c.setHtfTrend(pt.htfTrend);
                    }
                }
                appendActiveCandle(symbol, dbInterval, history);

                logger.info("Loaded and serving {} candles for {} ({})", history.size(), symbol, interval);
                return history;
            }

        } catch (Exception e) {
            logger.error("Error fetching history from TimescaleDB: {}", e.getMessage());
        }

        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/history/batch")
    public Map<String, List<Candle>> getHistoryBatch(@RequestBody Map<String, Object> req) {
        List<String> symbols = (List<String>) req.get("symbols");
        String interval = (String) req.get("interval");
        Integer limit = req.get("limit") instanceof Number ? ((Number) req.get("limit")).intValue() : null;

        if (symbols == null || interval == null)
            return new java.util.HashMap<>();

        Map<String, List<Candle>> result = new java.util.concurrent.ConcurrentHashMap<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.List<java.util.concurrent.CompletableFuture<Void>> futures = symbols.stream()
                .map(sym -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        List<Candle> h = getHistory(sym, interval, limit);
                        if (h != null && !h.isEmpty()) {
                            result.put(sym, h);
                        }
                    } catch (Exception ignored) {}
                }, executor))
                .toList();
            java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
        }
        return result;
    }

    private void appendActiveCandle(String symbol, String interval, List<Candle> history) {
        SymbolState state = engine.getState(symbol);
        if (state == null) return;
        Candle active = state.getCurrentCandles().get(interval);
        if (active == null) return;

        Candle last = history.isEmpty() ? null : history.get(history.size() - 1);
        boolean shouldAppend = (last == null) || active.getTimestamp().isAfter(last.getTimestamp());
        boolean shouldReplace = (last != null) && active.getTimestamp().equals(last.getTimestamp());

        if (shouldAppend || shouldReplace) {
            Candle copy = new Candle(active);
            if (shouldReplace) {
                history.set(history.size() - 1, copy);
            } else {
                history.add(copy);
            }
        }
    }

    @GetMapping("/monitor/all")
    public Map<String, Object> getUnifiedMonitor() {
        Map<String, Object> result = new java.util.HashMap<>();

        // 1. Matrix and Symbols (Similar to /api/matrix)
        Map<String, Object> matrix = new java.util.HashMap<>();
        for (Map.Entry<String, SymbolState> entry : engine.getSymbolStates().entrySet()) {
            String sym = entry.getKey();
            // NOTE: SENSEX is included so the frontend signal engine can read its ST
            // trends.
            // The left-panel symbol list still filters it out client-side.

            SymbolState state = entry.getValue();
            Map<String, Object> symData = new java.util.HashMap<>();
            Map<String, Candle> currentCandles = state.getCurrentCandles();

            Candle c1 = currentCandles.get("1min");
            if (c1 != null) {
                symData.put("price", c1.getClose());
                symData.put("isGreen", c1.getClose() >= c1.getOpen());
                symData.put("stMa", c1.getStMa());
                symData.put("trend", c1.getStTrend());
            }

            symData.put("stTrendByTf", state.getStTrendByTfCache());
            symData.put("stTrend2ByTf", state.getStTrend2ByTfCache());
            symData.put("emaBiasByTf", state.getEmaBiasByTfCache());
            symData.put("lastFlipTimes", state.getLastFlipTimes());
            matrix.put(sym, symData);
        }
        result.put("matrix", matrix);

        return result;
    }

    // ────────────────────────────────────────────────────────────────────────────
    // UNI-RENKO ENDPOINT (Option B standalone overlay)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/renko/{symbol}?limit=200
     *
     * Returns the Guppy Renko (RG) brick history for a symbol plus a pre-computed
     * signal summary so the frontend can display bricks and alerts immediately.
     *
     * Response shape:
     * {
     *   "symbol":          "SENSEX",
     *   "bricks":          [ { open, close, high, low, direction, timestamp,
     *                          brickSizeT, reversalSizeR,
     *                          consecutiveCount, signalScore, bullish } ... ],
     *   "latestBrick":     { ... } | null,
     *   "direction":       +1 | -1 | 0,
     *   "consecutiveCount": N,
     *   "brickSizeT":      75.0,
     *   "reversalSizeR":   150.0,
     *   "openOffsetO":     37.5,
     *   "brickCount":      N,
     *   "signal":          "BUY CE" | "BUY PE" | "WATCH" | "NEUTRAL",
     *   "signalScore":     0–5,
     *   "signalLabel":     "Strong" | "Moderate" | "Weak"
     * }
     */
    private boolean isSensexOptionSymbol(String symbol) {
        if (symbol == null) return false;
        String s = symbol.trim().toUpperCase();
        if (s.equals("SENSEX") || s.equals("NIFTY 50") || s.equals("BANKEX")) return false;
        return s.endsWith("CE") || s.endsWith("PE")
                || s.endsWith("CALL") || s.endsWith("PUT")
                || s.contains(" CE") || s.contains(" PE")
                || (s.startsWith("SENSEX") && (s.contains("CE") || s.contains("PE")));
    }

    @GetMapping("/renko/{symbol}")
    public java.util.Map<String, Object> getRenkoBricks(@PathVariable String symbol) {
        return java.util.Map.of("symbol", symbol, "bricks", java.util.Collections.emptyList());
    }

    /**
     * GET /api/supertrend-mtf-ha/{symbol}?interval=3min&limit=1000&mult=2.0&period=7&htfMode=Auto&userHtf=15min
     *
     * Returns the calculated Supertrend MTF Heikin Ashi series and latest signal state
     * ported from Pine Script v4 (LonesomeTheBlue).
     */
    @GetMapping("/supertrend-mtf-ha/{symbol}")
    public java.util.Map<String, Object> getSupertrendMtfHeikinAshi(
            @PathVariable String symbol,
            @RequestParam(required = false, defaultValue = "3min") String interval,
            @RequestParam(required = false, defaultValue = "1000") int limit,
            @RequestParam(required = false, defaultValue = "2.0") double mult,
            @RequestParam(required = false, defaultValue = "7") int period,
            @RequestParam(required = false, defaultValue = "Auto") String htfMode,
            @RequestParam(required = false, defaultValue = "15min") String userHtf) {

        List<Candle> candles = getHistory(symbol, interval, limit);
        if (candles == null || candles.isEmpty()) {
            return java.util.Map.of(
                "symbol", symbol,
                "interval", interval,
                "points", java.util.Collections.emptyList()
            );
        }

        String resolvedHtf = com.algo.indicators.SupertrendMtfHeikinAshi.resolveHtf(interval, htfMode, userHtf);
        List<com.algo.indicators.SupertrendMtfHeikinAshi.SupertrendPoint> points =
                com.algo.indicators.SupertrendMtfHeikinAshi.calculate(candles, interval, htfMode, userHtf, mult, period);

        com.algo.indicators.SupertrendMtfHeikinAshi.SupertrendPoint latest =
                points.isEmpty() ? null : points.get(points.size() - 1);

        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("interval", interval);
        result.put("htfMode", htfMode);
        result.put("htfTimeframe", resolvedHtf);
        result.put("multiplier", mult);
        result.put("period", period);
        result.put("points", points);
        if (latest != null) {
            result.put("latestTrend", latest.trend);
            result.put("latestTrailingSl", latest.trailingSl);
            result.put("latestHtfTrend", latest.htfTrend);
            result.put("latestHtfTrailingSl", latest.htfTrailingSl);
            result.put("isBullishFlip", latest.isBullishFlip);
            result.put("isBearishFlip", latest.isBearishFlip);
            result.put("isHtfBullishFlip", latest.isHtfBullishFlip);
            result.put("isHtfBearishFlip", latest.isHtfBearishFlip);
        }
        return result;
    }
}
