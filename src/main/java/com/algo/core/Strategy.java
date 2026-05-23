package com.algo.core;

import java.util.ArrayList;
import java.util.List;

public class Strategy {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(Strategy.class);
    private String symbol;
    private double ema15 = 0;
    private double ema15_5 = 0;
    private double ema15_15 = 0;
    private double ema15_15_15 = 0;
    private double ema15_15_15_15 = 0;
    private double ema15_15_15_15_30 = 0;

    // Use ArrayDeque for O(1) head removal (vs O(N) ArrayList.remove(0))
    private final java.util.ArrayDeque<Double> ema15History = new java.util.ArrayDeque<>(1001);
    private final java.util.ArrayDeque<Double> ema15_5History = new java.util.ArrayDeque<>(1001);
    private final java.util.ArrayDeque<Double> ema15_15History = new java.util.ArrayDeque<>(1001);
    private final java.util.ArrayDeque<Double> ema15_15_15History = new java.util.ArrayDeque<>(1001);
    private final java.util.ArrayDeque<Double> ema15_15_15_15History = new java.util.ArrayDeque<>(1001);
    private final java.util.ArrayDeque<Double> ema15_15_15_15_30History = new java.util.ArrayDeque<>(1001);

    // Volatile snapshots — updated on candle close, read lock-free by WebSocket
    // threads
    private volatile List<Double> ema15HistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Double> ema15_5HistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Double> ema15_15HistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Double> ema15_15_15HistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Double> ema15_15_15_15HistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Double> ema15_15_15_15_30HistorySnapshot = java.util.Collections.emptyList();

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
    private int currentMacroBias = 0; // 1 = Bullish Macro, -1 = Bearish Macro, 0 = Neutral/Invalid

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
                    candle.getLow(), candle.getClose(), candle.getAtr());
        }
        if (stma2 != null) {
            stma2.adoptState(candle.getStMa2(), candle.getStUp2(), candle.getStDn2(), candle.getStTrend2(),
                    candle.getLow(), candle.getClose(), candle.getAtr());
        }
    }

    public synchronized void onNewCandle(Candle finishedCandle, Candle newCandle, int macroBias) {
        this.currentMacroBias = macroBias;

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

        if (finishedCandle != null) {
            // Commit final states if needed
        }

        populateCandleIndicators(newCandle);

        // Publish lock-free snapshots for WebSocket readers (captured once per candle
        // close)
        publishHistorySnapshots();
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
        // We calculate score even if we don't know the symbol yet
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
            candle.setAtr(stma.getLiveAtr());
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

        // --- Core Trend Score ---
        double score = getTradeScore(candle.getClose());

        // 3. Macro Trend Alignment (The "Big Brother" Rule)
        int directionalSignal = getDirectionalSignal();
        boolean macroAligned = (currentMacroBias == 0 || currentMacroBias == 999
                || directionalSignal == currentMacroBias);

        // Simple filtering: ensure signal is strong and aligned with Macro trend
        // (threshold 5.0).
        int signal = (score >= 5.0 && macroAligned) ? directionalSignal : 0;

        candle.setTradeScore(score);
        candle.setSignal(signal);
    }

    /**
     * returns 1 for strong bullish, -1 for strong bearish, 0 for neutral
     */
    public int getBias() {
        if (stma == null)
            return 0;
        return stma.getTrend();
    }

    // Deleted ER and Z-Score logic.

    // =========================================================================
    // BAYESIAN TRADE SCORE
    // =========================================================================

    /**
     * Composite weighted score focusing purely on Trend Confluence.
     *
     * +2.0 Both SuperTrends agree on the same direction (Mandatory)
     * +3.0 EMA Cloud aligned with ST direction
     */
    public double getTradeScore(double currentPrice) {
        double score = 0.0;

        int st1Trend = (stma != null) ? stma.getLiveTrend() : 0;
        int st2Trend = (stma2 != null) ? stma2.getLiveTrend() : 0;

        // ── Condition 1: Both SuperTrends must agree ─────────────────────────
        if (st1Trend != 0 && st1Trend == st2Trend) {
            score += 2.0;
        } else {
            return 0.0; // Hard exit: No trade if trends conflict
        }

        int direction = st1Trend;

        // ── Condition 2: EMA Cloud alignment (+3.0) ──────────────────────────
        // Combined with SuperTrend, this ensures a strong trend entry.
        boolean emaCloudBullish = ema15_5 > ema15_15_15_15_30;
        boolean emaCloudBearish = ema15_5 < ema15_15_15_15_30;

        if ((direction == 1 && emaCloudBullish) ||
                (direction == -1 && emaCloudBearish)) {
            score += 3.0; // Heavily weighted for cloud confirmation
        }

        return score;
    }

    /**
     * Returns the raw directional signal from aligned SuperTrends.
     * Only call this AFTER confirming tradeScore >= 5.0.
     * +1 = BUY direction
     * -1 = SELL direction
     * 0 = No clear direction
     */
    public int getDirectionalSignal() {
        int st1 = (stma != null) ? stma.getLiveTrend() : 0;
        int st2 = (stma2 != null) ? stma2.getLiveTrend() : 0;
        if (st1 == st2 && st1 != 0)
            return st1;
        return 0;
    }

    private double calculateEMA(double value, double currentEMA, int period) {
        // Correct snap logic: if zero, initialize to 'value'
        if (currentEMA <= 0 || Double.isNaN(currentEMA))
            return value;
        double k = 2.0 / (period + 1);
        return value * k + currentEMA * (1 - k);
    }

    private void addToHistory(java.util.ArrayDeque<Double> history, double value) {
        history.addLast(value);
        if (history.size() > 1000)
            history.pollFirst(); // O(1) — no element shifting
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
            // Re-publish snapshots with updated live values
            publishHistorySnapshots();

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

    private void updateLastInHistory(java.util.ArrayDeque<Double> history, double value) {
        if (!history.isEmpty()) {
            history.pollLast();
            history.addLast(value);
        }
    }

    /**
     * Publishes cheap lock-free snapshots of history for WebSocket/external reads.
     * Called under the existing synchronized lock so the copy is consistent,
     * but readers never need to hold any lock to safely iterate the result.
     */
    private void publishHistorySnapshots() {
        ema15HistorySnapshot = new ArrayList<>(ema15History);
        ema15_5HistorySnapshot = new ArrayList<>(ema15_5History);
        ema15_15HistorySnapshot = new ArrayList<>(ema15_15History);
        ema15_15_15HistorySnapshot = new ArrayList<>(ema15_15_15History);
        ema15_15_15_15HistorySnapshot = new ArrayList<>(ema15_15_15_15History);
        ema15_15_15_15_30HistorySnapshot = new ArrayList<>(ema15_15_15_15_30History);
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

    // --- Lock-free history getters ---
    // Reads from volatile snapshot fields that are updated once per candle close.
    // No synchronization needed — callers (WebSocket broadcaster, etc.) can safely
    // iterate without ever contending with the live-tick processing lock.

    public List<Double> getEma15History() {
        return ema15HistorySnapshot;
    }

    public List<Double> getEma15_5History() {
        return ema15_5HistorySnapshot;
    }

    public List<Double> getEma15_15History() {
        return ema15_15HistorySnapshot;
    }

    public List<Double> getEma15_15_15History() {
        return ema15_15_15HistorySnapshot;
    }

    public List<Double> getEma15_15_15_15History() {
        return ema15_15_15_15HistorySnapshot;
    }

    public List<Double> getEma15_15_15_15_30History() {
        return ema15_15_15_15_30HistorySnapshot;
    }

    public synchronized com.algo.indicators.SuperTrendedMA getStma() {
        return stma;
    }
}
