package com.algo.indicators;

import com.algo.core.Candle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class SuperTrendedMA {
    private int maLength;
    private int atrPeriod;
    private double multiplier;
    private boolean changeATR;

    // ArrayDeque gives O(1) head removal vs O(N) ArrayList.remove(0)
    private final ArrayDeque<Double> srcHistory = new ArrayDeque<>(1001);
    private final ArrayDeque<Double> trHistory = new ArrayDeque<>(1001);
    private final ArrayDeque<Double> maHistory = new ArrayDeque<>(1001);
    private final ArrayDeque<Double> upHistory = new ArrayDeque<>(1001);
    private final ArrayDeque<Double> dnHistory = new ArrayDeque<>(1001);
    private final ArrayDeque<Integer> trendHistory = new ArrayDeque<>(1001);

    // Volatile snapshots for thread-safe, lock-free reads by external threads
    // (UI/Broadcaster)
    private volatile List<Double> maHistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Double> upHistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Double> dnHistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Integer> trendHistorySnapshot = java.util.Collections.emptyList();

    // State
    private double lastEma = 0;
    private double lastUp = 0;
    private double lastDn = 0;
    private int trend = 1;
    private int prevTrend = 1;
    private double lastAtr = 0;
    private double lastClose = 0;
    private double trueLastClose = 0;

    // Live variables to hold the temporary calculation before commit
    private double liveEma = 0;
    private double liveUp = 0;
    private double liveDn = 0;
    private int liveTrend = 1;
    private double liveAtr = 0;
    private double liveClose = 0;
    private double trueLiveClose = 0;

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
    public void adoptState(Double ma, Double up, Double dn, Integer trend, double lastPrice, double actualClose,
            Double atr) {
        this.liveEma = (ma != null) ? ma : 0;
        this.liveUp = (up != null) ? up : 0;
        this.liveDn = (dn != null) ? dn : 0;
        this.liveTrend = (trend != null) ? trend : 1;
        this.liveClose = lastPrice;
        this.trueLiveClose = actualClose;
        this.liveAtr = (atr != null) ? atr : 0;

        this.lastEma = liveEma;
        this.lastUp = liveUp;
        this.lastDn = liveDn;
        this.trend = liveTrend;
        this.prevTrend = liveTrend;
        this.lastClose = lastPrice;
        this.trueLastClose = actualClose;
        this.lastAtr = liveAtr;

        // Populate history with at least one point to prevent TR calculation issues
        if (ma != null) {
            maHistory.add(ma);
            upHistory.add(liveUp);
            dnHistory.add(liveDn);
            trendHistory.add(liveTrend);
            srcHistory.add(lastPrice);
        }
        publishSnapshots();
    }

    public void commit() {
        lastEma = liveEma;
        lastUp = liveUp;
        lastDn = liveDn;
        prevTrend = trend;
        trend = liveTrend;
        lastAtr = liveAtr;
        lastClose = liveClose;
        trueLastClose = trueLiveClose;
        publishSnapshots();
    }

    public void update(Candle candle) {
        calculateInternal(candle, true); // true = isNewCandle (appends to lists)
        stableHistorySize = maHistory.size();
        limitHistory();
    }

    public void updateLive(Candle candle) {
        calculateInternal(candle, false); // false = not new candle (overwrites last in lists)
        publishSnapshots();
    }

    private void publishSnapshots() {
        maHistorySnapshot = new ArrayList<>(maHistory);
        upHistorySnapshot = new ArrayList<>(upHistory);
        dnHistorySnapshot = new ArrayList<>(dnHistory);
        trendHistorySnapshot = new ArrayList<>(trendHistory);
    }

    private void calculateInternal(Candle candle, boolean isNewCandle) {
        double price = candle.getLow(); // Using LOW instead of CLOSE as per user request
        double high = candle.getHigh();
        double low = candle.getLow();
        double actualClose = candle.getClose(); // The actual intra-bar close

        // Calculate TR using the true last close per Pine Script ta.tr
        double tr = 0;
        if (!srcHistory.isEmpty()) {
            tr = Math.max(high - low, Math.max(Math.abs(high - trueLastClose), Math.abs(low - trueLastClose)));
        } else {
            tr = high - low;
        }

        // Calculate EMA - snap to price if lastEma is zero or negative
        double k_ma = 2.0 / (maLength + 1);
        double currentEma = (lastEma <= 0 || Double.isNaN(lastEma)) ? price : price * k_ma + lastEma * (1 - k_ma);

        // Calculate ATR using RMA (standard) or EMA based on changeATR flag
        double currentAtr = 0;
        int availableHistory = trHistory.size() + (isNewCandle ? 1 : 0);

        // If lastAtr > 0 (Instant Sync from DB), we map seamlessly back into RMA
        // ignoring slow warmup
        if (availableHistory >= atrPeriod || lastAtr > 0) {
            if (lastAtr <= 0) {
                // Initialize with Simple Moving Average (SMA).
                // ArrayDeque doesn't support indexed get(), so snapshot to array once.
                Double[] trArr = trHistory.toArray(new Double[0]);
                double sum = 0;
                int start = Math.max(0, trArr.length - atrPeriod + (isNewCandle ? 1 : 0));
                int end = isNewCandle ? trArr.length : trArr.length - 1;
                for (int i = start; i < end; i++)
                    sum += trArr[i];
                sum += tr;
                currentAtr = sum / atrPeriod;
            } else {
                if (changeATR) {
                    // RMA Smoothing (Standard ATR): (prevAtr * (n-1) + tr) / n
                    currentAtr = (lastAtr * (atrPeriod - 1) + tr) / atrPeriod;
                } else {
                    // EMA Smoothing: tr * k_atr + lastAtr * (1-k_atr) where k_atr = 2/(n+1)
                    double k_atr = 2.0 / (atrPeriod + 1);
                    currentAtr = tr * k_atr + lastAtr * (1 - k_atr);
                }
            }
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

                if (currentTrend == -1 && price > lastDn) {
                    currentTrend = 1;
                } else if (currentTrend == 1 && price < lastUp) {
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
        liveClose = price;
        trueLiveClose = actualClose;

        if (isNewCandle) {
            srcHistory.addLast(price);
            trHistory.addLast(tr);
            maHistory.addLast(currentEma);
            upHistory.addLast(currentUp);
            dnHistory.addLast(currentDn);
            trendHistory.addLast(currentTrend);
        } else {
            updateLastInHistory(srcHistory, price);
            updateLastInHistory(trHistory, tr);
            updateLastInHistory(maHistory, currentEma);
            updateLastInHistory(upHistory, currentUp);
            updateLastInHistory(dnHistory, currentDn);
            updateLastInHistory(trendHistory, currentTrend);
        }
    }

    private <T> void updateLastInHistory(ArrayDeque<T> history, T value) {
        if (!history.isEmpty()) {
            history.pollLast();
            history.addLast(value);
        }
    }

    private void limitHistory() {
        // pollFirst() is O(1) on ArrayDeque; the old remove(0) was O(N) on ArrayList
        if (srcHistory.size() > 1000) {
            srcHistory.pollFirst();
            trHistory.pollFirst();
            maHistory.pollFirst();
            upHistory.pollFirst();
            dnHistory.pollFirst();
            trendHistory.pollFirst();
        }
    }

    /** Returns the live (intra-candle) MA value without list access. */
    public double getLiveMa() {
        return (liveTrend == 1) ? liveUp : liveDn;
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

    public double getLiveAtr() {
        return liveAtr;
    }

    public double getMA() {
        return (liveTrend == 1) ? liveUp : liveDn;
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
     * Returns a defensive snapshot of MA history.
     * Lock-free read via volatile snapshot updated on candle close/tick.
     */
    public List<Double> getMaHistory() {
        return maHistorySnapshot;
    }

    /**
     * Returns a defensive snapshot of upper-band history.
     */
    public List<Double> getUpHistory() {
        return upHistorySnapshot;
    }

    /**
     * Returns a defensive snapshot of lower-band history.
     */
    public List<Double> getDnHistory() {
        return dnHistorySnapshot;
    }

    /**
     * Returns a defensive snapshot of trend history.
     */
    public List<Integer> getTrendHistory() {
        return trendHistorySnapshot;
    }
}
