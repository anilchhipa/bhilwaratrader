import React, { useEffect, useRef, useState } from 'react';
import { createChart, ColorType, CandlestickSeries, LineSeries, HistogramSeries } from 'lightweight-charts';
import SockJS from 'sockjs-client';
import Stomp from 'stompjs';
import axios from 'axios';
import { CloudSeriesDefinition } from '../helpers/CloudSeries';
import { API_BASE, WS_BASE } from '../config';

const TradingChart = ({ symbol, interval }) => {
    const mainChartRef = useRef();
    const macdChartRef = useRef();
    const containerRef = useRef();
    const chartInstanceRef = useRef();
    const macdChartInstanceRef = useRef();
    const isDraggingRef = useRef(false);

    // Status state
    const [status, setStatus] = useState({ state: 'connecting', msg: 'Initializing...' });
    const [stats, setStats] = useState({ candles: 0, lastTick: null });

    // Panel heights state
    const [mainChartHeight, setMainChartHeight] = useState(400);
    const [macdChartHeight, setMacdChartHeight] = useState(150);

    // Series Refs
    const candleSeriesRef = useRef();
    const emaSeriesRef = useRef({});
    const stSeriesRef = useRef();
    const macdLinesRef = useRef({});

    const parseTime = (timestamp) => {
        if (!timestamp) return 0;
        const date = new Date(timestamp);
        return Math.floor(date.getTime() / 1000);
    };

    useEffect(() => {
        if (!mainChartRef.current || !macdChartRef.current) return;

        const mainChart = createChart(mainChartRef.current, {
            layout: { background: { type: ColorType.Solid, color: '#131722' }, textColor: '#d1d4dc' },
            grid: { vertLines: { color: '#2b2b43' }, horzLines: { color: '#2b2b43' } },
            width: mainChartRef.current.clientWidth || 800,
            height: mainChartHeight,
            crosshair: { mode: 0 },
            timeScale: {
                visible: false,
                rightOffset: 50,
                fixLeftEdge: false,
                fixRightEdge: false,
                minBarSpacing: 0.5,
            },
            handleScroll: {
                mouseWheel: false,
                pressedMouseMove: true,
                horzTouchDrag: true,
                vertTouchDrag: false,
            },
            handleScale: {
                axisPressedMouseMove: true,
                mouseWheel: true,
                pinch: true,
            },
        });
        chartInstanceRef.current = mainChart;

        // Initialize ST Shade first (Background)
        stSeriesRef.current = {};
        stSeriesRef.current.shade = mainChart.addCustomSeries(CloudSeriesDefinition, {
            // Custom series options
        });

        const candleSeries = mainChart.addSeries(CandlestickSeries, {
            upColor: '#26a69a', downColor: '#ef5350', borderVisible: false,
            wickUpColor: '#26a69a', wickDownColor: '#ef5350',
        });
        candleSeriesRef.current = candleSeries;

        const emaConfigs = [
            { key: 'EMA15', color: '#2196f3', width: 1 },
            { key: 'EMA15_5', color: '#ffeb3b', width: 1 },
            { key: 'EMA15_15', color: '#ff9800', width: 1 },
            { key: 'EMA15_15_15', color: '#e91e63', width: 1 },
            { key: 'EMA15_30', color: '#9e9e9e', width: 1 },
            { key: 'EMA100', color: '#00bcd4', width: 1 },
        ];

        emaConfigs.forEach(cfg => {
            emaSeriesRef.current[cfg.key] = mainChart.addSeries(LineSeries, {
                color: cfg.color, lineWidth: cfg.width, title: '',
                lastValueVisible: false, priceLineVisible: false, crosshairMarkerVisible: false
            });
        });

        // Removed st_shade scale configuration



        const macdChart = createChart(macdChartRef.current, {
            layout: { background: { type: ColorType.Solid, color: '#131722' }, textColor: '#d1d4dc' },
            grid: { vertLines: { color: '#2b2b43' }, horzLines: { color: '#2b2b43' } },
            width: macdChartRef.current.clientWidth || 800,
            height: macdChartHeight,
            crosshair: { mode: 0 },
            timeScale: {
                visible: true,
                rightOffset: 50,
                timeVisible: true,
                secondsVisible: false,
                fixLeftEdge: false,
                fixRightEdge: false,
                minBarSpacing: 0.5,
            },
            handleScroll: {
                mouseWheel: false,
                pressedMouseMove: true,
                horzTouchDrag: true,
                vertTouchDrag: false,
            },
            handleScale: {
                axisPressedMouseMove: true,
                mouseWheel: true,
                pinch: true,
            },
        });
        macdChartInstanceRef.current = macdChart;

        macdLinesRef.current.macd = macdChart.addSeries(LineSeries, {
            color: '#2196f3', lineWidth: 2, title: '',
            lastValueVisible: false, priceLineVisible: false, crosshairMarkerVisible: false
        });
        macdLinesRef.current.signal = macdChart.addSeries(LineSeries, {
            color: '#ffeb3b', lineWidth: 2, title: '',
            lastValueVisible: false, priceLineVisible: false, crosshairMarkerVisible: false
        });
        macdLinesRef.current.macd5 = macdChart.addSeries(LineSeries, {
            color: '#4caf50', lineWidth: 1.5, title: '',
            lastValueVisible: false, priceLineVisible: false, crosshairMarkerVisible: false
        });
        macdLinesRef.current.macd55 = macdChart.addSeries(LineSeries, {
            color: '#f44336', lineWidth: 1.5, title: '',
            lastValueVisible: false, priceLineVisible: false, crosshairMarkerVisible: false
        });

        let isSyncing = false;
        mainChart.timeScale().subscribeVisibleLogicalRangeChange(range => {
            if (isSyncing || !range) return;
            isSyncing = true;
            try {
                macdChart.timeScale().setVisibleLogicalRange(range);
            } catch (e) { /* Ignore sync errors during initial load */ }
            isSyncing = false;
        });
        macdChart.timeScale().subscribeVisibleLogicalRangeChange(range => {
            if (isSyncing || !range) return;
            isSyncing = true;
            try { mainChart.timeScale().setVisibleLogicalRange(range); } catch (e) { console.warn("Sync Main failed", e); }
            isSyncing = false;
        });

        const fetchData = async () => {
            setStatus({ state: 'loading', msg: `Fetching ${symbol} history...` });
            try {
                const url = `${API_BASE}/api/history/${symbol}/${interval}?_t=${new Date().getTime()}`; // Prevent caching
                console.log("Fetching from:", url);
                const response = await axios.get(url, { timeout: 15000 });
                const data = response.data;

                if (data.length > 0) {
                    console.log("Sample data point:", data[0]);

                    const isValidData = (t, v) => t > 0 && v !== null && v !== undefined && !isNaN(parseFloat(v));
                    const sortedData = (arr) => arr.sort((a, b) => a.time - b.time);

                    try {
                        const candleData = data.map(c => ({
                            time: parseTime(c.timestamp),
                            open: parseFloat(c.open),
                            high: parseFloat(c.high),
                            low: parseFloat(c.low),
                            close: parseFloat(c.close)
                        })).filter(c => isValidData(c.time, c.close)); // Basic check

                        candleSeries.setData(sortedData(candleData));
                    } catch (e) {
                        console.error("Error setting candle data:", e);
                    }

                    emaConfigs.forEach(cfg => {
                        const key = cfg.key.toLowerCase();
                        try {
                            const validData = data
                                .map(c => ({ time: parseTime(c.timestamp), value: parseFloat(c[key]) }))
                                .filter(d => isValidData(d.time, d.value));

                            if (validData.length > 0) emaSeriesRef.current[cfg.key].setData(sortedData(validData));
                        } catch (e) {
                            console.warn(`Error setting data for ${key}:`, e);
                        }
                    });

                    try {
                        const bgData = data
                            .map(c => ({
                                time: parseTime(c.timestamp),
                                upper: parseFloat(c.close),
                                lower: parseFloat(c.stMa),
                                color: c.stTrend === 1 ? 'rgba(60, 120, 255, 0.6)' : 'rgba(255, 0, 0, 0.6)'
                            }))
                            .filter(d => d.time > 0);

                        if (bgData.length > 0) stSeriesRef.current.shade.setData(sortedData(bgData));
                    } catch (e) {
                        console.warn("Error setting ST data:", e);
                    }

                    const macdKeys = { macd: 'macdLine', signal: 'signalLine', macd5: 'macd5', macd55: 'macd55' };
                    Object.keys(macdKeys).forEach(k => {
                        const dbKey = macdKeys[k];
                        try {
                            const mData = data
                                .map(c => ({ time: parseTime(c.timestamp), value: parseFloat(c[dbKey]) }))
                                .filter(d => isValidData(d.time, d.value));
                            if (mData.length > 0) macdLinesRef.current[k].setData(sortedData(mData));
                        } catch (e) { console.warn(`Error setting MACD ${k}:`, e); }
                    });

                    setStatus({ state: 'ok', msg: `Connected to ${API_BASE}` });
                    setStats(s => ({ ...s, candles: data.length }));
                } else {
                    setStatus({ state: 'warn', msg: 'No historical data returned from API.' });
                }
            } catch (err) {
                console.error("History fetch error:", err);
                setStatus({ state: 'error', msg: `Failed to connect to ${API_BASE}. Check if Backend is running.` });
            }
        };
        fetchData();

        const socket = new SockJS(WS_BASE);
        const stomp = Stomp.over(socket);
        stomp.debug = null;
        stomp.connect({}, () => {
            stomp.subscribe('/topic/updates', (msg) => {
                const update = JSON.parse(msg.body);
                if (update.symbol === symbol && update.interval === interval) {
                    const time = parseTime(update.ohlc.t);
                    candleSeries.update({ time, open: update.ohlc.o, high: update.ohlc.h, low: update.ohlc.l, close: update.ohlc.c });
                    Object.keys(emaSeriesRef.current).forEach(key => {
                        if (update.indicators[key]) emaSeriesRef.current[key].update({ time, value: update.indicators[key] });
                    });
                    if (update.indicators.ST_MA) {
                        const color = update.indicators.ST_TREND === 1 ? '#00ff00' : '#ff0000';
                        const bgColor = update.indicators.ST_TREND === 1 ? 'rgba(60, 120, 255, 0.6)' : 'rgba(255, 0, 0, 0.6)';

                        stSeriesRef.current.shade.update({
                            time,
                            upper: update.ohlc.c,
                            lower: update.indicators.ST_MA,
                            color: update.indicators.ST_TREND === 1 ? 'rgba(60, 120, 255, 0.6)' : 'rgba(255, 0, 0, 0.6)'
                        });
                    }
                    if (update.indicators.MACD) macdLinesRef.current.macd.update({ time, value: update.indicators.MACD });
                    if (update.indicators.SIGNAL) macdLinesRef.current.signal.update({ time, value: update.indicators.SIGNAL });
                    if (update.indicators.MACD5) macdLinesRef.current.macd5.update({ time, value: update.indicators.MACD5 });
                    if (update.indicators.MACD55) macdLinesRef.current.macd55.update({ time, value: update.indicators.MACD55 });
                    setStats(s => ({ ...s, lastTick: new Date().toLocaleTimeString() }));
                }
            });
        }, (err) => {
            console.error("WebSocket error:", err);
        });

        const resizeObserver = new ResizeObserver(entries => {
            if (entries.length === 0) return;
            const { width } = entries[0].contentRect;
            mainChart.applyOptions({ width });
            macdChart.applyOptions({ width });
        });
        resizeObserver.observe(containerRef.current);

        return () => {
            resizeObserver.disconnect();
            mainChart.remove();
            macdChart.remove();
            if (stomp.connected) stomp.disconnect();
        };
    }, [symbol, interval, mainChartHeight, macdChartHeight]);

    const handleZoom = (zoomIn) => {
        const chart = chartInstanceRef.current;
        if (!chart) return;

        const timeScale = chart.timeScale();
        const range = timeScale.getVisibleLogicalRange();
        if (!range) return;

        const span = range.to - range.from;
        const center = range.from + span / 2;
        const factor = zoomIn ? 0.8 : 1.25; // 20% zoom
        const newSpan = span * factor;

        timeScale.setVisibleLogicalRange({
            from: center - newSpan / 2,
            to: center + newSpan / 2,
        });
    };

    // Visibility State
    const [visibility, setVisibility] = useState({
        EMA15: true, EMA15_5: true, EMA15_15: true,
        EMA15_15_15: true, EMA15_30: true,
        EMA100: true,
        ST_SHADE: true,
        MACD: true, SIGNAL: true, MACD5: true, MACD55: true
    });

    // Apply Visibility Changes
    useEffect(() => {
        // EMAs
        Object.keys(emaSeriesRef.current).forEach(key => {
            if (emaSeriesRef.current[key]) {
                emaSeriesRef.current[key].applyOptions({ visible: visibility[key] });
            }
        });

        // SuperTrend
        if (stSeriesRef.current) {
            if (stSeriesRef.current.shade) stSeriesRef.current.shade.applyOptions({ visible: visibility.ST_SHADE });
        }

        // MACD Lines
        Object.keys(macdLinesRef.current).forEach(key => {
            if (macdLinesRef.current[key]) {
                const visKey = key.toUpperCase();
                macdLinesRef.current[key].applyOptions({ visible: visibility[visKey] });
            }
        });
    }, [visibility]);

    const toggleVisibility = (key) => {
        setVisibility(prev => ({ ...prev, [key]: !prev[key] }));
    };

    const handleMouseDown = (e) => {
        isDraggingRef.current = true;
        e.preventDefault();
    };

    const handleMouseMove = (e) => {
        if (!isDraggingRef.current || !containerRef.current) return;

        const containerRect = containerRef.current.getBoundingClientRect();
        const newMainHeight = e.clientY - containerRect.top;
        const dividerHeight = 10; // Height of the divider
        const minHeight = 100; // Minimum height for each panel

        // Calculate available space
        const totalHeight = containerRect.height;
        const newMacdHeight = totalHeight - newMainHeight - dividerHeight;

        // Ensure both panels maintain minimum height
        if (newMainHeight >= minHeight && newMacdHeight >= minHeight) {
            setMainChartHeight(newMainHeight);
            setMacdChartHeight(newMacdHeight);
        }
    };

    const handleMouseUp = () => {
        isDraggingRef.current = false;
    };

    useEffect(() => {
        document.addEventListener('mousemove', handleMouseMove);
        document.addEventListener('mouseup', handleMouseUp);

        return () => {
            document.removeEventListener('mousemove', handleMouseMove);
            document.removeEventListener('mouseup', handleMouseUp);
        };
    }, []);

    return (
        <div ref={containerRef} style={{ display: 'flex', flexDirection: 'column', width: '100%', position: 'relative' }}>
            {/* Status Panel Overlay */}
            <div style={{
                position: 'absolute', top: '10px', left: '10px', zIndex: 100,
                padding: '8px 12px', borderRadius: '4px', fontSize: '12px',
                background: 'rgba(0,0,0,0.7)', border: '1px solid #333',
                color: status.state === 'error' ? '#ef5350' : (status.state === 'ok' ? '#26a69a' : '#ffeb3b'),
                display: 'flex', flexDirection: 'column', gap: '4px'
            }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <span style={{
                        width: '8px', height: '8px', borderRadius: '50%',
                        backgroundColor: status.state === 'error' ? '#ef5350' : (status.state === 'ok' ? '#26a69a' : '#ffeb3b'),
                        boxShadow: '0 0 5px currentColor'
                    }}
                    />
                    <strong>{status.msg}</strong>
                </div>
                {status.state === 'ok' && (
                    <div style={{ color: '#aaa', fontSize: '10px' }}>
                        Candles: {stats.candles} | Last Tick: {stats.lastTick || 'Waiting...'}
                    </div>
                )}
            </div>

            <div ref={mainChartRef} style={{ borderRadius: '8px', overflow: 'hidden', height: `${mainChartHeight}px`, marginBottom: '2px' }} />

            {/* Resizable Divider */}
            <div
                onMouseDown={handleMouseDown}
                style={{
                    height: '10px',
                    background: 'linear-gradient(to bottom, #3a3a5a 0%, #2a2a4a 50%, #3a3a5a 100%)',
                    cursor: 'ns-resize',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    position: 'relative',
                    userSelect: 'none',
                    transition: 'background 0.2s ease',
                    border: '1px solid #4a4a6a',
                    borderLeft: 'none',
                    borderRight: 'none',
                    margin: '2px 0',
                }}
                onMouseEnter={(e) => {
                    e.currentTarget.style.background = 'linear-gradient(to bottom, #4a4a7a 0%, #3a3a6a 50%, #4a4a7a 100%)';
                }}
                onMouseLeave={(e) => {
                    if (!isDraggingRef.current) {
                        e.currentTarget.style.background = 'linear-gradient(to bottom, #3a3a5a 0%, #2a2a4a 50%, #3a3a5a 100%)';
                    }
                }}
            >
                <div style={{
                    width: '50px',
                    height: '4px',
                    background: '#888',
                    borderRadius: '2px',
                    pointerEvents: 'none',
                    boxShadow: '0 1px 2px rgba(0,0,0,0.3)'
                }} />
            </div>

            <div ref={macdChartRef} style={{ borderRadius: '8px', overflow: 'hidden', height: `${macdChartHeight}px`, marginTop: '2px' }} />

            {/* Controls */}
            <div style={{
                position: 'absolute', bottom: '30px', left: '50%', transform: 'translateX(-50%)', zIndex: 100,
                display: 'flex', flexWrap: 'wrap', gap: '10px', background: 'rgba(43, 43, 67, 0.9)', padding: '8px', borderRadius: '8px',
                boxShadow: '0 4px 6px rgba(0,0,0,0.3)', justifyContent: 'center'
            }}>
                <div style={{ display: 'flex', gap: '5px' }}>
                    <button onClick={() => handleZoom(true)} style={zoomBtnStyle}>+</button>
                    <button onClick={() => handleZoom(false)} style={zoomBtnStyle}>-</button>
                </div>

                <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap', justifyContent: 'center' }}>
                    {/* EMA Toggles */}
                    {['EMA15', 'EMA15_5', 'EMA15_15', 'EMA15_15_15', 'EMA15_30', 'EMA100'].map(key => (
                        <div key={key} style={{ display: 'flex', alignItems: 'center' }}>
                            <input
                                type="checkbox"
                                id={`toggle-${key}`}
                                checked={visibility[key]}
                                onChange={() => toggleVisibility(key)}
                                style={{ marginRight: '3px', cursor: 'pointer' }}
                            />
                            <label htmlFor={`toggle-${key}`} style={{ color: '#d1d4dc', fontSize: '10px', cursor: 'pointer', userSelect: 'none' }}>
                                {key.replace('EMA', 'E')}
                            </label>
                        </div>
                    ))}

                    {/* ST Toggles */}
                    <div style={{ display: 'flex', alignItems: 'center', marginLeft: '5px', borderLeft: '1px solid #555', paddingLeft: '5px' }}>
                        <div style={{ display: 'flex', alignItems: 'center' }}>
                            <input
                                type="checkbox"
                                id="toggle-ST_SHADE"
                                checked={visibility.ST_SHADE}
                                onChange={() => toggleVisibility('ST_SHADE')}
                                style={{ marginRight: '3px', cursor: 'pointer' }}
                            />
                            <label htmlFor="toggle-ST_SHADE" style={{ color: 'rgba(0, 255, 0, 0.6)', fontSize: '10px', fontWeight: 'bold', cursor: 'pointer', userSelect: 'none' }}>Cloud</label>
                        </div>
                    </div>

                    {/* MACD Toggles */}
                    <div style={{ display: 'flex', gap: '8px', alignItems: 'center', marginLeft: '5px', borderLeft: '1px solid #555', paddingLeft: '5px' }}>
                        {[{ key: 'MACD', label: 'M', color: '#2196f3' }, { key: 'SIGNAL', label: 'S', color: '#ffeb3b' }, { key: 'MACD5', label: 'M5', color: '#4caf50' }, { key: 'MACD55', label: 'M55', color: '#f44336' }].map(({ key, label, color }) => (
                            <div key={key} style={{ display: 'flex', alignItems: 'center' }}>
                                <input
                                    type="checkbox"
                                    id={`toggle-${key}`}
                                    checked={visibility[key]}
                                    onChange={() => toggleVisibility(key)}
                                    style={{ marginRight: '3px', cursor: 'pointer' }}
                                />
                                <label htmlFor={`toggle-${key}`} style={{ color, fontSize: '10px', fontWeight: 'bold', cursor: 'pointer', userSelect: 'none' }}>{label}</label>
                            </div>
                        ))}
                    </div>
                </div>
            </div>
        </div>
    );
};

const zoomBtnStyle = {
    width: '30px', height: '30px', borderRadius: '4px', border: '1px solid #333',
    background: '#2b2b43', color: '#d1d4dc', fontSize: '18px', cursor: 'pointer',
    display: 'flex', alignItems: 'center', justifyContent: 'center'
};

export default TradingChart;
