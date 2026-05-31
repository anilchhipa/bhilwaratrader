package com.algo.indicators;

import com.algo.core.Candle;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Arc VWAP Supertrend — Java port of the BOSWaves Pine Script indicator.
 *
 * <h3>How it works</h3>
 * <ol>
 * <li><b>Slow ATR</b> – 100-bar SMA of True Range, used as the arc's distance
 * unit.</li>
 * <li><b>Arc</b> – A trailing stop line that starts far from price after a flip
 * and
 * accelerates toward it with an ever-growing velocity. When price is far from
 * VWAP the
 * acceleration is boosted further.</li>
 * <li><b>VWAP triple filter</b> – Running Session / Week / Month VWAPs are
 * maintained.
 * A trend flip is either <em>confirmed</em> (VWAP agrees) or <em>filtered</em>
 * (disagrees).</li>
 * </ol>
 *
 * <h3>Threading model</h3>
 * Follows the stable-baseline pattern used by {@link SuperTrendedMA}:
 * <ul>
 * <li>{@link #commit()} — called once per closed candle; freezes live values
 * into stable state.</li>
 * <li>{@link #update(Candle)} — processes a newly opened candle (appends to
 * history).</li>
 * <li>{@link #updateLive(Candle)} — re-runs calculation from stable baseline
 * during live ticks.</li>
 * </ul>
 * Volatile snapshot lists allow lock-free reads by WebSocket broadcaster
 * threads.
 */
public class ArcVwapSuperTrend {

    // ──────────────────────────────────────────────────────────────
    // Config
    // ──────────────────────────────────────────────────────────────

    /** Base acceleration rate (Pine: accelRate = 0.12) */
    private final double accelRate;

    /** Start distance multiplier in slowATR units (Pine: startMult = 2.0) */
    private final double startMult;

    /** Bars between velocity increments (Pine: smooth = 3) */
    private final int smooth;

    /**
     * How much faster the arc moves when price is far from VWAP (Pine:
     * vwapAccelBoost = 1.5)
     */
    private final double vwapAccelBoost;

    /** Step size coefficient (Pine constant: 0.15 × slowATR) */
    private static final double STEP_COEFF = 0.15;

    // ──────────────────────────────────────────────────────────────
    // Slow ATR (100-bar SMA of True Range)
    // ──────────────────────────────────────────────────────────────
    private static final int SLOW_ATR_PERIOD = 100;
    private final ArrayDeque<Double> trHistory = new ArrayDeque<>(200);
    private double liveSlowAtr = 0; // intra-candle value

    // ──────────────────────────────────────────────────────────────
    // Fast ATR-14 (used for vwapDistNorm)
    // ──────────────────────────────────────────────────────────────
    private static final int FAST_ATR_PERIOD = 14;
    private double lastAtr14 = 0; // stable committed ATR-14
    private double liveAtr14 = 0;
    private double lastClose = Double.NaN; // stable last close (for TR calc)
    private double liveLastClose = Double.NaN;

    // ──────────────────────────────────────────────────────────────
    // VWAPs — three rolling cumulative accumulators
    // ──────────────────────────────────────────────────────────────
    private double cumPvSession = 0, cumVolSession = 0;
    private double cumPvWeek = 0, cumVolWeek = 0;
    private double cumPvMonth = 0, cumVolMonth = 0;

    private double vwapSession = Double.NaN;
    private double vwapWeek = Double.NaN;
    private double vwapMonth = Double.NaN;

    // Period-boundary tracking (stable — committed on candle close)
    private LocalDate lastDate = null;
    private int lastWeek = -1;
    private int lastMonth = -1;

    // ──────────────────────────────────────────────────────────────
    // Arc state
    // ──────────────────────────────────────────────────────────────
    private boolean initDone = false;
    private int barCount = 0; // tracks bar_index % smooth logic

    // Stable (committed) arc state
    private double arc = Double.NaN;
    private double velocity = 0;
    private boolean trend = true; // true = bullish

    // Live (intra-candle) arc state — recalculated from stable baseline each tick
    private double liveArc = Double.NaN; // SMOOTHED output for the chart
    private double liveRawArc = Double.NaN; // RAW un-smoothed internal state
    private double liveVelocity = 0;
    private boolean liveTrend = true;

    private boolean liveFlipped = false;
    private boolean liveFlipConfirmed = false;
    private boolean liveFlipFiltered = false;

    // ──────────────────────────────────────────────────────────────
    // History deques (for broadcasting full chart history)
    // ──────────────────────────────────────────────────────────────
    private final ArrayDeque<Double> arcHistory = new ArrayDeque<>(1001); // Smoothed values for UI
    private final ArrayDeque<Boolean> trendHistory = new ArrayDeque<>(1001);
    private final ArrayDeque<Double> rawArcHistory = new ArrayDeque<>(15); // Raw stepped values for SMA calc

    // Volatile snapshots — lock-free reads for WebSocket threads
    private volatile List<Double> arcHistorySnapshot = java.util.Collections.emptyList();
    private volatile List<Boolean> trendHistorySnapshot = java.util.Collections.emptyList();

    // ──────────────────────────────────────────────────────────────
    // Constructor
    // ──────────────────────────────────────────────────────────────

    public ArcVwapSuperTrend(double accelRate, double startMult, int smooth, double vwapAccelBoost) {
        this.accelRate = accelRate;
        this.startMult = startMult;
        this.smooth = smooth;
        this.vwapAccelBoost = vwapAccelBoost;
    }

    /** Convenience constructor with Pine Script default parameters. */
    public ArcVwapSuperTrend() {
        this(0.12, 2.0, 3, 1.5);
    }

    // ──────────────────────────────────────────────────────────────
    // Public lifecycle methods
    // ──────────────────────────────────────────────────────────────

    /**
     * Called once when a new candle opens (after the previous one closes).
     * Appends a new point to history deques.
     */
    public void update(Candle candle) {
        barCount++;
        calculateInternal(candle, true);
        // Publish snapshots right after a new candle is confirmed
        publishSnapshots();
    }

    /**
     * Called on every live price tick within the current candle.
     * Recalculates from the stable committed baseline — never permanently skews
     * state.
     */
    public void updateLive(Candle candle) {
        calculateInternal(candle, false);
        publishSnapshots();
    }

    /**
     * Freezes live values into stable state. Called by Strategy.onNewCandle()
     * before update() — mirrors SuperTrendedMA.commit().
     */
    public void commit() {
        arc = liveRawArc; // CRITICAL: Commit raw step logic, NOT the smoothed lag
        velocity = liveVelocity;
        trend = liveTrend;

        lastAtr14 = liveAtr14;
        lastClose = liveLastClose;

        // Commit VWAP state — accumulators are already updated in calculateInternal
        // (period resets and additions are idempotent within a single candle)
    }

    // ──────────────────────────────────────────────────────────────
    // Core calculation
    // ──────────────────────────────────────────────────────────────

    private void calculateInternal(Candle candle, boolean isNewCandle) {
        double high = candle.getHigh();
        double low = candle.getLow();
        double close = candle.getClose();
        double hl2 = (high + low) / 2.0;
        double volume = candle.getVolume();
        LocalDate date = candle.getTimestamp().toLocalDate();

        // ── 1. True Range (using stable lastClose as previous close) ──────────────
        double prevClose = Double.isNaN(lastClose) ? close : lastClose;
        double tr = Math.max(high - low,
                Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));

        // ── 2. Slow ATR (100-bar SMA of TR) ──────────────────────────────────────
        if (isNewCandle) {
            trHistory.addLast(tr);
            if (trHistory.size() > SLOW_ATR_PERIOD)
                trHistory.pollFirst();
        }
        // Compute SMA over current window
        double trSum = 0;
        for (double t : trHistory)
            trSum += t;
        int trCount = trHistory.size();
        liveSlowAtr = trCount > 0 ? trSum / trCount : tr;

        // ── 3. Fast ATR-14 (RMA, for vwapDistNorm) ──────────────────────────────
        if (lastAtr14 <= 0) {
            liveAtr14 = tr; // bootstrap
        } else {
            liveAtr14 = (lastAtr14 * (FAST_ATR_PERIOD - 1) + tr) / FAST_ATR_PERIOD;
        }
        liveLastClose = close;

        // ── 4. VWAP updates ──────────────────────────────────────────────────────
        // Period boundary detection
        if (isNewCandle) {
            int weekNum = date.get(WeekFields.ISO.weekOfWeekBasedYear());
            int monthNum = date.getMonthValue();

            if (lastDate != null && !date.equals(lastDate)) {
                // New session (day boundary)
                cumPvSession = 0;
                cumVolSession = 0;
            }
            if (lastDate != null && weekNum != lastWeek) {
                cumPvWeek = 0;
                cumVolWeek = 0;
            }
            if (lastDate != null && monthNum != lastMonth) {
                cumPvMonth = 0;
                cumVolMonth = 0;
            }

            lastDate = date;
            lastWeek = weekNum;
            lastMonth = monthNum;

            cumPvSession += hl2 * volume;
            cumVolSession += volume;
            cumPvWeek += hl2 * volume;
            cumVolWeek += volume;
            cumPvMonth += hl2 * volume;
            cumVolMonth += volume;
        }
        // Live tick: update only the accumulators by replacing the "current"
        // contribution
        // (For simplicity we just re-read from the stable ones, which is fine for live
        // ticks
        // since cumulative VWAPs change very little intra-candle)
        vwapSession = cumVolSession > 0 ? cumPvSession / cumVolSession : hl2;
        vwapWeek = cumVolWeek > 0 ? cumPvWeek / cumVolWeek : hl2;
        vwapMonth = cumVolMonth > 0 ? cumPvMonth / cumVolMonth : hl2;

        // ── 5. Effective acceleration (boosted near VWAP extremes) ────────────────
        double refVwap = Double.isNaN(vwapSession) ? close : vwapSession;
        double safeAtr14 = liveAtr14 > 0 ? liveAtr14 : 1.0;
        double vwapDistNorm = Math.min(Math.abs(close - refVwap) / (safeAtr14 * 4.0), 1.0);
        double effectiveAccel = accelRate * (1.0 + (vwapAccelBoost - 1.0) * vwapDistNorm);

        // ── 6. Arc initialization ─────────────────────────────────────────────────
        double safeSlowAtr = liveSlowAtr > 0 ? liveSlowAtr : (liveAtr14 > 0 ? liveAtr14 : 1.0);

        if (!initDone && safeSlowAtr > 0 && barCount > 20) {
            arc = low - safeSlowAtr * startMult;
            liveArc = arc;
            trend = true;
            liveTrend = true;
            velocity = 0;
            liveVelocity = 0;
            initDone = true;
        }

        if (!initDone) {
            // Not enough bars yet for Arc — but we still emit VWAP/ATR
            liveArc = Double.NaN;
            liveFlipped = false;
            liveFlipConfirmed = false;
            liveFlipFiltered = false;
            if (isNewCandle)
                appendHistory(Double.NaN, true);
            return;
        }

        // ── 7. Work from stable baseline for live
        double workArc = isNewCandle ? arc : arc;
        double workVelocity = isNewCandle ? velocity : velocity;
        boolean workTrend = isNewCandle ? trend : trend;

        // ── 8. Trend flip detection ────────────────────────────────────────────────
        boolean prevTrend = workTrend;
        if (close < workArc)
            workTrend = false;
        if (close > workArc)
            workTrend = true;

        boolean rawFlipped = (workTrend != prevTrend);
        boolean confirmed = rawFlipped && vwapAgrees(workTrend, close);
        boolean filtered = rawFlipped && !confirmed;

        // ── 9. Arc reset on flip ───────────────────────────────────────────────────
        if (rawFlipped && workTrend) {
            workArc = low - safeSlowAtr * startMult;
            workVelocity = 0;
        }
        if (rawFlipped && !workTrend) {
            workArc = high + safeSlowAtr * startMult;
            workVelocity = 0;
        }

        // ── 10. Velocity update every `smooth` bars ────────────────────────────────
        double stepSize = safeSlowAtr * STEP_COEFF;
        if (!rawFlipped && barCount % smooth == 0) {
            workVelocity += effectiveAccel;
            if (workTrend) {
                workArc += stepSize * workVelocity;
            } else {
                workArc -= stepSize * workVelocity;
            }
        }

        // ── 11. SMA smoothing (3-bar) applied to arc value ────────────────────────
        double smoothedArc = workArc; // default: no smoothing yet
        if (!Double.isNaN(workArc) && rawArcHistory.size() >= smooth - 1) {
            double sum = workArc;
            int count = 1;

            // Look at previous raw points from history
            Double[] recent = rawArcHistory.toArray(new Double[0]);
            int start = Math.max(0, recent.length - (smooth - 1));
            for (int i = start; i < recent.length; i++) {
                if (recent[i] != null && !Double.isNaN(recent[i])) {
                    sum += recent[i];
                    count++;
                }
            }
            if (count > 0)
                smoothedArc = sum / count;
        }

        // Store live results
        liveArc = smoothedArc;
        liveRawArc = workArc;
        liveVelocity = workVelocity;
        liveTrend = workTrend;
        liveFlipped = rawFlipped;
        liveFlipConfirmed = confirmed;
        liveFlipFiltered = filtered;

        // ── 12. Append / overwrite history ────────────────────────────────────────
        if (isNewCandle) {
            appendHistory(smoothedArc, workTrend);
        } else {
            overwriteLastHistory(smoothedArc, workTrend);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // VWAP agreement check (Session VWAP — configurable extension point)
    // ──────────────────────────────────────────────────────────────

    private boolean vwapAgrees(boolean bullish, double close) {
        if (Double.isNaN(vwapSession))
            return true; // not yet initialized → don't block
        return bullish ? (close >= vwapSession) : (close <= vwapSession);
    }

    // ──────────────────────────────────────────────────────────────
    // History helpers
    // ──────────────────────────────────────────────────────────────

    private void appendHistory(double arcVal, boolean trendVal) {
        arcHistory.addLast(arcVal);
        trendHistory.addLast(trendVal);

        // Save the raw workArc for SMA calculations
        rawArcHistory.addLast(liveRawArc);

        if (arcHistory.size() > 1000) {
            arcHistory.pollFirst();
            trendHistory.pollFirst();
        }
        if (rawArcHistory.size() > 15) {
            rawArcHistory.pollFirst();
        }
    }

    private void overwriteLastHistory(double arcVal, boolean trendVal) {
        if (!arcHistory.isEmpty()) {
            arcHistory.pollLast();
            arcHistory.addLast(arcVal);
        }
        if (!trendHistory.isEmpty()) {
            trendHistory.pollLast();
            trendHistory.addLast(trendVal);
        }
        if (!rawArcHistory.isEmpty()) {
            rawArcHistory.pollLast();
            rawArcHistory.addLast(liveRawArc);
        }
    }

    private void publishSnapshots() {
        arcHistorySnapshot = new ArrayList<>(arcHistory);
        trendHistorySnapshot = new ArrayList<>(trendHistory);
    }

    // ──────────────────────────────────────────────────────────────
    // Live value getters (current intra-candle state)
    // ──────────────────────────────────────────────────────────────

    /** Current arc value (smoothed). NaN before initialization. */
    public double getLiveArc() {
        return liveArc;
    }

    /** Current trend: true = bullish, false = bearish. */
    public boolean getLiveTrend() {
        return liveTrend;
    }

    /** True if the trend flipped on the current bar. */
    public boolean isLiveFlipped() {
        return liveFlipped;
    }

    /** True if flip is confirmed by Session VWAP. */
    public boolean isFlipConfirmed() {
        return liveFlipConfirmed;
    }

    /** True if flip was rejected by Session VWAP. */
    public boolean isFlipFiltered() {
        return liveFlipFiltered;
    }

    /** Session VWAP value. */
    public double getVwapSession() {
        return vwapSession;
    }

    /** Weekly VWAP value. */
    public double getVwapWeek() {
        return vwapWeek;
    }

    /** Monthly VWAP value. */
    public double getVwapMonth() {
        return vwapMonth;
    }

    /** Slow ATR (100-bar SMA of TR) — live value. */
    public double getLiveSlowAtr() {
        return liveSlowAtr;
    }

    // ──────────────────────────────────────────────────────────────
    // Lock-free history snapshots (for WebSocket broadcaster)
    // ──────────────────────────────────────────────────────────────

    public List<Double> getArcHistory() {
        return arcHistorySnapshot;
    }

    public List<Boolean> getTrendHistory() {
        return trendHistorySnapshot;
    }
}
