import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import { API_BASE } from './config';
import ETradingChart from './components/ETradingChart';
import SymbolList from './components/SymbolList';
import ManageSymbols from './components/ManageSymbols';
import ChartConfig from './components/ChartConfig';
import { formatSymbol } from './helpers/formatSymbol';

import SignalMatrixList from './components/SignalMatrixList';

import './App.css';

function App() {
  const [selectedSymbol, setSelectedSymbol] = useState('RELIANCE');
  const [showManage, setShowManage] = useState(false);
  const [showConfig, setShowConfig] = useState(false);
  const [showDashboard, setShowDashboard] = useState(false);
  const [trackedSymbols, setTrackedSymbols] = useState([]);
  const [maximizedTf, setMaximizedTf] = useState(null);
  const [symbolPrices, setSymbolPrices] = useState({});
  const [backendReady, setBackendReady] = useState(false);
  const [showReadyPopup, setShowReadyPopup] = useState(false);
  const [isOffline, setIsOffline] = useState(false);

  // Use a ref to prevent closure issues in the polling interval
  const hasShownReadyPopup = React.useRef(false);

  const handlePriceUpdate = useCallback((symbol, price, isGreen) => {
    setSymbolPrices(prev => {
      const current = prev[symbol] || {};
      if (current.price !== price || current.isGreen !== isGreen) {
        return {
          ...prev,
          [symbol]: { ...current, price, isGreen }
        };
      }
      return prev;
    });
  }, []);

  // Visibility State for Technical Indicators
  const [visibility, setVisibility] = useState(() => {
    const saved = localStorage.getItem('chartVisibility');
    return saved ? JSON.parse(saved) : {
      EMA15: true, EMA15_5: true, EMA15_15: true, EMA15_15_15: true, EMA15_15_15_15: true,
      EMA15_15_15_15_30: true, CLOUD: true, SIGNALS: true,
      EMA50: true, EMA100: true,
      "1012_OPEN_CLOSE": true
    };
  });

  const fetchTrackedSymbols = () => {
    axios.get(`${API_BASE}/api/symbols`)
      .then(res => {
        // Show popup ONLY ONCE per page load using the ref
        if (!hasShownReadyPopup.current) {
          hasShownReadyPopup.current = true;
          setBackendReady(true);
          setShowReadyPopup(true);
          setTimeout(() => setShowReadyPopup(false), 5000);
        }
        setIsOffline(false);

        const symbolsData = res.data;
        setTrackedSymbols(Object.keys(symbolsData));

        const prices = {};
        for (const sym in symbolsData) {
          const state = symbolsData[sym];
          const currentCandles = state.currentCandles || {};
          const candle = currentCandles['1min'] || currentCandles['3min'] || currentCandles['5min'] || currentCandles['15min'];

          const stTrendByTf = {};
          const emaBiasByTf = {};
          for (const tf of ['1min', '3min', '5min', '15min']) {
            const indicators = (state.indicatorValues && state.indicatorValues[tf]) || {};
            const candleRef = (state.currentCandles && state.currentCandles[tf]) || {};

            // SuperTrend Component
            if (indicators['ST_TREND'] !== undefined) {
              stTrendByTf[tf] = indicators['ST_TREND'];
            } else if (candleRef.stTrend !== undefined) {
              stTrendByTf[tf] = candleRef.stTrend;
            }

            // EMA Bias Component (Comparison: ema15 > ema15_5)
            const e15 = indicators['EMA15'] !== undefined ? indicators['EMA15'] : candleRef.ema15;
            const e15_5 = indicators['EMA15_5'] !== undefined ? indicators['EMA15_5'] : candleRef.ema15_5;

            if (e15 && e15_5) {
              emaBiasByTf[tf] = e15 > e15_5 ? 1 : -1;
            } else {
              emaBiasByTf[tf] = 0;
            }
          }

          if (candle) {
            prices[sym] = {
              price: candle.close,
              isGreen: candle.close >= candle.open,
              stTrendByTf,
              emaBiasByTf,
              lastFlipTimes: state.lastFlipTimes || {}
            };
          }
        }
        setSymbolPrices(prev => ({ ...prev, ...prices }));
      })
      .catch(err => {
        console.error("Error fetching symbols", err);
        setIsOffline(true);
        // Removing setBackendReady(false) means the popup will ONLY show once
        // per browser tab session, even if the server restarts.
      });
  };

  useEffect(() => {
    fetchTrackedSymbols();
    const interval = setInterval(fetchTrackedSymbols, 2000);
    return () => clearInterval(interval);
  }, []);

  // Shared Order Lines State keyed by symbol
  const defaultOrderLines = {
    limit: { price: '', visible: false, label: 'Limit', color: '#3b82f6', quantity: 100, action: 'BUY' },
    target: { price: '', visible: false, label: 'Target', color: '#22c55e', quantity: 100, action: 'SELL' },
    sl: { price: '', visible: false, label: 'Stop', color: '#ef4444', quantity: 100, action: 'SELL' },
  };

  const [orderLinesState, setOrderLinesState] = useState({});
  const currentOrderLines = orderLinesState[selectedSymbol] || defaultOrderLines;

  const handleOrderLinesUpdate = (updater) => {
    setOrderLinesState(prev => {
      const current = prev[selectedSymbol] || defaultOrderLines;
      const nextLines = typeof updater === 'function' ? updater(current) : updater;
      return {
        ...prev,
        [selectedSymbol]: nextLines
      };
    });
  };


  return (
    <div className="app-container">
      <div className="main-content">
        {/* Connection Status Overlay */}
        {isOffline && (
          <div className="connection-overlay">
            <div className="status-card">
              <div className="spinner"></div>
              <h2>Backend Offline</h2>
              <p>Waiting for trading engine to start...</p>
            </div>
          </div>
        )}

        {/* System Ready Popup */}
        {showReadyPopup && (
          <div className="ready-popup" onClick={() => setShowReadyPopup(false)}>
            <div className="ready-icon">⚡</div>
            <div className="ready-content">
              <h3>System Ready</h3>
              <p>Trading Engine is online & synced.</p>
            </div>
          </div>
        )}

        {/* Glass Sidebar */}
        {/* Unified Glass Sidebar */}
        <aside className="sidebar">
          <div className="sidebar-header">
            <h3 className="outfit">Market Signals</h3>
            <div className="header-actions">
              <button className="icon-action-btn" onClick={() => setShowConfig(true)} title="Indicators">
                <span>📊</span>
              </button>
              <button className="icon-action-btn" onClick={() => setShowManage(true)} title="Manage Symbols">
                <span>⚙️</span>
              </button>
            </div>
          </div>

          <div className="symbol-list-container">
            <SignalMatrixList
              symbols={trackedSymbols}
              onSymbolSelect={setSelectedSymbol}
              selectedSymbol={selectedSymbol}
              symbolPrices={symbolPrices}
            />
          </div>


        </aside>

        {/* Charts Section */}
        <main className="chart-section">
          {maximizedTf ? (
            <div className="maximized-chart-wrapper">
              <ETradingChart
                symbol={selectedSymbol}
                initialInterval={maximizedTf}
                isGrid={false}
                sharedOrderLines={currentOrderLines}
                onOrderLinesUpdate={handleOrderLinesUpdate}
                externalVisibility={visibility}
                onVisibilityUpdate={setVisibility}
                onMaximize={() => setMaximizedTf(null)}
                isMaximized={true}
                onPriceUpdate={handlePriceUpdate}
              />
            </div>
          ) : (
            <div className="charts-grid">
              {['15min', '5min', '3min', '1min'].map(tf => (
                <div key={tf} className="grid-chart-item">
                  <ETradingChart
                    symbol={selectedSymbol}
                    initialInterval={tf}
                    isGrid={true}
                    sharedOrderLines={currentOrderLines}
                    onOrderLinesUpdate={handleOrderLinesUpdate}
                    externalVisibility={visibility}
                    onVisibilityUpdate={setVisibility}
                    onMaximize={() => setMaximizedTf(tf)}
                    isMaximized={false}
                    onPriceUpdate={handlePriceUpdate}
                  />
                </div>
              ))}
            </div>
          )}
        </main>
      </div>

      {showManage && (
        <ManageSymbols
          onClose={() => {
            setShowManage(false);
            fetchTrackedSymbols();
          }}
        />
      )}

      {showConfig && (
        <ChartConfig
          visibility={visibility}
          setVisibility={setVisibility}
          onClose={() => setShowConfig(false)}
        />
      )}
    </div>
  );
}

export default App;
