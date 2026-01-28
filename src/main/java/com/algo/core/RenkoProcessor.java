package com.algo.core;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class RenkoProcessor {
    private double brickSize;
    private double lastBrickClose;
    private List<RenkoBrick> bricks = new ArrayList<>();
    private Consumer<RenkoBrick> onBrickFormed;

    public RenkoProcessor(double brickSize, Consumer<RenkoBrick> onBrickFormed) {
        this.brickSize = brickSize;
        this.onBrickFormed = onBrickFormed;
    }

    public void init(double startPrice) {
        this.lastBrickClose = startPrice;
        // Align start price to brick size grid if needed, or just start from open
        this.lastBrickClose = Math.floor(startPrice / brickSize) * brickSize;
    }

    public void onTick(double currentPrice, LocalDateTime timestamp) {
        if (lastBrickClose == 0) {
            init(currentPrice);
            return;
        }

        double diff = currentPrice - lastBrickClose;
        int numBricks = (int) (Math.abs(diff) / brickSize);

        if (numBricks > 0) {
            for (int i = 0; i < numBricks; i++) {
                double open, close;
                boolean isGreen;

                if (diff > 0) {
                    // UP Move
                    open = lastBrickClose;
                    close = lastBrickClose + brickSize;
                    isGreen = true;
                    lastBrickClose += brickSize;
                } else {
                    // DOWN Move
                    open = lastBrickClose;
                    close = lastBrickClose - brickSize;
                    isGreen = false;
                    lastBrickClose -= brickSize;
                }

                RenkoBrick newBrick = new RenkoBrick(open, close, isGreen, timestamp);
                bricks.add(newBrick);
                if (onBrickFormed != null) {
                    onBrickFormed.accept(newBrick);
                }
            }
        }
    }

    public List<RenkoBrick> getBricks() {
        return bricks;
    }

    public void processHistory(List<Candle> history) {
        for (Candle candle : history) {
            // For Renko, we typically use Close price of the candle
            // Or we could simulate High/Low movement if we wanted more precision
            onTick(candle.getClose(), candle.getTimestamp());
        }
    }
}
