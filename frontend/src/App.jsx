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


  const fetchAll = useCallback(() => {
    axios.get(`${API_BASE}/api/monitor/all`)
      .then(res => {
        const { matrix } = res.data;
        setIsOffline(false);
        setSymbolPrices(prev => {
          const hasChanged = Object.keys(matrix).some(sym => {
            const prevSym = prev[sym] || {};
            const nextSym = matrix[sym];
            return prevSym.price !== nextSym.price ||
              JSON.stringify(prevSym.stTrendByTf) !== JSON.stringify(nextSym.stTrendByTf);
          });
          return hasChanged ? { ...prev, ...matrix } : prev;
        });
        const newTrackedSymbols = Object.keys(matrix);
        setTrackedSymbols(prev => (prev.length !== newTrackedSymbols.length || prev.some((s, i) => s !== newTrackedSymbols[i])) ? newTrackedSymbols : prev);

        // --- GLOBAL TREND FLIP DISCOVERY ---
        const discoveredFlips = [];
        Object.keys(matrix).forEach(sym => {
          const data = matrix[sym];
          const flips = data.lastFlipTimes || {};
          Object.keys(flips).forEach(tf => {
            const currentFlipTs = flips[tf] ? new Date(flips[tf]).getTime() : 0;
            if (currentFlipTs > 0) {
              const key = `${sym}-${tf}`;
              // Initialize seen flips on first load without toast
              if (seenFlipsRef.current[key] === undefined) {
                seenFlipsRef.current[key] = currentFlipTs;
                return;
              }
              // If flip is newer than last seen
              if (currentFlipTs > seenFlipsRef.current[key]) {
                seenFlipsRef.current[key] = currentFlipTs;
                const trend = data.stTrendByTf[tf];
                discoveredFlips.push({
                  id: `${key}-${currentFlipTs}`,
                  sym, tf, trend,
                  type: trend === 1 ? 'up' : 'dn'
                });
              }
            }
          });
        });

        if (discoveredFlips.length > 0) {
          // Play a subtle sound or just show toasts
          setAppNotifications(prev => [...prev.slice(-3), ...discoveredFlips]);
          discoveredFlips.forEach(df => {
            setTimeout(() => {
              setAppNotifications(prev => prev.filter(n => n.id !== df.id));
            }, 8000);
          });
        }


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
  }, []);

  useEffect(() => {
    fetchAll();
    const interval = setInterval(fetchAll, 2000);
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
                />
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
