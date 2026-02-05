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
    private String feedToken;

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
            System.out.println("AngelClient: Attempting login for " + clientCode);

            String totpCode = "";
            try {
                // Generate TOTP from Seed
                dev.samstevens.totp.code.CodeGenerator codeGenerator = new dev.samstevens.totp.code.DefaultCodeGenerator();
                dev.samstevens.totp.time.TimeProvider timeProvider = new dev.samstevens.totp.time.SystemTimeProvider();
                long counter = Math.floorDiv(timeProvider.getTime(), 30);
                totpCode = codeGenerator.generate(totp.trim().replaceAll("\\s", ""), counter);
                System.out.println("AngelClient: TOTP generated successfully.");
            } catch (Exception e) {
                System.err.println("AngelClient: TOTP Generation Failed: " + e.getMessage());
                return false;
            }

            User user = smartConnect.generateSession(clientCode, password, totpCode);
            if (user != null && user.getAccessToken() != null) {
                smartConnect.setAccessToken(user.getAccessToken());
                smartConnect.setRefreshToken(user.getRefreshToken());
                smartConnect.setUserId(clientCode);
                this.feedToken = user.getFeedToken();
                System.out.println("AngelClient: Login Successful. Session created.");
                return true;
            } else {
                System.err.println("AngelClient: Login Failed - Invalid credentials or expired TOTP.");
                return false;
            }
        } catch (Throwable e) {
            System.err.println("AngelClient: Critical login error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public SmartConnect getSmartConnect() {
        return smartConnect;
    }

    public String getFeedToken() {
        return feedToken;
    }

    private int apiHitCount = 0;

    private synchronized void checkRateLimit() {
        apiHitCount++;
        if (apiHitCount % 3 == 0) {
            try {
                Thread.sleep(1500); // Pause for 1.5 seconds after every 3 API calls
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public java.util.List<com.algo.core.Candle> fetchHistory(String token, String interval, String exchange) {
        // Default to last 5 days for intraday, 30 for daily+
        java.time.LocalDateTime endDate = java.time.LocalDateTime.now();
        int days = (interval.equals("ONE_DAY") || interval.equals("ONE_WEEK")) ? 30 : 5;
        java.time.LocalDateTime startDate = endDate.minusDays(days);
        return fetchHistory(token, interval, startDate, endDate, exchange);
    }

    public java.util.List<com.algo.core.Candle> fetchHistory(String token, String interval,
            java.time.LocalDateTime startDate, java.time.LocalDateTime endDate, String exchange) {
        java.util.List<com.algo.core.Candle> history = new java.util.ArrayList<>();

        try {
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
            java.time.format.DateTimeFormatter isoFmt = java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

            // Determine chunk size based on interval
            int chunkDays = 30; // Angel One supports 30 days for ONE_MINUTE
            if (interval.equals("ONE_DAY"))
                chunkDays = 365;
            if (interval.equals("ONE_WEEK"))
                chunkDays = 1825; // ~5 years

            java.time.LocalDateTime currentFrom = startDate;

            while (currentFrom.isBefore(endDate)) {
                java.time.LocalDateTime currentTo = currentFrom.plusDays(chunkDays);
                if (currentTo.isAfter(endDate)) {
                    currentTo = endDate;
                }

                String fromDateStr = currentFrom.format(fmt);
                String toDateStr = currentTo.format(fmt);

                JSONObject request = new JSONObject();
                request.put("exchange", exchange != null ? exchange : "NSE");
                request.put("symboltoken", token);
                request.put("interval", interval);
                request.put("fromdate", fromDateStr);
                request.put("todate", toDateStr);

                System.out.println(
                        "Fetching History for " + token + " (" + interval + ") with request: " + request.toString());

                checkRateLimit();

                JSONArray data = null;
                try {
                    System.out.println("Calling smartConnect.candleData(request)...");
                    data = smartConnect.candleData(request);
                } catch (Exception e) {
                    System.err.println("API call failed for " + token + " (" + interval + "): " + e.getMessage());
                }

                if (data != null && data.length() > 0) {
                    System.out.println("Fetched " + data.length() + " candles for " + token + " (" + interval + ")");
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
                    System.out.println("No data returned for " + token + " (" + interval + ") in chunk: " + fromDateStr
                            + " to " + toDateStr);
                }

                currentFrom = currentTo;

                // If we fetched everything or no more data, break
                if (currentTo.equals(endDate))
                    break;
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
