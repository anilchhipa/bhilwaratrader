package com.algo.core;

import java.time.LocalDateTime;

public class Candle {
    private double open;
    private double high;
    private double low;
    private double close;
    private long volume;
    private LocalDateTime timestamp;

    // Indicator values (nullable)
    private Double ema15;
    private Double ema15_5;
    private Double ema15_15;
    private Double ema15_15_15;
    private Double ema15_15_15_15;
    private Double ema15_15_15_15_30; // 5th cascaded EMA (cascade5 period)
    private Double stMa;
    private Double stUp;
    private Double stDn;
    private Integer stTrend;

    public Candle(double open, double high, double low, double close, long volume, LocalDateTime timestamp) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.timestamp = timestamp;
    }

    public Candle(double open, double high, double low, double close, LocalDateTime timestamp) {
        this(open, high, low, close, 0L, timestamp);
    }

    public double getOpen() {
        return open;
    }

    public double getHigh() {
        return high;
    }

    public double getLow() {
        return low;
    }

    public double getClose() {
        return close;
    }

    public long getVolume() {
        return volume;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setOpen(double open) {
        this.open = open;
    }

    public void setHigh(double high) {
        this.high = high;
    }

    public void setLow(double low) {
        this.low = low;
    }

    public void setClose(double close) {
        this.close = close;
    }

    public void setVolume(long volume) {
        this.volume = volume;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }

    // Indicator Getters and Setters (Precision preserved to avoid zig-zagging)
    public Double getEma15() {
        return ema15;
    }

    public void setEma15(Double ema15) {
        this.ema15 = ema15;
    }

    public Double getEma15_5() {
        return ema15_5;
    }

    public void setEma15_5(Double ema15_5) {
        this.ema15_5 = ema15_5;
    }

    public Double getEma15_15() {
        return ema15_15;
    }

    public void setEma15_15(Double ema15_15) {
        this.ema15_15 = ema15_15;
    }

    public Double getEma15_15_15() {
        return ema15_15_15;
    }

    public void setEma15_15_15(Double ema15_15_15) {
        this.ema15_15_15 = ema15_15_15;
    }

    public Double getEma15_15_15_15() {
        return ema15_15_15_15;
    }

    public void setEma15_15_15_15(Double ema15_15_15_15) {
        this.ema15_15_15_15 = ema15_15_15_15;
    }

    public Double getEma15_15_15_15_30() {
        return ema15_15_15_15_30;
    }

    public void setEma15_15_15_15_30(Double ema15_15_15_15_30) {
        this.ema15_15_15_15_30 = ema15_15_15_15_30;
    }

    public Double getStMa() {
        return stMa;
    }

    public void setStMa(Double stMa) {
        this.stMa = stMa;
    }

    public Double getStUp() {
        return stUp;
    }

    public void setStUp(Double stUp) {
        this.stUp = stUp;
    }

    public Double getStDn() {
        return stDn;
    }

    public void setStDn(Double stDn) {
        this.stDn = stDn;
    }

    public Integer getStTrend() {
        return stTrend;
    }

    public void setStTrend(Integer stTrend) {
        this.stTrend = stTrend;
    }

}
