package com.algo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import com.algo.data.TokenManager;
import com.algo.core.Engine;
import com.algo.core.IndicatorConfig;
import com.algo.data.RedisClient;
import com.algo.data.TimescaleClient;
import com.algo.data.ZerodhaClient;
import com.algo.data.LiveFeed;
import com.algo.web.LiveUpdateBroadcaster;
import org.springframework.boot.CommandLineRunner;
import java.util.Set;
import java.util.HashSet;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class TradingApplication {

    public static void main(String[] args) {
        // Skip Playwright's automatic browser check/download on every startup
        // to prevent delays and "Removing unused browser" messages.
        // Requires browsers to be already installed in ~/.cache/ms-playwright/
        System.setProperty("playwright.skip.browser.download", "true");
        System.setProperty("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");

        SpringApplication.run(TradingApplication.class, args);
    }

    @Bean
    public ZerodhaClient zerodhaClient() {
        // Use user credentials
        // userId, password, apiKey, apiSecret, totpSecret
        return new ZerodhaClient("VX7625", "A1S2$#@!aaa", "wlxn2xin3anb1eq3", "um5sre6ybkgcqp4vwc95aioubnhnl86c",
                "NAE6MAJ3SLJQ24KB5GJ7ETOREE6BWMV5");
    }

    @Bean
    public TokenManager tokenManager() {
        return new TokenManager();
    }

    @Bean
    public RedisClient redisClient() {
        return new RedisClient("localhost", 6379);
    }

    @Bean
    public TimescaleClient timescaleClient() {
        return new TimescaleClient("jdbc:postgresql://localhost:5432/postgres", "postgres", "password");
    }

    @Bean
    public Engine engine(TokenManager tokenManager, RedisClient redisClient,
            com.algo.data.TimescaleRepository timescaleRepository,
            LiveUpdateBroadcaster broadcaster,
            IndicatorConfig indicatorConfig) {
        return new Engine(tokenManager, redisClient, timescaleRepository, broadcaster, indicatorConfig);
    }

    @Bean
    public LiveUpdateBroadcaster liveUpdateBroadcaster() {
        return new LiveUpdateBroadcaster();
    }

    @Bean
    public LiveFeed liveFeed(ZerodhaClient zerodhaClient, TokenManager tokenManager, Engine engine) {
        // Use same apiKey and userId as ZerodhaClient to avoid KiteTicker 403
        String apiKey = "wlxn2xin3anb1eq3";
        String clientCode = "VX7625";

        return new LiveFeed(clientCode, apiKey, tokenManager, engine, (token, price) -> {
            // Optional: Log tick or extra processing
        });
    }

    @Bean
    public com.algo.data.TimescaleRepository timescaleRepository(TimescaleClient timescaleClient) {
        return new com.algo.data.TimescaleRepository(timescaleClient);
    }

    @Bean
    public com.algo.service.HistoricalDataService historicalDataService(ZerodhaClient zerodhaClient,
            com.algo.data.TimescaleRepository timescaleRepository,
            IndicatorConfig indicatorConfig) {
        return new com.algo.service.HistoricalDataService(zerodhaClient, timescaleRepository, indicatorConfig);
    }

    @Bean
    public com.algo.core.MarketSessionManager marketSessionManager(Engine engine,
            com.algo.service.HistoricalDataService historicalDataService, TokenManager tokenManager) {
        return new com.algo.core.MarketSessionManager(engine, historicalDataService, tokenManager);
    }

    @Bean
    public CommandLineRunner run(ZerodhaClient zerodhaClient, LiveFeed liveFeed, TokenManager tokenManager,
            com.algo.core.MarketSessionManager marketSessionManager,
            com.algo.data.TimescaleRepository timescaleRepository,
            Engine engine) {
        return args -> {
            try {
                System.out.println("Starting Trading Application...");

                // 0. Load symbols from DB
                java.util.List<String[]> tracked = timescaleRepository.getTrackedSymbols();
                System.out.println("Loading " + tracked.size() + " symbols from database...");
                for (String[] row : tracked) {
                    tokenManager.register(row[0], row[1]);
                    engine.registerSymbol(row[0]);
                }

                // 1. Login
                if (zerodhaClient.login()) {
                    System.out.println("Zerodha Login Successful!");

                    // 2. Resolve instrument tokens dynamically from Kite API
                    zerodhaClient.resolveTokens(tokenManager);

                    // 2b. Auto-remove expired/invalid symbols (e.g. expired options & futures)
                    // If a symbol could not be resolved, it means Zerodha no longer lists it
                    // (contract expired or invalid). Clean it up automatically.
                    java.util.List<String> expiredSymbols = new java.util.ArrayList<>();
                    for (String symbol : new java.util.ArrayList<>(tokenManager.getAllSymbols())) {
                        if (tokenManager.getToken(symbol) == null) {
                            expiredSymbols.add(symbol);
                        }
                    }
                    if (!expiredSymbols.isEmpty()) {
                        System.out.println("Auto-removing " + expiredSymbols.size()
                                + " expired/unresolvable symbol(s): " + expiredSymbols);
                        for (String symbol : expiredSymbols) {
                            timescaleRepository.removeTrackedSymbol(symbol);
                            tokenManager.removeSymbol(symbol);
                            engine.unregisterSymbol(symbol);
                        }
                        System.out.println("Expired symbols removed successfully.");
                    } else {
                        System.out.println("All symbols resolved successfully. No expired contracts found.");
                    }

                    // 3. Trigger Pre-Market Sync
                    System.out.println("Initializing market session...");
                    marketSessionManager.triggerManualPreMarketSync();
                    System.out.println("Market session initialization complete!");

                    // 4. Set Feed Token and Connect
                    liveFeed.setFeedToken(zerodhaClient.getAccessToken());
                    liveFeed.connect();

                    // 5. Subscribe to all RESOLVED tokens (tokens that were successfully looked up)
                    Set<String> tokens = tokenManager.getResolvedTokens();
                    liveFeed.subscribe(tokens);
                    System.out.println("Subscribed to " + tokens.size() + " symbols.");
                } else {
                    System.err.println("Zerodha Login FAILED. Live feed will not be available.");
                }
            } catch (Throwable t) {
                System.err.println("CRITICAL STARTUP ERROR: " + t.getMessage());
                t.printStackTrace();
            }
        };
    }
}
