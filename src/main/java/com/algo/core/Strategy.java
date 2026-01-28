package com.algo.core;

import java.util.ArrayList;
import java.util.List;

public class Strategy {
    private double ema15 = 0;
    private double ema15_5 = 0;
    private double ema15_15 = 0;
    private double ema15_15_15 = 0;
    private double ema15_15_15_30 = 0;

    private List<Double> ema15History = new ArrayList<>();
    private List<Double> ema15_5History = new ArrayList<>();
    private List<Double> ema15_15History = new ArrayList<>();
    private List<Double> ema15_15_15History = new ArrayList<>();
    private List<Double> ema15_15_15_30History = new ArrayList<>();

    // MACD variables
    private double ema50 = 0;
    private double ema100 = 0;
    private double macdLine = 0;
    private double signalLine = 0;
    private double maed_5 = 0;
    private double macd_5_5 = 0;

    private List<Double> macdHistory = new ArrayList<>();
    private List<Double> signalHistory = new ArrayList<>();
    private List<Double> maed5History = new ArrayList<>();
    private List<Double> macd55History = new ArrayList<>();

    private com.algo.indicators.SuperTrendedMA stma;

    public Strategy(int unused1, int unused2, double unused3) {
        // Default parameters from Pine Script (EMA only)
        this.stma = new com.algo.indicators.SuperTrendedMA(100, 10, 0.5, true);
    }

    public void onNewBrick(RenkoBrick brick) {
        updateIndicators(brick.getLow());

        // Update SuperTrended MA
        Candle c = new Candle(brick.getOpen(), brick.getHigh(), brick.getLow(), brick.getClose(), brick.getTimestamp());
        stma.update(c);
    }

    public void onNewCandle(Candle candle) {
        updateIndicators(candle.getLow());

        // Update SuperTrended MA
        stma.update(candle);
    }

    private void updateIndicators(double low) {
        // 1. EMA_15 = EMA(15, low)
        ema15 = calculateEMA(low, ema15, 15);

        // 2. EMA15_5 = EMA(5, EMA_15)
        ema15_5 = calculateEMA(ema15, ema15_5, 5);

        // 3. EMA15_15 = EMA(15, EMA_15)
        ema15_15 = calculateEMA(ema15, ema15_15, 15);

        // 4. EMA15_15_15 = EMA(15, EMA15_15)
        ema15_15_15 = calculateEMA(ema15_15, ema15_15_15, 15);

        // 5. EMA15_15_15_30 = EMA(30, EMA15_15_15)
        ema15_15_15_30 = calculateEMA(ema15_15_15, ema15_15_15_30, 15);

        // MACD Calculations
        ema50 = calculateEMA(low, ema50, 50);
        ema100 = calculateEMA(low, ema100, 100);
        macdLine = ema50 - ema100;
        signalLine = calculateEMA(macdLine, signalLine, 5); // Changed from 9 to 5
        maed_5 = calculateEMA(signalLine, maed_5, 5);
        macd_5_5 = calculateEMA(maed_5, macd_5_5, 5);

        // Add to history and maintain size
        addToHistory(ema15History, ema15);
        addToHistory(ema15_5History, ema15_5);
        addToHistory(ema15_15History, ema15_15);
        addToHistory(ema15_15_15History, ema15_15_15);
        addToHistory(ema15_15_15_30History, ema15_15_15_30);

        addToHistory(macdHistory, macdLine);
        addToHistory(signalHistory, signalLine);
        addToHistory(maed5History, maed_5);
        addToHistory(macd55History, macd_5_5);
    }

    private double calculateEMA(double value, double currentEMA, int period) {
        if (currentEMA == 0) {
            return value;
        } else {
            double k = 2.0 / (period + 1);
            return value * k + currentEMA * (1 - k);
        }
    }

    private void addToHistory(List<Double> history, double value) {
        history.add(value);
        if (history.size() > 1000) {
            history.remove(0);
        }
    }

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

    public double getEma15_15_15_30() {
        return ema15_15_15_30;
    }

    public List<Double> getEma15History() {
        return ema15History;
    }

    public List<Double> getEma15_5History() {
        return ema15_5History;
    }

    public List<Double> getEma15_15History() {
        return ema15_15History;
    }

    public List<Double> getEma15_15_15History() {
        return ema15_15_15History;
    }

    public List<Double> getEma15_15_15_30History() {
        return ema15_15_15_30History;
    }

    public List<Double> getMacdHistory() {
        return macdHistory;
    }

    public List<Double> getSignalHistory() {
        return signalHistory;
    }

    public List<Double> getMaed5History() {
        return maed5History;
    }

    public List<Double> getMacd55History() {
        return macd55History;
    }

    public com.algo.indicators.SuperTrendedMA getStma() {
        return stma;
    }
}
