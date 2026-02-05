package com.algo.core;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Utility for NSE market hours and timing logic
 */
public class MarketUtils {
    public static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    public static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    /**
     * Returns the last expected candle time based on current time.
     * If market is open, returns current time.
     * If market is closed, returns the last 15:30.
     */
    public static LocalDateTime getTargetSyncTime() {
        LocalDateTime now = LocalDateTime.now();

        // Handle Weekends
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY) {
            return now.minusDays(1).with(MARKET_CLOSE).withSecond(0).withNano(0);
        }
        if (now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return now.minusDays(2).with(MARKET_CLOSE).withSecond(0).withNano(0);
        }

        LocalTime time = now.toLocalTime();
        if (time.isBefore(MARKET_OPEN)) {
            // Before market open, target is yesterday's close
            LocalDateTime lastClose = now.minusDays(1).with(MARKET_CLOSE).withSecond(0).withNano(0);
            // If yesterday was Sunday/Saturday, go back to Friday
            if (lastClose.getDayOfWeek() == DayOfWeek.SUNDAY)
                lastClose = lastClose.minusDays(2);
            if (lastClose.getDayOfWeek() == DayOfWeek.SATURDAY)
                lastClose = lastClose.minusDays(1);
            return lastClose;
        } else if (time.isAfter(MARKET_CLOSE)) {
            // After market close, target is today's close
            return now.with(MARKET_CLOSE).withSecond(0).withNano(0);
        } else {
            // During market hours
            return now;
        }
    }

    /**
     * Checks if current time is within NSE market hours (Mon-Fri, 9:15-15:30)
     */
    public static boolean isMarketOpen() {
        LocalDateTime now = LocalDateTime.now();
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = now.toLocalTime();
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    /**
     * Checks if it's currently pre-market (before 9:15 AM on a weekday)
     */
    public static boolean isPreMarket() {
        LocalDateTime now = LocalDateTime.now();
        if (now.getDayOfWeek() == DayOfWeek.SATURDAY || now.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        return now.toLocalTime().isBefore(MARKET_OPEN);
    }

    /**
     * Checks if it's currently time to flush data (e.g., 3:16 PM)
     */
    public static boolean isFlushTime() {
        LocalTime time = LocalTime.now();
        return time.getHour() == 15 && time.getMinute() >= 16 && time.getMinute() < 20;
    }

    /**
     * Checks if the market session is over (after 3:30 PM)
     */
    public static boolean isPostMarket() {
        return LocalTime.now().isAfter(MARKET_CLOSE);
    }
}
