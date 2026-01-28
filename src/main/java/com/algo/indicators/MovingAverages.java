package com.algo.indicators;

import java.util.List;

public class MovingAverages {

    public static double sma(List<Double> data, int length) {
        if (data.size() < length)
            return 0;
        double sum = 0;
        for (int i = data.size() - length; i < data.size(); i++) {
            sum += data.get(i);
        }
        return sum / length;
    }

    public static double ema(List<Double> data, double prevEma, int length) {
        if (data.isEmpty())
            return 0;
        double current = data.get(data.size() - 1);
        if (prevEma == 0)
            return current;
        double k = 2.0 / (length + 1);
        return current * k + prevEma * (1 - k);
    }
}
