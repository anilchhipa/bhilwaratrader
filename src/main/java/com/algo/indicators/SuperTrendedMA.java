package com.algo.indicators;

import com.algo.core.Candle;
import java.util.ArrayList;
import java.util.List;

public class SuperTrendedMA {
    private int maLength;
    private int atrPeriod;
    private double multiplier;
    private boolean changeATR;

    private List<Double> srcHistory = new ArrayList<>();
    private List<Double> trHistory = new ArrayList<>();
    private List<Double> maHistory = new ArrayList<>();
    private List<Double> upHistory = new ArrayList<>();
    private List<Double> dnHistory = new ArrayList<>();
    private List<Integer> trendHistory = new ArrayList<>();

    // State
    private double lastEma = 0;
    private double lastUp = 0;
    private double lastDn = 0;
    private int trend = 1;
    private int prevTrend = 1;
    private double lastAtr = 0;
    private double lastClose = 0;

    public SuperTrendedMA(int maLength, int atrPeriod, double multiplier, boolean changeATR) {
        this.maLength = maLength;
        this.atrPeriod = atrPeriod;
        this.multiplier = multiplier;
        this.changeATR = changeATR;
    }

    public void update(Candle candle) {
        double close = candle.getClose();
        double high = candle.getHigh();
        double low = candle.getLow();

        srcHistory.add(close);

        // Calculate True Range
        double tr = 0;
        if (srcHistory.size() > 1) {
            tr = Math.max(high - low, Math.max(Math.abs(high - lastClose), Math.abs(low - lastClose)));
        } else {
            tr = high - low;
        }
        trHistory.add(tr);

        // Calculate MA (EMA only)
        lastEma = MovingAverages.ema(srcHistory, lastEma, maLength);
        maHistory.add(lastEma);

        // Calculate ATR
        double atr = calculateATR();

        // SuperTrend Logic
        prevTrend = trend;
        if (lastEma != 0 && atr != 0) {
            double up = lastEma - multiplier * atr;
            double dn = lastEma + multiplier * atr;

            if (srcHistory.size() > 1) {
                up = (lastClose > lastUp) ? Math.max(up, lastUp) : up;
                dn = (lastClose < lastDn) ? Math.min(dn, lastDn) : dn;

                if (trend == -1 && close > lastDn) {
                    trend = 1;
                } else if (trend == 1 && close < lastUp) {
                    trend = -1;
                }
            } else {
                lastUp = up;
                lastDn = dn;
            }

            lastUp = up;
            lastDn = dn;
        }

        lastClose = close;
        lastAtr = atr;
        upHistory.add(lastUp);
        dnHistory.add(lastDn);
        trendHistory.add(trend);

        limitHistory();
    }

    private void limitHistory() {
        if (srcHistory.size() > 1000) {
            srcHistory.remove(0);
            trHistory.remove(0);
            maHistory.remove(0);
            upHistory.remove(0);
            dnHistory.remove(0);
            trendHistory.remove(0);
        }
    }

    private double calculateATR() {
        if (trHistory.size() < atrPeriod)
            return 0;
        if (changeATR) {
            // Wilder's ATR
            if (lastAtr == 0) {
                lastAtr = MovingAverages.sma(trHistory, atrPeriod);
            } else {
                lastAtr = (lastAtr * (atrPeriod - 1) + trHistory.get(trHistory.size() - 1)) / atrPeriod;
            }
            return lastAtr;
        } else {
            // SMA of TR
            return MovingAverages.sma(trHistory, atrPeriod);
        }
    }

    public double getMA() {
        return maHistory.isEmpty() ? 0 : maHistory.get(maHistory.size() - 1);
    }

    public double getUp() {
        return lastUp;
    }

    public double getDn() {
        return lastDn;
    }

    public int getTrend() {
        return trend;
    }

    public boolean isBuySignal() {
        return trend == 1 && prevTrend == -1;
    }

    public boolean isSellSignal() {
        return trend == -1 && prevTrend == 1;
    }

    public List<Double> getMaHistory() {
        return maHistory;
    }

    public List<Double> getUpHistory() {
        return upHistory;
    }

    public List<Double> getDnHistory() {
        return dnHistory;
    }

    public List<Integer> getTrendHistory() {
        return trendHistory;
    }
}
