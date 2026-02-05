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
    private Double ema15_30;
    private Double stMa;
    private Double stUp;
    private Double stDn;
    private Integer stTrend;

    // MACD and additional EMA values
    private Double ema50;
    private Double ema100;
    private Double macdLine;
    private Double signalLine;
    private Double macd5;
    private Double macd55;

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

    // Rounding utility
    private Double round(Double value) {
        if (value == null)
            return null;
        return Math.round(value * 100.0) / 100.0;
    }

    // Indicator Getters and Setters with Rounding
    public Double getEma15() {
        return ema15;
    }

    public void setEma15(Double ema15) {
        this.ema15 = round(ema15);
    }

    public Double getEma15_5() {
        return ema15_5;
    }

    public void setEma15_5(Double ema15_5) {
        this.ema15_5 = round(ema15_5);
    }

    public Double getEma15_15() {
        return ema15_15;
    }

    public void setEma15_15(Double ema15_15) {
        this.ema15_15 = round(ema15_15);
    }

    public Double getEma15_15_15() {
        return ema15_15_15;
    }

    public void setEma15_15_15(Double ema15_15_15) {
        this.ema15_15_15 = round(ema15_15_15);
    }

    public Double getEma15_30() {
        return ema15_30;
    }

    public void setEma15_30(Double ema15_30) {
        this.ema15_30 = round(ema15_30);
    }

    public Double getStMa() {
        return stMa;
    }

    public void setStMa(Double stMa) {
        this.stMa = round(stMa);
    }

    public Double getStUp() {
        return stUp;
    }

    public void setStUp(Double stUp) {
        this.stUp = round(stUp);
    }

    public Double getStDn() {
        return stDn;
    }

    public void setStDn(Double stDn) {
        this.stDn = round(stDn);
    }

    public Integer getStTrend() {
        return stTrend;
    }

    public void setStTrend(Integer stTrend) {
        this.stTrend = stTrend;
    }

    public Double getEma50() {
        return ema50;
    }

    public void setEma50(Double ema50) {
        this.ema50 = round(ema50);
    }

    public Double getEma100() {
        return ema100;
    }

    public void setEma100(Double ema100) {
        this.ema100 = round(ema100);
    }

    public Double getMacdLine() {
        return macdLine;
    }

    public void setMacdLine(Double macdLine) {
        this.macdLine = round(macdLine);
    }

    public Double getSignalLine() {
        return signalLine;
    }

    public void setSignalLine(Double signalLine) {
        this.signalLine = round(signalLine);
    }

    public Double getMacd5() {
        return macd5;
    }

    public void setMacd5(Double macd5) {
        this.macd5 = round(macd5);
    }

    public Double getMacd55() {
        return macd55;
    }

    public void setMacd55(Double macd55) {
        this.macd55 = round(macd55);
    }
}
