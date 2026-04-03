import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import axios from 'axios';
import { API_BASE } from './config';
import CanvasTradingChart from './components/CanvasTradingChart';
import IndicatorsWidget from './components/IndicatorsWidget';
import ManageSymbolsWidget from './components/ManageSymbolsWidget';
import { formatSymbol } from './helpers/formatSymbol';

import CommandCenterWidget from './components/CommandCenterWidget';
import { LiveFeedProvider } from './context/LiveFeedContext';

import './App.css';

function App() {
  const [selectedSymbol, setSelectedSymbol] = useState('RELIANCE');
  const [trackedSymbols, setTrackedSymbols] = useState([]);
  const [maximizedTf, setMaximizedTf] = useState(null);
  const [viewMode, setViewMode] = useState('focus'); // 'focus' or 'scanner'
  const [scannerPage, setScannerPage] = useState(0);
  const [scannerCategory, setScannerCategory] = useState('STOCKS');
  const [scannerTf, setScannerTf] = useState('3min');
  const [symbolPrices, setSymbolPrices] = useState({});
  const [backendReady, setBackendReady] = useState(false);
  const [showReadyPopup, setShowReadyPopup] = useState(false);
  const [isOffline, setIsOffline] = useState(false);
  const [appNotifications, setAppNotifications] = useState([]);
  const seenFlipsRef = useRef({}); // symbol-tf -> timestamp

  const [activeWidgets, setActiveWidgets] = useState({
    command: true,
    manage: false,
    indicators: false
  });

  const toggleWidget = (id) => setActiveWidgets(prev => ({ ...prev, [id]: !prev[id] }));

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

  // Inject CSS variables for real-time updates across the app
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
      // 1. Fetch main matrix (now includes SENSEX if backend is restarted)
      const res = await axios.get(`${API_BASE}/api/monitor/all`);
      const { matrix } = res.data;
      setIsOffline(false);

      // 2. Fetch SENSEX state independently (fallback for old backend or missing data)
      const sRes = await axios.get(`${API_BASE}/api/state/SENSEX`);
      const indMap = sRes.data?.indicatorValues || {};
      const sTrends = { stTrendByTf: {}, stTrend2ByTf: {} };
      ['1min', '3min', '5min', '15min'].forEach(tf => {
        const v = indMap[tf] || {};
        const st1 = v.ST_TREND != null ? Math.round(v.ST_TREND) : null;
        sTrends.stTrendByTf[tf] = st1;
        sTrends.stTrend2ByTf[tf] = v.ST_TREND2 != null ? Math.round(v.ST_TREND2) : st1;
      });

      const mergedMatrix = { ...matrix, SENSEX: { ...(matrix.SENSEX || {}), ...sTrends } };

      setSymbolPrices(prev => {
        const hasChanged = Object.keys(mergedMatrix).some(sym => {
          const prevSym = prev[sym] || {};
          const nextSym = mergedMatrix[sym];
          return prevSym.price !== nextSym.price ||
            JSON.stringify(prevSym.stTrendByTf) !== JSON.stringify(nextSym.stTrendByTf);
        });
        return hasChanged ? { ...prev, ...mergedMatrix } : prev;
      });

      const newTrackedSymbols = Object.keys(matrix);
      setTrackedSymbols(prev => (prev.length !== newTrackedSymbols.length || prev.some((s, i) => s !== newTrackedSymbols[i])) ? newTrackedSymbols : prev);

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
              const trend = data.stTrendByTf[tf];
              discoveredFlips.push({ id: `${key}-${ct}`, sym, tf, trend, type: trend === 1 ? 'up' : 'dn' });
            }
          }
        });
      });

      if (discoveredFlips.length > 0) {
        setAppNotifications(prev => [...prev.slice(-3), ...discoveredFlips]);
        discoveredFlips.forEach(df => { setTimeout(() => setAppNotifications(prev => prev.filter(n => n.id !== df.id)), 8000); });
      }

      if (!hasShownReadyPopup.current) {
        hasShownReadyPopup.current = true;
        setBackendReady(true);
        setShowReadyPopup(true);
        setTimeout(() => setShowReadyPopup(false), 5000);
      }
    } catch (err) {
      console.error("Monitor poll fail", err);
      setIsOffline(true);
    }
  }, []);

  useEffect(() => {
    fetchAll();
    const interval = setInterval(fetchAll, 10000); // 10s poll - balanced for 1m TF
    return () => clearInterval(interval);
  }, [fetchAll]);


  const handleMaximize = useCallback((tf) => setMaximizedTf(prev => prev === tf ? null : tf), []);

  const chartSlots = useMemo(() => [
    { sym: selectedSymbol, tf: '15min' },
    { sym: selectedSymbol, tf: '5min' },
    { sym: selectedSymbol, tf: '3min' },
    { sym: selectedSymbol, tf: '1min' },
    { sym: 'SENSEX', tf: '15min' },
    { sym: 'SENSEX', tf: '5min' },
    { sym: 'SENSEX', tf: '3min' },
    { sym: 'SENSEX', tf: '1min' },
  ], [selectedSymbol]);

  const totalScannerPages = Math.ceil(trackedSymbols.filter(s => s !== 'SENSEX').length / 8);

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
    const start = scannerPage * 8;
    const chunk = list.slice(start, start + 8);
    return chunk.map(s => ({ sym: s, tf: scannerTf }));
  }, [scannerCategories, scannerCategory, scannerPage, scannerTf]);

  const categoryTotalPages = useMemo(() => {
    const list = scannerCategories[scannerCategory] || [];
    return Math.max(1, Math.ceil(list.length / 8));
  }, [scannerCategories, scannerCategory]);

  return (
    <LiveFeedProvider>
      <div className="app-container">
        <div className="main-content">
          <CommandCenterWidget
            isOpen={activeWidgets.command}
            onClose={() => toggleWidget('command')}
            trackedSymbols={trackedSymbols.filter(s => s !== 'SENSEX')}
            selectedSymbol={selectedSymbol}
            onSymbolSelect={setSelectedSymbol}
            symbolPrices={symbolPrices}
            toggleWidget={toggleWidget}
            activeWidgets={activeWidgets}
            viewMode={viewMode}
            setViewMode={setViewMode}
          />
          <main className="chart-section">
            {maximizedTf ? (
              <div className="maximized-chart-wrapper">
                <CanvasTradingChart
                  symbol={selectedSymbol}
                  initialInterval={maximizedTf}
                  isGrid={false}
                  externalVisibility={visibility}
                  onVisibilityUpdate={setVisibility}
                  onMaximize={handleMaximize}
                  isMaximized={true}
                  onPriceUpdate={handlePriceUpdate}
                  themeColors={themeColors}
                  symbolPrices={symbolPrices}
                />
              </div>
            ) : viewMode === 'scanner' ? (
              <div className="scanner-view-container">
                <div className="scanner-header">
                  <div className="scanner-info">
                    <span className="scanner-pill">{scannerTf.toUpperCase()} SCANNER</span>
                    <div className="scanner-tf-selector">
                      {['1min', '3min', '5min', '15min'].map(tf => (
                        <button
                          key={tf}
                          className={`tf-tab ${scannerTf === tf ? 'active' : ''}`}
                          onClick={() => setScannerTf(tf)}
                        >
                          {tf === '1min' ? '1M' : tf === '3min' ? '3M' : tf === '5min' ? '5M' : '15M'}
                        </button>
                      ))}
                    </div>
                    <div className="scanner-category-tabs">
                      {Object.keys(scannerCategories).map(cat => (
                        <button
                          key={cat}
                          className={`cat-tab ${scannerCategory === cat ? 'active' : ''}`}
                          onClick={() => { setScannerCategory(cat); setScannerPage(0); }}
                        >
                          {cat} ({scannerCategories[cat].length})
                        </button>
                      ))}
                    </div>
                  </div>
                  <div className="scanner-nav">
                    <span className="scanner-page-indicator">PAGE {scannerPage + 1} / {categoryTotalPages}</span>
                    <button className="nav-btn" onClick={() => setScannerPage(p => Math.max(0, p - 1))} disabled={scannerPage === 0} title="Previous Page">◀</button>
                    <button className="nav-btn" onClick={() => setScannerPage(p => Math.min(categoryTotalPages - 1, p + 1))} disabled={scannerPage >= categoryTotalPages - 1} title="Next Page">▶</button>
                    <button className="nav-close" onClick={() => setViewMode('focus')} title="Exit Scanner">✕</button>
                  </div>
                </div>
                <div className="charts-grid matrix-2x4">
                  {scannerSlots.map((slot, idx) => (
                    <div key={`${slot.sym}-${idx}`} className="grid-chart-item">
                      <CanvasTradingChart
                        symbol={slot.sym}
                        initialInterval={scannerTf}
                        isGrid={true}
                        externalVisibility={visibility}
                        onVisibilityUpdate={setVisibility}
                        onMaximize={handleMaximize}
                        isMaximized={false}
                        onPriceUpdate={handlePriceUpdate}
                        themeColors={themeColors}
                        symbolPrices={symbolPrices}
                      />
                    </div>
                  ))}
                  {Array.from({ length: Math.max(0, 8 - scannerSlots.length) }).map((_, i) => (
                    <div key={`empty-${i}`} className="grid-chart-item empty-slot">
                      <div className="empty-slot-content">CHART {scannerSlots.length + i + 1}</div>
                    </div>
                  ))}
                </div>
              </div>
            ) : (
              <div className="charts-grid matrix-2x4">
                {chartSlots.map((slot, idx) => (
                  <div key={`${slot.sym}-${slot.tf}-${idx}`} className="grid-chart-item">
                    <CanvasTradingChart
                      symbol={slot.sym}
                      initialInterval={slot.tf}
                      isGrid={true}
                      externalVisibility={visibility}
                      onVisibilityUpdate={setVisibility}
                      onMaximize={handleMaximize}
                      isMaximized={false}
                      onPriceUpdate={handlePriceUpdate}
                      themeColors={themeColors}
                      symbolPrices={symbolPrices}
                    />
                  </div>
                ))}
              </div>
            )}
          </main>
        </div>
        {!activeWidgets.command && (
          <button className="sidebar-restore-handle" onClick={() => toggleWidget('command')} title="Expand Command Center">
            🕹️
          </button>
        )}


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


        {/* Global Alert Discovery Toasts */}
        <div className="global-toast-container">
          {appNotifications.map(n => (
            <div key={n.id} className={`app-toast toast-${n.type}`}>
              <div className="toast-icon">{n.type === 'up' ? '🚀' : '🔻'}</div>
              <div className="toast-body">
                <span className="toast-title">{formatSymbol(n.sym)} ({n.tf})</span>
                <span className="toast-msg">SuperTrend flipped {n.type === 'up' ? 'Bullish' : 'Bearish'}</span>
              </div>
              <button className="toast-action" onClick={() => {
                setSelectedSymbol(n.sym);
                setAppNotifications(prev => prev.filter(nn => nn.id !== n.id));
              }}>GO</button>
            </div>
          ))}
        </div>
      </div>
    </LiveFeedProvider>
  );
}

export default App;
