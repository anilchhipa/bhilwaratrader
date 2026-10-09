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

    // --- SuperTrend ---
    @Value("${indicator.supertrend.maLength:42}")
    private int stMaLength;

    @Value("${indicator.supertrend.atrPeriod:10}")
    private int stAtrPeriod;

    @Value("${indicator.supertrend.multiplier:0.5}")
    private double stMultiplier;

    // --- SuperTrend 2 ---
    @Value("${indicator.supertrend2.maLength:63}")
    private int stMaLength2;

    @Value("${indicator.supertrend2.atrPeriod:10}")
    private int stAtrPeriod2;

    @Value("${indicator.supertrend2.multiplier:0.5}")
    private double stMultiplier2;

    @Value("${trading.paper-mode:false}")
    private boolean paperMode;

    @Value("${indicator.atr.threshold:0.15}")
    private double atrThreshold;


    // --- UniRenko ---
    /** Initial trend brick size in index points (used before ATR warms up). */
    @Value("${indicator.renko.initialT:100.0}")
    private double renkoInitialT;

    /** R = reversalFactor × T  (default 2.0 → reversal costs twice a normal brick). */
    @Value("${indicator.renko.reversalFactor:2.0}")
    private double renkoReversalFactor;

    /** T = ATR(3min) × atrMultiplier  (default 0.3 → moderately tight bricks). */
    @Value("${indicator.renko.atrMultiplier:0.3}")
    private double renkoAtrMultiplier;



    /** Renko mode: UniRenko or Traditional. */
    @Value("${indicator.renko.type:ATR}")
    private String renkoType;

    /** Block size for Renko 1 close line (Pine Script default: 15). */
    @Value("${indicator.renko1.blockSize:15.0}")
    private double renko1BlockSize;

    /** Block size for Renko 2 close line (Pine Script default: 50). */
    @Value("${indicator.renko2.blockSize:50.0}")
    private double renko2BlockSize;

    /** Block size for Renko 3 close line (Pine Script default: 100). */
    @Value("${indicator.renko3.blockSize:100.0}")
    private double renko3BlockSize;

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


    public double getRenkoInitialT() {
        return renkoInitialT;
    }

    public double getRenkoReversalFactor() {
        return renkoReversalFactor;
    }

    public double getRenkoAtrMultiplier() {
        return renkoAtrMultiplier;
    }



    public String getRenkoType() {
        return renkoType;
    }

    public double getRenko1BlockSize() {
        return renko1BlockSize;
    }

    public double getRenko2BlockSize() {
        return renko2BlockSize;
    }

    public double getRenko3BlockSize() {
        return renko3BlockSize;
    }

    @Override
    public String toString() {
        return String.format(
                "IndicatorConfig{ema=%d, cascades=[%d,%d,%d], " +
                        "st1=[ma=%d, atr=%d, mult=%.2f], st2=[ma=%d, atr=%d, mult=%.2f]}",
                emaPeriod1, emaCascade1, emaCascade2, emaCascade3,
                stMaLength, stAtrPeriod, stMultiplier,
                stMaLength2, stAtrPeriod2, stMultiplier2);
    }
}
