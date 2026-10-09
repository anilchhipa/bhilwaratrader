package com.algo.indicators;

import com.algo.core.Candle;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SupertrendMtfHeikinAshiTest {

    @Test
    public void testAutoHtfResolution() {
        assertEquals("5min", SupertrendMtfHeikinAshi.getAutoHtf("1"));
        assertEquals("5min", SupertrendMtfHeikinAshi.getAutoHtf("1min"));
        assertEquals("15min", SupertrendMtfHeikinAshi.getAutoHtf("3min"));
        assertEquals("15min", SupertrendMtfHeikinAshi.getAutoHtf("5min"));
        assertEquals("60min", SupertrendMtfHeikinAshi.getAutoHtf("15min"));
        assertEquals("120min", SupertrendMtfHeikinAshi.getAutoHtf("30min"));
        assertEquals("120min", SupertrendMtfHeikinAshi.getAutoHtf("45min"));
        assertEquals("240min", SupertrendMtfHeikinAshi.getAutoHtf("60min"));
        assertEquals("240min", SupertrendMtfHeikinAshi.getAutoHtf("120min"));
        assertEquals("240min", SupertrendMtfHeikinAshi.getAutoHtf("180min"));
        assertEquals("D", SupertrendMtfHeikinAshi.getAutoHtf("240min"));
        assertEquals("W", SupertrendMtfHeikinAshi.getAutoHtf("D"));
        assertEquals("5W", SupertrendMtfHeikinAshi.getAutoHtf("W"));
    }

    @Test
    public void testHeikinAshiCalculation() {
        List<Candle> candles = new ArrayList<>();
        LocalDateTime baseTime = LocalDateTime.of(2026, 9, 29, 9, 15);

        // Candle 1: Open 100, High 110, Low 95, Close 105
        candles.add(new Candle(100, 110, 95, 105, 1000, baseTime));
        // Candle 2: Open 105, High 115, Low 102, Close 112
        candles.add(new Candle(105, 115, 102, 112, 1200, baseTime.plusMinutes(1)));

        List<SupertrendMtfHeikinAshi.HaBar> haList = SupertrendMtfHeikinAshi.computeHeikinAshi(candles);
        assertEquals(2, haList.size());

        // Bar 0:
        // haClose = (100 + 110 + 95 + 105) / 4.0 = 410 / 4 = 102.5
        // haOpen = (100 + 105) / 2.0 = 102.5
        // haHigh = max(110, 102.5, 102.5) = 110
        // haLow = min(95, 102.5, 102.5) = 95
        assertEquals(102.5, haList.get(0).close, 1e-6);
        assertEquals(102.5, haList.get(0).open, 1e-6);
        assertEquals(110.0, haList.get(0).high, 1e-6);
        assertEquals(95.0, haList.get(0).low, 1e-6);

        // Bar 1:
        // haClose = (105 + 115 + 102 + 112) / 4.0 = 434 / 4 = 108.5
        // haOpen = (haOpen_prev + haClose_prev) / 2.0 = (102.5 + 102.5) / 2 = 102.5
        // haHigh = max(115, 102.5, 108.5) = 115
        // haLow = min(102, 102.5, 108.5) = 102
        assertEquals(108.5, haList.get(1).close, 1e-6);
        assertEquals(102.5, haList.get(1).open, 1e-6);
        assertEquals(115.0, haList.get(1).high, 1e-6);
        assertEquals(102.0, haList.get(1).low, 1e-6);
    }

    @Test
    public void testSupertrendUptrendAndDowntrendFlips() {
        List<Candle> candles = new ArrayList<>();
        LocalDateTime time = LocalDateTime.of(2026, 9, 29, 9, 15);

        // Create a strong rising trend (should result in trend = 1)
        double price = 1000.0;
        for (int i = 0; i < 20; i++) {
            price += 10.0;
            candles.add(new Candle(price - 2, price + 5, price - 4, price + 3, 500, time.plusMinutes(i)));
        }

        // Sudden crash to flip to downtrend (trend = -1)
        for (int i = 20; i < 35; i++) {
            price -= 25.0;
            candles.add(new Candle(price + 2, price + 3, price - 6, price - 4, 1500, time.plusMinutes(i)));
        }

        List<SupertrendMtfHeikinAshi.SupertrendPoint> points =
                SupertrendMtfHeikinAshi.calculate(candles, "1min", "Auto", "5", 2.0, 7);

        assertEquals(candles.size(), points.size());

        // In the beginning after warmup, trend should be Bullish (+1)
        assertTrue(points.get(10).trend == 1, "Should be in uptrend around bar 10");

        // Towards the end after the crash, trend should flip to Bearish (-1)
        SupertrendMtfHeikinAshi.SupertrendPoint lastPoint = points.get(points.size() - 1);
        assertEquals(-1, lastPoint.trend, "Should flip to downtrend after strong crash");

        // There should be at least one bearish flip detected
        boolean foundBearishFlip = points.stream().anyMatch(p -> p.isBearishFlip);
        assertTrue(foundBearishFlip, "Should have detected a bearish flip event");
    }

    @Test
    public void testStreamingUpdates() {
        SupertrendMtfHeikinAshi indicator = new SupertrendMtfHeikinAshi("1min", "Auto", "5", 2.0, 7);
        LocalDateTime time = LocalDateTime.of(2026, 9, 29, 9, 15);

        double price = 500.0;
        for (int i = 0; i < 15; i++) {
            price += 5.0;
            Candle c = new Candle(price - 1, price + 3, price - 2, price + 1, 200, time.plusMinutes(i));
            indicator.update(c);
            indicator.commit();
        }

        // Live intra-candle tick update
        Candle liveTick = new Candle(price, price + 10, price - 1, price + 8, 250, time.plusMinutes(15));
        indicator.updateLive(liveTick);

        assertTrue(indicator.getLiveTrend() != 0);
        assertTrue(indicator.getLiveTrailingSl() > 0);
        assertNotNull(indicator.getHistorySnapshot());
        assertFalse(indicator.getHistorySnapshot().isEmpty());
    }

    @Test
    public void testAdoptState() {
        List<Candle> candles = new ArrayList<>();
        LocalDateTime time = LocalDateTime.of(2026, 9, 29, 9, 15);
        double price = 500.0;
        for (int i = 0; i < 20; i++) {
            price += 5.0;
            candles.add(new Candle(price - 1, price + 3, price - 2, price + 1, 200, time.plusMinutes(i)));
        }

        List<SupertrendMtfHeikinAshi.SupertrendPoint> points =
                SupertrendMtfHeikinAshi.calculate(candles, "1min", "Auto", "5", 2.0, 7);

        SupertrendMtfHeikinAshi indicator = new SupertrendMtfHeikinAshi("1min", "Auto", "5", 2.0, 7);
        indicator.adoptState(points.get(points.size() - 1), points);

        assertEquals(points.size(), indicator.getHistorySnapshot().size());
        assertEquals(points.get(points.size() - 1).trailingSl, indicator.getLiveTrailingSl(), 1e-6);
        assertEquals(points.get(points.size() - 1).trend, indicator.getLiveTrend());

        // Now feed a live tick
        Candle liveTick = new Candle(price + 2, price + 5, price, price + 4, 100, time.plusMinutes(20));
        indicator.updateLive(liveTick);
        assertTrue(indicator.getLiveTrailingSl() > 0);
    }
}
