package com.algo.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;

public class LiveUpdateBroadcaster {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private final ConcurrentHashMap<String, Long> lastBroadcastTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastBroadcastCandleTime = new ConcurrentHashMap<>();
    private static final long THROTTLE_MS = 250; // max 4 updates per second per symbol/interval

    public void broadcastUpdate(String symbol, String interval, JSONObject ohlc, JSONObject indicators) {
        String key = symbol + "_" + interval;
        long now = System.currentTimeMillis();
        long last = lastBroadcastTime.getOrDefault(key, 0L);
        String currentCandleTime = ohlc.optString("t", "");
        String lastCandleTime = lastBroadcastCandleTime.getOrDefault(key, "");

        // Broadcast if throttle time has passed OR if it's the start of a new candle
        if (now - last >= THROTTLE_MS || !currentCandleTime.equals(lastCandleTime)) {
            lastBroadcastTime.put(key, now);
            lastBroadcastCandleTime.put(key, currentCandleTime);

            JSONObject payload = new JSONObject();
            payload.put("symbol", symbol);
            payload.put("interval", interval);
            payload.put("ohlc", ohlc);
            payload.put("indicators", indicators);

            messagingTemplate.convertAndSend("/topic/updates/" + symbol + "/" + interval, payload.toString());
        }
    }
}
