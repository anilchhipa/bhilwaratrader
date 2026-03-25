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

  const [breadth, setBreadth] = useState(null);
  const [prevBreadth, setPrevBreadth] = useState(null);

  useEffect(() => {
    const fetchAll = () => {
      axios.get(`${API_BASE}/api/monitor/all`)
        .then(res => {
          const { matrix, breadth: newBreadth } = res.data;

          setBreadth(prev => {
            if (prev) setPrevBreadth(prev);
            return newBreadth;
          });
          setIsOffline(false);

          // Matrix update with flip detection
          setSymbolPrices(prev => {
            const hasChanged = Object.keys(matrix).some(sym => {
              const prevSym = prev[sym] || {};
              const nextSym = matrix[sym];
              return prevSym.price !== nextSym.price ||
                JSON.stringify(prevSym.stTrendByTf) !== JSON.stringify(nextSym.stTrendByTf);
            });
            return hasChanged ? { ...prev, ...matrix } : prev;
          });

          // Tracked symbols from matrix keys
          const newTrackedSymbols = Object.keys(matrix);
          setTrackedSymbols(prev => (prev.length !== newTrackedSymbols.length || prev.some((s, i) => s !== newTrackedSymbols[i])) ? newTrackedSymbols : prev);

          if (!hasShownReadyPopup.current) {
            hasShownReadyPopup.current = true;
            setBackendReady(true);
            setShowReadyPopup(true);
            setTimeout(() => setShowReadyPopup(false), 5000);
          }
        })
        .catch(err => {
          console.error("Monitor poll fail", err);
          setIsOffline(true);
        });
    };

    fetchAll();
    const interval = setInterval(fetchAll, 2000); // 2000ms strictly respected now
    return () => clearInterval(interval);
  }, []); // Empty dependency array prevents the infinite polling loop


  return (
    <div className="app-container">
      <div className={`main-content ${mobileSidebarOpen ? 'sidebar-open' : ''}`}>
        {mobileSidebarOpen && (
          <div className="sidebar-backdrop" onClick={() => setMobileSidebarOpen(false)} />
        )}

        <button className="mobile-toggle-btn" onClick={() => setMobileSidebarOpen(!mobileSidebarOpen)} aria-label="Toggle Sidebar">
          {mobileSidebarOpen ? '✕' : '☰'}
        </button>

        <aside className={`sidebar ${mobileSidebarOpen ? 'mobile-show' : ''}`}>
          <div className="sidebar-header">
            <h3 className="outfit">Command Center</h3>
            <div className="header-actions">
              <button className="icon-action-btn" onClick={() => setShowConfig(true)} title="Indicators">📊</button>
              <button className="icon-action-btn" onClick={() => setShowManage(true)} title="Manage Symbols">⚙️</button>
            </div>
          </div>


          <div className="sidebar-scroll-area">
            <SignalMatrixList
              symbols={trackedSymbols}
              onSymbolSelect={(s) => { setSelectedSymbol(s); setMobileSidebarOpen(false); }}
              selectedSymbol={selectedSymbol}
              symbolPrices={symbolPrices}
            />
          </div>
        </aside>

        <main className="chart-section">
          {/* ... Chart rendering remains same ... */}
          {maximizedTf ? (
            <div className="maximized-chart-wrapper">
              <ETradingChart
                symbol={selectedSymbol}
                initialInterval={maximizedTf}
                isGrid={false}
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

      <BreadthWidget breadth={breadth} prevBreadth={prevBreadth} />

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
