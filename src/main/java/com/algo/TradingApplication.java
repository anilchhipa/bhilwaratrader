package com.algo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import com.algo.data.TokenManager;
import com.algo.core.Engine;
import com.algo.data.RedisClient;
import com.algo.data.TimescaleClient;
import com.algo.data.AngelClient;
import com.algo.data.LiveFeed;
import com.algo.web.LiveUpdateBroadcaster;
import org.springframework.boot.CommandLineRunner;
import java.util.Set;
import java.util.HashSet;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class TradingApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }

    @Bean
    public AngelClient angelClient() {
        // Use user credentials
        return new AngelClient("A436240", "2016", "ij2Si8cy", "H2CJID2A2BS47VZMHF7OBKGNZE");
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
            LiveUpdateBroadcaster broadcaster) {
        return new Engine(tokenManager, redisClient, timescaleRepository, broadcaster);
    }

    @Bean
    public LiveUpdateBroadcaster liveUpdateBroadcaster() {
        return new LiveUpdateBroadcaster();
    }

    @Bean
    public LiveFeed liveFeed(TokenManager tokenManager, Engine engine) {
        String clientCode = "A436240";
        String apiKey = "ij2Si8cy";

        return new LiveFeed(clientCode, apiKey, tokenManager, engine, (token, price) -> {
            // Optional: Log tick or extra processing
        });
    }

    @Bean
    public com.algo.data.TimescaleRepository timescaleRepository(TimescaleClient timescaleClient) {
        return new com.algo.data.TimescaleRepository(timescaleClient);
    }

    @Bean
    public com.algo.service.HistoricalDataService historicalDataService(AngelClient angelClient,
            com.algo.data.TimescaleRepository timescaleRepository) {
        return new com.algo.service.HistoricalDataService(angelClient, timescaleRepository);
    }

    @Bean
    public com.algo.core.MarketSessionManager marketSessionManager(Engine engine,
            com.algo.service.HistoricalDataService historicalDataService, TokenManager tokenManager) {
        return new com.algo.core.MarketSessionManager(engine, historicalDataService, tokenManager);
    }

    @Bean
    public CommandLineRunner run(AngelClient angelClient, LiveFeed liveFeed, TokenManager tokenManager,
            com.algo.core.MarketSessionManager marketSessionManager) {
        return args -> {
            try {
                System.out.println("Starting Trading Application...");

                // 1. Login
                if (angelClient.login()) {
                    System.out.println("Angel One Login Successful!");

                    // 2. Trigger Pre-Market Sync if needed (or always on startup for safety)
                    System.out.println("Initializing market session...");
                    marketSessionManager.triggerManualPreMarketSync();
                    System.out.println("Market session initialization complete!");

                    // 3. Set Feed Token and Connect
                    liveFeed.setFeedToken(angelClient.getFeedToken());
                    liveFeed.connect();

                    // 4. Subscribe to all symbols in TokenManager
                    Set<String> tokens = new HashSet<>();
                    for (String symbol : tokenManager.getAllSymbols()) {
                        tokens.add(tokenManager.getToken(symbol));
                    }
                    liveFeed.subscribe(tokens);
                    System.out.println("Subscribed to " + tokens.size() + " symbols.");
                } else {
                    System.err.println("Angel One Login FAILED. Live feed will not be available.");
                }
            } catch (Throwable t) {
                System.err.println("CRITICAL STARTUP ERROR: " + t.getMessage());
                t.printStackTrace();
            }
        };
    }
}
