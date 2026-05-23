package com.algo.core;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

public class RenkoBrick {
    private final long startTime; // Epoch millis
    private final long endTime; // Epoch millis (0 if active)
    private final double open;
    private final double close;

    public RenkoBrick(LocalDateTime start, LocalDateTime end, double open, double close) {
        this.startTime = start.atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
        this.endTime = end != null ? end.atZone(ZoneOffset.UTC).toInstant().toEpochMilli() : 0;
        this.open = open;
        this.close = close;
    }

    public long getStartTime() {
        return startTime;
    }

    public long getEndTime() {
        return endTime;
    }

    public double getOpen() {
        return open;
    }

    public double getClose() {
        return close;
    }
}
