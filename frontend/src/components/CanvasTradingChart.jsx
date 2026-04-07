import React, { useEffect, useState, useMemo, useRef, useCallback } from 'react';
import axios from 'axios';
import { API_BASE } from '../config';
import { formatSymbol } from '../helpers/formatSymbol';
import { useLiveFeed } from '../context/LiveFeedContext';

const COLORS = {
    bull: '#26a69a', bear: '#ef5350', bg: '#020617',
    emas: ['#ffffff', '#ef4444', '#3b82f6', '#22c55e', '#f59e0b', '#06b6d4'],
    stBull: 'rgba(0, 98, 255, 0.35)', stBear: 'rgba(239, 68, 68, 0.35)', st2: 'rgba(168,85,247,0.1)'
};

const CanvasTradingChart = React.memo(({ symbol, initialInterval = '3min', isGrid = false, isMaximized = false, onMaximize, onPriceUpdate, externalVisibility, onVisibilityUpdate, onIntervalChange, themeColors, symbolPrices, hideMaximize = false, preloadedData, isHighlighted = false }) => {
    const [data, setData] = useState([]);
    const [interval, setTimeframe] = useState(initialInterval);
    const [loading, setLoading] = useState(true);
    // Canonical live data lives in a ref — avoids React re-renders on every tick
    const liveDataRef = useRef([]);
    // Pending RAF handle for live-tick renders
    const pendingRafRef = useRef(null);

    useEffect(() => {
        setTimeframe(initialInterval);
    }, [initialInterval]);
    const vis = externalVisibility || { EMA15: true, EMA15_5: true, EMA15_15: true, EMA15_15_15: true, EMA15_15_15_15: true, EMA15_15_15_15_30: true, CLOUD: true, CLOUD2: true };

    const [isReplayMode, setIsReplayMode] = useState(false);
    const [replayIndex, setReplayIndex] = useState(-1);
    const [isReplaying, setIsReplaying] = useState(false);


    // NAVIGATION STATE
    const scrollOffset = useRef(0);
    const [visibleBars, setVisibleBars] = useState(() => parseInt(localStorage.getItem('chart_zoom_global')) || 160);
    const [isLog, setIsLog] = useState(() => localStorage.getItem('chart_log_scale') === 'true');
    const [yRange, setYRange] = useState(null); // { low, high } - for manual Y pan
    const isFollowing = useRef(true);
    const [orderLines, setOrderLines] = useState([]);
    const [toast, setToast] = useState(null); // { type, msg }
    const mouseRef = useRef({ x: -1, y: -1 });
    const draggingOrderRef = useRef(null);
    const dragStart = useRef(null);

    const [timeRemaining, setTimeRemaining] = useState('--:--');
    useEffect(() => {
        const calculateRemaining = () => {
            if (isReplayMode || !interval.includes('min')) return '--:--';
            const now = new Date();
            const min = now.getMinutes(), sec = now.getSeconds();
            let totalMins = 1;
            if (interval === '3min') totalMins = 3 - (min % 3);
            else if (interval === '5min') totalMins = 5 - (min % 5);
            else if (interval === '15min') totalMins = 15 - (min % 15);

            let rMin = totalMins - 1, rSec = 60 - sec;
            if (rSec === 60) { rMin += 1; rSec = 0; }
            return `${rMin.toString().padStart(2, '0')}:${rSec.toString().padStart(2, '0')}`;
        };
        const timer = setInterval(() => setTimeRemaining(calculateRemaining()), 1000);
        setTimeRemaining(calculateRemaining());
        return () => clearInterval(timer);
    }, [interval, isReplayMode]);

    // Custom setter that synchronously updates memory
    const updateOrderLines = useCallback((action) => {
        setOrderLines(prev => {
            const next = typeof action === 'function' ? action(prev) : action;
            localStorage.setItem(`orders_${symbol}`, JSON.stringify(next));
            return next;
        });
    }, [symbol]);

    // Load existing orders for this symbol
    useEffect(() => {
        const saved = localStorage.getItem(`orders_${symbol}`);
        if (saved) {
            try { setOrderLines(JSON.parse(saved)); } catch (e) { setOrderLines([]); }
        } else {
            setOrderLines([]);
        }
    }, [symbol]);

    useEffect(() => {
        const bc = new BroadcastChannel('grid_chart_sync');
        bc.onmessage = (e) => {
            if (e.data.type === 'SYNC_ZOOM') setVisibleBars(e.data.count);
            if (e.data.type === 'SYNC_LOG_SCALE') setIsLog(e.data.enabled);
        };
        return () => bc.close();
    }, []);

    const containerRef = useRef(null);
    const mainCanvasRef = useRef(null);
    const overlayCanvasRef = useRef(null);
    const replayDataRef = useRef([]);

    const dData = useMemo(() => isReplayMode && replayIndex >= 0 ? replayDataRef.current.slice(0, replayIndex + 1) : liveDataRef.current, [data, isReplayMode, replayIndex]);

    const getMath = useCallback((w, h, d) => {
        const pt = 26, pr = 38, pb = 20, cw = w - pr, ch = h - pb - pt, bs = cw / visibleBars;
        const tot = d.length;
        const margin = Math.floor(visibleBars * 0.35); // 35% blank space from right
        const end = tot - scrollOffset.current + margin;
        const start = Math.max(0, end - visibleBars);
        const slice = d.slice(Math.max(0, start), Math.min(tot, end));
        if (!slice.length) return null;

        let low = Infinity, high = -Infinity;
        for (let i = 0; i < slice.length; i++) {
            const c = slice[i];
            if (c.low < low) low = c.low;
            if (c.high > high) high = c.high;
        }

        if (yRange) { low = yRange.low; high = yRange.high; }
        else { const pad = (high - low) * 0.1; low -= pad; high += pad; }

        const p2y = (p) => isLog
            ? pt + ch - ((Math.log(p) - Math.log(low)) / (Math.log(high) - Math.log(low))) * ch
            : pt + ch - ((p - low) / (high - low)) * ch;
        const y2p = (y) => isLog
            ? Math.exp(Math.log(low) + ((pt + ch - y) / ch) * (Math.log(high) - Math.log(low)))
            : low + ((pt + ch - y) / ch) * (high - low);
        return { bs, p2y, y2p, i2x: (i) => (i - start) * bs + (bs / 2), start, end, ch, cw, pt, pb, pr, low, high, slice };
    }, [visibleBars, isLog, yRange]);


    const render = useCallback(() => {
        const canvas = mainCanvasRef.current; if (!canvas || !dData.length) return;
        const ctx = canvas.getContext('2d'), dpr = window.devicePixelRatio || 1,
            math = getMath(canvas.width / dpr, canvas.height / dpr, dData);
        if (!math) return;
        ctx.clearRect(0, 0, canvas.width, canvas.height); ctx.save(); ctx.scale(dpr, dpr);

        // GRID
        ctx.strokeStyle = '#1e293b'; ctx.lineWidth = 0.5;
        for (let i = 1; i < 9; i++) { const y = math.pt + (math.ch / 8) * i; ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(math.cw, y); ctx.stroke(); }
        for (let i = 1; i < 10; i++) { const x = (math.cw / 10) * i; ctx.beginPath(); ctx.moveTo(x, math.pt); ctx.lineTo(x, math.pt + math.ch); ctx.stroke(); }

        // BACKGROUND WATERMARK (Brand Themed - Vibrant Signature)
        ctx.save();
        const fontSize = isMaximized ? 14 : (isGrid ? 8 : 10);

        ctx.font = `300 ${fontSize}px Inter`;
        const tw = ctx.measureText(' Trader').width;
        ctx.font = `900 ${fontSize}px Inter`;
        const bw = ctx.measureText('Bhilwara').width;

        const x = math.cw - 8 - (bw + tw);
        const y = math.pt + math.ch - 8;
        ctx.textBaseline = 'alphabetic'; ctx.textAlign = 'left';

        // Visibility Shadow (Dark contrast)
        ctx.shadowColor = 'rgba(0,0,0,0.5)';
        ctx.shadowBlur = 3; ctx.shadowOffsetY = 1;

        // "Bhilwara" Part
        ctx.font = `900 ${fontSize}px Inter`;
        ctx.fillStyle = '#f8fafc';
        ctx.fillText('Bhilwara', x, y);

        // "Trader" Part
        ctx.font = `300 ${fontSize}px Inter`;
        ctx.fillStyle = '#fbbf24';
        ctx.fillText(' Trader', x + bw, y);
        ctx.restore();

        // CLOUDS (Segmented Rendering to prevent overlapping gaps)
        const drawCloud = (trendKey, maKey, col, colDown) => {
            let lastTrend = null, segment = [];
            const flush = (t, s) => {
                if (!s.length || (t !== 1 && t !== -1)) return;
                ctx.fillStyle = t === 1 ? col : (colDown || col);
                ctx.beginPath();
                s.forEach((p, i) => i === 0 ? ctx.moveTo(p.x, p.cY) : ctx.lineTo(p.x, p.cY));
                for (let i = s.length - 1; i >= 0; i--) ctx.lineTo(s[i].x, s[i].mY);
                ctx.closePath(); ctx.fill();
            };
            math.slice.forEach((c, i) => {
                const tr = c[trendKey] || c[trendKey.toUpperCase()];
                const maValue = c[maKey] || c[maKey.toUpperCase()];
                if (tr !== lastTrend) { flush(lastTrend, segment); segment = []; lastTrend = tr; }
                if (tr === 1 || tr === -1) segment.push({ x: math.i2x(math.start + i), mY: math.p2y(maValue), cY: math.p2y(c.close) });
            });
            flush(lastTrend, segment);
        };

        if (vis.CLOUD) drawCloud('stTrend', 'stMa', COLORS.stBull, COLORS.stBear);
        if (vis.CLOUD2) drawCloud('stTrend2', 'stMa2', COLORS.stBull, COLORS.stBear);

        // EMAS
        const emas = ['ema15', 'ema15_5', 'ema15_15', 'ema15_15_15', 'ema15_15_15_15', 'ema15_15_15_15_30'];
        emas.forEach((k, idx) => {
            if (!vis[k.toUpperCase()]) return;
            ctx.strokeStyle = COLORS.emas[idx]; ctx.lineWidth = 1.0; ctx.beginPath();
            math.slice.forEach((c, i) => { const x = math.i2x(math.start + i), y = math.p2y(c[k]); if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y); });
            ctx.stroke();
        });

        // CANDLES with DELTA GLOW and FOOTPRINT MARKERS
        math.slice.forEach((c, i) => {
            const x = math.i2x(math.start + i), isUp = c.close >= c.open;
            const deltaVal = c.delta || 0;
            let col = isUp ? COLORS.bull : COLORS.bear;
            if (deltaVal > 5000 && isUp) col = '#34d399';
            else if (deltaVal < -5000 && !isUp) col = '#f87171';

            // Draw wick
            ctx.strokeStyle = col; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(x, math.p2y(c.high)); ctx.lineTo(x, math.p2y(c.low)); ctx.stroke();

            // Draw body with potential Glow Effect for High Delta
            ctx.fillStyle = col;
            if (Math.abs(deltaVal) > 10000) {
                ctx.shadowBlur = 8; ctx.shadowColor = col;
            } else {
                ctx.shadowBlur = 0;
            }

            const bw = Math.max(2, math.bs * 0.8);
            ctx.fillRect(x - bw / 2, Math.min(math.p2y(c.open), math.p2y(c.close)), bw, Math.max(1, Math.abs(math.p2y(c.open) - math.p2y(c.close))));
            ctx.shadowBlur = 0;
        });

        // Y-AXIS (Compact Sidebar)
        ctx.fillStyle = '#0a0f1d'; ctx.fillRect(math.cw, math.pt, math.pr, math.ch);
        ctx.fillStyle = '#cbd5e1'; ctx.font = '9px Inter'; ctx.textAlign = 'center';
        for (let i = 0; i < 9; i++) {
            const y = math.pt + (math.ch / 8) * i;
            ctx.fillText(math.y2p(y).toFixed(symbol.includes('SENSEX') ? 0 : 1), math.cw + (math.pr / 2), y + 3.5);
        }
        if (isLog) {
            ctx.fillStyle = '#3b82f6'; ctx.font = 'bold 8px Inter';
            ctx.fillText('LOG', math.cw + (math.pr / 2), math.pt + 8);
        }

        // X-AXIS
        ctx.fillStyle = '#0a0f1d'; ctx.fillRect(0, math.pt + math.ch, math.cw + math.pr, 20);

        // DELTA HISTOGRAM (At the footer of chart)
        math.slice.forEach((c, i) => {
            const x = math.i2x(math.start + i), d = c.delta || 0;
            if (d === 0) return;
            const hScale = Math.min(15, Math.abs(d) / 5000);
            ctx.fillStyle = d > 0 ? '#34d399' : '#f87171'; // Green/Red
            const bw = Math.max(1, math.bs * 0.5);
            const y = math.pt + math.ch - (d > 0 ? hScale : 0);
            ctx.fillRect(x - bw / 2, y, bw, hScale);
        });

        ctx.fillStyle = '#cbd5e1'; ctx.textAlign = 'center';
        const barsPerLabel = Math.max(1, Math.floor(visibleBars / 6));
        math.slice.forEach((c, i) => {
            const date = new Date(c.timestamp);
            const prev = i > 0 ? math.slice[i - 1] : dData[math.start + i - 1];
            const isNewDay = prev && date.getDate() !== new Date(prev.timestamp).getDate();

            const x = math.i2x(math.start + i);
            if (isNewDay) {
                const bw = 14, bh = 14;
                ctx.fillStyle = '#0f172a';
                ctx.beginPath(); ctx.roundRect(x - (bw / 2), math.pt + math.ch + 3, bw, bh, 3); ctx.fill();
                ctx.strokeStyle = 'rgba(245, 158, 11, 0.5)'; ctx.lineWidth = 1; ctx.stroke();

                ctx.fillStyle = '#f59e0b'; ctx.font = 'bold 10px Inter';
                ctx.fillText('D', x, math.pt + math.ch + 13.5);
                ctx.fillStyle = '#cbd5e1'; ctx.font = '9px Inter';
            } else if ((math.start + i) % barsPerLabel === 0) {
                const label = date.getHours().toString().padStart(2, '0') + ":" + date.getMinutes().toString().padStart(2, '0');
                ctx.fillText(label, x, math.pt + math.ch + 13);
            }
        });

        ctx.restore();
    }, [dData, getMath, vis, symbol]);

    const renderOverlay = useCallback(() => {
        const canvas = overlayCanvasRef.current; if (!canvas) return;
        const ctx = canvas.getContext('2d'), dpr = window.devicePixelRatio || 1, rect = canvas.getBoundingClientRect(),
            math = getMath(rect.width, rect.height, dData);
        if (!math) return;
        ctx.clearRect(0, 0, canvas.width, canvas.height); ctx.save(); ctx.scale(dpr, dpr);
        const mouse = mouseRef.current;

        if (mouse.x > 0 && mouse.x < math.cw && mouse.y > math.pt && mouse.y < math.pt + math.ch) {
            ctx.strokeStyle = 'rgba(255,255,255,0.4)'; ctx.lineWidth = 1; ctx.textAlign = 'center';
            ctx.beginPath(); ctx.moveTo(0, mouse.y); ctx.lineTo(math.cw, mouse.y); ctx.stroke();
            ctx.beginPath(); ctx.moveTo(mouse.x, math.pt); ctx.lineTo(mouse.x, math.pt + math.ch); ctx.stroke();

            ctx.fillStyle = '#3b82f6'; ctx.fillRect(math.cw, mouse.y - 10, math.pr, 20);
            ctx.fillStyle = '#fff'; ctx.font = 'bold 9px Inter';
            ctx.fillText(math.y2p(mouse.y).toFixed(2), math.cw + (math.pr / 2), mouse.y + 4);

            // X-Axis Highlight
            const barIdx = Math.floor(mouse.x / math.bs) + math.start;
            if (barIdx >= 0 && barIdx < dData.length) {
                const candle = dData[barIdx];
                const date = new Date(candle.timestamp);
                const label = date.toLocaleDateString(undefined, { day: '2-digit', month: 'short' }) + " " +
                    date.getHours().toString().padStart(2, '0') + ":" + date.getMinutes().toString().padStart(2, '0');

                ctx.font = 'bold 9px Inter';
                const tw = ctx.measureText(label).width + 10;
                const tx = Math.max(tw / 2, Math.min(math.cw - tw / 2, mouse.x));

                ctx.fillStyle = '#3b82f6';
                ctx.beginPath();
                ctx.roundRect(tx - tw / 2, math.pt + math.ch + 3, tw, 14, 3);
                ctx.fill();

                ctx.fillStyle = '#fff';
                ctx.fillText(label, tx, math.pt + math.ch + 13);
            }
        }
        orderLines.forEach(l => {
            const y = math.p2y(l.price); if (y < math.pt || y > math.pt + math.ch) return;
            const isDragging = draggingOrderRef.current?.id === l.id;
            const isBuy = l.type === 'BUY';
            const color = isBuy ? '#22c55e' : '#ef4444';

            ctx.save();
            ctx.strokeStyle = color; ctx.lineWidth = isDragging ? 2 : 1; ctx.setLineDash(l.isPending ? [5, 2] : [5, 5]);
            ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(math.cw, y); ctx.stroke(); ctx.setLineDash([]);

            ctx.font = 'bold 10px Inter'; ctx.textBaseline = 'middle'; ctx.textAlign = 'left';
            const b1 = `${l.type} ${l.qty || 1}${l.isPending ? ' (DROP)' : ''}`, b2 = `${l.price.toFixed(2)}`, b3 = '✕';
            const w1 = ctx.measureText(b1).width + 12, w2 = ctx.measureText(b2).width + 12, w3 = 18;
            let curX = 5;

            ctx.fillStyle = color; ctx.beginPath(); ctx.roundRect(curX, y - 9, w1, 18, 4); ctx.fill();
            ctx.fillStyle = '#fff'; ctx.fillText(b1, curX + 6, y); curX += w1 + 4;

            ctx.fillStyle = '#0f172a'; ctx.beginPath(); ctx.roundRect(curX, y - 9, w2, 18, 4); ctx.fill();
            ctx.strokeStyle = color + '80'; ctx.stroke();
            ctx.fillStyle = '#fff'; ctx.fillText(b2, curX + 6, y); curX += w2 + 4;

            ctx.fillStyle = '#0f172a'; ctx.beginPath(); ctx.roundRect(curX, y - 9, w3, 18, 4); ctx.fill();
            ctx.strokeStyle = color + '80'; ctx.stroke();
            ctx.fillStyle = color; ctx.fillText(b3, curX + 5, y);
            ctx.restore();
        });
        ctx.restore();
    }, [getMath, dData, orderLines, symbol]);

    const showToast = (type, msg) => { setToast({ type, msg }); setTimeout(() => setToast(null), 3000); };

    // Seed chart from WebSocket snapshot delivery (zero REST calls in scanner mode)
    const snapshotCallback = useCallback((candles) => {
        if (!candles || candles.length === 0) {
            liveDataRef.current = [];
            replayDataRef.current = [];
            setData([]);
            setLoading(false);
            return;
        }
        const normalized = candles.map(c => ({ ...c, timestamp: new Date(c.timestamp).getTime() }));
        liveDataRef.current = normalized;
        replayDataRef.current = normalized;
        setData(normalized); // trigger one re-render to update dData memo
        setLoading(false);
    }, []);

    // Use preloaded batch data if available, otherwise wait for WS snapshot
    // Fall back to individual REST fetch only for maximized (full history) view
    useEffect(() => {
        if (preloadedData && preloadedData.length > 0) {
            const normalized = preloadedData.map(c => ({ ...c, timestamp: new Date(c.timestamp).getTime() }));
            liveDataRef.current = normalized;
            replayDataRef.current = normalized;
            setData(normalized);
            setLoading(false);
            return;
        }

        // Maximized mode OR special reference anchors (NIFTY/SENSEX): fetch history via REST
        const isAnchor = (symbol === 'NIFTY 50' || symbol === 'SENSEX');
        if (isMaximized || isAnchor) {
            const controller = new AbortController();
            setLoading(true);
            axios.get(`${API_BASE}/api/history/${symbol}/${interval}?limit=4000&_t=${Date.now()}`, { signal: controller.signal })
                .then(res => {
                    const normalized = res.data.map(c => ({ ...c, timestamp: new Date(c.timestamp).getTime() }));
                    liveDataRef.current = normalized;
                    replayDataRef.current = normalized;
                    setData(normalized);
                    setLoading(false);
                })
                .catch(err => { if (!axios.isCancel(err)) { console.error(err); setLoading(false); } });
            return () => controller.abort();
        }

        // Normal Scanner mode: data will arrive via WebSocket snapshot (handled by useLiveFeed callback)
        setLoading(true);
    }, [symbol, interval, preloadedData, isMaximized]);
    useLiveFeed(symbol, interval, useCallback(u => {
        if (isReplayMode) return;
        // Mutate the ref directly — NO setState, NO array copy, NO React re-render
        const arr = liveDataRef.current;
        const f = {
            ...u.ohlc, ...u.indicators,
            timestamp: u.ohlc.t,
            open: u.ohlc.o, high: u.ohlc.h, low: u.ohlc.l, close: u.ohlc.c,
            delta: u.ohlc.d
        };
        if (arr.length && arr[arr.length - 1].timestamp === f.timestamp) {
            arr[arr.length - 1] = f;
        } else {
            arr.push(f);
        }
        // Schedule a canvas repaint if one isn't already pending — 60fps max
        if (!pendingRafRef.current) {
            pendingRafRef.current = requestAnimationFrame(() => {
                pendingRafRef.current = null;
                render();
            });
        }
    }, [isReplayMode, render]), snapshotCallback);
    useEffect(() => { let t; if (isReplaying) t = setInterval(() => setReplayIndex(p => p < replayDataRef.current.length - 1 ? p + 1 : (setIsReplaying(false), p)), 250); return () => clearInterval(t); }, [isReplaying]);
    useEffect(() => {
        render();
        renderOverlay();
    }, [dData, visibleBars, isLog, yRange, orderLines, render, renderOverlay]);

    useEffect(() => {
        const r = () => {
            if (!containerRef.current) return;
            const { offsetWidth: w, offsetHeight: h } = containerRef.current, dpr = window.devicePixelRatio || 1;
            [mainCanvasRef.current, overlayCanvasRef.current].forEach(c => { if (c) { c.width = w * dpr; c.height = h * dpr; c.style.width = w + 'px'; c.style.height = h + 'px'; } });
            render(); renderOverlay();
        };
        const o = new ResizeObserver(r); o.observe(containerRef.current); return () => o.disconnect();
    }, [render, renderOverlay]);

    const handleMouseDown = (e) => {
        isFollowing.current = false;
        const rect = mainCanvasRef.current.getBoundingClientRect();
        const math = getMath(rect.width, rect.height, dData);
        if (!math) return; // Guard during lazy-load
        const x = e.clientX - rect.left, y = e.clientY - rect.top;

        const hit = orderLines.find(l => Math.abs(math.p2y(l.price) - y) < 10);
        if (hit) {
            const ctx = overlayCanvasRef.current.getContext('2d');
            const font = 'bold 10px Inter'; ctx.font = font;
            const w1 = ctx.measureText(`${hit.type} ${hit.qty}${hit.isPending ? ' (DROP)' : ''}`).width + 12;
            const w2 = ctx.measureText(`${hit.price.toFixed(2)}`).width + 12;
            const xX = 5 + w1 + 4 + w2 + 4;
            if (x >= xX && x <= xX + 18) { handleCancel(hit.id); return; }
            draggingOrderRef.current = { id: hit.id };
            return;
        }

        dragStart.current = {
            x: e.clientX,
            y: e.clientY,
            scroll: scrollOffset.current,
            yRangeStart: { low: math.low, high: math.high },
            visibleBarsStart: visibleBars,
            isYAxis: (e.clientX - rect.left) > (rect.width - math.pr),
            isXAxis: (e.clientY - rect.top) > (rect.height - math.pb)
        };
    };

    const handleMouseUp = () => {
        if (draggingOrderRef.current) {
            const line = orderLines.find(l => l.id === draggingOrderRef.current.id);
            if (line) {
                if (line.isPending) handlePlace(line);
                else handleModify(line.id, line.price, line.qty);
            }
        }
        dragStart.current = null;
        draggingOrderRef.current = null;
    };

    const handlePlace = async (line) => {
        const roundedPrice = Math.round(line.price * 20.0) / 20.0;
        try {
            const res = await axios.post(`${API_BASE}/api/order/place`, {
                symbol, txnType: line.type, qty: line.qty, price: roundedPrice, exchange: symbol.includes('SENSEX') ? 'BSE' : 'NSE', product: 'MIS'
            });
            updateOrderLines(nx => nx.map(ol => ol.id === line.id ? { ...ol, id: res.data.orderId, price: roundedPrice, isPending: false } : ol));
            showToast('success', `Order placed: ${line.type} ${line.qty} @ ${roundedPrice.toFixed(2)}`);
        } catch (err) {
            updateOrderLines(nx => nx.filter(ol => ol.id !== line.id)); // Remove on fail
            showToast('error', `Order failed: ${err.response?.data?.error || err.message}`);
        }
    };

    const handleModify = async (id, price, qty) => {
        const roundedPrice = Math.round(price * 20.0) / 20.0;
        try {
            await axios.put(`${API_BASE}/api/order/modify`, { orderId: String(id), price: roundedPrice, qty: qty || 1 });
            showToast('success', `Order modified to ${roundedPrice.toFixed(2)}`);
        } catch (err) { showToast('error', `Modify failed: ${err.response?.data?.error || err.message}`); }
    };

    const handleCancel = async (id) => {
        try {
            await axios.delete(`${API_BASE}/api/order/cancel/${id}`);
            updateOrderLines(nx => nx.filter(l => l.id !== id));
            showToast('success', 'Order cancelled');
        } catch (err) { showToast('error', `Cancel failed: ${err.response?.data?.error || err.message}`); }
    };

    const handleMouseMove = (e) => {
        const rect = mainCanvasRef.current.getBoundingClientRect();
        const x = e.clientX - rect.left, y = e.clientY - rect.top;
        mouseRef.current = { x, y };
        const math = getMath(rect.width, rect.height, dData);
        if (!math) return; // Guard against still-loading or empty data

        if (x > 0 && x < math.cw && y > math.pt && y < math.pt + math.ch) {
            // Mouse is inside chart area
        }

        if (draggingOrderRef.current) {
            const dragId = draggingOrderRef.current.id;
            updateOrderLines(nx => nx.map(l => l.id === dragId ? { ...l, price: math.y2p(y) } : l));
        }

        if (dragStart.current) {
            const dx = e.clientX - dragStart.current.x, dy = e.clientY - dragStart.current.y;
            if (dragStart.current.isYAxis) {
                const base = dragStart.current.yRangeStart;
                const scale = Math.exp(dy * 0.005);
                const mid = (base.high + base.low) / 2;
                const newRange = ((base.high - base.low) / 2) * scale;
                if (newRange > 0.05) {
                    setYRange({ low: mid - newRange, high: mid + newRange });
                }
            } else if (dragStart.current.isXAxis) {
                const scale = Math.exp(-dx * 0.003);
                const newBars = Math.max(20, Math.min(800, Math.round(dragStart.current.visibleBarsStart * scale)));
                setVisibleBars(newBars);
            } else {
                scrollOffset.current = dragStart.current.scroll + Math.round(dx / (rect.width / visibleBars));
                if (dy !== 0) {
                    const base = dragStart.current.yRangeStart;
                    const pShift = (dy / rect.height) * (base.high - base.low);
                    setYRange({ low: base.low + pShift, high: base.high + pShift });
                }
            }
        }

        if (containerRef.current) {
            if (dragStart.current) {
                if (dragStart.current.isYAxis) containerRef.current.style.cursor = 'ns-resize';
                else if (dragStart.current.isXAxis) containerRef.current.style.cursor = 'ew-resize';
                else containerRef.current.style.cursor = 'grabbing';
            } else if (draggingOrderRef.current) {
                containerRef.current.style.cursor = 'ns-resize';
            } else if (x > math.cw) {
                containerRef.current.style.cursor = 'ns-resize';
            } else if (y > math.pt + math.ch) {
                containerRef.current.style.cursor = 'ew-resize';
            } else {
                containerRef.current.style.cursor = 'crosshair';
            }
        }

        requestAnimationFrame(() => {
            if (dragStart.current) render();
            renderOverlay();
        });
    };

    const handleBuySell = async (type) => {
        const rect = mainCanvasRef.current.getBoundingClientRect();
        const math = getMath(rect.width, rect.height, dData);
        if (!math) return; // Guard for modal/empty states
        const midY = math.pt + (math.ch / 2);
        const price = math.y2p(midY);
        const tempId = Date.now();

        try {
            const mq = await axios.get(`${API_BASE}/api/account/max-qty?symbol=${symbol}&price=${price}&leverage=5`);
            const qty = mq.data.maxQty || 1;
            updateOrderLines(nx => [...nx, { id: tempId, price, type, qty, isPending: true }]);
        } catch (err) {
            showToast('error', `Funds fetch failed: ${err.message}`);
        }
    };

    return (
        <div ref={containerRef} className={`trading-canvas-wrapper ${isHighlighted ? 'glow-highlight' : ''}`} style={{ width: '100%', height: '100%', position: 'relative', background: COLORS.bg, overflow: 'hidden', borderRadius: 'inherit' }}
            onWheel={e => { e.preventDefault(); setVisibleBars(p => Math.max(20, Math.min(800, p + (e.deltaY > 0 ? 8 : -8)))); }}
            onMouseDown={handleMouseDown} onMouseMove={handleMouseMove} onMouseUp={handleMouseUp} onMouseLeave={() => { dragStart.current = null; draggingOrderRef.current = null; mouseRef.current = { x: -1, y: -1 }; renderOverlay(); }}>
            <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: '26px', zIndex: 10, display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: 'rgba(15,23,42,0.95)', padding: '0 8px', borderBottom: '1px solid rgba(255,255,255,0.1)', backdropFilter: 'blur(8px)' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    <span style={{ color: '#fbbf24', fontSize: '11px', fontWeight: 'bold' }}>{formatSymbol(symbol)}</span>
                    <span style={{ color: '#cbd5e1', fontSize: '10px', minWidth: '35px' }}>{timeRemaining}</span>
                    <div style={{ display: 'flex', gap: '4px', marginLeft: '8px' }}>
                        {(isMaximized ? ['1min', '3min', '5min', '15min'] : []).map(tf => (
                            <button key={tf} onClick={(e) => { e.stopPropagation(); setTimeframe(tf); if (onIntervalChange) onIntervalChange(tf); }} style={{ background: interval === tf ? '#3b82f6' : '#1e293b', color: '#fff', border: 'none', fontSize: '10px', height: '18px', padding: '0 6px', borderRadius: '2px', cursor: 'pointer', opacity: loading ? 0.5 : 1 }}>{tf}</button>
                        ))}
                    </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    {loading && <div className="loader-mini" style={{ width: '12px', height: '12px', border: '2px solid #3b82f6', borderTopColor: 'transparent', borderRadius: '50%', animation: 'spin 0.8s linear infinite' }} />}
                    <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
                    <div style={{ display: 'flex', alignItems: 'center', background: 'rgba(255,255,255,0.05)', borderRadius: '3px', padding: '1px' }}>
                        <button onClick={() => setIsReplayMode(!isReplayMode)} title="Replay Mode" style={{ background: isReplayMode ? '#ef4444' : 'transparent', color: '#fff', border: 'none', fontSize: '12px', height: '18px', width: '24px', display: 'flex', justifyContent: 'center', alignItems: 'center', borderRadius: '2px', cursor: 'pointer' }}>
                            {isReplayMode ? '✕' : '⟳'}
                        </button>
                        {isReplayMode && <button onClick={() => setIsReplaying(!isReplaying)} style={{ background: '#3b82f6', color: '#fff', border: 'none', fontSize: '10px', height: '18px', padding: '0 6px', borderRadius: '2px', cursor: 'pointer', marginLeft: '4px' }}>{isReplaying ? '⏸' : '▶'}</button>}
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                        <button onClick={() => handleBuySell('BUY')} style={{ background: '#059669', color: '#fff', border: 'none', fontSize: '10px', fontWeight: 'bold', height: '18px', padding: '0 8px', borderRadius: '2px', cursor: 'pointer' }}>BL</button>
                        <button onClick={() => handleBuySell('SELL')} style={{ background: '#dc2626', color: '#fff', border: 'none', fontSize: '10px', fontWeight: 'bold', height: '18px', padding: '0 8px', borderRadius: '2px', cursor: 'pointer' }}>SL</button>
                        <button onClick={() => { setYRange(null); scrollOffset.current = 0; isFollowing.current = true; render(); }} title="Reset Chart" style={{ background: '#1e293b', color: '#fff', border: 'none', fontSize: '12px', height: '18px', width: '24px', display: 'flex', justifyContent: 'center', alignItems: 'center', borderRadius: '2px', cursor: 'pointer' }}>↺</button>
                        {!hideMaximize && (
                            <button onClick={() => onMaximize(interval)} title={isMaximized ? "Exit Maximize" : "Maximize"} style={{ background: isMaximized ? '#3b82f6' : '#1e293b', color: '#fff', border: 'none', fontSize: '12px', height: '18px', width: '24px', display: 'flex', justifyContent: 'center', alignItems: 'center', borderRadius: '2px', cursor: 'pointer' }}>{isMaximized ? '↙' : '⛶'}</button>
                        )}
                    </div>
                </div>
            </div>
            <canvas ref={mainCanvasRef} style={{ position: 'absolute', top: 0, left: 0 }} />
            <canvas ref={overlayCanvasRef} style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none' }} />
            {toast && (
                <div style={{ position: 'absolute', bottom: '30px', left: '50%', transform: 'translateX(-50%)', background: toast.type === 'success' ? 'rgba(5,150,105,0.9)' : 'rgba(220,38,38,0.9)', color: '#fff', padding: '6px 12px', borderRadius: '20px', fontSize: '11px', fontWeight: 'bold', zIndex: 100, backdropFilter: 'blur(5px)', boxShadow: '0 4px 12px rgba(0,0,0,0.5)' }}>
                    {toast.msg}
                </div>
            )}
        </div>
    );
});

export default CanvasTradingChart;
