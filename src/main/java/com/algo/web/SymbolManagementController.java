package com.algo.web;

import com.algo.core.Candle;
import com.algo.core.Engine;
import com.algo.data.LiveFeed;
import com.algo.data.RedisClient;
import com.algo.data.TimescaleRepository;
import com.algo.data.TokenManager;
import com.algo.data.ZerodhaClient;
import com.algo.service.HistoricalDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Controller for Symbol Tracking, Watchlist management, Symbol search, and Data Purge.
 */
@RestController
@RequestMapping("/api/symbols")
@CrossOrigin(origins = "*")
public class SymbolManagementController {
    private static final Logger logger = LoggerFactory.getLogger(SymbolManagementController.class);

    private final TokenManager tokenManager;
    private final ZerodhaClient zerodhaClient;
    private final HistoricalDataService historicalDataService;
    private final TimescaleRepository timescaleRepository;
    private final RedisClient redisClient;
    private final Engine engine;
    private final LiveFeed liveFeed;

    private volatile java.util.TreeMap<String, com.zerodhatech.models.Instrument> instrumentIndex = null;
    private final Object indexLock = new Object();

    public SymbolManagementController(TokenManager tokenManager,
                                      ZerodhaClient zerodhaClient,
                                      HistoricalDataService historicalDataService,
                                      TimescaleRepository timescaleRepository,
                                      RedisClient redisClient,
                                      Engine engine,
                                      LiveFeed liveFeed) {
        this.tokenManager = tokenManager;
        this.zerodhaClient = zerodhaClient;
        this.historicalDataService = historicalDataService;
        this.timescaleRepository = timescaleRepository;
        this.redisClient = redisClient;
        this.engine = engine;
        this.liveFeed = liveFeed;
    }

    /**
     * GET /api/symbols
     * Returns all tracked symbols with their in-memory state.
     */
    @GetMapping("")
    public Map<String, com.algo.core.SymbolState> getAllSymbols() {
        return engine.getSymbolStates();
    }

    /**
     * GET /api/symbols/search?query=RELIANCE
     * Prefix and substring search over instruments with O(log N) lookup.
     */
    @GetMapping("/search")
    public List<Map<String, String>> searchInstruments(@RequestParam String query) {
        List<Map<String, String>> result = new ArrayList<>();
        if (query == null || query.isBlank()) return result;

        final String q = query.toUpperCase().trim();

        java.util.TreeMap<String, com.zerodhatech.models.Instrument> idx = instrumentIndex;
        if (idx == null) {
            synchronized (indexLock) {
                idx = instrumentIndex;
                if (idx == null) {
                    List<com.zerodhatech.models.Instrument> all = zerodhaClient.getAllInstruments();
                    if (all == null) return result;
                    idx = new java.util.TreeMap<>();
                    for (com.zerodhatech.models.Instrument inst : all) {
                        if (inst.tradingsymbol != null) {
                            idx.put(inst.tradingsymbol.toUpperCase(), inst);
                        }
                    }
                    instrumentIndex = idx;
                    logger.info("searchInstruments: Built index with {} instruments", idx.size());
                }
            }
        }

        // Phase 1: prefix matches
        String prefix = q;
        String prefixEnd = prefix.isEmpty() ? "\uFFFF" : prefix.substring(0, prefix.length() - 1)
                + (char)(prefix.charAt(prefix.length() - 1) + 1);
        for (com.zerodhatech.models.Instrument inst : idx.subMap(prefix, prefixEnd).values()) {
            Map<String, String> m = new HashMap<>();
            m.put("symbol", inst.tradingsymbol);
            m.put("exchange", inst.exchange);
            m.put("type", inst.instrument_type);
            result.add(m);
            if (result.size() >= 50) return result;
        }

        // Phase 2: mid-string matches
        if (result.size() < 50) {
            for (com.zerodhatech.models.Instrument inst : idx.values()) {
                String sym = inst.tradingsymbol.toUpperCase();
                if (!sym.startsWith(q) && sym.contains(q)) {
                    Map<String, String> m = new HashMap<>();
                    m.put("symbol", inst.tradingsymbol);
                    m.put("exchange", inst.exchange);
                    m.put("type", inst.instrument_type);
                    result.add(m);
                    if (result.size() >= 50) break;
                }
            }
        }
        return result;
    }

    /**
     * GET /api/symbols/categorized
     * Returns pre-categorized stocks, futures, and options.
     */
    @GetMapping("/categorized")
    public Map<String, List<Map<String, String>>> getCategorizedSymbols() {
        Map<String, List<Map<String, String>>> result = new HashMap<>();
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
            Map<String, String> m = new HashMap<>();
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

    /**
     * POST /api/symbols/track
     * Tracks a new symbol, fetches initial history, and subscribes live feed.
     */
    @PostMapping("/track")
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
            // 1. Fetch initial historical data
            historicalDataService.loadInitialData(symbol, token);

            // 2. Sync missing recent candles
            for (String tf : new String[] { "1min", "3min", "5min" }) {
                historicalDataService.syncData(symbol, token, tf);
            }

            // 3. Seed downloaded history to live engine
            for (String tf : new String[] { "1min", "3min", "5min" }) {
                List<Candle> history = timescaleRepository.getLatestCandles(symbol, tf, 2500);
                if (history != null && !history.isEmpty() && engine.getState(symbol) != null) {
                    engine.getState(symbol).seedHistory(tf, history);
                }
            }

            // 4. Subscribe to live feed
            liveFeed.subscribe(Collections.singleton(token));
        }

        return ResponseEntity.ok(Map.of("message", "Symbol tracked", "symbol", symbol));
    }

    /**
     * POST /api/symbols/untrack
     * Removes symbol from watchlist and live feed.
     */
    @PostMapping("/untrack")
    public ResponseEntity<?> untrackSymbol(@RequestBody Map<String, String> req) {
        String symbol = req.get("symbol");
        if (symbol == null)
            return ResponseEntity.badRequest().body("Missing symbol");

        String token = tokenManager.getToken(symbol);

        timescaleRepository.removeTrackedSymbol(symbol);
        engine.unregisterSymbol(symbol);
        tokenManager.removeSymbol(symbol);

        if (token != null) {
            liveFeed.unsubscribe(Collections.singleton(token));
        }

        return ResponseEntity.ok(Map.of("message", "Symbol untracked", "symbol", symbol));
    }

    /**
     * POST /api/symbols/flush
     * Flushes candle data and cache for specified symbols without removing tracking.
     */
    @PostMapping("/flush")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> flushSymbols(@RequestBody Map<String, Object> req) {
        List<String> symbols = (List<String>) req.get("symbols");
        if (symbols == null || symbols.isEmpty())
            return ResponseEntity.badRequest().body("No symbols provided");

        for (String symbol : symbols) {
            timescaleRepository.deleteCandleData(symbol);
            redisClient.clearSymbolData(symbol);

            if (engine.getState(symbol) != null) {
                engine.unregisterSymbol(symbol);
                engine.registerSymbol(symbol);
            }
        }

        return ResponseEntity.ok(Map.of("message", "Symbols flushed", "count", symbols.size()));
    }

    /**
     * POST /api/symbols/purge
     * Completely deletes all history and removes symbols from tracking.
     */
    @PostMapping("/purge")
    @SuppressWarnings("unchecked")
    public ResponseEntity<?> purgeSymbols(@RequestBody Map<String, Object> req) {
        List<String> symbols = (List<String>) req.get("symbols");
        if (symbols == null || symbols.isEmpty())
            return ResponseEntity.badRequest().body("No symbols provided");

        for (String symbol : symbols) {
            String token = tokenManager.getToken(symbol);

            timescaleRepository.deleteCandleData(symbol);
            redisClient.clearSymbolData(symbol);

            timescaleRepository.removeTrackedSymbol(symbol);
            engine.unregisterSymbol(symbol);
            tokenManager.removeSymbol(symbol);

            if (token != null) {
                liveFeed.unsubscribe(Collections.singleton(token));
            }
        }
        return ResponseEntity.ok(Map.of("message", "Symbols purged", "count", symbols.size()));
    }
}
