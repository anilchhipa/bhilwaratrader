package com.algo.core;

import com.algo.service.HistoricalDataService;
import com.algo.data.TokenManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Manages the market session transitions and triggers actions.
 */
@Component
public class MarketSessionManager {
    private final Engine engine;
    private final HistoricalDataService historicalDataService;
    private final TokenManager tokenManager;

    private final com.algo.data.ZerodhaClient zerodhaClient;
    private boolean initialized = false;

    public MarketSessionManager(Engine engine, HistoricalDataService historicalDataService,
            TokenManager tokenManager, com.algo.data.ZerodhaClient zerodhaClient) {
        this.engine = engine;
        this.historicalDataService = historicalDataService;
        this.tokenManager = tokenManager;
        this.zerodhaClient = zerodhaClient;
    }

    /**
     * Main task that runs every minute to check if initialization is needed.
     * In a continuous architecture, we just need to ensure we caught up on missing
     * data.
     */
    @Scheduled(cron = "0 * * * * MON-FRI")
    public void checkSession() {
        if (!initialized) {
            handleInitialization();
        }

        // At the end of the day or completely post-market, we can force a final flush
        // of active/incomplete candles
        if (MarketUtils.isPostMarket() && initialized) {
            System.out.println("MarketSessionManager: Market is closed. Flushing final active candles...");
            engine.flushSessionToDatabase();

            // Optionally reset initialization for the next day, though usually the app
            // restarts
            initialized = false;
        }
    }

    private void handleInitialization() {
        if (zerodhaClient != null && !zerodhaClient.isLoggedIn()) {
            System.out.println("MarketSessionManager: Zerodha not logged in. Skipping initialization for now...");
            return;
        }

        System.out.println("MarketSessionManager: Starting initialization/sync logic...");
        for (String symbol : tokenManager.getAllSymbols()) {
            String token = tokenManager.getToken(symbol);
            if (token != null) {
                // This checks the DB and syncs missing candles up to the current time
                historicalDataService.loadInitialData(symbol, token);

                // Also trigger an explicit sync of missing live data just in case
                // loadInitialData
                // only grabbed the 500 minimum and missed the last few minutes
                for (String tf : new String[] { "1min", "3min", "5min", "15min" }) {
                    historicalDataService.syncData(symbol, token, tf);
                }
            }
        }

        // Sync these to Redis so the UI is populated
        historicalDataService.syncAllSymbolsToRedis(engine);

        initialized = true;
        System.out.println(
                "MarketSessionManager: Initialization completed. Transitioning to continuous live data processing.");
    }

    /**
     * Manual trigger for pre-market sync (e.g., if app started late)
     */
    public void triggerManualPreMarketSync() {
        handleInitialization();
    }
}
