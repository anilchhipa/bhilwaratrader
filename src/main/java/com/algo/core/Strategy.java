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

    // Snapshot variables for stable baselines (state after the last closed candle)
    private double ema15_stable = 0;
    private double ema15_5_stable = 0;
    private double ema15_15_stable = 0;
    private double ema15_15_15_stable = 0;
    private double ema15_15_15_30_stable = 0;
    private double ema50_stable = 0;
    private double ema100_stable = 0;
    private double macdLine_stable = 0;
    private double signalLine_stable = 0;
    private double maed_5_stable = 0;
    private double macd_5_5_stable = 0;

    private com.algo.indicators.SuperTrendedMA stma;
    private int stableHistorySize = 0;

    public Strategy(int unused1, int unused2, double unused3) {
        this.stma = new com.algo.indicators.SuperTrendedMA(100, 10, 0.5, true);
    }

    public void onNewCandle(Candle candle) {
        updateIndicators(candle.getLow(), false);
        stma.update(candle);
        updateSnapshots();
        stableHistorySize = ema15History.size();
        populateCandleIndicators(candle);
    }

    public void updateLastCandle(Candle candle) {
        boolean isFirstTick = (ema15History.size() == stableHistorySize);
        updateIndicators(candle.getLow(), !isFirstTick);
        stma.updateLive(candle);
        populateCandleIndicators(candle);
    }

    public void populateCandleIndicators(Candle candle) {
        candle.setEma15(ema15);
        candle.setEma15_5(ema15_5);
        candle.setEma15_15(ema15_15);
        candle.setEma15_15_15(ema15_15_15);
        candle.setEma15_30(ema15_15_15_30);

        candle.setEma50(ema50);
        candle.setEma100(ema100);
        candle.setMacdLine(macdLine);
        candle.setSignalLine(signalLine);
        candle.setMacd5(maed_5);
        candle.setMacd55(macd_5_5);

        if (stma != null) {
            java.util.List<Double> maHistory = stma.getMaHistory();
            java.util.List<Double> upHistory = stma.getUpHistory();
            java.util.List<Double> dnHistory = stma.getDnHistory();
            java.util.List<Integer> trendHistory = stma.getTrendHistory();

            if (!maHistory.isEmpty())
                candle.setStMa(maHistory.get(maHistory.size() - 1));
            if (!upHistory.isEmpty())
                candle.setStUp(upHistory.get(upHistory.size() - 1));
            if (!dnHistory.isEmpty())
                candle.setStDn(dnHistory.get(dnHistory.size() - 1));
            if (!trendHistory.isEmpty())
                candle.setStTrend(trendHistory.get(trendHistory.size() - 1));
        }
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

    private void updateIndicators(double price) {
        updateIndicators(price, false);
    }

    private void updateIndicators(double price, boolean updateLast) {
        if (updateLast) {
            // Revert to stable values before calculating the update for this tick
            ema15 = calculateEMA(price, ema15_stable, 15);
            ema15_5 = calculateEMA(ema15, ema15_5_stable, 5);
            ema15_15 = calculateEMA(ema15, ema15_15_stable, 15);
            ema15_15_15 = calculateEMA(ema15_15, ema15_15_15_stable, 15);
            ema15_15_15_30 = calculateEMA(ema15_15_15, ema15_15_15_30_stable, 30);

            ema50 = calculateEMA(price, ema50_stable, 50);
            ema100 = calculateEMA(price, ema100_stable, 100);
            macdLine = ema50 - ema100;
            signalLine = calculateEMA(macdLine, signalLine_stable, 5);

            maed_5 = calculateEMA(signalLine, maed_5_stable, 5);
            macd_5_5 = calculateEMA(maed_5, macd_5_5_stable, 5);

            updateLastInHistory(ema15History, ema15);
            updateLastInHistory(ema15_5History, ema15_5);
            updateLastInHistory(ema15_15History, ema15_15);
            updateLastInHistory(ema15_15_15History, ema15_15_15);
            updateLastInHistory(ema15_15_15_30History, ema15_15_15_30);
            updateLastInHistory(macdHistory, macdLine);
            updateLastInHistory(signalHistory, signalLine);
            updateLastInHistory(maed5History, maed_5);
            updateLastInHistory(macd55History, macd_5_5);
        } else {
            ema15 = calculateEMA(price, ema15, 15);
            ema15_5 = calculateEMA(ema15, ema15_5, 5);
            ema15_15 = calculateEMA(ema15, ema15_15, 15);
            ema15_15_15 = calculateEMA(ema15_15, ema15_15_15, 15);
            ema15_15_15_30 = calculateEMA(ema15_15_15, ema15_15_15_30, 30);

            ema50 = calculateEMA(price, ema50, 50);
            ema100 = calculateEMA(price, ema100, 100);
            macdLine = ema50 - ema100;
            signalLine = calculateEMA(macdLine, signalLine, 5);

            maed_5 = calculateEMA(signalLine, maed_5, 5);
            macd_5_5 = calculateEMA(maed_5, macd_5_5, 5);

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
    }

    private void updateSnapshots() {
        ema15_stable = ema15;
        ema15_5_stable = ema15_5;
        ema15_15_stable = ema15_15;
        ema15_15_15_stable = ema15_15_15;
        ema15_15_15_30_stable = ema15_15_15_30;
        ema50_stable = ema50;
        ema100_stable = ema100;
        macdLine_stable = macdLine;
        signalLine_stable = signalLine;
        maed_5_stable = maed_5;
        macd_5_5_stable = macd_5_5;
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
