package com.algo.data;

import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.Tick;
import com.zerodhatech.ticker.KiteTicker;
import com.zerodhatech.ticker.OnConnect;
import com.zerodhatech.ticker.OnDisconnect;
import com.zerodhatech.ticker.OnError;
import com.zerodhatech.ticker.OnTicks;

import java.util.ArrayList;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class LiveFeed {
    private BiConsumer<String, Double> onTick;
    private TokenManager tokenManager;
    private com.algo.core.Engine engine;
    private KiteTicker ticker;
    private String clientCode;
    private String apiKey;
    private String accessToken;

    public LiveFeed(String clientCode, String apiKey,
            TokenManager tokenManager, com.algo.core.Engine engine, BiConsumer<String, Double> onTick) {
        this.clientCode = clientCode;
        this.apiKey = apiKey;
        this.onTick = onTick;
        this.tokenManager = tokenManager;
        this.engine = engine;

        System.out.println("LiveFeed: Initialized for " + clientCode + ". Waiting for access token...");
    }

    public void setFeedToken(String accessToken) {
        this.accessToken = accessToken;
        if (apiKey != null && accessToken != null && !accessToken.trim().isEmpty()) {
            System.out.println("LiveFeed: (Re)Initializing KiteTicker for " + clientCode);
            this.ticker = new KiteTicker(accessToken, apiKey);
            setupTickerListeners();
        }
    }

    private void setupTickerListeners() {
        if (ticker == null)
            return;

        ticker.setOnConnectedListener(new OnConnect() {
            @Override
            public void onConnected() {
                System.out.println("LiveFeed: KiteTicker Connected.");
                if (!subscribedTokens.isEmpty()) {
                    subscribe(subscribedTokens);
                }
            }
        });

        ticker.setOnDisconnectedListener(new OnDisconnect() {
            @Override
            public void onDisconnected() {
                System.out.println("LiveFeed: KiteTicker Disconnected.");
            }
        });

        ticker.setOnErrorListener(new OnError() {
            @Override
            public void onError(Exception e) {
                System.err.println("LiveFeed: KiteTicker Error: " + e.getMessage());
            }

            @Override
            public void onError(KiteException e) {
                System.err.println("LiveFeed: KiteTicker Error: " + e.message);
            }

            @Override
            public void onError(String s) {
                System.err.println("LiveFeed: KiteTicker Error: " + s);
            }
        });

        ticker.setOnTickerArrivalListener(new OnTicks() {
            @Override
            public void onTicks(ArrayList<Tick> ticks) {
                for (Tick tick : ticks) {
                    if (tick.getLastTradedPrice() > 0) {
                        String tokenStr = String.valueOf(tick.getInstrumentToken());
                        String symbol = getSymbolFromToken(tokenStr);
                        if (symbol == null) {
                            System.err.println("LiveFeed: Unrecognized token received: '" + tokenStr + "'");
                            continue;
                        }
                        processTick(symbol, tokenStr, tick.getLastTradedPrice(), java.time.LocalDateTime.now());
                    }
                }
            }
        });
    }

    public void processTick(String symbol, String token, double price, java.time.LocalDateTime tickTime) {
        if (symbol == null)
            return;

        if (onTick != null) {
            onTick.accept(token, price);
        }

        if (engine != null) {
            engine.onTick(token, price);
        }
    }

    private String getSymbolFromToken(String token) {
        if (tokenManager != null) {
            return tokenManager.getSymbol(token);
        }
        return token;
    }

    public void setInitialPrices(java.util.Map<String, Double> prices) {
        // Not used with real WebSocket as it provides current LTP
    }

    private final java.util.Set<String> subscribedTokens = java.util.Collections
            .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public void connect() {
        if (ticker != null) {
            try {
                System.out.println("LiveFeed: Attempting WebSocket connection for " + clientCode + " (Token: "
                        + (accessToken != null ? "Yes" : "No") + ")");
                ticker.connect();
            } catch (Exception e) {
                System.err.println("LiveFeed: Connection failed for " + clientCode + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void subscribe(Set<String> tokens) {
        subscribedTokens.addAll(tokens);
        if (ticker != null) {
            ArrayList<Long> tokenIds = new ArrayList<>();
            for (String t : tokens) {
                try {
                    tokenIds.add(Long.parseLong(t));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid token ID for Zerodha (must be numeric): " + t);
                }
            }
            if (!tokenIds.isEmpty()) {
                ticker.subscribe(tokenIds);
                ticker.setMode(tokenIds, KiteTicker.modeLTP);
                System.out.println("LiveFeed: Sent subscription request for " + tokenIds.size() + " tokens.");
            }
        } else {
            System.out.println("LiveFeed: Subscribed tokens added to pending list. Ticker not initialized yet.");
        }
    }

    public void unsubscribe(Set<String> tokens) {
        subscribedTokens.removeAll(tokens);
        if (ticker != null) {
            ArrayList<Long> tokenIds = new ArrayList<>();
            for (String t : tokens) {
                try {
                    tokenIds.add(Long.parseLong(t));
                } catch (NumberFormatException e) {
                }
            }
            if (!tokenIds.isEmpty()) {
                ticker.unsubscribe(tokenIds);
                System.out.println("LiveFeed: Sent unsubscription request for " + tokenIds.size() + " tokens.");
            }
        }
    }

    public void stop() {
        if (ticker != null) {
            ticker.disconnect();
        }
    }
}
