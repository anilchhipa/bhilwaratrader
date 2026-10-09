package com.algo.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.json.JSONObject;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LiveUpdateBroadcaster {
    private static final Logger logger = LoggerFactory.getLogger(LiveUpdateBroadcaster.class);

    private final SimpMessagingTemplate messagingTemplate;

    private final ConcurrentHashMap<String, Long> lastBroadcastTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastBroadcastCandleTime = new ConcurrentHashMap<>();
    private static final long THROTTLE_MS = 50; // max 20 updates per second per symbol/interval

    @Autowired
    public LiveUpdateBroadcaster(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void broadcastUpdate(String symbol, String interval, JSONObject ohlc, JSONObject indicators) {
        if (messagingTemplate == null) {
            logger.warn("LiveUpdateBroadcaster: messagingTemplate is null");
            return;
        }
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

    private static final com.fasterxml.jackson.databind.ObjectMapper OBJECT_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public void broadcastRenkoUpdate(String symbol, Object payload) {
        if (messagingTemplate == null) return;
        try {
            String json = OBJECT_MAPPER.writeValueAsString(payload);
            messagingTemplate.convertAndSend("/topic/renko/" + symbol, json);
        } catch (Exception e) {
            logger.error("Error broadcasting Renko: {}", e.getMessage());
        }
    }

    private static double getDouble(java.util.Map<String, Double> map, String key) {
        if (map == null) return 0.0;
        Double v = map.get(key);
        return v != null ? v : 0.0;
    }

    private static int getInt(java.util.Map<String, Double> map, String key) {
        if (map == null) return 0;
        Double v = map.get(key);
        return v != null ? v.intValue() : 0;
    }

    /**
     * ULTRA-LOW LATENCY BINARY BROADCAST.
     * constructed directly from domain objects without intermediate JSON
     * allocations.
     */
    public void broadcastUpdateOptimized(String symbol, String interval, com.algo.core.Candle candle,
            java.util.Map<String, Double> indicators) {
        
        if (messagingTemplate == null) {
            logger.error("LiveUpdateBroadcaster: messagingTemplate is NULL! Cannot broadcast to {}/{}", symbol, interval);
            return;
        }

        long currentCandleTime = candle.getEpochMilli();

        try {
            com.algo.proto.ChartProtos.CandleData data = com.algo.proto.ChartProtos.CandleData.newBuilder()
                    .setT(currentCandleTime)
                    .setO(candle.getOpen())
                    .setH(candle.getHigh())
                    .setL(candle.getLow())
                    .setC(candle.getClose())
                    .setV(candle.getVolume())
                    .setEma15(getDouble(indicators, "ema15"))
                    .setEma15Plus5(getDouble(indicators, "ema15_5"))
                    .setEma15Plus15(getDouble(indicators, "ema15_15"))
                    .setEma15Plus15Plus15(getDouble(indicators, "ema15_15_15"))
                    .setStMa(getDouble(indicators, "stMa"))
                    .setStUp(getDouble(indicators, "stUp"))
                    .setStDn(getDouble(indicators, "stDn"))
                    .setStTrend(getInt(indicators, "stTrend"))
                    .setStMa2(getDouble(indicators, "stMa2"))
                    .setStUp2(getDouble(indicators, "stUp2"))
                    .setStDn2(getDouble(indicators, "stDn2"))
                    .setStTrend2(getInt(indicators, "stTrend2"))
                    .setHaTrailingSl(getDouble(indicators, "haTrailingSl"))
                    .setHaTrend(getInt(indicators, "haTrend"))
                    .setHtfTrailingSl(getDouble(indicators, "htfTrailingSl"))
                    .setHtfTrend(getInt(indicators, "htfTrend"))
                    .build();

            com.algo.proto.ChartProtos.CandleUpdate.Builder binaryUpdate = com.algo.proto.ChartProtos.CandleUpdate
                    .newBuilder()
                    .setSymbol(symbol)
                    .setTimeframe(interval)
                    .setCandle(data);

            org.springframework.messaging.simp.SimpMessageHeaderAccessor headerAccessor = org.springframework.messaging.simp.SimpMessageHeaderAccessor
                    .create();
            headerAccessor
                    .setContentType(org.springframework.util.MimeTypeUtils.APPLICATION_OCTET_STREAM);
            headerAccessor.setLeaveMutable(true);

            org.springframework.messaging.Message<byte[]> message = org.springframework.messaging.support.MessageBuilder
                    .withPayload(binaryUpdate.build().toByteArray())
                    .copyHeaders(headerAccessor.getMessageHeaders())
                    .build();
            messagingTemplate.send("/topic/updates/" + symbol + "/" + interval, message);
        } catch (Exception e) {
            logger.error("LiveUpdateBroadcaster broadcast failed for {}/{}: {}", symbol, interval, e.getMessage(), e);
        }
    }
}
