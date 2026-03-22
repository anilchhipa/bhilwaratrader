import React, { useEffect, useState, useMemo, useRef, useCallback } from 'react';
import ReactECharts from 'echarts-for-react';
import * as echarts from 'echarts';
import SockJS from 'sockjs-client';
import Stomp from 'stompjs';
import axios from 'axios';
import { API_BASE, WS_BASE } from '../config';
import { formatSymbol } from '../helpers/formatSymbol';

const ETradingChart = React.memo(({ symbol = 'SENSEX', initialInterval = '3min', isGrid = false, externalVisibility, onVisibilityUpdate, onMaximize, isMaximized, onPriceUpdate, hideHeader = false, themeColors }) => {
    const [interval, setTimeframe] = useState(initialInterval);
    const [data, setData] = useState([]);
    const [hoverIndex, setHoverIndex] = useState(null);
    const [status, setStatus] = useState({ state: 'loading', msg: 'Loading ECharts...' });
    const [countdown, setCountdown] = useState('');
    const [chartVersion, setChartVersion] = useState(0);
    const forceUpdate = useCallback(() => setChartVersion(v => v + 1), []);
    const echartRef = useRef(null);
    const containerRef = useRef(null);

    // Replay State
    const [isReplayMode, setIsReplayMode] = useState(false);
    const [replayIndex, setReplayIndex] = useState(-1);
    const [replaySpeed, setReplaySpeed] = useState(500); // ms per step
    const [isReplaying, setIsReplaying] = useState(false);
    const replayFullDataRef = useRef([]);
    const syncChannelRef = useRef(null);
    const dataSymbolRef = useRef(null);


    // Y-Axis Scaling State
    const isScalingY = useRef(false);
    const dragStartY = useRef(0);
    const initialZoomState = useRef(null);

    // X-Axis Scaling State
    const isScalingX = useRef(false);
    const dragStartX = useRef(0);
    const initialZoomStateX = useRef(null);
    // Viewport Persistence (prevents reset on live update)
    const userZoomRef = useRef(null); // X-axis (Time) { start, end }
    const userYAxisRangeRef = useRef(null); // Y-axis (Price) { min, max } or null



    // Manual Panning State (Main Chart)
    const isPanningY = useRef(false);
    const panStartY = useRef(0);
    const initialPriceRange = useRef(null);
    const isPanningX = useRef(false);
    const panStartX = useRef(0);
    const initialChartZoomX = useRef(null);
    // Initial Fetch
    useEffect(() => {
        const fetchData = async () => {
            try {
                setStatus({ state: 'loading', msg: `Fetching ${symbol} ${interval}...` });
                const response = await axios.get(`${API_BASE}/api/history/${symbol}/${interval}?_t=${Date.now()}`);
                if (response.data && response.data.length > 0) {
                    const sorted = response.data.sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
                    dataSymbolRef.current = symbol; // Track which symbol this data belongs to
                    setData(sorted);
                    replayFullDataRef.current = sorted;
                    setStatus({ state: 'ok', msg: 'R' });
                } else {
                    setData([]);
                    replayFullDataRef.current = [];
                    setStatus({ state: 'warn', msg: 'No data for this interval' });
                }
            } catch (err) {
                console.error("Fetch failed", err);
                setStatus({ state: 'error', msg: 'Historical data fetch failed' });
            }
        };
        fetchData();
    }, [symbol, interval]);

    // Replay Timer Effect
    useEffect(() => {
        let timer;
        if (isReplayMode && isReplaying) {
            timer = setInterval(() => {
                setReplayIndex(prev => {
                    if (prev < (replayFullDataRef.current?.length || 0) - 1) {
                        return prev + 1;
                    }
                    setIsReplaying(false);
                    return prev;
                });
            }, replaySpeed);
        }
        return () => clearInterval(timer);
    }, [isReplayMode, isReplaying, replaySpeed]);

    // Replay Sync Effect
    useEffect(() => {
        if (!isReplayMode) return;

        syncChannelRef.current = new BroadcastChannel(`replay_sync_${symbol}`);
        syncChannelRef.current.onmessage = (event) => {
            if (event.data.type === 'SYNC_TIMESTAMP' && !isReplaying) {
                const targetTs = event.data.timestamp;
                // Find nearest index in our own data
                const idx = replayFullDataRef.current.findIndex(d => new Date(d.timestamp).getTime() >= targetTs);
                if (idx !== -1) setReplayIndex(idx);
            }
        };

        return () => {
            syncChannelRef.current?.close();
        };
    }, [isReplayMode, symbol, isReplaying]);

    // Broadcast our replay index changes (only if we are the one replaying or manually stepping)
    useEffect(() => {
        if (isReplayMode && replayIndex >= 0 && replayIndex < replayFullDataRef.current.length) {
            const currentTs = new Date(replayFullDataRef.current[replayIndex].timestamp).getTime();
            syncChannelRef.current?.postMessage({
                type: 'SYNC_TIMESTAMP',
                timestamp: currentTs
            });
        }
    }, [replayIndex, isReplayMode]);

    // Handle Data for Display (Replay vs Live)
    const displayData = useMemo(() => {
        if (isReplayMode && replayIndex >= 0) {
            return replayFullDataRef.current.slice(0, replayIndex + 1);
        }
        return data;
    }, [data, isReplayMode, replayIndex]);

    const stSignals = [];


    useEffect(() => {
        if (displayData.length > 0 && onPriceUpdate && !isReplayMode) {
            // Only update the parent if the data we have actually belongs to the current symbol
            if (dataSymbolRef.current === symbol) {
                const lastCandle = displayData[displayData.length - 1];
                if (lastCandle && lastCandle.close !== undefined) {
                    onPriceUpdate(symbol, lastCandle.close, lastCandle.close >= lastCandle.open);
                }
            }
        }
    }, [displayData, symbol, onPriceUpdate, isReplayMode]);



    // Reset state & zoom when symbol or interval changes so we always start at the latest candles
    useEffect(() => {
        setData([]);
        setIsReplayMode(false);
        setReplayIndex(-1);
        setIsReplaying(false);
        userZoomRef.current = null;
        userYAxisRangeRef.current = null;
    }, [symbol, interval]);

    // Candle Countdown Timer
    useEffect(() => {
        const intervalMinutes = parseInt(interval, 10) || 1;
        const intervalSeconds = intervalMinutes * 60;

        const tick = () => {
            const now = new Date();
            const totalSecs = now.getMinutes() * 60 + now.getSeconds();
            const secsElapsed = totalSecs % intervalSeconds;
            const secsRemaining = intervalSeconds - secsElapsed;
            const mm = String(Math.floor(secsRemaining / 60)).padStart(2, '0');
            const ss = String(secsRemaining % 60).padStart(2, '0');
            setCountdown(`${mm}:${ss}`);
        };

        tick();
        const timer = setInterval(tick, 1000);
        return () => clearInterval(timer);
    }, [interval]);

    // WebSocket Updates with auto-reconnect
    useEffect(() => {
        if (isReplayMode) return;

        let active = true;
        let stomp = null;
        let reconnectTimer = null;
        let retryDelay = 2000;

        const connect = () => {
            if (!active) return;
            const socket = new SockJS(WS_BASE);
            stomp = Stomp.over(socket);
            stomp.debug = null;

            stomp.connect({}, () => {
                if (!active) { stomp.disconnect(); return; }
                retryDelay = 2000; // Reset on success

                stomp.subscribe(`/topic/updates/${symbol}/${interval}`, (msg) => {
                    if (!active) return;
                    const update = JSON.parse(msg.body);
                    if (update.symbol === symbol && update.interval === interval) {
                        setData(prev => {
                            const newPoint = {
                                timestamp: update.ohlc.t,
                                open: update.ohlc.o, high: update.ohlc.h, low: update.ohlc.l, close: update.ohlc.c,
                                ema15: update.indicators.EMA15,
                                ema15_5: update.indicators.EMA15_5,
                                ema15_15: update.indicators.EMA15_15,
                                ema15_15_15: update.indicators.EMA15_15_15,
                                ema15_15_15_15: update.indicators.EMA15_15_15_15,
                                ema15_15_15_15_30: update.indicators.EMA15_15_15_15_30,
                                stMa: update.indicators.ST_MA,
                                stTrend: update.indicators.ST_TREND,
                                sellerFear: update.indicators.SELLER_FEAR,

                            };

                            if (prev.length === 0) {
                                const res = [newPoint];
                                replayFullDataRef.current = res;
                                return res;
                            }

                            const last = prev[prev.length - 1];
                            let updated;
                            const isSameTime = new Date(last.timestamp).getTime() === new Date(update.ohlc.t).getTime();
                            if (isSameTime) {
                                updated = [...prev];

                                updated[updated.length - 1] = newPoint;
                            } else {
                                updated = [...prev, newPoint];
                            }
                            replayFullDataRef.current = updated;
                            return updated;
                        });
                    }
                });
            }, (err) => {
                if (!active) return;
                console.warn(`WS disconnected, reconnecting in ${retryDelay / 1000}s...`);
                reconnectTimer = setTimeout(() => {
                    retryDelay = Math.min(retryDelay * 2, 30000); // cap at 30s
                    connect();
                }, retryDelay);
            });
        };

        connect();

        return () => {
            active = false;
            clearTimeout(reconnectTimer);
            if (stomp && stomp.connected) stomp.disconnect();
        };
    }, [symbol, interval, isReplayMode]);



    // Indicator Config
    const visibility = externalVisibility || {
        EMA15: true, EMA15_5: true, EMA15_15: true, EMA15_15_15: true, EMA15_15_15_15: true,
        EMA15_15_15_15_30: true, CLOUD: true
    };







    // Hover State for Axis Indicators
    const [hoveredAxis, setHoveredAxis] = useState(null); // 'y_main', 'y_macd', 'x', or null

    const mouseHandlersRef = useRef({ move: null, up: null });

    mouseHandlersRef.current.move = (e) => {
        if (!containerRef.current) return;
        const rect = containerRef.current.getBoundingClientRect();
        const y = e.clientY - rect.top;




        // Handle Manual Panning (Both X and Y)
        if (isPanningY.current || isPanningX.current) {
            const echarts = echartRef.current?.getEchartsInstance();
            if (echarts) {
                // Vertical Pan
                if (isPanningY.current && initialPriceRange.current) {
                    const deltaY = e.clientY - panStartY.current;
                    const { min, max } = initialPriceRange.current;
                    const priceRange = max - min;
                    const mainChartGridHeight = rect.height * 0.9;
                    const priceDelta = (deltaY / mainChartGridHeight) * priceRange;
                    const newMin = min + priceDelta;
                    const newMax = max + priceDelta;

                    if (!isNaN(newMin) && !isNaN(newMax)) {
                        echarts.setOption({ yAxis: [{ min: newMin, max: newMax }] });
                        userYAxisRangeRef.current = { min: newMin, max: newMax };
                    }
                }

                // Horizontal Pan (using dataZoom)
                if (isPanningX.current && initialChartZoomX.current) {
                    const deltaX = panStartX.current - e.clientX;
                    const gridWidth = rect.width - 55;
                    const zoomRange = initialChartZoomX.current.end - initialChartZoomX.current.start;
                    // deltaX / gridWidth gives the fraction of the visible range shifted
                    const percentShift = (deltaX / gridWidth) * zoomRange;

                    let newStart = initialChartZoomX.current.start + percentShift;
                    let newEnd = initialChartZoomX.current.end + percentShift;

                    // Bound to [0, 100]
                    if (newStart < 0) { newEnd -= newStart; newStart = 0; }
                    if (newEnd > 100) { newStart -= (newEnd - 100); newEnd = 100; }

                    if (!isNaN(newStart) && !isNaN(newEnd)) {
                        echarts.dispatchAction({
                            type: 'dataZoom',
                            dataZoomIndex: 0,
                            start: newStart,
                            end: newEnd
                        });
                        userZoomRef.current = { start: newStart, end: newEnd };
                    }
                }

                echarts.getZr().setCursorStyle('grabbing');
                return;
            }
        }

        // Handle Y-Axis Scaling
        if (isScalingY.current) {
            const deltaY = dragStartY.current - e.clientY;
            const sensitivity = 0.005;
            const scaleFactor = Math.exp(deltaY * sensitivity);

            const echarts = echartRef.current?.getEchartsInstance();
            if (echarts && initialZoomState.current) {
                const { min, max } = initialZoomState.current;
                const center = (min + max) / 2;
                const halfRange = ((max - min) / 2) * scaleFactor;

                const newMin = center - halfRange;
                const newMax = center + halfRange;
                echarts.setOption({
                    yAxis: [{ min: newMin, max: newMax }]
                });
                userYAxisRangeRef.current = { min: newMin, max: newMax };
            }
        }

        // Handle X-Axis Scaling
        if (isScalingX.current) {
            const deltaX = e.clientX - dragStartX.current;
            const sensitivity = 0.005;
            const scaleFactor = Math.exp(-deltaX * sensitivity);

            const echarts = echartRef.current?.getEchartsInstance();
            if (echarts && initialZoomStateX.current) {
                const { start, end } = initialZoomStateX.current;
                const range = end - start;
                const newRange = range * scaleFactor;
                const center = (start + end) / 2;

                let newStart = center - newRange / 2;
                let newEnd = center + newRange / 2;

                if (newStart < 0) { newStart = 0; newEnd = Math.min(100, newRange); }
                if (newEnd > 100) { newEnd = 100; newStart = Math.max(0, 100 - newRange); }

                echarts.dispatchAction({
                    type: 'dataZoom',
                    dataZoomIndex: 0, // X-axis index (Time)
                    start: newStart,
                    end: newEnd
                });
            }
        }
    };

    mouseHandlersRef.current.up = () => {
        isScalingY.current = false;
        isScalingX.current = false;
        isPanningY.current = false;
        isPanningX.current = false;
        initialPriceRange.current = null;
        initialChartZoomX.current = null;
    };

    useEffect(() => {
        const move = (e) => mouseHandlersRef.current.move && mouseHandlersRef.current.move(e);
        const up = (e) => mouseHandlersRef.current.up && mouseHandlersRef.current.up(e);

        window.addEventListener('mousemove', move);
        window.addEventListener('mouseup', up);
        return () => {
            window.removeEventListener('mousemove', move);
            window.removeEventListener('mouseup', up);
        };
    }, []);

    const option = useMemo(() => {
        if (displayData.length === 0) {
            return {
                backgroundColor: '#131722',
                xAxis: { show: false },
                yAxis: { show: false },
                series: []
            };
        }

        const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
        const timestamps = displayData.map(c => {
            const d = new Date(c.timestamp);
            const dd = d.getDate().toString().padStart(2, '0');
            const mon = MONTHS[d.getMonth()];
            const hh = d.getHours().toString().padStart(2, '0');
            const mm = d.getMinutes().toString().padStart(2, '0');
            return `${dd} ${mon} ${hh}:${mm}`;
        });
        const ohlc = displayData.map(c => [c.open, c.close, c.low, c.high]);

        const dayMarkers = [];
        for (let i = 0; i < displayData.length; i++) {
            const dateCurr = new Date(displayData[i].timestamp).getDate();
            const datePrev = i > 0 ? new Date(displayData[i - 1].timestamp).getDate() : null;
            if (i === 0 || (datePrev !== null && datePrev !== dateCurr)) {
                dayMarkers.push({
                    xAxis: i,
                    y: '94%', // Position at the very bottom of the grid
                    symbol: 'rect',
                    symbolSize: [18, 14],
                    itemStyle: { color: '#3b82f6', borderRadius: 2 },
                    label: { show: true, formatter: 'D', color: '#fff', fontSize: 10, fontWeight: 'bold' },
                    emphasis: { disabled: true }
                });
            }
        }


        // Add 'Right Offset' buffer (whitespace for future candles)
        const bufferSize = 50;
        const lastTime = displayData.length > 0 ? new Date(displayData[displayData.length - 1].timestamp).getTime() : Date.now();
        for (let i = 1; i <= bufferSize; i++) {
            const futureDate = new Date(lastTime + i * 60000); // Rough estimate for labels
            timestamps.push(""); // Keep it empty for future slots
            ohlc.push([null, null, null, null]);
        }

        return {
            backgroundColor: '#131722',
            animation: false,
            replaceMerge: ['xAxis', 'yAxis', 'grid', 'series', 'dataZoom'],
            tooltip: {
                show: true,
                trigger: 'axis',
                showContent: false, // Keep the box hidden
                axisPointer: {
                    type: 'cross',
                    lineStyle: { color: '#4d5057', type: 'dashed' },
                    label: {
                        backgroundColor: '#333',
                        formatter: (params) => {
                            // For x-axis pointer: show full date + time
                            if (params.axisDimension === 'x') {
                                return params.value || '';
                            }
                            // For y-axis pointer: show number
                            return typeof params.value === 'number'
                                ? params.value.toFixed(2)
                                : params.value;
                        }
                    }
                }
            },
            axisPointer: { link: [], label: { backgroundColor: '#333' } },
            grid: [
                { left: '5px', right: '35px', top: '5%', height: '90%' }
            ],
            xAxis: [
                {
                    type: 'category',
                    data: timestamps,
                    boundaryGap: true,
                    axisLine: { lineStyle: { color: '#333' } },
                    axisLabel: {
                        color: '#d1d4dc',
                        fontSize: 10,
                        formatter: (val) => {
                            if (!val || val === "") return "";
                            // Just show time HH:mm
                            const parts = val.split(' ');
                            return parts.length >= 3 ? parts[2] : val;
                        }
                    },
                    splitLine: { show: false }
                }
            ],

            yAxis: [
                {
                    scale: true,
                    position: 'right',
                    min: userYAxisRangeRef.current ? userYAxisRangeRef.current.min : null,
                    max: userYAxisRangeRef.current ? userYAxisRangeRef.current.max : null,
                    axisLine: { show: false },
                    axisLabel: { color: '#d1d4dc', fontSize: 10, formatter: v => v >= 1000 ? (v / 1000).toFixed(1) + 'k' : v.toFixed(0) },
                    splitLine: { show: false }
                }
            ],
            dataZoom: [
                // X-Axis Zoom (Time) - Default Mouse Wheel
                {
                    type: 'inside',
                    xAxisIndex: [0],
                    filterMode: 'filter',
                    orient: 'horizontal',
                    zoomOnMouseWheel: true,
                    moveOnMouseMove: true,
                    start: userZoomRef.current ? userZoomRef.current.start : Math.max(0, 100 - (16000 / timestamps.length)),
                    end: userZoomRef.current ? userZoomRef.current.end : 100
                },
                // Y-Axis Zoom (Price) - Manual Only (via Buttons/Drag if enabled, but user dislikes wheel)
                {
                    type: 'inside',
                    yAxisIndex: [0],
                    filterMode: 'empty',
                    orient: 'vertical',
                    zoomOnMouseWheel: false,
                    moveOnMouseWheel: true, // Allow wheel move (panning)
                    moveOnMouseMove: true      // Allow drag panning
                }
            ],
            series: [
                {
                    name: symbol,
                    type: 'candlestick',
                    data: ohlc,
                    itemStyle: {
                        color: '#26a69a', color0: '#ef5350',
                        borderColor: '#26a69a', borderColor0: '#ef5350'
                    },
                    markPoint: {
                        symbol: 'arrow',
                        symbolSize: 10,
                        data: dayMarkers
                    },
                    markLine: null
                },
                // Perfectly Smooth Cloud (Native Series Stacking)
                // Uptrend: Base = stMa (lower), Cloud = offset to close (positive)
                {
                    name: 'BaseUp',
                    type: 'line',
                    z: 1,
                    data: visibility.CLOUD ? displayData.map(c => c.stTrend === 1 ? c.stMa : null) : [],
                    lineStyle: { opacity: 0 },
                    symbol: 'none',
                    stack: 'uptrend',
                    silent: true
                },
                {
                    name: 'CloudUp',
                    type: 'line',
                    z: 1,
                    data: visibility.CLOUD ? displayData.map(c => c.stTrend === 1 ? (Math.max(0, c.close - c.stMa)) : null) : [],
                    lineStyle: { opacity: 0 },
                    symbol: 'none',
                    stack: 'uptrend',
                    areaStyle: { color: themeColors?.bullish ? (themeColors.bullish + '66') : 'rgba(60, 120, 255, 0.4)', opacity: 1 },
                    silent: true
                },
                // Downtrend: Base = close (lower), Cloud = offset to stMa (positive)
                {
                    name: 'BaseDown',
                    type: 'line',
                    z: 1,
                    data: visibility.CLOUD ? displayData.map(c => c.stTrend !== 1 ? c.close : null) : [],
                    lineStyle: { opacity: 0 },
                    symbol: 'none',
                    stack: 'downtrend',
                    silent: true
                },
                {
                    name: 'CloudDown',
                    type: 'line',
                    z: 1,
                    data: visibility.CLOUD ? displayData.map(c => c.stTrend !== 1 ? (Math.max(0, c.stMa - c.close)) : null) : [],
                    lineStyle: { opacity: 0 },
                    symbol: 'none',
                    stack: 'downtrend',
                    areaStyle: { color: themeColors?.bearish ? (themeColors.bearish + '66') : 'rgba(255, 0, 0, 0.4)', opacity: 1 },
                    silent: true
                },
                // Cascaded EMA lines
                { name: 'EMA15', type: 'line', data: visibility.EMA15 ? displayData.map(c => c.ema15) : null, itemStyle: { color: '#ffffff' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                { name: 'EMA15_5', type: 'line', data: visibility.EMA15_5 ? displayData.map(c => c.ema15_5) : null, itemStyle: { color: '#ef5350' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                { name: 'EMA15_15', type: 'line', data: visibility.EMA15_15 ? displayData.map(c => c.ema15_15) : null, itemStyle: { color: '#2196f3' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                { name: 'EMA15_15_15', type: 'line', data: visibility.EMA15_15_15 ? displayData.map(c => c.ema15_15_15) : null, itemStyle: { color: '#4caf50' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                { name: 'EMA15_15_15_15', type: 'line', data: visibility.EMA15_15_15_15 ? displayData.map(c => c.ema15_15_15_15) : null, itemStyle: { color: '#ffeb3b' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                { name: 'EMA15_15_15_15_30', type: 'line', data: visibility.EMA15_15_15_15_30 ? displayData.map(c => c.ema15_15_15_15_30) : null, itemStyle: { color: '#00bcd4' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },




            ]
        };
    }, [displayData, symbol, visibility, interval]);

    return (
        <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', gap: isGrid ? '5px' : '10px', position: 'relative' }}>
            {/* Legend & Stats Overlay - Smaller in Grid */}
            {!hideHeader && (
                <div style={{
                    padding: isGrid ? '4px 12px' : '6px 20px',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    background: 'rgba(30,34,45, 0.4)',
                    backdropFilter: 'blur(8px)',
                    borderRadius: '10px',
                    border: '1px solid rgba(255,255,255,0.03)'
                }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
                        <h2 style={{ margin: 0, color: '#f8fafc', fontSize: isGrid ? '12px' : '16px', fontWeight: 700, letterSpacing: '-0.3px' }}>
                            {formatSymbol(symbol)} <span style={{ color: '#fbbf24', fontWeight: 600, marginLeft: '6px' }}>{interval}</span>
                        </h2>
                        {!isGrid && (
                            <div className="pill-bar">
                                {['1min', '3min', '5min', '15min'].map(tf => (
                                    <button
                                        key={tf}
                                        className={`pill-btn ${interval === tf ? 'active' : ''}`}
                                        onClick={() => setTimeframe(tf)}
                                    >
                                        {tf}
                                    </button>
                                ))}
                            </div>
                        )}
                    </div>
                    <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>


                        <div style={{ color: status.state === 'error' ? '#ef5350' : '#26a69a', fontSize: isGrid ? '10px' : '12px', fontWeight: 'bold', height: '28px', display: 'flex', alignItems: 'center' }}>{status.msg}</div>
                        {!isGrid && <div style={{ color: '#aaa', fontSize: '11px', height: '28px', display: 'flex', alignItems: 'center' }}>{displayData.length} Bars</div>}


                        <div style={{ width: '1px', height: '14px', background: 'rgba(255,255,255,0.1)', margin: '0 6px' }} />

                        {/* Compact Replay Controls */}
                        <div style={{ display: 'flex', alignItems: 'center', gap: isGrid ? '4px' : '10px', background: 'rgba(0,0,0,0.2)', height: '28px', padding: isGrid ? '0 4px' : '0 10px', borderRadius: '6px', border: '1px solid rgba(255,255,255,0.05)' }}>
                            <button
                                onClick={() => {
                                    if (isReplayMode) {
                                        setIsReplayMode(false);
                                        setIsReplaying(false);
                                    } else {
                                        setIsReplayMode(true);
                                        setReplayIndex(Math.max(0, data.length - 100));
                                    }
                                }}
                                title={isReplayMode ? "Exit Replay Mode" : "Enter Replay Mode"}
                                style={{
                                    background: isReplayMode ? 'var(--accent-crimson)' : 'var(--accent-neon-blue)',
                                    color: 'white', border: 'none', borderRadius: '4px',
                                    height: '22px',
                                    minWidth: isGrid ? '22px' : '70px',
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'center',
                                    padding: isGrid ? '0' : '0 8px',
                                    fontSize: isGrid ? '9px' : '10px',
                                    cursor: 'pointer',
                                    fontWeight: '800',
                                    textTransform: 'uppercase',
                                    letterSpacing: '0.4px',
                                    boxShadow: isReplayMode ? '0 2px 8px rgba(239, 68, 68, 0.2)' : '0 2px 8px rgba(59, 130, 246, 0.2)'
                                }}
                            >
                                {isReplayMode ? (isGrid ? '⏹' : '⏹ EXIT') : (isGrid ? '🎬' : '🎬 REPLAY')}
                            </button>

                            {isReplayMode && (
                                <>
                                    <button
                                        onClick={() => { setIsReplaying(false); setReplayIndex(prev => Math.max(0, prev - 1)); }}
                                        style={{ background: 'transparent', border: 'none', color: 'white', cursor: 'pointer', fontSize: isGrid ? '12px' : '16px', padding: '0 4px' }}
                                        title="Step Backward"
                                    >
                                        ⏪
                                    </button>
                                    <button
                                        onClick={() => setIsReplaying(!isReplaying)}
                                        style={{ background: 'transparent', border: 'none', color: 'white', cursor: 'pointer', fontSize: isGrid ? '12px' : '16px', padding: '0 4px' }}
                                        title={isReplaying ? "Pause" : "Play"}
                                    >
                                        {isReplaying ? '⏸' : '▶️'}
                                    </button>
                                    <button
                                        onClick={() => { setIsReplaying(false); setReplayIndex(prev => Math.min(replayFullDataRef.current.length - 1, prev + 1)); }}
                                        style={{ background: 'transparent', border: 'none', color: 'white', cursor: 'pointer', fontSize: isGrid ? '12px' : '16px', padding: '0 4px' }}
                                        title="Step Forward"
                                    >
                                        ⏩
                                    </button>

                                    {!isGrid && (
                                        <>
                                            <div style={{ width: '1px', height: '16px', background: '#4d5057', margin: '0 4px' }} />
                                            <input
                                                type="range"
                                                min="0"
                                                max={Math.max(0, replayFullDataRef.current.length - 1)}
                                                value={replayIndex}
                                                onChange={(e) => { setIsReplaying(false); setReplayIndex(parseInt(e.target.value)); }}
                                                style={{ width: '100px', height: '4px', cursor: 'pointer' }}
                                            />
                                            <select
                                                value={replaySpeed}
                                                onChange={(e) => setReplaySpeed(parseInt(e.target.value))}
                                                style={{ background: '#1e222d', color: '#d1d4dc', border: '1px solid #4d5057', fontSize: '10px', borderRadius: '3px', padding: '2px' }}
                                            >
                                                <option value="1000">1x</option>
                                                <option value="500">2x</option>
                                                <option value="200">5x</option>
                                                <option value="100">10x</option>
                                                <option value="50">20x</option>
                                            </select>
                                        </>
                                    )}
                                </>
                            )}
                        </div>

                        {/* Integrated Maximize/Minimize */}
                        {onMaximize && (
                            <div style={{ display: 'flex', alignItems: 'center', height: '28px' }}>
                                <div style={{ width: '1px', height: '14px', background: 'rgba(255,255,255,0.1)', margin: '0 6px' }} />
                                {isMaximized ? (
                                    <button
                                        className="minimize-btn"
                                        onClick={onMaximize}
                                        style={{ height: '22px', padding: '0 8px', background: 'rgba(239, 68, 68, 0.1)', color: '#fb7185', border: '1px solid rgba(239, 68, 68, 0.2)', borderRadius: '4px', fontSize: '9px', fontWeight: '800', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px' }}
                                    >
                                        <span>✕</span> <span>EXIT {interval.toUpperCase()}</span>
                                    </button>
                                ) : (
                                    <button
                                        className="maximize-btn"
                                        onClick={onMaximize}
                                        style={{ width: '22px', height: '22px', background: 'rgba(255,255,255,0.05)', color: '#fff', border: '1px solid rgba(255,255,255,0.1)', borderRadius: '4px', fontSize: '11px', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}
                                        title="Maximize Chart"
                                    >
                                        ⛶
                                    </button>
                                )}
                            </div>
                        )}

                    </div>
                </div>
            )}


            {/* Automation Execution Monitor */}




            <div
                ref={containerRef}
                style={{
                    flex: 1,
                    position: 'relative',
                    background: '#131722',
                    borderRadius: '8px',
                    overflow: 'hidden',
                    cursor: hoveredAxis === 'x' ? 'ew-resize' : (hoveredAxis ? 'ns-resize' : 'default')
                }}
                onMouseMove={(e) => {
                    // Start with basic hover detection if not dragging
                    if (!containerRef.current) return;
                    if (isScalingX.current || isScalingY.current) return;

                    const rect = containerRef.current.getBoundingClientRect();
                    const x = e.clientX - rect.left;
                    const y = e.clientY - rect.top;

                    const mainChartGridHeight = rect.height * 0.9;

                    // Main Y-Axis Hover
                    if (x > rect.width - 55 && y < mainChartGridHeight) {
                        setHoveredAxis('y_main');
                        echartRef.current?.getEchartsInstance()?.getZr().setCursorStyle('ns-resize');
                    }
                    // X-Axis Hover
                    else if (y > rect.height - 30) {
                        setHoveredAxis('x');
                        echartRef.current?.getEchartsInstance()?.getZr().setCursorStyle('ew-resize');
                    } else if (hoveredAxis) {
                        setHoveredAxis(null);
                        echartRef.current?.getEchartsInstance()?.getZr().setCursorStyle('default');
                    }
                }}
                onMouseLeave={() => {
                    setHoveredAxis(null);
                    echartRef.current?.getEchartsInstance()?.getZr().setCursorStyle('default');
                }}
                onMouseDown={(e) => {
                    if (!containerRef.current) return;
                    const rect = containerRef.current.getBoundingClientRect();
                    const mainChartGridHeight = rect.height * 0.9;
                    const x = e.clientX - rect.left;
                    const y = e.clientY - rect.top;
                    let yMin, yMax;


                    // Check if click is in the right axis area (approx > width - 50px)
                    // And also within the top grid (Main Chart) area to avoid confusion or specific targeting
                    // Already defined at the top of onMouseDown

                    if (x > rect.width - 55 && y < mainChartGridHeight) {
                        isScalingY.current = true;
                        dragStartY.current = e.clientY;
                        const echarts = echartRef.current?.getEchartsInstance();
                        if (echarts) {
                            const opt = echarts.getOption();

                            // Capture Y state using coordinate conversion (RELIABLE)
                            // Capture Y state using coordinate conversion (RELIABLE)
                            try {
                                const pTop = echarts.convertFromPixel({ seriesIndex: 0 }, [0, 0])[1];
                                const pBot = echarts.convertFromPixel({ seriesIndex: 0 }, [0, mainChartGridHeight])[1];
                                yMin = pBot;
                                yMax = pTop;
                            } catch (e) {
                                // Model fallback 
                                const visibleModel = echarts.getModel().getComponent('yAxis', 0);
                                const extent = visibleModel?.axis?.scale?.getExtent?.();
                                yMin = extent ? extent[0] : 0;
                                yMax = extent ? extent[1] : 100;
                            }

                            // DATA-DRIVEN VALIDATION (Bulletproof)
                            // If price seems stuck in 0-100 range but data shows real prices (e.g. 1000+)
                            const lastPrice = data[data.length - 1]?.close || 0;
                            if (lastPrice > 500 && yMax <= 150) {
                                // Recover by looking at CURRENTLY VISIBLE data indices accurately
                                const dzStart = opt?.dataZoom?.[0]?.start || 0;
                                const dzEnd = opt?.dataZoom?.[0]?.end || 100;
                                const startIdx = Math.max(0, Math.floor(data.length * dzStart / 100));
                                const endIdx = Math.min(data.length, Math.ceil(data.length * dzEnd / 100));
                                const visibleData = data.slice(startIdx, endIdx);
                                if (visibleData.length > 0) {
                                    yMin = visibleData.reduce((min, d) => Math.min(min, d.low), Infinity) * 0.99;
                                    yMax = visibleData.reduce((max, d) => Math.max(max, d.high), -Infinity) * 1.01;
                                }
                            }

                            if (isNaN(yMin) || isNaN(yMax) || yMin === yMax) {
                                yMin = 0; yMax = 100;
                            }
                            initialZoomState.current = { min: yMin, max: yMax };
                        }
                        // Prevent text selection
                        e.preventDefault();

                    }


                    // Check if click is in the bottom X-axis area (Bottom 30px)
                    if (y > rect.height - 30) {
                        isScalingX.current = true;
                        dragStartX.current = e.clientX;
                        const echarts = echartRef.current?.getEchartsInstance();
                        if (echarts) {
                            const option = echarts.getOption();
                            // Index 0 is X-axis zoom
                            if (option && option.dataZoom && option.dataZoom[0]) {
                                initialZoomStateX.current = { ...option.dataZoom[0] };
                            }
                        }
                        e.preventDefault();
                    }

                    // ─── Handle Manual Panning (Both X and Y) ──────────────────────────────
                    if (!isScalingY.current && !isScalingX.current && !draggingLine.current && !placingLine.current && y < mainChartGridHeight && x < rect.width - 55) {
                        isPanningY.current = true;
                        isPanningX.current = true;
                        panStartY.current = e.clientY;
                        panStartX.current = e.clientX;
                        const echarts = echartRef.current?.getEchartsInstance();
                        if (echarts) {
                            const opt = echarts.getOption();

                            // Capture Y state using coordinate conversion (RELIABLE)
                            try {
                                const pTop = echarts.convertFromPixel({ seriesIndex: 0 }, [0, 0])[1];
                                const pBot = echarts.convertFromPixel({ seriesIndex: 0 }, [0, mainChartGridHeight])[1];
                                yMin = pBot;
                                yMax = pTop;
                            } catch (e) {
                                const visibleModel = echarts.getModel().getComponent('yAxis', 0);
                                const extent = visibleModel?.axis?.scale?.getExtent?.();
                                yMin = extent ? extent[0] : 0;
                                yMax = extent ? extent[1] : 100;
                            }

                            // DATA-DRIVEN VALIDATION (Bulletproof)
                            // If price seems stuck in 0-100 range but data shows real prices (e.g. 1000+)
                            const lastPrice = data[data.length - 1]?.close || 0;
                            if (lastPrice > 500 && yMax <= 150) {
                                const dzStart = opt?.dataZoom?.[0]?.start || 0;
                                const dzEnd = opt?.dataZoom?.[0]?.end || 100;
                                const startIdx = Math.max(0, Math.floor(data.length * dzStart / 100));
                                const endIdx = Math.min(data.length, Math.ceil(data.length * dzEnd / 100));
                                const visibleData = data.slice(startIdx, endIdx);
                                if (visibleData.length > 0) {
                                    yMin = visibleData.reduce((min, d) => Math.min(min, d.low), Infinity) * 0.99;
                                    yMax = visibleData.reduce((max, d) => Math.max(max, d.high), -Infinity) * 1.01;
                                }
                            }

                            initialPriceRange.current = { min: yMin, max: yMax };

                            // Capture X state (dataZoom)
                            if (opt?.dataZoom?.[0]) {
                                initialChartZoomX.current = { start: opt.dataZoom[0].start, end: opt.dataZoom[0].end };
                            }

                            echarts.getZr().setCursorStyle('grabbing');
                        }
                        e.preventDefault();
                    }


                }}
            >
                {/* Data Overlay removed */}

                {/* Candle Countdown Timer - hidden in replay mode */}
                {countdown && !isReplayMode && (
                    <div style={{
                        position: 'absolute',
                        top: '10px',
                        left: '10px',
                        zIndex: 10,
                        pointerEvents: 'none',
                        background: 'rgba(19, 23, 34, 0.85)',
                        border: '1px solid rgba(255,255,255,0.1)',
                        borderRadius: '4px',
                        padding: '2px 8px',
                        fontSize: '11px',
                        fontFamily: 'monospace',
                        color: '#26a69a',
                        fontWeight: 'bold',
                        letterSpacing: '0.5px'
                    }}>
                        ⏱ {countdown}
                    </div>
                )}

                {/* Replay Mode Overlay */}
                {isReplayMode && replayIndex >= 0 && replayFullDataRef.current[replayIndex] && (
                    <div style={{
                        position: 'absolute',
                        top: '10px',
                        left: '10px',
                        zIndex: 10,
                        pointerEvents: 'none',
                        background: 'rgba(239, 83, 80, 0.15)',
                        border: '1px solid #ef5350',
                        borderRadius: '4px',
                        padding: '4px 10px',
                        fontSize: '11px',
                        fontFamily: 'monospace',
                        color: '#ef5350',
                        fontWeight: 'bold',
                    }}>
                        🎬 REPLAY · {new Date(replayFullDataRef.current[replayIndex].timestamp).toLocaleString('en-IN', { hour12: false })} · Bar {replayIndex + 1}/{replayFullDataRef.current.length}
                        <div style={{ fontSize: '9px', opacity: 0.8, marginTop: '2px' }}>Click chart to jump to point</div>
                    </div>
                )}





                <ReactECharts
                    key={`${symbol}-${interval}`}
                    ref={echartRef}
                    option={option}
                    style={{
                        height: '100%',
                        width: '100%',
                        minHeight: isGrid ? '200px' : '600px'
                    }}
                    group="GRID_SYNC"
                    onEvents={{
                        'updateAxisPointer': (event) => {
                            if (event.axesInfo && event.axesInfo[0]) {
                                const index = event.axesInfo[0].value;
                                if (index !== hoverIndex) setHoverIndex(index);
                            }
                        },
                        'click': (params) => {
                            if (isReplayMode && params.dataIndex !== undefined) {
                                setIsReplaying(false);
                                setReplayIndex(params.dataIndex);
                            }
                        },
                        'dataZoom': (event) => {
                            // Persist the user's zoom position immediately
                            const echarts = echartRef.current?.getEchartsInstance();
                            if (echarts) {
                                const opt = echarts.getOption();
                                if (opt?.dataZoom?.[0]) {
                                    userZoomRef.current = {
                                        start: opt.dataZoom[0].start,
                                        end: opt.dataZoom[0].end
                                    };
                                }
                                // Also capture Y-axis if it was changed via dataZoom (index 1)
                                if (opt?.yAxis?.[0]?.min !== undefined && opt?.yAxis?.[0]?.max !== undefined) {
                                    userYAxisRangeRef.current = {
                                        min: opt.yAxis[0].min,
                                        max: opt.yAxis[0].max
                                    };
                                }
                            }
                        },
                        'finished': forceUpdate
                    }}
                    onChartReady={() => {
                        echarts.connect('GRID_SYNC');
                        echartRef.current?.getEchartsInstance().getZr().on('mousemove', () => { }); // Trigger for axisPointer
                        echartRef.current?.getEchartsInstance().getZr().on('globalout', () => setHoverIndex(null));
                    }}
                    notMerge={true}
                    lazyUpdate={true}
                />
            </div>

            {
                !isGrid && (
                    <div style={{ display: 'flex', gap: '15px', justifyContent: 'center', background: '#1e222d', padding: '10px', borderRadius: '8px' }}>

                        {Object.keys(visibility).map(key => (
                            <label key={key} style={{ color: '#d1d4dc', fontSize: '11px', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '6px' }}>
                                <input
                                    type="checkbox"
                                    checked={visibility[key]}
                                    onChange={() => {
                                        const next = { ...visibility, [key]: !visibility[key] };
                                        if (onVisibilityUpdate) onVisibilityUpdate(next);
                                        else setLocalVisibility(next);
                                    }}
                                />
                                {key.replace(/_/g, ' ')}
                            </label>
                        ))}
                    </div>
                )
            }

            {/* Zoom Controls Removed (User preferred drag interaction) */}


        </div >
    );
});

export default ETradingChart;
