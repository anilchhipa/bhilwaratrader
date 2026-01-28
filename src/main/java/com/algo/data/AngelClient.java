package com.algo.data;

import com.angelbroking.smartapi.SmartConnect;
import com.angelbroking.smartapi.models.User;
import org.json.JSONArray;
import org.json.JSONObject;

public class AngelClient {
    private SmartConnect smartConnect;
    private String clientCode;
    private String password;
    private String apiKey;
    private String totp;

    public AngelClient(String clientCode, String password, String apiKey, String totp) {
        this.clientCode = clientCode;
        this.password = password;
        this.apiKey = apiKey;
        this.totp = totp;

        smartConnect = new SmartConnect();
        smartConnect.setApiKey(apiKey);
    }

    public boolean login() {
        try {
            // Generate TOTP from Seed
            dev.samstevens.totp.code.CodeGenerator codeGenerator = new dev.samstevens.totp.code.DefaultCodeGenerator();
            dev.samstevens.totp.time.TimeProvider timeProvider = new dev.samstevens.totp.time.SystemTimeProvider();
            long counter = Math.floorDiv(timeProvider.getTime(), 30);
            String totpCode = codeGenerator.generate(totp.trim().replaceAll("\\s", ""), counter);

            System.out.println("Attempting Login for " + clientCode + " with TOTP: " + totpCode);

            User user = smartConnect.generateSession(clientCode, password, totpCode);
            if (user != null && user.getAccessToken() != null) {
                System.out.println("Login Successful!");
                System.out.println("Access Token: " + user.getAccessToken().substring(0, 10) + "...");
                System.out.println("Feed Token: " + user.getFeedToken());

                // Explicitly set tokens in smartConnect instance
                smartConnect.setAccessToken(user.getAccessToken());
                smartConnect.setRefreshToken(user.getRefreshToken());
                smartConnect.setUserId(clientCode);

                return true;
            } else {
                System.out.println("Login Failed. User object is null or missing Access Token.");
                return false;
            }
        } catch (Exception e) {
            System.err.println("Login Exception: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public SmartConnect getSmartConnect() {
        return smartConnect;
    }

    private int apiHitCount = 0;

    private void checkRateLimit() {
        apiHitCount++;
        if (apiHitCount % 3 == 0) {
            try {
                Thread.sleep(1000); // Pause for 1 second after every 3 API calls
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public java.util.List<com.algo.core.Candle> fetchHistory(String token, String interval) {
        java.util.List<com.algo.core.Candle> history = new java.util.ArrayList<>();

        try {
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            java.time.format.DateTimeFormatter isoFmt = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

            // Fetch last 30 days in 5-day chunks
            java.time.LocalDateTime endDate = java.time.LocalDateTime.now();
            java.time.LocalDateTime startDate = endDate.minusDays(30);
            java.time.LocalDateTime currentFrom = startDate;

            while (currentFrom.isBefore(endDate)) {
                java.time.LocalDateTime currentTo = currentFrom.plusDays(5);
                if (currentTo.isAfter(endDate)) {
                    currentTo = endDate;
                }

                String fromDateStr = currentFrom.format(fmt);
                String toDateStr = currentTo.format(fmt);

                JSONObject request = new JSONObject();
                request.put("exchange", "NSE");
                request.put("symboltoken", token);
                request.put("interval", interval);
                request.put("fromdate", fromDateStr);
                request.put("todate", toDateStr);

                System.out.println("Fetching History for " + token + " from " + fromDateStr + " to " + toDateStr);
                // System.out.println("API Request Body: " + request.toString());

                checkRateLimit();

                JSONArray data = null;
                try {
                    data = smartConnect.candleData(request);
                } catch (Exception e) {
                    System.out.println("API call failed: " + e.getMessage());
                }

                if (data != null && data.length() > 0) {
                    System.out.println("Fetched " + data.length() + " candles.");
                    for (int i = 0; i < data.length(); i++) {
                        JSONArray candleData = data.getJSONArray(i);
                        String timeStr = candleData.getString(0);
                        double open = candleData.getDouble(1);
                        double high = candleData.getDouble(2);
                        double low = candleData.getDouble(3);
                        double close = candleData.getDouble(4);

                        history.add(new com.algo.core.Candle(open, high, low, close,
                                java.time.ZonedDateTime.parse(timeStr, isoFmt).toLocalDateTime()));
                    }
                } else {
                    System.out.println("No data for chunk: " + fromDateStr + " to " + toDateStr);
                }

                currentFrom = currentTo;
            }

            // Sort history by timestamp to ensure correct order after merging chunks
            history.sort((c1, c2) -> c1.getTimestamp().compareTo(c2.getTimestamp()));

        } catch (Exception e) {
            System.err.println("Error fetching history for " + token + ": " + e.getMessage());
        }

        if (history.isEmpty()) {
            System.err.println("!!! ERROR: API failed to return data for " + token + ". NO DATA AVAILABLE.");
        }

        return history;
    }
}
