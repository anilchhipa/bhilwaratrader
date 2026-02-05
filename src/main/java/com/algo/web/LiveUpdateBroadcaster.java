package com.algo.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.json.JSONObject;

public class LiveUpdateBroadcaster {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    public void broadcastUpdate(String symbol, String interval, JSONObject ohlc, JSONObject indicators) {
        JSONObject payload = new JSONObject();
        payload.put("symbol", symbol);
        payload.put("interval", interval);
        payload.put("ohlc", ohlc);
        payload.put("indicators", indicators);

        messagingTemplate.convertAndSend("/topic/updates", payload.toString());
    }
}
