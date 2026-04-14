import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import axios from 'axios';
import { API_BASE } from './config';
import CanvasTradingChart from './components/CanvasTradingChart';
import IndicatorsWidget from './components/IndicatorsWidget';
import ManageSymbolsWidget from './components/ManageSymbolsWidget';
import { formatSymbol } from './helpers/formatSymbol';
import { LiveFeedProvider } from './context/LiveFeedContext';
import SymbolSearch from './components/SymbolSearch';

import './App.css';

function App() {
  // --- Core State (Scanner Only) ---
  const [trackedSymbols, setTrackedSymbols] = useState([]);
  const [scannerCategory, setScannerCategory] = useState('STOCKS');
  const [scannerTf, setScannerTf] = useState('3min');
  const [symbolPrices, setSymbolPrices] = useState({});
  const [isOffline, setIsOffline] = useState(false);
  const scannerCategoryRef = useRef('STOCKS');
  const scannerTfRef = useRef('3min');

  const [pageIndex, setPageIndex] = useState(0);
  const [maximizedChart, setMaximizedChart] = useState(null);
  const [highlightedSymbol, setHighlightedSymbol] = useState(null);
  const [gridLayout, setGridLayout] = useState(() => localStorage.getItem('scanner_grid_layout') || '2x4');
  const [isZenMode, setIsZenMode] = useState(false);

  const [activeWidgets, setActiveWidgets] = useState({
    manage: false,
    indicators: false
  });
  const [globalSearchQuery, setGlobalSearchQuery] = useState('');
  const [isSearchOpen, setIsSearchOpen] = useState(false);

  // --- Harmonic Sync Logic (1, 3, 5, 15 min alignment) ---
  const [syncSeconds, setSyncSeconds] = useState(0);
  useEffect(() => {
    const update = () => {
      const now = new Date();
      const totalSecs = now.getMinutes() * 60 + now.getSeconds();
      const nextSync = Math.ceil((totalSecs + 1) / 900) * 900;
      setSyncSeconds(nextSync - totalSecs);
    };
    update();
    const inv = setInterval(update, 1000);
    return () => clearInterval(inv);
  }, []);

  const toggleWidget = (id) => setActiveWidgets(prev => ({ ...prev, [id]: !prev[id] }));

  const setScannerCategoryWithRef = useCallback((cat) => { scannerCategoryRef.current = cat; setScannerCategory(cat); }, []);
  const setScannerTfWithRef = useCallback((tf) => { scannerTfRef.current = tf; setScannerTf(tf); }, []);

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
    const list = trackedSymbols;
    const stocks = [], futures = [], calls = [], puts = [], indices = [];

    const extractStrike = (sym) => {
      const match = sym.match(/(\d+)[CP]E$/);
      return match ? parseInt(match[1], 10) : 0;
    };

    list.forEach(s => {
      if (s === 'SENSEX' || s === 'NIFTY 50' || s === 'BANKNIFTY' || s === 'FINNIFTY') indices.push(s);
      else if (s.endsWith('FUT')) futures.push(s);
      else if (/\d+[CP]E$/.test(s)) {
        if (s.endsWith('CE')) calls.push(s);
        else puts.push(s);
      } else stocks.push(s);
    });

    calls.sort((a, b) => extractStrike(a) - extractStrike(b));
    puts.sort((a, b) => extractStrike(a) - extractStrike(b));

    return { INDEX: indices, STOCKS: stocks, FUTURES: futures, CALLS: calls, PUTS: puts };
  }, [trackedSymbols]);

  const scannerSlots = useMemo(() => {
    const list = scannerCategories[scannerCategory] || [];
    return list.map(s => ({ sym: s, tf: scannerTf }));
  }, [scannerCategories, scannerCategory, scannerTf]);

  const gridSizes = { '2x2': 4, '2x3': 6, '2x4': 8 };
  const gridSize = gridSizes[gridLayout] || 8;

  const maxPages = Math.ceil(scannerSlots.length / gridSize);
  const currentScannerSlots = scannerSlots.slice(pageIndex * gridSize, (pageIndex + 1) * gridSize);

  // Page index is now managed manually to avoid race conditions with symbol jumping

  const jumpToSymbol = useCallback((symbol) => {
    // 0. If in maximized mode, we might want to just replace the maximized symbol or stay focused
    if (maximizedChart) {
      setMaximizedChart({ sym: symbol, tf: maximizedChart.tf });
      return;
    }

    // 1. Identify which category this symbol belongs to
    let foundCat = 'STOCKS';
    for (const cat in scannerCategories) {
      if (scannerCategories[cat].includes(symbol)) {
        foundCat = cat;
        break;
      }
    }

    // 2. Switch category + ref
    setScannerCategoryWithRef(foundCat);

    // 3. Find index within that category to set correct page
    const list = scannerCategories[foundCat];
    const idx = list.indexOf(symbol);
    if (idx >= 0) {
      const page = Math.floor(idx / gridSize);
      setPageIndex(page);
    }

    // 4. Flash/Highlight the chart for visual identification
    setHighlightedSymbol(symbol);
    setTimeout(() => setHighlightedSymbol(null), 5000);
  }, [scannerCategories, maximizedChart, gridSize]);



  // Keyboard Navigation
  useEffect(() => {
    const handleKeyDown = (e) => {
      // Escape works globally
      if (e.key === 'Escape' || e.key === 'Esc' || e.keyCode === 27) {
        setMaximizedChart(null);
        // Also close widgets if needed
        return;
      }

      // Ignore if user is typing in an input or textarea
      if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return;

      switch (e.key) {
        case 'ArrowLeft':
          setPageIndex(p => Math.max(0, p - 1));
          break;
        case 'ArrowRight':
          setPageIndex(p => Math.min(Math.max(0, maxPages - 1), p + 1));
          break;
        case 's': case 'S':
          if (scannerCategories['STOCKS']) { setScannerCategoryWithRef('STOCKS'); setPageIndex(0); }
          break;
        case 'f': case 'F':
          if (scannerCategories['FUTURES']) { setScannerCategoryWithRef('FUTURES'); setPageIndex(0); }
          break;
        case 'c': case 'C':
          if (scannerCategories['CALLS']) { setScannerCategoryWithRef('CALLS'); setPageIndex(0); }
          break;
        case 'p': case 'P':
          if (scannerCategories['PUTS']) { setScannerCategoryWithRef('PUTS'); setPageIndex(0); }
          break;
        case 'i': case 'I':
          if (scannerCategories['INDEX']) { setScannerCategoryWithRef('INDEX'); setPageIndex(0); }
          break;
        case 'g': case 'G':
          setGridLayout(prev => {
            const layouts = ['2x2', '2x3', '2x4'];
            const nextIdx = (layouts.indexOf(prev) + 1) % layouts.length;
            const nextLayout = layouts[nextIdx];
            localStorage.setItem('scanner_grid_layout', nextLayout);
            return nextLayout;
          });
          setPageIndex(0);
          break;
        case 'ArrowUp': {
          const currentZoom = parseInt(localStorage.getItem('chart_zoom_global')) || 160;
          const newZoom = Math.max(20, Math.round(currentZoom * 0.8)); // zoom in (fewer bars)
          localStorage.setItem('chart_zoom_global', newZoom);
          ['1min', '3min', '5min', '15min'].forEach(tf => localStorage.setItem(`chart_zoom_${tf}`, newZoom));
          const bc = new BroadcastChannel('grid_chart_sync');
          bc.postMessage({ type: 'SYNC_ZOOM', count: newZoom });
          bc.close();
          break;
        }
        case 'ArrowDown': {
          const currentZoom = parseInt(localStorage.getItem('chart_zoom_global')) || 160;
          const newZoom = Math.min(800, Math.round(currentZoom * 1.25)); // zoom out (more bars)
          localStorage.setItem('chart_zoom_global', newZoom);
          ['1min', '3min', '5min', '15min'].forEach(tf => localStorage.setItem(`chart_zoom_${tf}`, newZoom));
          const bc = new BroadcastChannel('grid_chart_sync');
          bc.postMessage({ type: 'SYNC_ZOOM', count: newZoom });
          bc.close();
          break;
        }
        case '1':
          setScannerTfWithRef('1min');
          setPageIndex(0);
          break;
        case '2':
          setScannerTfWithRef('3min');
          setPageIndex(0);
          break;
        case '3':
          setScannerTfWithRef('5min');
          setPageIndex(0);
          break;
        case '4':
          setScannerTfWithRef('15min');
          setPageIndex(0);
          break;
        case 't': case 'T': {
          const tfs = ['1min', '3min', '5min', '15min'];
          const currentTf = scannerTfRef.current || '3min';
          const nextIndex = (tfs.indexOf(currentTf) + 1) % tfs.length;
          setScannerTfWithRef(tfs[nextIndex]);
          setPageIndex(0);
          break;
        }
        case 'z': case 'Z':
          setIsZenMode(prev => !prev);
          break;
        case 'Escape':
          setMaximizedChart(null);
          break;
        default:
          break;
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [maxPages, setScannerCategoryWithRef, scannerCategories]);

  return (
    <LiveFeedProvider>
      <div className="app-container">
        <div className="main-content">
          <main className="chart-section no-sidebar">
            <div className="scanner-view-container">
              <div className="scanner-header sticky-header" style={{ display: isZenMode ? 'none' : 'flex' }}>
                {/* Header Left: Logo */}
                <div className="header-left">
                  <div className="brand-logo" style={{ display: 'flex', alignItems: 'center', gap: '0px', userSelect: 'none' }}>
                    <span style={{ fontFamily: '"Dancing Script", cursive', fontSize: '22px', fontWeight: '700', color: '#f8fafc', letterSpacing: '0.5px' }}>Bhilwara</span>
                    <span style={{ fontFamily: '"Dancing Script", cursive', fontSize: '22px', fontWeight: '700', color: '#fbbf24', letterSpacing: '0.5px', marginLeft: '6px' }}>trader</span>
                  </div>
                </div>

                {/* Header Center: Navigation & Categories */}
                <div className="header-center">
                  {!maximizedChart && (
                    <div style={{ display: 'flex', gap: '8px' }}>
                      <div className="scanner-tf-selector" style={{ margin: 0 }}>
                        {['1min', '3min', '5min', '15min'].map(tf => (
                          <button
                            key={tf}
                            className={`tf-tab ${scannerTf === tf ? 'active' : ''}`}
                            onClick={() => { setScannerTfWithRef(tf); setPageIndex(0); }}
                          >
                            {tf.toUpperCase()}
                          </button>
                        ))}
                      </div>
                    </div>
                  )}
                  <div className="scanner-category-tabs" style={{ margin: 0 }}>
                    {Object.keys(scannerCategories).map(cat => (
                      <button
                        key={cat}
                        className={`cat-tab ${scannerCategory === cat ? 'active' : ''}`}
                        onClick={() => { setScannerCategoryWithRef(cat); setPageIndex(0); }}
                      >
                        {cat} ({scannerCategories[cat].length})
                      </button>
                    ))}
                  </div>
                </div>

                {/* Header Right: Pagination & Tools */}
                <div className="header-right">
                  {/* Sync Indicator */}
                  <div className="scanner-tf-selector" style={{
                    marginRight: '8px',
                    width: '74px',
                    justifyContent: 'center',
                    gap: '6px',
                    background: syncSeconds <= 30 ? 'rgba(251, 191, 36, 0.12)' : 'rgba(0,0,0,0.4)',
                    border: syncSeconds <= 30 ? '1px solid rgba(251, 191, 36, 0.4)' : '1px solid rgba(255,255,255,0.1)',
                  }}>
                    <div style={{
                      width: '5px',
                      height: '5px',
                      borderRadius: '50%',
                      background: syncSeconds <= 30 ? '#fbbf24' : '#64748b',
                      boxShadow: syncSeconds <= 10 ? '0 0 10px #fbbf24' : 'none',
                      animation: syncSeconds <= 10 ? 'pulse 1s infinite' : 'none'
                    }} />
                    <span style={{
                      fontSize: '10px',
                      fontWeight: '900',
                      color: syncSeconds <= 30 ? '#fbbf24' : '#94a3b8',
                      letterSpacing: '0.4px',
                      fontFamily: 'monospace'
                    }}>
                      {Math.floor(syncSeconds / 60)}:{(syncSeconds % 60).toString().padStart(2, '0')}
                    </span>
                  </div>
                  <div className="scanner-tf-selector" style={{ margin: 0, width: '120px', justifyContent: 'space-between', gap: '2px', marginRight: '8px' }}>
                    <button disabled={pageIndex === 0} onClick={() => setPageIndex(p => p - 1)} className="tf-tab" style={{ cursor: pageIndex === 0 ? 'not-allowed' : 'pointer', opacity: pageIndex === 0 ? 0.3 : 1, padding: '2px', minWidth: '20px', height: '18px' }}>◀</button>
                    <span style={{ color: '#cbd5e1', fontSize: '10px', fontWeight: '900', display: 'flex', alignItems: 'center', justifyContent: 'center', letterSpacing: '0.4px', flex: 1 }}>PAGE {pageIndex + 1} / {maxPages || 1}</span>
                    <button disabled={pageIndex >= maxPages - 1} onClick={() => setPageIndex(p => p + 1)} className="tf-tab" style={{ cursor: pageIndex >= maxPages - 1 ? 'not-allowed' : 'pointer', opacity: pageIndex >= maxPages - 1 ? 0.3 : 1, padding: '2px', minWidth: '20px', height: '18px' }}>▶</button>
                  </div>

                  <div className="scanner-tf-selector" style={{ margin: 0 }}>
                    <button
                      className={`tf-tab ${activeWidgets.manage ? 'active' : ''}`}
                      onClick={() => toggleWidget('manage')}
                      title="Manage Symbols"
                      style={{ padding: '0px', minWidth: '24px', height: '18px', display: 'flex', justifyContent: 'center', alignItems: 'center' }}
                    >
                      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.5" strokeLinecap="round" strokeLinejoin="round"><line x1="12" y1="5" x2="12" y2="19"></line><line x1="5" y1="12" x2="19" y2="12"></line></svg>
                    </button>
                    <button
                      className={`tf-tab ${activeWidgets.indicators ? 'active' : ''}`}
                      onClick={() => toggleWidget('indicators')}
                      title="Indicators Settings"
                      style={{ padding: '0px', minWidth: '24px', height: '18px', display: 'flex', justifyContent: 'center', alignItems: 'center' }}
                    >
                      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
                    </button>
                  </div>
                </div>
              </div>

              {maximizedChart ? (
                <div className="maximized-active-layout-inline" style={{
                  flex: 1,
                  display: 'grid',
                  gridTemplateColumns: '1fr 3fr',
                  gridTemplateRows: 'repeat(2, 1fr)',
                  gap: '10px',
                  padding: '0',
                  background: '#020617',
                  height: isZenMode ? 'calc(100vh - 20px)' : 'calc(100vh - 120px)', // full height minus headers
                  minHeight: 0
                }}>
                  {/* Slot 1: NIFTY 50 Anchor */}
                  <div className="anchor-slot" style={{ gridRow: '1 / 2', gridColumn: '1 / 2', borderRadius: '6px', overflow: 'hidden', border: '1px solid rgba(148, 163, 184, 0.1)', height: '100%', minHeight: 0 }}>
                    <CanvasTradingChart
                      symbol="NIFTY 50"
                      initialInterval={maximizedChart.tf}
                      isGrid={true}
                      externalVisibility={visibility}
                      onVisibilityUpdate={setVisibility}
                      onMaximize={() => { }}
                      isMaximized={false}
                      onPriceUpdate={handlePriceUpdate}
                      themeColors={themeColors}
                      symbolPrices={symbolPrices}
                      hideMaximize={true}
                    />
                  </div>

                  {/* Slot 5: SENSEX Anchor */}
                  <div className="anchor-slot" style={{ gridRow: '2 / 3', gridColumn: '1 / 2', borderRadius: '6px', overflow: 'hidden', border: '1px solid rgba(148, 163, 184, 0.1)', height: '100%', minHeight: 0 }}>
                    <CanvasTradingChart
                      symbol="SENSEX"
                      initialInterval={maximizedChart.tf}
                      isGrid={true}
                      externalVisibility={visibility}
                      onVisibilityUpdate={setVisibility}
                      onMaximize={() => { }}
                      isMaximized={false}
                      onPriceUpdate={handlePriceUpdate}
                      themeColors={themeColors}
                      symbolPrices={symbolPrices}
                      hideMaximize={true}
                    />
                  </div>

                  {/* Main Area: The Maximized Chart */}
                  <div className="main-max-slot" style={{ gridRow: '1 / 3', gridColumn: '2 / 3', borderRadius: '6px', overflow: 'hidden', border: '1px solid rgba(59, 130, 246, 0.3)', boxShadow: '0 0 20px rgba(0,0,0,0.5)' }}>
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
              ) : (
                <div className={`charts-grid matrix-${gridLayout}`}>
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
                        isHighlighted={highlightedSymbol === slot.sym}
                      />
                    </div>
                  ))}
                  {/* Empty Placeholders */}
                  {Array.from({ length: Math.max(0, gridSize - currentScannerSlots.length) }).map((_, i) => (
                    <div key={`placeholder-${i}`} className="grid-chart-item placeholder-slot" style={{
                      border: '1px solid rgba(255,255,255,0.03)',
                      background: 'linear-gradient(rgba(255,255,255,0.01) 1px, transparent 1px) 0 0 / 25px 25px, linear-gradient(90deg, rgba(255,255,255,0.01) 1px, transparent 1px) 0 0 / 25px 25px, #0a0f1d',
                      display: 'flex',
                      justifyContent: 'center',
                      alignItems: 'center'
                    }}>
                      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '8px', opacity: 0.15 }}>
                        <span style={{ fontSize: '11px', fontWeight: '900', letterSpacing: '2px', color: '#94a3b8' }}>CHART {currentScannerSlots.length + i + 1}</span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </main>
        </div>

        <IndicatorsWidget
          isOpen={activeWidgets.indicators}
          onClose={() => toggleWidget('indicators')}
          visibility={visibility}
          setVisibility={setVisibility}
          themeColors={themeColors}
          setThemeColors={setThemeColors}
          gridLayout={gridLayout}
          setGridLayout={setGridLayout}
          setPageIndex={setPageIndex}
        />

        <ManageSymbolsWidget
          isOpen={activeWidgets.manage}
          onClose={() => { toggleWidget('manage'); setGlobalSearchQuery(''); }}
          trackedData={symbolPrices}
          tracked={trackedSymbols}
          onUpdate={fetchAll}
          initialSearch={globalSearchQuery}
        />

        <SymbolSearch
          isOpen={isSearchOpen}
          onClose={setIsSearchOpen}
          symbols={trackedSymbols}
          onSymbolSelect={jumpToSymbol}
          symbolPrices={symbolPrices}
          onGlobalSearch={(q) => {
            setGlobalSearchQuery(q);
            setActiveWidgets(prev => ({ ...prev, manage: true }));
          }}
        />
      </div>
    </LiveFeedProvider>
  );
}

export default App;
