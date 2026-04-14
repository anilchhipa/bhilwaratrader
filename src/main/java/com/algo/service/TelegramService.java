package com.algo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Service to send real-time trading alerts to a Telegram Bot.
 * This ensures traders get push notifications on their mobile devices
 * even when the dashboard is closed.
 */
@Service
public class TelegramService {
    private static final Logger logger = LoggerFactory.getLogger(TelegramService.class);

    // Default dummy values. These should ideally be set via Environment Variables
    private String botToken = System.getenv("TELEGRAM_BOT_TOKEN");
    private String chatId = System.getenv("TELEGRAM_CHAT_ID");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public void sendMessage(String message) {
        if (botToken == null || chatId == null || botToken.isEmpty() || chatId.isEmpty()) {
            logger.warn("Telegram: Bot Token or Chat ID not configured. Skipping alert: {}", message);
            return;
        }

        String url = String.format("https://api.telegram.org/bot%s/sendMessage?chat_id=%s&text=%s&parse_mode=Markdown",
                botToken, chatId, java.net.URLEncoder.encode(message, java.nio.charset.StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenAccept(body -> logger.info("Telegram notification sent: {}", body))
                .exceptionally(t -> {
                    logger.error("Failed to send Telegram message: {}", t.getMessage());
                    return null;
                });
    }

    public void sendTrendFlipAlert(String symbol, String timeframe, int trend, boolean isSecondary) {
        String emoji = trend == 1 ? "🚀" : "🔻";
        String trendName = trend == 1 ? "*BULLISH*" : "*BEARISH*";
        String typeLabel = isSecondary ? "Trend (L)" : "Trend (S)";
        String message = String.format("%s *%s (%s)*\n%s flipped %s",
                emoji, symbol, timeframe, typeLabel, trendName);
        sendMessage(message);
    }
}
