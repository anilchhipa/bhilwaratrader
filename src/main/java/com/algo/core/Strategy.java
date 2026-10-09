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

    // Use ArrayDeque for O(1) head removal (vs O(N) ArrayList.remove(0))
    private final java.util.ArrayDeque<Double> ema15History = new java.util.ArrayDeque<>(1001);
    private final java.util.ArrayDeque<Double> ema15_5History = new java.util.ArrayDeque<>(1001);
    private final java.util.ArrayDeque<Double> ema15_15History = new java.util.ArrayDeque<>(1001);
    private final java.util.ArrayDeque<Double> ema15_15_15History = new java.util.ArrayDeque<>(1001);

    // Volatile snapshots — updated on candle close, read lock-free by WebSocket
    // threads
    private volatile List<Double> ema15HistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Double> ema15_5HistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Double> ema15_15HistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Double> ema15_15_15HistorySnapshot = java.util.Collections.emptyList();

    // Dirty flag: set on candle close, cleared after snapshot rebuild.
    // Avoids creating 4×1000-element ArrayList copies every minute per symbol
    // when no external reader has requested the history since the last close.
    private volatile boolean historySnapshotDirty = false;

    // Snapshot variables for stable baselines (state after the last closed candle)
    private double ema15_stable = 0;
    private double ema15_5_stable = 0;
    private double ema15_15_stable = 0;
    private double ema15_15_15_stable = 0;

    private com.algo.indicators.SuperTrendedMA stma;
    private com.algo.indicators.SuperTrendedMA stma2;
    private com.algo.indicators.SupertrendMtfHeikinAshi stHa;

    // --- Config-driven periods ---
    private final int p1; // EMA base period
    private final int c1; // EMA cascade 1
    private final int c2; // EMA cascade 2
    private final int c3; // EMA cascade 3

    public Strategy(IndicatorConfig config) {
        this(config, "1min");
    }

    public Strategy(IndicatorConfig config, String timeframe) {
        this.p1 = config.getEmaPeriod1();
        this.c1 = config.getEmaCascade1();
        this.c2 = config.getEmaCascade2();
        this.c3 = config.getEmaCascade3();

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
        this.stHa = new com.algo.indicators.SupertrendMtfHeikinAshi(timeframe);
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

        // Set stable snapshots immediately
        this.ema15_stable = ema15;
        this.ema15_5_stable = ema15_5;
        this.ema15_15_stable = ema15_15;
        this.ema15_15_15_stable = ema15_15_15;

        if (stma != null) {
            stma.adoptState(candle.getStMa(), candle.getStUp(), candle.getStDn(), candle.getStTrend(),
                    candle.getLow(), candle.getClose(), candle.getAtr());
        }
        if (stma2 != null) {
            // BUG FIX: was incorrectly passing getStDn2() for both up and down bands.
            stma2.adoptState(candle.getStMa2(), candle.getStUp2(), candle.getStDn2(), candle.getStTrend2(),
                    candle.getLow(), candle.getClose(), candle.getAtr());
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
        if (stHa != null) {
            stHa.commit();
            stHa.update(newCandle);
        }

        if (finishedCandle != null) {
            // Commit final states if needed
        }

        populateCandleIndicators(newCandle);

        // Mark snapshots dirty so that the next getEma*History() call rebuilds them.
        // We intentionally do NOT build the 6 ArrayLists here — they are only needed
        // if a WebSocket reader actually requests the history this candle interval.
        historySnapshotDirty = true;
    }



    public synchronized void updateLastCandle(Candle candle) {
        // Always calculate live updates from the STABLE baseline (updateLast = true)
        // so that intra-candle volatility doesn't permanently skew the EMAs
        updateIndicators(candle.getLow(), true);
        if (stma != null)
            stma.updateLive(candle);
        if (stma2 != null)
            stma2.updateLive(candle);
        if (stHa != null)
            stHa.updateLive(candle);

        populateCandleIndicators(candle);
    }

    public void populateCandleIndicators(Candle candle) {
        candle.setEma15(ema15);
        candle.setEma15_5(ema15_5);
        candle.setEma15_15(ema15_15);
        candle.setEma15_15_15(ema15_15_15);

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
        if (stHa != null) {
            if (stHa.getLiveTrailingSl() != 0)
                candle.setHaTrailingSl(stHa.getLiveTrailingSl());
            candle.setHaTrend(stHa.getLiveTrend());
            if (stHa.getLiveHtfTrailingSl() != 0)
                candle.setHtfTrailingSl(stHa.getLiveHtfTrailingSl());
            candle.setHtfTrend(stHa.getLiveHtfTrend());
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

            updateLastInHistory(ema15History, ema15);
            updateLastInHistory(ema15_5History, ema15_5);
            updateLastInHistory(ema15_15History, ema15_15);
            updateLastInHistory(ema15_15_15History, ema15_15_15);

            // OPTIMIZATION: Do NOT publish history snapshots on every live tick.
            // History snapshots (full array copies) are only needed when a candle closes.
            // Live point updates are handled via the scalar getters (getEma15, etc).
        } else {
            ema15 = calculateEMA(price, ema15, p1);
            ema15_5 = calculateEMA(ema15, ema15_5, c1);
            ema15_15 = calculateEMA(ema15, ema15_15, c2);
            ema15_15_15 = calculateEMA(ema15_15, ema15_15_15, c3);

            addToHistory(ema15History, ema15);
            addToHistory(ema15_5History, ema15_5);
            addToHistory(ema15_15History, ema15_15);
            addToHistory(ema15_15_15History, ema15_15_15);
        }
    }

    private void updateSnapshots() {
        ema15_stable = ema15;
        ema15_5_stable = ema15_5;
        ema15_15_stable = ema15_15;
        ema15_15_15_stable = ema15_15_15;
    }

    private void updateLastInHistory(java.util.ArrayDeque<Double> history, double value) {
        if (!history.isEmpty()) {
            history.pollLast();
            history.addLast(value);
        }
    }

    /**
     * Rebuilds all six history snapshots under the existing synchronized lock.
     * Only called lazily when a reader requests history AND the data is dirty.
     * This avoids 6×1000-element ArrayList allocations every candle close when
     * no reader has consumed the history since the previous close.
     */
    private void publishHistorySnapshots() {
        ema15HistorySnapshot                 = new ArrayList<>(ema15History);
        ema15_5HistorySnapshot               = new ArrayList<>(ema15_5History);
        ema15_15HistorySnapshot              = new ArrayList<>(ema15_15History);
        ema15_15_15HistorySnapshot           = new ArrayList<>(ema15_15_15History);
        historySnapshotDirty = false;
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

    // --- Lock-free history getters ---
    // Reads from volatile snapshot fields. Snapshots are rebuilt lazily:
    // only when dirty (a candle has closed since the last read) and a reader
    // actually requests the data. No synchronization needed for the read itself.

    public List<Double> getEma15History() {
        if (historySnapshotDirty) { synchronized (this) { if (historySnapshotDirty) publishHistorySnapshots(); } }
        return ema15HistorySnapshot;
    }

    public List<Double> getEma15_5History() {
        if (historySnapshotDirty) { synchronized (this) { if (historySnapshotDirty) publishHistorySnapshots(); } }
        return ema15_5HistorySnapshot;
    }

    public List<Double> getEma15_15History() {
        if (historySnapshotDirty) { synchronized (this) { if (historySnapshotDirty) publishHistorySnapshots(); } }
        return ema15_15HistorySnapshot;
    }

    public List<Double> getEma15_15_15History() {
        if (historySnapshotDirty) { synchronized (this) { if (historySnapshotDirty) publishHistorySnapshots(); } }
        return ema15_15_15HistorySnapshot;
    }

    public synchronized com.algo.indicators.SuperTrendedMA getStma() {
        return stma;
    }

    public synchronized com.algo.indicators.SupertrendMtfHeikinAshi getStHa() {
        return stHa;
    }
}
