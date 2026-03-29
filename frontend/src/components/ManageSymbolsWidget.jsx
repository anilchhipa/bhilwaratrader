import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';
import { API_BASE } from '../config';

const ManageSymbolsWidget = ({ isOpen, onClose, onUpdate, trackedData: propTrackedData, tracked: propTracked }) => {
    const [categories, setCategories] = useState({ STOCKS: [], FUTURES: [], OPTIONS: [] });
    // Keep local states but they can be synced with props
    const [tracked, setTracked] = useState([]);
    const [trackedData, setTrackedData] = useState({});
    const [loading, setLoading] = useState(false);
    const [spotPrice, setSpotPrice] = useState('');
    const [selectedForFlush, setSelectedForFlush] = useState([]);
    const [searchQuery, setSearchQuery] = useState('');
    const [searchResults, setSearchResults] = useState([]);
    const [isSearching, setIsSearching] = useState(false);

    const [position, setPosition] = useState(() => {
        const saved = localStorage.getItem('manage_symbols_pos');
        return saved ? JSON.parse(saved) : { x: window.innerWidth / 2 - 450, y: 100 };
    });

    const [isDragging, setIsDragging] = useState(false);
    const offsetRef = useRef({ x: 0, y: 0 });

    useEffect(() => {
        if (!isOpen) return;
        fetchCategorized();
    }, [isOpen]);

    // Sync with props
    useEffect(() => {
        if (propTracked) setTracked(propTracked);
        if (propTrackedData) setTrackedData(propTrackedData);
    }, [propTracked, propTrackedData]);


    const fetchCategorized = async () => {
        setLoading(true);
        try {
            const res = await axios.get(`${API_BASE}/api/symbols/categorized`);
            setCategories(res.data);
        } catch (err) { } finally {
            setLoading(false);
        }
    };

    const trackSymbol = async (inst) => {
        try {
            await axios.post(`${API_BASE}/api/symbols/track`, {
                symbol: inst.symbol,
                exchange: inst.exchange
            });
            if (onUpdate) onUpdate(); // Trigger refresh in App.jsx
        } catch (err) { }
    };

    const untrackSymbol = async (symbol) => {
        try {
            await axios.post(`${API_BASE}/api/symbols/untrack`, { symbol });
            if (onUpdate) onUpdate();
        } catch (err) { }
    };

    const toggleSymbol = async (inst, isTracked) => {
        if (isTracked) await untrackSymbol(inst.symbol);
        else await trackSymbol(inst);
    };

    const getStrike = (symbol) => {
        const regex = /^[a-zA-Z]+\d{2}(?:[a-zA-Z]{3}|[1-9ONDond]\d{2})(\d+)[CPcp][Ee]$/;
        const match = symbol.match(regex);
        if (match) return match[1];
        const fallbackRegex = /(\d{5})[CPcp][Ee]$/;
        const fallbackMatch = symbol.match(fallbackRegex);
        if (fallbackMatch) return fallbackMatch[1];
        return symbol;
    };

    const getStrikeValue = (symbol) => parseInt(getStrike(symbol)) || 0;

    const formatDate = (dateStr) => {
        if (!dateStr) return '';
        const d = new Date(dateStr);
        return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
    };

    useEffect(() => {
        const sensexState = trackedData['SENSEX'];
        if (sensexState && sensexState.currentCandles?.['1min']) {
            setSpotPrice(sensexState.currentCandles['1min'].close.toString());
        }
    }, [trackedData]);

    const filterOptionsBySpot = (options) => {
        if (!spotPrice || isNaN(spotPrice)) return options;
        const spot = parseFloat(spotPrice);
        const atm = Math.round(spot / 100) * 100;
        const minStrike = atm - 1000;
        const maxStrike = atm + 1000;
        return options.filter(opt => {
            const strike = getStrikeValue(opt.symbol);
            return strike >= minStrike && strike <= maxStrike;
        });
    };

    const handleFlush = async () => {
        if (selectedForFlush.length === 0) return;
        if (!window.confirm(`Flush data for ${selectedForFlush.length} symbols?`)) return;
        try {
            await axios.post(`${API_BASE}/api/symbols/flush`, { symbols: selectedForFlush });
            setSelectedForFlush([]);
        } catch (err) { }
    };

    const handleFlushAll = async () => {
        if (!window.confirm("CRITICAL: Flush ALL?")) return;
        try {
            await axios.post(`${API_BASE}/api/symbols/flush-all`);
            setSelectedForFlush([]);
        } catch (err) { }
    };

    const handleSearch = async (q) => {
        setSearchQuery(q);
        if (q.length < 2) {
            setSearchResults([]);
            return;
        }
        setIsSearching(true);
        try {
            const res = await axios.get(`${API_BASE}/api/symbols/search?query=${q}`);
            setSearchResults(res.data);
        } catch (err) { } finally {
            setIsSearching(false);
        }
    };

    const startDrag = (e) => {
        if (e.target.closest('.modal-header') && !e.target.closest('.close-btn') && !e.target.closest('.spot-filter') && !e.target.closest('.search-box-container')) {
            setIsDragging(true);
            offsetRef.current = {
                x: e.clientX - position.x,
                y: e.clientY - position.y
            };
            e.preventDefault();
        }
    };

    useEffect(() => {
        if (!isDragging) return;
        const handleMove = (e) => {
            const pos = { x: e.clientX - offsetRef.current.x, y: e.clientY - offsetRef.current.y };
            setPosition(pos);
            localStorage.setItem('manage_symbols_pos', JSON.stringify(pos));
        };
        const stopDrag = () => setIsDragging(false);
        window.addEventListener('mousemove', handleMove);
        window.addEventListener('mouseup', stopDrag);
        return () => {
            window.removeEventListener('mousemove', handleMove);
            window.removeEventListener('mouseup', stopDrag);
        };
    }, [isDragging]);

    if (!isOpen) return null;

    const callOptions = filterOptionsBySpot(categories.OPTIONS.filter(i => i.type === 'CE'));
    const putOptions = filterOptionsBySpot(categories.OPTIONS.filter(i => i.type === 'PE'));
    const firstOpt = categories.OPTIONS[0];
    const optHeaderSuffix = firstOpt ? ` - ${firstOpt.name} (${formatDate(firstOpt.expiry)})` : "";

    const renderCategory = (label, items, isOption = false) => (
        <div className="category-group">
            <label>{label}</label>
            <div className="checkbox-list">
                {items.map(inst => {
                    const isTracked = tracked.includes(inst.symbol);
                    const displayName = isOption ? getStrike(inst.symbol) : inst.symbol;
                    let highlightClass = "";
                    if (isOption && spotPrice) {
                        const spot = parseFloat(spotPrice);
                        const strike = getStrikeValue(inst.symbol);
                        const atmStrike = Math.round(spot / 100) * 100;
                        if (strike === atmStrike) highlightClass = "atm-highlight";
                        else if ((inst.type === 'CE' && strike < spot) || (inst.type === 'PE' && strike > spot)) highlightClass = "itm-highlight";
                        else highlightClass = "otm-highlight";
                    }
                    return (
                        <div key={inst.symbol} className={`checkbox-item ${highlightClass}`}>
                            <label>
                                <input type="checkbox" checked={isTracked} onChange={() => toggleSymbol(inst, isTracked)} />
                                <span className="inst-name">{displayName}</span>
                                {!isOption && <span className="inst-exch">{inst.exchange}</span>}
                            </label>
                        </div>
                    );
                })}
            </div>
        </div>
    );

    return (
        <div
            className="manage-symbols-modal floating-widget-container"
            style={{
                position: 'fixed',
                left: `${position.x}px`,
                top: `${position.y}px`,
                zIndex: 1200,
                width: '900px',
                pointerEvents: 'auto'
            }}
        >
            <div className={`modal-content ${isDragging ? 'dragging' : ''}`} style={{ width: '100%', margin: 0 }}>
                <div className="modal-header" onMouseDown={startDrag} style={{ cursor: 'move', userSelect: 'none' }}>
                    <div className="title-area">
                        <h2>Manage Symbols</h2>
                        <div className="spot-filter" onMouseDown={e => e.stopPropagation()}>
                            <label>SENSEX SPOT {trackedData['SENSEX'] ? <span className="live-badge">(Live)</span> : ""}</label>
                            <div className="spot-input-wrapper">
                                <input
                                    type="number"
                                    value={spotPrice}
                                    onChange={(e) => setSpotPrice(e.target.value)}
                                />
                                <span className="spot-unit">₹</span>
                            </div>
                        </div>
                        <div className="search-box-container" onMouseDown={e => e.stopPropagation()}>
                            <input
                                type="text"
                                className="search-input"
                                placeholder="Search any symbol (e.g. NIFTY, TCS...)"
                                value={searchQuery}
                                onChange={(e) => handleSearch(e.target.value)}
                            />
                            {isSearching && <div className="search-loader">...</div>}
                        </div>
                    </div>
                    <button className="close-btn" onClick={onClose}>&times;</button>
                </div>

                <div className="manage-body">
                    {searchQuery.length >= 2 && (
                        <div className="categories-container search-results-container" style={{ marginBottom: '24px' }}>
                            {renderCategory(`Search Results for "${searchQuery}"`, searchResults, false)}
                        </div>
                    )}

                    {loading && <div className="loader">Loading instrument lists...</div>}
                    <div className="categories-container">
                        {renderCategory("Sensex Stocks", categories.STOCKS)}
                        {renderCategory("Futures", categories.FUTURES)}
                        {renderCategory("Call Options" + optHeaderSuffix, callOptions, true)}
                        {renderCategory("Put Options" + optHeaderSuffix, putOptions, true)}

                        <div className="category-group flush-group">
                            <div className="group-header">
                                <label>Active Watchlist</label>
                                <button className="flush-all-link" onClick={handleFlushAll}>Flush ALL Global Data</button>
                            </div>
                            <div className="checkbox-list">
                                {tracked.map(s => (
                                    <div key={s} className={`checkbox-item flush-item ${selectedForFlush.includes(s) ? 'selected' : ''}`}>
                                        <label>
                                            <input
                                                type="checkbox"
                                                checked={selectedForFlush.includes(s)}
                                                onChange={() => setSelectedForFlush(prev => prev.includes(s) ? prev.filter(x => x !== s) : [...prev, s])}
                                            />
                                            <span className="inst-name">{s}</span>
                                        </label>
                                    </div>
                                ))}
                            </div>
                        </div>
                    </div>
                </div>

                <div className="modal-footer">
                    <div className="footer-actions">
                        {selectedForFlush.length > 0 && (
                            <button className="pill-btn active" onClick={handleFlush} style={{ background: 'var(--accent-crimson)' }}>
                                Flush Selected ({selectedForFlush.length})
                            </button>
                        )}
                        <button className="pill-btn active" onClick={onClose} style={{ padding: '8px 32px' }}>Done</button>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default ManageSymbolsWidget;
