import React from 'react';
import { formatSymbol } from '../helpers/formatSymbol';

const SymbolList = ({ symbols, onSymbolSelect, selectedSymbol }) => {
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
                        {selectedSymbol === s && (
                            <div className="live-tag">
                                <span className="live-dot"></span>
                                <span>LIVE</span>
                            </div>
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
