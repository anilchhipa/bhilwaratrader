import React, { useEffect, useState, useMemo, useRef, useCallback } from 'react';
import axios from 'axios';
import { API_BASE } from '../config';
import { formatSymbol } from '../helpers/formatSymbol';
import { useLiveFeed } from '../context/LiveFeedContext';

const COLORS = {
    bg: '#020617',
    bull: '#10b981',
    bear: '#f43f5e',
    text: '#f8fafc',
    grid: 'rgba(30, 41, 59, 0.4)',
    crosshair: 'rgba(255, 255, 255, 0.3)',
    emas: ['#ffffff', '#ef4444', '#3b82f6', '#22c55e', '#f59e0b', '#06b6d4'],
    stBull: 'rgba(0, 110, 255, 0.45)', stBear: 'rgba(255, 35, 35, 0.45)', st2: 'rgba(168, 85, 247, 0.15)',
    arcBull: '#1ac200',   // green arc (bullish)
    arcBear: '#ff4040',   // red arc (bearish)
    vwap: 'rgba(251, 191, 36, 0.55)'  // session VWAP line
};

const CanvasTradingChart = React.memo(({ symbol, initialInterval = '3min', isGrid = false, isMaximized = false, onMaximize, onPriceUpdate, externalVisibility, onVisibilityUpdate, onIntervalChange, themeColors, symbolPrices, hideMaximize = false, preloadedData, isHighlighted = false, globalResetTrigger, onDelete }) => {
    const [data, setData] = useState([]);
    const [interval, setTimeframe] = useState(initialInterval);
    const [loading, setLoading] = useState(true);
    // Canonical live data lives in a ref — avoids React re-renders on every tick
    const liveDataRef = useRef([]);
    // Pending RAF handle for live-tick renders
    const pendingRafRef = useRef(null);
    // Always-current render refs — prevents stale closures in RAF callbacks
    const renderRef = useRef(null);
    const renderOverlayRef = useRef(null);
    // Throttle RAF for overlay redraws on pointer move
    const overlayRafRef = useRef(null);
    // BroadcastChannel ref for writing zoom sync messages
    const bcRef = useRef(null);

    useEffect(() => {
        setTimeframe(initialInterval);
    }, [initialInterval]);
    const vis = externalVisibility || { EMA15: true, EMA15_5: true, EMA15_15: true, EMA15_15_15: true, EMA15_15_15_15: true, EMA15_15_15_15_30: true, CLOUD: true, CLOUD2: true, ARC_VWAP: true };


    const [isReplayMode, setIsReplayMode] = useState(false);
    const [replayIndex, setReplayIndex] = useState(-1);
    const [isReplaying, setIsReplaying] = useState(false);
    const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);


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
    const [config, setConfig] = useState(null);

    useEffect(() => {
        axios.get(`${API_BASE}/api/config`).then(res => setConfig(res.data)).catch(err => console.error("Config load fail", err));
    }, []);

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

    const handleDeleteSymbol = (e) => {
        e.stopPropagation();
        setShowDeleteConfirm(true);
    };

    const confirmDeleteSymbol = async () => {
        try {
            await axios.post(`${API_BASE}/api/symbols/purge`, { symbols: [symbol] });
            localStorage.removeItem(`orders_${symbol}`);
            if (onDelete) onDelete();
        } catch (err) {
            console.error("Purge failure:", err);
        }
        setShowDeleteConfirm(false);
    };

    // Custom setter that synchronously updates memory
    const updateOrderLines = useCallback((action) => {
        setOrderLines(prev => {
            const next = typeof action === 'function' ? action(prev) : action;
            localStorage.setItem(`orders_${symbol}`, JSON.stringify(next));
            return next;
        });
    }, [symbol]);

    const activePointers = useRef(new Map());
    const pinchStartDist = useRef(null);
    const pinchStartBars = useRef(null);

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
        bcRef.current = bc;
        bc.onmessage = (e) => {
            if (e.data.type === 'SYNC_ZOOM') {
                setVisibleBars(e.data.count);
                setYRange(null); // Ensure Y-axis snaps instantly to new X window limits
            }
            if (e.data.type === 'SYNC_LOG_SCALE') setIsLog(e.data.enabled);
        };
        return () => { bc.close(); bcRef.current = null; };
    }, []);

    const containerRef = useRef(null);
    const mainCanvasRef = useRef(null);
    const overlayCanvasRef = useRef(null);
    const replayDataRef = useRef([]);

    const dData = useMemo(() => isReplayMode && replayIndex >= 0 ? replayDataRef.current.slice(0, replayIndex + 1) : liveDataRef.current, [data, isReplayMode, replayIndex]);

    const getMath = useCallback((w, h, d) => {
        const isMobile = w < 500;
        const pt = 26, pr = 48, pb = 20, cw = w - pr, ch = h - pb - pt, bs = cw / visibleBars;
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

        const p2y = (p) => {
            const y = isLog
                ? pt + ch - ((Math.log(p) - Math.log(low)) / (Math.log(high) - Math.log(low))) * ch
                : pt + ch - ((p - low) / (high - low)) * ch;
            return Math.floor(y); // Snap to pixel
        };
        const y2p = (y) => isLog
            ? Math.exp(Math.log(low) + ((pt + ch - y) / ch) * (Math.log(high) - Math.log(low)))
            : low + ((pt + ch - y) / ch) * (high - low);
        return { bs, p2y, y2p, i2x: (i) => Math.floor((i - start) * bs + (bs / 2)), start, end, ch, cw, pt, pb, pr, low, high, slice };
    }, [visibleBars, isLog, yRange]);


    const render = useCallback(() => {
        const canvas = mainCanvasRef.current; if (!canvas || !dData.length) return;
        const ctx = canvas.getContext('2d'), dpr = window.devicePixelRatio || 1,
            math = getMath(canvas.width / dpr, canvas.height / dpr, dData);
        if (!math) return;
        ctx.clearRect(0, 0, canvas.width, canvas.height); ctx.save(); ctx.scale(dpr, dpr);
        ctx.imageSmoothingEnabled = false; // Disable blurring for razor-sharp edges

        const visibleBars = math.slice.length;
        const bw = Math.max(2, math.bs * 0.85);

        // 1. Draw Cinematic Grid (at the bottom)
        ctx.strokeStyle = COLORS.grid;
        ctx.lineWidth = 0.5;
        const barsPerGrid = Math.max(1, Math.floor(visibleBars / 10));
        math.slice.forEach((c, i) => {
            if ((math.start + i) % barsPerGrid === 0) {
                const x = math.i2x(math.start + i);
                ctx.beginPath(); ctx.moveTo(x, math.pt); ctx.lineTo(x, math.pt + math.ch); ctx.stroke();
            }
        });
        for (let i = 0; i < 9; i++) {
            const y = math.pt + (math.ch / 8) * i;
            ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(math.cw, y); ctx.stroke();
        }

        // BACKGROUND WATERMARK (Brand Themed - Signature style)
        ctx.save();
        const fontSize = isMaximized ? 14 : (isGrid ? 8 : 10);
        ctx.font = `700 ${fontSize * 1.3}px "Dancing Script"`;

        const bhw = ctx.measureText('Bhilwara').width;
        const trw = ctx.measureText('trader').width;
        const gap = 4;

        const x = math.cw - 12 - (bhw + trw + gap);
        const y = math.pt + math.ch - 8;
        ctx.textBaseline = 'alphabetic'; ctx.textAlign = 'left';

        // Visibility Shadow
        ctx.shadowColor = 'rgba(0,0,0,0.5)';
        ctx.shadowBlur = 3; ctx.shadowOffsetY = 1;

        // "Bhilwara" Part
        ctx.fillStyle = '#f8fafc';
        ctx.fillText('Bhilwara', x, y);

        // "trader" Part
        ctx.fillStyle = '#fbbf24';
        ctx.fillText('trader', x + bhw + gap, y);
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
                if (tr === 1 || tr === -1) {
                    const ohlc4 = (c.open + c.high + c.low + c.close) / 4;
                    segment.push({ x: math.i2x(math.start + i), mY: math.p2y(maValue), cY: math.p2y(ohlc4) });
                }
            });
            flush(lastTrend, segment);
        };

        if (vis.CLOUD) drawCloud('stTrend', 'stMa', 'rgba(0, 110, 255, 0.45)', 'rgba(255, 35, 35, 0.45)');
        if (vis.CLOUD2) drawCloud('stTrend2', 'stMa2', 'rgba(0, 110, 255, 0.45)', 'rgba(255, 35, 35, 0.45)');

        // EMA
        const emas = ['ema15', 'ema15_5', 'ema15_15', 'ema15_15_15', 'ema15_15_15_15', 'ema15_15_15_15_30'];
        emas.forEach((k, idx) => {
            if (!vis[k.toUpperCase()]) return;
            ctx.strokeStyle = COLORS.emas[idx]; ctx.lineWidth = 0.3; ctx.beginPath();
            math.slice.forEach((c, i) => { const x = math.i2x(math.start + i), y = math.p2y(c[k]); if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y); });
            ctx.stroke();
        });

        // ── ARC VWAP SUPERTREND ──────────────────────────────────────────
        if (vis.ARC_VWAP) {
            // 1. Session VWAP line — hidden (distracting)
            // let vwapStarted = false;
            // ctx.save();
            // ctx.strokeStyle = COLORS.vwap;
            // ctx.lineWidth = 1.2;
            // ctx.setLineDash([4, 6]);
            // ctx.beginPath();
            // math.slice.forEach((c, i) => {
            //     const v = c.vwapSession;
            //     if (v === undefined || v === null || isNaN(v) || v <= 0) return;
            //     const x = math.i2x(math.start + i), y = math.p2y(v);
            //     if (!vwapStarted) { ctx.moveTo(x, y); vwapStarted = true; }
            //     else ctx.lineTo(x, y);
            // });
            // ctx.stroke();
            // ctx.setLineDash([]);
            // ctx.restore();

            // 2. Arc line — color follows trend, breaks on flips
            let lastArcTrend = null;
            ctx.save();
            ctx.lineWidth = 0.7;
            let arcPath = [];
            const flushArc = (col, path) => {
                if (path.length < 2) return;
                ctx.strokeStyle = col;
                ctx.beginPath();
                path.forEach(([x, y], i) => i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y));
                ctx.stroke();
            };
            math.slice.forEach((c, i) => {
                const av = c.arcValue;
                if (!av || av <= 0) { flushArc(lastArcTrend ? COLORS.arcBull : COLORS.arcBear, arcPath); arcPath = []; return; }
                const col = c.arcTrend ? COLORS.arcBull : COLORS.arcBear;
                if (col !== (lastArcTrend ? COLORS.arcBull : COLORS.arcBear) && arcPath.length > 0) {
                    flushArc(lastArcTrend ? COLORS.arcBull : COLORS.arcBear, arcPath);
                    arcPath = [];
                }
                lastArcTrend = c.arcTrend;
                arcPath.push([math.i2x(math.start + i), math.p2y(av)]);
            });
            flushArc(lastArcTrend ? COLORS.arcBull : COLORS.arcBear, arcPath);
            ctx.restore();

            // 3. Flip indicators — B/S for confirmed flips, ▲/▼ for unconfirmed (filtered) flips
            ctx.save();
            ctx.textAlign = 'center';
            math.slice.forEach((c, i) => {
                if (!c.arcValue || c.arcValue <= 0) return;
                const prev = i > 0 ? math.slice[i - 1] : null;
                if (!prev || !prev.arcValue) return;
                const flipped = (c.arcTrend !== prev.arcTrend);
                if (!flipped) return;
                const x = math.i2x(math.start + i);
                const arcY = math.p2y(c.arcValue);
                
                if (c.arcFlipConfirmed) {
                    const badgeY = c.arcTrend ? arcY + 22 : arcY - 14;
                    const badgeColor = c.arcTrend ? '#10b981' : '#f43f5e';
                    const text = c.arcTrend ? 'B' : 'S';
                    const size = 14; // Small compact diameter

                    ctx.save();
                    // Subtle Glow
                    ctx.shadowColor = badgeColor;
                    ctx.shadowBlur = 6;
                    ctx.shadowOffsetX = 0;
                    ctx.shadowOffsetY = 0;

                    // Draw circular badge
                    ctx.fillStyle = badgeColor;
                    ctx.beginPath();
                    ctx.arc(x, badgeY, size / 2, 0, Math.PI * 2);
                    ctx.fill();

                    // Subtle border
                    ctx.shadowBlur = 0;
                    ctx.strokeStyle = 'rgba(255, 255, 255, 0.3)';
                    ctx.lineWidth = 1;
                    ctx.stroke();

                    // Label text
                    ctx.fillStyle = '#ffffff';
                    ctx.font = 'bold 9px JetBrains Mono, Inter, sans-serif';
                    ctx.textBaseline = 'middle';
                    ctx.fillText(text, x, badgeY + 0.5);
                    ctx.restore();
                } else {
                    ctx.font = 'bold 18px Inter';
                    const alpha = 0.45; // Dimmed unconfirmed flip
                    if (c.arcTrend) {
                        // Dimmed Bull flip triangle
                        ctx.fillStyle = `rgba(26,194,0,${alpha})`;
                        ctx.fillText('▲', x, arcY + 26);
                    } else {
                        // Dimmed Bear flip triangle
                        ctx.fillStyle = `rgba(255,64,64,${alpha})`;
                        ctx.fillText('▼', x, arcY - 10);
                    }
                }
            });
            ctx.restore();
        }
        // ── END ARC VWAP ──────────────────────────────────────────────────




        // CANDLES
        math.slice.forEach((c, i) => {
            const x = math.i2x(math.start + i), isUp = c.close >= c.open;
            const col = isUp ? COLORS.bull : COLORS.bear;

            // Draw wick
            ctx.strokeStyle = col; ctx.lineWidth = 1; ctx.beginPath(); ctx.moveTo(x, math.p2y(c.high)); ctx.lineTo(x, math.p2y(c.low)); ctx.stroke();

            // Draw body
            ctx.fillStyle = col;
            const bw = Math.max(2, math.bs * 0.8);
            ctx.fillRect(x - bw / 2, Math.min(math.p2y(c.open), math.p2y(c.close)), bw, Math.max(1, Math.abs(math.p2y(c.open) - math.p2y(c.close))));


        });


        // SIGNAL MARKERS REMOVED (Bayesian System)


        // LIVE PRICE LINE
        const lastCandle = dData[dData.length - 1];
        if (lastCandle) {
            const lastPrice = lastCandle.close;
            const y = math.p2y(lastPrice);
            if (y >= math.pt && y <= math.pt + math.ch) {
                const isUp = lastCandle.close >= lastCandle.open;
                ctx.save();
                ctx.setLineDash([4, 4]);
                ctx.strokeStyle = isUp ? COLORS.bull : COLORS.bear;
                ctx.lineWidth = 0.3;
                ctx.beginPath();
                ctx.moveTo(0, y);
                ctx.lineTo(math.cw, y);
                ctx.stroke();
                ctx.restore();
            }
        }

        // Y-AXIS (Obsidian Sidebar)
        const isMobile = math.pr < 50;
        ctx.fillStyle = COLORS.bg; ctx.fillRect(math.cw, math.pt, math.pr + 1, math.ch);
        ctx.fillStyle = '#e2e8f0'; ctx.font = isMobile ? '8.5px Inter' : '9px Inter'; ctx.textAlign = 'center';
        for (let i = 0; i < 9; i++) {
            const y = math.pt + (math.ch / 8) * i;
            const priceText = math.y2p(y).toFixed(isMobile ? 0 : 1);
            ctx.fillText(priceText, math.cw + (math.pr / 2), y + 3.5);
        }

        // HIGHLIGHT LAST PRICE ON Y-AXIS
        if (lastCandle) {
            const lastPrice = lastCandle.close;
            const y = math.p2y(lastPrice);
            if (y >= math.pt && y <= math.pt + math.ch) {
                const isUp = lastCandle.close >= lastCandle.open;
                ctx.fillStyle = isUp ? COLORS.bull : COLORS.bear;
                ctx.fillRect(math.cw, y - 9, math.pr + 1, 18);
                ctx.fillStyle = '#fff';
                ctx.font = `bold ${isMobile ? '9' : '10'}px Inter`;
                ctx.textAlign = 'center';
                ctx.fillText(lastPrice.toFixed(symbol.includes('SENSEX') || symbol.includes('NIFTY') ? 0 : 2), math.cw + (math.pr / 2), y + 3.5);
            }
        }

        if (isLog) {
            ctx.fillStyle = '#3b82f6'; ctx.font = 'bold 8px Inter';
            ctx.fillText('LOG', math.cw + (math.pr / 2), math.pt + 8);
        }

        // X-AXIS (Obsidian Base)
        ctx.fillStyle = COLORS.bg; ctx.fillRect(0, math.pt + math.ch, math.cw + math.pr, 20);

        // Cinematic Vignette (Dynamic Volatility Focus)
        const currentAtr = lastCandle ? (lastCandle.atr ?? lastCandle.ATR ?? 0) : 0;
        const isLowVol = config && config.atrThreshold > 0 && currentAtr < config.atrThreshold;

        const grad = ctx.createRadialGradient(math.cw / 2, math.pt + math.ch / 2, 0, math.cw / 2, math.pt + math.ch / 2, Math.max(math.cw, math.ch));
        grad.addColorStop(0, 'rgba(0,0,0,0)');

        // If Low Vol (High Theta Decay), use a greyish "Dead Zone" vignette
        if (isLowVol) {
            grad.addColorStop(1, 'rgba(15, 23, 42, 0.85)'); // Thicker, darker slate
        } else {
            grad.addColorStop(1, 'rgba(2, 6, 23, 0.45)'); // Original sleek obsidian
        }

        ctx.fillStyle = grad;
        ctx.fillRect(0, math.pt, math.cw, math.ch);

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
            ctx.strokeStyle = 'rgba(255,255,255,0.4)'; ctx.lineWidth = 0.2; ctx.textAlign = 'center';
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
            const y = math.p2y(l.price);
            const isBuy = l.type === 'BUY';
            const color = isBuy ? '#22c55e' : '#ef4444';
            const isDragging = draggingOrderRef.current?.id === l.id;

            if (y >= math.pt && y <= math.pt + math.ch) {
                ctx.save();
                ctx.strokeStyle = color; ctx.lineWidth = isDragging ? 2 : 1; ctx.setLineDash(l.isPending ? [5, 2] : [5, 5]);
                ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(math.cw, y); ctx.stroke(); ctx.setLineDash([]);
                ctx.restore();
            }

            const domNode = document.getElementById(`order-dom-${l.id}`);
            if (domNode) {
                if (y < math.pt || y > math.pt + math.ch) {
                    domNode.style.display = 'none';
                } else {
                    domNode.style.display = 'flex';
                    domNode.style.top = (y - 12) + 'px';
                    const priceInput = domNode.querySelector('.order-price-input');
                    if (priceInput && document.activeElement !== priceInput) {
                        priceInput.value = l.price.toFixed(2);
                    }
                }
            }
        });
        ctx.restore();
    }, [getMath, dData, orderLines, symbol]);

    // Keep render refs pointing to the latest functions — avoids stale closure bugs in RAFs
    useEffect(() => { renderRef.current = render; }, [render]);
    useEffect(() => { renderOverlayRef.current = renderOverlay; }, [renderOverlay]);

    // Cancel any pending RAFs on unmount to prevent errors on detached canvases
    useEffect(() => {
        return () => {
            if (pendingRafRef.current) cancelAnimationFrame(pendingRafRef.current);
            if (overlayRafRef.current) cancelAnimationFrame(overlayRafRef.current);
        };
    }, []);

    const lastResetSignal = useRef(0);

    const onReset = useCallback(() => {
        setYRange(null);
        scrollOffset.current = 0;
        isFollowing.current = true;
        setVisibleBars(160);
    }, []);

    const showToast = (type, msg) => { setToast({ type, msg }); setTimeout(() => setToast(null), 3000); };


    // Seed chart from WebSocket snapshot delivery (zero REST calls in scanner mode)
    const snapshotCallback = useCallback((data) => {
        if (!data || !data.candles || data.candles.length === 0) {
            liveDataRef.current = [];
            replayDataRef.current = [];
            setData([]);
            setLoading(false);
            return;
        }
        const normalized = data.candles.map(c => {
            const base = { ...c, timestamp: new Date(c.timestamp).getTime() };
            if (c.indicators) Object.assign(base, c.indicators);
            return base;
        });
        liveDataRef.current = normalized;
        replayDataRef.current = normalized;
        setData(normalized); // trigger one re-render to update dData memo
        setLoading(false);
    }, []);

    // Use preloaded batch data if available, otherwise wait for WS snapshot
    // Fall back to individual REST fetch only for maximized (full history) view
    useEffect(() => {
        if (preloadedData && preloadedData.length > 0) {
            const normalized = preloadedData.map(c => {
                const base = { ...c, timestamp: new Date(c.timestamp).getTime() };
                if (c.indicators) Object.assign(base, c.indicators);
                return base;
            });
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
                    const normalized = res.data.map(c => {
                        const base = { ...c, timestamp: new Date(c.timestamp).getTime() };
                        if (c.indicators) Object.assign(base, c.indicators);
                        return base;
                    });
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
    useEffect(() => {
        if (globalResetTrigger > 0 && globalResetTrigger !== lastResetSignal.current) {
            lastResetSignal.current = globalResetTrigger;
            onReset();
        }
    }, [globalResetTrigger, onReset]);

    useLiveFeed(symbol, interval, useCallback(u => {
        if (isReplayMode) return;

        // Mutate the ref directly — NO setState, NO array copy, NO React re-render
        const arr = liveDataRef.current;
        const f = {
            ...u.ohlc,
            ...(u.indicators || {}),
            timestamp: u.ohlc.t,
            open: u.ohlc.o, high: u.ohlc.h, low: u.ohlc.l, close: u.ohlc.c,
            atr: u.ohlc.a
        };
        if (arr.length && arr[arr.length - 1].timestamp === f.timestamp) {
            arr[arr.length - 1] = f;
        } else {
            arr.push(f);
        }

        // Schedule a canvas repaint if one isn't already pending — 60fps max.
        // renderRef always points to the latest render closure, preventing stale captures.
        if (!pendingRafRef.current) {
            pendingRafRef.current = requestAnimationFrame(() => {
                pendingRafRef.current = null;
                renderRef.current?.();
                renderOverlayRef.current?.(); // Keep order labels in sync on every live tick
            });
        }
    }, [isReplayMode]), snapshotCallback);
    useEffect(() => { let t; if (isReplaying) t = setInterval(() => setReplayIndex(p => p < replayDataRef.current.length - 1 ? p + 1 : (setIsReplaying(false), p)), 1000); return () => clearInterval(t); }, [isReplaying]);
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
        activePointers.current.set(e.pointerId, e);
        if (activePointers.current.size === 2) {
            const pts = Array.from(activePointers.current.values());
            pinchStartDist.current = Math.hypot(pts[0].clientX - pts[1].clientX, pts[0].clientY - pts[1].clientY);
            pinchStartBars.current = visibleBars;
            dragStart.current = null; // Cancel any single-finger drag
            return;
        }

        if (!e.isPrimary) return;
        isFollowing.current = false;
        const rect = mainCanvasRef.current.getBoundingClientRect();
        const math = getMath(rect.width, rect.height, dData);
        if (!math) return; // Guard during lazy-load
        const x = e.clientX - rect.left, y = e.clientY - rect.top;

        // Ignore clicks on header (26px height) to allow scrolling and avoid accidental drags
        if (y < 26) return;

        const hit = orderLines.find(l => Math.abs(math.p2y(l.price) - y) < 10);
        if (hit) {
            draggingOrderRef.current = { id: hit.id, originalPrice: hit.price };
            return;
        }

        const isYAxis = (e.clientX - rect.left) > (rect.width - math.pr);
        const isXAxis = (e.clientY - rect.top) > (rect.height - math.pb);

        // Capture pointer if on axis to ensure we get events even if user moves out
        if (isYAxis || isXAxis) {
            e.target.setPointerCapture(e.pointerId);
        }

        dragStart.current = {
            x: e.clientX,
            y: e.clientY,
            scroll: scrollOffset.current,
            yRangeStart: { low: math.low, high: math.high },
            visibleBarsStart: visibleBars,
            isYAxis,
            isXAxis
        };
    };

    const handleMouseUp = (e) => {
        activePointers.current.delete(e.pointerId);
        if (activePointers.current.size < 2) {
            pinchStartDist.current = null;
            pinchStartBars.current = null;
        }

        // Grab and IMMEDIATELY clear the drag ref so the line stops following the cursor
        const dragInfo = draggingOrderRef.current;
        draggingOrderRef.current = null;
        dragStart.current = null;

        if (dragInfo) {
            const line = orderLines.find(l => l.id === dragInfo.id);
            if (line) {
                if (line.isPending) {
                    handlePlace(line);
                } else {
                    // Send price modification to broker
                    handleModify(line.id, line.price, line.qty);
                    // Focus the qty input on the order label so the user can adjust qty if needed
                    setTimeout(() => {
                        const domNode = document.getElementById(`order-dom-${line.id}`);
                        if (domNode) {
                            const input = domNode.querySelector('input[type="number"]');
                            if (input) input.focus();
                        }
                    }, 50);
                }
            }
        }
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
        activePointers.current.set(e.pointerId, e);

        if (activePointers.current.size === 2 && pinchStartDist.current) {
            const pts = Array.from(activePointers.current.values());
            const dist = Math.hypot(pts[0].clientX - pts[1].clientX, pts[0].clientY - pts[1].clientY);
            const ratio = pinchStartDist.current / dist;
            const newBars = Math.max(20, Math.min(800, Math.floor(pinchStartBars.current * ratio)));
            if (newBars !== visibleBars) {
                setVisibleBars(newBars);
                bcRef.current?.postMessage({ type: 'SYNC_ZOOM', count: newBars }); // Sync pinch zoom across all charts
            }
            return;
        }

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

        if (dragStart.current && activePointers.current.size === 1) {
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

        // Throttle overlay redraws — at most one pending RAF frame at a time
        if (!overlayRafRef.current) {
            overlayRafRef.current = requestAnimationFrame(() => {
                overlayRafRef.current = null;
                if (dragStart.current) renderRef.current?.();
                renderOverlayRef.current?.();
            });
        }
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

            // Order label is rendered via the DOM order-lines block below — no extra state needed

        } catch (err) {
            showToast('error', `Funds fetch failed: ${err.message}`);
        }
    };


    return (
        <div ref={containerRef} className={`trading-canvas-wrapper ${isHighlighted ? 'glow-highlight' : ''}`} style={{ width: '100%', height: '100%', position: 'relative', background: COLORS.bg, overflow: 'hidden', borderRadius: 'inherit', touchAction: 'pan-y' }}
            onWheel={e => {
                e.preventDefault();
                setVisibleBars(p => {
                    const next = Math.max(20, Math.min(800, p + (e.deltaY > 0 ? 8 : -8)));
                    bcRef.current?.postMessage({ type: 'SYNC_ZOOM', count: next }); // Sync wheel zoom across all charts
                    return next;
                });
            }}
            onPointerDown={handleMouseDown} onPointerMove={handleMouseMove} onPointerUp={handleMouseUp}
            onPointerCancel={(e) => { activePointers.current.clear(); pinchStartDist.current = null; dragStart.current = null; draggingOrderRef.current = null; mouseRef.current = { x: -1, y: -1 }; renderOverlay(); }}
            onPointerLeave={(e) => { activePointers.current.delete(e.pointerId); if (activePointers.current.size === 0) { dragStart.current = null; draggingOrderRef.current = null; mouseRef.current = { x: -1, y: -1 }; renderOverlay(); } }}>

            {/* Y-Axis Touch Capture Zone (Allows scaling without triggering page scroll) */}
            <div style={{ position: 'absolute', top: '26px', right: 0, bottom: '20px', width: '48px', touchAction: 'none', zIndex: 100 }} />

            <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: '26px', zIndex: 10, display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: 'rgba(15,23,42,0.95)', padding: '0 8px', borderBottom: '1px solid rgba(255,255,255,0.1)', backdropFilter: 'blur(8px)', touchAction: 'pan-y' }} onPointerDown={e => e.stopPropagation()}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    <span style={{ color: '#fbbf24', fontSize: '11px', fontWeight: 'bold' }}>{formatSymbol(symbol)}</span>



                    <span style={{ color: '#cbd5e1', fontSize: '10px', minWidth: '35px' }}>{timeRemaining}</span>
                    <div style={{ display: 'flex', gap: '4px', marginLeft: '8px' }}>
                        {(isMaximized ? ['1min', '3min', '5min', '15min'] : []).map(tf => (
                            <button key={tf} onClick={(e) => { e.stopPropagation(); setTimeframe(tf); if (onIntervalChange) onIntervalChange(tf); }} style={{ background: interval === tf ? '#3b82f6' : '#1e293b', color: '#fff', border: 'none', fontSize: '10px', height: '18px', padding: '0 6px', borderRadius: '2px', cursor: 'pointer', opacity: loading ? 0.5 : 1 }}>
                                {tf.replace('min', 'M')}
                            </button>
                        ))}
                    </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    {loading && <div className="loader-mini" style={{ width: '12px', height: '12px', border: '2px solid #3b82f6', borderTopColor: 'transparent', borderRadius: '50%', animation: 'spin 0.8s linear infinite' }} />}
                    <style>{`@keyframes spin { to { transform: rotate(360deg); } }`}</style>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
                        <button
                            onClick={handleDeleteSymbol}
                            className="chart-tool-btn btn-delete"
                            title="Delete / Purge Symbol"
                            style={{ marginRight: '4px', background: 'rgba(239, 68, 68, 0.15)', color: '#ef4444', border: '1px solid rgba(239, 68, 68, 0.3)' }}
                        >
                            <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path></svg>
                        </button>
                        <button
                            onClick={() => setIsReplayMode(!isReplayMode)}
                            className={`chart-tool-btn btn-replay ${isReplayMode ? 'active' : ''}`}
                            data-tooltip="Toggle Replay Mode"
                        >
                            {isReplayMode ? (
                                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>
                            ) : (
                                <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><polygon points="11 19 2 12 11 5 11 19"></polygon><polygon points="22 19 13 12 22 5 22 19"></polygon></svg>
                            )}
                        </button>
                        {isReplayMode && (
                            <button
                                onClick={() => setIsReplaying(!isReplaying)}
                                className="chart-tool-btn btn-replay"
                                style={{ background: isReplaying ? 'rgba(59, 130, 246, 0.4)' : '' }}
                                data-tooltip={isReplaying ? "Pause" : "Play"}
                            >
                                {isReplaying ? (
                                    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="4" strokeLinecap="round" strokeLinejoin="round"><rect x="6" y="4" width="4" height="16"></rect><rect x="14" y="4" width="4" height="16"></rect></svg>
                                ) : (
                                    <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor" stroke="none"><polygon points="5,3 19,12 5,21"></polygon></svg>
                                )}
                            </button>
                        )}
                        <button onClick={() => handleBuySell('BUY')} className="chart-tool-btn btn-buy" data-tooltip="Place Buy Order">BL</button>
                        <button onClick={() => handleBuySell('SELL')} className="chart-tool-btn btn-sell" data-tooltip="Place Sell Order">SL</button>
                        {!hideMaximize && (
                            <button
                                onClick={() => onMaximize(interval)}
                                className={`chart-tool-btn btn-max ${isMaximized ? 'active' : ''}`}
                                data-tooltip={isMaximized ? "Exit Full View" : "Maximize View"}
                            >
                                {isMaximized ? (
                                    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><path d="M4 14l10-10"></path><path d="M20 4h-6v6"></path><path d="M4 20h6v-6"></path></svg>
                                ) : (
                                    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"><path d="M15 3h6v6"></path><path d="M9 21H3v-6"></path><path d="M21 3l-7 7"></path><path d="M3 21l7-7"></path></svg>
                                )}
                            </button>
                        )}
                    </div>
                </div>
            </div>
            <canvas ref={mainCanvasRef} style={{ position: 'absolute', top: 0, left: 0, borderRadius: 'inherit' }} />
            <canvas ref={overlayCanvasRef} style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none', borderRadius: 'inherit' }} />
            {toast && (
                <div style={{ position: 'absolute', bottom: '30px', left: '50%', transform: 'translateX(-50%)', background: toast.type === 'success' ? 'rgba(5,150,105,0.9)' : 'rgba(220,38,38,0.9)', color: '#fff', padding: '6px 12px', borderRadius: '20px', fontSize: '11px', fontWeight: 'bold', zIndex: 100, backdropFilter: 'blur(5px)', boxShadow: '0 4px 12px rgba(0,0,0,0.5)' }}>
                    {toast.msg}
                </div>
            )}

            {/* BAYESIAN INTEL OVERLAY REMOVED */}

            {/* Custom Delete Confirmation Modal */}
            {showDeleteConfirm && (
                <div style={{ position: 'absolute', inset: 0, zIndex: 1000, background: 'rgba(2, 6, 23, 0.6)', backdropFilter: 'blur(4px)', display: 'flex', justifyContent: 'center', alignItems: 'center', fontFamily: '"Inter", sans-serif' }}
                    onClick={() => setShowDeleteConfirm(false)}>
                    <div style={{ background: '#0f172a', border: '1px solid rgba(239, 68, 68, 0.4)', borderRadius: '12px', padding: '20px', width: '280px', boxShadow: '0 20px 40px rgba(0,0,0,0.8), 0 0 20px rgba(239, 68, 68, 0.15)', textAlign: 'center', animation: 'popIn 0.2s cubic-bezier(0.18, 0.89, 0.32, 1.28)' }}
                        onClick={e => e.stopPropagation()}>
                        <div style={{ width: '40px', height: '40px', borderRadius: '50%', background: 'rgba(239,68,68,0.1)', display: 'flex', justifyContent: 'center', alignItems: 'center', margin: '0 auto 12px' }}>
                            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path></svg>
                        </div>
                        <h3 style={{ margin: '0 0 8px 0', fontSize: '15px', color: '#f8fafc', fontWeight: 600 }}>Delete {symbol}?</h3>
                        <p style={{ margin: '0 0 20px 0', fontSize: '12px', color: '#94a3b8', lineHeight: '1.4' }}>This will permanently remove the symbol from your tracked list and clear all cached history.</p>
                        <div style={{ display: 'flex', gap: '10px' }}>
                            <button onClick={() => setShowDeleteConfirm(false)}
                                style={{ flex: 1, padding: '8px 0', background: 'transparent', border: '1px solid rgba(255,255,255,0.1)', color: '#cbd5e1', borderRadius: '6px', fontSize: '12px', fontWeight: 600, cursor: 'pointer', transition: 'all 0.2s' }}>Cancel</button>
                            <button onClick={confirmDeleteSymbol}
                                style={{ flex: 1, padding: '8px 0', background: '#ef4444', border: 'none', color: '#fff', borderRadius: '6px', fontSize: '12px', fontWeight: 600, cursor: 'pointer', boxShadow: '0 4px 12px rgba(239,68,68,0.3)', transition: 'all 0.2s' }}>Yes, Delete</button>
                        </div>
                    </div>
                    <style>{`@keyframes popIn { from { transform: scale(0.9); opacity: 0; } to { transform: scale(1); opacity: 1; } }`}</style>
                </div>
            )}

            {orderLines.map(l => {
                const isBuy = l.type === 'BUY';
                const color = isBuy ? '#22c55e' : '#ef4444';
                return (
                    <div key={l.id} id={`order-dom-${l.id}`} style={{
                        position: 'absolute',
                        display: 'none',
                        alignItems: 'center',
                        gap: '4px',
                        zIndex: 100,
                        left: '5px',
                        top: 0
                    }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '4px', background: color, padding: '2px 4px', borderRadius: '4px', boxShadow: '0 2px 4px rgba(0,0,0,0.5)' }}>
                            <span style={{ fontSize: '10px', fontWeight: 'bold', color: '#fff' }}>{l.type}{l.isPending ? ' (DROP)' : ''}</span>
                            <input
                                type="number"
                                defaultValue={l.qty}
                                onFocus={e => e.target.select()}
                                onBlur={(e) => {
                                    const newQty = parseInt(e.target.value, 10);
                                    if (!isNaN(newQty) && newQty > 0) {
                                        if (!l.isPending && l.qty !== newQty) handleModify(l.id, l.price, newQty);
                                        updateOrderLines(nx => nx.map(ol => ol.id === l.id ? { ...ol, qty: newQty } : ol));
                                    } else {
                                        e.target.value = l.qty;
                                    }
                                }}
                                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === 'Escape') e.target.blur(); }}
                                onPointerDown={e => e.stopPropagation()}
                                style={{ width: '45px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.2)', color: '#fff', fontSize: '11px', outline: 'none', fontWeight: 'bold', textAlign: 'center', borderRadius: '2px' }}
                            />
                        </div>

                        <div style={{ display: 'flex', alignItems: 'center', background: '#0f172a', padding: '2px 4px', borderRadius: '4px', border: `1px solid ${color}80`, boxShadow: '0 2px 4px rgba(0,0,0,0.5)' }}>
                            <input
                                className="order-price-input"
                                type="number"
                                step="0.05"
                                defaultValue={l.price.toFixed(2)}
                                onFocus={e => e.target.select()}
                                onBlur={(e) => {
                                    const newPrice = parseFloat(e.target.value);
                                    if (!isNaN(newPrice) && newPrice > 0) {
                                        if (!l.isPending && l.price !== newPrice) handleModify(l.id, newPrice, l.qty);
                                        updateOrderLines(nx => nx.map(ol => ol.id === l.id ? { ...ol, price: newPrice } : ol));
                                    } else {
                                        e.target.value = l.price.toFixed(2);
                                    }
                                }}
                                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === 'Escape') e.target.blur(); }}
                                onPointerDown={e => e.stopPropagation()}
                                style={{ width: '60px', background: 'transparent', border: 'none', color: '#fff', fontSize: '11px', outline: 'none', fontWeight: 'bold', textAlign: 'center' }}
                            />
                        </div>

                        <button
                            onClick={(e) => {
                                e.stopPropagation();
                                handleCancel(l.id);
                            }}
                            onPointerDown={e => e.stopPropagation()}
                            style={{ background: '#0f172a', border: `1px solid ${color}80`, color: color, borderRadius: '4px', width: '22px', height: '22px', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '11px', fontWeight: 'bold', boxShadow: '0 2px 4px rgba(0,0,0,0.5)' }}>✕</button>
                    </div>
                );
            })}
        </div>
    );
});

export default CanvasTradingChart;
