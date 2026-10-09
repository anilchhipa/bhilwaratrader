package com.algo.web;

import com.algo.data.ZerodhaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for Account, Funds, Intraday Limits, and Trade Charges calculation.
 */
@RestController
@RequestMapping("/api/account")
@CrossOrigin(origins = "*")
public class AccountController {
    private static final Logger logger = LoggerFactory.getLogger(AccountController.class);

    private final ZerodhaClient zerodhaClient;

    public AccountController(ZerodhaClient zerodhaClient) {
        this.zerodhaClient = zerodhaClient;
    }

    /**
     * GET /api/account/funds
     * Returns available cash, used debits, and used span for equity segment.
     */
    @GetMapping("/funds")
    public ResponseEntity<?> getAccountFunds() {
        try {
            java.util.Map<String, Double> funds = zerodhaClient.getAvailableFunds();
            return ResponseEntity.ok(funds);
        } catch (Exception e) {
            logger.error("Error fetching funds: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to fetch funds: " + e.getMessage()));
        }
    }

    /**
     * GET /api/account/max-qty?symbol=SENSEX...&price=1300&leverage=5
     * Returns maximum intraday quantity you can buy at the given price.
     */
    @GetMapping("/max-qty")
    public ResponseEntity<?> getMaxIntradayQty(
            @RequestParam String symbol,
            @RequestParam double price,
            @RequestParam(defaultValue = "5") double leverage) {
        try {
            int maxQty = zerodhaClient.getMaxIntradayQty(price, leverage);
            int lotSize = zerodhaClient.getLotSize(symbol);
            if (lotSize > 1) {
                maxQty = (maxQty / lotSize) * lotSize;
            }

            java.util.Map<String, Double> funds = zerodhaClient.getAvailableFunds();
            double available = funds.getOrDefault("net", 0.0);
            return ResponseEntity.ok(Map.of(
                    "maxQty", maxQty,
                    "available", available,
                    "lotSize", lotSize,
                    "leverage", leverage,
                    "price", price,
                    "totalNeeded", price * maxQty / leverage));
        } catch (Exception e) {
            logger.error("Error calculating max qty: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST /api/account/charges
     * Returns itemised charge breakdown for an intraday round-trip.
     */
    @PostMapping("/charges")
    public ResponseEntity<?> calculateCharges(@RequestBody Map<String, Object> req) {
        try {
            double buyPrice = ((Number) req.get("buyPrice")).doubleValue();
            double sellPrice = ((Number) req.get("sellPrice")).doubleValue();
            int qty = ((Number) req.get("qty")).intValue();
            String exchange = (String) req.getOrDefault("exchange", "NSE");

            java.util.Map<String, Double> charges = zerodhaClient.calculateIntradayCharges(buyPrice, sellPrice, qty, exchange);

            double grossProfit = (sellPrice - buyPrice) * qty;
            double netProfit = grossProfit - charges.get("total");

            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>(charges);
            result.put("gross_profit", Math.round(grossProfit * 100.0) / 100.0);
            result.put("net_profit", Math.round(netProfit * 100.0) / 100.0);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error calculating charges: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", "Bad request: " + e.getMessage()));
        }
    }
}
