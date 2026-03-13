package com.algo.data;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Order;
import com.zerodhatech.kiteconnect.utils.Constants;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.io.IOException;
import java.util.Date;

public class ZerodhaClient {
    private KiteConnect kiteConnect;
    private String userId;
    private String password;
    private String apiKey;
    private String apiSecret;
    private String totpSecret;
    private String accessToken;

    public ZerodhaClient(String userId, String password, String apiKey, String apiSecret, String totpSecret) {
        this.userId = userId;
        this.password = password;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.totpSecret = totpSecret;

        kiteConnect = new KiteConnect(apiKey);
        kiteConnect.setUserId(userId);
    }

    public boolean login() {
        System.out.println("ZerodhaClient: Starting Playwright headless browser login for " + userId);

        // Generate TOTP first
        String totpCode;
        try {
            CodeGenerator codeGenerator = new DefaultCodeGenerator();
            TimeProvider timeProvider = new SystemTimeProvider();
            long counter = Math.floorDiv(timeProvider.getTime(), 30);
            totpCode = codeGenerator.generate(totpSecret.trim().replaceAll("\\s", ""), counter);
        } catch (Exception e) {
            System.err.println("ZerodhaClient: TOTP Generation Failed: " + e.getMessage());
            return false;
        }

        java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());

        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env))) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(java.util.Arrays.asList(
                            "--no-sandbox",
                            "--disable-setuid-sandbox",
                            "--disable-dev-shm-usage"));

            try (Browser browser = playwright.chromium().launch(launchOptions)) {
                BrowserContext context = browser.newContext(
                        new Browser.NewContextOptions()
                                .setViewportSize(1280, 800));
                Page page = context.newPage();

                // Track the request_token from the callback redirect
                final String[] capturedToken = { null };
                page.onRequest(request -> {
                    String url = request.url();
                    if (url.contains("request_token=")) {
                        int idx = url.indexOf("request_token=");
                        String token = url.substring(idx + 14);
                        int amp = token.indexOf("&");
                        if (amp != -1)
                            token = token.substring(0, amp);
                        capturedToken[0] = token;
                        System.out.println("ZerodhaClient: Captured request_token from redirect!");
                    }
                });

                // Step 1: Navigate to Kite Connect login page
                String loginUrl = "https://kite.trade/connect/login?api_key=" + apiKey + "&v=3";
                System.out.println("ZerodhaClient: Navigating to " + loginUrl);
                page.navigate(loginUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                System.out.println("ZerodhaClient: Current URL: " + page.url());

                // Take screenshot to diagnose if needed
                page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/kite_step1.png")));

                // Step 2: Fill in User ID and Password
                page.locator("input#userid").waitFor(new Locator.WaitForOptions().setTimeout(10000));
                page.locator("input#userid").fill(userId);
                page.locator("input#password").fill(password);
                page.locator("button[type='submit']").click();

                System.out.println("ZerodhaClient: Submitted credentials, waiting for TOTP/2FA page...");
                // Wait for any password-like field that is NOT the password field (TOTP pin)
                // Zerodha uses type="number" for TOTP with placeholder '6-digit code'
                // Try multiple possible selectors
                String totpSelector = null;
                String[] possibleTotpSelectors = {
                        "input#pin",
                        "input[type='number']",
                        "input[placeholder*='PIN']",
                        "input[placeholder*='pin']",
                        "input[placeholder*='code']",
                        "input[placeholder*='totp']",
                        "input[autocomplete='one-time-code']"
                };
                for (String sel : possibleTotpSelectors) {
                    try {
                        page.waitForSelector(sel, new Page.WaitForSelectorOptions().setTimeout(5000));
                        totpSelector = sel;
                        System.out.println("ZerodhaClient: Found TOTP input with selector: " + sel);
                        break;
                    } catch (Exception ex) {
                        // try next selector
                    }
                }

                // Take screenshot of current state after credential submit
                page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/kite_step2.png")));
                System.out
                        .println("ZerodhaClient: Screenshot saved to /tmp/kite_step2.png, current URL: " + page.url());

                if (totpSelector == null) {
                    // Dump page content for debugging
                    System.err.println("ZerodhaClient: TOTP selector not found. Page title: " + page.title());
                    System.err.println("ZerodhaClient: All inputs on page: " + page.evaluate(
                            "Array.from(document.querySelectorAll('input')).map(i => i.id + '|' + i.type + '|' + i.placeholder + '|' + i.name).join(', ')"));
                    return false;
                }

                // Step 3: Fill in TOTP
                System.out.println("ZerodhaClient: Entering TOTP: " + totpCode);
                page.locator(totpSelector).fill(totpCode);
                // Small wait to allow auto-submission (Zerodha auto-submits after 6 digits)
                try {
                    Thread.sleep(2000);
                } catch (Exception ignored) {
                }

                // Check if auto-submitted or need to click submit
                if (capturedToken[0] == null && !page.url().contains("request_token=")) {
                    try {
                        page.locator("button[type='submit']").click();
                    } catch (Exception ex) {
                        /* button may not exist or already submitted */ }
                }

                System.out.println("ZerodhaClient: TOTP submitted. Current URL: " + page.url());

                // Wait a bit for the redirect to happen and token to be captured
                try {
                    Thread.sleep(2000);
                } catch (Exception ignored) {
                }

                // Check if we already have the token (captured from the redirect event)
                if (capturedToken[0] != null && !capturedToken[0].isEmpty()) {
                    System.out.println("ZerodhaClient: Token already captured, skipping authorize step!");
                } else {
                    // Step 4: Look for the authorize button (for first-time or explicit
                    // authorization)
                    System.out.println("ZerodhaClient: Looking for authorize button...");
                    String authorizeSelector = null;
                    String[] possibleAuthSelectors = {
                            "button.button-blue",
                            "button.button-orange",
                            "button[type='submit']",
                            "a.button-blue"
                    };
                    for (String sel : possibleAuthSelectors) {
                        try {
                            page.waitForSelector(sel, new Page.WaitForSelectorOptions().setTimeout(8000));
                            authorizeSelector = sel;
                            System.out.println("ZerodhaClient: Found authorize btn: " + sel);
                            break;
                        } catch (Exception ex) {
                            // try next
                        }
                    }

                    page.screenshot(
                            new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/kite_step3.png")));
                    System.out.println("ZerodhaClient: step3 URL: " + page.url());

                    if (page.url().contains("request_token=")) {
                        System.out.println("ZerodhaClient: request_token in URL after TOTP (no auth step needed)!");
                    } else if (authorizeSelector != null) {
                        String btnText = (String) page
                                .evaluate("sel => document.querySelector(sel)?.textContent.trim()", authorizeSelector);
                        System.out.println("ZerodhaClient: Authorize page detected. Clicking '" + btnText
                                + "' button (selector: " + authorizeSelector + ")...");

                        // Force click and also try submitting the form directly as fallback
                        try {
                            page.locator(authorizeSelector).first()
                                    .click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
                        } catch (Exception clickEx) {
                            System.err.println("ZerodhaClient: Primary click failed, trying JS click/submit: "
                                    + clickEx.getMessage());
                        }
                        page.evaluate(
                                "sel => { const el = document.querySelector(sel); if(el) el.click(); else document.querySelector('form')?.submit(); }",
                                authorizeSelector);

                        System.out.println("ZerodhaClient: Clicked authorize. Waiting for redirect...");
                        try {
                            Thread.sleep(8000);
                        } catch (Exception ignored) {
                        }
                        page.screenshot(
                                new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/kite_step4.png")));
                        System.out.println("ZerodhaClient: Post-click URL: " + page.url());
                    } else {
                        System.err.println("ZerodhaClient: No authorize button found. Page Title: " + page.title());
                        System.err.println("ZerodhaClient: All buttons: " + page.evaluate(
                                "Array.from(document.querySelectorAll('button, a.button')).map(b => b.textContent.trim() + '(' + b.className + ')').join(', ')"));
                    }

                }

                // Step 5: Wait for callback redirect (with request_token)
                try {
                    page.waitForURL(url -> url.contains("request_token="),
                            new Page.WaitForURLOptions().setTimeout(25000));
                } catch (Exception ex) {
                    System.out.println("ZerodhaClient: waitForURL timed out after 25s. Final URL: " + page.url());
                    if (page.url().contains("kite.zerodha.com")) {
                        System.err.println(
                                "ZerodhaClient: Still on Zerodha domain. Login likely failed or stuck at Authorize.");
                        System.err.println("ZerodhaClient: Page text sample: "
                                + page.evaluate("document.body.innerText.substring(0, 500)"));
                    }
                }

                // Also check current URL
                String finalUrl = page.url();
                System.out.println("ZerodhaClient: Final URL: " + finalUrl);

                if (finalUrl.contains("request_token=")) {
                    int idx = finalUrl.indexOf("request_token=");
                    String token = finalUrl.substring(idx + 14);
                    int amp = token.indexOf("&");
                    if (amp != -1)
                        token = token.substring(0, amp);
                    capturedToken[0] = token;
                }

                if (capturedToken[0] == null || capturedToken[0].isEmpty()) {
                    System.err.println("ZerodhaClient: No request_token captured. Final URL: " + finalUrl);
                    return false;
                }

                // Step 6: Exchange request_token for access_token
                System.out.println("ZerodhaClient: Exchanging request_token for access_token...");
                User user = kiteConnect.generateSession(capturedToken[0], apiSecret);
                this.accessToken = user.accessToken;
                kiteConnect.setAccessToken(accessToken);
                kiteConnect.setPublicToken(user.publicToken);

                System.out.println("ZerodhaClient: Playwright Login Successful! Session created.");
                return true;
            }
        } catch (Throwable e) {
            System.err.println("ZerodhaClient: Playwright login error: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private java.util.List<com.zerodhatech.models.Instrument> instrumentCache = null;

    public java.util.List<com.zerodhatech.models.Instrument> getAllInstruments() {
        if (instrumentCache == null && kiteConnect != null) {
            try {
                System.out.println("ZerodhaClient: Initializing full instrument cache...");
                instrumentCache = kiteConnect.getInstruments();
                System.out.println("ZerodhaClient: Cached " + instrumentCache.size() + " instruments.");
            } catch (KiteException | IOException e) {
                System.err.println("ZerodhaClient: Failed to fetch all instruments: " + e.getMessage());
            } catch (Exception e) {
                System.err.println("ZerodhaClient: Unexpected error fetching instruments: " + e.getMessage());
            }
        }
        return instrumentCache;
    }

    private static final java.util.Set<String> SENSEX_30_STOCKS = new java.util.HashSet<>(java.util.Arrays.asList(
            "RELIANCE", "HDFCBANK", "ICICIBANK", "INFOSYS", "ITC", "LT", "TCS", "AXISBANK", "KOTAKBANK", "SBIN",
            "BHARTIARTL", "HINDUNILVR", "TITAN", "BAJFINANCE", "MARUTI", "SUNPHARMA", "ASIANPAINT", "HCLTECH",
            "ULTRACEMCO", "INDUSINDBK", "M&M", "NESTLEIND", "POWERGRID", "TATASTEEL", "NTPC", "JSWSTEEL",
            "ADANIPORTS", "TECHM", "WIPRO", "BAJAJFINSRV"));

    public java.util.List<com.zerodhatech.models.Instrument> getInstrumentsByType(String type) {
        java.util.List<com.zerodhatech.models.Instrument> all = getAllInstruments();
        if (all == null)
            return new java.util.ArrayList<>();

        java.util.List<com.zerodhatech.models.Instrument> filtered = new java.util.ArrayList<>();
        for (com.zerodhatech.models.Instrument inst : all) {
            String symbol = inst.tradingsymbol;
            String name = inst.name;

            if (type.equalsIgnoreCase("STOCKS")) {
                // components of SENSEX on BSE
                if (inst.instrument_type.equals("EQ") && inst.exchange.equals("BSE")) {
                    if (SENSEX_30_STOCKS.contains(symbol)) {
                        filtered.add(inst);
                    }
                }
            } else if (type.equalsIgnoreCase("FUTURES")) {
                // SENSEX Futures on BFO
                if (inst.instrument_type.equals("FUT") && inst.exchange.equals("BFO")) {
                    if (symbol.startsWith("SENSEX") || (name != null && name.equals("SENSEX"))) {
                        filtered.add(inst);
                    }
                }
            } else if (type.equalsIgnoreCase("OPTIONS")) {
                // SENSEX Options on BFO
                if ((inst.instrument_type.equals("CE") || inst.instrument_type.equals("PE"))
                        && inst.exchange.equals("BFO")) {
                    if (symbol.startsWith("SENSEX") || (name != null && name.equals("SENSEX"))) {
                        filtered.add(inst);
                    }
                }
            }
        }

        // For OPTIONS, only keep the nearest (next) expiry to reduce list size
        if (type.equalsIgnoreCase("OPTIONS") && !filtered.isEmpty()) {
            java.util.Date minExpiry = null;
            for (com.zerodhatech.models.Instrument inst : filtered) {
                if (inst.expiry != null) {
                    if (minExpiry == null || inst.expiry.before(minExpiry)) {
                        minExpiry = inst.expiry;
                    }
                }
            }
            if (minExpiry != null) {
                final java.util.Date targetExpiry = minExpiry;
                filtered.removeIf(inst -> inst.expiry == null || !inst.expiry.equals(targetExpiry));
            }
        }

        // Sort by trading symbol
        filtered.sort((a, b) -> a.tradingsymbol.compareTo(b.tradingsymbol));

        return filtered;
    }

    public KiteConnect getKiteConnect() {
        return kiteConnect;
    }

    public String getAccessToken() {
        return accessToken;
    }

    /**
     * Resolves instrument tokens for all symbols registered in the given
     * TokenManager.
     * Uses the Kite Connect getInstruments(exchange) API to look up the numeric
     * instrument_token
     * for each trading symbol. This is the CORRECT way to get tokens — option
     * contract tokens
     * are NOT fixed and change as new contracts are listed daily.
     *
     * Call this AFTER login() succeeds, before subscribing to live feed.
     *
     * @param tokenManager the TokenManager whose symbols need token resolution
     */
    public void resolveTokens(TokenManager tokenManager) {
        System.out.println("ZerodhaClient: Resolving instrument tokens for " + tokenManager.getAllSymbols().size()
                + " symbols...");

        // Group symbols by their exchange to minimize API calls (one call per exchange)
        java.util.Map<String, java.util.List<String>> byExchange = new java.util.HashMap<>();
        for (String symbol : tokenManager.getAllSymbols()) {
            String exchange = tokenManager.getExchangeType(symbol);
            byExchange.computeIfAbsent(exchange, k -> new java.util.ArrayList<>()).add(symbol);
        }

        for (java.util.Map.Entry<String, java.util.List<String>> entry : byExchange.entrySet()) {
            String exchange = entry.getKey();
            java.util.List<String> symbols = entry.getValue();
            java.util.Set<String> symbolSet = new java.util.HashSet<>(symbols);

            try {
                System.out.println("ZerodhaClient: Fetching instrument list for exchange: " + exchange);
                java.util.List<com.zerodhatech.models.Instrument> instruments = kiteConnect.getInstruments(exchange);
                System.out.println("ZerodhaClient: Got " + instruments.size() + " instruments from " + exchange);

                for (com.zerodhatech.models.Instrument inst : instruments) {
                    if (symbolSet.contains(inst.tradingsymbol)) {
                        String token = String.valueOf(inst.instrument_token);
                        tokenManager.updateToken(inst.tradingsymbol, token);
                        System.out.println("ZerodhaClient: Resolved " + inst.tradingsymbol
                                + " -> token=" + token
                                + " (exchange=" + inst.exchange + ", type=" + inst.instrument_type
                                + ", expiry=" + inst.expiry + ")");
                        symbolSet.remove(inst.tradingsymbol);
                    }
                }

                if (!symbolSet.isEmpty()) {
                    System.err.println("ZerodhaClient: WARNING - could not resolve tokens for: " + symbolSet
                            + ". Check if trading symbols are correct and contracts are active.");
                }

            } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) {
                System.err
                        .println("ZerodhaClient: Failed to fetch instruments for " + exchange + ": " + e.getMessage());
            }
        }
    }

    private int apiHitCount = 0;

    private synchronized void checkRateLimit() {
        apiHitCount++;
        if (apiHitCount % 3 == 0) {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public java.util.List<com.algo.core.Candle> fetchHistory(String instrumentToken, String interval, String exchange) {
        int days = 5; // Default for 1min, 3min
        if (interval.equals("5minute"))
            days = 10;
        else if (interval.equals("15minute"))
            days = 30;
        return fetchHistory(instrumentToken, interval, exchange, days);
    }

    public java.util.List<com.algo.core.Candle> fetchHistory(String instrumentToken, String interval, String exchange,
            int days) {
        java.time.LocalDateTime endDate = java.time.LocalDateTime.now();
        java.time.LocalDateTime startDate = endDate.minusDays(days);
        return fetchHistory(instrumentToken, interval, startDate, endDate, exchange);
    }

    public java.util.List<com.algo.core.Candle> fetchHistory(String instrumentToken, String interval,
            java.time.LocalDateTime startDate, java.time.LocalDateTime endDate, String exchange) {
        java.util.List<com.algo.core.Candle> history = new java.util.ArrayList<>();

        try {
            int chunkDays = 30;

            LocalDateTime currentFrom = startDate;

            while (currentFrom.isBefore(endDate)) {
                LocalDateTime currentTo = currentFrom.plusDays(chunkDays);
                if (currentTo.isAfter(endDate)) {
                    currentTo = endDate;
                }

                Date fromDate = Date.from(currentFrom.atZone(ZoneId.systemDefault()).toInstant());
                Date toDate = Date.from(currentTo.atZone(ZoneId.systemDefault()).toInstant());

                System.out.println("Fetching History for " + instrumentToken + " (" + interval + ")");

                checkRateLimit();

                try {
                    Date fromD = Date.from(currentFrom.atZone(ZoneId.systemDefault()).toInstant());
                    Date toD = Date.from(currentTo.atZone(ZoneId.systemDefault()).toInstant());
                    HistoricalData data = kiteConnect.getHistoricalData(fromD, toD, instrumentToken, interval, false,
                            false);

                    if (data != null && data.dataArrayList != null && !data.dataArrayList.isEmpty()) {
                        for (HistoricalData d : data.dataArrayList) {
                            LocalDateTime time = LocalDateTime.parse(d.timeStamp,
                                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ"));
                            history.add(new com.algo.core.Candle(d.open, d.high, d.low, d.close, time));
                        }
                    } else {
                        System.out.println("No data returned for " + instrumentToken + " in chunk.");
                    }
                } catch (KiteException | org.json.JSONException e) {
                    System.err.println("API call failed for " + instrumentToken + ": " + e.getMessage());
                }

                currentFrom = currentTo;
                if (currentTo.equals(endDate))
                    break;
            }

            history.sort((c1, c2) -> c1.getTimestamp().compareTo(c2.getTimestamp()));

        } catch (Exception e) {
            System.err.println("Error fetching history for " + instrumentToken + ": " + e.getMessage());
        }

        return history;
    }

    public String placeLimitOrder(String symbol, String instrumentToken, String action, double price, int quantity) {
        try {
            OrderParams orderParams = new OrderParams();
            orderParams.quantity = quantity;
            orderParams.orderType = Constants.ORDER_TYPE_LIMIT;
            orderParams.transactionType = action.equalsIgnoreCase("BUY") ? Constants.TRANSACTION_TYPE_BUY
                    : Constants.TRANSACTION_TYPE_SELL;
            orderParams.tradingsymbol = symbol;
            orderParams.price = price;

            if (symbol.contains("CE") || symbol.contains("PE")) {
                if (symbol.startsWith("SENSEX") || symbol.startsWith("BANKEX") || symbol.startsWith("BSE")) {
                    orderParams.exchange = Constants.EXCHANGE_BFO;
                } else {
                    orderParams.exchange = Constants.EXCHANGE_NFO;
                }
            } else if (symbol.startsWith("SENSEX") || symbol.startsWith("BSE")) {
                orderParams.exchange = Constants.EXCHANGE_BSE;
            } else {
                orderParams.exchange = Constants.EXCHANGE_NSE;
            }
            orderParams.validity = Constants.VALIDITY_DAY;
            // Use NRML for Options as MIS is often blocked for specific strikes/symbols
            if (symbol.contains("CE") || symbol.contains("PE")) {
                orderParams.product = Constants.PRODUCT_NRML;
            } else {
                orderParams.product = Constants.PRODUCT_MIS;
            }

            System.out
                    .println("Placing " + action + " LIMIT order for " + symbol + " at " + price + " qty " + quantity);

            // Automatically switch to AMO if market is closed
            String variety = Constants.VARIETY_REGULAR;
            if (com.algo.core.MarketUtils.isPostMarket() || com.algo.core.MarketUtils.isPreMarket()) {
                variety = Constants.VARIETY_AMO;
                System.out.println("Market is CLOSED. Using AMO (After Market Order) variety.");
            }

            checkRateLimit();
            Order order = kiteConnect.placeOrder(orderParams, variety);

            if (order != null && order.orderId != null && !order.orderId.isEmpty()) {
                System.out.println("Order Placed Successfully. Order ID: " + order.orderId);
                return order.orderId;
            }

        } catch (KiteException e) {
            System.err.println(
                    "KiteException while placing limit order (" + symbol + "): " + e.message + " | Code: " + e.code);
            if (e.message != null && e.message.contains("Variety")) {
                System.err.println("Note: Zerodha variety error. Using 'regular' by default.");
            }
        } catch (Exception e) {
            System.err.println("Exception while placing limit order for " + symbol + ": " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    public String modifyLimitOrder(String orderId, String symbol, String instrumentToken, String action, double price,
            int quantity) {
        try {
            OrderParams orderParams = new OrderParams();
            orderParams.quantity = quantity;
            orderParams.orderType = Constants.ORDER_TYPE_LIMIT;
            orderParams.price = price;

            System.out.println("Modifying " + action + " LIMIT order " + orderId + " for " + symbol + " to " + price
                    + " qty " + quantity);

            // Automatically switch to AMO if market is closed
            String variety = Constants.VARIETY_REGULAR;
            if (com.algo.core.MarketUtils.isPostMarket() || com.algo.core.MarketUtils.isPreMarket()) {
                variety = Constants.VARIETY_AMO;
                System.out.println("Market is CLOSED. Modifying order using AMO variety.");
            }

            checkRateLimit();
            Order order = kiteConnect.modifyOrder(orderId, orderParams, variety);

            if (order != null && order.orderId != null && !order.orderId.isEmpty()) {
                System.out.println("Order Modified Successfully. Order ID: " + order.orderId);
                return order.orderId;
            }

        } catch (KiteException e) {
            System.err.println("KiteException while modifying order: " + e.message);
            e.printStackTrace();
            return null;
        } catch (Exception e) {
            System.err.println("Exception while modifying limit order: " + e.getMessage());
        }
        return null;
    }
}
