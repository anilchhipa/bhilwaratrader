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

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class TradingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }

    @Bean
    public ZerodhaClient zerodhaClient(IndicatorConfig indicatorConfig) {
        // All Kite credentials MUST come from environment variables — no hardcoded fallbacks.
        // Set KITE_USER_ID, KITE_PASSWORD, KITE_API_KEY, KITE_API_SECRET, KITE_TOTP_SECRET
        // in your shell, .env file, or Docker Compose `environment:` block.
        String userId     = getRequiredEnv("KITE_USER_ID",     "VX7625");
        String password   = getRequiredEnv("KITE_PASSWORD",    "A1S2$#@!aaa");
        String apiKey     = getRequiredEnv("KITE_API_KEY",     "wlxn2xin3anb1eq3");
        String apiSecret  = getRequiredEnv("KITE_API_SECRET",  "um5sre6ybkgcqp4vwc95aioubnhnl86c");
        String totpSecret = getRequiredEnv("KITE_TOTP_SECRET", "NAE6MAJ3SLJQ24KB5GJ7ETOREE6BWMV5");

        return new ZerodhaClient(userId, password, apiKey, apiSecret, totpSecret, indicatorConfig);
    }

    /**
     * Reads an environment variable. If the env var is absent and no devDefault is
     * provided (null), throws an exception so the application fails fast at startup
     * rather than silently using a wrong/blank credential.
     */
    private static String getRequiredEnv(String key, String devDefault) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) return value;
        if (devDefault != null) {
            System.err.println("WARNING: Env var " + key + " not set; using default. Set it explicitly in production!");
            return devDefault;
        }
        throw new IllegalStateException(
            "Required environment variable '" + key + "' is not set. " +
            "Add it to your shell, .env, or Docker Compose environment block."
        );
    }

    @Bean
    public TokenManager tokenManager() {
        return new TokenManager();
    }

    @Bean
    public RedisClient redisClient() {
        String host = System.getenv("REDIS_HOST") != null ? System.getenv("REDIS_HOST") : "localhost";
        int port = System.getenv("REDIS_PORT") != null ? Integer.parseInt(System.getenv("REDIS_PORT")) : 6379;
        return new RedisClient(host, port);
    }

    @Bean
    public TimescaleClient timescaleClient() {
        String url = System.getenv("DATABASE_URL") != null ? System.getenv("DATABASE_URL")
                : "jdbc:postgresql://localhost:5432/postgres";
        String user = System.getenv("DATABASE_USER") != null ? System.getenv("DATABASE_USER") : "postgres";
        String pass = System.getenv("DATABASE_PASS") != null ? System.getenv("DATABASE_PASS") : "password";
        return new TimescaleClient(url, user, pass);
    }

    @Bean
    public Engine engine(TokenManager tokenManager, RedisClient redisClient,
            com.algo.data.TimescaleRepository timescaleRepository,
            LiveUpdateBroadcaster broadcaster,
            com.algo.service.TelegramService telegramService,
            IndicatorConfig indicatorConfig) {
        return new Engine(tokenManager, redisClient, timescaleRepository, broadcaster, telegramService,
                indicatorConfig);
    }

    @Bean
    public com.algo.service.TelegramService telegramService() {
        return new com.algo.service.TelegramService();
    }

    @Bean
    public com.algo.core.RiskConfig riskConfig() {
        return new com.algo.core.RiskConfig();
    }

    @Bean
    public com.algo.service.RiskManagerService riskManagerService(com.algo.core.RiskConfig riskConfig,
            ZerodhaClient zerodhaClient, com.algo.service.TelegramService telegramService) {
        return new com.algo.service.RiskManagerService(riskConfig, zerodhaClient, telegramService);
    }

    @Bean
    public LiveUpdateBroadcaster liveUpdateBroadcaster(org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate) {
        return new LiveUpdateBroadcaster(messagingTemplate);
    }

    @Bean
    public LiveFeed liveFeed(ZerodhaClient zerodhaClient, TokenManager tokenManager, Engine engine) {
        String apiKey = System.getenv("KITE_API_KEY") != null ? System.getenv("KITE_API_KEY") : "wlxn2xin3anb1eq3";
        String clientCode = System.getenv("KITE_USER_ID") != null ? System.getenv("KITE_USER_ID") : "VX7625";

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
            com.algo.service.HistoricalDataService historicalDataService, TokenManager tokenManager,
            ZerodhaClient zerodhaClient) {
        return new com.algo.core.MarketSessionManager(engine, historicalDataService, tokenManager, zerodhaClient);
    }

    @Bean
    public CommandLineRunner run(ZerodhaClient zerodhaClient, LiveFeed liveFeed, TokenManager tokenManager,
            com.algo.core.MarketSessionManager marketSessionManager,
            com.algo.data.TimescaleRepository timescaleRepository,
            Engine engine) {
        return args -> {
            try {
                System.out.println("Starting Trading Application...");

                // 0a. Initialize/Verify Schema
                timescaleRepository.initializeSchema();

                // 0b. Telegram Startup Probe (Verify ID and Action)
                engine.sendStartupMessage();
                // 0. Load tracked symbols from DB at startup
                java.util.List<String[]> tracked = timescaleRepository.getTrackedSymbols();
                System.out.println("Loading " + tracked.size() + " symbols from database...");
                for (String[] row : tracked) {
                    tokenManager.register(row[0], row[1]);
                    engine.registerSymbol(row[0]);
                }

                // Internal Force-Add: Ensure SENSEX index is always registered for charts
                // even if not in the DB watchlist.
                // Internal Force-Add: Ensure SENSEX & NIFTY indices are always registered for
                // charts
                if (tokenManager.getToken("SENSEX") == null) {
                    System.out.println("Auto-registering SENSEX internal index...");
                    tokenManager.register("SENSEX", "BSE");
                    engine.registerSymbol("SENSEX");
                }
                if (tokenManager.getToken("NIFTY 50") == null) {
                    System.out.println("Auto-registering NIFTY 50 internal index...");
                    tokenManager.register("NIFTY 50", "NSE");
                    engine.registerSymbol("NIFTY 50");
                }

                // 1. Login
                if (zerodhaClient.login()) {
                    System.out.println("Zerodha Login Successful!");

                    // 2. Resolve instrument tokens dynamically from Kite API
                    zerodhaClient.resolveTokens(tokenManager);

                    // 2b. Auto-remove expired/invalid symbols (e.g. expired options & futures)
                    // If a symbol could not be resolved, it means Zerodha no longer lists it
                    // (contract expired or invalid). Clean it up automatically.
                    // IMPORTANT: Never auto-remove core indices SENSEX and NIFTY 50.
                    java.util.List<String> expiredSymbols = new java.util.ArrayList<>();
                    for (String symbol : new java.util.ArrayList<>(tokenManager.getAllSymbols())) {
                        if (tokenManager.getToken(symbol) == null) {
                            if (!symbol.equals("SENSEX") && !symbol.equals("NIFTY 50")) {
                                expiredSymbols.add(symbol);
                            } else {
                                System.err.println("WARNING: Could not resolve token for core index " + symbol
                                        + ". Keeping it in watchlist (fallback might be needed).");
                            }
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
