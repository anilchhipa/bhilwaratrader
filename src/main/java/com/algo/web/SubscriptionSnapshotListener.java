package com.algo.web;

import com.algo.core.Candle;
import com.algo.data.TimescaleRepository;
import com.algo.service.HistoricalDataService;
import com.algo.data.TokenManager;
import com.algo.core.Engine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Listens for new STOMP subscriptions on /topic/updates/{symbol}/{interval}
 * and immediately pushes a history snapshot to that specific client session.
 * This eliminates all REST calls for historical data.
 */
@Component
public class SubscriptionSnapshotListener implements ApplicationListener<SessionSubscribeEvent> {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionSnapshotListener.class);
    private static final int SNAPSHOT_CANDLE_LIMIT = 500;

    // Use virtual threads for non-blocking DB lookups per subscription
    private final Executor snapshotExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    @Autowired
    private TimescaleRepository timescaleRepository;
    @Autowired
    private HistoricalDataService historicalDataService;
    @Autowired
    private TokenManager tokenManager;
    @Autowired
    private Engine engine;

    @Override
    public void onApplicationEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();

        // Only handle subscriptions to /topic/updates/{symbol}/{interval}
        if (destination == null || !destination.startsWith("/topic/updates/"))
            return;

        String[] parts = destination.split("/");
        // Expected: ["", "topic", "updates", "SYMBOL", "INTERVAL"]
        if (parts.length < 5)
            return;

        String symbol = parts[3];
        String interval = parts[4];

        // Fire snapshot delivery on a virtual thread (non-blocking)
        snapshotExecutor.execute(() -> sendSnapshot(sessionId, symbol, interval, destination));
    }

    private void sendSnapshot(String sessionId, String symbol, String interval, String destination) {
        try {
            List<Candle> history = timescaleRepository.getLatestCandles(symbol, interval, SNAPSHOT_CANDLE_LIMIT);
            if (history == null || history.isEmpty())
                return;

            // Append active candle from memory if available
            com.algo.core.SymbolState state = engine.getState(symbol);
            if (state != null) {
                Candle active = state.getCurrentCandles().get(interval);
                if (active != null) {
                    boolean shouldReplace = !history.isEmpty() &&
                            active.getTimestamp().equals(history.get(history.size() - 1).getTimestamp());
                    boolean shouldAppend = !history.isEmpty() &&
                            active.getTimestamp().isAfter(history.get(history.size() - 1).getTimestamp());
                    if (shouldReplace)
                        history.set(history.size() - 1, active);
                    else if (shouldAppend)
                        history.add(active);
                }
            }

            // Indicators are already calculated and stored in TimescaleDB.
            // Recalculating them here for every websocket subscription was a major performance hit.
            Map<String, Object> snapshot = new HashMap<>();
            snapshot.put("type", "SNAPSHOT");
            snapshot.put("symbol", symbol);
            snapshot.put("interval", interval);
            snapshot.put("candles", history);

            // Send to the specific subscriber session using user-session routing
            messagingTemplate.convertAndSendToUser(
                    sessionId,
                    "/queue/snapshot",
                    mapper.writeValueAsString(snapshot),
                    createHeaders(sessionId));

            log.debug("Snapshot: sent {} candles for {}/{} to session {}", history.size(), symbol, interval, sessionId);
        } catch (Exception e) {
            log.warn("Snapshot delivery failed for {}/{}: {}", symbol, interval, e.getMessage());
        }
    }

    private org.springframework.messaging.MessageHeaders createHeaders(String sessionId) {
        org.springframework.messaging.simp.SimpMessageHeaderAccessor headerAccessor = org.springframework.messaging.simp.SimpMessageHeaderAccessor
                .create(
                        org.springframework.messaging.simp.SimpMessageType.MESSAGE);
        headerAccessor.setSessionId(sessionId);
        headerAccessor.setLeaveMutable(true);
        return headerAccessor.getMessageHeaders();
    }
}
