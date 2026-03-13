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
    private KiteTicker[] tickers = new KiteTicker[3];
    private String clientCode;
    private String apiKey;
    private String accessToken;

    private final java.util.Set<String> subscribedTokens = java.util.Collections
            .newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

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
            System.out.println("LiveFeed: (Re)Initializing KiteTickers for " + clientCode);
            for (int i = 0; i < 3; i++) {
                if (tickers[i] != null) {
                    try {
                        tickers[i].disconnect();
                    } catch (Exception e) {
                    }
                }
                tickers[i] = new KiteTicker(accessToken, apiKey);
                setupTickerListeners(tickers[i], i);
            }
        }
    }

    private void setupTickerListeners(KiteTicker ticker, int tickerIndex) {
        if (ticker == null)
            return;

        ticker.setOnConnectedListener(new OnConnect() {
            @Override
            public void onConnected() {
                System.out.println("LiveFeed: KiteTicker " + tickerIndex + " Connected.");
                resubscribeAll();
            }
        });

        ticker.setOnDisconnectedListener(new OnDisconnect() {
            @Override
            public void onDisconnected() {
                System.out.println("LiveFeed: KiteTicker " + tickerIndex + " Disconnected.");
            }
        });

        ticker.setOnErrorListener(new OnError() {
            @Override
            public void onError(Exception e) {
                System.err.println("LiveFeed: KiteTicker " + tickerIndex + " Error: " + e.getMessage());
            }

            @Override
            public void onError(KiteException e) {
                System.err.println("LiveFeed: KiteTicker " + tickerIndex + " Error: " + e.message);
            }

            @Override
            public void onError(String s) {
                System.err.println("LiveFeed: KiteTicker " + tickerIndex + " Error: " + s);
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
    }

    public void connect() {
        System.out.println("LiveFeed: Attempting WebSocket connection for " + clientCode + " (Token: "
                + (accessToken != null ? "Yes" : "No") + ")");
        for (int i = 0; i < 3; i++) {
            if (tickers[i] != null) {
                try {
                    tickers[i].connect();
                } catch (Exception e) {
                    System.err.println("LiveFeed: Connection failed for KiteTicker " + i + ": " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    public void subscribe(Set<String> tokens) {
        subscribedTokens.addAll(tokens);
        resubscribeAll();
    }

    public void unsubscribe(Set<String> tokens) {
        subscribedTokens.removeAll(tokens);

        for (int i = 0; i < 3; i++) {
            if (tickers[i] != null) {
                ArrayList<Long> tokenIds = new ArrayList<>();
                for (String t : tokens) {
                    // Shard logic corresponding to subscribe
                    if (Math.abs(t.hashCode()) % 3 == i) {
                        try {
                            tokenIds.add(Long.parseLong(t));
                        } catch (NumberFormatException e) {
                        }
                    }
                }
                if (!tokenIds.isEmpty()) {
                    tickers[i].unsubscribe(tokenIds);
                    System.out.println("LiveFeed: Ticker " + i + " sent unsubscription request for " + tokenIds.size()
                            + " tokens.");
                }
            }
        }
    }

    private void resubscribeAll() {
        if (subscribedTokens.isEmpty())
            return;

        for (int i = 0; i < 3; i++) {
            if (tickers[i] != null) {
                ArrayList<Long> tokenIds = new ArrayList<>();
                for (String t : subscribedTokens) {
                    // Shard tokens across the 3 tickers deterministically based on hash
                    if (Math.abs(t.hashCode()) % 3 == i) {
                        try {
                            tokenIds.add(Long.parseLong(t));
                        } catch (NumberFormatException e) {
                            System.err.println("Invalid token ID: " + t);
                        }
                    }
                }
                if (!tokenIds.isEmpty()) {
                    try {
                        tickers[i].subscribe(tokenIds);
                        tickers[i].setMode(tokenIds, KiteTicker.modeLTP);
                        System.out.println("LiveFeed: Ticker " + i + " sent subscription request for " + tokenIds.size()
                                + " tokens.");
                    } catch (Exception e) {
                        System.err.println("LiveFeed: Error subscribing on Ticker " + i + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    public void stop() {
        for (int i = 0; i < 3; i++) {
            if (tickers[i] != null) {
                try {
                    tickers[i].disconnect();
                } catch (Exception e) {
                }
            }
        }
    }
}
