package com.algo.data;

import com.angelbroking.smartapi.smartstream.models.LTP;
import com.angelbroking.smartapi.smartstream.models.TokenID;
import com.angelbroking.smartapi.smartstream.models.SmartStreamSubsMode;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;
import com.angelbroking.smartapi.smartstream.ticker.SmartStreamListener;
import com.angelbroking.smartapi.smartstream.ticker.SmartStreamTicker;
import com.angelbroking.smartapi.smartstream.models.Quote;
import com.angelbroking.smartapi.smartstream.models.SnapQuote;
import com.angelbroking.smartapi.smartstream.models.Depth;
import com.angelbroking.smartapi.smartstream.models.SmartStreamError;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

public class LiveFeed implements SmartStreamListener {
    private BiConsumer<String, Double> onTick;
    private TokenManager tokenManager;
    private com.algo.core.Engine engine;
    private SmartStreamTicker ticker;
    private String clientCode;
    private String feedToken;

    public LiveFeed(String clientCode, String apiKey,
            TokenManager tokenManager, com.algo.core.Engine engine, BiConsumer<String, Double> onTick) {
        this.clientCode = clientCode;
        this.onTick = onTick;
        this.tokenManager = tokenManager;
        this.engine = engine;

        System.out.println("LiveFeed: Initialized for " + clientCode + ". Waiting for feed token...");
    }

    public void setFeedToken(String feedToken) {
        this.feedToken = feedToken;
        if (clientCode != null && feedToken != null && !clientCode.equals("CLIENT_CODE")
                && !feedToken.trim().isEmpty()) {
            System.out.println("LiveFeed: (Re)Initializing SmartStreamTicker for " + clientCode);
            this.ticker = new SmartStreamTicker(clientCode, feedToken, this);
        }
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
                        + (feedToken != null ? "Yes" : "No") + ")");
                ticker.connect();
            } catch (Exception e) {
                System.err.println("LiveFeed: Connection failed for " + clientCode + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void subscribe(Set<String> tokens) {
        subscribedTokens.addAll(tokens);
        if (ticker != null && ticker.isConnectionOpen()) {
            Set<TokenID> tokenIds = tokens.stream()
                    .map(t -> {
                        String symbol = getSymbolFromToken(t);
                        ExchangeType type = (symbol != null && tokenManager != null)
                                ? tokenManager.getExchangeType(symbol)
                                : ExchangeType.NSE_CM;
                        return new TokenID(type, t);
                    })
                    .collect(Collectors.toSet());
            ticker.subscribe(SmartStreamSubsMode.LTP, tokenIds);
            System.out.println("LiveFeed: Sent subscription request for " + tokens.size() + " tokens.");
        } else {
            System.out.println("LiveFeed: Subscribed tokens added to pending list. Connection open: "
                    + (ticker != null && ticker.isConnectionOpen()));
        }
    }

    public void stop() {
        if (ticker != null) {
            ticker.disconnect();
        }
    }

    @Override
    public void onLTPArrival(LTP ltp) {
        String token = ltp.getToken().getToken();
        String symbol = getSymbolFromToken(token);
        if (symbol == null) {
            System.err.println("LiveFeed: Unrecognized token received: '" + token + "' (Length: "
                    + (token != null ? token.length() : 0) + ")");
            return;
        }
        double price = ltp.getLastTradedPrice() / 100.0; // LTP is typically in paise
        processTick(symbol, token, price, java.time.LocalDateTime.now());
    }

    @Override
    public void onQuoteArrival(Quote quote) {
    }

    @Override
    public void onSnapQuoteArrival(SnapQuote snapQuote) {
    }

    @Override
    public void onDepthArrival(Depth depth) {
    }

    @Override
    public void onConnected() {
        System.out.println("LiveFeed: SmartStream Connected.");
        if (!subscribedTokens.isEmpty()) {
            subscribe(subscribedTokens);
        }
    }

    @Override
    public void onDisconnected() {
        System.out.println("LiveFeed: SmartStream Disconnected.");
    }

    @Override
    public void onError(SmartStreamError smartStreamError) {
        Throwable ex = smartStreamError.getException();
        if (ex != null) {
            System.err
                    .println("LiveFeed: SmartStream Error: " + ex.getClass().getSimpleName() + " - " + ex.getMessage());
            ex.printStackTrace();
        } else {
            System.err.println("LiveFeed: SmartStream Error: No exception details provided.");
        }
    }

    @Override
    public void onPong() {
    }

    @Override
    public SmartStreamError onErrorCustom() {
        return null;
    }
}
