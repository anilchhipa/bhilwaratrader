package com.algo.core;

import java.time.LocalDateTime;

public class Candle {
    private double open;
    private double high;
    private double low;
    private double close;
    private LocalDateTime timestamp;
    private long cachedEpochMilli;

    // Indicator values (nullable)
    private Double ema15;
    private Double ema15_5;
    private Double ema15_15;
    private Double ema15_15_15;
    private Double stMa;
    private Double stUp;
    private Double stDn;
    private Integer stTrend;

    // Second SuperTrend (e.g. maLength=100)
    private Double stMa2;
    private Double stUp2;
    private Double stDn2;
    private Integer stTrend2;
    private Double atr;

    // SuperTrend MTF Heikin Ashi
    private Double haTrailingSl;
    private Integer haTrend;
    private Double htfTrailingSl;
    private Integer htfTrend;


    private Double volume;

    public Candle(double open, double high, double low, double close, double volume, LocalDateTime timestamp) {
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.timestamp = timestamp;
        if (timestamp != null) {
            this.cachedEpochMilli = timestamp.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
    }

    /** Fast copy constructor */
    public Candle(Candle other) {
        if (other == null) return;
        this.open = other.open;
        this.high = other.high;
        this.low = other.low;
        this.close = other.close;
        this.volume = other.volume;
        this.timestamp = other.timestamp;
        this.cachedEpochMilli = other.cachedEpochMilli;
        this.ema15 = other.ema15;
        this.ema15_5 = other.ema15_5;
        this.ema15_15 = other.ema15_15;
        this.ema15_15_15 = other.ema15_15_15;
        this.stMa = other.stMa;
        this.stUp = other.stUp;
        this.stDn = other.stDn;
        this.stTrend = other.stTrend;
        this.stMa2 = other.stMa2;
        this.stUp2 = other.stUp2;
        this.stDn2 = other.stDn2;
        this.stTrend2 = other.stTrend2;
        this.atr = other.atr;
        this.haTrailingSl = other.haTrailingSl;
        this.haTrend = other.haTrend;
        this.htfTrailingSl = other.htfTrailingSl;
        this.htfTrend = other.htfTrend;
    }

    public Double getVolume() {
        return volume != null ? volume : 0.0;
    }

    public void setVolume(Double volume) {
        this.volume = volume;
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

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
        if (timestamp != null) {
            this.cachedEpochMilli = timestamp.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
    }

    public long getEpochMilli() {
        return cachedEpochMilli;
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

    public Double getStMa2() {
        return stMa2;
    }

    public void setStMa2(Double stMa2) {
        this.stMa2 = stMa2;
    }

    public Double getStUp2() {
        return stUp2;
    }

    public void setStUp2(Double stUp2) {
        this.stUp2 = stUp2;
    }

    public Double getStDn2() {
        return stDn2;
    }

    public void setStDn2(Double stDn2) {
        this.stDn2 = stDn2;
    }

    public Integer getStTrend2() {
        return stTrend2;
    }

    public void setStTrend2(Integer stTrend2) {
        this.stTrend2 = stTrend2;
    }

    public Double getAtr() {
        return atr != null ? atr : 0.0;
    }

    public void setAtr(Double atr) {
        this.atr = atr;
    }

    public Double getHaTrailingSl() {
        return haTrailingSl;
    }

    public void setHaTrailingSl(Double haTrailingSl) {
        this.haTrailingSl = haTrailingSl;
    }

    public Integer getHaTrend() {
        return haTrend;
    }

    public void setHaTrend(Integer haTrend) {
        this.haTrend = haTrend;
    }

    public Double getHtfTrailingSl() {
        return htfTrailingSl;
    }

    public void setHtfTrailingSl(Double htfTrailingSl) {
        this.htfTrailingSl = htfTrailingSl;
    }

    public Integer getHtfTrend() {
        return htfTrend;
    }

    public void setHtfTrend(Integer htfTrend) {
        this.htfTrend = htfTrend;
    }
}
