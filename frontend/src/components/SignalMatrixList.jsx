import React from 'react';
import { formatSymbol } from '../helpers/formatSymbol';

const isRecent = (timestamp) => {
    if (!timestamp) return false;
    const diff = Date.now() - new Date(timestamp).getTime();
    return diff < 5 * 60 * 1000; // 5 minutes
};

const SignalMatrixList = ({ symbols, symbolPrices, selectedSymbol, onSymbolSelect }) => {
    const timeframes = [
        { key: '15min', label: '15M' },
        { key: '5min', label: '5M' },
        { key: '3min', label: '3M' },
        { key: '1min', label: '1M' }
    ];

    const categorize = () => {
        const stocks = [];
        const futures = [];
        const calls = [];
        const puts = [];

        symbols.forEach(s => {
            if (s.endsWith('FUT')) futures.push(s);
            else if (/\d+[CP]E$/.test(s)) {
                if (s.endsWith('CE')) calls.push(s);
                else puts.push(s);
            } else stocks.push(s);
        });

        return { stocks, futures, calls, puts };
    };

    const categories = categorize();

    const renderRows = (items) => {
        return items.map(symbol => {
            const data = symbolPrices[symbol] || {};
            const stTrendByTf = data.stTrendByTf || {};
            const emaBiasByTf = data.emaBiasByTf || {};
            const lastFlips = data.lastFlipTimes || {};

            const isLongConfluence = ['1min', '3min', '5min', '15min'].every(tf => stTrendByTf[tf] === 1);
            const isShortConfluence = ['1min', '3min', '5min', '15min'].every(tf => stTrendByTf[tf] === -1);

            // Scalp Trigger: Higher TFs aligned + 1M just flipped
            const isScalpLong = ['3min', '5min', '15min'].every(tf => stTrendByTf[tf] === 1) &&
                stTrendByTf['1min'] === 1 && isRecent(lastFlips['1min']);

            const isScalpShort = ['3min', '5min', '15min'].every(tf => stTrendByTf[tf] === -1) &&
                stTrendByTf['1min'] === -1 && isRecent(lastFlips['1min']);

            const hasRecentFlip = Object.values(lastFlips).some(ts => isRecent(ts));
            const isActive = selectedSymbol === symbol;
            const priceData = symbolPrices[symbol];

            return (
                <tr
                    key={symbol}
                    className={`matrix-row ${isActive ? 'active-row' : ''} ${hasRecentFlip ? 'flip-alert' : ''} ${isLongConfluence ? 'confluence-long' : ''} ${isShortConfluence ? 'confluence-short' : ''} ${isScalpLong ? 'scalp-trigger-long' : ''} ${isScalpShort ? 'scalp-trigger-short' : ''}`}
                    onClick={() => onSymbolSelect(symbol)}
                >
                    <td className="symbol-cell-main">
                        <div className="symbol-name-row">
                            <span className="sym-text">{formatSymbol(symbol)}</span>
                            {priceData && (
                                <span className={`price-text ${priceData.isGreen ? 'up' : 'dn'}`}>
                                    {priceData.price.toLocaleString(undefined, { minimumFractionDigits: 2 })}
                                </span>
                            )}
                        </div>
                    </td>
                    {timeframes.map(tf => {
                        const trend = stTrendByTf[tf.key];
                        const emaBias = data.emaBiasByTf?.[tf.key];
                        const flip = lastFlips[tf.key];
                        const trendClass = trend === 1 ? 'u-bg' : (trend === -1 ? 'd-bg' : 'v-bg');
                        const biasClass = emaBias === 1 ? 'e-u-bg' : (emaBias === -1 ? 'e-d-bg' : 'e-v-bg');

                        return (
                            <td key={tf.key} className="signal-cell">
                                <div className="indicator-group" title={`${tf.label} Signals`}>
                                    <div className={`trend-box ${trendClass} ${isRecent(flip) ? 'pulse' : ''}`}></div>
                                    <div className={`bias-box ${biasClass}`}></div>
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
            <div className="matrix-category-block">
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
