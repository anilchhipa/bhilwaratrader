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

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api")
@org.springframework.web.bind.annotation.CrossOrigin(origins = "*")
public class TradingController {

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

    @GetMapping("/config")
    public com.algo.core.IndicatorConfig getConfig() {
        return indicatorConfig;
    }

    @GetMapping("/symbols")
    public Map<String, SymbolState> getAllSymbols() {
        return engine.getSymbolStates();
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
            // Try to fetch from TimescaleDB first
            List<Candle> history = timescaleRepository.getLatestCandles(symbol, dbInterval, 2000);

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
                    System.out.println("Data for " + symbol + " (" + interval + ") is stale (last: "
                            + latest.getTimestamp() + "). Syncing...");
                    historicalDataService.syncData(symbol, token, interval);

                    // Re-fetch after sync to get updated data
                    history = timescaleRepository.getLatestCandles(symbol, dbInterval, 2000);
                }

                // Append the current active/incomplete candle from memory so the UI has the
                // absolute latest tick data
                appendActiveCandle(symbol, dbInterval, history);

                System.out.println("Serving " + history.size() + " candles from TimescaleDB for " + symbol + " ("
                        + interval + ")");
                return history;
            }

            // If database is empty, trigger data load ONLY for this interval and retry
            System.out.println("No data in TimescaleDB for " + symbol + " (" + interval + "). Loading from API...");
            historicalDataService.loadInitialData(symbol, token, interval);

            // Retry fetching from database
            history = timescaleRepository.getLatestCandles(symbol, dbInterval, 2000);
            if (history != null && !history.isEmpty()) {
                appendActiveCandle(symbol, dbInterval, history);

                System.out.println(
                        "Loaded and serving " + history.size() + " candles for " + symbol + " (" + interval + ")");
                return history;
            }

        } catch (Exception e) {
            System.err.println("Error fetching history from TimescaleDB: " + e.getMessage());
            e.printStackTrace();
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

    @PostMapping("/order/place")
    public ResponseEntity<?> placeLimitOrder(@RequestBody Map<String, Object> orderReq) {
        try {
            String symbol = (String) orderReq.get("symbol");
            String action = (String) orderReq.get("action"); // expected: "BUY" or "SELL"
            String priceStr = String.valueOf(orderReq.get("price"));
            String quantityStr = String.valueOf(orderReq.get("quantity"));

            if (symbol == null || action == null || priceStr == null || quantityStr == null) {
                return ResponseEntity.badRequest().body("Missing required order parameters");
            }

            double price = Double.parseDouble(priceStr);
            int quantity = Integer.parseInt(quantityStr);

            System.out.println("API Request - Place Order: symbol=" + symbol + ", action=" + action + ", price=" + price
                    + ", quantity=" + quantity);

            String token = tokenManager.getToken(symbol);
            if (token == null) {
                return ResponseEntity.badRequest().body("Invalid symbol. Token not found.");
            }

            // Route to Zerodha Client to place order
            String orderId = zerodhaClient.placeLimitOrder(symbol, token, action, price, quantity);

            if (orderId != null) {
                return ResponseEntity.ok(Map.of("message", "Order placed successfully", "orderId", orderId));
            } else {
                return ResponseEntity.status(500).body(Map.of("error", "Failed to place order via API"));
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body(Map.of("error", "Exception while placing order: " + e.getMessage()));
        }
    }

    @PostMapping("/order/modify")
    public ResponseEntity<?> modifyLimitOrder(@RequestBody Map<String, Object> orderReq) {
        try {
            String orderId = (String) orderReq.get("orderId");
            String symbol = (String) orderReq.get("symbol");
            String action = (String) orderReq.get("action"); // expected: "BUY" or "SELL"
            String priceStr = String.valueOf(orderReq.get("price"));
            String quantityStr = String.valueOf(orderReq.get("quantity"));

            if (orderId == null || symbol == null || action == null || priceStr == null || quantityStr == null) {
                return ResponseEntity.badRequest().body("Missing required order parameters");
            }

            double price = Double.parseDouble(priceStr);
            int quantity = Integer.parseInt(quantityStr);

            String token = tokenManager.getToken(symbol);
            if (token == null) {
                return ResponseEntity.badRequest().body("Invalid symbol. Token not found.");
            }

            // Route to Zerodha Client to modify order
            String newOrderId = zerodhaClient.modifyLimitOrder(orderId, symbol, token, action, price, quantity);

            if (newOrderId != null) {
                return ResponseEntity.ok(Map.of("message", "Order modified successfully", "orderId", newOrderId));
            } else {
                return ResponseEntity.status(500)
                        .body(Map.of("error", "Failed to modify order via API or API returned error"));
            }

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500)
                    .body(Map.of("error", "Exception while modifying order: " + e.getMessage()));
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
        result.put("OPTIONS", toSimpleList(zerodhaClient.getInstrumentsByType("OPTIONS"), 500));

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

    @PostMapping("/symbols/flush-all")
    public ResponseEntity<?> flushAll() {
        // Delete ALL from DB
        timescaleRepository.flushAllData();
        // Delete ALL from Redis
        redisClient.flushAll();

        // Re-register all tracked symbols in engine to clear in-memory state
        for (String symbol : engine.getSymbolStates().keySet()) {
            engine.unregisterSymbol(symbol);
            engine.registerSymbol(symbol);
        }

        return ResponseEntity.ok(Map.of("message", "ALL data flushed"));
    }
}
