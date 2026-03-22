export const runScalpBacktest = (data, quantity = 1000, targetPoints = 1.0, stopLossPoints = 0, instantEntry = false, costPerTrade = 0, slippage = 0) => {
    if (!data || data.length < 2) return null;

    let totalGrossPnL = 0;
    let winCount = 0;
    let loseCount = 0;
    let trades = [];
    let currentTrade = null;
    let dailyStats = {}; // { 'YYYY-MM-DD': { pnl: 0, trades: 0 } }

    for (let i = 1; i < data.length; i++) {
        const curr = data[i];
        const prev = data[i - 1];

        const currDate = new Date(curr.timestamp);
        const hours = currDate.getHours();
        const mins = currDate.getMinutes();
        const timeVal = hours * 100 + mins;

        // Restriction: No NEW entries after 3:15 PM (1515)
        const canEntry = timeVal < 1515;
        // Restriction: Force EXIT at or after 3:15 PM (1515)
        const isForceExitTime = timeVal >= 1515;

        if (!currentTrade) {
            // STRATEGY: PULLBACK ENTRY (Intraday only, before 3:15 PM)
            if (canEntry) {
                if (curr.stTrend === 1 && curr.low <= curr.stMa) {
                    const rawEntryPrice = curr.stMa;
                    const entryPrice = rawEntryPrice + slippage; // Worsen entry by slippage

                    currentTrade = {
                        type: 'LONG',
                        entryPrice: entryPrice,
                        target: entryPrice + targetPoints,
                        stop: (stopLossPoints > 0) ? (entryPrice - stopLossPoints) : null,
                        entryIndex: i,
                        entryTime: curr.timestamp
                    };
                }
                else if (curr.stTrend === -1 && curr.high >= curr.stMa) {
                    const rawEntryPrice = curr.stMa;
                    const entryPrice = rawEntryPrice - slippage; // Worsen entry by slippage

                    currentTrade = {
                        type: 'SHORT',
                        entryPrice: entryPrice,
                        target: entryPrice - targetPoints,
                        stop: (stopLossPoints > 0) ? (entryPrice + stopLossPoints) : null,
                        entryIndex: i,
                        entryTime: curr.timestamp
                    };
                }
            }
        }

        if (currentTrade) {
            const entryDateObj = new Date(currentTrade.entryTime);
            const currDateObj = new Date(curr.timestamp);

            // Intraday Logic: Exit if it's a NEW DAY OR if it's after 15:15
            const isEndOfDay = entryDateObj.toDateString() !== currDateObj.toDateString();
            const shouldForceExit = isEndOfDay || isForceExitTime;

            const trendReversed = (currentTrade.type === 'LONG' && curr.stTrend === -1) ||
                (currentTrade.type === 'SHORT' && curr.stTrend === 1);

            const result = checkExit(currentTrade, curr, trendReversed || shouldForceExit);
            if (result) {
                processExit(result);
            }
        }
    }

    function checkExit(trade, candle, forceExit) {
        let exitPrice = null;
        let reason = '';

        if (trade.type === 'LONG') {
            if (candle.high >= trade.target) {
                exitPrice = trade.target - slippage;
                reason = 'TARGET';
            } else if (trade.stop && candle.low <= trade.stop) {
                exitPrice = trade.stop - slippage;
                reason = 'STOP';
            } else if (forceExit) {
                exitPrice = candle.close - slippage;
                reason = 'INTRADAY EXIT / REVERSAL';
            }
        } else {
            if (candle.low <= trade.target) {
                exitPrice = trade.target + slippage;
                reason = 'TARGET';
            } else if (trade.stop && candle.high >= trade.stop) {
                exitPrice = trade.stop + slippage;
                reason = 'STOP';
            } else if (forceExit) {
                exitPrice = candle.close + slippage;
                reason = 'INTRADAY EXIT / REVERSAL';
            }
        }

        if (exitPrice !== null) return { exitPrice, reason, time: candle.timestamp, index: data.indexOf(candle) };
        return null;
    }

    function processExit(result) {
        const grossPnlPoints = currentTrade.type === 'LONG' ?
            (result.exitPrice - currentTrade.entryPrice) :
            (currentTrade.entryPrice - result.exitPrice);

        const grossPnL = grossPnlPoints * quantity;
        const netPnL = grossPnL - costPerTrade;

        totalGrossPnL += grossPnL;

        if (grossPnL > 0) winCount++;
        else loseCount++;

        const entryDate = new Date(currentTrade.entryTime).toLocaleDateString('en-GB');
        if (!dailyStats[entryDate]) dailyStats[entryDate] = { pnl: 0, trades: 0, wins: 0 };
        dailyStats[entryDate].pnl += netPnL;
        dailyStats[entryDate].trades += 1;
        if (grossPnL > 0) dailyStats[entryDate].wins += 1;

        trades.push({
            ...currentTrade,
            exitPrice: result.exitPrice,
            exitIndex: result.index,
            exitTime: result.time,
            grossPnL,
            netPnL,
            reason: result.reason
        });
        currentTrade = null;
    }

    const totalNetPnL = totalGrossPnL - (trades.length * costPerTrade);

    return {
        totalGrossPnL,
        totalNetPnL,
        totalBrokerage: trades.length * costPerTrade,
        winCount,
        loseCount,
        totalTrades: trades.length,
        winRate: trades.length > 0 ? (winCount / trades.length * 100).toFixed(1) : 0,
        trades,
        dailyStats: Object.entries(dailyStats).sort((a, b) => {
            const dateA = a[0].split('/').reverse().join('');
            const dateB = b[0].split('/').reverse().join('');
            return dateB.localeCompare(dateA); // Descending order
        })
    };
};
