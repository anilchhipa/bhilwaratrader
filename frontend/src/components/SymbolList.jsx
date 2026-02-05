import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { API_BASE } from '../config';

const SymbolList = ({ onSymbolSelect, selectedSymbol }) => {
    const [symbols, setSymbols] = useState([]);

    useEffect(() => {
        // Fetch tracked symbols from backend
        axios.get(`${API_BASE}/api/symbols`)
            .then(res => {
                setSymbols(Object.keys(res.data));
            })
            .catch(err => console.error("Error fetching symbols", err));
    }, []);

    return (
        <div className="symbol-list">
            <h3>Market Watch</h3>
            <div className="list-items">
                {symbols.map(s => (
                    <div
                        key={s}
                        className={`symbol-item ${selectedSymbol === s ? 'active' : ''}`}
                        onClick={() => onSymbolSelect(s)}
                    >
                        <span className="symbol-name">{s}</span>
                        <span className="symbol-ltp">--.--</span>
                    </div>
                ))}
            </div>
        </div>
    );
};

export default SymbolList;
