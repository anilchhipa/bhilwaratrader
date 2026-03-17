import React, { useState, useEffect, useCallback } from 'react';
import axios from 'axios';
import { API_BASE } from './config';
import ETradingChart from './components/ETradingChart';
import SymbolList from './components/SymbolList';
import ManageSymbols from './components/ManageSymbols';
import ChartConfig from './components/ChartConfig';
import BreadthWidget from './components/BreadthWidget';
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
  const [mobileSidebarOpen, setMobileSidebarOpen] = useState(false);

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
    const defaults = {
      EMA15: true, EMA15_5: true, EMA15_15: true, EMA15_15_15: true, EMA15_15_15_15: true,
      EMA15_15_15_15_30: true, CLOUD: true,
      "1012_OPEN_CLOSE": true
    };
    const saved = localStorage.getItem('chartVisibility');
    return saved ? { ...defaults, ...JSON.parse(saved) } : defaults;
  });

  const [themeColors, setThemeColors] = useState(() => {
    const saved = localStorage.getItem('themeColors');
    return saved ? JSON.parse(saved) : {
      bullish: '#3b82f6',
      bearish: '#ef4444'
    };
  });

  // Inject CSS variables for real-time updates across the app
  useEffect(() => {
    const root = document.documentElement;
    root.style.setProperty('--bullish-color', themeColors.bullish);
    root.style.setProperty('--bearish-color', themeColors.bearish);
    // Add semi-transparent versions for shading
    root.style.setProperty('--bullish-color-alpha', themeColors.bullish + '66'); // 0.4 alpha
    root.style.setProperty('--bearish-color-alpha', themeColors.bearish + '66'); // 0.4 alpha
    localStorage.setItem('themeColors', JSON.stringify(themeColors));
  }, [themeColors]);

  const fetchTrackedSymbols = () => {
    axios.get(`${API_BASE}/api/matrix`)
      .then(res => {
        // Show popup ONLY ONCE per page load
        if (!hasShownReadyPopup.current) {
          hasShownReadyPopup.current = true;
          setBackendReady(true);
          setShowReadyPopup(true);
          setTimeout(() => setShowReadyPopup(false), 5000);
        }
        setIsOffline(false);

        const matrixData = res.data;
        const newTrackedSymbols = Object.keys(matrixData);

        // Only update trackedSymbols if the list has changed to avoid unnecessary re-renders
        setTrackedSymbols(prev => {
          if (prev.length !== newTrackedSymbols.length || prev.some((s, i) => s !== newTrackedSymbols[i])) {
            return newTrackedSymbols;
          }
          return prev;
        });

        // The backend now provides exactly what the matrix needs
        setSymbolPrices(prev => {
          // Check if anything actually changed before updating state
          // (Simple optimization to skip state update if data is identical)
          const hasChanged = Object.keys(matrixData).some(sym => {
            const prevSym = prev[sym] || {};
            const nextSym = matrixData[sym];
            return prevSym.price !== nextSym.price ||
              JSON.stringify(prevSym.stTrendByTf) !== JSON.stringify(nextSym.stTrendByTf);
          });

          if (hasChanged) {
            return { ...prev, ...matrixData };
          }
          return prev;
        });
      })
      .catch(err => {
        console.error("Error fetching matrix", err);
        setIsOffline(true);
      });
  };

  useEffect(() => {
    fetchTrackedSymbols();
    const interval = setInterval(() => {
      fetchTrackedSymbols();
    }, 2000);
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
      <div className={`main-content ${mobileSidebarOpen ? 'sidebar-open' : ''}`}>
        {mobileSidebarOpen && (
          <div
            className="sidebar-backdrop"
            onClick={() => setMobileSidebarOpen(false)}
          />
        )}
        <button
          className="mobile-toggle-btn"
          onClick={() => setMobileSidebarOpen(!mobileSidebarOpen)}
          aria-label="Toggle Sidebar"
        >
          {mobileSidebarOpen ? '✕' : '☰'}
        </button>
        {/* Glass Sidebar */}
        {/* Unified Glass Sidebar */}
        <aside className={`sidebar ${mobileSidebarOpen ? 'mobile-show' : ''}`}>
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
              onSymbolSelect={(s) => {
                setSelectedSymbol(s);
                setMobileSidebarOpen(false); // Close on mobile after selection
              }}
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
                themeColors={themeColors}
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
                    themeColors={themeColors}
                  />
                </div>
              ))}
            </div>
          )}
        </main>
      </div>

      <BreadthWidget />

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
          themeColors={themeColors}
          setThemeColors={setThemeColors}
          onClose={() => setShowConfig(false)}
        />
      )}
    </div>
  );
}

export default App;
