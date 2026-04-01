import React from 'react';
import { formatSymbol } from '../helpers/formatSymbol';

const isRecent = (timestamp) => {
    if (!timestamp) return false;
    const diff = Date.now() - new Date(timestamp).getTime();
    return diff < 5 * 60 * 1000; // 5 minutes
};

const SignalMatrixList = ({ symbols, symbolPrices, selectedSymbol, onSymbolSelect }) => {
    const timeframes = React.useMemo(() => [
        { key: '15min', label: '15M' },
        { key: '5min', label: '5M' },
        { key: '3min', label: '3M' },
        { key: '1min', label: '1M' }
    ], []);

    const categories = React.useMemo(() => {
        const stocks = [];
        const futures = [];
        const calls = [];
        const puts = [];

        symbols.forEach(s => {
            if (s === 'SENSEX') return; // Hide index itself from the clickable list
            if (s.endsWith('FUT')) futures.push(s);
            else if (/\d+[CP]E$/.test(s)) {
                if (s.endsWith('CE')) calls.push(s);
                else puts.push(s);
            } else stocks.push(s);
        });

        // SENSEX Free-Float Weightage Hierarchy (Official Rank)
        const WEIGHTS = {
            'HDFCBANK': 1,
            'RELIANCE': 2,
            'ICICIBANK': 3,
            'INFY': 4,
            'ITC': 5,
            'LT': 6,
            'TCS': 7,
            'AXISBANK': 8,
            'BHARTIARTL': 9,
            'SBIN': 10
        };

        const sorter = (a, b) => {
            const wa = WEIGHTS[a] || 999;
            const wb = WEIGHTS[b] || 999;
            return wa !== wb ? wa - wb : a.localeCompare(b);
        };

        return {
            stocks: [...stocks].sort(sorter),
            futures: [...futures].sort(sorter),
            calls: [...calls].sort(sorter),
            puts: [...puts].sort(sorter)
        };
    }, [symbols]);

    const renderRows = (items) => {
        return items.map(symbol => {
            const data = symbolPrices[symbol] || {};
            const lastFlips = data.lastFlipTimes || {};
            let latestFlipTs = Math.max(0, ...Object.values(lastFlips).map(ts => ts ? new Date(ts).getTime() : 0));
            let hasRecentFlip = latestFlipTs > 0 && isRecent(new Date(latestFlipTs));

            const isActive = selectedSymbol === symbol;
            const priceData = symbolPrices[symbol];

            return (
                <tr
                    key={symbol}
                    className={`matrix-row ${isActive ? 'active-row' : ''} ${hasRecentFlip ? 'flip-alert' : ''}`}
                    onClick={() => onSymbolSelect(symbol)}
                >
                    <td className="symbol-cell-main">
                        <div className="symbol-name-row">
                            <span className="sym-text">{formatSymbol(symbol)}</span>
                        </div>
                    </td>
                    {timeframes.map(tf => {
                        const trend1 = data.stTrendByTf?.[tf.key];
                        const trend2 = data.stTrend2ByTf?.[tf.key] ?? trend1; // Fallback to T1 if T2 is missing
                        const emaBias = data.emaBiasByTf?.[tf.key];
                        const flip = lastFlips[tf.key];

                        const t1Class = trend1 === 1 ? 'u-bg' : (trend1 === -1 ? 'd-bg' : 'v-bg');
                        const t2Class = trend2 === 1 ? 'u-bg' : (trend2 === -1 ? 'd-bg' : 'v-bg');
                        const biasClass = emaBias === 1 ? 'e-u-bg' : (emaBias === -1 ? 'e-d-bg' : 'e-v-bg');

                        return (
                            <td key={tf.key} className="signal-cell">
                                <div className="indicator-group" title={`${tf.label}: T1, T2, EMA`}>
                                    <div className="st-group">
                                        <div className="st-boxes-row">
                                            <div className={`st-box ${t1Class} ${isRecent(flip) ? 'pulse' : ''}`} title="T1"></div>
                                            <div className={`st-box ${t2Class}`} title="T2"></div>
                                        </div>
                                        <div className={`ema-underline ${biasClass}`} title="EMA Bias"></div>
                                    </div>
                                </div>
                            </td>
                        );
                    })}
                </tr>
            );
        });
    };

    const renderCategory = (title, items) => {
        if (!items || items.length === 0) return null;
        return (
            <div className="matrix-category-block" key={title}>
                <div className="category-header-title">{title}</div>
                <table className="premium-matrix-table">
                    <tbody>
                        {renderRows(items)}
                    </tbody>
                </table>
            </div>
        );
    };

    return (
        <div className="premium-matrix-container">
            {renderCategory("STOCKS", categories.stocks)}
            {renderCategory("FUTURES", categories.futures)}
            {renderCategory("CALL OPTIONS", categories.calls)}
            {renderCategory("PUT OPTIONS", categories.puts)}
        </div>
    );
};

export default SignalMatrixList;
