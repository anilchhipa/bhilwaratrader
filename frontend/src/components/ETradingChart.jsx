import React, { useEffect, useState, useMemo, useRef, useCallback } from 'react';
import ReactECharts from 'echarts-for-react';
import * as echarts from 'echarts';
import SockJS from 'sockjs-client';
import Stomp from 'stompjs';
import axios from 'axios';
import { API_BASE, WS_BASE } from '../config';
import { formatSymbol } from '../helpers/formatSymbol';

const ETradingChart = React.memo(({ symbol = 'NIFTY', initialInterval = '3min', isGrid = false, sharedOrderLines, onOrderLinesUpdate, externalVisibility, onVisibilityUpdate, onMaximize, isMaximized }) => {
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

    // Resize State
    const [splitPct, setSplitPct] = useState(60); // 60% for main chart
    const isResizing = useRef(false);

    // Y-Axis Scaling State
    const isScalingY = useRef(false);
    const dragStartY = useRef(0);
    const initialZoomState = useRef(null);

    // X-Axis Scaling State
    const isScalingX = useRef(false);
    const dragStartX = useRef(0);
    const initialZoomStateX = useRef(null);

    // MACD Y-Axis Scaling State
    const isScalingY_MACD = useRef(false);
    const initialZoomState_MACD = useRef(null);

    // User X-Axis Zoom Persistence (prevents reset on live update)
    const userZoomRef = useRef(null); // { start, end } or null to use default

    // Manual Panning State (Main Chart)
    const isPanningY = useRef(false);
    const panStartY = useRef(0);
    const initialPriceRange = useRef(null);

    // Manual Panning State (MACD Panel)
    const isPanningY_MACD = useRef(false);
    const panStartY_MACD = useRef(0);
    const initialMACDPriceRange = useRef(null);

    // Initial Fetch
    useEffect(() => {
        const fetchData = async () => {
            try {
                // Clear old data immediately to prevent scaling/flicker issues when switching stocks
                setData([]);
                setIsReplayMode(false);
                setReplayIndex(-1);
                setIsReplaying(false);

                setStatus({ state: 'loading', msg: `Fetching ${symbol} ${interval}...` });
                const response = await axios.get(`${API_BASE}/api/history/${symbol}/${interval}?_t=${Date.now()}`);
                if (response.data && response.data.length > 0) {
                    const sorted = response.data.sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
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

    // Reset zoom when symbol or interval changes so we always start at the latest candles
    useEffect(() => {
        userZoomRef.current = null;
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

    // WebSocket Updates
    useEffect(() => {
        if (isReplayMode) return; // Pause live data during replay

        let active = true;
        const socket = new SockJS(WS_BASE);
        const stomp = Stomp.over(socket);
        stomp.debug = null;

        stomp.connect({}, () => {
            if (!active) {
                stomp.disconnect();
                return;
            }

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
                            ema50: update.indicators.EMA50,
                            ema100: update.indicators.EMA100,
                            stMa: update.indicators.ST_MA,
                            stTrend: update.indicators.ST_TREND,
                            macdLine: update.indicators.MACD,
                            signalLine: update.indicators.SIGNAL,
                            macd5: update.indicators.MACD5,
                            macd55: update.indicators.MACD55
                        };

                        if (prev.length === 0) {
                            const res = [newPoint];
                            replayFullDataRef.current = res;
                            return res;
                        }

                        const last = prev[prev.length - 1];
                        let updated;
                        if (last.timestamp === update.ohlc.t) {
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
            if (active) console.error("WS connection failed", err);
        });

        return () => {
            active = false;
            if (stomp.connected) stomp.disconnect();
        };
    }, [symbol, interval, isReplayMode]);



    // Indicator Config (Sync with backend)
    const [showMacd, setShowMacd] = useState(true);
    const [localVisibility, setLocalVisibility] = useState({
        EMA15: true, EMA15_5: true, EMA15_15: true, EMA15_15_15: true, EMA15_15_15_15: true,
        EMA15_15_15_15_30: true, CLOUD: true,
        EMA50: true, EMA100: true,
        MACD: true, SIGNAL: true, MACD5: true, MACD55: true
    });

    const visibility = externalVisibility || localVisibility;

    useEffect(() => {
        axios.get(`${API_BASE}/api/config`).then(res => {
            if (res.data) {
                setShowMacd(res.data.macdShow);
                setLocalVisibility(prev => ({
                    ...prev,
                    EMA50: res.data.macdShowEMAs,
                    EMA100: res.data.macdShowEMAs,
                    MACD: res.data.macdShowEMAs,
                    SIGNAL: res.data.macdShowEMAs,
                    MACD5: res.data.macdShowEMAs,
                    MACD55: res.data.macdShowEMAs
                }));
            }
        }).catch(err => console.error("Config fetch failed", err));
    }, []);

    // ─── Order Lines State ────────────────────────────────────────────────────
    // ─── Order Lines State (LIFTED to App.jsx) ────────────────────────────────
    // Use shared state via prop aliases
    const orderLines = sharedOrderLines || {};
    const setOrderLines = onOrderLinesUpdate || (() => { });

    const orderLinesRef = useRef(orderLines);
    useEffect(() => { orderLinesRef.current = orderLines; }, [orderLines]);

    // Drag state for order lines
    const draggingLine = useRef(null); // { key, startY, startPrice }
    const placingLine = useRef(null); // Key of line being placed (click-to-move-click)
    const orderLineDragActive = useRef(false);

    const toggleOrderLine = useCallback((key) => {
        setOrderLines(prev => {
            const line = prev[key];
            const nextVisible = !line.visible;

            // If turning ON, start placement mode
            if (nextVisible) {
                placingLine.current = key;
            } else {
                // If turning OFF, clear placement interaction if it was active
                if (placingLine.current === key) placingLine.current = null;
            }

            let price = line.price;
            // Seed price from last candle close on first enable if empty
            if (nextVisible && (price === '' || price === null)) {
                const lastCandle = data[data.length - 1];
                price = lastCandle ? String(lastCandle.close) : '';
            }
            return { ...prev, [key]: { ...line, visible: nextVisible, price } };
        });
    }, [data]);

    const setOrderPrice = useCallback((key, value) => {
        setOrderLines(prev => ({ ...prev, [key]: { ...prev[key], price: value } }));
    }, []);

    const setOrderQuantity = useCallback((key, value) => {
        setOrderLines(prev => ({ ...prev, [key]: { ...prev[key], quantity: value } }));
    }, []);

    const setOrderAction = useCallback((key, value) => {
        setOrderLines(prev => ({ ...prev, [key]: { ...prev[key], action: value } }));
    }, []);

    const handleMouseDown = (e) => {
        isResizing.current = true;
        e.preventDefault();
    };

    // Hover State for Axis Indicators
    const [hoveredAxis, setHoveredAxis] = useState(null); // 'y_main', 'y_macd', 'x', or null

    const mouseHandlersRef = useRef({ move: null, up: null });

    mouseHandlersRef.current.move = (e) => {
        if (!containerRef.current) return;
        const rect = containerRef.current.getBoundingClientRect();
        const y = e.clientY - rect.top;

        // ─── Handle Placement (Follow Mouse) ─────────────────────────────
        if (placingLine.current && !draggingLine.current) {
            const echarts = echartRef.current?.getEchartsInstance();
            if (echarts) {
                const pointInGrid = echarts.convertFromPixel({ seriesIndex: 0 }, [0, y]);
                if (pointInGrid && pointInGrid.length === 2) {
                    let newPrice = pointInGrid[1];
                    newPrice = Math.round(newPrice * 20) / 20;
                    const key = placingLine.current;

                    setOrderLines(prev => ({
                        ...prev,
                        [key]: { ...prev[key], price: newPrice }
                    }));
                    echarts.getZr().setCursorStyle('crosshair');
                    return;
                }
            }
        }

        // ─── Handle Order Line Dragging ──────────────────────────────────
        if (draggingLine.current) {
            const echarts = echartRef.current?.getEchartsInstance();
            if (echarts) {
                // Convert pixel Y to price value
                // The main chart grid is index 0. We assume lines are on the main chart.
                const pointInGrid = echarts.convertFromPixel({ seriesIndex: 0 }, [0, y]);
                if (pointInGrid && pointInGrid.length === 2) {
                    let newPrice = pointInGrid[1];
                    // Snap to 0.05
                    newPrice = Math.round(newPrice * 20) / 20;
                    const key = draggingLine.current.key;
                    setOrderLines(prev => ({
                        ...prev,
                        [key]: { ...prev[key], price: newPrice }
                    }));
                    echarts.getZr().setCursorStyle('ns-resize');
                    return; // Stop other interactions
                }
            }
        }

        // Check if hovering over an order line
        if (!isResizing.current && !isScalingY.current && !isScalingX.current) {
            const echarts = echartRef.current?.getEchartsInstance();
            if (echarts) {
                let hoveringLine = false;
                const lines = orderLinesRef.current;
                for (const [key, line] of Object.entries(lines)) {
                    if (line.visible && line.price !== '' && !isNaN(parseFloat(line.price))) {
                        const pixel = echarts.convertToPixel({ seriesIndex: 0 }, [0, parseFloat(line.price)]);
                        if (pixel && Math.abs(pixel[1] - y) < 8) { // 8px tolerance
                            hoveringLine = true;
                            break;
                        }
                    }
                }
                if (hoveringLine) {
                    echarts.getZr().setCursorStyle('ns-resize');
                }
            }
        }

        // Handle Panel Resizing
        if (isResizing.current && showMacd) {
            const height = rect.height;
            let newPct = (y / height) * 100;
            newPct = Math.max(20, Math.min(newPct, 80));
            setSplitPct(newPct);
        }

        // Handle Manual Vertical Panning
        if (isPanningY.current) {
            const deltaY = e.clientY - panStartY.current;
            const echarts = echartRef.current?.getEchartsInstance();
            if (echarts && initialPriceRange.current) {
                const { min, max } = initialPriceRange.current;
                const priceRange = max - min;
                const topGridHeight = rect.height * (splitPct / 100);

                // Convert pixels to price: displacement = deltaY * (priceRange / gridHeight)
                const priceDelta = (deltaY / topGridHeight) * priceRange;

                echarts.setOption({
                    yAxis: [{ min: min + priceDelta, max: max + priceDelta }]
                });
                echarts.getZr().setCursorStyle('grabbing');
                return;
            }
        }

        // Handle Manual Vertical Panning - MACD
        if (isPanningY_MACD.current && showMacd) {
            const deltaY = e.clientY - panStartY_MACD.current;
            const echarts = echartRef.current?.getEchartsInstance();
            if (echarts && initialMACDPriceRange.current) {
                const { min, max } = initialMACDPriceRange.current;
                const priceRange = max - min;
                const bottomGridHeight = rect.height * ((100 - splitPct - 4) / 100);

                const priceDelta = (deltaY / bottomGridHeight) * priceRange;

                echarts.setOption({
                    yAxis: [{}, { min: min + priceDelta, max: max + priceDelta }]
                });
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

                echarts.setOption({
                    yAxis: [{ min: center - halfRange, max: center + halfRange }]
                });
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

        // Handle MACD Y-Axis Scaling
        if (isScalingY_MACD.current && showMacd) {
            const deltaY = dragStartY.current - e.clientY;
            const sensitivity = 0.005;
            const scaleFactor = Math.exp(deltaY * sensitivity);

            const echarts = echartRef.current?.getEchartsInstance();
            if (echarts && initialZoomState_MACD.current) {
                const { min, max } = initialZoomState_MACD.current;
                const center = (min + max) / 2;
                const halfRange = ((max - min) / 2) * scaleFactor;

                echarts.setOption({
                    yAxis: [{}, { min: center - halfRange, max: center + halfRange }]
                });
            }
        }
    };

    mouseHandlersRef.current.up = () => {
        isResizing.current = false;
        isScalingY.current = false;
        isScalingX.current = false;
        isScalingY_MACD.current = false;
        isPanningY.current = false;
        initialPriceRange.current = null;
        isPanningY_MACD.current = false;
        initialMACDPriceRange.current = null;

        // Check for drag complete on order lines
        if (draggingLine.current) {
            const key = draggingLine.current.key;
            const startPrice = draggingLine.current.startPrice;
            const finalDraggingLine = draggingLine.current; // capture for closure

            // We use setOrderLines callback to access the guaranteed latest state
            setOrderLines(prev => {
                const line = prev[key];
                if (line && line.orderId) {
                    const currentPrice = parseFloat(line.price);
                    if (!isNaN(currentPrice) && currentPrice !== startPrice) {
                        console.log(`Modifying order! Start: ${startPrice}, New: ${currentPrice}`);
                        // Trigger the API call
                        setStatus({ state: 'loading', msg: 'Modifying order...' });
                        axios.post(`${API_BASE}/api/order/modify`, {
                            orderId: line.orderId,
                            symbol: symbol,
                            action: line.action || 'BUY',
                            price: currentPrice,
                            quantity: line.quantity || 100
                        }).then(res => {
                            setStatus({ state: 'ok', msg: `Order Modified: ${res.data.orderId}` });
                            setTimeout(() => setStatus({ state: 'ok', msg: 'R' }), 5000);
                            // Update orderId just in case Angel returns a new one on modify
                            setOrderLines(innerPrev => ({
                                ...innerPrev,
                                [key]: { ...innerPrev[key], orderId: res.data.orderId }
                            }));
                        }).catch(err => {
                            console.error("Order modification failed", err);
                            setStatus({ state: 'error', msg: 'Modify Failed' });
                            // Revert line to previous price
                            setOrderLines(innerPrev => ({
                                ...innerPrev,
                                [key]: { ...innerPrev[key], price: startPrice }
                            }));
                        });
                    }
                }
                return prev; // don't actually change state here, just read it
            });
        }
        draggingLine.current = null;
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
        if (displayData.length === 0) return {};

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

        const dayStartLines = [];
        for (let i = 1; i < displayData.length; i++) {
            const datePrev = new Date(displayData[i - 1].timestamp).getDate();
            const dateCurr = new Date(displayData[i].timestamp).getDate();
            if (datePrev !== dateCurr) {
                dayStartLines.push({
                    xAxis: i,
                    lineStyle: { color: 'rgba(255, 255, 255, 0.2)', width: 1, type: 'dashed' },
                    label: { show: false }
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
            axisPointer: { link: showMacd ? [{ xAxisIndex: 'all' }] : [], label: { backgroundColor: '#333' } },
            grid: showMacd ? [
                { left: '5px', right: '35px', top: '5%', height: `${Math.max(10, splitPct - 5)}%` },
                { left: '5px', right: '35px', top: `${splitPct + 4}%`, height: `${Math.max(5, 91 - splitPct)}%` }
            ] : [
                { left: '5px', right: '35px', top: '5%', height: '90%' }
            ],
            xAxis: showMacd ? [
                {
                    type: 'category',
                    data: timestamps,
                    boundaryGap: true,
                    axisLine: { lineStyle: { color: '#333' } },
                    axisLabel: { show: false },
                    splitLine: { show: true, lineStyle: { color: 'rgba(43, 43, 67, 0.4)' } },
                    axisPointer: { label: { formatter: (params) => params.value || '' } }
                },
                {
                    gridIndex: 1,
                    type: 'category',
                    data: timestamps,
                    boundaryGap: true,
                    axisLine: { lineStyle: { color: '#333' } },
                    axisLabel: {
                        color: '#d1d4dc',
                        fontSize: 10,
                        formatter: (val) => val  // already 'DD Mon HH:MM'
                    },
                    splitLine: { show: true, lineStyle: { color: 'rgba(43, 43, 67, 0.4)' } }
                }
            ] : [
                {
                    type: 'category',
                    data: timestamps,
                    boundaryGap: true,
                    axisLine: { lineStyle: { color: '#333' } },
                    axisLabel: {
                        color: '#d1d4dc',
                        fontSize: 10,
                        formatter: (val) => val  // already 'DD Mon HH:MM'
                    },
                    splitLine: { show: true, lineStyle: { color: 'rgba(43, 43, 67, 0.4)' } }
                }
            ],
            yAxis: showMacd ? [
                {
                    scale: true,
                    position: 'right',
                    axisLine: { show: false },
                    axisLabel: { color: '#64748b', fontSize: 10, formatter: v => v >= 1000 ? (v / 1000).toFixed(1) + 'k' : v.toFixed(0) },
                    splitLine: { show: true, lineStyle: { color: 'rgba(255, 255, 255, 0.03)' } }
                },
                {
                    gridIndex: 1,
                    scale: true,
                    position: 'right',
                    axisLine: { show: false },
                    axisLabel: { color: '#64748b', fontSize: 10, formatter: v => v.toFixed(1) },
                    splitLine: { show: false }
                }
            ] : [
                {
                    scale: true,
                    position: 'right',
                    axisLine: { show: false },
                    axisLabel: { color: '#64748b', fontSize: 10, formatter: v => v >= 1000 ? (v / 1000).toFixed(1) + 'k' : v.toFixed(0) },
                    splitLine: { show: true, lineStyle: { color: 'rgba(255, 255, 255, 0.03)' } }
                }
            ],
            dataZoom: [
                // X-Axis Zoom (Time) - Default Mouse Wheel
                {
                    type: 'inside',
                    xAxisIndex: showMacd ? [0, 1] : [0],
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
                },
                // MACD Y-Axis Zoom
                ...(showMacd ? [{
                    type: 'inside',
                    yAxisIndex: [1],
                    filterMode: 'empty',
                    orient: 'vertical',
                    zoomOnMouseWheel: false,
                    moveOnMouseWheel: true,
                    moveOnMouseMove: true
                }] : [])
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
                    markLine: {
                        silent: true,
                        symbol: 'none',
                        animation: false,
                        lineStyle: { width: 1.5, type: 'solid' },
                        label: { show: true, position: 'insideEndTop', fontSize: 11, fontWeight: 'bold', padding: [2, 4] },
                        data: [
                            ...dayStartLines,
                            ...Object.entries(orderLines)
                                .filter(([, v]) => v.visible && v.price !== '' && !isNaN(parseFloat(v.price)))
                                .map(([, v]) => ({
                                    yAxis: parseFloat(v.price),
                                    lineStyle: { color: v.color, width: 1 },
                                    label: { formatter: `${v.label}: ${parseFloat(v.price).toFixed(2)}`, color: v.color, backgroundColor: 'rgba(13,13,30,0.85)', borderColor: v.color, borderWidth: 1, borderRadius: 3 }
                                })),
                            // 10:12 AM Reference Lines
                            ...(visibility["1012_OPEN_CLOSE"] ? (() => {
                                // Find all candles at 10:12 and pick the most recent one
                                const occurrences = displayData.filter(c => {
                                    const d = new Date(c.timestamp);
                                    return d.getHours() === 10 && d.getMinutes() === 12;
                                });

                                if (occurrences.length === 0) return [];

                                const targetCandle = occurrences[occurrences.length - 1];
                                const candleDate = new Date(targetCandle.timestamp).toLocaleDateString();
                                const todayDate = new Date().toLocaleDateString();

                                // Determine if market is "Live" (Trading day and hours)
                                const now = new Date();
                                const day = now.getDay(); // 0 is Sunday, 6 is Saturday
                                const timeVal = now.getHours() * 100 + now.getMinutes();
                                const isTradingDay = day >= 1 && day <= 5;
                                const isTradingHours = timeVal >= 915 && timeVal <= 1530;
                                const isMarketLive = isTradingDay && isTradingHours;

                                // Logic:
                                // 1. If candle is from today -> Show it.
                                // 2. If candle is from past day -> Only show if market is NOT live.
                                const shouldShow = (candleDate === todayDate) || !isMarketLive;

                                if (targetCandle && shouldShow) {
                                    return [
                                        {
                                            yAxis: targetCandle.open,
                                            lineStyle: { color: '#ffeb3b', width: 1, type: 'dashed' },
                                            label: { formatter: `10:12 OPEN: ${targetCandle.open}`, color: '#ffeb3b', position: 'start' }
                                        },
                                        {
                                            yAxis: targetCandle.close,
                                            lineStyle: { color: '#ffeb3b', width: 1, type: 'dashed' },
                                            label: { formatter: `10:12 CLOSE: ${targetCandle.close}`, color: '#ffeb3b', position: 'start' }
                                        }
                                    ];
                                }
                                return [];
                            })() : [])
                        ]
                    }
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
                    areaStyle: { color: 'rgba(60, 120, 255, 0.4)', opacity: 1 },
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
                    areaStyle: { color: 'rgba(255, 0, 0, 0.4)', opacity: 1 },
                    silent: true
                },
                // Cascaded EMA lines
                { name: 'EMA15', type: 'line', data: visibility.EMA15 ? displayData.map(c => c.ema15) : null, itemStyle: { color: '#ffffff' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                { name: 'EMA15_5', type: 'line', data: visibility.EMA15_5 ? displayData.map(c => c.ema15_5) : null, itemStyle: { color: '#ef5350' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                { name: 'EMA15_15', type: 'line', data: visibility.EMA15_15 ? displayData.map(c => c.ema15_15) : null, itemStyle: { color: '#2196f3' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                { name: 'EMA15_15_15', type: 'line', data: visibility.EMA15_15_15 ? displayData.map(c => c.ema15_15_15) : null, itemStyle: { color: '#ef5350' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                { name: 'EMA15_15_15_15', type: 'line', data: visibility.EMA15_15_15_15 ? displayData.map(c => c.ema15_15_15_15) : null, itemStyle: { color: '#ffeb3b' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                { name: 'EMA15_15_15_15_30', type: 'line', data: visibility.EMA15_15_15_15_30 ? displayData.map(c => c.ema15_15_15_15_30) : null, itemStyle: { color: '#ef5350' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                { name: 'EMA50', type: 'line', data: visibility.EMA50 ? displayData.map(c => c.ema50) : null, itemStyle: { color: '#ff9800' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1.5 } },
                { name: 'EMA100', type: 'line', data: visibility.EMA100 ? displayData.map(c => c.ema100) : null, itemStyle: { color: '#9c27b0' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1.5 } },

                ...(showMacd ? [
                    { name: 'MACD', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: visibility.MACD ? displayData.map(c => c.macdLine) : null, itemStyle: { color: '#ffffff' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                    { name: 'Signal', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: visibility.SIGNAL ? displayData.map(c => c.signalLine) : null, itemStyle: { color: '#ef5350' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                    { name: 'MACD5', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: visibility.MACD5 ? displayData.map(c => c.macd5) : null, itemStyle: { color: '#2196f3' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } },
                    { name: 'MACD55', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: visibility.MACD55 ? displayData.map(c => c.macd55) : null, itemStyle: { color: '#ef5350' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1 } }
                ] : [])

            ]
        };
    }, [displayData, symbol, visibility, interval, splitPct, showMacd, orderLines]);

    return (
        <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', gap: isGrid ? '5px' : '10px', position: 'relative' }}>
            {/* Legend & Stats Overlay - Smaller in Grid */}
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
                        {formatSymbol(symbol)} <span style={{ color: '#64748b', fontWeight: 400 }}>{interval}</span>
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
                    {/* Integrated Order Line Controls */}
                    <div style={{ display: 'flex', gap: '6px', height: '28px', alignItems: 'center' }}>
                        {Object.entries(orderLines).map(([key, line]) => (
                            <div key={key} style={{ display: 'flex', alignItems: 'center', gap: '4px', height: '28px' }}>
                                <button
                                    onClick={() => toggleOrderLine(key)}
                                    title={`Toggle ${line.label}`}
                                    style={{
                                        width: '14px', height: '14px', borderRadius: '3px',
                                        border: `1.5px solid ${line.color}`,
                                        background: line.visible ? line.color : 'transparent',
                                        cursor: 'pointer', padding: 0, flexShrink: 0,
                                        transition: 'all 0.2s'
                                    }}
                                />
                                {!isGrid && <span style={{ color: line.color, fontSize: '10px', fontWeight: 700 }}>{line.label}</span>}
                                {isGrid && <span style={{ color: line.color, fontSize: '9px', fontWeight: 700 }}>{line.label.substring(0, 1)}</span>}

                                {line.visible && (
                                    <input
                                        type="number"
                                        step="0.5"
                                        value={line.price}
                                        onChange={e => setOrderPrice(key, e.target.value)}
                                        style={{
                                            width: '42px', background: 'transparent',
                                            border: 'none', borderBottom: `1px solid ${line.color}`,
                                            color: '#eee', fontSize: '10px', padding: '0 2px',
                                            height: '20px', textAlign: 'center', outline: 'none'
                                        }}
                                        placeholder="0.00"
                                    />
                                )}
                            </div>
                        ))}
                    </div>


                    <div style={{ color: status.state === 'error' ? '#ef5350' : '#26a69a', fontSize: isGrid ? '10px' : '12px', fontWeight: 'bold', height: '28px', display: 'flex', alignItems: 'center' }}>{status.msg}</div>
                    {!isGrid && <div style={{ color: '#aaa', fontSize: '11px', height: '28px', display: 'flex', alignItems: 'center' }}>{displayData.length} Bars</div>}

                    {/* Compact Replay Controls */}
                    <div style={{ display: 'flex', alignItems: 'center', gap: isGrid ? '4px' : '10px', background: 'rgba(0,0,0,0.2)', height: '28px', padding: '0 10px', borderRadius: '6px', border: '1px solid rgba(255,255,255,0.05)' }}>
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
                                padding: 0,
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
                    if (isScalingX.current || isScalingY.current || isScalingY_MACD.current || isResizing.current) return;

                    const rect = containerRef.current.getBoundingClientRect();
                    const x = e.clientX - rect.left;
                    const y = e.clientY - rect.top;

                    const topGridHeight = rect.height * (splitPct / 100);
                    const bottomGridTop = rect.height * ((splitPct + 4) / 100);

                    // Main Y-Axis Hover
                    if (x > rect.width - 55 && y < topGridHeight) {
                        setHoveredAxis('y_main');
                        echartRef.current?.getEchartsInstance()?.getZr().setCursorStyle('ns-resize');
                    }
                    // MACD Y-Axis Hover
                    else if (showMacd && x > rect.width - 55 && y > bottomGridTop && y < rect.height - 30) {
                        setHoveredAxis('y_macd');
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
                    const x = e.clientX - rect.left;
                    const y = e.clientY - rect.top;

                    // ─── Handle Placement Commit ──────────────────────────────────
                    if (placingLine.current) {
                        const committedKey = placingLine.current;
                        placingLine.current = null;
                        const echarts = echartRef.current?.getEchartsInstance();
                        echarts?.getZr().setCursorStyle('default');
                        e.preventDefault();

                        // Place order API call for 'limit' line
                        if (committedKey === 'limit') {
                            const line = orderLinesRef.current['limit'];
                            const committedPrice = line?.price;
                            if (committedPrice && !isNaN(parseFloat(committedPrice))) {
                                setStatus({ state: 'loading', msg: 'Placing order...' });
                                axios.post(`${API_BASE}/api/order/place`, {
                                    symbol: symbol,
                                    action: line.action || 'BUY',
                                    price: parseFloat(committedPrice),
                                    quantity: line.quantity || 100
                                }).then(res => {
                                    setStatus({ state: 'ok', msg: `Order Placed: ${res.data.orderId}` });
                                    setTimeout(() => setStatus({ state: 'ok', msg: 'R' }), 5000);

                                    // Save the orderId in state so we can modify it later
                                    setOrderLines(prev => ({
                                        ...prev,
                                        ['limit']: { ...prev['limit'], orderId: res.data.orderId }
                                    }));
                                }).catch(err => {
                                    console.error("Order placement failed", err);
                                    setStatus({ state: 'error', msg: 'Order Failed' });
                                });
                            }
                        }

                        return;
                    }

                    // ─── Check for Order Line Drag Start ──────────────────────────
                    const echarts = echartRef.current?.getEchartsInstance();
                    if (echarts) {
                        const lines = orderLinesRef.current;
                        for (const [key, line] of Object.entries(lines)) {
                            if (line.visible && line.price !== '' && !isNaN(parseFloat(line.price))) {
                                const pixel = echarts.convertToPixel({ seriesIndex: 0 }, [0, parseFloat(line.price)]);
                                if (pixel && Math.abs(pixel[1] - y) < 8) { // 8px tolerance
                                    draggingLine.current = { key, startY: y, startPrice: parseFloat(line.price) };
                                    e.preventDefault();
                                    return; // Stop here, don't trigger other interactions
                                }
                            }
                        }
                    }

                    // Check if click is in the right axis area (approx > width - 50px)
                    // And also within the top grid (Main Chart) area to avoid confusion or specific targeting
                    // Top grid height is splitPct.
                    const topGridHeight = rect.height * (splitPct / 100);

                    if (x > rect.width - 55 && y < topGridHeight) {
                        isScalingY.current = true;
                        dragStartY.current = e.clientY;
                        const echarts = echartRef.current?.getEchartsInstance();
                        if (echarts) {
                            const opt = echarts.getOption();
                            // Get current yAxis min/max. If 'auto', compute from visible data.
                            let yMin = opt?.yAxis?.[0]?.min;
                            let yMax = opt?.yAxis?.[0]?.max;
                            if (yMin == null || yMax == null || typeof yMin === 'string' || typeof yMax === 'string') {
                                // Compute from visible data range
                                const visibleModel = echarts.getModel().getComponent('yAxis', 0);
                                const extent = visibleModel?.axis?.scale?.getExtent?.();
                                yMin = extent ? extent[0] : 0;
                                yMax = extent ? extent[1] : 100;
                            }
                            initialZoomState.current = { min: yMin, max: yMax };
                        }
                        // Prevent text selection
                        e.preventDefault();

                    }

                    // Check if click is in the MACD Y-axis area (Bottom Grid Right Axis)
                    const bottomGridTop = rect.height * ((splitPct + 4) / 100);
                    if (showMacd && x > rect.width - 55 && y > bottomGridTop && y < rect.height - 30) {
                        isScalingY_MACD.current = true;
                        dragStartY.current = e.clientY;
                        const echarts = echartRef.current?.getEchartsInstance();
                        if (echarts) {
                            const opt = echarts.getOption();
                            // Get current MACD yAxis (index 1) min/max.
                            let yMin = opt?.yAxis?.[1]?.min;
                            let yMax = opt?.yAxis?.[1]?.max;
                            if (yMin == null || yMax == null || typeof yMin === 'string' || typeof yMax === 'string') {
                                // Compute from visible data range for index 1
                                const visibleModel = echarts.getModel().getComponent('yAxis', 1);
                                const extent = visibleModel?.axis?.scale?.getExtent?.();
                                yMin = extent ? extent[0] : 0;
                                yMax = extent ? extent[1] : 100;
                            }
                            initialZoomState_MACD.current = { min: yMin, max: yMax };
                        }
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

                    // ─── Handle Manual Vertical Panning ──────────────────────────────
                    // If no other interaction was triggered, and we are in the main grid
                    if (!isScalingY.current && !isScalingX.current && !draggingLine.current && !placingLine.current && y < topGridHeight && x < rect.width - 55) {
                        isPanningY.current = true;
                        panStartY.current = e.clientY;
                        const echarts = echartRef.current?.getEchartsInstance();
                        if (echarts) {
                            const opt = echarts.getOption();
                            let yMin = opt?.yAxis?.[0]?.min;
                            let yMax = opt?.yAxis?.[0]?.max;
                            if (yMin == null || yMax == null || typeof yMin === 'string' || typeof yMax === 'string') {
                                const visibleModel = echarts.getModel().getComponent('yAxis', 0);
                                const extent = visibleModel?.axis?.scale?.getExtent?.();
                                yMin = extent ? extent[0] : 0;
                                yMax = extent ? extent[1] : 100;
                            }
                            initialPriceRange.current = { min: yMin, max: yMax };
                            echarts.getZr().setCursorStyle('grabbing');
                        }
                        e.preventDefault();
                    }

                    // ─── Handle Manual Vertical Panning (MACD) ──────────────────────────
                    if (showMacd && !isScalingY_MACD.current && !isScalingX.current && !draggingLine.current && !placingLine.current && y > bottomGridTop && y < rect.height - 30 && x < rect.width - 55) {
                        isPanningY_MACD.current = true;
                        panStartY_MACD.current = e.clientY;
                        const echarts = echartRef.current?.getEchartsInstance();
                        if (echarts) {
                            const opt = echarts.getOption();
                            let yMin = opt?.yAxis?.[1]?.min;
                            let yMax = opt?.yAxis?.[1]?.max;
                            if (yMin == null || yMax == null || typeof yMin === 'string' || typeof yMax === 'string') {
                                const visibleModel = echarts.getModel().getComponent('yAxis', 1);
                                const extent = visibleModel?.axis?.scale?.getExtent?.();
                                yMin = extent ? extent[0] : 0;
                                yMax = extent ? extent[1] : 100;
                            }
                            initialMACDPriceRange.current = { min: yMin, max: yMax };
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


                {/* Order Line Control Windows */}
                {Object.entries(orderLines).map(([key, line]) => {
                    const echarts = echartRef.current?.getEchartsInstance();
                    if (!line.visible || isNaN(parseFloat(line.price)) || !echarts) return null;

                    const pixel = echarts.convertToPixel({ seriesIndex: 0 }, [0, parseFloat(line.price)]);
                    if (!pixel) return null;

                    // Hide window if it's off-screen vertically
                    const rect = containerRef.current?.getBoundingClientRect();
                    if (rect && (pixel[1] < 0 || pixel[1] > rect.height)) return null;

                    return (
                        <div key={key}
                            onMouseDown={(e) => e.stopPropagation()}
                            style={{
                                position: 'absolute',
                                top: `${pixel[1]}px`,
                                left: '10px',
                                transform: 'translateY(-50%)',
                                background: '#1e222d',
                                border: `1px solid ${line.color}`,
                                borderRadius: '6px',
                                padding: '4px 6px',
                                display: 'flex',
                                alignItems: 'center',
                                gap: '6px',
                                zIndex: 100,
                                boxShadow: '0 4px 12px rgba(0,0,0,0.5)',
                                pointerEvents: 'auto',
                                userSelect: 'none',
                                transition: draggingLine.current?.key === key ? 'none' : 'top 0.1s ease-out'
                            }}>
                            <div style={{
                                width: '8px', height: '8px', borderRadius: '50%',
                                background: line.color, flexShrink: 0
                            }} />

                            <span style={{
                                color: '#fff', fontSize: '10px', fontWeight: 'bold',
                                minWidth: '35px', textTransform: 'uppercase'
                            }}>{line.label}</span>

                            <div style={{ position: 'relative', display: 'flex', alignItems: 'center' }}>
                                <input
                                    type="number"
                                    value={line.quantity}
                                    onChange={(e) => setOrderQuantity(key, parseInt(e.target.value) || 0)}
                                    style={{
                                        width: '45px', background: '#2b2b43', border: '1px solid rgba(255,255,255,0.1)',
                                        color: '#fff', fontSize: '11px', borderRadius: '3px', padding: '2px 4px',
                                        textAlign: 'center', outline: 'none'
                                    }}
                                />
                                <span style={{ position: 'absolute', right: '-12px', color: '#666', fontSize: '8px' }}>QTY</span>
                            </div>

                            <div style={{ display: 'flex', background: '#2b2b43', borderRadius: '4px', padding: '2px', marginLeft: '5px' }}>
                                <button
                                    onClick={(e) => { e.stopPropagation(); setOrderAction(key, 'BUY'); }}
                                    style={{
                                        padding: '2px 8px', fontSize: '10px', border: 'none', borderRadius: '3px',
                                        background: line.action === 'BUY' ? '#26a69a' : 'transparent',
                                        color: line.action === 'BUY' ? '#fff' : '#888',
                                        cursor: 'pointer', fontWeight: 'bold', transition: 'all 0.2s'
                                    }}
                                >BUY</button>
                                <button
                                    onClick={(e) => { e.stopPropagation(); setOrderAction(key, 'SELL'); }}
                                    style={{
                                        padding: '2px 8px', fontSize: '10px', border: 'none', borderRadius: '3px',
                                        background: line.action === 'SELL' ? '#ef5350' : 'transparent',
                                        color: line.action === 'SELL' ? '#fff' : '#888',
                                        cursor: 'pointer', fontWeight: 'bold', transition: 'all 0.2s'
                                    }}
                                >SELL</button>
                            </div>

                            <div
                                style={{
                                    marginLeft: '4px', padding: '2px', cursor: 'pointer',
                                    color: '#666'
                                }}
                                onClick={(e) => { e.stopPropagation(); toggleOrderLine(key); }}
                                title="Close"
                            >✕</div>
                        </div>
                    );
                })}


                {/* Resizable Handle Overlay */}
                {showMacd && (
                    <div
                        onMouseDown={handleMouseDown}
                        style={{
                            position: 'absolute',
                            top: `${splitPct}%`,
                            left: '0',
                            right: '0',
                            height: '4%', // Match the gap roughly
                            cursor: 'row-resize',
                            zIndex: 20,
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                        }}
                        title="Drag to resize panel"
                    >
                        <div style={{
                            width: '40px',
                            height: '4px',
                            background: 'rgba(255,255,255,0.2)',
                            borderRadius: '2px',
                            pointerEvents: 'none',
                            border: '1px solid rgba(0,0,0,0.5)'
                        }} />
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
                            forceUpdate();
                            // Persist the user's zoom position so live updates don't reset it
                            const echarts = echartRef.current?.getEchartsInstance();
                            if (echarts) {
                                const opt = echarts.getOption();
                                if (opt?.dataZoom?.[0]) {
                                    userZoomRef.current = {
                                        start: opt.dataZoom[0].start,
                                        end: opt.dataZoom[0].end
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
                    notMerge={false}
                    lazyUpdate={true}
                />
            </div>

            {!isGrid && (
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
            )}

            {/* Zoom Controls Removed (User preferred drag interaction) */}


        </div>
    );
});

export default ETradingChart;
