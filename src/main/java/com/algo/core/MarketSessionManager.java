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

    private boolean preMarketDone = false;
    private boolean flushDone = false;

    public MarketSessionManager(Engine engine, HistoricalDataService historicalDataService,
            TokenManager tokenManager) {
        this.engine = engine;
        this.historicalDataService = historicalDataService;
        this.tokenManager = tokenManager;
    }

    /**
     * Main task that runs every minute to check session state.
     */
    @Scheduled(cron = "0 * * * * MON-FRI")
    public void checkSession() {
        if (MarketUtils.isPreMarket()) {
            handlePreMarket();
        } else if (MarketUtils.isFlushTime()) {
            handleFlush();
        } else if (MarketUtils.isPostMarket()) {
            handlePostMarket();
        } else {
            // During live market hours
            resetFlags();
        }
    }

    private void handlePreMarket() {
        if (preMarketDone)
            return;

        System.out.println("MarketSessionManager: Starting Pre-Market logic...");
        for (String symbol : tokenManager.getAllSymbols()) {
            String token = tokenManager.getToken(symbol);
            if (token != null) {
                historicalDataService.loadInitialData(symbol, token);
            }
        }

        // Sync these to Redis so the UI is populated
        historicalDataService.syncAllSymbolsToRedis(engine);

        preMarketDone = true;
        flushDone = false;
        System.out.println("MarketSessionManager: Pre-Market logic completed.");
    }

    private void handleFlush() {
        if (flushDone)
            return;

        System.out.println("MarketSessionManager: It's 3:16 PM! Triggering session flush...");
        engine.flushSessionToDatabase();
        flushDone = true;
        System.out.println("MarketSessionManager: Flush completed.");
    }

    private void handlePostMarket() {
        // Any post-market cleanup if needed
    }

    private void resetFlags() {
        // Reset flags for the next session if we are in the middle of a session
        if (preMarketDone && flushDone) {
            // This is just a placeholder, logic for full day reset would go here
        }
    }

    /**
     * Manual trigger for pre-market sync (e.g., if app started late)
     */
    public void triggerManualPreMarketSync() {
        handlePreMarket();
    }
}
