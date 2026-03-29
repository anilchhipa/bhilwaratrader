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
                            active.getTimestamp());
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

    // ─────────────────────────────────────────────────────────────────────────
    // ACCOUNT ENDPOINTS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/account/funds
     * Returns available cash, used debits, and used span for equity segment.
     * Example response: { "net": 50000.0, "available_cash": 48000.0, ... }
     */
    @GetMapping("/account/funds")
    public ResponseEntity<?> getAccountFunds() {
        try {
            java.util.Map<String, Double> funds = zerodhaClient.getAvailableFunds();
            return ResponseEntity.ok(funds);
        } catch (Exception e) {
            logger.error("Error fetching funds: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to fetch funds: " + e.getMessage()));
        }
    }

    /**
     * GET /api/account/max-qty?price=1300&leverage=5
     * Returns maximum intraday quantity you can buy at the given price.
     * leverage defaults to 5 (Zerodha MIS standard).
     */
    @GetMapping("/account/max-qty")
    public ResponseEntity<?> getMaxIntradayQty(
            @RequestParam String symbol,
            @RequestParam double price,
            @RequestParam(defaultValue = "5") double leverage) {
        try {
            int maxQty = zerodhaClient.getMaxIntradayQty(price, leverage);
            int lotSize = zerodhaClient.getLotSize(symbol);
            if (lotSize > 1) {
                maxQty = (maxQty / lotSize) * lotSize;
            }

            java.util.Map<String, Double> funds = zerodhaClient.getAvailableFunds();
            double available = funds.getOrDefault("net", 0.0);
            return ResponseEntity.ok(Map.of(
                    "maxQty", maxQty,
                    "available", available,
                    "lotSize", lotSize,
                    "leverage", leverage,
                    "price", price,
                    "totalNeeded", price * maxQty / leverage));
        } catch (Exception e) {
            logger.error("Error calculating max qty: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/account/charges
     * Body: { "buyPrice": 1300.0, "sellPrice": 1302.0, "qty": 200, "exchange":
     * "NSE" }
     * Returns itemised charge breakdown for an intraday round-trip.
     */
    @PostMapping("/account/charges")
    public ResponseEntity<?> calculateCharges(@RequestBody Map<String, Object> req) {
        try {
            double buyPrice = ((Number) req.get("buyPrice")).doubleValue();
            double sellPrice = ((Number) req.get("sellPrice")).doubleValue();
            int qty = ((Number) req.get("qty")).intValue();
            String exchange = (String) req.getOrDefault("exchange", "NSE");

            java.util.Map<String, Double> charges = zerodhaClient.calculateIntradayCharges(buyPrice, sellPrice, qty,
                    exchange);

            double grossProfit = (sellPrice - buyPrice) * qty;
            double netProfit = grossProfit - charges.get("total");

            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>(charges);
            result.put("gross_profit", Math.round(grossProfit * 100.0) / 100.0);
            result.put("net_profit", Math.round(netProfit * 100.0) / 100.0);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error calculating charges: {}", e.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Bad request: " + e.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIMIT ORDER ENDPOINTS (used by drag-drop line on chart)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/order/place
     * Body: { "symbol": "RELIANCE", "exchange": "NSE", "txnType": "BUY",
     * "qty": 200, "price": 1300.50, "product": "MIS" }
     * Places a new limit order and returns the Zerodha order ID.
     */
    @PostMapping("/order/place")
    public ResponseEntity<?> placeOrder(@RequestBody Map<String, Object> req) {
        try {
            String symbol = (String) req.get("symbol");
            String uiExchange = (String) req.get("exchange");
            String exchange = tokenManager != null && symbol != null ? tokenManager.getExchangeType(symbol) : "NSE";
            if (uiExchange != null && !uiExchange.equals(exchange)) {
                logger.warn("Exchange warning for {}: Using backend map ({}) over UI provided ({})", symbol, exchange,
                        uiExchange);
            }
            String txnType = (String) req.get("txnType"); // "BUY" or "SELL"
            int qty = ((Number) req.get("qty")).intValue();
            double price = ((Number) req.get("price")).doubleValue();
            String product = (String) req.getOrDefault("product", "MIS");

            String reqVariety = (String) req.get("variety");
            String variety = reqVariety != null ? reqVariety
                    : (com.algo.core.MarketUtils.isMarketOpen() ? "regular" : "amo");

            if (symbol == null || txnType == null || qty <= 0 || price <= 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing required fields: symbol, txnType, qty, price"));
            }

            // Round price to nearest 0.05 (NSE/BSE tick size requirement)
            price = Math.round(price * 20.0) / 20.0;

            // Round quantity to nearest multiple of Lot Size
            int lotSize = zerodhaClient.getLotSize(symbol);
            if (lotSize > 1) {
                int roundedQty = (qty / lotSize) * lotSize;
                if (roundedQty == 0)
                    roundedQty = lotSize; // Minimum 1 lot
                if (roundedQty != qty) {
                    logger.info("Rounding quantity for {}: {} -> {} (Lot Size: {})", symbol, qty, roundedQty, lotSize);
                    qty = roundedQty;
                }
            }

            String orderId = zerodhaClient.placeLimitOrder(symbol, exchange, txnType, qty, price, product, variety);
            return ResponseEntity.ok(Map.of(
                    "orderId", orderId,
                    "symbol", symbol,
                    "txnType", txnType,
                    "qty", qty,
                    "price", price,
                    "product", product,
                    "status", "PLACED"));
        } catch (Exception e) {
            logger.error("Error placing order: {}", e.getMessage());
            return ResponseEntity.status(500)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PUT /api/order/modify
     * Body: { "orderId": "240101000012345", "price": 1305.00, "qty": 200 }
     * Modifies the price of an existing pending limit order (used when line is
     * dragged).
     */
    @org.springframework.web.bind.annotation.PutMapping("/order/modify")
    public ResponseEntity<?> modifyOrder(@RequestBody Map<String, Object> req) {
        try {
            String orderId = (String) req.get("orderId");
            double newPrice = ((Number) req.get("price")).doubleValue();
            int qty = ((Number) req.get("qty")).intValue();

            if (orderId == null || newPrice <= 0 || qty <= 0) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "Missing required fields: orderId, price, qty"));
            }

            // Round price to nearest 0.05
            newPrice = Math.round(newPrice * 20.0) / 20.0;

            // Round quantity to nearest multiple of Lot Size if symbol can be matched
            // In modify request, we often don't have the symbol. We'd need to fetch order
            // details first.
            // For now, we assume the UI passed a correct qty, but price is the main
            // drag-target.

            String reqVariety = (String) req.get("variety");
            String variety = reqVariety != null ? reqVariety
                    : (com.algo.core.MarketUtils.isMarketOpen() ? "regular" : "amo");

            String updated = zerodhaClient.modifyLimitOrder(orderId, newPrice, qty, variety);
            return ResponseEntity.ok(Map.of(
                    "orderId", updated,
                    "newPrice", newPrice,
                    "status", "MODIFIED"));
        } catch (Exception e) {
            logger.error("Error modifying order: {}", e.getMessage());
            return ResponseEntity.status(500)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * DELETE /api/order/cancel/{orderId}
     * Cancels a pending limit order (used when the order line is removed from
     * chart).
     */
    @org.springframework.web.bind.annotation.DeleteMapping("/order/cancel/{orderId}")
    public ResponseEntity<?> cancelOrder(@PathVariable String orderId) {
        try {
            boolean cancelled = zerodhaClient.cancelOrder(orderId);
            if (cancelled) {
                return ResponseEntity.ok(Map.of("orderId", orderId, "status", "CANCELLED"));
            } else {
                return ResponseEntity.internalServerError()
                        .body(Map.of("error", "Cancellation failed. Order may be already filled."));
            }
        } catch (Exception e) {
            logger.error("Error cancelling order: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/monitor/all")
    public Map<String, Object> getUnifiedMonitor() {
        Map<String, Object> result = new java.util.HashMap<>();

        // 1. Matrix and Symbols (Similar to /api/matrix)
        Map<String, Object> matrix = new java.util.HashMap<>();
        for (Map.Entry<String, SymbolState> entry : engine.getSymbolStates().entrySet()) {
            String sym = entry.getKey();
            if (sym.equals("SENSEX"))
                continue; // Explicitly hide SENSEX from the left panel/matrix

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

        return result;
    }
}
