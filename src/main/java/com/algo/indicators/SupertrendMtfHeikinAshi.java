package com.algo.indicators;

import com.algo.core.Candle;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Supertrend MTF Heikin Ashi
 * Java implementation of the Pine Script v4 indicator by LonesomeTheBlue:
 * "Supertrend MTF Heikin Ashi"
 *
 * <h3>Logic Summary</h3>
 * <ol>
 *   <li><b>Current Timeframe Heikin Ashi</b>:
 *     Calculates synthetic Heikin Ashi bars (haOpen, haHigh, haLow, haClose) and
 *     RMA-based ATR(Period) on Heikin Ashi values.
 *   </li>
 *   <li><b>Current Timeframe SuperTrend</b>:
 *     Calculates Up and Dn bands: (haHigh + haLow) / 2 +/- (Mult * haAtr).
 *     Ratchets TUp and TDown based on previous haClose and determines Trend (+1 for bull, -1 for bear).
 *     Trailing SL = Trend == 1 ? TUp : TDown.
 *   </li>
 *   <li><b>Higher Timeframe (HTF) Heikin Ashi SuperTrend</b>:
 *     Uses auto higher timeframe mapping (e.g. 1m -> 5m, 3m -> 15m, 5m -> 15m, etc.) or user-defined HTF.
 *     Samples the closed HTF Heikin Ashi bar (lookahead = barmerge.lookahead_on with [1]).
 *     Ratchets TUpHtf and TDownHtf based on closehtf.
 *     HTF Trailing SL = TrendHtf == 1 ? TUpHtf : TDownHtf.
 *   </li>
 *   <li><b>Alerts & Flips</b>:
 *     Detects Trend flips (+1 to -1 and -1 to +1) for both Current Timeframe and HTF.
 *   </li>
 * </ol>
 */
public class SupertrendMtfHeikinAshi {

    public static final String MODE_AUTO = "Auto";
    public static final String MODE_USER_DEFINED = "User Defined";

    private final String currentTf;
    private final String mode;
    private final String userHtf;
    private final String resolvedHtf;
    private final double multiplier;
    private final int atrPeriod;

    // ─────────────────────────────────────────────────────────────────────────
    // Heikin Ashi & RMA Tracker for a single bar stream
    // ─────────────────────────────────────────────────────────────────────────
    public static class HaBar {
        public final double open;
        public final double high;
        public final double low;
        public final double close;
        public final LocalDateTime timestamp;

        public HaBar(double open, double high, double low, double close, LocalDateTime timestamp) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
            this.timestamp = timestamp;
        }
    }

    /**
     * Complete indicator calculation output point for each candle bar.
     */
    public static class SupertrendPoint {
        public LocalDateTime timestamp;
        public long epochMilli;

        // Base Heikin Ashi candle
        public double haOpen;
        public double haHigh;
        public double haLow;
        public double haClose;
        public double haAtr;

        // Current timeframe Supertrend
        public double up;
        public double dn;
        public double tUp;
        public double tDown;
        public int trend; // +1 Bull, -1 Bear
        public double trailingSl;
        public boolean isBullishFlip; // trend == 1 and prevTrend == -1
        public boolean isBearishFlip; // trend == -1 and prevTrend == 1

        // Higher Timeframe (HTF) Supertrend
        public String htfTimeframe;
        public double htfHigh;
        public double htfLow;
        public double htfClose;
        public double htfAtr;
        public double htfUp;
        public double htfDn;
        public double htfTUp;
        public double htfTDown;
        public int htfTrend; // +1 Bull, -1 Bear
        public double htfTrailingSl;
        public boolean isHtfBullishFlip; // htfTrend == 1 and prevHtfTrend == -1
        public boolean isHtfBearishFlip; // htfTrend == -1 and prevHtfTrend == 1

        @Override
        public String toString() {
            return String.format(
                "SupertrendPoint[ts=%s, trend=%d, sl=%.2f, flip=%s, htf=%s, htfTrend=%d, htfSl=%.2f, htfFlip=%s]",
                timestamp, trend, trailingSl,
                (isBullishFlip ? "BULL" : (isBearishFlip ? "BEAR" : "NONE")),
                htfTimeframe, htfTrend, htfTrailingSl,
                (isHtfBullishFlip ? "BULL" : (isHtfBearishFlip ? "BEAR" : "NONE"))
            );
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal State for Streaming Execution
    // ─────────────────────────────────────────────────────────────────────────

    // Current TF state (stable after commit)
    private double lastHaOpen = Double.NaN;
    private double lastHaClose = Double.NaN;
    private double lastAtr = 0;
    private double lastTUp = Double.NaN;
    private double lastTDown = Double.NaN;
    private int lastTrend = 1;
    private int prevTrend = 1;

    // HTF state
    private final List<Candle> htfAccumulator = new ArrayList<>();
    private double lastHtfHaOpen = Double.NaN;
    private double lastHtfHaClose = Double.NaN;
    private double lastHtfAtr = 0;
    private double lastHtfTUp = Double.NaN;
    private double lastHtfTDown = Double.NaN;
    private int lastHtfTrend = 1;
    private int prevHtfTrend = 1;
    private double lastHtfHigh = Double.NaN;
    private double lastHtfLow = Double.NaN;
    private double lastHtfCloseVal = Double.NaN;

    // Live variables (temporary for intra-candle ticks)
    private double liveHaOpen = Double.NaN;
    private double liveHaHigh = Double.NaN;
    private double liveHaLow = Double.NaN;
    private double liveHaClose = Double.NaN;
    private double liveAtr = 0;
    private double liveTUp = Double.NaN;
    private double liveTDown = Double.NaN;
    private int liveTrend = 1;
    private double liveTrailingSl = 0;

    private double liveHtfTrailingSl = 0;
    private int liveHtfTrend = 1;

    // Volatile snapshots for thread-safe external reads
    private volatile List<SupertrendPoint> historySnapshot = Collections.emptyList();
    private final List<SupertrendPoint> history = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Constructors
    // ─────────────────────────────────────────────────────────────────────────

    public SupertrendMtfHeikinAshi() {
        this("1min", MODE_AUTO, "5", 2.0, 7);
    }

    public SupertrendMtfHeikinAshi(String currentTf) {
        this(currentTf, MODE_AUTO, "5", 2.0, 7);
    }

    public SupertrendMtfHeikinAshi(String currentTf, String mode, String userHtf, double multiplier, int atrPeriod) {
        this.currentTf = normalizeTimeframe(currentTf);
        this.mode = (mode != null && mode.equalsIgnoreCase(MODE_USER_DEFINED)) ? MODE_USER_DEFINED : MODE_AUTO;
        this.userHtf = (userHtf != null && !userHtf.isBlank()) ? normalizeTimeframe(userHtf) : "5min";
        this.resolvedHtf = resolveHtf(this.currentTf, this.mode, this.userHtf);
        this.multiplier = multiplier > 0 ? multiplier : 2.0;
        this.atrPeriod = atrPeriod > 0 ? atrPeriod : 7;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Timeframe & Auto HTF Mapping (Pine Script exact match)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Pine Script Auto Higher Timeframe logic:
     * timeframe.period == '1'   ? '5' :
     * timeframe.period == '3'   ? '15' :
     * timeframe.period == '5'   ? '15' :
     * timeframe.period == '15'  ? '60' :
     * timeframe.period == '30'  ? '120' :
     * timeframe.period == '45'  ? '120' :
     * timeframe.period == '60'  ? '240' :
     * timeframe.period == '120' ? '240' :
     * timeframe.period == '180' ? '240' :
     * timeframe.period == '240' ? 'D' :
     * timeframe.period == 'D'   ? 'W' :
     * timeframe.period == 'W'   ? '5W' : 'D'
     */
    public static String getAutoHtf(String tf) {
        String clean = normalizeTimeframe(tf);
        switch (clean) {
            case "1min":
                return "5min";
            case "3min":
                return "15min";
            case "5min":
                return "15min";
            case "15min":
                return "60min";
            case "30min":
                return "120min";
            case "45min":
                return "120min";
            case "60min":
                return "240min";
            case "120min":
                return "240min";
            case "180min":
                return "240min";
            case "240min":
                return "D";
            case "D":
                return "W";
            case "W":
                return "5W";
            default:
                return "D";
        }
    }

    public static String resolveHtf(String currentTf, String mode, String userHtf) {
        if (MODE_USER_DEFINED.equalsIgnoreCase(mode) && userHtf != null && !userHtf.isBlank()) {
            return normalizeTimeframe(userHtf);
        }
        return getAutoHtf(currentTf);
    }

    public static String normalizeTimeframe(String tf) {
        if (tf == null) return "1min";
        String s = tf.trim().toUpperCase();
        if (s.equals("1") || s.equals("1MIN") || s.equals("1M")) return "1min";
        if (s.equals("3") || s.equals("3MIN") || s.equals("3M")) return "3min";
        if (s.equals("5") || s.equals("5MIN") || s.equals("5M")) return "5min";
        if (s.equals("15") || s.equals("15MIN") || s.equals("15M")) return "15min";
        if (s.equals("30") || s.equals("30MIN") || s.equals("30M")) return "30min";
        if (s.equals("45") || s.equals("45MIN") || s.equals("45M")) return "45min";
        if (s.equals("60") || s.equals("60MIN") || s.equals("1H") || s.equals("60M")) return "60min";
        if (s.equals("120") || s.equals("120MIN") || s.equals("2H") || s.equals("120M")) return "120min";
        if (s.equals("180") || s.equals("180MIN") || s.equals("3H") || s.equals("180M")) return "180min";
        if (s.equals("240") || s.equals("240MIN") || s.equals("4H") || s.equals("240M")) return "240min";
        if (s.equals("D") || s.equals("1D") || s.equals("DAY") || s.equals("DAILY")) return "D";
        if (s.equals("W") || s.equals("1W") || s.equals("WEEK") || s.equals("WEEKLY")) return "W";
        if (s.equals("5W") || s.equals("MONTH") || s.equals("M")) return "5W";
        return tf.toLowerCase();
    }

    public static int timeframeToMinutes(String tf) {
        String clean = normalizeTimeframe(tf);
        switch (clean) {
            case "1min": return 1;
            case "3min": return 3;
            case "5min": return 5;
            case "15min": return 15;
            case "30min": return 30;
            case "45min": return 45;
            case "60min": return 60;
            case "120min": return 120;
            case "180min": return 180;
            case "240min": return 240;
            case "D": return 1440;
            case "W": return 10080;
            case "5W": return 50400;
            default:
                if (clean.endsWith("min")) {
                    try {
                        return Integer.parseInt(clean.replace("min", ""));
                    } catch (Exception ignored) {}
                }
                return 5;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Batch Calculation (Full History Processing)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Batch calculation given base candles. Higher timeframe candles are automatically
     * aggregated from the base candles according to the specified/resolved HTF.
     */
    public static List<SupertrendPoint> calculate(List<Candle> currentCandles, String currentTf) {
        return calculate(currentCandles, currentTf, MODE_AUTO, "5", 2.0, 7);
    }

    public static List<SupertrendPoint> calculate(
            List<Candle> currentCandles,
            String currentTf,
            String mode,
            String userHtf,
            double multiplier,
            int atrPeriod) {
        if (currentCandles == null || currentCandles.isEmpty()) {
            return Collections.emptyList();
        }

        String resolvedHtf = resolveHtf(currentTf, mode, userHtf);
        List<Candle> htfCandles = aggregateCandles(currentCandles, resolvedHtf);
        return calculate(currentCandles, htfCandles, currentTf, resolvedHtf, multiplier, atrPeriod);
    }

    /**
     * Batch calculation given both current timeframe candles and higher timeframe candles.
     */
    public static List<SupertrendPoint> calculate(
            List<Candle> currentCandles,
            List<Candle> htfCandles,
            String currentTf,
            String htfTf,
            double multiplier,
            int atrPeriod) {

        if (currentCandles == null || currentCandles.isEmpty()) {
            return Collections.emptyList();
        }

        int n = currentCandles.size();
        List<SupertrendPoint> points = new ArrayList<>(n);

        // 1. Calculate Heikin Ashi series for current timeframe
        List<HaBar> currentHa = computeHeikinAshi(currentCandles);
        List<Double> currentHaAtr = computeWilderAtr(currentHa, atrPeriod);

        // 2. Calculate Heikin Ashi series and ATR for HTF
        List<HaBar> htfHa = (htfCandles != null && !htfCandles.isEmpty())
                ? computeHeikinAshi(htfCandles)
                : Collections.emptyList();
        List<Double> htfHaAtr = (htfHa != null && !htfHa.isEmpty())
                ? computeWilderAtr(htfHa, atrPeriod)
                : Collections.emptyList();

        // 3. Current Timeframe Supertrend State
        double prevTUp = Double.NaN;
        double prevTDown = Double.NaN;
        int prevTrend = 1;

        // 4. Higher Timeframe Supertrend State on Current Chart
        double prevTUpHtf = Double.NaN;
        double prevTDownHtf = Double.NaN;
        int prevTrendHtf = 1;
        double prevCloseHtf = Double.NaN;

        // Map HTF bars by end timestamp for non-repainting closed-bar lookup (lookahead_on with [1])
        int htfIndex = 0;

        for (int i = 0; i < n; i++) {
            Candle candle = currentCandles.get(i);
            HaBar ha = currentHa.get(i);
            double atr = currentHaAtr.get(i);

            SupertrendPoint pt = new SupertrendPoint();
            pt.timestamp = candle.getTimestamp();
            pt.epochMilli = candle.getEpochMilli();
            pt.haOpen = ha.open;
            pt.haHigh = ha.high;
            pt.haLow = ha.low;
            pt.haClose = ha.close;
            pt.haAtr = atr;

            // --- Current TF Supertrend Bands ---
            pt.up = (ha.high + ha.low) / 2.0 - (multiplier * atr);
            pt.dn = (ha.high + ha.low) / 2.0 + (multiplier * atr);

            if (i == 0 || Double.isNaN(prevTUp)) {
                pt.tUp = pt.up;
                pt.tDown = pt.dn;
                pt.trend = 1;
            } else {
                double prevHaClose = currentHa.get(i - 1).close;
                pt.tUp = (prevHaClose > prevTUp) ? Math.max(pt.up, prevTUp) : pt.up;
                pt.tDown = (prevHaClose < prevTDown) ? Math.min(pt.dn, prevTDown) : pt.dn;

                if (ha.close > prevTDown) {
                    pt.trend = 1;
                } else if (ha.close < prevTUp) {
                    pt.trend = -1;
                } else {
                    pt.trend = (prevTrend != 0) ? prevTrend : 1;
                }
            }

            pt.trailingSl = (pt.trend == 1) ? pt.tUp : pt.tDown;
            pt.isBullishFlip = (i > 0 && pt.trend == 1 && prevTrend == -1);
            pt.isBearishFlip = (i > 0 && pt.trend == -1 && prevTrend == 1);

            prevTUp = pt.tUp;
            prevTDown = pt.tDown;
            prevTrend = pt.trend;

            // --- Higher Time Frame Supertrend (lookahead_on with [1]) ---
            pt.htfTimeframe = htfTf;
            LocalDateTime currentBarTime = candle.getTimestamp();

            // Find the most recently CLOSED HTF candle as of currentBarTime
            if (htfCandles != null && !htfCandles.isEmpty()) {
                while (htfIndex + 1 < htfCandles.size() &&
                        !htfCandles.get(htfIndex + 1).getTimestamp().isAfter(currentBarTime)) {
                    htfIndex++;
                }

                // In Pine Script: security(..., HTF, high[1], lookahead=barmerge.lookahead_on)
                // This means the closed bar of the HTF. If htfIndex is the current active HTF bar,
                // bar [1] is htfIndex - 1. If htfIndex is already fully in the past, it's that closed bar.
                int closedHtfIdx = htfIndex;
                if (closedHtfIdx < htfHa.size() && closedHtfIdx >= 0) {
                    HaBar htfBar = htfHa.get(closedHtfIdx);
                    double hAtr = htfHaAtr.get(closedHtfIdx);

                    pt.htfHigh = htfBar.high;
                    pt.htfLow = htfBar.low;
                    pt.htfClose = htfBar.close;
                    pt.htfAtr = hAtr;

                    // Uphtf = abs(highhtf + lowhtf) / 2 - (Mult * HTfatr)
                    // Dnhtf = abs(highhtf + lowhtf) / 2 + (Mult * HTfatr)
                    pt.htfUp = Math.abs(pt.htfHigh + pt.htfLow) / 2.0 - (multiplier * hAtr);
                    pt.htfDn = Math.abs(pt.htfHigh + pt.htfLow) / 2.0 + (multiplier * hAtr);

                    if (i == 0 || Double.isNaN(prevTUpHtf)) {
                        pt.htfTUp = pt.htfUp;
                        pt.htfTDown = pt.htfDn;
                        pt.htfTrend = 1;
                    } else {
                        // TUphtf := closehtf[1] > TUphtf[1] ? max(Uphtf, TUphtf[1]) : Uphtf
                        // TDownhtf := closehtf[1] < TDownhtf[1] ? min(Dnhtf,TDownhtf[1]) : Dnhtf
                        // TrendHtf := closehtf > TDownhtf[1] ? 1 : closehtf < TUphtf[1] ? -1: nz(TrendHtf[1], 1)
                        pt.htfTUp = (prevCloseHtf > prevTUpHtf) ? Math.max(pt.htfUp, prevTUpHtf) : pt.htfUp;
                        pt.htfTDown = (prevCloseHtf < prevTDownHtf) ? Math.min(pt.htfDn, prevTDownHtf) : pt.htfDn;

                        if (pt.htfClose > prevTDownHtf) {
                            pt.htfTrend = 1;
                        } else if (pt.htfClose < prevTUpHtf) {
                            pt.htfTrend = -1;
                        } else {
                            pt.htfTrend = (prevTrendHtf != 0) ? prevTrendHtf : 1;
                        }
                    }

                    pt.htfTrailingSl = (pt.htfTrend == 1) ? pt.htfTUp : pt.htfTDown;
                    pt.isHtfBullishFlip = (i > 0 && pt.htfTrend == 1 && prevTrendHtf == -1);
                    pt.isHtfBearishFlip = (i > 0 && pt.htfTrend == -1 && prevTrendHtf == 1);

                    prevTUpHtf = pt.htfTUp;
                    prevTDownHtf = pt.htfTDown;
                    prevTrendHtf = pt.htfTrend;
                    prevCloseHtf = pt.htfClose;
                }
            }

            points.add(pt);
        }

        return points;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Streaming Live Calculation (Tick and Closed Candle)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Freezes the state of the closed candle into the stable baseline.
     * Follows the same pattern as {@link SuperTrendedMA#commit()}.
     */
    public synchronized void commit() {
        lastHaOpen = liveHaOpen;
        lastHaClose = liveHaClose;
        lastAtr = liveAtr;
        lastTUp = liveTUp;
        lastTDown = liveTDown;
        prevTrend = lastTrend;
        lastTrend = liveTrend;

        prevHtfTrend = lastHtfTrend;
        lastHtfTrend = liveHtfTrend;
        publishSnapshot();
    }

    /**
     * Called when a new candle opens or a completed candle is added to history.
     */
    public synchronized void update(Candle candle) {
        calculateInternal(candle, true);
        publishSnapshot();
    }

    /**
     * Called on intra-candle live ticks. Evaluates from stable baseline without state drift.
     */
    public synchronized void updateLive(Candle candle) {
        calculateInternal(candle, false);
    }

    private void calculateInternal(Candle candle, boolean isNewCandle) {
        double o = candle.getOpen();
        double h = candle.getHigh();
        double l = candle.getLow();
        double c = candle.getClose();

        // 1. Calculate Heikin Ashi values
        double haClose = (o + h + l + c) / 4.0;
        double haOpen;
        if (Double.isNaN(lastHaOpen)) {
            haOpen = (o + c) / 2.0;
        } else {
            haOpen = (lastHaOpen + lastHaClose) / 2.0;
        }
        double haHigh = Math.max(h, Math.max(haOpen, haClose));
        double haLow = Math.min(l, Math.min(haOpen, haClose));

        // 2. True Range & RMA ATR on Heikin Ashi
        double tr;
        if (Double.isNaN(lastHaClose)) {
            tr = haHigh - haLow;
        } else {
            tr = Math.max(haHigh - haLow, Math.max(Math.abs(haHigh - lastHaClose), Math.abs(haLow - lastHaClose)));
        }

        double currentAtr;
        if (lastAtr <= 0) {
            currentAtr = tr;
        } else {
            currentAtr = (lastAtr * (atrPeriod - 1) + tr) / atrPeriod;
        }

        // 3. Supertrend Bands
        double up = (haHigh + haLow) / 2.0 - (multiplier * currentAtr);
        double dn = (haHigh + haLow) / 2.0 + (multiplier * currentAtr);

        double tUp = up;
        double tDown = dn;
        int trend = lastTrend;

        if (!Double.isNaN(lastTUp) && !Double.isNaN(lastHaClose)) {
            tUp = (lastHaClose > lastTUp) ? Math.max(up, lastTUp) : up;
            tDown = (lastHaClose < lastTDown) ? Math.min(dn, lastTDown) : dn;

            if (haClose > lastTDown) {
                trend = 1;
            } else if (haClose < lastTUp) {
                trend = -1;
            } else {
                trend = (lastTrend != 0) ? lastTrend : 1;
            }
        }

        double trailingSl = (trend == 1) ? tUp : tDown;

        // Store live values
        liveHaOpen = haOpen;
        liveHaHigh = haHigh;
        liveHaLow = haLow;
        liveHaClose = haClose;
        liveAtr = currentAtr;
        liveTUp = tUp;
        liveTDown = tDown;
        liveTrend = trend;
        liveTrailingSl = trailingSl;

        // 4. Update HTF state if applicable
        updateHtfLive(candle, isNewCandle);

        SupertrendPoint pt = new SupertrendPoint();
        pt.timestamp = candle.getTimestamp();
        pt.epochMilli = candle.getEpochMilli();
        pt.haOpen = haOpen;
        pt.haHigh = haHigh;
        pt.haLow = haLow;
        pt.haClose = haClose;
        pt.haAtr = currentAtr;
        pt.up = up;
        pt.dn = dn;
        pt.tUp = tUp;
        pt.tDown = tDown;
        pt.trend = trend;
        pt.trailingSl = trailingSl;
        pt.isBullishFlip = (trend == 1 && prevTrend == -1);
        pt.isBearishFlip = (trend == -1 && prevTrend == 1);

        pt.htfTimeframe = resolvedHtf;
        pt.htfHigh = lastHtfHigh;
        pt.htfLow = lastHtfLow;
        pt.htfClose = lastHtfCloseVal;
        pt.htfAtr = lastHtfAtr;
        pt.htfTrailingSl = liveHtfTrailingSl;
        pt.htfTrend = liveHtfTrend;
        pt.isHtfBullishFlip = (liveHtfTrend == 1 && prevHtfTrend == -1);
        pt.isHtfBearishFlip = (liveHtfTrend == -1 && prevHtfTrend == 1);

        if (isNewCandle) {
            history.add(pt);
            if (history.size() > 2000) {
                history.remove(0);
            }
        } else if (!history.isEmpty()) {
            history.set(history.size() - 1, pt);
        }
    }

    private void updateHtfLive(Candle candle, boolean isNewCandle) {
        if (candle == null || candle.getTimestamp() == null) return;

        int htfMins = timeframeToMinutes(resolvedHtf);
        if (isNewCandle && !htfAccumulator.isEmpty()) {
            LocalDateTime firstTs = htfAccumulator.get(0).getTimestamp();
            LocalDateTime currentTs = candle.getTimestamp();
            long minutesBetween = ChronoUnit.MINUTES.between(firstTs, currentTs);

            if (minutesBetween >= htfMins) {
                // Completed HTF bar! Aggregate all candles accumulated in the completed bar
                double o = htfAccumulator.get(0).getOpen();
                double h = htfAccumulator.stream().mapToDouble(Candle::getHigh).max().orElse(o);
                double l = htfAccumulator.stream().mapToDouble(Candle::getLow).min().orElse(o);
                double c = htfAccumulator.get(htfAccumulator.size() - 1).getClose();
                double v = htfAccumulator.stream().mapToDouble(Candle::getVolume).sum();

                htfAccumulator.clear();

                // Process HTF Heikin Ashi & Supertrend on the closed bar
                double htfHaClose = (o + h + l + c) / 4.0;
                double htfHaOpen = Double.isNaN(lastHtfHaOpen) ? (o + c) / 2.0 : (lastHtfHaOpen + lastHtfHaClose) / 2.0;
                double htfHaHigh = Math.max(h, Math.max(htfHaOpen, htfHaClose));
                double htfHaLow = Math.min(l, Math.min(htfHaOpen, htfHaClose));

                double htfTr = Double.isNaN(lastHtfHaClose) ? (htfHaHigh - htfHaLow)
                        : Math.max(htfHaHigh - htfHaLow, Math.max(Math.abs(htfHaHigh - lastHtfHaClose), Math.abs(htfHaLow - lastHtfHaClose)));

                double htfAtr = (lastHtfAtr <= 0) ? htfTr : (lastHtfAtr * (atrPeriod - 1) + htfTr) / atrPeriod;

                double htfUp = Math.abs(htfHaHigh + htfHaLow) / 2.0 - (multiplier * htfAtr);
                double htfDn = Math.abs(htfHaHigh + htfHaLow) / 2.0 + (multiplier * htfAtr);

                double htfTUp = htfUp;
                double htfTDown = htfDn;
                int htfTrend = lastHtfTrend;

                if (!Double.isNaN(lastHtfTUp) && !Double.isNaN(lastHtfHaClose)) {
                    htfTUp = (lastHtfHaClose > lastHtfTUp) ? Math.max(htfUp, lastHtfTUp) : htfUp;
                    htfTDown = (lastHtfHaClose < lastHtfTDown) ? Math.min(htfDn, lastHtfTDown) : htfDn;

                    if (htfHaClose > lastHtfTDown) {
                        htfTrend = 1;
                    } else if (htfHaClose < lastHtfTUp) {
                        htfTrend = -1;
                    } else {
                        htfTrend = (lastHtfTrend != 0) ? lastHtfTrend : 1;
                    }
                }

                lastHtfHaOpen = htfHaOpen;
                lastHtfHaClose = htfHaClose;
                lastHtfAtr = htfAtr;
                lastHtfHigh = htfHaHigh;
                lastHtfLow = htfHaLow;
                lastHtfCloseVal = htfHaClose;
                lastHtfTUp = htfTUp;
                lastHtfTDown = htfTDown;
                prevHtfTrend = lastHtfTrend;
                lastHtfTrend = htfTrend;

                liveHtfTrailingSl = (htfTrend == 1) ? htfTUp : htfTDown;
                liveHtfTrend = htfTrend;
            }
        }

        // Accumulate candle into current forming HTF bar
        if (isNewCandle) {
            htfAccumulator.add(candle);
        } else if (!htfAccumulator.isEmpty()) {
            htfAccumulator.set(htfAccumulator.size() - 1, candle);
        } else {
            htfAccumulator.add(candle);
        }

        // TradingView standard: liveHtfTrailingSl and liveHtfTrend remain frozen
        // to the last completed HTF bar. We do NOT recalculate intra-bar, ensuring
        // clean horizontal steps (staircase) across all intermediate candles.
    }

    private void publishSnapshot() {
        historySnapshot = new ArrayList<>(history);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mathematical Utilities
    // ─────────────────────────────────────────────────────────────────────────

    public static List<HaBar> computeHeikinAshi(List<Candle> candles) {
        int n = candles.size();
        List<HaBar> haList = new ArrayList<>(n);
        double prevHaOpen = Double.NaN;
        double prevHaClose = Double.NaN;

        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            double haClose = (c.getOpen() + c.getHigh() + c.getLow() + c.getClose()) / 4.0;
            double haOpen;
            if (i == 0 || Double.isNaN(prevHaOpen)) {
                haOpen = (c.getOpen() + c.getClose()) / 2.0;
            } else {
                haOpen = (prevHaOpen + prevHaClose) / 2.0;
            }
            double haHigh = Math.max(c.getHigh(), Math.max(haOpen, haClose));
            double haLow = Math.min(c.getLow(), Math.min(haOpen, haClose));

            haList.add(new HaBar(haOpen, haHigh, haLow, haClose, c.getTimestamp()));
            prevHaOpen = haOpen;
            prevHaClose = haClose;
        }
        return haList;
    }

    /**
     * Computes Wilder's RMA ATR on Heikin Ashi bars, matching TradingView's Pine Script ta.atr(Period).
     */
    public static List<Double> computeWilderAtr(List<HaBar> haBars, int period) {
        int n = haBars.size();
        List<Double> atrList = new ArrayList<>(n);
        if (n == 0) return atrList;

        double[] tr = new double[n];
        for (int i = 0; i < n; i++) {
            HaBar curr = haBars.get(i);
            if (i == 0) {
                tr[i] = curr.high - curr.low;
            } else {
                HaBar prev = haBars.get(i - 1);
                double d1 = curr.high - curr.low;
                double d2 = Math.abs(curr.high - prev.close);
                double d3 = Math.abs(curr.low - prev.close);
                tr[i] = Math.max(d1, Math.max(d2, d3));
            }
        }

        // RMA smoothing: initial SMA, then RMA
        double rma = 0.0;
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            if (i < period) {
                sum += tr[i];
                rma = sum / (i + 1);
            } else {
                rma = (rma * (period - 1) + tr[i]) / period;
            }
            atrList.add(rma);
        }
        return atrList;
    }

    /**
     * Aggregates lower-timeframe candles into higher-timeframe candles.
     */
    public static List<Candle> aggregateCandles(List<Candle> baseCandles, String targetTf) {
        if (baseCandles == null || baseCandles.isEmpty()) {
            return Collections.emptyList();
        }

        int targetMins = timeframeToMinutes(targetTf);
        if (targetMins <= 1) {
            return new ArrayList<>(baseCandles);
        }

        Map<LocalDateTime, List<Candle>> buckets = new LinkedHashMap<>();
        for (Candle c : baseCandles) {
            LocalDateTime ts = c.getTimestamp();
            int minute = ts.getMinute();
            int bucketMinute = (minute / targetMins) * targetMins;
            LocalDateTime bucketKey = ts.withMinute(bucketMinute).withSecond(0).withNano(0);
            buckets.computeIfAbsent(bucketKey, k -> new ArrayList<>()).add(c);
        }

        List<Candle> aggregated = new ArrayList<>(buckets.size());
        for (Map.Entry<LocalDateTime, List<Candle>> entry : buckets.entrySet()) {
            List<Candle> group = entry.getValue();
            if (group.isEmpty()) continue;

            double open = group.get(0).getOpen();
            double close = group.get(group.size() - 1).getClose();
            double high = group.stream().mapToDouble(Candle::getHigh).max().orElse(open);
            double low = group.stream().mapToDouble(Candle::getLow).min().orElse(open);
            double volume = group.stream().mapToDouble(Candle::getVolume).sum();

            aggregated.add(new Candle(open, high, low, close, volume, entry.getKey()));
        }

        return aggregated;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Getters
    // ─────────────────────────────────────────────────────────────────────────

    public String getCurrentTf() {
        return currentTf;
    }

    public String getResolvedHtf() {
        return resolvedHtf;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public int getAtrPeriod() {
        return atrPeriod;
    }

    public double getLiveTrailingSl() {
        return liveTrailingSl;
    }

    public int getLiveTrend() {
        return liveTrend;
    }

    public double getLiveHtfTrailingSl() {
        return liveHtfTrailingSl;
    }

    public int getLiveHtfTrend() {
        return liveHtfTrend;
    }

    /**
     * Seeds the indicator instance with pre-computed points from calculate().
     * Adopts the latest point's current-TF and HTF state and populates the history buffer
     * with the latest 2000 points without re-executing updates and snapshots.
     */
    public synchronized void adoptState(SupertrendPoint lastPt, List<SupertrendPoint> batchPoints) {
        if (lastPt == null) return;

        this.lastHaOpen = lastPt.haOpen;
        this.lastHaClose = lastPt.haClose;
        this.lastAtr = lastPt.haAtr;
        this.lastTUp = lastPt.tUp;
        this.lastTDown = lastPt.tDown;
        this.lastTrend = lastPt.trend;
        this.prevTrend = lastPt.trend;

        this.liveHaOpen = lastPt.haOpen;
        this.liveHaHigh = lastPt.haHigh;
        this.liveHaLow = lastPt.haLow;
        this.liveHaClose = lastPt.haClose;
        this.liveAtr = lastPt.haAtr;
        this.liveTUp = lastPt.tUp;
        this.liveTDown = lastPt.tDown;
        this.liveTrend = lastPt.trend;
        this.liveTrailingSl = lastPt.trailingSl;

        adoptHtfState(lastPt);

        if (batchPoints != null && !batchPoints.isEmpty()) {
            this.history.clear();
            int start = Math.max(0, batchPoints.size() - 2000);
            this.history.addAll(batchPoints.subList(start, batchPoints.size()));
            publishSnapshot();
        }
    }

    /**
     * Adopt the HTF streaming state from a previously computed SupertrendPoint.
     * Called by SymbolState.seedHistory() after the batch calculate() warm-up so the
     * streaming instance starts with a valid HTF baseline instead of cold defaults.
     * Without this, liveHtfTrailingSl stays 0 until the first full HTF bar closes
     * after startup — causing the white HTF line to disappear at live start.
     */
    public synchronized void adoptHtfState(SupertrendPoint lastPt) {
        if (lastPt == null) return;
        lastHtfHaOpen  = lastPt.htfHigh; // best available proxy; actual haOpen not exported
        lastHtfHaClose = lastPt.htfClose;
        lastHtfAtr     = lastPt.htfAtr;
        lastHtfHigh    = lastPt.htfHigh;
        lastHtfLow     = lastPt.htfLow;
        lastHtfCloseVal= lastPt.htfClose;
        lastHtfTUp     = lastPt.htfTUp;
        lastHtfTDown   = lastPt.htfTDown;
        lastHtfTrend   = lastPt.htfTrend;
        prevHtfTrend   = lastPt.htfTrend;
        liveHtfTrailingSl = lastPt.htfTrailingSl;
        liveHtfTrend      = lastPt.htfTrend;
    }

    public List<SupertrendPoint> getHistorySnapshot() {
        return historySnapshot;
    }
}
