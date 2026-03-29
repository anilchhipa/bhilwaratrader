package com.algo.core;

import java.util.ArrayList;
import java.util.List;

public class Strategy {
    private double ema15 = 0;
    private double ema15_5 = 0;
    private double ema15_15 = 0;
    private double ema15_15_15 = 0;
    private double ema15_15_15_15 = 0;
    private double ema15_15_15_15_30 = 0;

    private List<Double> ema15History = new ArrayList<>();
    private List<Double> ema15_5History = new ArrayList<>();
    private List<Double> ema15_15History = new ArrayList<>();
    private List<Double> ema15_15_15History = new ArrayList<>();
    private List<Double> ema15_15_15_15History = new ArrayList<>();
    private List<Double> ema15_15_15_15_30History = new ArrayList<>();

    // Snapshot variables for stable baselines (state after the last closed candle)
    private double ema15_stable = 0;
    private double ema15_5_stable = 0;
    private double ema15_15_stable = 0;
    private double ema15_15_15_stable = 0;
    private double ema15_15_15_15_stable = 0;
    private double ema15_15_15_15_30_stable = 0;

    private com.algo.indicators.SuperTrendedMA stma;
    private com.algo.indicators.SuperTrendedMA stma2;

    // --- Config-driven periods ---
    private final int p1; // EMA base period
    private final int c1; // EMA cascade 1
    private final int c2; // EMA cascade 2
    private final int c3; // EMA cascade 3
    private final int c4; // EMA cascade 4
    private final int c5; // EMA cascade 5

    public Strategy(IndicatorConfig config) {
        this.p1 = config.getEmaPeriod1();
        this.c1 = config.getEmaCascade1();
        this.c2 = config.getEmaCascade2();
        this.c3 = config.getEmaCascade3();
        this.c4 = config.getEmaCascade4();
        this.c5 = config.getEmaCascade5();

        this.stma = new com.algo.indicators.SuperTrendedMA(
                config.getStMaLength(),
                config.getStAtrPeriod(),
                config.getStMultiplier(),
                true);
        this.stma2 = new com.algo.indicators.SuperTrendedMA(
                config.getStMaLength2(),
                config.getStAtrPeriod2(),
                config.getStMultiplier2(),
                true);
    }

    /**
     * Adopts the state from a previously calculated candle.
     * This is used to "prime" the strategy instance so it starts with a smooth
     * baseline.
     */
    public void adoptState(Candle candle) {
        this.ema15 = candle.getEma15() != null ? candle.getEma15() : 0;
        this.ema15_5 = candle.getEma15_5() != null ? candle.getEma15_5() : 0;
        this.ema15_15 = candle.getEma15_15() != null ? candle.getEma15_15() : 0;
        this.ema15_15_15 = candle.getEma15_15_15() != null ? candle.getEma15_15_15() : 0;
        this.ema15_15_15_15 = candle.getEma15_15_15_15() != null ? candle.getEma15_15_15_15() : 0;
        this.ema15_15_15_15_30 = candle.getEma15_15_15_15_30() != null ? candle.getEma15_15_15_15_30() : 0;

        // Set stable snapshots immediately
        this.ema15_stable = ema15;
        this.ema15_5_stable = ema15_5;
        this.ema15_15_stable = ema15_15;
        this.ema15_15_15_stable = ema15_15_15;
        this.ema15_15_15_15_stable = ema15_15_15_15;
        this.ema15_15_15_15_30_stable = ema15_15_15_15_30;

        if (stma != null) {
            stma.adoptState(candle.getStMa(), candle.getStUp(), candle.getStDn(), candle.getStTrend(),
                    candle.getClose());
        }
        if (stma2 != null) {
            stma2.adoptState(candle.getStMa2(), candle.getStUp2(), candle.getStDn2(), candle.getStTrend2(),
                    candle.getClose());
        }
    }

    public synchronized void onNewCandle(Candle finishedCandle, Candle newCandle, int macroBias) {

        // 2. Commit the PREVIOUS candle's final live values to the stable baseline
        updateSnapshots();

        // 3. Calculate the first tick of the NEW candle for EMAs
        updateIndicators(newCandle.getLow(), false);

        if (stma != null) {
            stma.commit();
            stma.update(newCandle);
        }
        if (stma2 != null) {
            stma2.commit();
            stma2.update(newCandle);
        }

        populateCandleIndicators(newCandle);
    }

    public synchronized void updateLastCandle(Candle candle) {
        // Always calculate live updates from the STABLE baseline (updateLast = true)
        // so that intra-candle volatility doesn't permanently skew the EMAs
        updateIndicators(candle.getLow(), true);
        if (stma != null)
            stma.updateLive(candle);
        if (stma2 != null)
            stma2.updateLive(candle);
        populateCandleIndicators(candle);
    }

    public void populateCandleIndicators(Candle candle) {
        candle.setEma15(ema15);
        candle.setEma15_5(ema15_5);
        candle.setEma15_15(ema15_15);
        candle.setEma15_15_15(ema15_15_15);
        candle.setEma15_15_15_15(ema15_15_15_15);
        candle.setEma15_15_15_15_30(ema15_15_15_15_30);

        if (stma != null) {
            if (stma.getLiveMa() != 0)
                candle.setStMa(stma.getLiveMa());
            if (stma.getLiveUp() != 0)
                candle.setStUp(stma.getLiveUp());
            if (stma.getLiveDn() != 0)
                candle.setStDn(stma.getLiveDn());
            candle.setStTrend(stma.getLiveTrend());
        }
        if (stma2 != null) {
            if (stma2.getLiveMa() != 0)
                candle.setStMa2(stma2.getLiveMa());
            if (stma2.getLiveUp() != 0)
                candle.setStUp2(stma2.getLiveUp());
            if (stma2.getLiveDn() != 0)
                candle.setStDn2(stma2.getLiveDn());
            candle.setStTrend2(stma2.getLiveTrend());
        }
    }

    /**
     * returns 1 for strong bullish, -1 for strong bearish, 0 for neutral
     */
    public int getBias() {
        if (stma == null)
            return 0;
        return stma.getTrend();
    }

    private double calculateEMA(double value, double currentEMA, int period) {
        if (currentEMA == 0)
            return value;
        double k = 2.0 / (period + 1);
        return value * k + currentEMA * (1 - k);
    }

    private void addToHistory(List<Double> history, double value) {
        history.add(value);
        if (history.size() > 1000)
            history.remove(0);
    }

    private void updateIndicators(double price, boolean updateLast) {
        if (updateLast) {
            // Revert to stable values before calculating the update for this tick
            ema15 = calculateEMA(price, ema15_stable, p1);
            ema15_5 = calculateEMA(ema15, ema15_5_stable, c1);
            ema15_15 = calculateEMA(ema15, ema15_15_stable, c2);
            ema15_15_15 = calculateEMA(ema15_15, ema15_15_15_stable, c3);
            ema15_15_15_15 = calculateEMA(ema15_15_15, ema15_15_15_15_stable, c4);

            ema15_15_15_15_30 = calculateEMA(ema15_15_15_15, ema15_15_15_15_30_stable, c5);

            updateLastInHistory(ema15History, ema15);
            updateLastInHistory(ema15_5History, ema15_5);
            updateLastInHistory(ema15_15History, ema15_15);
            updateLastInHistory(ema15_15_15History, ema15_15_15);
            updateLastInHistory(ema15_15_15_15History, ema15_15_15_15);
            updateLastInHistory(ema15_15_15_15_30History, ema15_15_15_15_30);

        } else {
            ema15 = calculateEMA(price, ema15, p1);
            ema15_5 = calculateEMA(ema15, ema15_5, c1);
            ema15_15 = calculateEMA(ema15, ema15_15, c2);
            ema15_15_15 = calculateEMA(ema15_15, ema15_15_15, c3);
            ema15_15_15_15 = calculateEMA(ema15_15_15, ema15_15_15_15, c4);

            ema15_15_15_15_30 = calculateEMA(ema15_15_15_15, ema15_15_15_15_30, c5);

            addToHistory(ema15History, ema15);
            addToHistory(ema15_5History, ema15_5);
            addToHistory(ema15_15History, ema15_15);
            addToHistory(ema15_15_15History, ema15_15_15);
            addToHistory(ema15_15_15_15History, ema15_15_15_15);
            addToHistory(ema15_15_15_15_30History, ema15_15_15_15_30);

        }

    }

    private void updateSnapshots() {
        ema15_stable = ema15;
        ema15_5_stable = ema15_5;
        ema15_15_stable = ema15_15;
        ema15_15_15_stable = ema15_15_15;
        ema15_15_15_15_stable = ema15_15_15_15;
        ema15_15_15_15_30_stable = ema15_15_15_15_30;

    }

    private void updateLastInHistory(List<Double> history, double value) {
        if (!history.isEmpty())
            history.set(history.size() - 1, value);
    }

    // Getters for Indicators and History
    public double getEma15() {
        return ema15;
    }

    public double getEma15_5() {
        return ema15_5;
    }

    public double getEma15_15() {
        return ema15_15;
    }

    public double getEma15_15_15() {
        return ema15_15_15;
    }

    public double getEma15_15_15_15() {
        return ema15_15_15_15;
    }

    public double getEma15_15_15_15_30() {
        return ema15_15_15_15_30;
    }

    // --- Thread-safe history getters ---
    // Returns a defensive snapshot of the internal list.
    // Callers (e.g. WebSocket broadcaster) can safely iterate without
    // risking ConcurrentModificationException from concurrent tick updates.

    public synchronized List<Double> getEma15History() {
        return new ArrayList<>(ema15History);
    }

    public synchronized List<Double> getEma15_5History() {
        return new ArrayList<>(ema15_5History);
    }

    public synchronized List<Double> getEma15_15History() {
        return new ArrayList<>(ema15_15History);
    }

    public synchronized List<Double> getEma15_15_15History() {
        return new ArrayList<>(ema15_15_15History);
    }

    public synchronized List<Double> getEma15_15_15_15History() {
        return new ArrayList<>(ema15_15_15_15History);
    }

    public synchronized List<Double> getEma15_15_15_15_30History() {
        return new ArrayList<>(ema15_15_15_15_30History);
    }

    public synchronized com.algo.indicators.SuperTrendedMA getStma() {
        return stma;
    }
}
