package com.algo.data;

import java.util.HashMap;
import java.util.Map;

public class TokenManager {
    private Map<String, String> symbolToToken = new HashMap<>();
    private Map<String, String> tokenToSymbol = new HashMap<>();

    public TokenManager() {
        // Real NSE Tokens for Nifty 50
        add("RELIANCE", "2885");
        add("TCS", "11536");
        add("HDFCBANK", "1333");
        add("INFY", "1594");
        add("SBIN", "3045");
        add("ICICIBANK", "4963");
        add("BHARTIARTL", "10604");
        add("KOTAKBANK", "1922");
        add("LT", "11483");
        add("ITC", "1660");
        add("AXISBANK", "5900");
        add("HINDUNILVR", "1330");
        add("ADANIENT", "25");
        add("BAJFINANCE", "317");
        add("ASIANPAINT", "236");
    }

    private void add(String symbol, String token) {
        symbolToToken.put(symbol, token);
        tokenToSymbol.put(token, symbol);
    }

    public String getToken(String symbol) {
        return symbolToToken.get(symbol);
    }

    public String getSymbol(String token) {
        return tokenToSymbol.get(token);
    }
}
