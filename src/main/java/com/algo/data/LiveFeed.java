package com.algo.data;

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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LiveFeed {
    private static final Logger logger = LoggerFactory.getLogger(LiveFeed.class);
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

        logger.info("LiveFeed: Initialized for {}. Waiting for access token...", clientCode);
    }

    public void setFeedToken(String accessToken) {
        this.accessToken = accessToken;
        if (apiKey != null && accessToken != null && !accessToken.trim().isEmpty()) {
            logger.info("LiveFeed: (Re)Initializing KiteTickers for {}", clientCode);
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
                logger.info("LiveFeed: KiteTicker {} Connected.", tickerIndex);
                resubscribeAll();
            }
        });

        ticker.setOnDisconnectedListener(new OnDisconnect() {
            @Override
            public void onDisconnected() {
                logger.info("LiveFeed: KiteTicker {} Disconnected.", tickerIndex);
            }
        });

        ticker.setOnErrorListener(new OnError() {
            @Override
            public void onError(Exception e) {
                logger.error("LiveFeed: KiteTicker {} Error: {}", tickerIndex, e.getMessage());
            }

            @Override
            public void onError(KiteException e) {
                logger.error("LiveFeed: KiteTicker {} Error: {}", tickerIndex, e.message);
            }

            @Override
            public void onError(String s) {
                logger.error("LiveFeed: KiteTicker {} Error: {}", tickerIndex, s);
            }
        });

        ticker.setOnTickerArrivalListener(new OnTicks() {
            @Override
            public void onTicks(ArrayList<Tick> ticks) {
                for (Tick tick : ticks) {
                    if (tick.getLastTradedPrice() > 0) {
                        String tokenStr = String.valueOf(tick.getInstrumentToken());
                        String symbol = getSymbolFromToken(tokenStr);
                        if (symbol == null)
                            continue;

                        long lastQty = (long) tick.getLastTradedQuantity();
                        double delta = 0;

                        // Identify aggressive side based on Tick vs Bid/Ask
                        if (tick.getMarketDepth() != null && tick.getMarketDepth().get("buy") != null
                                && tick.getMarketDepth().get("sell") != null) {
                            double bestBid = tick.getMarketDepth().get("buy").get(0).getPrice();
                            double bestAsk = tick.getMarketDepth().get("sell").get(0).getPrice();

                            if (tick.getLastTradedPrice() >= bestAsk)
                                delta = (double) lastQty;
                            else if (tick.getLastTradedPrice() <= bestBid)
                                delta = (double) -lastQty;
                        }

                        if (engine != null) {
                            engine.onTick(tokenStr, tick.getLastTradedPrice(), delta);
                        }
                    }
                }
            }
        });
    }

    public void processTick(String symbol, String token, double price, double delta, java.time.LocalDateTime tickTime) {
        if (symbol == null)
            return;

        if (onTick != null) {
            onTick.accept(token, price);
        }

        if (engine != null) {
            engine.onTick(token, price, delta);
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
        logger.info("LiveFeed: Attempting WebSocket connection for {} (Token: {})", clientCode,
                (accessToken != null ? "Yes" : "No"));
        for (int i = 0; i < 3; i++) {
            if (tickers[i] != null) {
                try {
                    tickers[i].connect();
                } catch (Exception e) {
                    logger.error("LiveFeed: Connection failed for KiteTicker {}: {}", i, e.getMessage());
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
                    logger.info("LiveFeed: Ticker {} sent unsubscription request for {} tokens.", i, tokenIds.size());
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
                            logger.error("Invalid token ID: {}", t);
                        }
                    }
                }
                if (!tokenIds.isEmpty()) {
                    try {
                        tickers[i].subscribe(tokenIds);
                        tickers[i].setMode(tokenIds, KiteTicker.modeFull);
                        logger.info("LiveFeed: Ticker {} sent subscription request for {} tokens.", i, tokenIds.size());
                    } catch (Exception e) {
                        logger.error("LiveFeed: Error subscribing on Ticker {}: {}", i, e.getMessage());
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
