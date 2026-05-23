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
    private static final long THROTTLE_MS = 50; // max 20 updates per second per symbol/interval

    public void broadcastUpdate(String symbol, String interval, JSONObject ohlc, JSONObject indicators) {
        // Legacy support (to be phased out)
        String key = symbol + "_" + interval;
        long now = System.currentTimeMillis();
        long currentCandleTime = ohlc.optLong("t", 0);
        long lastCandleTime = lastBroadcastCandleTime.getOrDefault(key, 0L);

        if (now - lastBroadcastTime.getOrDefault(key, 0L) >= THROTTLE_MS || currentCandleTime != lastCandleTime) {
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

    /**
     * ULTRA-LOW LATENCY BINARY BROADCAST.
     * constructed directly from domain objects without intermediate JSON
     * allocations.
     */
    public void broadcastUpdateOptimized(String symbol, String interval, com.algo.core.Candle candle,
            java.util.Map<String, Double> indicators, java.util.List<com.algo.core.RenkoBrick> renko) {
        String key = symbol + "_" + interval;
        long now = System.currentTimeMillis();
        long currentCandleTime = candle.getTimestamp().atZone(java.time.ZoneId.systemDefault()).toInstant()
                .toEpochMilli();
        long lastCandleTime = lastBroadcastCandleTime.getOrDefault(key, 0L);

        if (now - lastBroadcastTime.getOrDefault(key, 0L) < THROTTLE_MS && currentCandleTime == lastCandleTime) {
            return;
        }

        lastBroadcastTime.put(key, now);
        lastBroadcastCandleTime.put(key, currentCandleTime);

        try {
            com.algo.proto.ChartProtos.CandleData data = com.algo.proto.ChartProtos.CandleData.newBuilder()
                    .setT(currentCandleTime)
                    .setO(candle.getOpen())
                    .setH(candle.getHigh())
                    .setL(candle.getLow())
                    .setC(candle.getClose())
                    .setV(candle.getVolume())
                    .setEma15(indicators.getOrDefault("ema15", 0.0))
                    .setEma15Plus5(indicators.getOrDefault("ema15_5", 0.0))
                    .setEma15Plus15(indicators.getOrDefault("ema15_15", 0.0))
                    .setEma15Plus15Plus15(indicators.getOrDefault("ema15_15_15", 0.0))
                    .setEma15Plus15Plus15Plus15(indicators.getOrDefault("ema15_15_15_15", 0.0))
                    .setEma15Plus15Plus15Plus15Plus30(indicators.getOrDefault("ema15_15_15_15_30", 0.0))
                    .setStMa(indicators.getOrDefault("stMa", 0.0))
                    .setStUp(indicators.getOrDefault("stUp", 0.0))
                    .setStDn(indicators.getOrDefault("stDn", 0.0))
                    .setStTrend(indicators.getOrDefault("stTrend", 0.0).intValue())
                    .setStMa2(indicators.getOrDefault("stMa2", 0.0))
                    .setStUp2(indicators.getOrDefault("stUp2", 0.0))
                    .setStDn2(indicators.getOrDefault("stDn2", 0.0))
                    .setStTrend2(indicators.getOrDefault("stTrend2", 0.0).intValue())
                    .setSignal(indicators.getOrDefault("signal", 0.0).intValue())
                    .setTradeScore(indicators.getOrDefault("tradeScore", 0.0))
                    .setEr(indicators.getOrDefault("er", 0.0))
                    .build();

            com.algo.proto.ChartProtos.CandleUpdate.Builder binaryUpdate = com.algo.proto.ChartProtos.CandleUpdate
                    .newBuilder()
                    .setSymbol(symbol)
                    .setTimeframe(interval)
                    .setCandle(data);

            if (renko != null && !renko.isEmpty()) {
                for (com.algo.core.RenkoBrick rb : renko) {
                    binaryUpdate.addRenko(com.algo.proto.ChartProtos.RenkoBrick.newBuilder()
                            .setT(rb.getStartTime())
                            .setEt(rb.getEndTime())
                            .setO(rb.getOpen())
                            .setC(rb.getClose())
                            .build());
                }
            }

            org.springframework.messaging.simp.SimpMessageHeaderAccessor headerAccessor = org.springframework.messaging.simp.SimpMessageHeaderAccessor
                    .create();
            headerAccessor
                    .setContentType(org.springframework.util.MimeTypeUtils.parseMimeType("application/x-protobuf"));
            headerAccessor.setLeaveMutable(true);

            messagingTemplate.convertAndSend("/topic/updates/" + symbol + "/" + interval,
                    binaryUpdate.build().toByteArray(), headerAccessor.getMessageHeaders());
        } catch (Exception e) {
            // Log once and skip
        }
    }
}
