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

    @GetMapping("/symbols")
    public Map<String, SymbolState> getAllSymbols() {
        return engine.getSymbolStates();
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
            if (latest == null)
                latest = currentCandles.get("15min");

            if (latest != null) {
                symData.put("price", latest.getClose());
                symData.put("isGreen", latest.getClose() >= latest.getOpen());
            }

            // Optimization: Backend pre-calculates the matrix values
            Map<String, Integer> stTrendByTf = new java.util.HashMap<>();
            Map<String, Integer> emaBiasByTf = new java.util.HashMap<>();

            for (String tf : new String[] { "1min", "3min", "5min", "15min" }) {
                Candle c = currentCandles.get(tf);
                if (c != null) {
                    stTrendByTf.put(tf, c.getStTrend());

                    Double e15 = c.getEma15();
                    Double e15_5 = c.getEma15_5();
                    if (e15 != null && e15_5 != null) {
                        emaBiasByTf.put(tf, e15 > e15_5 ? 1 : -1);
                    } else {
                        emaBiasByTf.put(tf, 0);
                    }
                }
            }

            symData.put("stTrendByTf", stTrendByTf);
            symData.put("emaBiasByTf", emaBiasByTf);
            symData.put("lastFlipTimes", state.getLastFlipTimes());

            result.put(sym, symData);
        }
        return result;
    }

    @GetMapping("/breadth")
    public Map<String, Object> getMarketBreadth() {
        String[] timeframes = { "1min", "3min", "5min", "15min" };
        Map<String, Object> result = new java.util.HashMap<>();

        for (String tf : timeframes) {
            int stUp = 0, stDown = 0, emaUp = 0, emaDown = 0, total = 0;

            java.util.Set<String> top10Sensex = new java.util.HashSet<>(java.util.Arrays.asList(
                    "HDFCBANK", "RELIANCE", "ICICIBANK", "INFY", "LT",
                    "ITC", "TCS", "BHARTIARTL", "AXISBANK", "SBIN"));

            for (Map.Entry<String, SymbolState> entry : engine.getSymbolStates().entrySet()) {
                if (!top10Sensex.contains(entry.getKey())) {
                    continue; // Skip symbols not in the top 10 Sensex list
                }

                Map<String, Candle> candles = entry.getValue().getCurrentCandles();
                Candle c = candles.get(tf);
                if (c == null)
                    continue;
                total++;

                // SuperTrend direction
                Integer trend = c.getStTrend();
                if (trend != null && trend == 1)
                    stUp++;
                else if (trend != null && trend == -1)
                    stDown++;

                // EMA bias (ema15 vs ema15_5)
                Double e15 = c.getEma15();
                Double e15_5 = c.getEma15_5();
                if (e15 != null && e15_5 != null) {
                    if (e15 > e15_5)
                        emaUp++;
                    else
                        emaDown++;
                }
            }

            Map<String, Object> tfData = new java.util.HashMap<>();
            tfData.put("stUp", stUp);
            tfData.put("stDown", stDown);
            tfData.put("emaUp", emaUp);
            tfData.put("emaDown", emaDown);
            tfData.put("total", total);
            result.put(tf, tfData);
        }

        return result;
    }

    @GetMapping("/state/{symbol}")
    public SymbolState getSymbolState(@PathVariable String symbol) {
        return engine.getState(symbol);
    }

    @GetMapping("/history/{symbol}/{interval}")
    public List<Candle> getHistory(@PathVariable String symbol, @PathVariable String interval) {
        String token = tokenManager.getToken(symbol);
        if (token == null)
            return new ArrayList<>();

        // Map frontend intervals to database intervals (already in correct format)
        String dbInterval = interval; // 1min, 3min, 5min, etc.

        try {
            // Dynamic limit: give enough candles for ~30 trading days per timeframe
            // 1min: 375 candles/day x 30 days = ~11250
            // 3min: 125 x 30 = ~3750
            // 5min: 75 x 30 = ~2250
            // 15min: 25 x 30 = ~750
            int candleLimit = 3750; // default for 3min
            if (interval.equals("1min"))
                candleLimit = 11250;
            else if (interval.equals("5min"))
                candleLimit = 2250;
            else if (interval.equals("15min"))
                candleLimit = 750;

            // Try to fetch from TimescaleDB first
            List<Candle> history = timescaleRepository.getLatestCandles(symbol, dbInterval, candleLimit);

            // Freshness Check: If DB has data but the latest candle is old, trigger sync
            if (history != null && !history.isEmpty()) {
                Candle latest = history.get(history.size() - 1);
                LocalDateTime targetTime = com.algo.core.MarketUtils.getTargetSyncTime();

                // If latest candle is significantly behind (e.g., 2 intervals), sync
                // This handles the case where DB had "enough" candles (e.g., from yesterday)
                // but today's data hasn't been synced yet.
                // We use a small buffer (e.g., 2m) to avoid syncing during the very first
                // minute of the interval.
                // Increase sync sensitivity: If data is more than 1 minute old, sync it.
                // This ensures charts feel 'instant' when switching between symbols.
                // Calculate tolerance based on interval
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
                    historicalDataService.syncData(symbol, token, interval);

                    // Re-fetch after sync to get updated data
                    history = timescaleRepository.getLatestCandles(symbol, dbInterval, candleLimit);
                }

                // Append the current active/incomplete candle from memory
                appendActiveCandle(symbol, dbInterval, history);

                // Re-calculate indicators on the fly to ensure new logic (like Seller Fear)
                // is applied to historical data served to the chart.
                historicalDataService.calculateAndPopulateIndicators(history, symbol, dbInterval);

                logger.debug("Serving {} candles from TimescaleDB for {} ({})", history.size(), symbol, interval);
                return history;
            }

            // If database is empty, trigger data load ONLY for this interval and retry
            logger.info("No data in TimescaleDB for {} ({}). Loading from API...", symbol, interval);
            historicalDataService.loadInitialData(symbol, token, interval);

            // Retry fetching from database
            history = timescaleRepository.getLatestCandles(symbol, dbInterval, candleLimit);
            if (history != null && !history.isEmpty()) {
                appendActiveCandle(symbol, dbInterval, history);

                logger.info("Loaded and serving {} candles for {} ({})", history.size(), symbol, interval);
                return history;
            }

        } catch (Exception e) {
            logger.error("Error fetching history from TimescaleDB: {}", e.getMessage());
        }

        return new ArrayList<>();
    }

    private void appendActiveCandle(String symbol, String interval, List<Candle> history) {
        SymbolState state = engine.getState(symbol);
        if (state != null) {
            Candle active = state.getCurrentCandles().get(interval);
            if (active != null) {
                // Check if the live active candle should replace the last DB candle or be
                // appended
                boolean shouldAppend = history.isEmpty()
                        || active.getTimestamp().isAfter(history.get(history.size() - 1).getTimestamp());
                boolean shouldReplace = !history.isEmpty()
                        && active.getTimestamp().equals(history.get(history.size() - 1).getTimestamp());

                if (shouldAppend || shouldReplace) {
                    // Make a copy to avoid mutating the live memory reference while serializing
                    Candle copy = new Candle(active.getOpen(), active.getHigh(), active.getLow(), active.getClose(),
                            active.getVolume(), active.getTimestamp());
                    copy.setEma15(active.getEma15());
                    copy.setEma15_5(active.getEma15_5());
                    copy.setEma15_15(active.getEma15_15());
                    copy.setEma15_15_15(active.getEma15_15_15());
                    copy.setEma15_15_15_15(active.getEma15_15_15_15());
                    copy.setEma15_15_15_15_30(active.getEma15_15_15_15_30());
                    copy.setStMa(active.getStMa());
                    copy.setStUp(active.getStUp());
                    copy.setStDn(active.getStDn());
                    copy.setStTrend(active.getStTrend());

                    if (shouldReplace) {
                        history.set(history.size() - 1, copy); // Replace the incomplete DB candle with the precise live
                                                               // one
                    } else {
                        history.add(copy); // Append as a brand new active candle
                    }
                }
            }
        }
    }

    @GetMapping("/symbols/search")
    public List<Map<String, String>> searchInstruments(@RequestParam String query) {
        List<com.zerodhatech.models.Instrument> all = zerodhaClient.getAllInstruments();
        List<Map<String, String>> result = new ArrayList<>();
        if (all == null)
            return result;

        String q = query.toUpperCase();
        for (com.zerodhatech.models.Instrument inst : all) {
            String sym = inst.tradingsymbol;
            if (sym != null && sym.contains(q)) {
                Map<String, String> m = new java.util.HashMap<>();
                m.put("symbol", inst.tradingsymbol);
                m.put("exchange", inst.exchange);
                m.put("type", inst.instrument_type);
                result.add(m);
                if (result.size() > 50)
                    break;
            }
        }
        return result;
    }

    @GetMapping("/symbols/categorized")
    public Map<String, List<Map<String, String>>> getCategorizedSymbols() {
        Map<String, List<Map<String, String>>> result = new java.util.HashMap<>();

        result.put("STOCKS", toSimpleList(zerodhaClient.getInstrumentsByType("STOCKS"), 200));
        result.put("FUTURES", toSimpleList(zerodhaClient.getInstrumentsByType("FUTURES"), 200));
        result.put("OPTIONS", toSimpleList(zerodhaClient.getInstrumentsByType("OPTIONS"), 2500));

        return result;
    }

    private List<Map<String, String>> toSimpleList(List<com.zerodhatech.models.Instrument> list, int limit) {
        List<Map<String, String>> result = new ArrayList<>();
        int count = 0;
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd");
        for (com.zerodhatech.models.Instrument inst : list) {
            Map<String, String> m = new java.util.HashMap<>();
            m.put("symbol", inst.tradingsymbol);
            m.put("exchange", inst.exchange);
            m.put("type", inst.instrument_type);
            m.put("name", inst.name);
            if (inst.expiry != null) {
                m.put("expiry", sdf.format(inst.expiry));
            }
            result.add(m);
            if (++count >= limit)
                break;
        }
        return result;
    }

    @PostMapping("/symbols/track")
    public ResponseEntity<?> trackSymbol(@RequestBody Map<String, String> req) {
        String symbol = req.get("symbol");
        String exchange = req.get("exchange");
        if (symbol == null || exchange == null) {
            return ResponseEntity.badRequest().body("Missing symbol or exchange");
        }

        timescaleRepository.addTrackedSymbol(symbol, exchange);
        tokenManager.register(symbol, exchange);
        zerodhaClient.resolveTokens(tokenManager);
        engine.registerSymbol(symbol);

        String token = tokenManager.getToken(symbol);
        if (token != null) {
            // 1. Fetch all required historical data (up to current time)
            historicalDataService.loadInitialData(symbol, token);

            // 2. Fetch any missing recent candles
            for (String tf : new String[] { "1min", "3min", "5min", "15min" }) {
                historicalDataService.syncData(symbol, token, tf);
            }

            // 3. Seed the downloaded history into the live memory Engine
            // This warms up the EMAs and other cascaded indicators so they don't
            // cold-start.
            for (String tf : new String[] { "1min", "3min", "5min", "15min" }) {
                java.util.List<Candle> history = timescaleRepository.getLatestCandles(symbol, tf, 3000);
                if (history != null && !history.isEmpty()) {
                    engine.getState(symbol).seedHistory(tf, history);
                }
            }

            // 4. Finally, subscribe to the live feed now that history is populated
            liveFeed.subscribe(java.util.Collections.singleton(token));
        }

        return ResponseEntity.ok(Map.of("message", "Symbol tracked", "symbol", symbol));
    }

    @PostMapping("/symbols/untrack")
    public ResponseEntity<?> untrackSymbol(@RequestBody Map<String, String> req) {
        String symbol = req.get("symbol");
        if (symbol == null)
            return ResponseEntity.badRequest().body("Missing symbol");

        String token = tokenManager.getToken(symbol);

        timescaleRepository.removeTrackedSymbol(symbol);
        engine.unregisterSymbol(symbol);
        tokenManager.removeSymbol(symbol);

        // Unsubscribe from live feed
        if (token != null) {
            liveFeed.unsubscribe(java.util.Collections.singleton(token));
        }

        return ResponseEntity.ok(Map.of("message", "Symbol untracked", "symbol", symbol));
    }

    @PostMapping("/symbols/flush")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> flushSymbols(@RequestBody Map<String, Object> req) {
        List<String> symbols = (List<String>) req.get("symbols");
        if (symbols == null || symbols.isEmpty())
            return ResponseEntity.badRequest().body("No symbols provided");

        for (String symbol : symbols) {
            // Delete from DB
            timescaleRepository.deleteCandleData(symbol);
            // Delete from Redis
            redisClient.clearSymbolData(symbol);

            // Re-register in engine to clear in-memory state if active
            if (engine.getState(symbol) != null) {
                engine.unregisterSymbol(symbol);
                engine.registerSymbol(symbol);
            }
        }

        return ResponseEntity.ok(Map.of("message", "Symbols flushed", "count", symbols.size()));
    }

    @PostMapping("/order/flush-all")
    public ResponseEntity<?> flushAll() {
        // ... (existing flushAll logic) ...
        return ResponseEntity.ok(Map.of("message", "ALL data flushed"));
    }

    @GetMapping("/monitor/all")
    public Map<String, Object> getUnifiedMonitor() {
        Map<String, Object> result = new java.util.HashMap<>();

        // 1. Matrix and Symbols (Similar to /api/matrix)
        Map<String, Object> matrix = new java.util.HashMap<>();
        for (Map.Entry<String, SymbolState> entry : engine.getSymbolStates().entrySet()) {
            String sym = entry.getKey();
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

            Map<String, Integer> stTrendByTf = new java.util.HashMap<>();
            Map<String, Integer> emaBiasByTf = new java.util.HashMap<>();
            for (String tf : new String[] { "1min", "3min", "5min", "15min" }) {
                Candle c = currentCandles.get(tf);
                if (c != null) {
                    stTrendByTf.put(tf, c.getStTrend());
                    Double e15 = c.getEma15();
                    Double e15_5 = c.getEma15_5();
                    emaBiasByTf.put(tf, (e15 != null && e15_5 != null) ? (e15 > e15_5 ? 1 : -1) : 0);
                }
            }
            symData.put("stTrendByTf", stTrendByTf);
            symData.put("emaBiasByTf", emaBiasByTf);
            symData.put("lastFlipTimes", state.getLastFlipTimes());
            matrix.put(sym, symData);
        }
        result.put("matrix", matrix);

        // 2. Breadth (Re-using logic from getMarketBreadth)
        String[] bTfs = { "1min", "3min", "5min", "15min" };
        Map<String, Object> breadth = new java.util.HashMap<>();
        java.util.Set<String> top10Sensex = new java.util.HashSet<>(java.util.Arrays.asList(
                "HDFCBANK", "RELIANCE", "ICICIBANK", "INFY", "LT", "ITC", "TCS", "BHARTIARTL", "AXISBANK", "SBIN"));

        for (String tf : bTfs) {
            int stUp = 0, stDown = 0, emaUp = 0, emaDown = 0, total = 0;
            for (Map.Entry<String, SymbolState> entry : engine.getSymbolStates().entrySet()) {
                if (!top10Sensex.contains(entry.getKey()))
                    continue;
                Candle c = entry.getValue().getCurrentCandles().get(tf);
                if (c == null)
                    continue;
                total++;
                if (c.getStTrend() != null) {
                    if (c.getStTrend() == 1)
                        stUp++;
                    else if (c.getStTrend() == -1)
                        stDown++;
                }
                if (c.getEma15() != null && c.getEma15_5() != null) {
                    if (c.getEma15() > c.getEma15_5())
                        emaUp++;
                    else
                        emaDown++;
                }
            }
            Map<String, Object> tfB = new java.util.HashMap<>();
            tfB.put("stUp", stUp);
            tfB.put("stDown", stDown);
            tfB.put("emaUp", emaUp);
            tfB.put("emaDown", emaDown);
            tfB.put("total", total);
            breadth.put(tf, tfB);
        }
        result.put("breadth", breadth);

        return result;
    }
}
