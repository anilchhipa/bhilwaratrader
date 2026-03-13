import React from 'react';
import { formatSymbol } from '../helpers/formatSymbol';

const SymbolList = ({ symbols, onSymbolSelect, selectedSymbol, symbolPrices = {} }) => {
    const categorize = () => {
        const stocks = [];
        const futures = [];
        const calls = [];
        const puts = [];

        symbols.forEach(s => {
            if (s.endsWith('FUT')) {
                futures.push(s);
            } else if (/\d+[CP]E$/.test(s)) {
                if (s.endsWith('CE')) calls.push(s);
                else puts.push(s);
            } else {
                stocks.push(s);
            }
        });

        return { stocks, futures, calls, puts };
    };

    const { stocks, futures, calls, puts } = categorize();

    const renderSection = (title, items) => {
        if (items.length === 0) return null;
        const sectionClass = title.toLowerCase().replace(' ', '-');
        return (
            <div className={`list-section ${sectionClass}-section`}>
                <div className="section-header">{title}</div>
                {items.map(s => (
                    <div
                        key={s}
                        className={`symbol-item ${selectedSymbol === s ? 'active' : ''}`}
                        onClick={() => onSymbolSelect(s)}
                    >
                        <span className="symbol-name">{formatSymbol(s)}</span>
                        {symbolPrices[s] ? (
                            <div
                                className="live-tag"
                                style={{
                                    backgroundColor: symbolPrices[s].isGreen ? 'rgba(34, 197, 94, 0.1)' : 'rgba(239, 68, 68, 0.1)',
                                    borderColor: symbolPrices[s].isGreen ? 'rgba(34, 197, 94, 0.2)' : 'rgba(239, 68, 68, 0.2)',
                                    color: symbolPrices[s].isGreen ? '#4ade80' : '#f87171'
                                }}
                            >
                                <span
                                    className="live-dot"
                                    style={{
                                        backgroundColor: symbolPrices[s].isGreen ? '#22c55e' : '#ef4444',
                                        boxShadow: `0 0 8px ${symbolPrices[s].isGreen ? 'rgba(34,197,94,0.5)' : 'rgba(239,68,68,0.5)'}`
                                    }}
                                ></span>
                                <span>{symbolPrices[s].price.toFixed(2)}</span>
                            </div>
                        ) : (
                            selectedSymbol === s && (
                                <div className="live-tag">
                                    <span className="live-dot"></span>
                                    <span>LIVE</span>
                                </div>
                            )
                        )}
                    </div>
                ))}
            </div>
        );
    };

    return (
        <div className="symbol-list">
            <div className="list-items">
                {renderSection("Stocks", stocks)}
                {renderSection("Futures", futures)}
                {renderSection("Calls", calls)}
                {renderSection("Puts", puts)}
            </div>
        </div>
    );
};

export default SymbolList;
