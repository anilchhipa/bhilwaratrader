package com.algo.data;

import java.util.HashMap;
import java.util.Map;
import com.angelbroking.smartapi.smartstream.models.ExchangeType;

public class TokenManager {
    private Map<String, String> symbolToToken = new HashMap<>();
    private Map<String, String> tokenToSymbol = new HashMap<>();
    private Map<String, ExchangeType> symbolToExchange = new HashMap<>();

    public TokenManager() {
        // Indices
        // add("NIFTY", "99926000", ExchangeType.NSE_CM); // NSE Index
        // add("BANKNIFTY", "99926009", ExchangeType.NSE_CM); // NSE Index
        // add("SENSEX", "1", ExchangeType.BSE_CM); // BSE Index

        // Sensex Options
        add("SENSEX2620583300CE", "823275", ExchangeType.BSE_FO);
        add("SENSEX2620584000PE", "823265", ExchangeType.BSE_FO);

        // Nifty Options (Example)
        // add("NIFTY26FEB22000CE", "35000", ExchangeType.NFO);
        // add("NIFTY26FEB22000PE", "35001", ExchangeType.NFO);
    }

    private void add(String symbol, String token) {
        add(symbol, token, ExchangeType.NSE_CM); // Default
    }

    private void add(String symbol, String token, ExchangeType exchangeType) {
        symbolToToken.put(symbol, token);
        tokenToSymbol.put(token, symbol);
        symbolToExchange.put(symbol, exchangeType);
    }

    public String getToken(String symbol) {
        return symbolToToken.get(symbol);
    }

    public ExchangeType getExchangeType(String symbol) {
        return symbolToExchange.getOrDefault(symbol, ExchangeType.NSE_CM);
    }

    public String getSymbol(String token) {
        if (token == null)
            return null;
        String trimmed = token.trim();
        String symbol = tokenToSymbol.get(trimmed);
        return symbol;
    }

    public java.util.Set<String> getAllSymbols() {
        return symbolToToken.keySet();
    }
}
