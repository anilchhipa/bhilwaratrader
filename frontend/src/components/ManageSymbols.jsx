import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { API_BASE } from '../config';

const ManageSymbols = ({ onClose, onUpdate }) => {
    const [categories, setCategories] = useState({ STOCKS: [], FUTURES: [], OPTIONS: [] });
    const [tracked, setTracked] = useState([]);
    const [loading, setLoading] = useState(false);

    useEffect(() => {
        fetchTracked();
        fetchCategorized();
    }, []);

    const fetchTracked = async () => {
        try {
            const res = await axios.get(`${API_BASE}/api/symbols`);
            setTracked(Object.keys(res.data));
            if (onUpdate) onUpdate();
        } catch (err) {
            console.error("Error fetching tracked symbols", err);
        }
    };

    const fetchCategorized = async () => {
        setLoading(true);
        try {
            const res = await axios.get(`${API_BASE}/api/symbols/categorized`);
            setCategories(res.data);
        } catch (err) {
            console.error("Error fetching categories", err);
        } finally {
            setLoading(false);
        }
    };

    const trackSymbol = async (instStr) => {
        const inst = JSON.parse(instStr);
        try {
            await axios.post(`${API_BASE}/api/symbols/track`, {
                symbol: inst.symbol,
                exchange: inst.exchange
            });
            fetchTracked();
        } catch (err) {
            console.error("Failed to track symbol", err);
        }
    };

    const untrackSymbol = async (symbol) => {
        try {
            await axios.post(`${API_BASE}/api/symbols/untrack`, { symbol });
            fetchTracked();
        } catch (err) {
            console.error("Failed to untrack symbol", err);
        }
    };

    const toggleSymbol = async (inst, isTracked) => {
        if (isTracked) {
            await untrackSymbol(inst.symbol);
        } else {
            await trackSymbol(JSON.stringify(inst));
        }
    };

    const getStrike = (symbol) => {
        // Regex for SENSEX/NIFTY weekly options: [INDEX][YY][M/MONTH][DD][STRIKE][CE/PE]
        // SENSEX2630571200CE -> Strike 71200
        const regex = /^([A-Z]+)(\d{2})([1-9OND])(\d{2})(\d+)([CP]E)$/;
        const match = symbol.match(regex);
        if (match) return match[5];
        return symbol;
    };

    const formatDate = (dateStr) => {
        if (!dateStr) return '';
        const d = new Date(dateStr);
        return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
    };

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
                        const isCall = inst.type === 'CE';

                        const atmStrike = Math.round(spot / 100) * 100;
                        if (strike === atmStrike) {
                            highlightClass = "atm-highlight";
                        } else if (isCall) {
                            highlightClass = strike < spot ? "itm-highlight" : "otm-highlight";
                        } else {
                            highlightClass = strike > spot ? "itm-highlight" : "otm-highlight";
                        }
                    }

                    return (
                        <div key={inst.symbol} className={`checkbox-item ${highlightClass}`}>
                            <label>
                                <input
                                    type="checkbox"
                                    checked={isTracked}
                                    onChange={() => toggleSymbol(inst, isTracked)}
                                />
                                <span className="inst-name">{displayName}</span>
                                {!isOption && <span className="inst-exch">{inst.exchange}</span>}
                            </label>
                        </div>
                    );
                })}
            </div>
        </div>
    );

    const [spotPrice, setSpotPrice] = useState('');

    const getStrikeValue = (symbol) => {
        const strike = getStrike(symbol);
        return parseInt(strike);
    };

    const filterOptionsBySpot = (options) => {
        if (!spotPrice || isNaN(spotPrice)) return options;
        const spot = parseFloat(spotPrice);
        const atm = Math.round(spot / 100) * 100;
        const minStrike = atm - 500;
        const maxStrike = atm + 500;

        return options.filter(opt => {
            const strike = getStrikeValue(opt.symbol);
            return strike >= minStrike && strike <= maxStrike;
        });
    };

    const callOptions = filterOptionsBySpot(categories.OPTIONS.filter(i => i.type === 'CE'));
    const putOptions = filterOptionsBySpot(categories.OPTIONS.filter(i => i.type === 'PE'));

    // Extract expiry/underlying for header from first option available
    const firstOpt = categories.OPTIONS[0];
    const optHeaderSuffix = firstOpt ? ` - ${firstOpt.name} (${formatDate(firstOpt.expiry)})` : "";

    const [selectedForFlush, setSelectedForFlush] = useState([]);

    const handleFlush = async () => {
        if (selectedForFlush.length === 0) return;
        if (!window.confirm(`Are you sure you want to flush data (DB + Redis) for ${selectedForFlush.length} symbols?`)) return;

        try {
            await axios.post(`${API_BASE}/api/symbols/flush`, { symbols: selectedForFlush });
            alert("Data flushed successfully");
            setSelectedForFlush([]);
        } catch (err) {
            console.error("Flush failed", err);
            alert("Flush failed");
        }
    };

    const handleFlushAll = async () => {
        if (!window.confirm("CRITICAL: This will delete ALL candle data from the database and Redis for ALL symbols. This cannot be undone. Are you sure?")) return;

        try {
            await axios.post(`${API_BASE}/api/symbols/flush-all`);
            alert("All global data flushed successfully");
            setSelectedForFlush([]);
            // Optionally refresh tracked if needed, but flush-all doesn't untrack
            if (onUpdate) onUpdate();
        } catch (err) {
            console.error("Flush All failed", err);
            alert("Flush All failed");
        }
    };

    const toggleFlushSelect = (symbol) => {
        setSelectedForFlush(prev =>
            prev.includes(symbol) ? prev.filter(s => s !== symbol) : [...prev, symbol]
        );
    };

    return (
        <div className="manage-symbols-modal">
            <div className="modal-content">
                <div className="modal-header">
                    <div className="title-area">
                        <h2>Manage Symbols</h2>
                        <div className="spot-filter">
                            <label>SENSEX SPOT</label>
                            <div className="spot-input-wrapper">
                                <input
                                    type="number"
                                    placeholder="Enter Spot Price..."
                                    value={spotPrice}
                                    onChange={(e) => setSpotPrice(e.target.value)}
                                />
                                <span className="spot-unit">₹</span>
                            </div>
                        </div>
                    </div>
                    <button className="close-btn" onClick={onClose}>&times;</button>
                </div>

                <div className="manage-body">
                    {loading && <div className="loader">Loading instrument lists...</div>}

                    <div className="categories-container">
                        {renderCategory("Sensex Stocks", categories.STOCKS)}
                        {renderCategory("Futures", categories.FUTURES)}
                        {renderCategory("Call Options" + optHeaderSuffix, callOptions, true)}
                        {renderCategory("Put Options" + optHeaderSuffix, putOptions, true)}

                        <div className="category-group flush-group">
                            <div className="group-header">
                                <label>Active Watchlist (Select to Flush Data)</label>
                                <button className="flush-all-link" onClick={handleFlushAll}>
                                    Flush ALL Global Data
                                </button>
                            </div>
                            {tracked.length > 0 ? (
                                <div className="checkbox-list">
                                    {tracked.map(s => (
                                        <div key={s} className={`checkbox-item flush-item ${selectedForFlush.includes(s) ? 'selected' : ''}`}>
                                            <label>
                                                <input
                                                    type="checkbox"
                                                    checked={selectedForFlush.includes(s)}
                                                    onChange={() => toggleFlushSelect(s)}
                                                />
                                                <span className="inst-name">{s}</span>
                                            </label>
                                        </div>
                                    ))}
                                </div>
                            ) : (
                                <div className="empty-msg">No symbols tracked yet.</div>
                            )}
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

export default ManageSymbols;
