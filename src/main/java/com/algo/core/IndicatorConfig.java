package com.algo.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds all configurable indicator parameters, read from
 * application.properties.
 * Inject this wherever Strategy or SuperTrendedMA is instantiated.
 */
@Component
public class IndicatorConfig {

    // --- Cascaded EMA ---
    @Value("${indicator.ema.period1:15}")
    private int emaPeriod1;

    @Value("${indicator.ema.cascade1:5}")
    private int emaCascade1;

    @Value("${indicator.ema.cascade2:15}")
    private int emaCascade2;

    @Value("${indicator.ema.cascade3:15}")
    private int emaCascade3;

    @Value("${indicator.ema.cascade4:15}")
    private int emaCascade4;

    @Value("${indicator.ema.cascade5:30}")
    private int emaCascade5;

    // --- Standalone EMAs (used for MACD) ---
    @Value("${indicator.ema.fast:50}")
    private int emaFast;

    @Value("${indicator.ema.slow:100}")
    private int emaSlow;

    // --- MACD ---
    @Value("${indicator.macd.signal:5}")
    private int macdSignal;

    @Value("${indicator.macd.signal2:5}")
    private int macdSignal2;

    // --- SuperTrend ---
    @Value("${indicator.supertrend.maLength:42}")
    private int stMaLength;

    @Value("${indicator.supertrend.atrPeriod:10}")
    private int stAtrPeriod;

    @Value("${indicator.supertrend.multiplier:0.5}")
    private double stMultiplier;

    // --- Visibility ---
    @Value("${indicator.macd.show:true}")
    private boolean macdShow;

    @Value("${indicator.macd.showEMAs:true}")
    private boolean macdShowEMAs;

    // --- Getters ---

    public int getEmaPeriod1() {
        return emaPeriod1;
    }

    public int getEmaCascade1() {
        return emaCascade1;
    }

    public int getEmaCascade2() {
        return emaCascade2;
    }

    public int getEmaCascade3() {
        return emaCascade3;
    }

    public int getEmaCascade4() {
        return emaCascade4;
    }

    public int getEmaCascade5() {
        return emaCascade5;
    }

    public int getEmaFast() {
        return emaFast;
    }

    public int getEmaSlow() {
        return emaSlow;
    }

    public int getMacdSignal() {
        return macdSignal;
    }

    public int getMacdSignal2() {
        return macdSignal2;
    }

    public int getStMaLength() {
        return stMaLength;
    }

    public int getStAtrPeriod() {
        return stAtrPeriod;
    }

    public double getStMultiplier() {
        return stMultiplier;
    }

    public boolean isMacdShow() {
        return macdShow;
    }

    public boolean isMacdShowEMAs() {
        return macdShowEMAs;
    }

    @Override
    public String toString() {
        return String.format(
                "IndicatorConfig{ema=%d, cascades=[%d,%d,%d,%d,%d], fast=%d, slow=%d, " +
                        "macdSignal=%d, macdSignal2=%d, st=[ma=%d, atr=%d, mult=%.2f], " +
                        "showMacd=%b, showMacdEMAs=%b}",
                emaPeriod1, emaCascade1, emaCascade2, emaCascade3, emaCascade4, emaCascade5,
                emaFast, emaSlow, macdSignal, macdSignal2,
                stMaLength, stAtrPeriod, stMultiplier,
                macdShow, macdShowEMAs);
    }
}
