package com.algo.core;

import java.time.LocalDateTime;

public class RenkoBrick {
    private double open;
    private double close;
    private double high;
    private double low;
    private boolean isGreen; // true for Green (Up), false for Red (Down)
    private LocalDateTime timestamp;

    public RenkoBrick(double open, double close, boolean isGreen, LocalDateTime timestamp) {
        this.open = open;
        this.close = close;
        this.isGreen = isGreen;
        this.timestamp = timestamp;
        this.high = Math.max(open, close);
        this.low = Math.min(open, close);
    }

    public double getOpen() { return open; }
    public double getClose() { return close; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public boolean isGreen() { return isGreen; }
    public LocalDateTime getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return String.format("Brick[%s | O:%.2f C:%.2f]", isGreen ? "GREEN" : "RED", open, close);
    }
}
