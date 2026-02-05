package com.algo.web;

import com.algo.core.Engine;
import com.algo.core.SymbolState;
import com.algo.core.Candle;
import com.algo.data.AngelClient;
import com.algo.data.TokenManager;
import com.algo.data.TimescaleRepository;
import com.algo.service.HistoricalDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@RestController
@RequestMapping("/api")
@org.springframework.web.bind.annotation.CrossOrigin(origins = "*")
public class TradingController {

    @Autowired
    private Engine engine;

    @Autowired
    private AngelClient angelClient;

    @Autowired
    private TokenManager tokenManager;

    @Autowired
    private TimescaleRepository timescaleRepository;

    @Autowired
    private HistoricalDataService historicalDataService;

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
            List<Candle> history = timescaleRepository.getLatestCandles(symbol, dbInterval, 500);

            if (history != null && !history.isEmpty()) {
                System.out.println("Serving " + history.size() + " candles from TimescaleDB for " + symbol + " ("
                        + interval + ")");
                return history;
            }

            // If database is empty, trigger data load and retry
            System.out.println("No data in TimescaleDB for " + symbol + " (" + interval + "). Loading from API...");
            historicalDataService.loadInitialData(symbol, token);

            // Retry fetching from database
            history = timescaleRepository.getLatestCandles(symbol, dbInterval, 500);
            if (history != null && !history.isEmpty()) {
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
}
