/**
 * Deterministic Non-Repainting Traditional Renko Guppy (GMMA) Engine
 *
 * Implements pure Traditional Renko with fixed box sizes:
 * - Renko 1: Fixed Box Size 15 (Feeds the 10 Guppy EMAs)
 * - Renko 2: Fixed Box Size 50 (White step line)
 * - Renko 3: Fixed Box Size 100 (Blue step line)
 *
 * Strictly Non-Repainting & Deterministic:
 * - Bricks form ONLY when closed candle prices breach the box boundary.
 * - Reversals strictly require 2 full boxes.
 * - 10 Guppy EMAs (Fast: 3, 5, 8, 10, 12; Slow: 30, 35, 40, 45, 50) are calculated
 *   sequentially over confirmed bricks and step-projected onto the candle timeline.
 * - Historical values are locked and never change when new live candles/ticks arrive.
 */

// ── Pure Deterministic Traditional Renko Brick Builder ────────────────────────
export function buildDeterministicRenkoBricks(candles, boxSize) {
    if (!candles || candles.length === 0) return [];
    const bSize = Math.max(0.1, Number(boxSize) || 15);

    // Initial reference anchor
    const firstClose = candles[0].close;
    let currentLevel = Math.floor(firstClose / bSize) * bSize;
    let direction = 0; // 0 = initial, 1 = up, -1 = down

    const bricks = [
        {
            timestamp: candles[0].timestamp,
            close: currentLevel,
            open: currentLevel,
            direction: 0
        }
    ];

    for (let i = 1; i < candles.length; i++) {
        const c = candles[i];
        const p = c.close;
        const ts = c.timestamp;

        if (direction === 0) {
            if (p >= currentLevel + bSize) {
                direction = 1;
                const count = Math.floor((p - currentLevel) / bSize);
                for (let k = 0; k < count; k++) {
                    const prevLvl = currentLevel;
                    currentLevel += bSize;
                    bricks.push({ timestamp: ts, open: prevLvl, close: currentLevel, direction: 1 });
                }
            } else if (p <= currentLevel - bSize) {
                direction = -1;
                const count = Math.floor((currentLevel - p) / bSize);
                for (let k = 0; k < count; k++) {
                    const prevLvl = currentLevel;
                    currentLevel -= bSize;
                    bricks.push({ timestamp: ts, open: prevLvl, close: currentLevel, direction: -1 });
                }
            }
        } else if (direction === 1) {
            // Continuation UP: price crosses above next box boundary
            if (p >= currentLevel + bSize) {
                const count = Math.floor((p - currentLevel) / bSize);
                for (let k = 0; k < count; k++) {
                    const prevLvl = currentLevel;
                    currentLevel += bSize;
                    bricks.push({ timestamp: ts, open: prevLvl, close: currentLevel, direction: 1 });
                }
            }
            // Reversal DOWN: strictly requires 2 full boxes
            else if (p <= currentLevel - 2 * bSize) {
                direction = -1;
                const prevLvl = currentLevel - bSize; // top of reversal brick
                currentLevel -= 2 * bSize;
                bricks.push({ timestamp: ts, open: prevLvl, close: currentLevel, direction: -1 });

                // Any extra downward boxes in same bar
                const extra = Math.floor((currentLevel - p) / bSize);
                for (let k = 0; k < extra; k++) {
                    const stepPrev = currentLevel;
                    currentLevel -= bSize;
                    bricks.push({ timestamp: ts, open: stepPrev, close: currentLevel, direction: -1 });
                }
            }
        } else if (direction === -1) {
            // Continuation DOWN: price crosses below next box boundary
            if (p <= currentLevel - bSize) {
                const count = Math.floor((currentLevel - p) / bSize);
                for (let k = 0; k < count; k++) {
                    const prevLvl = currentLevel;
                    currentLevel -= bSize;
                    bricks.push({ timestamp: ts, open: prevLvl, close: currentLevel, direction: -1 });
                }
            }
            // Reversal UP: strictly requires 2 full boxes
            else if (p >= currentLevel + 2 * bSize) {
                direction = 1;
                const prevLvl = currentLevel + bSize; // bottom of reversal brick
                currentLevel += 2 * bSize;
                bricks.push({ timestamp: ts, open: prevLvl, close: currentLevel, direction: 1 });

                // Any extra upward boxes in same bar
                const extra = Math.floor((p - currentLevel) / bSize);
                for (let k = 0; k < extra; k++) {
                    const stepPrev = currentLevel;
                    currentLevel += bSize;
                    bricks.push({ timestamp: ts, open: stepPrev, close: currentLevel, direction: 1 });
                }
            }
        }
    }

    return bricks;
}

// ── Complete Traditional Renko Guppy Computation ─────────────────────────────
export function computeDeterministicRenkoGuppy(candles, options = {}) {
    if (!candles || candles.length === 0) return null;

    const {
        boxSize1 = 15,    // Renko 1 (Traditional 15 points)
        boxSize2 = 50,    // Renko 2 (Traditional 50 points)
        boxSize3 = 100,   // Renko 3 (Traditional 100 points)
        fastLengths = [3, 5, 8, 10, 12],
        slowLengths = [30, 35, 40, 45, 50],
        closeMaLength = 3
    } = options;

    const totalCandles = candles.length;

    // 1. Build strictly confirmed bricks for all 3 Traditional Renko streams
    const r1Bricks = buildDeterministicRenkoBricks(candles, boxSize1);
    const r2Bricks = buildDeterministicRenkoBricks(candles, boxSize2);
    const r3Bricks = buildDeterministicRenkoBricks(candles, boxSize3);

    // 2. Compute dynamic Renko 1 source across candles
    // When a brick is confirmed (boundary breached), renkoSrc snaps to that confirmed brick level.
    // Between confirmations (during active pullbacks or unconfirmed moves), renkoSrc tracks
    // the candle close rather than freezing at the stale previous brick. This completely eliminates
    // both flat horizontal staircases and false inverse decay while preserving silky-smooth ribbons.
    const renkoSrc = new Float64Array(totalCandles);
    const renko1Close = new Float64Array(totalCandles);
    const bSize = boxSize1;
    let currentLevel = Math.floor((candles[0] ? candles[0].close : 0) / bSize) * bSize;
    let direction = 0;

    for (let i = 0; i < totalCandles; i++) {
        const p = candles[i].close;
        if (direction === 0) {
            if (p >= currentLevel + bSize) {
                direction = 1;
                const count = Math.floor((p - currentLevel) / bSize);
                currentLevel += count * bSize;
                renkoSrc[i] = currentLevel;
            } else if (p <= currentLevel - bSize) {
                direction = -1;
                const count = Math.floor((currentLevel - p) / bSize);
                currentLevel -= count * bSize;
                renkoSrc[i] = currentLevel;
            } else {
                renkoSrc[i] = p;
            }
        } else if (direction === 1) {
            if (p >= currentLevel + bSize) {
                const count = Math.floor((p - currentLevel) / bSize);
                currentLevel += count * bSize;
                renkoSrc[i] = currentLevel;
            } else if (p <= currentLevel - 2 * bSize) {
                direction = -1;
                currentLevel -= 2 * bSize;
                const extra = Math.floor((currentLevel - p) / bSize);
                currentLevel -= extra * bSize;
                renkoSrc[i] = currentLevel;
            } else {
                renkoSrc[i] = p;
            }
        } else if (direction === -1) {
            if (p <= currentLevel - bSize) {
                const count = Math.floor((currentLevel - p) / bSize);
                currentLevel -= count * bSize;
                renkoSrc[i] = currentLevel;
            } else if (p >= currentLevel + 2 * bSize) {
                direction = 1;
                currentLevel += 2 * bSize;
                const extra = Math.floor((p - currentLevel) / bSize);
                currentLevel += extra * bSize;
                renkoSrc[i] = currentLevel;
            } else {
                renkoSrc[i] = p;
            }
        }
        renko1Close[i] = currentLevel;
    }

    // 3. Compute 10 Guppy EMAs continuously over renkoSrc
    // Fast: 3, 5, 8, 10, 12 (Aqua)
    // Slow: 30, 35, 40, 45, 50 (Blue)
    const initialSrc = renkoSrc[0] || (candles[0] ? candles[0].close : 0);

    const fastEmas = fastLengths.map(len => {
        const arr = new Float64Array(totalCandles);
        const k = 2 / (len + 1);
        let ema = initialSrc;
        for (let i = 0; i < totalCandles; i++) {
            ema = renkoSrc[i] * k + ema * (1 - k);
            arr[i] = ema;
        }
        return arr;
    });

    const slowEmas = slowLengths.map(len => {
        const arr = new Float64Array(totalCandles);
        const k = 2 / (len + 1);
        let ema = initialSrc;
        for (let i = 0; i < totalCandles; i++) {
            ema = renkoSrc[i] * k + ema * (1 - k);
            arr[i] = ema;
        }
        return arr;
    });

    // 4. Step-map Renko 2 close (White)
    const renko2Close = new Float64Array(totalCandles);
    let b2Idx = 0;
    for (let i = 0; i < totalCandles; i++) {
        const ts = candles[i].timestamp;
        while (b2Idx + 1 < r2Bricks.length && r2Bricks[b2Idx + 1].timestamp <= ts) {
            b2Idx++;
        }
        renko2Close[i] = (r2Bricks.length > 0 && r2Bricks[b2Idx].timestamp <= ts) ? r2Bricks[b2Idx].close : NaN;
    }

    // 5. Step-map Renko 3 close (Blue)
    const renko3Close = new Float64Array(totalCandles);
    let b3Idx = 0;
    for (let i = 0; i < totalCandles; i++) {
        const ts = candles[i].timestamp;
        while (b3Idx + 1 < r3Bricks.length && r3Bricks[b3Idx + 1].timestamp <= ts) {
            b3Idx++;
        }
        renko3Close[i] = (r3Bricks.length > 0 && r3Bricks[b3Idx].timestamp <= ts) ? r3Bricks[b3Idx].close : NaN;
    }

    // 6. Compute Price Close MA (3-period EMA of candle close in Red)
    const closeMa = new Float64Array(totalCandles);
    const kClose = 2 / (closeMaLength + 1);
    let cEma = candles[0] ? candles[0].close : NaN;
    for (let i = 0; i < totalCandles; i++) {
        const c = candles[i].close;
        cEma = isNaN(cEma) ? c : c * kClose + cEma * (1 - kClose);
        closeMa[i] = cEma;
    }

    return {
        fastEmas,      // 5 Float64Arrays [3, 5, 8, 10, 12] (Aqua)
        slowEmas,      // 5 Float64Arrays [30, 35, 40, 45, 50] (Blue)
        renko1Close,   // Float64Array (Renko 1 close)
        renko2Close,   // Float64Array (Renko 2 close, White)
        renko3Close,   // Float64Array (Renko 3 close, Blue)
        closeMa,       // Float64Array (3-period EMA of candle close, Red)
        boxSize1,
        boxSize2,
        boxSize3,
        totalBricks1: r1Bricks.length,
        totalBricks2: r2Bricks.length,
        totalBricks3: r3Bricks.length,
        lastTrend: r1Bricks.length > 0 ? r1Bricks[r1Bricks.length - 1].direction : 0
    };
}
