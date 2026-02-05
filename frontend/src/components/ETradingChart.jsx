import React, { useEffect, useState, useMemo, useRef } from 'react';
import ReactECharts from 'echarts-for-react';
import SockJS from 'sockjs-client';
import Stomp from 'stompjs';
import axios from 'axios';
import { API_BASE, WS_BASE } from '../config';

const ETradingChart = React.memo(({ symbol = 'NIFTY', initialInterval = '3min', isGrid = false }) => {
    const [interval, setInterval] = useState(initialInterval);
    const [data, setData] = useState([]);
    const [hoverIndex, setHoverIndex] = useState(null);
    const [status, setStatus] = useState({ state: 'loading', msg: 'Loading ECharts...' });
    const echartRef = useRef(null);
    const containerRef = useRef(null);

    // Resize State
    const [splitPct, setSplitPct] = useState(60); // 60% for main chart
    const isResizing = useRef(false);

    // Initial Fetch
    useEffect(() => {
        const fetchData = async () => {
            try {
                setStatus({ state: 'loading', msg: `Fetching ${symbol} ${interval}...` });
                const response = await axios.get(`${API_BASE}/api/history/${symbol}/${interval}?_t=${Date.now()}`);
                if (response.data && response.data.length > 0) {
                    setData(response.data.sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp)));
                    setStatus({ state: 'ok', msg: 'Chart Ready' });
                } else {
                    setData([]);
                    setStatus({ state: 'warn', msg: 'No data for this interval' });
                }
            } catch (err) {
                console.error("Fetch failed", err);
                setStatus({ state: 'error', msg: 'Historical data fetch failed' });
            }
        };
        fetchData();
    }, [symbol, interval]);

    // WebSocket Updates
    useEffect(() => {
        const socket = new SockJS(WS_BASE);
        const stomp = Stomp.over(socket);
        stomp.debug = null;
        stomp.connect({}, () => {
            stomp.subscribe('/topic/updates', (msg) => {
                const update = JSON.parse(msg.body);
                if (update.symbol === symbol && update.interval === interval) {
                    setData(prev => {
                        if (prev.length === 0) return [
                            {
                                timestamp: update.ohlc.t,
                                open: update.ohlc.o, high: update.ohlc.h, low: update.ohlc.l, close: update.ohlc.c,
                                ema15: update.indicators.EMA15,
                                ema15_5: update.indicators.EMA15_5,
                                ema15_15: update.indicators.EMA15_15,
                                stMa: update.indicators.ST_MA,
                                stTrend: update.indicators.ST_TREND,
                                macdLine: update.indicators.MACD,
                                signalLine: update.indicators.SIGNAL,
                                macd5: update.indicators.MACD5,
                                macd55: update.indicators.MACD55
                            }
                        ];
                        const last = prev[prev.length - 1];
                        const newPoint = {
                            timestamp: update.ohlc.t,
                            open: update.ohlc.o, high: update.ohlc.h, low: update.ohlc.l, close: update.ohlc.c,
                            ema15: update.indicators.EMA15,
                            ema15_5: update.indicators.EMA15_5,
                            ema15_15: update.indicators.EMA15_15,
                            ema15_15_15: update.indicators.EMA15_15_15,
                            ema15_30: update.indicators.EMA15_30,
                            ema100: update.indicators.EMA100,
                            stMa: update.indicators.ST_MA,
                            stTrend: update.indicators.ST_TREND,
                            macdLine: update.indicators.MACD,
                            signalLine: update.indicators.SIGNAL,
                            macd5: update.indicators.MACD5,
                            macd55: update.indicators.MACD55
                        };

                        if (last.timestamp === update.ohlc.t) {
                            const clone = [...prev];
                            clone[clone.length - 1] = newPoint;
                            return clone;
                        }
                        return [...prev, newPoint];
                    });
                }
            });
        }, (err) => console.error("WS connection failed", err));

        return () => { if (stomp.connected) stomp.disconnect(); };
    }, [symbol, interval]);

    // Visibility States
    const [visibility, setVisibility] = useState({
        EMA15: true, EMA15_5: true, EMA15_15: true, EMA100: true, CLOUD: true,
        MACD: true, SIGNAL: true, MACD5: true, MACD55: true
    });

    const handleMouseDown = (e) => {
        isResizing.current = true;
        e.preventDefault();
    };

    useEffect(() => {
        const handleMouseMove = (e) => {
            if (!isResizing.current || !containerRef.current) return;
            const rect = containerRef.current.getBoundingClientRect();
            // Calculate percentage relative to the chart container height
            // We subtract the top padding offset if needed, but grid top is relative to container.
            // Mouse clientY is global.
            const y = e.clientY - rect.top;
            const height = rect.height;
            let newPct = (y / height) * 100;

            // Clamping to avoid breaking layout
            // Min 20% for top, Max 80% for top
            newPct = Math.max(20, Math.min(newPct, 80));

            // Debouncing setSplitPct could be good, but let's try direct first
            setSplitPct(newPct);
        };

        const handleMouseUp = () => {
            isResizing.current = false;
        };

        window.addEventListener('mousemove', handleMouseMove);
        window.addEventListener('mouseup', handleMouseUp);
        return () => {
            window.removeEventListener('mousemove', handleMouseMove);
            window.removeEventListener('mouseup', handleMouseUp);
        };
    }, []);

    const option = useMemo(() => {
        if (data.length === 0) return {};

        const timestamps = data.map(c => {
            const d = new Date(c.timestamp);
            return d.getHours().toString().padStart(2, '0') + ':' + d.getMinutes().toString().padStart(2, '0');
        });
        const ohlc = data.map(c => [c.open, c.close, c.low, c.high]);

        // Add 'Right Offset' buffer (whitespace for future candles)
        const bufferSize = 50;
        const lastTime = data.length > 0 ? new Date(data[data.length - 1].timestamp).getTime() : Date.now();
        for (let i = 1; i <= bufferSize; i++) {
            const futureDate = new Date(lastTime + i * 60000); // Rough estimate for labels
            timestamps.push(""); // Keep it empty for future slots
            ohlc.push([null, null, null, null]);
        }

        return {
            backgroundColor: '#131722',
            animation: false,
            tooltip: {
                show: true,
                trigger: 'axis',
                showContent: false, // Keep the box hidden
                axisPointer: {
                    type: 'cross',
                    lineStyle: { color: '#4d5057', type: 'dashed' },
                    label: { backgroundColor: '#333' }
                }
            },
            axisPointer: { link: [{ xAxisIndex: 'all' }], label: { backgroundColor: '#333' } },
            grid: [
                { left: '50px', right: '50px', top: '5%', height: `${Math.max(10, splitPct - 5)}%` },
                { left: '50px', right: '50px', top: `${splitPct + 4}%`, height: `${Math.max(5, 91 - splitPct)}%` }
            ],
            xAxis: [
                {
                    type: 'category',
                    data: timestamps,
                    boundaryGap: true,
                    axisLine: { lineStyle: { color: '#333' } },
                    axisLabel: { show: false },
                    splitLine: { show: true, lineStyle: { color: 'rgba(43, 43, 67, 0.4)' } }
                },
                {
                    gridIndex: 1,
                    type: 'category',
                    data: timestamps,
                    boundaryGap: true,
                    axisLine: { lineStyle: { color: '#333' } },
                    axisLabel: { color: '#d1d4dc', fontSize: 10 },
                    splitLine: { show: true, lineStyle: { color: 'rgba(43, 43, 67, 0.4)' } }
                }
            ],
            yAxis: [
                {
                    scale: true,
                    position: 'right',
                    axisLine: { show: false },
                    axisLabel: { color: '#d1d4dc', fontSize: 10 },
                    splitLine: { show: true, lineStyle: { color: 'rgba(43, 43, 67, 0.4)' } }
                },
                {
                    gridIndex: 1,
                    scale: true,
                    position: 'right',
                    axisLine: { show: false },
                    axisLabel: { color: '#d1d4dc', fontSize: 10 },
                    splitLine: { show: false }
                }
            ],
            dataZoom: [
                { type: 'inside', xAxisIndex: [0, 1], start: Math.max(0, 100 - (16000 / timestamps.length)), end: 100 }
            ],
            series: [
                {
                    name: symbol,
                    type: 'candlestick',
                    data: ohlc,
                    itemStyle: {
                        color: '#26a69a', color0: '#ef5350',
                        borderColor: '#26a69a', borderColor0: '#ef5350'
                    }
                },
                // Perfectly Smooth Cloud (Native Series Stacking)
                // Uptrend: Base = stMa (lower), Cloud = offset to close (positive)
                {
                    name: 'BaseUp',
                    type: 'line',
                    data: data.map(c => c.stTrend === 1 ? c.stMa : null),
                    lineStyle: { opacity: 0 },
                    symbol: 'none',
                    stack: 'uptrend',
                    silent: true
                },
                {
                    name: 'CloudUp',
                    type: 'line',
                    data: data.map(c => c.stTrend === 1 ? (Math.max(0, c.close - c.stMa)) : null),
                    lineStyle: { opacity: 0 },
                    symbol: 'none',
                    stack: 'uptrend',
                    areaStyle: { color: 'rgba(60, 120, 255, 0.4)', opacity: 1 },
                    silent: true,
                    hidden: !visibility.CLOUD
                },
                // Downtrend: Base = close (lower), Cloud = offset to stMa (positive)
                {
                    name: 'BaseDown',
                    type: 'line',
                    data: data.map(c => c.stTrend !== 1 ? c.close : null),
                    lineStyle: { opacity: 0 },
                    symbol: 'none',
                    stack: 'downtrend',
                    silent: true
                },
                {
                    name: 'CloudDown',
                    type: 'line',
                    data: data.map(c => c.stTrend !== 1 ? (Math.max(0, c.stMa - c.close)) : null),
                    lineStyle: { opacity: 0 },
                    symbol: 'none',
                    stack: 'downtrend',
                    areaStyle: { color: 'rgba(255, 0, 0, 0.4)', opacity: 1 },
                    silent: true,
                    hidden: !visibility.CLOUD
                },
                { name: 'EMA15', type: 'line', data: data.map(c => c.ema15), itemStyle: { color: '#2196f3' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1, opacity: visibility.EMA15 ? 1 : 0 } },
                { name: 'EMA15_5', type: 'line', data: data.map(c => c.ema15_5), itemStyle: { color: '#ffeb3b' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1, opacity: visibility.EMA15_5 ? 1 : 0 } },
                { name: 'EMA15_15', type: 'line', data: data.map(c => c.ema15_15), itemStyle: { color: '#ff9800' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1, opacity: visibility.EMA15_15 ? 1 : 0 } },
                { name: 'EMA100', type: 'line', data: data.map(c => c.ema100), itemStyle: { color: '#00bcd4' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1, opacity: visibility.EMA100 ? 1 : 0 } },



                { name: 'MACD', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: data.map(c => c.macdLine), itemStyle: { color: '#2196f3' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1, opacity: visibility.MACD ? 1 : 0 } },
                { name: 'Signal', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: data.map(c => c.signalLine), itemStyle: { color: '#ffeb3b' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1, opacity: visibility.SIGNAL ? 1 : 0 } },
                { name: 'MACD5', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: data.map(c => c.macd5), itemStyle: { color: '#4caf50' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1, opacity: visibility.MACD5 ? 1 : 0 } },
                { name: 'MACD55', type: 'line', xAxisIndex: 1, yAxisIndex: 1, data: data.map(c => c.macd55), itemStyle: { color: '#f44336' }, showSymbol: false, emphasis: { scale: false }, lineStyle: { width: 1, opacity: visibility.MACD55 ? 1 : 0 } }
            ]
        };
    }, [data, symbol, visibility, interval, splitPct]);

    return (
        <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', gap: isGrid ? '5px' : '10px' }}>
            {/* Legend & Stats Overlay - Smaller in Grid */}
            <div style={{ padding: isGrid ? '5px 10px' : '10px 15px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: '#1e222d', borderRadius: '8px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '20px' }}>
                    <h2 style={{ margin: 0, color: '#fff', fontSize: isGrid ? '14px' : '18px' }}>{symbol} {isGrid ? `(${interval})` : ''}</h2>
                    {!isGrid && (
                        <div className="interval-picker" style={{ display: 'flex', gap: '5px' }}>
                            {['1min', '3min', '5min', '15min', '1h'].map(tf => (
                                <button
                                    key={tf}
                                    style={{
                                        padding: '4px 10px', fontSize: '12px', borderRadius: '4px', border: 'none',
                                        background: interval === tf ? '#2196f3' : '#2b2b43',
                                        color: '#d1d4dc', cursor: 'pointer'
                                    }}
                                    onClick={() => setInterval(tf)}
                                >
                                    {tf}
                                </button>
                            ))}
                        </div>
                    )}
                </div>
                <div style={{ display: 'flex', gap: '15px' }}>
                    <div style={{ color: status.state === 'error' ? '#ef5350' : '#26a69a', fontSize: isGrid ? '10px' : '12px', fontWeight: 'bold' }}>{status.msg}</div>
                    {!isGrid && <div style={{ color: '#aaa', fontSize: '11px' }}>{data.length} Bars</div>}
                </div>
            </div>

            <div ref={containerRef} style={{ flex: 1, position: 'relative', background: '#131722', borderRadius: '8px', overflow: 'hidden' }}>
                {/* Data Overlay */}
                {(() => {
                    const displayData = (hoverIndex !== null && hoverIndex < data.length) ? data[hoverIndex] : data[data.length - 1];
                    if (!displayData) return null;
                    return (
                        <div style={{
                            position: 'absolute', top: '10px', left: '10px', zIndex: 10,
                            pointerEvents: 'none', background: 'rgba(30, 34, 45, 0.7)',
                            padding: '4px 8px', borderRadius: '4px', fontSize: '11px', color: '#d1d4dc',
                            display: 'flex', flexWrap: 'wrap', gap: '8px', border: '1px solid rgba(255,255,255,0.05)'
                        }}>
                            <span style={{ color: '#fff', fontWeight: 'bold' }}>{symbol} ({interval})</span>
                            <span>O: <b style={{ color: displayData.close >= displayData.open ? '#26a69a' : '#ef5350' }}>{displayData.open}</b></span>
                            <span>H: <b style={{ color: '#fff' }}>{displayData.high}</b></span>
                            <span>L: <b style={{ color: '#fff' }}>{displayData.low}</b></span>
                            <span>C: <b style={{ color: displayData.close >= displayData.open ? '#26a69a' : '#ef5350' }}>{displayData.close}</b></span>
                            <span style={{ marginLeft: '10px', color: '#2196f3' }}>MACD: <b>{displayData.macdLine?.toFixed(2)}</b></span>
                            <span style={{ color: '#ffeb3b' }}>Sig: <b>{displayData.signalLine?.toFixed(2)}</b></span>
                            <span style={{ color: '#4caf50' }}>M5: <b>{displayData.macd5?.toFixed(2)}</b></span>
                            <span style={{ color: '#f44336' }}>M55: <b>{displayData.macd55?.toFixed(2)}</b></span>
                        </div>
                    );
                })()}

                {/* Resizable Handle Overlay */}
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

                <ReactECharts
                    ref={echartRef}
                    option={option}
                    style={{ height: '100%', width: '100%', minHeight: isGrid ? '200px' : '600px' }}
                    onEvents={{
                        'updateAxisPointer': (event) => {
                            if (event.axesInfo && event.axesInfo[0]) {
                                const index = event.axesInfo[0].value;
                                if (index !== hoverIndex) setHoverIndex(index);
                            }
                        }
                    }}
                    onChartReady={() => {
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
                            <input type="checkbox" checked={visibility[key]} onChange={() => setVisibility(v => ({ ...v, [key]: !v[key] }))} />
                            {key.replace('_', ' ')}
                        </label>
                    ))}
                </div>
            )}
        </div>
    );
});

export default ETradingChart;
