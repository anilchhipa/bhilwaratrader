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

    // --- SuperTrend ---
    @Value("${indicator.supertrend.maLength:42}")
    private int stMaLength;

    @Value("${indicator.supertrend.atrPeriod:10}")
    private int stAtrPeriod;

    @Value("${indicator.supertrend.multiplier:0.5}")
    private double stMultiplier;

    // --- SuperTrend 2 ---
    @Value("${indicator.supertrend2.maLength:100}")
    private int stMaLength2;

    @Value("${indicator.supertrend2.atrPeriod:10}")
    private int stAtrPeriod2;

    @Value("${indicator.supertrend2.multiplier:0.5}")
    private double stMultiplier2;

    @Value("${trading.paper-mode:false}")
    private boolean paperMode;

    @Value("${indicator.atr.threshold:0.15}")
    private double atrThreshold;

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

    public int getStMaLength() {
        return stMaLength;
    }

    public int getStAtrPeriod() {
        return stAtrPeriod;
    }

    public double getStMultiplier() {
        return stMultiplier;
    }

    public int getStMaLength2() {
        return stMaLength2;
    }

    public int getStAtrPeriod2() {
        return stAtrPeriod2;
    }

    public double getStMultiplier2() {
        return stMultiplier2;
    }

    public boolean isPaperMode() {
        return paperMode;
    }

    public double getAtrThreshold() {
        return atrThreshold;
    }

    @Override
    public String toString() {
        return String.format(
                "IndicatorConfig{ema=%d, cascades=[%d,%d,%d,%d,%d], " +
                        "st1=[ma=%d, atr=%d, mult=%.2f], st2=[ma=%d, atr=%d, mult=%.2f]}",
                emaPeriod1, emaCascade1, emaCascade2, emaCascade3, emaCascade4, emaCascade5,
                stMaLength, stAtrPeriod, stMultiplier,
                stMaLength2, stAtrPeriod2, stMultiplier2);
    }
}
