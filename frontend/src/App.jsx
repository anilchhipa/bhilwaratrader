import React, { useState, useEffect } from 'react';
import axios from 'axios';
import { API_BASE } from './config';
import ETradingChart from './components/ETradingChart';
import SymbolList from './components/SymbolList';
import ManageSymbols from './components/ManageSymbols';
import ChartConfig from './components/ChartConfig';

import './App.css';

function App() {
  const [selectedSymbol, setSelectedSymbol] = useState('RELIANCE');
  const [showManage, setShowManage] = useState(false);
  const [showConfig, setShowConfig] = useState(false);
  const [trackedSymbols, setTrackedSymbols] = useState([]);
  const [maximizedTf, setMaximizedTf] = useState(null);

  // Visibility State for Technical Indicators
  const [visibility, setVisibility] = useState(() => {
    const saved = localStorage.getItem('chartVisibility');
    return saved ? JSON.parse(saved) : {
      EMA15: true, EMA15_5: true, EMA15_15: true, EMA15_15_15: true, EMA15_15_15_15: true,
      EMA15_15_15_15_30: true, CLOUD: true,
      EMA50: true, EMA100: true,
      MACD: true, SIGNAL: true, MACD5: true, MACD55: true,
      "1012_OPEN_CLOSE": true
    };
  });

  const fetchTrackedSymbols = () => {
    axios.get(`${API_BASE}/api/symbols`)
      .then(res => setTrackedSymbols(Object.keys(res.data)))
      .catch(err => console.error("Error fetching symbols", err));
  };

  useEffect(() => {
    fetchTrackedSymbols();
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
        {/* Glass Sidebar */}
        <aside className="sidebar">
          <div className="sidebar-header">
            <h3 className="outfit">Command Center</h3>
          </div>

          <div className="symbol-list-container">
            <SymbolList
              symbols={trackedSymbols}
              onSymbolSelect={setSelectedSymbol}
              selectedSymbol={selectedSymbol}
            />
          </div>

          <div className="sidebar-footer">
            <button className="action-btn" onClick={() => setShowConfig(true)}>
              <span style={{ fontSize: '18px' }}>📊</span>
              <span>Indicators</span>
            </button>
            <button className="action-btn" onClick={() => setShowManage(true)}>
              <span style={{ fontSize: '18px' }}>⚙️</span>
              <span>Manage Symbols</span>
            </button>
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
