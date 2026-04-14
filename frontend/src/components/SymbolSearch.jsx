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
    placeholder = "Search Symbol (/)",
    isOpen,
    onClose
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
            if (e.key === '/' && document.activeElement !== inputRef.current && !isOpen) {
                e.preventDefault();
                onClose(true); // Open modal
            }
            if (e.key === 'Escape' && isOpen) {
                setShowResults(false);
                onClose(false); // Close modal
                inputRef.current?.blur();
            }
        };
        window.addEventListener('keydown', handleKeyDown);
        return () => window.removeEventListener('keydown', handleKeyDown);
    }, [isOpen, onClose]);

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

    useEffect(() => {
        if (isOpen) {
            setTimeout(() => inputRef.current?.focus(), 50);
        } else {
            setQuery('');
            setShowResults(false);
        }
    }, [isOpen]);

    const handleSelect = (symbol) => {
        onSymbolSelect(symbol);
        setQuery('');
        setShowResults(false);
        onClose(false);
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
                onClose(false);
            }
        }
    };

    if (!isOpen) return null;

    return (
        <div style={{
            position: 'fixed',
            top: 0, left: 0, right: 0, bottom: 0,
            backgroundColor: 'rgba(2, 6, 23, 0.65)',
            backdropFilter: 'blur(8px)',
            WebkitBackdropFilter: 'blur(8px)',
            zIndex: 9999,
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'flex-start',
            paddingTop: '20vh'
        }} onClick={() => onClose(false)}>
            <div className="symbol-search-container" ref={containerRef} style={{ position: 'relative', width: '600px', maxWidth: '90%' }} onClick={e => e.stopPropagation()}>
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
                            background: 'rgba(15, 23, 42, 0.8)',
                            border: '1px solid rgba(59, 130, 246, 0.5)',
                            borderRadius: '12px',
                            padding: '16px 24px 16px 52px',
                            color: '#f8fafc',
                            fontSize: '18px',
                            fontFamily: '"Outfit", sans-serif',
                            fontWeight: '500',
                            outline: 'none',
                            transition: 'all 0.2s',
                            boxShadow: '0 0 30px rgba(59, 130, 246, 0.15)',
                            backdropFilter: 'blur(8px)'
                        }}
                    />
                    <span className="search-icon" style={{ position: 'absolute', left: '20px', top: '50%', transform: 'translateY(-50%)', opacity: 0.5, fontSize: '20px' }}>🔍</span>
                    {query && <button onClick={() => setQuery('')} style={{ position: 'absolute', right: '16px', top: '50%', transform: 'translateY(-50%)', background: 'transparent', border: 'none', color: '#64748b', cursor: 'pointer', fontSize: '14px' }}>✕</button>}
                </div>

                {showResults && results.length > 0 && (
                    <div className="search-results-dropdown" style={{
                        position: 'absolute',
                        top: '100%',
                        left: 0,
                        right: 0,
                        marginTop: '8px',
                        background: '#0f172a',
                        border: '1px solid rgba(59, 130, 246, 0.3)',
                        borderRadius: '12px',
                        boxShadow: '0 20px 40px rgba(0,0,0,0.5)',
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
                                        padding: '12px 20px',
                                        display: 'flex',
                                        justifyContent: 'space-between',
                                        alignItems: 'center',
                                        cursor: 'pointer',
                                        background: idx === activeIndex ? 'rgba(59, 130, 246, 0.15)' : 'transparent',
                                        borderLeft: `4px solid ${idx === activeIndex ? '#3b82f6' : 'transparent'}`,
                                        transition: 'all 0.1s'
                                    }}
                                >
                                    <div style={{ display: 'flex', flexDirection: 'column' }}>
                                        <span style={{ fontSize: '16px', fontWeight: '800', color: '#f8fafc' }}>{formatSymbol(sym)}</span>
                                        <span style={{ fontSize: '12px', color: '#94a3b8', marginTop: '2px' }}>{sym.includes('FUT') ? 'FUTURES' : (/\d+[CP]E$/.test(sym) ? 'OPTIONS' : 'STOCKS')}</span>
                                    </div>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                                        <div style={{ width: '8px', height: '8px', borderRadius: '50%', background: trendColor, boxShadow: `0 0 8px ${trendColor}88` }}></div>
                                        <span style={{ fontSize: '14px', fontFamily: '"JetBrains Mono", monospace', color: trendColor }}>{priceData.price || '---'}</span>
                                    </div>
                                </div>
                            );
                        })}
                    </div>
                )}
            </div>
        </div>
    );
}
