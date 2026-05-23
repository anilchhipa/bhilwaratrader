package com.algo.data;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

public class TokenManager {
    private Map<String, String> symbolToToken = new ConcurrentHashMap<>();
    private Map<String, String> tokenToSymbol = new ConcurrentHashMap<>();
    private Map<String, String> symbolToExchange = new ConcurrentHashMap<>();

    public TokenManager() {
        // Fallback for core indices in case resolution fails
        updateToken("SENSEX", "265");
        updateToken("NIFTY 50", "256001");
        symbolToExchange.put("SENSEX", "BSE");
        symbolToExchange.put("NIFTY 50", "NSE");
    }

    /**
     * Unregister a symbol.
     */
    public void removeSymbol(String symbol) {
        String token = symbolToToken.remove(symbol);
        if (token != null) {
            tokenToSymbol.remove(token);
        }
        symbolToExchange.remove(symbol);
    }

    public void register(String symbol, String exchange) {
        // ConcurrentHashMap doesn't allow null values. We'll leave the token as absent
        // until resolved.
        symbolToExchange.put(symbol, exchange);
    }

    /** Called by ZerodhaClient.resolveTokens() after fetching from Kite API. */
    public void updateToken(String symbol, String token) {
        symbolToToken.put(symbol, token);
        tokenToSymbol.put(token, symbol);
    }

    public String getToken(String symbol) {
        return symbolToToken.get(symbol);
    }

    public String getExchangeType(String symbol) {
        return symbolToExchange.getOrDefault(symbol, "NSE");
    }

    public String getSymbol(String token) {
        if (token == null)
            return null;
        return tokenToSymbol.get(token.trim());
    }

    public Set<String> getAllSymbols() {
        return symbolToExchange.keySet();
    }

    /** Returns only symbols that have a resolved token (ready for subscription). */
    public Set<String> getResolvedTokens() {
        Set<String> tokens = new java.util.HashSet<>();
        for (Map.Entry<String, String> e : symbolToToken.entrySet()) {
            if (e.getValue() != null)
                tokens.add(e.getValue());
        }
        return tokens;
    }
}
