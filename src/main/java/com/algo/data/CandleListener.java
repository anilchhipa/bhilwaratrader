package com.algo.data;

import com.algo.core.Candle;

public interface CandleListener {
    /**
     * Called when an active candle is updated (price change within same interval)
     */
    void onCandleUpdated(String symbol, String interval, Candle candle);

    /**
     * Called when a candle is closed and finalized
     */
    void onCandleClosed(String symbol, String interval, Candle candle);
}
