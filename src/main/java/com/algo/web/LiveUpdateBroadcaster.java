package com.algo.web;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;

public class LiveUpdateBroadcaster {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private final ConcurrentHashMap<String, Long> lastBroadcastTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastBroadcastCandleTime = new ConcurrentHashMap<>();
    private static final long THROTTLE_MS = 250; // max 4 updates per second per symbol/interval

    public void broadcastUpdate(String symbol, String interval, JSONObject ohlc, JSONObject indicators) {
        String key = symbol + "_" + interval;
        long now = System.currentTimeMillis();
        long last = lastBroadcastTime.getOrDefault(key, 0L);
        long currentCandleTime = ohlc.optLong("t", 0);
        long lastCandleTime = lastBroadcastCandleTime.getOrDefault(key, 0L);

        // Broadcast if throttle time has passed OR if it's the start of a new candle
        if (now - last >= THROTTLE_MS || currentCandleTime != lastCandleTime) {
            lastBroadcastTime.put(key, now);
            lastBroadcastCandleTime.put(key, currentCandleTime);

            try {
                com.algo.proto.ChartProtos.CandleData.Builder dataBuilder = com.algo.proto.ChartProtos.CandleData
                        .newBuilder()
                        .setT(ohlc.optLong("t", 0))
                        .setO(ohlc.optDouble("o", 0))
                        .setH(ohlc.optDouble("h", 0))
                        .setL(ohlc.optDouble("l", 0))
                        .setC(ohlc.optDouble("c", 0))
                        .setV(ohlc.optDouble("v", 0))
                        .setD(ohlc.optDouble("d", 0))
                        .setEma15(indicators.optDouble("ema15", 0))
                        .setEma15Plus5(indicators.optDouble("ema15_5", 0))
                        .setEma15Plus15(indicators.optDouble("ema15_15", 0))
                        .setEma15Plus15Plus15(indicators.optDouble("ema15_15_15", 0))
                        .setEma15Plus15Plus15Plus15(indicators.optDouble("ema15_15_15_15", 0))
                        .setEma15Plus15Plus15Plus15Plus30(indicators.optDouble("ema15_15_15_15_30", 0))
                        .setStMa(indicators.optDouble("stMa", 0))
                        .setStUp(indicators.optDouble("stUp", 0))
                        .setStDn(indicators.optDouble("stDn", 0))
                        .setStTrend(indicators.optInt("stTrend", 0))
                        .setStMa2(indicators.optDouble("stMa2", 0))
                        .setStUp2(indicators.optDouble("stUp2", 0))
                        .setStDn2(indicators.optDouble("stDn2", 0))
                        .setStTrend2(indicators.optInt("stTrend2", 0))
                        .setWhaleSignal(indicators.optInt("whaleSignal", 0));

                com.algo.proto.ChartProtos.CandleUpdate binaryUpdate = com.algo.proto.ChartProtos.CandleUpdate
                        .newBuilder()
                        .setSymbol(symbol)
                        .setTimeframe(interval)
                        .setCandle(dataBuilder.build())
                        .build();

                // Send raw binary to the topic
                messagingTemplate.convertAndSend("/topic/updates/" + symbol + "/" + interval,
                        binaryUpdate.toByteArray());
            } catch (Exception e) {
                // Fallback to JSON if Proto fails
                JSONObject payload = new JSONObject();
                payload.put("symbol", symbol);
                payload.put("interval", interval);
                payload.put("ohlc", ohlc);
                payload.put("indicators", indicators);
                messagingTemplate.convertAndSend("/topic/updates/" + symbol + "/" + interval, payload.toString());
            }
        }
    }
}
