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

    // Live variables to hold the temporary calculation before commit
    private double liveEma = 0;
    private double liveUp = 0;
    private double liveDn = 0;
    private int liveTrend = 1;
    private double liveAtr = 0;
    private double liveClose = 0;

    private int stableHistorySize = 0;

    public SuperTrendedMA(int maLength, int atrPeriod, double multiplier, boolean changeATR) {
        this.maLength = maLength;
        this.atrPeriod = atrPeriod;
        this.multiplier = multiplier;
        this.changeATR = changeATR;
    }

    /**
     * Adopts state from a known baseline (e.g. from database)
     */
    public void adoptState(Double ma, Double up, Double dn, Integer trend, double lastPrice) {
        this.liveEma = (ma != null) ? ma : 0;
        this.liveUp = (up != null) ? up : 0;
        this.liveDn = (dn != null) ? dn : 0;
        this.liveTrend = (trend != null) ? trend : 1;
        this.liveClose = lastPrice;

        this.lastEma = liveEma;
        this.lastUp = liveUp;
        this.lastDn = liveDn;
        this.trend = liveTrend;
        this.prevTrend = liveTrend;
        this.lastClose = lastPrice;

        // Populate history with at least one point to prevent TR calculation issues
        if (ma != null) {
            maHistory.add(ma);
            upHistory.add(liveUp);
            dnHistory.add(liveDn);
            trendHistory.add(liveTrend);
            srcHistory.add(lastPrice);
        }
    }

    public void commit() {
        lastEma = liveEma;
        lastUp = liveUp;
        lastDn = liveDn;
        prevTrend = trend;
        trend = liveTrend;
        lastAtr = liveAtr;
        lastClose = liveClose;
    }

    public void update(Candle candle) {
        calculateInternal(candle, true); // true = isNewCandle (appends to lists)
        stableHistorySize = maHistory.size();
        limitHistory();
    }

    public void updateLive(Candle candle) {
        calculateInternal(candle, false); // false = not new candle (overwrites last in lists)
    }

    private void calculateInternal(Candle candle, boolean isNewCandle) {
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
        if (trHistory.size() + (isNewCandle ? 1 : 0) >= atrPeriod) {
            double sum = 0;
            // Correctly sum exactly atrPeriod - 1 historical items.
            // If new candle: trHistory has only closed candles.
            // If live: trHistory includes the previous version of this same candle at the
            // end.
            int start = Math.max(0, trHistory.size() - atrPeriod + (isNewCandle ? 1 : 0));
            int end = isNewCandle ? trHistory.size() : trHistory.size() - 1;
            for (int i = start; i < end; i++) {
                sum += trHistory.get(i);
            }
            sum += tr;
            currentAtr = sum / atrPeriod;
        }

        // SuperTrend Logic
        int currentTrend = trend;
        double currentUp = lastUp;
        double currentDn = lastDn;

        if (currentEma != 0 && currentAtr != 0) {
            double up = currentEma - multiplier * currentAtr;
            double dn = currentEma + multiplier * currentAtr;

            if (srcHistory.size() > 0 && lastEma > 0) {
                // Ratchet logic: Ensure the levels only move in the direction of the trend
                // We use lastClose and lastUp/lastDn (the finalized baselines) to prevent
                // live-tick jitter
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

        // Store live values for commit later
        liveEma = currentEma;
        liveUp = currentUp;
        liveDn = currentDn;
        liveTrend = currentTrend;
        liveAtr = currentAtr;
        liveClose = close;

        if (isNewCandle) {
            srcHistory.add(close);
            trHistory.add(tr);
            maHistory.add(currentEma);
            upHistory.add(currentUp);
            dnHistory.add(currentDn);
            trendHistory.add(currentTrend);
        } else {
            updateLastInHistory(srcHistory, close);
            updateLastInHistory(trHistory, tr);
            updateLastInHistory(maHistory, currentEma);
            updateLastInHistory(upHistory, currentUp);
            updateLastInHistory(dnHistory, currentDn);
            updateLastInHistory(trendHistory, currentTrend);
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

    /** Returns the live (intra-candle) MA value without list access. */
    public double getLiveMa() {
        return liveEma;
    }

    /** Returns the live (intra-candle) upper band without list access. */
    public double getLiveUp() {
        return liveUp;
    }

    /** Returns the live (intra-candle) lower band without list access. */
    public double getLiveDn() {
        return liveDn;
    }

    /** Returns the live (intra-candle) trend without list access. */
    public int getLiveTrend() {
        return liveTrend;
    }

    public double getMA() {
        return liveEma;
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

    /**
     * Returns a defensive snapshot of MA history. Safe for external/concurrent
     * reads.
     */
    public List<Double> getMaHistory() {
        return new ArrayList<>(maHistory);
    }

    /**
     * Returns a defensive snapshot of upper-band history. Safe for
     * external/concurrent reads.
     */
    public List<Double> getUpHistory() {
        return new ArrayList<>(upHistory);
    }

    /**
     * Returns a defensive snapshot of lower-band history. Safe for
     * external/concurrent reads.
     */
    public List<Double> getDnHistory() {
        return new ArrayList<>(dnHistory);
    }

    /**
     * Returns a defensive snapshot of trend history. Safe for external/concurrent
     * reads.
     */
    public List<Integer> getTrendHistory() {
        return new ArrayList<>(trendHistory);
    }
}
