import React, { useState, useEffect, useRef } from 'react';
import { formatSymbol } from '../helpers/formatSymbol';

/**
 * High-Performance Smart Symbol Search
 * Provides instant fuzzy searching across tracked symbols
 */
export default function SymbolSearch({
    symbols,
    onSymbolSelect,
    onGlobalSearch,
    symbolPrices,
    placeholder = "Search Symbol (/)"
}) {
    const [query, setQuery] = useState('');
    const [results, setResults] = useState([]);
    const [showResults, setShowResults] = useState(false);
    const [activeIndex, setActiveIndex] = useState(0);
    const inputRef = useRef(null);
    const containerRef = useRef(null);

    // Global shortcut handle (/)
    useEffect(() => {
        const handleKeyDown = (e) => {
            if (e.key === '/' && document.activeElement !== inputRef.current) {
                e.preventDefault();
                inputRef.current?.focus();
            }
            if (e.key === 'Escape') {
                setShowResults(false);
                inputRef.current?.blur();
            }
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, []);

    useEffect(() => {
        if (!query.trim()) {
            setResults([]);
            setShowResults(false);
            return;
        }

        const q = query.toUpperCase();
        const filtered = symbols
            .filter(s => s.toUpperCase().includes(q))
            .slice(0, 10);

        setResults(filtered);
        setShowResults(true);
        setActiveIndex(0);
    }, [query, symbols]);

    const handleSelect = (symbol) => {
        onSymbolSelect(symbol);
        setQuery('');
        setShowResults(false);
        inputRef.current?.blur();
    };

    const handleKeyDown = (e) => {
        if (e.key === 'ArrowDown') {
            e.preventDefault();
            setActiveIndex(prev => Math.min(prev + 1, results.length - 1));
        } else if (e.key === 'ArrowUp') {
            e.preventDefault();
            setActiveIndex(prev => Math.max(prev - 1, 0));
        } else if (e.key === 'Enter') {
            if (results[activeIndex]) {
                handleSelect(results[activeIndex]);
            } else if (onGlobalSearch) {
                onGlobalSearch(query);
            }
        }
    };

    return (
        <div className="symbol-search-container" ref={containerRef} style={{ position: 'relative', width: '220px' }}>
            <div className="search-input-wrapper" style={{ position: 'relative' }}>
                <input
                    ref={inputRef}
                    type="text"
                    className="smart-search-input"
                    placeholder={placeholder}
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    onFocus={() => query.trim() && setShowResults(true)}
                    onKeyDown={handleKeyDown}
                    autoComplete="off"
                    style={{
                        width: '100%',
                        background: 'rgba(30, 41, 59, 0.4)',
                        border: '1px solid rgba(255, 255, 255, 0.08)',
                        borderRadius: '6px',
                        padding: '6px 12px 6px 32px',
                        color: '#f8fafc',
                        fontSize: '11px',
                        fontFamily: '"Outfit", sans-serif',
                        fontWeight: '500',
                        outline: 'none',
                        transition: 'all 0.2s',
                        backdropFilter: 'blur(8px)'
                    }}
                />
                <span className="search-icon" style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', opacity: 0.5 }}>🔍</span>
                {query && <button onClick={() => setQuery('')} style={{ position: 'absolute', right: '8px', top: '50%', transform: 'translateY(-50%)', background: 'transparent', border: 'none', color: '#64748b', cursor: 'pointer', fontSize: '10px' }}>✕</button>}
            </div>

            {showResults && results.length > 0 && (
                <div className="search-results-dropdown" style={{
                    position: 'absolute',
                    top: '100%',
                    left: 0,
                    right: 0,
                    marginTop: '4px',
                    background: '#0f172a',
                    border: '1px solid rgba(59, 130, 246, 0.3)',
                    borderRadius: '8px',
                    boxShadow: '0 10px 25px rgba(0,0,0,0.5)',
                    zIndex: 2000,
                    overflow: 'hidden'
                }}>
                    {results.map((sym, idx) => {
                        const priceData = symbolPrices[sym] || {};
                        const trend = priceData.stTrendByTf?.['3min'] || 0; // Default to 3m trend
                        const trendColor = trend === 1 ? '#34d399' : (trend === -1 ? '#f87171' : '#94a3b8');

                        return (
                            <div
                                key={sym}
                                className={`search-result-item ${idx === activeIndex ? 'active' : ''}`}
                                onClick={() => handleSelect(sym)}
                                onMouseEnter={() => setActiveIndex(idx)}
                                style={{
                                    padding: '8px 12px',
                                    display: 'flex',
                                    justifyContent: 'space-between',
                                    alignItems: 'center',
                                    cursor: 'pointer',
                                    background: idx === activeIndex ? 'rgba(59, 130, 246, 0.1)' : 'transparent',
                                    borderLeft: `3px solid ${idx === activeIndex ? '#3b82f6' : 'transparent'}`,
                                    transition: 'all 0.1s'
                                }}
                            >
                                <div style={{ display: 'flex', flexDirection: 'column' }}>
                                    <span style={{ fontSize: '11px', fontWeight: '800', color: '#f8fafc' }}>{formatSymbol(sym)}</span>
                                    <span style={{ fontSize: '9px', color: '#64748b' }}>{sym.includes('FUT') ? 'FUTURES' : (/\d+[CP]E$/.test(sym) ? 'OPTIONS' : 'STOCKS')}</span>
                                </div>
                                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                    <div style={{ width: '6px', height: '6px', borderRadius: '50%', background: trendColor, boxShadow: `0 0 6px ${trendColor}66` }}></div>
                                    <span style={{ fontSize: '10px', fontFamily: '"JetBrains Mono", monospace', color: trendColor }}>{priceData.price || '---'}</span>
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}
        </div>
    );
}
