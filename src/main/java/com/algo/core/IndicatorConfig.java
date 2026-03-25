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

    @Value("${trading.paper-mode:false}")
    private boolean paperMode;

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

    public boolean isPaperMode() {
        return paperMode;
    }

    @Override
    public String toString() {
        return String.format(
                "IndicatorConfig{ema=%d, cascades=[%d,%d,%d,%d,%d], " +
                        "st=[ma=%d, atr=%d, mult=%.2f]}",
                emaPeriod1, emaCascade1, emaCascade2, emaCascade3, emaCascade4, emaCascade5,
                stMaLength, stAtrPeriod, stMultiplier);
    }
}
