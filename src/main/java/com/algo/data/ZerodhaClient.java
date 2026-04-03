package com.algo.data;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitUntilState;
import com.zerodhatech.kiteconnect.KiteConnect;
import com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException;
import com.zerodhatech.models.User;
import com.zerodhatech.models.HistoricalData;
import com.zerodhatech.models.OrderParams;
import com.zerodhatech.models.Order;
import com.zerodhatech.models.Position;
import com.zerodhatech.kiteconnect.utils.Constants;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.io.IOException;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZerodhaClient {
    private static final Logger logger = LoggerFactory.getLogger(ZerodhaClient.class);
    private KiteConnect kiteConnect;
    private String userId;
    private String password;
    private String apiKey;
    private String apiSecret;
    private String totpSecret;
    private String accessToken;
    private com.algo.core.IndicatorConfig indicatorConfig;

    public ZerodhaClient(String userId, String password, String apiKey, String apiSecret, String totpSecret,
            com.algo.core.IndicatorConfig indicatorConfig) {
        this.userId = userId;
        this.password = password;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.totpSecret = totpSecret;
        this.indicatorConfig = indicatorConfig;

        kiteConnect = new KiteConnect(apiKey);
        kiteConnect.setUserId(userId);
    }

    private final java.util.Map<String, Integer> lotSizeCache = new java.util.concurrent.ConcurrentHashMap<>();

    public int getLotSize(String symbol) {
        if (lotSizeCache.containsKey(symbol))
            return lotSizeCache.get(symbol);

        java.util.List<com.zerodhatech.models.Instrument> all = getAllInstruments();
        if (all != null) {
            for (com.zerodhatech.models.Instrument inst : all) {
                if (symbol.equals(inst.tradingsymbol)) {
                    lotSizeCache.put(symbol, inst.lot_size);
                    return inst.lot_size;
                }
            }
        }
        return 1; // Default
    }

    public boolean login() {
        logger.info("ZerodhaClient: Starting Playwright headless browser login for {}", userId);

        // Generate TOTP first
        String totpCode;
        try {
            CodeGenerator codeGenerator = new DefaultCodeGenerator();
            TimeProvider timeProvider = new SystemTimeProvider();
            long counter = Math.floorDiv(timeProvider.getTime(), 30);
            totpCode = codeGenerator.generate(totpSecret.trim().replaceAll("\\s", ""), counter);
        } catch (Exception e) {
            logger.error("ZerodhaClient: TOTP Generation Failed: {}", e.getMessage());
            return false;
        }

        java.util.Map<String, String> env = new java.util.HashMap<>(System.getenv());

        try (Playwright playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env))) {
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setHeadless(true).setArgs(
                    java.util.Arrays.asList("--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage"));

            try (Browser browser = playwright.chromium().launch(launchOptions)) {
                BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 800));
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
                        logger.info("ZerodhaClient: Captured request_token from redirect!");
                    }
                });

                // Step 1: Navigate to Kite Connect login page
                String loginUrl = "https://kite.trade/connect/login?api_key=" + apiKey + "&v=3";
                logger.info("ZerodhaClient: Navigating to {}", loginUrl);
                page.navigate(loginUrl, new Page.NavigateOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
                logger.debug("ZerodhaClient: Current URL: {}", page.url());

                // Take screenshot to diagnose if needed
                page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/kite_step1.png")));

                // Step 2: Fill in User ID and Password
                page.locator("input#userid").waitFor(new Locator.WaitForOptions().setTimeout(10000));
                page.locator("input#userid").fill(userId);
                page.locator("input#password").fill(password);
                page.locator("button[type='submit']").click();

                logger.info("ZerodhaClient: Submitted credentials, waiting for TOTP/2FA page...");
                // Wait for any password-like field that is NOT the password field (TOTP pin)
                // Zerodha uses type="number" for TOTP with placeholder '6-digit code'
                // Try multiple possible selectors
                String totpSelector = null;
                String[] possibleTotpSelectors = { "input#pin", "input[type='number']", "input[placeholder*='PIN']",
                        "input[placeholder*='pin']", "input[placeholder*='code']", "input[placeholder*='totp']",
                        "input[autocomplete='one-time-code']" };
                for (String sel : possibleTotpSelectors) {
                    try {
                        page.waitForSelector(sel, new Page.WaitForSelectorOptions().setTimeout(5000));
                        totpSelector = sel;
                        logger.debug("ZerodhaClient: Found TOTP input with selector: {}", sel);
                        break;
                    } catch (Exception ex) {
                        // try next selector
                    }
                }

                // Take screenshot of current state after credential submit
                page.screenshot(new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/kite_step2.png")));
                logger.debug("ZerodhaClient: Screenshot saved to /tmp/kite_step2.png, current URL: {}", page.url());

                if (totpSelector == null) {
                    // Dump page content for debugging
                    logger.error("ZerodhaClient: TOTP selector not found. Page title: {}", page.title());
                    logger.debug("ZerodhaClient: All inputs on page: {}", page.evaluate(
                            "Array.from(document.querySelectorAll('input')).map(i => i.id + '|' + i.type + '|' + i.placeholder + '|' + i.name).join(', ')"));
                    return false;
                }

                // Step 3: Fill in TOTP
                logger.info("ZerodhaClient: Entering TOTP: {}", totpCode);
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

                logger.debug("ZerodhaClient: TOTP submitted. Current URL: {}", page.url());

                // Wait a bit for the redirect to happen and token to be captured
                try {
                    Thread.sleep(2000);
                } catch (Exception ignored) {
                }

                // Check if we already have the token (captured from the redirect event)
                if (capturedToken[0] != null && !capturedToken[0].isEmpty()) {
                    logger.info("ZerodhaClient: Token already captured, skipping authorize step!");
                } else {
                    // Step 4: Look for the authorize button (for first-time or explicit
                    // authorization)
                    logger.info("ZerodhaClient: Looking for authorize button...");
                    String authorizeSelector = null;
                    String[] possibleAuthSelectors = { "button.button-blue", "button.button-orange",
                            "button[type='submit']", "a.button-blue" };
                    for (String sel : possibleAuthSelectors) {
                        try {
                            page.waitForSelector(sel, new Page.WaitForSelectorOptions().setTimeout(8000));
                            authorizeSelector = sel;
                            logger.debug("ZerodhaClient: Found authorize btn: {}", sel);
                            break;
                        } catch (Exception ex) {
                            // try next
                        }
                    }

                    page.screenshot(
                            new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/kite_step3.png")));
                    logger.debug("ZerodhaClient: step3 URL: {}", page.url());

                    if (page.url().contains("request_token=")) {
                        logger.info("ZerodhaClient: request_token in URL after TOTP (no auth step needed)!");
                    } else if (authorizeSelector != null) {
                        String btnText = (String) page
                                .evaluate("sel => document.querySelector(sel)?.textContent.trim()", authorizeSelector);
                        logger.info("ZerodhaClient: Authorize page detected. Clicking '{}' button (selector: {})...",
                                btnText, authorizeSelector);

                        // Force click and also try submitting the form directly as fallback
                        try {
                            page.locator(authorizeSelector).first()
                                    .click(new Locator.ClickOptions().setForce(true).setTimeout(5000));
                        } catch (Exception clickEx) {
                            logger.warn("ZerodhaClient: Primary click failed, trying JS click/submit: {}",
                                    clickEx.getMessage());
                        }
                        page.evaluate(
                                "sel => { const el = document.querySelector(sel); if(el) el.click(); else document.querySelector('form')?.submit(); }",
                                authorizeSelector);

                        logger.info("ZerodhaClient: Clicked authorize. Waiting for redirect...");
                        try {
                            Thread.sleep(8000);
                        } catch (Exception ignored) {
                        }
                        page.screenshot(
                                new Page.ScreenshotOptions().setPath(java.nio.file.Paths.get("/tmp/kite_step4.png")));
                        logger.debug("ZerodhaClient: Post-click URL: {}", page.url());
                    } else {
                        logger.warn("ZerodhaClient: No authorize button found. Page Title: {}", page.title());
                        logger.debug("ZerodhaClient: All buttons: {}", page.evaluate(
                                "Array.from(document.querySelectorAll('button, a.button')).map(b => b.textContent.trim() + '(' + b.className + ')').join(', ')"));
                    }

                }

                // Step 5: Wait for callback redirect (with request_token)
                try {
                    page.waitForURL(url -> url.contains("request_token="),
                            new Page.WaitForURLOptions().setTimeout(25000));
                } catch (Exception ex) {
                    logger.warn("ZerodhaClient: waitForURL timed out after 25s. Final URL: {}", page.url());
                    if (page.url().contains("kite.zerodha.com")) {
                        logger.error(
                                "ZerodhaClient: Still on Zerodha domain. Login likely failed or stuck at Authorize.");
                        logger.debug("ZerodhaClient: Page text sample: {}",
                                page.evaluate("document.body.innerText.substring(0, 500)"));
                    }
                }

                // Also check current URL
                String finalUrl = page.url();
                logger.info("ZerodhaClient: Final URL: {}", finalUrl);

                if (finalUrl.contains("request_token=")) {
                    int idx = finalUrl.indexOf("request_token=");
                    String token = finalUrl.substring(idx + 14);
                    int amp = token.indexOf("&");
                    if (amp != -1)
                        token = token.substring(0, amp);
                    capturedToken[0] = token;
                }

                if (capturedToken[0] == null || capturedToken[0].isEmpty()) {
                    logger.error("ZerodhaClient: No request_token captured. Final URL: {}", finalUrl);
                    return false;
                }

                // Step 6: Exchange request_token for access_token
                logger.info("ZerodhaClient: Exchanging request_token for access_token...");
                User user = kiteConnect.generateSession(capturedToken[0], apiSecret);
                this.accessToken = user.accessToken;
                kiteConnect.setAccessToken(accessToken);
                kiteConnect.setPublicToken(user.publicToken);

                logger.info("ZerodhaClient: Playwright Login Successful! Session created.");
                return true;
            }
        } catch (Throwable e) {
            logger.error("ZerodhaClient: Playwright login error: {}", e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private java.util.List<com.zerodhatech.models.Instrument> instrumentCache = null;

    public java.util.List<com.zerodhatech.models.Instrument> getAllInstruments() {
        if (instrumentCache == null && kiteConnect != null) {
            try {
                logger.info("ZerodhaClient: Initializing full instrument cache...");
                instrumentCache = kiteConnect.getInstruments();
                logger.info("ZerodhaClient: Cached {} instruments.", instrumentCache.size());
            } catch (KiteException | IOException e) {
                logger.error("ZerodhaClient: Failed to fetch all instruments: {}", e.getMessage());
            } catch (Exception e) {
                logger.error("ZerodhaClient: Unexpected error fetching instruments: {}", e.getMessage());
            }
        }
        return instrumentCache;
    }

    private static final java.util.Set<String> SENSEX_30_STOCKS = new java.util.HashSet<>(
            java.util.Arrays.asList("RELIANCE", "HDFCBANK", "ICICIBANK", "INFY", "ITC", "LT", "TCS",
                    "AXISBANK",
                    "KOTAKBANK", "SBIN", "BHARTIARTL", "HINDUNILVR", "TITAN", "BAJFINANCE", "MARUTI", "SUNPHARMA",
                    "ASIANPAINT", "HCLTECH", "ULTRACEMCO", "INDUSINDBK", "M&M", "NESTLEIND", "POWERGRID", "TATASTEEL",
                    "NTPC", "JSWSTEEL", "ADANIPORTS", "TECHM", "WIPRO", "BAJAJFINSRV"));

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
                // Strictly filter for the main SENSEX index only
                if ((inst.instrument_type.equals("CE") || inst.instrument_type.equals("PE"))) {
                    if (inst.exchange.equals("BFO") && symbol.startsWith("SENSEX") && !symbol.startsWith("SENSEX50")) {
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

        // Sort options by numeric strike; others (stocks/futures) by symbol
        filtered.sort((a, b) -> {
            if (type.equalsIgnoreCase("OPTIONS")) {
                try {
                    long strikeA = extractStrike(a.tradingsymbol);
                    long strikeB = extractStrike(b.tradingsymbol);
                    if (strikeA != strikeB) {
                        return Long.compare(strikeA, strikeB);
                    }
                } catch (Exception ignored) {
                }
            }
            return a.tradingsymbol.compareTo(b.tradingsymbol);
        });

        return filtered;
    }

    private long extractStrike(String symbol) {
        try {
            // First try matching standard structure: INDEX YY MONTH/MMM DD? STRIKE CE/PE
            java.util.regex.Pattern p = java.util.regex.Pattern
                    .compile("[A-Za-z]+\\d{2}(?:[A-Za-z]{3}|[1-9ONDond]\\d{2})(\\d+)[CPcp][Ee]$");
            java.util.regex.Matcher m = p.matcher(symbol);
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }

            // Fallback: match precisely 5 digits before CE/PE
            p = java.util.regex.Pattern.compile("(\\d{5})[CPcp][Ee]$");
            m = p.matcher(symbol);
            if (m.find()) {
                return Long.parseLong(m.group(1));
            }
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    public KiteConnect getKiteConnect() {
        return kiteConnect;
    }

    public String getAccessToken() {
        return accessToken;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACCOUNT METHODS
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns available funds and margin details for the equity segment.
     * Keys: net, available_cash, used_debits, used_span
     */
    public java.util.Map<String, Double> getAvailableFunds() {
        java.util.Map<String, Double> funds = new java.util.HashMap<>();

        // 1. Direct simulation check
        if (indicatorConfig != null && indicatorConfig.isPaperMode()) {
            funds.put("net", 1000000.0);
            funds.put("available_cash", 1000000.0);
            return funds;
        }

        // 2. Try fetching real margins
        try {
            if (kiteConnect != null) {
                com.zerodhatech.models.Margin margin = kiteConnect.getMargins("equity");
                if (margin != null) {
                    double net = parseDouble(margin.net);
                    if (net > 0) {
                        funds.put("net", net);
                        funds.put("available_cash",
                                margin.available != null ? parseDouble(margin.available.cash) : 0.0);
                        return funds;
                    }
                }
            }
        } catch (Exception | KiteException e) {
            logger.warn("getAvailableFunds: Skipping real margins due to error: {}", e.getMessage());
        }

        // 3. SECURE FALLBACK: If we're here, either Zerodha isn't logged in OR has 0
        // balance.
        // Return ₹10 Lakhs simulation funds anyway to keep the UI interactive and
        // testable.
        logger.info("getAvailableFunds: Using ₹1,000,000 global fallback for UI simulation.");
        funds.put("net", 1000000.0);
        funds.put("available_cash", 1000000.0);
        return funds;
    }

    /** Null-safe String to double parser. Returns 0.0 on null or parse error. */
    private double parseDouble(String s) {
        if (s == null || s.isBlank())
            return 0.0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    /**
     * Calculates the maximum intraday quantity for a stock at a given price.
     * Uses a default 5× MIS leverage; Zerodha's actual leverage varies by stock.
     *
     * @param price              Current LTP of the stock
     * @param leverageMultiplier Typically 5 for MIS (BSE/NSE equities)
     */
    public int getMaxIntradayQty(double price, double leverageMultiplier) {
        if (price <= 0) {
            logger.warn("getMaxIntradayQty: Price is 0 or negative. Returning 0 qty.");
            return 0;
        }
        java.util.Map<String, Double> funds = getAvailableFunds();
        double available = funds.getOrDefault("net", 0.0);
        int qty = (int) Math.floor((available * leverageMultiplier) / price);

        logger.info("Max Intraday Qty Check: price={}, leverage={}, available={}, → maxQty={}",
                price, leverageMultiplier, available, qty);
        return qty;
    }

    /**
     * Calculates itemised intraday equity charges for a round-trip trade.
     * All rates are static SEBI/exchange rates (as of FY 2025-26).
     *
     * @param buyPrice  Fill price on buy leg
     * @param sellPrice Fill price on sell leg
     * @param qty       Number of shares
     * @param exchange  "NSE" or "BSE"
     * @return Map with keys: brokerage, stt, exchange_charges, sebi, gst, stamp,
     *         total
     */
    public java.util.Map<String, Double> calculateIntradayCharges(double buyPrice, double sellPrice, int qty,
            String exchange) {

        double buyTurnover = buyPrice * qty;
        double sellTurnover = sellPrice * qty;
        double totalTurnover = buyTurnover + sellTurnover;

        // Brokerage: flat ₹20 or 0.03% whichever is lower, per order (2 orders)
        double brokerage = Math.min(20.0, 0.0003 * buyTurnover) + Math.min(20.0, 0.0003 * sellTurnover);

        // STT: 0.025% on sell-side turnover only (intraday equity)
        double stt = 0.00025 * sellTurnover;

        // Exchange transaction charges
        double exchRate = "BSE".equalsIgnoreCase(exchange) ? 0.0000375 : 0.0000297;
        double exchCharge = exchRate * totalTurnover;

        // SEBI charges: ₹10 per crore = 0.000001 of turnover
        double sebi = 0.000001 * totalTurnover;

        // GST: 18% on (brokerage + exchange charges)
        double gst = 0.18 * (brokerage + exchCharge);

        // Stamp duty: 0.003% on buy-side only, capped at ₹1500
        double stamp = Math.min(1500.0, 0.00003 * buyTurnover);

        double total = brokerage + stt + exchCharge + sebi + gst + stamp;

        java.util.Map<String, Double> result = new java.util.LinkedHashMap<>();
        result.put("brokerage", round2(brokerage));
        result.put("stt", round2(stt));
        result.put("exchange_charges", round2(exchCharge));
        result.put("sebi", round2(sebi));
        result.put("gst", round2(gst));
        result.put("stamp", round2(stamp));
        result.put("total", round2(total));
        result.put("gross_profit_needed", round2(total)); // alias
                                                          // —
                                                          // how
                                                          // much
                                                          // gross
                                                          // profit
                                                          // you
                                                          // need
                                                          // to
                                                          // break
                                                          // even
        return result;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ORDER METHODS (used by limit-order drag-drop feature)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Places a limit order via Zerodha Kite.
     *
     * @param symbol      Trading symbol e.g. "RELIANCE"
     * @param exchange    "NSE" or "BSE"
     * @param txnType     "BUY" or "SELL"
     * @param qty         Quantity
     * @param price       Limit price
     * @param productType "MIS" for intraday, "CNC" for delivery
     * @return Zerodha order ID string, or null on failure
     */
    public String placeLimitOrder(String symbol, String exchange, String txnType, int qty, double price,
            String productType, String variety) {
        try {
            OrderParams params = new OrderParams();
            params.tradingsymbol = symbol;
            params.exchange = exchange;
            params.transactionType = txnType.toUpperCase(); // "BUY" / "SELL"
            params.quantity = qty;
            params.price = price;
            params.product = productType.toUpperCase(); // "MIS" / "CNC"
            params.orderType = Constants.ORDER_TYPE_LIMIT;
            params.validity = Constants.VALIDITY_DAY;

            logger.info("Attempting real order: symbol={}, exchange={}, txn={}, qty={}, prc={}, prod={}",
                    symbol, exchange, txnType, qty, price, productType);

            if (indicatorConfig != null && indicatorConfig.isPaperMode()) {
                String paperId = "PAPER-" + System.currentTimeMillis();
                logger.info("[PAPER MODE] Mock order placed: {} {} {} x {} @ {} → orderId={}",
                        txnType, qty, symbol, price, productType, paperId);
                return paperId;
            }

            String finalVariety = variety != null ? variety : Constants.VARIETY_REGULAR;
            Order order = kiteConnect.placeOrder(params, finalVariety);
            logger.info("Order placed [{}]: {} {} {} x {} @ {} → orderId={}", finalVariety,
                    txnType, qty, symbol, price, productType, order.orderId);
            return order.orderId;
        } catch (KiteException e) {
            String msg = e.message != null ? e.message : e.toString();
            logger.error(
                    "placeLimitOrder KiteException: msg={} code={} | Params: [sym={}, exch={}, txn={}, qty={}, prc={}, prod={}]",
                    msg, e.code, symbol, exchange, txnType, qty, price, productType);
            throw new RuntimeException("Kite Error: " + msg);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            logger.error(
                    "placeLimitOrder Exception. Details: {} | Params check: [sym={}, exch={}, txn={}, qty={}, prc={}, prod={}]",
                    msg, symbol, exchange, txnType, qty, price, productType);
            throw new RuntimeException("Kite Error: " + msg);
        }
    }

    /**
     * Modifies an existing pending limit order's price.
     *
     * @param orderId  Zerodha order ID to modify
     * @param newPrice Updated limit price
     * @param qty      Quantity (must be passed again for Kite API)
     * @return Updated order ID, or null on failure
     */
    public String modifyLimitOrder(String orderId, double newPrice, int qty, String variety) {
        try {
            OrderParams params = new OrderParams();
            params.price = newPrice;
            params.quantity = qty;
            params.orderType = Constants.ORDER_TYPE_LIMIT;
            params.validity = Constants.VALIDITY_DAY;

            if (indicatorConfig != null && indicatorConfig.isPaperMode()) {
                logger.info("[PAPER MODE] Mock order modified: orderId={} → newPrice={}", orderId, newPrice);
                return orderId;
            }

            String finalVariety = variety != null ? variety : Constants.VARIETY_REGULAR;
            Order order = kiteConnect.modifyOrder(orderId, params, finalVariety);
            logger.info("Order modified [{}]: orderId={} → newPrice={}", finalVariety, orderId, newPrice);
            return order.orderId;
        } catch (KiteException e) {
            String msg = e.message != null ? e.message : e.toString();
            logger.error("modifyLimitOrder KiteException: orderId={} message={} code={}", orderId, msg, e.code);
            throw new RuntimeException("Kite Error: " + msg);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            logger.error("modifyLimitOrder Exception: orderId={} msg={}", orderId, msg);
            throw new RuntimeException("Kite Error: " + msg);
        }
    }

    /**
     * Cancels a pending limit order.
     *
     * @param orderId Zerodha order ID
     * @return true if cancellation succeeded
     */
    public boolean cancelOrder(String orderId) {
        try {
            if (indicatorConfig != null && indicatorConfig.isPaperMode()) {
                logger.info("[PAPER MODE] Mock order cancelled: orderId={}", orderId);
                return true;
            }
            if (orderId != null && orderId.startsWith("PAPER-")) {
                logger.info("[PAPER MODE] Mock order deleted: orderId={}", orderId);
                return true;
            }

            // Detect correct variety based on market hours (Regular vs AMO)
            String variety = com.algo.core.MarketUtils.isMarketOpen() ? Constants.VARIETY_REGULAR
                    : Constants.VARIETY_AMO;

            try {
                kiteConnect.cancelOrder(orderId, variety);
                logger.info("Order cancelled [{}]: orderId={}", variety, orderId);
            } catch (KiteException ke) {
                // If first attempt fails and it was off-market hours, try 'regular' just in
                // case
                if (variety.equals(Constants.VARIETY_AMO)) {
                    logger.warn("AMO cancel failed for {}, retrying with REGULAR...", orderId);
                    kiteConnect.cancelOrder(orderId, Constants.VARIETY_REGULAR);
                    logger.info("Order cancelled [REGULAR]: orderId={}", orderId);
                } else {
                    throw ke; // Re-throw if it wasn't a variety mismatch
                }
            }
            return true;
        } catch (Exception | KiteException e) {
            String msg = e instanceof KiteException ? ((KiteException) e).message : e.getMessage();
            logger.error("cancelOrder failed for orderId={} [Error: {}]", orderId, msg);
            return false;
        }
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
        logger.info("ZerodhaClient: Resolving instrument tokens for {} symbols...",
                tokenManager.getAllSymbols().size());

        // Group symbols by their exchange to minimize API calls (one call per exchange)
        java.util.Map<String, java.util.List<String>> byExchange = new java.util.HashMap<>();
        for (String symbol : tokenManager.getAllSymbols()) {
            String exchange = tokenManager.getExchangeType(symbol);
            // Dynamic override: indices like SENSEX must be resolved against BSE
            // regardless of what the database says (handles legacy BFO/NFO mapping bugs)
            if (symbol.equals("SENSEX") || symbol.equals("BANKEX")) {
                exchange = "BSE";
            }
            byExchange.computeIfAbsent(exchange, k -> new java.util.ArrayList<>()).add(symbol);
        }

        for (java.util.Map.Entry<String, java.util.List<String>> entry : byExchange.entrySet()) {
            String exchange = entry.getKey();
            java.util.List<String> symbols = entry.getValue();
            java.util.Set<String> symbolSet = new java.util.HashSet<>(symbols);

            try {
                logger.info("ZerodhaClient: Fetching instrument list for exchange: {}", exchange);
                java.util.List<com.zerodhatech.models.Instrument> instruments = kiteConnect.getInstruments(exchange);
                logger.info("ZerodhaClient: Got {} instruments from {}", instruments.size(), exchange);

                for (com.zerodhatech.models.Instrument inst : instruments) {
                    // Check direct trading symbol match (standard)
                    if (symbolSet.contains(inst.tradingsymbol)) {
                        String token = String.valueOf(inst.instrument_token);
                        tokenManager.updateToken(inst.tradingsymbol, token);
                        logger.info("ZerodhaClient: Resolved {} -> token={} (exchange={}, type={}, expiry={})",
                                inst.tradingsymbol, token, inst.exchange, inst.instrument_type, inst.expiry);
                        symbolSet.remove(inst.tradingsymbol);
                    }
                    // Fallback for indices (e.g. SENSEX might be listed as 'BSE SENSEX' name but we
                    // want 'SENSEX' symbol)
                    else {
                        for (String targetSym : new java.util.ArrayList<>(symbolSet)) {
                            // Case-insensitive name match (handles 'SENSEX' vs 'SENSEX' name discrepancies)
                            if (inst.name != null && inst.name.equalsIgnoreCase(targetSym)) {
                                String token = String.valueOf(inst.instrument_token);
                                tokenManager.updateToken(targetSym, token);
                                logger.info("ZerodhaClient: Resolved {} (via name '{}') -> token={} (exchange={})",
                                        targetSym, inst.name, token, inst.exchange);
                                symbolSet.remove(targetSym);
                            }
                        }
                    }
                }

                if (!symbolSet.isEmpty()) {
                    logger.warn(
                            "ZerodhaClient: WARNING - could not resolve tokens for: {}. Check if trading symbols are correct and contracts are active.",
                            symbolSet);
                }

            } catch (com.zerodhatech.kiteconnect.kitehttp.exceptions.KiteException | Exception e) {
                logger.error("ZerodhaClient: Failed to fetch instruments for {}: {}", exchange, e.getMessage());
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
        int days = 4; // Default
                      // for
                      // 1min,
                      // 3min
        if (interval.equals("5minute"))
            days = 8;
        else if (interval.equals("15minute"))
            days = 20;
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

                logger.info("Fetching History for {} ({})", instrumentToken, interval);

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
                            history.add(new com.algo.core.Candle(d.open, d.high, d.low, d.close, d.volume, time));
                        }
                    } else {
                        logger.warn("No data returned for {} in chunk.", instrumentToken);
                    }
                } catch (KiteException | org.json.JSONException e) {
                    logger.error("API call failed for {}: {}", instrumentToken, e.getMessage());
                }

                currentFrom = currentTo;
                if (currentTo.equals(endDate))
                    break;
            }

            history.sort((c1, c2) -> c1.getTimestamp().compareTo(c2.getTimestamp()));

        } catch (Exception e) {
            logger.error("Error fetching history for {}: {}", instrumentToken, e.getMessage());
        }

        return history;
    }

}
