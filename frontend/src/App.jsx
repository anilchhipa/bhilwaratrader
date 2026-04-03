import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import axios from 'axios';
import { API_BASE } from './config';
import CanvasTradingChart from './components/CanvasTradingChart';
import IndicatorsWidget from './components/IndicatorsWidget';
import ManageSymbolsWidget from './components/ManageSymbolsWidget';
import { formatSymbol } from './helpers/formatSymbol';
import { LiveFeedProvider } from './context/LiveFeedContext';

import './App.css';

function App() {
  // --- Core State (Scanner Only) ---
  const [trackedSymbols, setTrackedSymbols] = useState([]);
  const [scannerCategory, setScannerCategory] = useState('STOCKS');
  const [scannerTf, setScannerTf] = useState('3min');
  const [symbolPrices, setSymbolPrices] = useState({});
  const [isOffline, setIsOffline] = useState(false);
  const [appNotifications, setAppNotifications] = useState([]);
  const seenFlipsRef = useRef({});
  const scannerCategoryRef = useRef('STOCKS');
  const scannerTfRef = useRef('3min');

  const [pageIndex, setPageIndex] = useState(0);
  const [maximizedChart, setMaximizedChart] = useState(null);

  const [activeWidgets, setActiveWidgets] = useState({
    manage: false,
    indicators: false
  });

  const toggleWidget = (id) => setActiveWidgets(prev => ({ ...prev, [id]: !prev[id] }));

  const setScannerCategoryWithRef = (cat) => { scannerCategoryRef.current = cat; setScannerCategory(cat); };
  const setScannerTfWithRef = (tf) => { scannerTfRef.current = tf; setScannerTf(tf); };

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
      EMA15_15_15_15_30: true, CLOUD: true, CLOUD2: true
    };
    const saved = localStorage.getItem('chartVisibility');
    return saved ? { ...defaults, ...JSON.parse(saved) } : defaults;
  });

  const [themeColors, setThemeColors] = useState(() => {
    const saved = localStorage.getItem('themeColors');
    return saved ? JSON.parse(saved) : {
      bullish: '#3b82f6',
      bearish: '#ef4444',
      bullish2: '#a855f7',
      bearish2: '#f97316'
    };
  });

  // Inject CSS variables
  useEffect(() => {
    const root = document.documentElement;
    root.style.setProperty('--bullish-color', themeColors.bullish);
    root.style.setProperty('--bearish-color', themeColors.bearish);
    root.style.setProperty('--bullish2-color', themeColors.bullish2);
    root.style.setProperty('--bearish2-color', themeColors.bearish2);
    root.style.setProperty('--bullish-color-alpha', themeColors.bullish + '66');
    root.style.setProperty('--bearish-color-alpha', themeColors.bearish + '66');
    localStorage.setItem('themeColors', JSON.stringify(themeColors));
  }, [themeColors]);


  const fetchAll = useCallback(async () => {
    try {
      const res = await axios.get(`${API_BASE}/api/monitor/all`);
      const { matrix } = res.data;
      setIsOffline(false);

      setSymbolPrices(prev => ({ ...prev, ...matrix }));
      const newTrackedSymbols = Object.keys(matrix);
      setTrackedSymbols(newTrackedSymbols);

      // --- TREND FLIP DISCOVERY ---
      const discoveredFlips = [];
      Object.keys(matrix).forEach(sym => {
        const data = matrix[sym];
        const flips = data.lastFlipTimes || {};
        Object.keys(flips).forEach(tf => {
          const ct = flips[tf] ? new Date(flips[tf]).getTime() : 0;
          if (ct > 0) {
            const key = `${sym}-${tf}`;
            if (seenFlipsRef.current[key] === undefined) { seenFlipsRef.current[key] = ct; return; }
            if (ct > seenFlipsRef.current[key]) {
              seenFlipsRef.current[key] = ct;
              const trend = data.stTrendByTf?.[tf];
              discoveredFlips.push({ id: `${key}-${ct}`, sym, tf, trend, type: trend === 1 ? 'up' : 'dn' });
            }
          }
        });
      });

      if (discoveredFlips.length > 0) {
        setAppNotifications(prev => [...prev.slice(-3), ...discoveredFlips]);
        discoveredFlips.forEach(df => { setTimeout(() => setAppNotifications(prev => prev.filter(n => n.id !== df.id)), 8000); });
      }
    } catch (err) {
      console.error("Monitor poll fail", err);
      setIsOffline(true);
    }
  }, []);

  useEffect(() => {
    fetchAll();
    const interval = setInterval(fetchAll, 30000); // 30s Poll
    return () => clearInterval(interval);
  }, [fetchAll]);


  const scannerCategories = useMemo(() => {
    const list = trackedSymbols.filter(s => s !== 'SENSEX');
    const stocks = [], futures = [], calls = [], puts = [];

    list.forEach(s => {
      if (s.endsWith('FUT')) futures.push(s);
      else if (/\d+[CP]E$/.test(s)) {
        if (s.endsWith('CE')) calls.push(s);
        else puts.push(s);
      } else stocks.push(s);
    });

    return { STOCKS: stocks, FUTURES: futures, CALLS: calls, PUTS: puts };
  }, [trackedSymbols]);

  const scannerSlots = useMemo(() => {
    const list = scannerCategories[scannerCategory] || [];
    return list.map(s => ({ sym: s, tf: scannerTf }));
  }, [scannerCategories, scannerCategory, scannerTf]);

  const maxPages = Math.ceil(scannerSlots.length / 8);
  const currentScannerSlots = scannerSlots.slice(pageIndex * 8, (pageIndex + 1) * 8);

  useEffect(() => {
    setPageIndex(0);
  }, [scannerCategory, scannerTf]);



  return (
    <LiveFeedProvider>
      <div className="app-container">
        <div className="main-content">
          <main className="chart-section no-sidebar">
            <div className="scanner-view-container">
              <div className="scanner-header sticky-header">
                {/* Header Left: Logo */}
                <div className="header-left">
                  <div className="brand-logo" style={{ display: 'flex', alignItems: 'center', gap: '3px', userSelect: 'none' }}>
                    <span style={{ fontFamily: '"Inter", sans-serif', fontSize: '18px', fontWeight: '900', color: '#f8fafc', letterSpacing: '0.5px' }}>Bhilwara</span>
                    <span style={{ fontFamily: '"Inter", sans-serif', fontSize: '18px', fontWeight: '300', color: '#fbbf24', letterSpacing: '1px' }}>Trader</span>
                  </div>
                </div>

                {/* Header Center: Navigation & Categories */}
                <div className="header-center">
                  <div className="scanner-tf-selector" style={{ margin: 0 }}>
                    {['1min', '3min', '5min', '15min'].map(tf => (
                      <button
                        key={tf}
                        className={`tf-tab ${scannerTf === tf ? 'active' : ''}`}
                        onClick={() => setScannerTfWithRef(tf)}
                      >
                        {tf.toUpperCase()}
                      </button>
                    ))}
                  </div>
                  <div className="scanner-category-tabs" style={{ margin: 0 }}>
                    {Object.keys(scannerCategories).map(cat => (
                      <button
                        key={cat}
                        className={`cat-tab ${scannerCategory === cat ? 'active' : ''}`}
                        onClick={() => setScannerCategoryWithRef(cat)}
                      >
                        {cat} ({scannerCategories[cat].length})
                      </button>
                    ))}
                  </div>
                </div>

                {/* Header Right: Pagination & Tools */}
                <div className="header-right">
                  <div className="scanner-tf-selector" style={{ margin: 0, width: '140px', justifyContent: 'space-between' }}>
                    <button disabled={pageIndex === 0} onClick={() => setPageIndex(p => p - 1)} className="tf-tab" style={{ cursor: pageIndex === 0 ? 'not-allowed' : 'pointer', opacity: pageIndex === 0 ? 0.3 : 1, padding: '4px', minWidth: '24px' }}>◀</button>
                    <span style={{ color: '#cbd5e1', fontSize: '10px', fontWeight: '900', display: 'flex', alignItems: 'center', justifyContent: 'center', letterSpacing: '0.5px', flex: 1 }}>PAGE {pageIndex + 1} / {maxPages || 1}</span>
                    <button disabled={pageIndex >= maxPages - 1} onClick={() => setPageIndex(p => p + 1)} className="tf-tab" style={{ cursor: pageIndex >= maxPages - 1 ? 'not-allowed' : 'pointer', opacity: pageIndex >= maxPages - 1 ? 0.3 : 1, padding: '4px', minWidth: '24px' }}>▶</button>
                  </div>

                  {isOffline && <div className="connection-error-pill">⚠️ BACKEND OFFLINE</div>}

                  <div className="scanner-tf-selector" style={{ margin: 0 }}>
                    <button
                      className={`tf-tab ${activeWidgets.manage ? 'active' : ''}`}
                      onClick={() => toggleWidget('manage')}
                      title="Manage Symbols"
                      style={{ padding: '4px', minWidth: '28px', display: 'flex', justifyContent: 'center', alignItems: 'center' }}
                    >
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
                    </button>
                    <button
                      className={`tf-tab ${activeWidgets.indicators ? 'active' : ''}`}
                      onClick={() => toggleWidget('indicators')}
                      title="Indicators Settings"
                      style={{ padding: '4px', minWidth: '28px', display: 'flex', justifyContent: 'center', alignItems: 'center' }}
                    >
                      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
                    </button>
                  </div>
                </div>
              </div>

              <div className="charts-grid matrix-2x4">
                {currentScannerSlots.map((slot, idx) => (
                  <div key={`${slot.sym}-${idx}`} className="grid-chart-item">
                    <CanvasTradingChart
                      symbol={slot.sym}
                      initialInterval={scannerTf}
                      isGrid={true}
                      externalVisibility={visibility}
                      onVisibilityUpdate={setVisibility}
                      onMaximize={(tf) => setMaximizedChart({ sym: slot.sym, tf })}
                      isMaximized={false}
                      onPriceUpdate={handlePriceUpdate}
                      themeColors={themeColors}
                      symbolPrices={symbolPrices}
                    />
                  </div>
                ))}
                {/* Empty Placeholders to keep structure */}
                {Array.from({ length: Math.max(0, 8 - currentScannerSlots.length) }).map((_, i) => {
                  const chartNumber = currentScannerSlots.length + i + 1;
                  return (
                    <div key={`placeholder-${i}`} className="grid-chart-item placeholder-slot" style={{
                      border: '1px solid rgba(255,255,255,0.03)',
                      background: 'linear-gradient(rgba(255,255,255,0.01) 1px, transparent 1px) 0 0 / 25px 25px, linear-gradient(90deg, rgba(255,255,255,0.01) 1px, transparent 1px) 0 0 / 25px 25px, #0a0f1d',
                      display: 'flex',
                      justifyContent: 'center',
                      alignItems: 'center'
                    }}>
                      <div className="placeholder-content" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '8px', opacity: 0.15 }}>
                        <span style={{ fontSize: '11px', fontWeight: '900', letterSpacing: '2px', color: '#94a3b8' }}>CHART {chartNumber}</span>
                        <div style={{ width: '40px', height: '1px', background: 'rgba(148, 163, 184, 0.3)' }}></div>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>
          </main>
        </div>

        {/* The Action Hub was moved to the scanner header */}

        <IndicatorsWidget
          isOpen={activeWidgets.indicators}
          onClose={() => toggleWidget('indicators')}
          visibility={visibility}
          setVisibility={setVisibility}
          themeColors={themeColors}
          setThemeColors={setThemeColors}
        />

        <ManageSymbolsWidget
          isOpen={activeWidgets.manage}
          onClose={() => toggleWidget('manage')}
          trackedData={symbolPrices}
          tracked={trackedSymbols}
          onUpdate={fetchAll}
        />

        {/* Global Notifications */}
        <div className="global-toast-container">
          {appNotifications.map(n => (
            <div key={n.id} className={`app-toast toast-${n.type}`} style={{ cursor: 'pointer' }} onClick={() => setMaximizedChart({ sym: n.sym, tf: n.tf })}>
              <div className="toast-icon">{n.type === 'up' ? '🚀' : '🔻'}</div>
              <div className="toast-body" style={{ display: 'flex', flexDirection: 'column' }}>
                <span className="toast-title">{formatSymbol(n.sym)} ({n.tf})</span>
                <span className="toast-msg">SuperTrend flipped {n.type === 'up' ? 'Bullish' : 'Bearish'}</span>
                <span style={{ fontSize: '9px', color: '#60a5fa', marginTop: '4px', fontWeight: 'bold' }}>CLICK TO MAXIMIZE</span>
              </div>
            </div>
          ))}
        </div>

        {/* Maximized Chart Overlay */}
        {maximizedChart && (
          <div className="manage-symbols-modal full-overlay" style={{ zIndex: 9999 }}>
            <div className="modal-content" style={{ width: '95%', height: '90vh', maxWidth: 'none', background: '#0a0f1d' }}>
              <div className="modal-header" style={{ padding: '16px 24px', background: '#020617', borderBottom: '1px solid rgba(255,255,255,0.1)' }}>
                <div className="title-area">
                  <h2 style={{ fontSize: '18px', display: 'flex', alignItems: 'center' }}>{formatSymbol(maximizedChart.sym)} <span style={{ color: '#3b82f6', fontSize: '12px', marginLeft: '12px', background: 'rgba(59,130,246,0.1)', padding: '2px 8px', borderRadius: '4px' }}>{maximizedChart.tf.toUpperCase()}</span></h2>
                </div>
                <button
                  onClick={() => setMaximizedChart(null)}
                  style={{
                    border: 'none',
                    background: '#ef4444',
                    color: '#fff',
                    width: '32px',
                    height: '32px',
                    borderRadius: '50%',
                    display: 'flex',
                    justifyContent: 'center',
                    alignItems: 'center',
                    cursor: 'pointer',
                    boxShadow: '0 4px 10px rgba(239, 68, 68, 0.3)',
                    transition: 'transform 0.2s'
                  }}
                  onMouseEnter={(e) => e.target.style.transform = 'scale(1.1)'}
                  onMouseLeave={(e) => e.target.style.transform = 'scale(1)'}
                >✕</button>
              </div>
              <div className="maximized-chart-wrapper" style={{ flex: 1, position: 'relative' }}>
                <CanvasTradingChart
                  symbol={maximizedChart.sym}
                  initialInterval={maximizedChart.tf}
                  isGrid={false}
                  externalVisibility={visibility}
                  onVisibilityUpdate={setVisibility}
                  onMaximize={() => setMaximizedChart(null)}
                  onIntervalChange={(newTf) => setMaximizedChart(p => ({ ...p, tf: newTf }))}
                  isMaximized={true}
                  onPriceUpdate={handlePriceUpdate}
                  themeColors={themeColors}
                  symbolPrices={symbolPrices}
                />
              </div>
            </div>
          </div>
        )}

      </div>
    </LiveFeedProvider>
  );
}

export default App;
