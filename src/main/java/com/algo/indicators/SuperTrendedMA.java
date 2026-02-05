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
    private int stableHistorySize = 0;

    public SuperTrendedMA(int maLength, int atrPeriod, double multiplier, boolean changeATR) {
        this.maLength = maLength;
        this.atrPeriod = atrPeriod;
        this.multiplier = multiplier;
        this.changeATR = changeATR;
    }

    public void update(Candle candle) {
        calculateInternal(candle, false);
        stableHistorySize = maHistory.size();
        limitHistory();
    }

    public void updateLive(Candle candle) {
        calculateInternal(candle, true);
    }

    private void calculateInternal(Candle candle, boolean isLive) {
        double close = candle.getClose();
        double high = candle.getHigh();
        double low = candle.getLow();

        // Calculate TR
        double tr = 0;
        if (srcHistory.size() > 0) {
            tr = Math.max(high - low, Math.max(Math.abs(high - lastClose), Math.abs(low - lastClose)));
        } else {
            tr = high - low;
        }

        // Calculate EMA
        double k = 2.0 / (maLength + 1);
        double currentEma = (lastEma == 0) ? close : close * k + lastEma * (1 - k);

        // Calculate ATR
        double currentAtr = 0;
        if (trHistory.size() + (isLive ? 0 : 1) >= atrPeriod) {
            if (changeATR) {
                if (lastAtr == 0) {
                    // This is slightly wrong for live because we don't have the full history
                    // properly masked
                    // but for now let's simplify
                    double sum = 0;
                    for (Double t : trHistory)
                        sum += t;
                    if (!isLive)
                        sum += tr;
                    currentAtr = sum / atrPeriod;
                } else {
                    currentAtr = (lastAtr * (atrPeriod - 1) + tr) / atrPeriod;
                }
            } else {
                double sum = 0;
                int start = Math.max(0, trHistory.size() - atrPeriod + 1);
                for (int i = start; i < trHistory.size(); i++)
                    sum += trHistory.get(i);
                sum += tr;
                currentAtr = sum / atrPeriod;
            }
        }

        // SuperTrend Logic
        int currentTrend = trend;
        double currentUp = lastUp;
        double currentDn = lastDn;

        if (currentEma != 0 && currentAtr != 0) {
            double up = currentEma - multiplier * currentAtr;
            double dn = currentEma + multiplier * currentAtr;

            if (srcHistory.size() > 0) {
                up = (lastClose > lastUp) ? Math.max(up, lastUp) : up;
                dn = (lastClose < lastDn) ? Math.min(dn, lastDn) : dn;

                if (currentTrend == -1 && close > dn) {
                    currentTrend = 1;
                } else if (currentTrend == 1 && close < up) {
                    currentTrend = -1;
                }
            } else {
                up = currentEma - multiplier * currentAtr;
                dn = currentEma + multiplier * currentAtr;
            }
            currentUp = up;
            currentDn = dn;
        }

        if (!isLive) {
            // Commit to history
            srcHistory.add(close);
            trHistory.add(tr);
            maHistory.add(currentEma);
            upHistory.add(currentUp);
            dnHistory.add(currentDn);
            trendHistory.add(currentTrend);

            // Update state
            prevTrend = trend;
            trend = currentTrend;
            lastEma = currentEma;
            lastUp = currentUp;
            lastDn = currentDn;
            lastAtr = currentAtr;
            lastClose = close;
        } else {
            // Just update OR add to history for visualization
            if (maHistory.size() == stableHistorySize) {
                // First tick of a new candle
                maHistory.add(currentEma);
                upHistory.add(currentUp);
                dnHistory.add(currentDn);
                trendHistory.add(currentTrend);
            } else {
                updateLastInHistory(maHistory, currentEma);
                updateLastInHistory(upHistory, currentUp);
                updateLastInHistory(dnHistory, currentDn);
                updateLastInHistory(trendHistory, currentTrend);
            }
        }
    }

    private <T> void updateLastInHistory(List<T> history, T value) {
        if (!history.isEmpty()) {
            history.set(history.size() - 1, value);
        }
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
        // Obsolete but kept for reference if needed
        return lastAtr;
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
