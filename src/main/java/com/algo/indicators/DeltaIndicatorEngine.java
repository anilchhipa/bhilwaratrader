package com.algo.indicators;

/**
 * High-performance engine for calculating technical indicators incrementally
 * (delta-based).
 */
public class DeltaIndicatorEngine {

    /**
     * Updates an Absolute EMA using the previous EMA value.
     * Formula: EMA = (Price - PrevEMA) * k + PrevEMA
     */
    public static double updateEMA(double prevEMA, double currentPrice, int period) {
        if (prevEMA == 0)
            return currentPrice; // Initialize with current price
        double k = 2.0 / (period + 1);
        return (currentPrice - prevEMA) * k + prevEMA;
    }

    /**
     * Updates RSI state.
     * We need to track the average gain and average loss.
     */
    public static RSIState updateRSI(RSIState state, double currentPrice, double prevPrice, int period) {
        double change = currentPrice - prevPrice;
        double gain = Math.max(0, change);
        double loss = Math.max(0, -change);

        if (state == null) {
            return new RSIState(gain, loss, currentPrice);
        }

        double alpha = 1.0 / period;
        double avgGain = (gain - state.avgGain) * alpha + state.avgGain;
        double avgLoss = (loss - state.avgLoss) * alpha + state.avgLoss;

        double rs = (avgLoss == 0) ? 100 : avgGain / avgLoss;
        double rsi = 100 - (100 / (1 + rs));

        return new RSIState(avgGain, avgLoss, rsi, currentPrice);
    }

    public static class RSIState {
        public double avgGain;
        public double avgLoss;
        public double value;
        public double lastPrice;

        public RSIState(double avgGain, double avgLoss, double lastPrice) {
            this.avgGain = avgGain;
            this.avgLoss = avgLoss;
            this.lastPrice = lastPrice;
        }

        public RSIState(double avgGain, double avgLoss, double value, double lastPrice) {
            this.avgGain = avgGain;
            this.avgLoss = avgLoss;
            this.value = value;
            this.lastPrice = lastPrice;
        }
    }

    /**
     * Updates MACD state.
     */
    public static MACDState updateMACD(MACDState state, double price) {
        if (state == null) {
            double ema12 = price;
            double ema26 = price;
            return new MACDState(ema12, ema26, 0, 0);
        }

        double ema12 = updateEMA(state.ema12, price, 12);
        double ema26 = updateEMA(state.ema26, price, 26);
        double macdLine = ema12 - ema26;
        double signalLine = updateEMA(state.signalLine, macdLine, 9);
        double histogram = macdLine - signalLine;

        return new MACDState(ema12, ema26, macdLine, signalLine, histogram);
    }

    public static class MACDState {
        public double ema12;
        public double ema26;
        public double macdLine;
        public double signalLine;
        public double histogram;

        public MACDState(double ema12, double ema26, double macdLine, double signalLine) {
            this.ema12 = ema12;
            this.ema26 = ema26;
            this.macdLine = macdLine;
            this.signalLine = signalLine;
        }

        public MACDState(double ema12, double ema26, double macdLine, double signalLine, double histogram) {
            this.ema12 = ema12;
            this.ema26 = ema26;
            this.macdLine = macdLine;
            this.signalLine = signalLine;
            this.histogram = histogram;
        }
    }

    /**
     * Updates Supertrend state.
     */
    public static SupertrendState updateSupertrend(SupertrendState state, double high, double low, double close,
            double prevClose, int period, double multiplier) {
        double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));

        if (state == null) {
            double atr = tr;
            double mid = (high + low) / 2.0;
            return new SupertrendState(atr, mid + (multiplier * atr), mid - (multiplier * atr), 1, close);
        }

        double atr = updateEMA(state.atr, tr, period);
        double src = (high + low) / 2.0;

        double basicUpper = src + (multiplier * atr);
        double basicLower = src - (multiplier * atr);

        double finalUpper = (basicUpper < state.finalUpper || prevClose > state.finalUpper) ? basicUpper
                : state.finalUpper;
        double finalLower = (basicLower > state.finalLower || prevClose < state.finalLower) ? basicLower
                : state.finalLower;

        int trend = state.trend;
        if (state.trend == 1 && close > finalUpper)
            trend = -1;
        else if (state.trend == -1 && close < finalLower)
            trend = 1;

        double value = (trend == 1) ? finalLower : finalUpper;

        return new SupertrendState(atr, finalUpper, finalLower, trend, value);
    }

    public static class SupertrendState {
        public double atr;
        public double finalUpper;
        public double finalLower;
        public int trend; // 1 for UP/Lowerband, -1 for DOWN/Upperband
        public double value;

        public SupertrendState(double atr, double finalUpper, double finalLower, int trend, double value) {
            this.atr = atr;
            this.finalUpper = finalUpper;
            this.finalLower = finalLower;
            this.trend = trend;
            this.value = value;
        }
    }
}
