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
  useEffect(() => {
    document.title = "Bhilwara Trader | Pro Dashboard";
  }, []);

  const [symbolPrices, setSymbolPrices] = useState({});
  const [isOffline, setIsOffline] = useState(false);
  const scannerCategoryRef = useRef('STOCKS');
  const scannerTfRef = useRef('3min');

  const [mainTab, setMainTab] = useState(() => localStorage.getItem('main_tab') || 'SCANNER');
  const [pageIndex, setPageIndex] = useState(0);
  const [maximizedChart, setMaximizedChart] = useState(null);

  const handleMaximizeToggle = useCallback((chartData) => {
    setMaximizedChart(chartData);
  }, []);
  const handleChartMaximize = useCallback((tf, sym) => {
    setMaximizedChart({ sym, tf });
  }, []);
  const handleCloseMaximize = useCallback(() => {
    setMaximizedChart(null);
  }, []);
  const NOOP = useCallback(() => { }, []);
  const [highlightedSymbol, setHighlightedSymbol] = useState(null);
  const [gridLayout, setGridLayout] = useState(() => localStorage.getItem('scanner_grid_layout') || '2x4');
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [cpTf, setCpTf] = useState(() => {
    const saved = localStorage.getItem('cp_tf');
    return (saved && saved !== '15min') ? saved : '3min';
  });
  const [cpSelectedCE, setCpSelectedCE] = useState(() => localStorage.getItem('cp_selected_ce') || null);
  const [cpSelectedPE, setCpSelectedPE] = useState(() => localStorage.getItem('cp_selected_pe') || null);
  const [cpCeOpen, setCpCeOpen] = useState(false);
  const [cpPeOpen, setCpPeOpen] = useState(false);
  const [mobileTfOpen, setMobileTfOpen] = useState(false);
  const [mobileCatOpen, setMobileCatOpen] = useState(false);

  const [activeWidgets, setActiveWidgets] = useState({
    manage: false,
    indicators: false,
  });
  const [globalSearchQuery, setGlobalSearchQuery] = useState('');
  const [isSearchOpen, setIsSearchOpen] = useState(false);

  // Persistent BroadcastChannel singleton — avoids create/destroy on every zoom keypress
  const broadcastRef = useRef(null);
  useEffect(() => {
    broadcastRef.current = new BroadcastChannel('grid_chart_sync');
    return () => broadcastRef.current?.close();
  }, []);



  const toggleWidget = (id) => setActiveWidgets(prev => ({ ...prev, [id]: !prev[id] }));

  useEffect(() => {
    if (cpSelectedCE) localStorage.setItem('cp_selected_ce', cpSelectedCE);
    else localStorage.removeItem('cp_selected_ce');
  }, [cpSelectedCE]);

  useEffect(() => {
    if (cpSelectedPE) localStorage.setItem('cp_selected_pe', cpSelectedPE);
    else localStorage.removeItem('cp_selected_pe');
  }, [cpSelectedPE]);

  const setMainTabPersist = useCallback((tab) => { localStorage.setItem('main_tab', tab); setMainTab(tab); setCpCeOpen(false); setCpPeOpen(false); }, []);
  const setCpTfPersist = useCallback((tf) => { localStorage.setItem('cp_tf', tf); setCpTf(tf); }, []);

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
      EMA15: true, EMA15_5: true, EMA15_15: true, EMA15_15_15: true,
      CLOUD: true, CLOUD2: true,
      RENKO2: false, RENKO3: false, RENKO_CLOSE: false,
      ST_HA_CURRENT: false, ST_HA_HTF: false, ST_HA_FLIPS: false
    };
    const saved = localStorage.getItem('chartVisibility');
    let finalVisibility = defaults;
    if (saved) {
      const parsed = JSON.parse(saved);
      delete parsed.EMA15_15_15_15;
      delete parsed.EMA15_15_15_15_30;
      finalVisibility = { ...defaults, ...parsed };
    }
    return finalVisibility;
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
    const interval = setInterval(fetchAll, 90000); // 90s — WS feed carries live data; REST is a fallback sync
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



  const [globalResetTrigger, setGlobalResetTrigger] = useState(0);

  const handleGlobalReset = () => setGlobalResetTrigger(Date.now());

  // Native Browser Fullscreen (hides browser address bar on mobile & desktop)
  const requestAppFullscreen = async () => {
    try {
      const el = document.documentElement;
      if (el.requestFullscreen) {
        await el.requestFullscreen();
      } else if (el.webkitRequestFullscreen) {
        await el.webkitRequestFullscreen();
      } else if (el.mozRequestFullScreen) {
        await el.mozRequestFullScreen();
      } else if (el.msRequestFullscreen) {
        await el.msRequestFullscreen();
      }
    } catch (err) {
      console.warn('Native fullscreen request:', err);
    }
  };

  const exitAppFullscreen = async () => {
    try {
      if (document.fullscreenElement || document.webkitFullscreenElement || document.mozFullScreenElement || document.msFullscreenElement) {
        if (document.exitFullscreen) {
          await document.exitFullscreen();
        } else if (document.webkitExitFullscreen) {
          await document.webkitExitFullscreen();
        } else if (document.mozCancelFullScreen) {
          await document.mozCancelFullScreen();
        } else if (document.msExitFullscreen) {
          await document.msExitFullscreen();
        }
      }
    } catch (err) {
      console.warn('Native exit fullscreen:', err);
    }
  };

  const toggleFullscreen = useCallback(() => {
    const isFs = Boolean(
      document.fullscreenElement ||
      document.webkitFullscreenElement ||
      document.mozFullScreenElement ||
      document.msFullscreenElement
    );
    if (isFs) {
      exitAppFullscreen();
    } else {
      requestAppFullscreen();
    }
  }, []);

  // Sync state if user enters/exits browser native fullscreen (e.g. Esc key, F11, or mobile gestures)
  useEffect(() => {
    const handleFsChange = () => {
      const isFs = Boolean(
        document.fullscreenElement ||
        document.webkitFullscreenElement ||
        document.mozFullScreenElement ||
        document.msFullscreenElement
      );
      setIsFullscreen(isFs);
    };

    document.addEventListener('fullscreenchange', handleFsChange);
    document.addEventListener('webkitfullscreenchange', handleFsChange);
    document.addEventListener('mozfullscreenchange', handleFsChange);
    document.addEventListener('MSFullscreenChange', handleFsChange);

    return () => {
      document.removeEventListener('fullscreenchange', handleFsChange);
      document.removeEventListener('webkitfullscreenchange', handleFsChange);
      document.removeEventListener('mozfullscreenchange', handleFsChange);
      document.removeEventListener('MSFullscreenChange', handleFsChange);
    };
  }, []);

  // Global Shortcuts
  useEffect(() => {
    const handleKeyDown = (e) => {
      // Escape Priority: 1. Close Maximized 2. Global Reset
      if (e.key === 'Escape') {
        if (maximizedChart) {
          setMaximizedChart(null);
        } else {
          handleGlobalReset();
        }
      }

      if (e.shiftKey && (e.key === 'R' || e.key === 'r')) {
        e.preventDefault();
        handleGlobalReset();
      }

      if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) {
        e.preventDefault();
        setIsSearchOpen(true);
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
          if (scannerCategories['STOCKS']) { setScannerCategoryWithRef('STOCKS'); setPageIndex(0); setMaximizedChart(null); }
          break;
        case 'f': case 'F':
          if (scannerCategories['FUTURES']) { setScannerCategoryWithRef('FUTURES'); setPageIndex(0); setMaximizedChart(null); }
          break;
        case 'c': case 'C':
          if (scannerCategories['CALLS']) { setScannerCategoryWithRef('CALLS'); setPageIndex(0); setMaximizedChart(null); }
          break;
        case 'p': case 'P':
          if (scannerCategories['PUTS']) { setScannerCategoryWithRef('PUTS'); setPageIndex(0); setMaximizedChart(null); }
          break;
        case 'i': case 'I':
          if (scannerCategories['INDEX']) { setScannerCategoryWithRef('INDEX'); setPageIndex(0); setMaximizedChart(null); }
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
          ['1min', '3min', '5min'].forEach(tf => localStorage.setItem(`chart_zoom_${tf}`, newZoom));
          broadcastRef.current?.postMessage({ type: 'SYNC_ZOOM', count: newZoom });
          break;
        }
        case 'ArrowDown': {
          const currentZoom = parseInt(localStorage.getItem('chart_zoom_global')) || 160;
          const newZoom = Math.min(800, Math.round(currentZoom * 1.25)); // zoom out (more bars)
          localStorage.setItem('chart_zoom_global', newZoom);
          ['1min', '3min', '5min'].forEach(tf => localStorage.setItem(`chart_zoom_${tf}`, newZoom));
          broadcastRef.current?.postMessage({ type: 'SYNC_ZOOM', count: newZoom });
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
        case 't': case 'T': {
          const tfs = ['1min', '3min', '5min'];
          const currentTf = scannerTfRef.current || '3min';
          const nextIndex = (tfs.indexOf(currentTf) + 1) % tfs.length;
          setScannerTfWithRef(tfs[nextIndex]);
          setPageIndex(0);
          break;
        }
        case 'z': case 'Z':
          toggleFullscreen();
          break;
        default:
          break;
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [maxPages, setScannerCategoryWithRef, scannerCategories, maximizedChart]);

  // C&P — independent CE / PE selection with LTP
  const cpCallsSorted = useMemo(() => {
    const ex = s => { const m = s.match(/(\d+)[CP]E$/); return m ? parseInt(m[1], 10) : 0; };
    return [...(scannerCategories['CALLS'] || [])].sort((a, b) => ex(a) - ex(b));
  }, [scannerCategories]);
  const cpPutsSorted = useMemo(() => {
    const ex = s => { const m = s.match(/(\d+)[CP]E$/); return m ? parseInt(m[1], 10) : 0; };
    return [...(scannerCategories['PUTS'] || [])].sort((a, b) => ex(a) - ex(b));
  }, [scannerCategories]);
  const cpActiveCE = (cpSelectedCE && cpCallsSorted.includes(cpSelectedCE)) ? cpSelectedCE : (cpCallsSorted[0] || null);
  const cpActivePE = (cpSelectedPE && cpPutsSorted.includes(cpSelectedPE)) ? cpSelectedPE : (cpPutsSorted[0] || null);

  return (
    <LiveFeedProvider>
      <div className="app-container">
        <div className="main-content">
          <main className="chart-section no-sidebar">
            <div className="scanner-view-container">
              <div className="scanner-header sticky-header">
                {/* Header Left: Main Tab Switch (SC / CP) */}
                <div className="header-left">
                  <div className="main-tab-switcher" style={{ display: 'flex', gap: '3px', background: 'rgba(0,0,0,0.45)', padding: '3px', borderRadius: '9px', border: '1px solid rgba(255,255,255,0.08)' }}>
                    <button
                      className={`tf-tab ${mainTab === 'SCANNER' ? 'active' : ''}`}
                      onClick={() => setMainTabPersist('SCANNER')}
                      title="Scanner (SC)"
                      style={{ fontSize: '10px', padding: '3px 8px', minWidth: '32px', letterSpacing: '0.5px', fontWeight: '800' }}
                    >SC</button>
                    <button
                      className={`tf-tab ${mainTab === 'CP' ? 'active' : ''}`}
                      onClick={() => setMainTabPersist('CP')}
                      title="Calls & Puts (CP)"
                      style={{
                        fontSize: '10px', padding: '3px 8px', minWidth: '32px', letterSpacing: '0.5px', fontWeight: '800',
                        background: mainTab === 'CP' ? 'linear-gradient(135deg, #10b981, #3b82f6)' : 'transparent',
                        color: mainTab === 'CP' ? '#fff' : '#94a3b8',
                        boxShadow: mainTab === 'CP' ? '0 0 14px rgba(16,185,129,0.3)' : 'none',
                      }}
                    >CP</button>
                  </div>
                </div>

                {/* Header Center: Navigation & Categories */}
                <div className="header-center">
                  {mainTab === 'SCANNER' && (
                    <>
                      {/* Desktop Timeframe Buttons */}
                      <div className="desktop-only-header" style={{ display: 'flex', gap: '4px' }}>
                        <div className="scanner-tf-selector" style={{ margin: 0 }}>
                          {['1min', '3min', '5min'].map(tf => (
                            <button
                              key={tf}
                              className={`tf-tab ${scannerTf === tf ? 'active' : ''}`}
                              onClick={() => { setScannerTfWithRef(tf); setPageIndex(0); }}
                            >
                              {tf.replace('min', 'M').toUpperCase()}
                            </button>
                          ))}
                        </div>
                      </div>

                      {/* Mobile Timeframe Dropdown Pill */}
                      <div className="mobile-only-header" style={{ position: 'relative' }}>
                        <button
                          className="mobile-header-pill"
                          onClick={() => { setMobileTfOpen(o => !o); setMobileCatOpen(false); }}
                        >
                          <span>{scannerTf.replace('min', 'M').toUpperCase()}</span>
                          <span className="pill-arrow">{mobileTfOpen ? '▲' : '▼'}</span>
                        </button>
                        {mobileTfOpen && (
                          <>
                            <div onClick={() => setMobileTfOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 1050 }} />
                            <div className="mobile-dropdown-menu">
                              {['1min', '3min', '5min'].map(tf => (
                                <button
                                  key={tf}
                                  className={`mobile-dropdown-item ${scannerTf === tf ? 'active' : ''}`}
                                  onClick={() => { setScannerTfWithRef(tf); setPageIndex(0); setMobileTfOpen(false); }}
                                >
                                  <span>{tf.replace('min', 'M').toUpperCase()}</span>
                                  {scannerTf === tf && <span style={{ color: '#fbbf24' }}>✓</span>}
                                </button>
                              ))}
                            </div>
                          </>
                        )}
                      </div>
                    </>
                  )}

                  {mainTab === 'CP' && (
                    <div style={{ position: 'relative', flexShrink: 0 }}>
                      <button
                        onClick={() => { setCpCeOpen(o => !o); setCpPeOpen(false); }}
                        title={cpActiveCE ? `${cpActiveCE} (${symbolPrices[cpActiveCE]?.price?.toFixed(2) ?? ''})` : 'Select Call'}
                        style={{ display: 'flex', alignItems: 'center', gap: '3px', background: cpCeOpen ? 'rgba(16,185,129,0.18)' : 'rgba(0,0,0,0.35)', border: `1px solid ${cpCeOpen ? 'rgba(16,185,129,0.5)' : 'rgba(16,185,129,0.25)'}`, borderRadius: '7px', padding: '3px 7px', height: '24px', cursor: 'pointer' }}
                      >
                        {cpActiveCE ? (
                          <>
                            <span className="cp-pill-strike">{cpActiveCE.match(/^.+?2[0-9][A-Z0-9]{3}(\d+)CE$/) ? `${cpActiveCE.match(/^.+?2[0-9][A-Z0-9]{3}(\d+)CE$/)[1]} CE` : cpActiveCE}</span>
                            <span className="cp-pill-dash">&nbsp;-&nbsp;</span>
                            <span className="cp-pill-price" style={{ color: symbolPrices[cpActiveCE]?.isGreen ? '#10b981' : '#f87171' }}>
                              {symbolPrices[cpActiveCE]?.price?.toFixed(2) ?? '--'}
                            </span>
                          </>
                        ) : (
                          <span style={{ fontSize: '11px', fontWeight: '700', color: '#e2e8f0', fontFamily: 'monospace' }}>
                            <span className="cp-pill-strike">SELECT </span>CE
                          </span>
                        )}
                        <span style={{ fontSize: '8px', color: '#64748b', marginLeft: '1px' }}>{cpCeOpen ? '▲' : '▼'}</span>
                      </button>
                      {cpCeOpen && (
                        <>
                          <div onClick={() => setCpCeOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 1050 }} />
                          <div style={{ position: 'absolute', top: 'calc(100% + 5px)', left: 0, zIndex: 1100, background: '#141821', border: '1px solid rgba(16,185,129,0.25)', borderRadius: '10px', padding: '6px 0', minWidth: '180px', maxHeight: '320px', overflowY: 'auto', boxShadow: '0 16px 48px rgba(0,0,0,0.8)' }}>
                            <div style={{ padding: '4px 12px 6px', borderBottom: '1px solid rgba(255,255,255,0.06)', marginBottom: '3px' }}><span style={{ fontSize: '9px', fontWeight: '900', color: '#10b981', letterSpacing: '1px' }}>SELECT CALL</span></div>
                            {cpCallsSorted.map(sym => {
                              const ltp = symbolPrices[sym]; const active = sym === cpActiveCE; return (
                                <div key={sym} onClick={() => { setCpSelectedCE(sym); setCpCeOpen(false); }}
                                  style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '7px 12px', cursor: 'pointer', background: active ? 'rgba(16,185,129,0.12)' : 'transparent', borderLeft: active ? '2px solid #10b981' : '2px solid transparent' }}
                                  onMouseEnter={e => { if (!active) e.currentTarget.style.background = 'rgba(255,255,255,0.04)'; }}
                                  onMouseLeave={e => { if (!active) e.currentTarget.style.background = 'transparent'; }}
                                >
                                  <span style={{ fontSize: '11px', fontFamily: 'monospace', color: '#e2e8f0', flex: 1 }}>{sym.match(/^.+?2[0-9][A-Z0-9]{3}(\d+)CE$/) ? `${sym.match(/^.+?2[0-9][A-Z0-9]{3}(\d+)CE$/)[1]} CE` : sym}</span>
                                  {ltp && <span style={{ fontSize: '11px', fontWeight: '900', fontFamily: 'monospace', color: ltp.isGreen ? '#10b981' : '#f87171', minWidth: '50px', textAlign: 'right' }}>{ltp.price?.toFixed(2)}</span>}
                                  {active && <span style={{ fontSize: '9px', color: '#10b981' }}>✓</span>}
                                </div>
                              );
                            })}
                            {cpCallsSorted.length === 0 && <div style={{ padding: '12px', fontSize: '10px', color: '#475569', textAlign: 'center' }}>No calls</div>}
                          </div>
                        </>
                      )}
                    </div>
                  )}

                  {mainTab === 'CP' && (
                    <div className="scanner-tf-selector cp-tf-selector" style={{ margin: 0, flexShrink: 0 }}>
                      {['1min', '3min', '5min'].map(tf => (
                        <button
                          key={tf}
                          className={`tf-tab ${cpTf === tf ? 'active' : ''}`}
                          onClick={() => setCpTfPersist(tf)}
                        >
                          {tf.replace('min', 'M').toUpperCase()}
                        </button>
                      ))}
                    </div>
                  )}

                  {mainTab === 'SCANNER' && (
                    <>
                      {/* Page Navigation */}
                      <div className={`scanner-tf-selector page-nav-box ${maxPages <= 1 ? 'hide-single-page-mobile' : ''}`} style={{ margin: 0, justifyContent: 'space-between', gap: '2px' }}>
                        <button disabled={pageIndex === 0} onClick={() => setPageIndex(p => p - 1)} className="tf-tab" data-tooltip="Previous Page" style={{ cursor: pageIndex === 0 ? 'not-allowed' : 'pointer', opacity: pageIndex === 0 ? 0.3 : 1, padding: '2px', minWidth: '18px', height: '18px' }}>◀</button>
                        <span className="page-label" style={{ color: '#cbd5e1', fontSize: '10px', fontWeight: '900', display: 'flex', alignItems: 'center', justifyContent: 'center', letterSpacing: '0.4px', padding: '0 4px' }}>PAGE {pageIndex + 1}/{maxPages || 1}</span>
                        <span className="page-label-small" style={{ display: 'none', color: '#cbd5e1', fontSize: '10px', fontWeight: '900', alignItems: 'center', justifyContent: 'center' }}>{pageIndex + 1}/{maxPages || 1}</span>
                        <button disabled={pageIndex >= maxPages - 1} onClick={() => setPageIndex(p => p + 1)} className="tf-tab" data-tooltip="Next Page" style={{ cursor: pageIndex >= maxPages - 1 ? 'not-allowed' : 'pointer', opacity: pageIndex >= maxPages - 1 ? 0.3 : 1, padding: '2px', minWidth: '18px', height: '18px' }}>▶</button>
                      </div>

                      {/* Category Dropdown Pill */}
                      <div style={{ position: 'relative' }}>
                        <button
                          className="mobile-header-pill"
                          onClick={() => { setMobileCatOpen(o => !o); setMobileTfOpen(false); }}
                          title="Select Category"
                        >
                          <span>{{ INDEX: 'IN', STOCKS: 'ST', FUTURES: 'FU', CALLS: 'CL', PUTS: 'PT' }[scannerCategory] || scannerCategory}</span>
                          <span className="pill-arrow">{mobileCatOpen ? '▲' : '▼'}</span>
                        </button>
                        {mobileCatOpen && (
                          <>
                            <div onClick={() => setMobileCatOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 1050 }} />
                            <div className="mobile-dropdown-menu">
                              {Object.keys(scannerCategories).map(cat => {
                                const labels = { INDEX: 'INDEX', STOCKS: 'STOCKS', FUTURES: 'FUTURES', CALLS: 'CALLS', PUTS: 'PUTS' };
                                const active = scannerCategory === cat;
                                return (
                                  <button
                                    key={cat}
                                    className={`mobile-dropdown-item ${active ? 'active' : ''}`}
                                    onClick={() => { setScannerCategoryWithRef(cat); setPageIndex(0); setMobileCatOpen(false); }}
                                  >
                                    <span>{labels[cat] || cat} ({scannerCategories[cat]?.length || 0})</span>
                                    {active && <span style={{ color: '#fbbf24' }}>✓</span>}
                                  </button>
                                );
                              })}
                            </div>
                          </>
                        )}
                      </div>
                    </>
                  )}

                  {mainTab === 'CP' && (
                    <div style={{ position: 'relative', flexShrink: 0 }}>
                      <button
                        onClick={() => { setCpPeOpen(o => !o); setCpCeOpen(false); }}
                        title={cpActivePE ? `${cpActivePE} (${symbolPrices[cpActivePE]?.price?.toFixed(2) ?? ''})` : 'Select Put'}
                        style={{ display: 'flex', alignItems: 'center', gap: '3px', background: cpPeOpen ? 'rgba(239,68,68,0.18)' : 'rgba(0,0,0,0.35)', border: `1px solid ${cpPeOpen ? 'rgba(239,68,68,0.5)' : 'rgba(239,68,68,0.25)'}`, borderRadius: '7px', padding: '3px 7px', height: '24px', cursor: 'pointer' }}
                      >
                        {cpActivePE ? (
                          <>
                            <span className="cp-pill-strike">{cpActivePE.match(/^.+?2[0-9][A-Z0-9]{3}(\d+)PE$/) ? `${cpActivePE.match(/^.+?2[0-9][A-Z0-9]{3}(\d+)PE$/)[1]} PE` : cpActivePE}</span>
                            <span className="cp-pill-dash">&nbsp;-&nbsp;</span>
                            <span className="cp-pill-price" style={{ color: symbolPrices[cpActivePE]?.isGreen ? '#10b981' : '#f87171' }}>
                              {symbolPrices[cpActivePE]?.price?.toFixed(2) ?? '--'}
                            </span>
                          </>
                        ) : (
                          <span style={{ fontSize: '11px', fontWeight: '700', color: '#e2e8f0', fontFamily: 'monospace' }}>
                            <span className="cp-pill-strike">SELECT </span>PE
                          </span>
                        )}
                        <span style={{ fontSize: '8px', color: '#64748b', marginLeft: '1px' }}>{cpPeOpen ? '▲' : '▼'}</span>
                      </button>
                      {cpPeOpen && (
                        <>
                          <div onClick={() => setCpPeOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 1050 }} />
                          <div style={{ position: 'absolute', top: 'calc(100% + 5px)', right: 0, zIndex: 1100, background: '#141821', border: '1px solid rgba(239,68,68,0.25)', borderRadius: '10px', padding: '6px 0', minWidth: '180px', maxHeight: '320px', overflowY: 'auto', boxShadow: '0 16px 48px rgba(0,0,0,0.8)' }}>
                            <div style={{ padding: '4px 12px 6px', borderBottom: '1px solid rgba(255,255,255,0.06)', marginBottom: '3px' }}><span style={{ fontSize: '9px', fontWeight: '900', color: '#f87171', letterSpacing: '1px' }}>SELECT PUT</span></div>
                            {cpPutsSorted.map(sym => {
                              const ltp = symbolPrices[sym]; const active = sym === cpActivePE; return (
                                <div key={sym} onClick={() => { setCpSelectedPE(sym); setCpPeOpen(false); }}
                                  style={{ display: 'flex', alignItems: 'center', gap: '8px', padding: '7px 12px', cursor: 'pointer', background: active ? 'rgba(239,68,68,0.12)' : 'transparent', borderLeft: active ? '2px solid #ef4444' : '2px solid transparent' }}
                                  onMouseEnter={e => { if (!active) e.currentTarget.style.background = 'rgba(255,255,255,0.04)'; }}
                                  onMouseLeave={e => { if (!active) e.currentTarget.style.background = 'transparent'; }}
                                >
                                  <span style={{ fontSize: '11px', fontFamily: 'monospace', color: '#e2e8f0', flex: 1 }}>{sym.match(/^.+?2[0-9][A-Z0-9]{3}(\d+)PE$/) ? `${sym.match(/^.+?2[0-9][A-Z0-9]{3}(\d+)PE$/)[1]} PE` : sym}</span>
                                  {ltp && <span style={{ fontSize: '11px', fontWeight: '900', fontFamily: 'monospace', color: ltp.isGreen ? '#10b981' : '#f87171', minWidth: '50px', textAlign: 'right' }}>{ltp.price?.toFixed(2)}</span>}
                                  {active && <span style={{ fontSize: '9px', color: '#ef4444' }}>✓</span>}
                                </div>
                              );
                            })}
                            {cpPutsSorted.length === 0 && <div style={{ padding: '12px', fontSize: '10px', color: '#475569', textAlign: 'center' }}>No puts</div>}
                          </div>
                        </>
                      )}
                    </div>
                  )}

                </div>

                {/* Header Right: Tools */}
                <div className="header-right">
                  <div className="scanner-tf-selector" style={{ margin: 0, padding: '3px 6px', gap: '6px', height: '28px' }}>
                    <button
                      className={`tf-tab ${isFullscreen ? 'active' : ''}`}
                      onClick={toggleFullscreen}
                      data-tooltip={isFullscreen ? "Exit Fullscreen" : "Fullscreen"}
                      style={{ padding: '0px', minWidth: '24px', height: '22px', display: 'flex', justifyContent: 'center', alignItems: 'center', borderRadius: '6px' }}
                    >
                      {isFullscreen ? (
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round">
                          <path d="M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 1 2-2h3M3 16h3a2 2 0 0 1 2 2v3" />
                        </svg>
                      ) : (
                        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.3" strokeLinecap="round" strokeLinejoin="round">
                          <path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3" />
                        </svg>
                      )}
                    </button>

                    <button
                      className={`tf-tab ${activeWidgets.manage ? 'active' : ''}`}
                      onClick={() => toggleWidget('manage')}
                      data-tooltip="Manage Symbols"
                      style={{ padding: '0px', minWidth: '24px', height: '22px', display: 'flex', justifyContent: 'center', alignItems: 'center', borderRadius: '6px' }}
                    >
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
                        <line x1="12" y1="5" x2="12" y2="19"></line>
                        <line x1="5" y1="12" x2="19" y2="12"></line>
                      </svg>
                    </button>

                    <button
                      className={`tf-tab ${activeWidgets.indicators ? 'active' : ''}`}
                      onClick={() => toggleWidget('indicators')}
                      data-tooltip="Configuration"
                      style={{ padding: '0px', minWidth: '24px', height: '22px', display: 'flex', justifyContent: 'center', alignItems: 'center', borderRadius: '6px' }}
                    >
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                        <circle cx="12" cy="12" r="3"></circle>
                        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path>
                      </svg>
                    </button>
                  </div>
                </div>
              </div>

              {mainTab === 'CP' && !maximizedChart ? (
                <div className="cp-view-grid" style={{ gap: '4px', flex: 1, minHeight: 0, overflow: 'hidden', padding: '0px 4px 4px 4px' }}>

                  {/* CALL side */}
                  <div className="cp-panel" style={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
                    <div style={{ flex: 1, minHeight: 0, border: '1px solid rgba(16,185,129,0.12)', borderRadius: '8px', overflow: 'visible' }}>
                      {cpActiveCE ? <CanvasTradingChart key={`cp_${cpActiveCE}`} symbol={cpActiveCE} initialInterval={cpTf} isGrid={true} externalVisibility={visibility} onVisibilityUpdate={setVisibility} globalResetTrigger={globalResetTrigger} onMaximize={handleChartMaximize} isMaximized={false} onPriceUpdate={handlePriceUpdate} themeColors={themeColors} onDelete={fetchAll} defaultShowRenko={false} /> : <div style={{ height: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '8px', opacity: 0.4 }}><span style={{ fontSize: '22px' }}>📈</span><span style={{ fontSize: '11px', color: '#94a3b8', fontWeight: '700' }}>Select a Call above</span></div>}
                    </div>
                  </div>

                  {/* PUT side */}
                  <div className="cp-panel" style={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
                    <div style={{ flex: 1, minHeight: 0, border: '1px solid rgba(239,68,68,0.12)', borderRadius: '8px', overflow: 'visible' }}>
                      {cpActivePE ? <CanvasTradingChart key={`cp_${cpActivePE}`} symbol={cpActivePE} initialInterval={cpTf} isGrid={true} externalVisibility={visibility} onVisibilityUpdate={setVisibility} globalResetTrigger={globalResetTrigger} onMaximize={handleChartMaximize} isMaximized={false} onPriceUpdate={handlePriceUpdate} themeColors={themeColors} onDelete={fetchAll} defaultShowRenko={false} /> : <div style={{ height: '100%', display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: '8px', opacity: 0.4 }}><span style={{ fontSize: '22px' }}>📉</span><span style={{ fontSize: '11px', color: '#94a3b8', fontWeight: '700' }}>Select a Put above</span></div>}
                    </div>
                  </div>

                </div>
              ) : maximizedChart ? (
                <div className="maximized-active-layout-inline" style={{
                  flex: 1,
                  display: 'grid',
                  gridTemplateColumns: window.innerWidth > 768 ? '1fr 3fr' : '1fr',
                  gridTemplateRows: window.innerWidth > 768 ? 'repeat(2, 1fr)' : '1fr',
                  gap: '10px',
                  padding: '0',
                  background: '#020617',
                  minHeight: 0,
                  overflow: 'hidden',
                }}>
                  {/* Slot 1: NIFTY 50 Anchor */}
                  {window.innerWidth > 768 && (
                    <div className="anchor-slot" style={{ gridRow: '1 / 2', gridColumn: '1 / 2', borderRadius: '6px', overflow: 'visible', border: '1px solid rgba(148, 163, 184, 0.1)', height: '100%', minHeight: 0 }}>
                      <CanvasTradingChart
                        symbol="NIFTY 50"
                        initialInterval={maximizedChart.tf}
                        isGrid={true}
                        externalVisibility={visibility}
                        onVisibilityUpdate={setVisibility}
                        onMaximize={NOOP}
                        isMaximized={false}
                        onPriceUpdate={handlePriceUpdate}
                        themeColors={themeColors}
                        hideMaximize={true}
                        globalResetTrigger={globalResetTrigger}
                      />
                    </div>
                  )}

                  {/* Slot 5: SENSEX Anchor */}
                  {window.innerWidth > 768 && (
                    <div className="anchor-slot" style={{ gridRow: '2 / 3', gridColumn: '1 / 2', borderRadius: '6px', overflow: 'visible', border: '1px solid rgba(148, 163, 184, 0.1)', height: '100%', minHeight: 0 }}>
                      <CanvasTradingChart
                        symbol="SENSEX"
                        initialInterval={maximizedChart.tf}
                        isGrid={true}
                        externalVisibility={visibility}
                        onVisibilityUpdate={setVisibility}
                        onMaximize={NOOP}
                        isMaximized={false}
                        onPriceUpdate={handlePriceUpdate}
                        themeColors={themeColors}
                        hideMaximize={true}
                        globalResetTrigger={globalResetTrigger}
                      />
                    </div>
                  )}

                  {/* Main Area: The Maximized Chart */}
                  <div className="main-max-slot" style={{ gridRow: window.innerWidth > 768 ? '1 / 3' : '1 / 2', gridColumn: window.innerWidth > 768 ? '2 / 3' : '1 / 2', borderRadius: '6px', overflow: 'visible', border: '1px solid rgba(59, 130, 246, 0.3)', boxShadow: '0 0 20px rgba(0,0,0,0.5)', position: 'relative', height: '100%', minHeight: 0 }}>
                    <CanvasTradingChart
                      key={maximizedChart.sym}
                      symbol={maximizedChart.sym}
                      initialInterval={maximizedChart.tf}
                      isGrid={false}
                      externalVisibility={visibility}
                      onVisibilityUpdate={setVisibility}
                      onMaximize={handleCloseMaximize}
                      onIntervalChange={(newTf) => setMaximizedChart(p => ({ ...p, tf: newTf }))}
                      globalResetTrigger={globalResetTrigger}
                      isMaximized={true}
                      onPriceUpdate={handlePriceUpdate}
                      themeColors={themeColors}
                      onDelete={fetchAll}
                    />

                    {/* CP maximized: header dropdowns above handle CE/PE switching */}
                  </div>
                </div>
              ) : (
                <div className={`charts-grid matrix-${gridLayout}`}>
                  {currentScannerSlots.map((slot, idx) => (
                    <div key={slot.sym} className="grid-chart-item">
                      <CanvasTradingChart
                        key={slot.sym}
                        symbol={slot.sym}
                        initialInterval={scannerTf}
                        isGrid={true}
                        externalVisibility={visibility}
                        onVisibilityUpdate={setVisibility}
                        globalResetTrigger={globalResetTrigger}
                        onMaximize={handleChartMaximize}
                        isMaximized={false}
                        onPriceUpdate={handlePriceUpdate}
                        themeColors={themeColors}
                        isHighlighted={highlightedSymbol === slot.sym}
                        onDelete={fetchAll}
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
