import React, { useEffect, useState, useMemo, useRef, useCallback } from 'react';
import axios from 'axios';
import { API_BASE } from '../config';
import { formatSymbol } from '../helpers/formatSymbol';
import { useLiveFeed, useTopicFeed } from '../context/LiveFeedContext';

let globalConfigPromise = null;
const getGlobalConfig = () => {
    if (!globalConfigPromise) {
        globalConfigPromise = axios.get(`${API_BASE}/api/config`)
            .then(res => res.data)
            .catch(err => {
                globalConfigPromise = null;
                console.error("Config load fail", err);
                return null;
            });
    }
    return globalConfigPromise;
};

import { COLORS, DEFAULT_VISIBILITY } from '../helpers/chartConstants';
import { computeDeterministicRenkoGuppy } from '../helpers/nonRepaintingRenkoGuppy';
import CountdownTimer from './CountdownTimer';
import ErrorBoundary from './ErrorBoundary';

const CanvasTradingChart = React.memo(({ symbol, initialInterval = '3min', isGrid = false, isMaximized = false, onMaximize, onPriceUpdate, externalVisibility, onVisibilityUpdate, onIntervalChange, themeColors, hideMaximize = false, preloadedData, isHighlighted = false, globalResetTrigger, onDelete, defaultShowRenko = false }) => {
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
    const vis = externalVisibility || DEFAULT_VISIBILITY;


    const [isReplayMode, setIsReplayMode] = useState(false);
    const [replayIndex, setReplayIndex] = useState(-1);
    const [isReplaying, setIsReplaying] = useState(false);
    const [isCrosshairForced, setIsCrosshairForced] = useState(false);
    const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);


    // NAVIGATION STATE
    const scrollOffset = useRef(0);
    const [visibleBars, setVisibleBars] = useState(() => parseInt(localStorage.getItem('chart_zoom_global')) || 160);
    const [isLog, setIsLog] = useState(() => localStorage.getItem('chart_log_scale') === 'true');
    const [yRange, setYRange] = useState(null); // { low, high } - for manual Y pan
    const [renkoYRange, setRenkoYRange] = useState(null); // { low, high } - for manual Renko Y pan/zoom
    const isFollowing = useRef(true);
    // Renko Guppy overlay toggle
    const [showRenko, setShowRenko] = useState(defaultShowRenko);
    const renkoCanvasRef = useRef(null);
    const renkoScrollOffset = useRef(0);
    const [renkoVisibleBricks, setRenkoVisibleBricks] = useState(300);
    const renkoMathRef = useRef(null);
    const renkoRenderRef = useRef(null);
    const wheelRafRef = useRef(null);
    const [orderLines, setOrderLines] = useState([]);
    const [toast, setToast] = useState(null); // { type, msg }
    const mouseRef = useRef({ x: -1, y: -1 });
    const draggingOrderRef = useRef(null);
    const dragStart = useRef(null);


    const [config, setConfig] = useState(null);

    useEffect(() => {
        getGlobalConfig().then(cfg => {
            if (cfg) setConfig(cfg);
        });
    }, []);



    // ── Supertrend MTF Heikin Ashi data (fallback initial load if not already in candles) ──
    const [stMtfHaData, setStMtfHaData] = useState(null);
    const showStMtfHa = !!(vis.ST_HA_CURRENT || vis.ST_HA_HTF || vis.ST_HA_FLIPS);

    useEffect(() => {
        if (!showStMtfHa) {
            setStMtfHaData(null);
            return;
        }
        // If live data already has haTrailingSl populated on candles, no REST fetch needed!
        const hasLiveHa = liveDataRef.current && liveDataRef.current.some(c => c.haTrailingSl > 0);
        if (hasLiveHa) return;

        let active = true;
        const fetchStMtfHa = async () => {
            try {
                const res = await axios.get(`${API_BASE}/api/supertrend-mtf-ha/${symbol}?interval=${interval}&limit=1000`);
                if (active && res.data && res.data.points) {
                    setStMtfHaData(res.data.points);
                }
            } catch (err) {
                // Ignore error if aborted or component unmounted
            }
        };
        fetchStMtfHa();
        return () => { active = false; };
    }, [showStMtfHa, symbol, interval]);





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
    const crosshairActiveRef = useRef(false);
    const longPressTimerRef = useRef(null);

    // Load existing orders for this symbol
    useEffect(() => {
        const saved = localStorage.getItem(`orders_${symbol}`);
        if (saved) {
            try { setOrderLines(JSON.parse(saved)); } catch (e) { setOrderLines([]); }
        } else {
            setOrderLines([]);
        }
    }, [symbol]);

    // ── Direct Chart-Click Order & Hotkey State (B=Buy, S=Sell, E=Exit) ──
    const activeHotkeyRef = useRef(null); // 'BUY' | 'SELL' | 'EXIT' | null
    const lastHotkeyRef   = useRef(null);
    const isHoveredRef    = useRef(false);
    const positionRef     = useRef({ netQty: 0, direction: null });

    const fetchSymbolPosition = useCallback(async () => {
        try {
            const res = await axios.get(`${API_BASE}/api/order/position/${symbol}`);
            if (res.data) {
                const netQty = res.data.netQty || 0;
                const dir = netQty > 0 ? 'BUY' : (netQty < 0 ? 'SELL' : null);
                positionRef.current = { netQty, direction: dir, buyQty: res.data.buyQty, sellQty: res.data.sellQty };
            }
        } catch (err) {
            // Broker offline or paper trading
        }
    }, [symbol]);

    useEffect(() => {
        fetchSymbolPosition();
    }, [symbol, fetchSymbolPosition]);

    const getExitTradeInfo = useCallback(() => {
        // 1. Check real broker position for this symbol
        if (positionRef.current && positionRef.current.netQty !== 0) {
            const netQty = positionRef.current.netQty;
            return {
                hasTrade: true,
                exitTxnType: netQty > 0 ? 'SELL' : 'BUY',
                exitQty: Math.abs(netQty),
                source: 'position'
            };
        }
        // 2. Check active order lines in this chart
        const nonPending = orderLines.filter(l => !l.isPending);
        if (nonPending.length > 0) {
            const lastOrder = nonPending[nonPending.length - 1];
            return {
                hasTrade: true,
                exitTxnType: lastOrder.type === 'BUY' ? 'SELL' : 'BUY',
                exitQty: lastOrder.qty || 1,
                source: 'chart_order'
            };
        }
        // 3. Fallback to any order lines in this chart
        if (orderLines.length > 0) {
            const lastOrder = orderLines[orderLines.length - 1];
            return {
                hasTrade: true,
                exitTxnType: lastOrder.type === 'BUY' ? 'SELL' : 'BUY',
                exitQty: lastOrder.qty || 1,
                source: 'chart_order'
            };
        }
        return { hasTrade: false };
    }, [orderLines]);

    useEffect(() => {
        const handleKeyDown = (e) => {
            const tag = document.activeElement ? document.activeElement.tagName.toLowerCase() : '';
            if (tag === 'input' || tag === 'textarea' || tag === 'select' || document.activeElement?.isContentEditable) {
                return;
            }
            if (!isHoveredRef.current) return;

            const key = e.key.toLowerCase();
            let mode = null;
            if (key === 'b') mode = 'BUY';
            else if (key === 's') mode = 'SELL';
            else if (key === 'e') mode = 'EXIT';

            if (mode && activeHotkeyRef.current !== mode) {
                activeHotkeyRef.current = mode;
                if (mode === 'EXIT') {
                    fetchSymbolPosition();
                }
                renderOverlayRef.current?.();
            }
        };

        const handleKeyUp = (e) => {
            const key = e.key.toLowerCase();
            if ((key === 'b' && activeHotkeyRef.current === 'BUY') ||
                (key === 's' && activeHotkeyRef.current === 'SELL') ||
                (key === 'e' && activeHotkeyRef.current === 'EXIT')) {
                activeHotkeyRef.current = null;
                renderOverlayRef.current?.();
            }
        };

        const handleBlur = () => {
            if (activeHotkeyRef.current) {
                activeHotkeyRef.current = null;
                renderOverlayRef.current?.();
            }
        };

        window.addEventListener('keydown', handleKeyDown);
        window.addEventListener('keyup', handleKeyUp);
        window.addEventListener('blur', handleBlur);
        return () => {
            window.removeEventListener('keydown', handleKeyDown);
            window.removeEventListener('keyup', handleKeyUp);
            window.removeEventListener('blur', handleBlur);
        };
    }, [fetchSymbolPosition]);

    useEffect(() => {
        const bc = new BroadcastChannel('grid_chart_sync');
        bcRef.current = bc;
        bc.onmessage = (e) => {
            if (e.data.type === 'SYNC_ZOOM') {
                setVisibleBars(e.data.count);
                setYRange(null); // Ensure Y-axis snaps instantly to new X window limits
                setRenkoYRange(null);
            }
            if (e.data.type === 'SYNC_LOG_SCALE') setIsLog(e.data.enabled);
        };
        return () => { bc.close(); bcRef.current = null; };
    }, []);

    const containerRef = useRef(null);
    const mainCanvasRef = useRef(null);
    const overlayCanvasRef = useRef(null);
    const replayDataRef = useRef([]);
    // ── Issue 4 fix: cache canvas 2D contexts in refs so getContext() is not
    // called on every animation frame (it has a small but real lookup cost).
    const mainCtxRef    = useRef(null);
    const overlayCtxRef = useRef(null);
    const renkoCtxRef   = useRef(null);
    // ── Issue 6 fix: track last mouse position to skip overlay redraws when
    // nothing has actually changed (mouse idle + no order updates).
    const lastMouseRef      = useRef({ x: -1, y: -1 });
    const lastOrderHashRef  = useRef('');
    // ── Issue 7 fix: keep a stable Map of order-line DOM nodes keyed by order
    // id so we can look them up in O(1) without querySelector on every frame.
    const orderDomRefs = useRef(new Map());
    // Cache for Guppy Renko EMAs to eliminate per-frame Float64Array allocations on hover
    const renkoEmaCacheRef = useRef(null);

    const dData = useMemo(() => isReplayMode && replayIndex >= 0 ? replayDataRef.current.slice(0, replayIndex + 1) : liveDataRef.current, [data, isReplayMode, replayIndex]);

    const getMath = useCallback((w, h, d) => {
        const isMobile = w < 550 || (typeof window !== 'undefined' && window.innerWidth <= 768);
        const pr = isMobile ? 32 : 38, pt = 26, pb = 20, cw = w - pr;
        const totalHeight = h - pb - pt;
        const panelCh = showRenko ? Math.floor(totalHeight * 0.3) : 0;
        const ch = totalHeight - panelCh;
        const panelPt = pt + ch;
        // Dynamic zoom limit based on container width: ensure bar spacing (bs) is at least 1.25px
        // so candles always have their own discrete pixel column and never collide
        const maxBarsForWidth = Math.max(20, Math.floor(cw / 1.25));
        const effBars = Math.min(visibleBars, maxBarsForWidth);
        const bs = cw / effBars;
        const tot = d.length;
        const margin = Math.floor(effBars * 0.35); // 35% blank space from right
        const end = tot - scrollOffset.current + margin;
        const start = Math.max(0, end - effBars);
        const slice = d.slice(Math.max(0, start), Math.min(tot, end));
        if (!slice.length) return null;

        let low = Infinity, high = -Infinity;
        for (let i = 0; i < slice.length; i++) {
            const c = slice[i];
            const cLow = typeof c.low === 'number' && !isNaN(c.low) ? c.low : (typeof c.l === 'number' ? c.l : c.close);
            const cHigh = typeof c.high === 'number' && !isNaN(c.high) ? c.high : (typeof c.h === 'number' ? c.h : c.close);
            if (typeof cLow === 'number' && !isNaN(cLow) && cLow < low) low = cLow;
            if (typeof cHigh === 'number' && !isNaN(cHigh) && cHigh > high) high = cHigh;
        }

        // Safety fallback if no valid prices found
        if (low === Infinity || high === -Infinity || isNaN(low) || isNaN(high)) {
            low = 1;
            high = 100;
        }

        if (yRange && typeof yRange.low === 'number' && !isNaN(yRange.low) && typeof yRange.high === 'number' && !isNaN(yRange.high)) {
            low = yRange.low;
            high = yRange.high;
        } else {
            const effectiveLog = isLog && high > 0;
            if (effectiveLog) {
                // Log scale: padding must be applied in logarithmic space!
                // Linear padding on dropping options pushes low <= 0, causing Math.log(low) = NaN
                const safeLow = Math.max(0.01, low > 0 ? low : high * 0.001);
                const safeHigh = Math.max(safeLow + 0.01, high);
                const logLow = Math.log(safeLow);
                const logHigh = Math.log(safeHigh);
                const logPad = (logHigh - logLow) * 0.08;
                low = Math.exp(logLow - logPad);
                high = Math.exp(logHigh + logPad);
            } else {
                const pad = (high - low) * 0.1;
                low -= pad;
                high += pad;
            }
        }

        if (high <= low) high = low + 1;

        // Log scale safety: logarithmic price scales are strictly defined for prices > 0.
        const effectiveLog = isLog && high > 0;
        if (effectiveLog) {
            const minSafeLow = Math.max(0.001, high * 0.0001);
            if (low <= 0 || isNaN(low)) low = minSafeLow;
            if (high <= low) high = low + 1;
        }

        const p2y = (p) => {
            if (p == null || isNaN(p)) return -9999;
            const safeP = effectiveLog ? Math.max(0.0001, p) : p;
            const y = effectiveLog
                ? pt + ch - ((Math.log(safeP) - Math.log(low)) / (Math.log(high) - Math.log(low))) * ch
                : pt + ch - ((p - low) / (high - low)) * ch;
            return Math.floor(y); // Snap to pixel
        };
        const y2p = (y) => {
            const val = effectiveLog
                ? Math.exp(Math.log(low) + ((pt + ch - y) / ch) * (Math.log(high) - Math.log(low)))
                : low + ((pt + ch - y) / ch) * (high - low);
            return isNaN(val) ? 0 : val;
        };
        return { bs, p2y, y2p, i2x: (i) => Math.floor((i - start) * bs + (bs / 2)), start, end, ch, cw, pt, pb, pr, low, high, slice, panelPt, panelCh, visibleBars: effBars };
    }, [visibleBars, isLog, yRange, showRenko]);



    const render = useCallback(() => {
        const canvas = mainCanvasRef.current; if (!canvas) return;
        // Use cached context (Issue 4 fix)
        if (!mainCtxRef.current) mainCtxRef.current = canvas.getContext('2d');
        const ctx = mainCtxRef.current;
        if (!dData || !dData.length) {
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            return;
        }
        const dpr = window.devicePixelRatio || 1;
        const math = getMath(canvas.width / dpr, canvas.height / dpr, dData);
        if (!math) {
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            return;
        }
        ctx.clearRect(0, 0, canvas.width, canvas.height); ctx.save(); ctx.scale(dpr, dpr);
        ctx.imageSmoothingEnabled = false; // Disable blurring for razor-sharp edges

        const visibleBars = math.slice.length;

        // 1. Draw Cinematic Grid (at the bottom) - REMOVED

        // BACKGROUND WATERMARK (Brand Themed - Circular BT Logo)
        ctx.save();
        const r = isMaximized ? 18 : (isGrid ? 10 : 13);
        const margin = 10;
        const centerX = math.cw - margin - r;
        const centerY = math.pt + math.ch - margin - r;

        // Visibility Shadow for Circle
        ctx.shadowColor = 'rgba(0, 0, 0, 0.6)';
        ctx.shadowBlur = 4;
        ctx.shadowOffsetY = 1;

        // Circle Background Fill
        ctx.beginPath();
        ctx.arc(centerX, centerY, r, 0, Math.PI * 2);
        ctx.fillStyle = 'rgba(15, 23, 42, 0.45)';
        ctx.fill();

        // Circle Border
        ctx.strokeStyle = 'rgba(251, 191, 36, 0.65)';
        ctx.lineWidth = Math.max(1.2, r * 0.09);
        ctx.stroke();

        // Text BT Inside Circle
        const fontSize = Math.round(r * 1.05);
        ctx.font = `800 ${fontSize}px "Outfit", "Inter", sans-serif`;
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';

        const bWidth = ctx.measureText('B').width;
        const tWidth = ctx.measureText('T').width;
        const letterSpacing = Math.max(0.5, r * 0.06);
        const totalTextWidth = bWidth + tWidth + letterSpacing;
        const startX = centerX - totalTextWidth / 2;
        const opticalOffset = fontSize * 0.04;

        // Text Shadow
        ctx.shadowColor = 'rgba(0, 0, 0, 0.8)';
        ctx.shadowBlur = 3;
        ctx.shadowOffsetY = 1;

        // "B" Part (White)
        ctx.fillStyle = '#f8fafc';
        ctx.fillText('B', startX, centerY + opticalOffset);

        // "T" Part (Amber Gold)
        ctx.fillStyle = '#fbbf24';
        ctx.fillText('T', startX + bWidth + letterSpacing, centerY + opticalOffset);

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
        const emas = ['ema15', 'ema15_5', 'ema15_15', 'ema15_15_15'];
        emas.forEach((k, idx) => {
            if (!vis[k.toUpperCase()]) return;
            ctx.strokeStyle = COLORS.emas[idx]; ctx.lineWidth = 0.3; ctx.beginPath();
            math.slice.forEach((c, i) => { const x = math.i2x(math.start + i), y = math.p2y(c[k]); if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y); });
            ctx.stroke();
        });

        // ── Supertrend MTF Heikin Ashi (Pine Script port by LonesomeTheBlue - Live Streaming) ──
        if (vis.ST_HA_CURRENT || vis.ST_HA_HTF || vis.ST_HA_FLIPS) {
            const stMap = stMtfHaData ? new Map() : null;
            if (stMtfHaData) {
                stMtfHaData.forEach(p => {
                    if (p.epochMilli) stMap.set(p.epochMilli, p);
                    else if (p.timestamp) stMap.set(new Date(p.timestamp).getTime(), p);
                });
            }

            // 1. Current Timeframe Supertrend HA (Lime for Up, Red for Down, width 2)
            if (vis.ST_HA_CURRENT) {
                ctx.lineWidth = 2;
                let lastTrend = null;
                let segment = [];
                const flushCurrent = (trend, seg) => {
                    if (!seg.length) return;
                    ctx.strokeStyle = trend === 1 ? '#10b981' : '#ef4444';
                    ctx.beginPath();
                    if (seg.length === 1) {
                        ctx.moveTo(seg[0].x - math.bs * 0.4, seg[0].y);
                        ctx.lineTo(seg[0].x + math.bs * 0.4, seg[0].y);
                    } else {
                        seg.forEach((pt, idx) => {
                            if (idx === 0) ctx.moveTo(pt.x, pt.y);
                            else ctx.lineTo(pt.x, pt.y);
                        });
                    }
                    ctx.stroke();
                };

                math.slice.forEach((c, i) => {
                    const pt = stMap ? stMap.get(c.timestamp) : null;
                    const trailingSl = (c.haTrailingSl !== undefined && c.haTrailingSl !== null && c.haTrailingSl > 0)
                        ? c.haTrailingSl : pt?.trailingSl;
                    const trend = (c.haTrend !== undefined && c.haTrend !== null && c.haTrend !== 0)
                        ? c.haTrend : pt?.trend;

                    if (trailingSl && trend) {
                        const x = math.i2x(math.start + i);
                        const y = math.p2y(trailingSl);
                        if (trend !== lastTrend) {
                            flushCurrent(lastTrend, segment);
                            segment = [];
                            lastTrend = trend;
                        }
                        segment.push({ x, y });
                    }
                });
                flushCurrent(lastTrend, segment);
            }

            // 2. Higher Timeframe Supertrend HA (White, width 1)
            if (vis.ST_HA_HTF) {
                ctx.lineWidth = 1;
                let lastHtfTrend = null;
                let htfSegment = [];
                const flushHtf = (trend, seg) => {
                    if (!seg.length) return;
                    ctx.strokeStyle = '#ffffff';
                    ctx.beginPath();
                    if (seg.length === 1) {
                        ctx.moveTo(seg[0].x - math.bs * 0.4, seg[0].y);
                        ctx.lineTo(seg[0].x + math.bs * 0.4, seg[0].y);
                    } else {
                        seg.forEach((pt, idx) => {
                            if (idx === 0) ctx.moveTo(pt.x, pt.y);
                            else ctx.lineTo(pt.x, pt.y);
                        });
                    }
                    ctx.stroke();
                };

                math.slice.forEach((c, i) => {
                    const pt = stMap ? stMap.get(c.timestamp) : null;
                    const htfTrailingSl = (c.htfTrailingSl !== undefined && c.htfTrailingSl !== null && c.htfTrailingSl > 0)
                        ? c.htfTrailingSl : pt?.htfTrailingSl;
                    const htfTrend = (c.htfTrend !== undefined && c.htfTrend !== null && c.htfTrend !== 0)
                        ? c.htfTrend : pt?.htfTrend;

                    if (htfTrailingSl && htfTrend) {
                        const x = math.i2x(math.start + i);
                        const y = math.p2y(htfTrailingSl);
                        if (htfTrend !== lastHtfTrend) {
                            flushHtf(lastHtfTrend, htfSegment);
                            htfSegment = [];
                            lastHtfTrend = htfTrend;
                        }
                        htfSegment.push({ x, y });
                    }
                });
                flushHtf(lastHtfTrend, htfSegment);
            }

            // 3. HTF Trend Flip Circles (Blue circles on bull flip, Red circles on bear flip)
            if (vis.ST_HA_FLIPS) {
                math.slice.forEach((c, i) => {
                    const pt = stMap ? stMap.get(c.timestamp) : null;
                    const htfTrailingSl = (c.htfTrailingSl !== undefined && c.htfTrailingSl !== null && c.htfTrailingSl > 0)
                        ? c.htfTrailingSl : pt?.htfTrailingSl;
                    const htfTrend = (c.htfTrend !== undefined && c.htfTrend !== null && c.htfTrend !== 0)
                        ? c.htfTrend : pt?.htfTrend;

                    const prevC = i > 0 ? math.slice[i - 1] : (math.start > 0 ? (dData ? dData[math.start - 1] : null) : null);
                    const prevPt = prevC && stMap ? stMap.get(prevC.timestamp) : null;
                    const prevHtfTrend = prevC
                        ? ((prevC.htfTrend !== undefined && prevC.htfTrend !== null && prevC.htfTrend !== 0) ? prevC.htfTrend : prevPt?.htfTrend)
                        : null;

                    const isBullFlip = (htfTrend === 1 && prevHtfTrend === -1) || pt?.isHtfBullishFlip;
                    const isBearFlip = (htfTrend === -1 && prevHtfTrend === 1) || pt?.isHtfBearishFlip;

                    if ((isBullFlip || isBearFlip) && htfTrailingSl) {
                        const x = math.i2x(math.start + i);
                        const y = math.p2y(htfTrailingSl);
                        ctx.fillStyle = isBullFlip ? '#3b82f6' : '#ef4444';
                        ctx.beginPath();
                        ctx.arc(x, y, 4.5, 0, Math.PI * 2);
                        ctx.fill();
                        ctx.strokeStyle = '#ffffff';
                        ctx.lineWidth = 1.5;
                        ctx.stroke();
                    }
                });
            }
        }






        // CANDLES
        // Calculate body width strictly bounded to bar spacing (bs)
        // to guarantee zero overlap and razor-sharp separation at any zoom level
        const bs = math.bs;
        let candleWidth = 1;
        if (bs >= 12) {
            candleWidth = Math.floor(bs * 0.7);
            if (candleWidth % 2 === 0) candleWidth -= 1; // Prefer odd width so 1px wick is perfectly centered
        } else if (bs >= 6) {
            candleWidth = Math.max(3, Math.floor(bs * 0.7));
            if (candleWidth % 2 === 0) candleWidth -= 1;
        } else if (bs >= 3.8) {
            candleWidth = Math.max(2, Math.floor(bs - 1.8));
        } else if (bs >= 2.6) {
            candleWidth = Math.max(1, Math.floor(bs - 1));
        } else {
            candleWidth = 1;
        }

        const isThinMode = candleWidth <= 1;

        math.slice.forEach((c, i) => {
            const x = math.i2x(math.start + i);
            const isUp = c.close >= c.open;
            const col = isUp ? COLORS.bull : COLORS.bear;

            const highY = math.p2y(c.high);
            const lowY = math.p2y(c.low);
            const openY = math.p2y(c.open);
            const closeY = math.p2y(c.close);

            const bodyTop = Math.min(openY, closeY);
            const bodyH = Math.max(1, Math.abs(closeY - openY));
            const wickH = Math.max(1, lowY - highY);

            if (isThinMode) {
                // When zoomed out, draw 1px wick with 55% alpha and solid 1px body.
                // High-to-low wick:
                ctx.fillStyle = isUp ? 'rgba(16, 185, 129, 0.55)' : 'rgba(244, 63, 94, 0.55)';
                ctx.fillRect(x, highY, 1, wickH);
                // Open-to-close body (crisp solid 1px column, zero bleeding):
                ctx.fillStyle = col;
                ctx.fillRect(x, bodyTop, 1, bodyH);
            } else {
                const bodyX = Math.round(x - candleWidth / 2);
                // Crisp 1px wick centered on bar
                ctx.fillStyle = col;
                ctx.fillRect(x, highY, 1, wickH);
                // Solid body
                ctx.fillRect(bodyX, bodyTop, candleWidth, bodyH);
            }
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
        const isMobile = math.pr <= 35;
        ctx.fillStyle = COLORS.bg; ctx.fillRect(math.cw, math.pt, math.pr + 1, math.ch);
        ctx.fillStyle = '#e2e8f0'; ctx.font = isMobile ? '8px Inter' : '9px Inter'; ctx.textAlign = 'center';
        for (let i = 0; i < 9; i++) {
            const y = math.pt + (math.ch / 8) * i;
            const price = math.y2p(y);
            if (typeof price === 'number' && !isNaN(price)) {
                const priceText = price.toFixed(isMobile ? 0 : 1);
                ctx.fillText(priceText, math.cw + (math.pr / 2), y + 3.5);
            }
        }

        // HIGHLIGHT LAST PRICE ON Y-AXIS
        if (lastCandle && typeof lastCandle.close === 'number' && !isNaN(lastCandle.close)) {
            const lastPrice = lastCandle.close;
            const y = math.p2y(lastPrice);
            if (!isNaN(y) && y >= math.pt && y <= math.pt + math.ch) {
                const isUp = lastCandle.close >= lastCandle.open;
                ctx.fillStyle = isUp ? COLORS.bull : COLORS.bear;
                ctx.fillRect(math.cw, y - 9, math.pr + 1, 18);
                ctx.fillStyle = '#fff';
                ctx.font = `bold ${isMobile ? '8.5' : '9.5'}px Inter`;
                ctx.textAlign = 'center';
                ctx.fillText(lastPrice.toFixed(symbol.includes('SENSEX') || symbol.includes('NIFTY') || (isMobile && lastPrice >= 100) ? 0 : 2), math.cw + (math.pr / 2), y + 3.5);
            }
        }

        if (isLog) {
            ctx.fillStyle = '#3b82f6'; ctx.font = isMobile ? 'bold 7.5px Inter' : 'bold 8px Inter';
            ctx.fillText('LOG', math.cw + (math.pr / 2), math.pt + 8);
        }

        // X-AXIS — draw in a fully isolated context block so no shadow/state leaks from above
        ctx.save();
        ctx.shadowColor = 'transparent';
        ctx.shadowBlur = 0;
        ctx.shadowOffsetX = 0;
        ctx.shadowOffsetY = 0;
        ctx.globalAlpha = 1;
        ctx.textBaseline = 'alphabetic';
        ctx.textAlign = 'center';

        const xAxisBase = math.pt + math.ch + math.panelCh; // always: top + candle-height + panel-height
        ctx.fillStyle = COLORS.bg;
        ctx.fillRect(0, xAxisBase, math.cw + math.pr, math.pb);

        let lastBadgeRight = -999;
        const barsPerLabel = Math.max(1, Math.floor(math.slice.length / 6));

        math.slice.forEach((c, i) => {
            const date = new Date(c.timestamp);
            const prev = i > 0 ? math.slice[i - 1] : (math.start + i > 0 ? dData[math.start + i - 1] : null);
            const isNewDay = prev && date.getDate() !== new Date(prev.timestamp).getDate();
            const x = math.i2x(math.start + i);

            // Display DATE badge on day boundary OR at the first bar of the visible slice
            if (isNewDay || i === 0) {
                const dayLabel = date.toLocaleDateString('en-GB', { day: '2-digit', month: 'short' });
                ctx.font = 'bold 9px Inter';
                const tw = ctx.measureText(dayLabel).width;
                const bw = tw + 10, bh = 14;

                if (x - bw / 2 > lastBadgeRight + 6 && x + bw / 2 < math.cw) {
                    ctx.fillStyle = '#1e293b';
                    ctx.beginPath();
                    ctx.roundRect(x - bw / 2, xAxisBase + 3, bw, bh, 3);
                    ctx.fill();
                    ctx.strokeStyle = 'rgba(245, 158, 11, 0.8)';
                    ctx.lineWidth = 1;
                    ctx.stroke();

                    ctx.fillStyle = '#fbbf24';
                    ctx.fillText(dayLabel, x, xAxisBase + 13);
                    lastBadgeRight = x + bw / 2;
                }
            } else if (i % barsPerLabel === 0) {
                const timeLabel = date.getHours().toString().padStart(2, '0') + ':' + date.getMinutes().toString().padStart(2, '0');
                ctx.font = '9px Inter';
                const tw = ctx.measureText(timeLabel).width;

                if (x - tw / 2 > lastBadgeRight + 8 && x + tw / 2 < math.cw) {
                    ctx.fillStyle = '#94a3b8';
                    ctx.fillText(timeLabel, x, xAxisBase + 13);
                    lastBadgeRight = x + tw / 2;
                }
            }
        });
        ctx.restore();

        ctx.restore();
    }, [dData, getMath, vis, symbol, config]);

    const renderOverlay = useCallback(() => {
        const canvas = overlayCanvasRef.current; if (!canvas) return;
        if (!overlayCtxRef.current) overlayCtxRef.current = canvas.getContext('2d');
        const ctx = overlayCtxRef.current;
        if (!dData || !dData.length) {
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            return;
        }
        const mouse = mouseRef.current;
        const dpr = window.devicePixelRatio || 1;
        const rect = canvas.getBoundingClientRect();
        const math = getMath(rect.width, rect.height, dData);
        if (!math) {
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            return;
        }

        // Issue 6 fix: skip redraw if mouse hasn't moved and orders haven't changed (unless dragging)
        const orderHash = orderLines.map(l => `${l.id}:${l.price}`).join(',');
        const isDraggingAny = !!draggingOrderRef.current;
        const currentHotkey = activeHotkeyRef.current;
        if (!isDraggingAny && 
            mouse.x === lastMouseRef.current.x &&
            mouse.y === lastMouseRef.current.y &&
            orderHash === lastOrderHashRef.current &&
            currentHotkey === lastHotkeyRef.current) return;
        lastMouseRef.current = { x: mouse.x, y: mouse.y };
        lastOrderHashRef.current = orderHash;
        lastHotkeyRef.current = currentHotkey;

        ctx.clearRect(0, 0, canvas.width, canvas.height); ctx.save(); ctx.scale(dpr, dpr);
        // mouse is already declared above (line 683)

        const totalH = math.panelCh > 0 ? (math.ch + math.panelCh) : math.ch;
        const xAxisBase = math.panelCh > 0 ? (math.panelPt + math.panelCh) : (math.pt + math.ch);

        if (mouse.x > 0 && mouse.x < math.cw && mouse.y > math.pt && mouse.y < math.pt + totalH) {
            const inCandlePanel = mouse.y >= math.pt && mouse.y <= math.pt + math.ch;
            const hotkey = activeHotkeyRef.current;

            if (hotkey && inCandlePanel) {
                const hoverPrice = Math.round(math.y2p(mouse.y) * 20.0) / 20.0;
                let strokeColor = '#10b981';
                let badgeBg = '#059669';
                let badgeText = '';

                if (hotkey === 'BUY') {
                    strokeColor = '#10b981';
                    badgeBg = '#059669';
                    badgeText = `+ BUY LIMIT @ ${hoverPrice.toFixed(2)}`;
                } else if (hotkey === 'SELL') {
                    strokeColor = '#ef4444';
                    badgeBg = '#dc2626';
                    badgeText = `- SELL LIMIT @ ${hoverPrice.toFixed(2)}`;
                } else if (hotkey === 'EXIT') {
                    const exitInfo = getExitTradeInfo();
                    if (exitInfo.hasTrade) {
                        strokeColor = '#f59e0b';
                        badgeBg = '#d97706';
                        badgeText = `EXIT: ${exitInfo.exitTxnType} ${exitInfo.exitQty} @ ${hoverPrice.toFixed(2)}`;
                    } else {
                        strokeColor = '#9ca3af';
                        badgeBg = '#4b5563';
                        badgeText = `NO TRADE TO EXIT @ ${hoverPrice.toFixed(2)}`;
                    }
                }

                ctx.strokeStyle = strokeColor;
                ctx.lineWidth = 1.5;
                ctx.setLineDash([5, 3]);
                ctx.beginPath(); ctx.moveTo(0, mouse.y); ctx.lineTo(math.cw, mouse.y); ctx.stroke();
                ctx.beginPath(); ctx.moveTo(mouse.x, math.pt); ctx.lineTo(mouse.x, math.pt + totalH); ctx.stroke();
                ctx.setLineDash([]);

                // Draw floating badge near mouse pointer
                ctx.font = 'bold 11px Inter, sans-serif';
                const bw = ctx.measureText(badgeText).width + 16;
                const bh = 22;
                let bx = mouse.x + 12;
                if (bx + bw > math.cw - 5) bx = mouse.x - bw - 12;
                let by = mouse.y - 11;
                if (by < math.pt + 4) by = math.pt + 4;
                if (by + bh > math.pt + math.ch) by = math.pt + math.ch - bh;

                ctx.shadowColor = 'rgba(0,0,0,0.6)';
                ctx.shadowBlur = 8;
                ctx.fillStyle = badgeBg;
                ctx.beginPath();
                if (ctx.roundRect) ctx.roundRect(bx, by, bw, bh, 5);
                else ctx.rect(bx, by, bw, bh);
                ctx.fill();
                ctx.shadowBlur = 0;

                ctx.strokeStyle = 'rgba(255,255,255,0.4)';
                ctx.lineWidth = 1;
                ctx.stroke();

                ctx.fillStyle = '#ffffff';
                ctx.textAlign = 'center';
                ctx.fillText(badgeText, bx + bw / 2, by + 15);
            } else {
                ctx.strokeStyle = 'rgba(255,255,255,0.7)'; ctx.lineWidth = 0.8; ctx.setLineDash([4, 4]); ctx.textAlign = 'center';
                ctx.beginPath(); ctx.moveTo(0, mouse.y); ctx.lineTo(math.cw, mouse.y); ctx.stroke();
                ctx.beginPath(); ctx.moveTo(mouse.x, math.pt); ctx.lineTo(mouse.x, math.pt + totalH); ctx.stroke();
                ctx.setLineDash([]);
            }

            // Y-Axis Highlight (only show main price if mouse is in the upper panel)
            let yVal = 0;
            if (mouse.y <= math.pt + math.ch) {
                yVal = math.y2p(mouse.y);
            } else if (showRenko && renkoMathRef.current && mouse.y >= math.panelPt) {
                yVal = renkoMathRef.current.y2p(mouse.y);
            }
            if (yVal) {
                const isMobile = math.pr <= 35;
                let yAxisBg = '#3b82f6';
                if (hotkey && inCandlePanel) {
                    if (hotkey === 'BUY') yAxisBg = '#10b981';
                    else if (hotkey === 'SELL') yAxisBg = '#ef4444';
                    else if (hotkey === 'EXIT') yAxisBg = '#f59e0b';
                }
                ctx.fillStyle = yAxisBg; ctx.fillRect(math.cw, mouse.y - 10, math.pr, 20);
                ctx.fillStyle = '#fff'; ctx.font = `bold ${isMobile ? '8.5' : '9'}px Inter`;
                ctx.fillText(yVal.toFixed(isMobile && yVal >= 100 ? 0 : 2), math.cw + (math.pr / 2), mouse.y + 4);
            }

            // X-Axis Highlight
            // Both candle panel and Renko panel share the same x-axis (dData time series).
            // Always resolve the hovered bar from dData using the candle bar-spacing formula
            // so the timestamp label is identical regardless of which panel the mouse is in.
            let label = "";
            let tx = 0;
            const barIdx = Math.round(mouse.x / math.bs - 0.5) + math.start;
            if (barIdx >= 0 && barIdx < dData.length) {
                ctx.font = 'bold 9px Inter';
                const candle = dData[barIdx];
                const date = new Date(candle.timestamp);
                label = date.toLocaleDateString('en-GB', { day: '2-digit', month: 'short' }) + " " +
                    date.getHours().toString().padStart(2, '0') + ":" + date.getMinutes().toString().padStart(2, '0');
                const tw = ctx.measureText(label).width + 10;
                tx = Math.max(tw / 2, Math.min(math.cw - tw / 2, mouse.x));
            }

            if (label) {
                ctx.font = 'bold 9px Inter';
                const tw = ctx.measureText(label).width + 10;
                ctx.fillStyle = '#3b82f6';
                ctx.beginPath();
                ctx.roundRect(tx - tw / 2, xAxisBase + 3, tw, 14, 3);
                ctx.fill();

                ctx.fillStyle = '#fff';
                ctx.fillText(label, tx, xAxisBase + 13);
            }
        }
        orderLines.forEach(l => {
            const isDragging = draggingOrderRef.current?.id === l.id;
            const effPrice = (isDragging && draggingOrderRef.current.currentPrice !== undefined) ? draggingOrderRef.current.currentPrice : l.price;
            const y = math.p2y(effPrice);
            const isBuy = l.type === 'BUY';
            const color = isBuy ? '#22c55e' : '#ef4444';

            if (y >= math.pt && y <= math.pt + math.ch) {
                ctx.save();
                ctx.strokeStyle = color; ctx.lineWidth = isDragging ? 2 : 1; ctx.setLineDash(l.isPending ? [5, 2] : [5, 5]);
                ctx.beginPath(); ctx.moveTo(0, y); ctx.lineTo(math.cw, y); ctx.stroke(); ctx.setLineDash([]);
                ctx.restore();
            }

            // Issue 7 fix: use cached ref map instead of document.getElementById on each frame
            const domNode = orderDomRefs.current.get(l.id);
            if (domNode) {
                if (y < math.pt || y > math.pt + math.ch) {
                    domNode.style.display = 'none';
                } else {
                    domNode.style.display = 'flex';
                    domNode.style.top = (y - 12) + 'px';
                    const priceInput = domNode.querySelector('.order-price-input');
                    if (priceInput && document.activeElement !== priceInput) {
                        priceInput.value = effPrice.toFixed(2);
                    }
                }
            }
        });
        ctx.restore();
    }, [getMath, dData, orderLines, symbol, showRenko, getExitTradeInfo]);

    // Keep render refs pointing to the latest functions — avoids stale closure bugs in RAFs
    useEffect(() => { renderRef.current = render; }, [render]);
    useEffect(() => { renderOverlayRef.current = renderOverlay; }, [renderOverlay]);

    const renderRenko = useCallback(() => {
        const canvas = renkoCanvasRef.current;
        if (!canvas) return;
        if (!renkoCtxRef.current) renkoCtxRef.current = canvas.getContext('2d');
        const ctx = renkoCtxRef.current;

        if (!showRenko || !dData || !dData.length) {
            ctx.clearRect(0, 0, canvas.width, canvas.height);
            return;
        }

        const dpr = window.devicePixelRatio || 1;
        const W = canvas.width / dpr;
        const H = canvas.height / dpr;
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        ctx.save(); ctx.scale(dpr, dpr);

        // ── Use the SAME math as the main candle chart ──────────────────────
        // This is what makes the panel x-axis sync perfectly (9:15 → 3:30).
        const math = getMath(W, H, dData);
        if (!math || !showRenko || math.panelCh <= 0) { ctx.restore(); return; }

        const PT  = math.panelPt + 4;
        const PR  = math.pr;
        const PB  = math.pb;
        const plotW = math.cw;
        const plotH = Math.max(1, math.panelCh - 4);

        const totalDData = dData.length;
        if (totalDData === 0) { ctx.restore(); return; }

        // ── Deterministic Non-Repainting Renko Guppy Calculation (with zero-alloc cache) ──
        let computed = null;
        const lastCandle = dData[totalDData - 1];
        const firstCandle = dData[0];
        const cache = renkoEmaCacheRef.current;

        const isOpt = symbol && (symbol.endsWith('CE') || symbol.endsWith('PE') || symbol.endsWith('CALL') || symbol.endsWith('PUT') || symbol.includes('CE') || symbol.includes('PE'));
        const b1 = isOpt ? 15 : (config?.renko1BlockSize || 15);
        const b2 = config?.renko2BlockSize || 50;
        const b3 = config?.renko3BlockSize || 100;

        if (
            cache &&
            cache.computed &&
            cache.interval === interval &&
            cache.symbol === symbol &&
            cache.boxSize1 === b1 &&
            cache.boxSize2 === b2 &&
            cache.boxSize3 === b3 &&
            cache.lastDDataLen === totalDData &&
            cache.lastCandleClose === (lastCandle ? lastCandle.close : 0) &&
            cache.firstCandleTs === (firstCandle ? firstCandle.timestamp : 0) &&
            cache.lastCandleTs === (lastCandle ? lastCandle.timestamp : 0)
        ) {
            computed = cache.computed;
        } else {
            computed = computeDeterministicRenkoGuppy(dData, {
                boxSize1: b1,
                boxSize2: b2,
                boxSize3: b3
            });

            renkoEmaCacheRef.current = {
                interval,
                symbol,
                boxSize1: b1,
                boxSize2: b2,
                boxSize3: b3,
                lastDDataLen: totalDData,
                lastCandleClose: lastCandle ? lastCandle.close : 0,
                firstCandleTs: firstCandle ? firstCandle.timestamp : 0,
                lastCandleTs: lastCandle ? lastCandle.timestamp : 0,
                computed
            };
        }

        if (!computed) { ctx.restore(); return; }

        const { fastEmas, slowEmas, renko2Close, renko3Close, closeMa, boxSize1, boxSize2, boxSize3, totalBricks1 } = computed;
        const emaData = [...fastEmas, ...slowEmas, closeMa];
        const renko2Arr = renko2Close;
        const renko3Arr = renko3Close;

        // ── Step 3: Y-scale from visible slice's EMA values ──────────────────
        const { start, slice } = math;
        let lo = Infinity, hi = -Infinity;
        const keysToScale = vis.RENKO_CLOSE ? emaData.length : emaData.length - 1; 
        for (let ei = 0; ei < keysToScale; ei++) {
            for (let i = start; i < start + slice.length; i++) {
                const v = emaData[ei][i];
                if (!isNaN(v)) { if (v < lo) lo = v; if (v > hi) hi = v; }
            }
        }
        if (vis.RENKO2 !== false && renko2Arr) {
            for (let i = start; i < start + slice.length; i++) {
                const v = renko2Arr[i];
                if (!isNaN(v)) { if (v < lo) lo = v; if (v > hi) hi = v; }
            }
        }
        if (vis.RENKO3 !== false && renko3Arr) {
            for (let i = start; i < start + slice.length; i++) {
                const v = renko3Arr[i];
                if (!isNaN(v)) { if (v < lo) lo = v; if (v > hi) hi = v; }
            }
        }

        if (!isFinite(lo) || !isFinite(hi) || hi <= lo) { ctx.restore(); return; }
        const pad = (hi - lo) * 0.12;
        lo -= pad; hi += pad;

        if (renkoYRange && typeof renkoYRange.low === 'number' && typeof renkoYRange.high === 'number' && renkoYRange.high > renkoYRange.low) {
            lo = renkoYRange.low;
            hi = renkoYRange.high;
        }

        const p2y    = price => PT + plotH - ((price - lo) / (hi - lo)) * plotH;
        const y2p_rk = y    => lo + ((PT + plotH - y) / plotH) * (hi - lo);

        // Expose for crosshair and dragging (map pixel to time index)
        renkoMathRef.current = {
            cw: plotW, ch: plotH, pt: PT, pb: PB, pr: PR,
            y2p: y2p_rk, p2y, bs: math.bs, start,
            cx_of: i => math.i2x ? math.i2x(i) : 0,
            panelPt: PT,
            low: lo, high: hi
        };

        // ── Background — fill ONLY the panel content area, NOT the x-axis strip ──
        ctx.fillStyle = COLORS.bg;
        ctx.fillRect(0, PT, plotW, plotH);
        ctx.strokeStyle = 'rgba(255,255,255,0.09)';
        ctx.lineWidth = 1;
        ctx.beginPath(); ctx.moveTo(0, PT - 1); ctx.lineTo(plotW, PT - 1); ctx.stroke();

        // ── Draw helper — one continuous smooth line over the visible slice ──────────
        const drawEmaLine = (arr, color, width, isDashed = false) => {
            ctx.save();
            ctx.strokeStyle = color;
            ctx.lineWidth   = width;
            ctx.lineJoin    = 'round';
            ctx.lineCap     = 'round';
            if (isDashed) ctx.setLineDash([4, 4]);
            ctx.beginPath();
            let started = false;
            for (let i = 0; i < slice.length; i++) {
                const gi = start + i;
                const v  = arr[gi];
                if (isNaN(v)) { started = false; continue; }
                const x = math.i2x(gi);
                const y = p2y(v);
                if (!started) { ctx.moveTo(x, y); started = true; } else ctx.lineTo(x, y);
            }
            ctx.stroke();
            ctx.restore();
        };

        // ── Step 5: Slow Guppy (Blue Ribbons — periods 30, 35, 40, 45, 50) ──────────────
        for (let si = 5; si < 10; si++) {
            const isLead = si === 9;
            drawEmaLine(emaData[si], isLead ? '#008ae6' : 'rgba(0, 138, 230, 0.60)', isLead ? 1.6 : 1.0);
        }

        // ── Step 6: Fast Guppy (Aqua Ribbons — periods 3, 5, 8, 10, 12) ────────────────
        for (let fi = 0; fi < 5; fi++) {
            const isLead = fi === 0;
            drawEmaLine(emaData[fi], isLead ? '#00f0ff' : 'rgba(0, 240, 255, 0.65)', isLead ? 1.8 : 1.0);
        }

        // ── Step 7: Price Line (Red thick line) ──────────────────────────
        if (vis.RENKO_CLOSE) {
            drawEmaLine(emaData[10], '#f43f5e', 2.0);
        }

        // ── Step 8: Renko 2 (White line) ──────────────────────────────────
        if (vis.RENKO2 !== false && renko2Arr) {
            drawEmaLine(renko2Arr, 'rgba(255, 255, 255, 0.85)', 1.2, true);
        }

        // ── Step 9: Renko 3 (Blue line) ───────────────────────────────────
        if (vis.RENKO3 !== false && renko3Arr) {
            drawEmaLine(renko3Arr, '#3b82f6', 1.2, true);
        }

        // ── Step 10: Y-axis label strip ──────────────────────────────────────
        ctx.fillStyle = COLORS.bg;
        ctx.fillRect(plotW, PT, PR, plotH + PB);
        ctx.fillStyle   = '#94a3b8';
        ctx.font        = '8.5px Inter';
        ctx.textAlign   = 'center';
        const renkoRange = hi - lo;
        const renkoDecimals = renkoRange < 5 ? 2 : (renkoRange < 30 ? 1 : 0);
        for (let i = 0; i <= 4; i++) {
            const price = lo + renkoRange * (1 - i / 4);
            const gy    = PT + (plotH / 4) * i;
            ctx.fillText(price.toFixed(renkoDecimals), plotW + PR / 2, gy + 3);
        }

        // ── Step 11: Header label ─────────────────────────────────────────────
        ctx.fillStyle  = 'rgba(148,163,184,0.75)';
        ctx.font       = '8px Inter';
        ctx.fillText(`Renko Guppy [Traditional]  Box:${boxSize1} (R2:${boxSize2}, R3:${boxSize3})  Bricks:${totalBricks1}`, 8, PT + 12);

        ctx.restore();

    }, [dData, isReplayMode, replayIndex, vis, getMath, showRenko, interval, symbol, config, renkoYRange]);

    useEffect(() => { renkoRenderRef.current = renderRenko; }, [renderRenko]);

    // Cancel any pending RAFs on unmount to prevent errors on detached canvases
    useEffect(() => {
        return () => {
            if (pendingRafRef.current) cancelAnimationFrame(pendingRafRef.current);
            if (overlayRafRef.current) cancelAnimationFrame(overlayRafRef.current);
            if (wheelRafRef.current) cancelAnimationFrame(wheelRafRef.current);
        };
    }, []);

    const lastResetSignal = useRef(0);

    const onReset = useCallback(() => {
        setYRange(null);
        setRenkoYRange(null);
        renkoEmaCacheRef.current = null;
        scrollOffset.current = 0;
        renkoScrollOffset.current = 0;
        isFollowing.current = true;
        setVisibleBars(160);
        setRenkoVisibleBricks(300);
    }, []);

    const showToast = (type, msg) => { setToast({ type, msg }); setTimeout(() => setToast(null), 3000); };


    // Seed chart from WebSocket snapshot delivery (zero REST calls in scanner mode)
    const snapshotCallback = useCallback((data) => {
        if (!data || data.symbol !== symbol || data.interval !== interval) return;
        if (!data.candles || data.candles.length === 0) {
            return;
        }
        const current = liveDataRef.current;
        if (current && current.length > 0 && current.length >= data.candles.length) {
            const currentLast = current[current.length - 1]?.timestamp || 0;
            const snapLast = new Date(data.candles[data.candles.length - 1]?.timestamp).getTime();
            if (currentLast >= snapLast) return;
        }
        const normalized = data.candles.map(c => {
            const base = { ...c, timestamp: new Date(c.timestamp).getTime() };
            if (c.indicators) Object.assign(base, c.indicators);
            return base;
        });
        liveDataRef.current = normalized;
        replayDataRef.current = normalized;
        renkoEmaCacheRef.current = null;
        setData(normalized); // trigger one re-render to update dData memo
        setLoading(false);
    }, [symbol, interval]);

    // Fetch reliable history on symbol/interval change with seamless transitions
    useEffect(() => {
        setLoading(true);
        setStMtfHaData(null);
        renkoEmaCacheRef.current = null;
        renkoMathRef.current = null;
        scrollOffset.current = 0;
        renkoScrollOffset.current = 0;
        setYRange(null);
        setRenkoYRange(null);
        liveDataRef.current = [];
        replayDataRef.current = [];
        setData([]);

        if (preloadedData && preloadedData.length > 0) {
            const normalized = preloadedData.map(c => {
                const base = { ...c, timestamp: new Date(c.timestamp).getTime() };
                if (c.indicators) Object.assign(base, c.indicators);
                return base;
            });
            liveDataRef.current = normalized;
            replayDataRef.current = normalized;
            renkoEmaCacheRef.current = null;
            setData(normalized);
            setLoading(false);
            return;
        }

        const controller = new AbortController();
        const fetchLimit = isMaximized ? 10000 : 1000;
        let isSubscribed = true;

        // Utilize Redis cache and HTTP caching (no cache-busting _t param)
        axios.get(`${API_BASE}/api/history/${symbol}/${interval}?limit=${fetchLimit}`, { signal: controller.signal })
            .then(res => {
                if (!isSubscribed) return;
                if (res.data && Array.isArray(res.data) && res.data.length > 0) {
                    const normalized = res.data.map(c => {
                        const base = { ...c, timestamp: new Date(c.timestamp).getTime() };
                        if (c.indicators) Object.assign(base, c.indicators);
                        return base;
                    });
                    liveDataRef.current = normalized;
                    replayDataRef.current = normalized;
                    renkoEmaCacheRef.current = null;
                    setData(normalized);
                } else {
                    console.warn('[Chart] No candles returned for', symbol, interval);
                }
                setLoading(false);
            })
            .catch(err => {
                if (!axios.isCancel(err) && isSubscribed) {
                    console.error('[Chart] History fetch failed for', symbol, interval, err);
                    setLoading(false);
                }
            });

        return () => {
            isSubscribed = false;
            controller.abort();
        };
    }, [symbol, interval, preloadedData, isMaximized]);

    useEffect(() => {
        setYRange(null);
        setRenkoYRange(null);
        renkoEmaCacheRef.current = null;
    }, [symbol, interval]);
    useEffect(() => {
        if (globalResetTrigger > 0 && globalResetTrigger !== lastResetSignal.current) {
            lastResetSignal.current = globalResetTrigger;
            onReset();
        }
    }, [globalResetTrigger, onReset]);

    useLiveFeed(symbol, interval, useCallback(u => {
        if (isReplayMode) return;
        if (u.symbol && u.symbol !== symbol) return;
        if (u.interval && u.interval !== interval) return;

        // Mutate the ref directly — NO setState, NO array copy, NO React re-render
        const arr = liveDataRef.current;
        if (!arr || !arr.length) return; // Do not append ticks before initial candles are populated

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
                renderOverlayRef.current?.();
                renkoRenderRef.current?.();
            });
        }
    }, [isReplayMode, symbol, interval]), snapshotCallback);
    useEffect(() => { let t; if (isReplaying) t = setInterval(() => setReplayIndex(p => p < replayDataRef.current.length - 1 ? p + 1 : (setIsReplaying(false), p)), 1000); return () => clearInterval(t); }, [isReplaying]);
    useEffect(() => {
        render();
        renderOverlay();
        renderRenko();
    }, [dData, visibleBars, renkoVisibleBricks, isLog, yRange, renkoYRange, orderLines, render, renderOverlay, renderRenko]);

    useEffect(() => {
        const r = () => {
            if (!containerRef.current) return;
            const { offsetWidth: w, offsetHeight: h } = containerRef.current, dpr = window.devicePixelRatio || 1;
            [mainCanvasRef.current, overlayCanvasRef.current].forEach(c => { if (c) { c.width = w * dpr; c.height = h * dpr; c.style.width = w + 'px'; c.style.height = h + 'px'; } });
            const rc = renkoCanvasRef.current;
            if (rc) { rc.width = w * dpr; rc.height = h * dpr; rc.style.width = w + 'px'; rc.style.height = h + 'px'; }
            // Invalidate cached contexts on resize — canvas resize clears the context state
            mainCtxRef.current = null;
            overlayCtxRef.current = null;
            renkoCtxRef.current = null;
            render(); renderOverlay(); renderRenko();
        };
        const o = new ResizeObserver(r); o.observe(containerRef.current); return () => o.disconnect();
    }, [render, renderOverlay, renderRenko]);

    // Cleanup pending animation frames on unmount
    useEffect(() => {
        return () => {
            if (pendingRafRef.current) cancelAnimationFrame(pendingRafRef.current);
            if (overlayRafRef.current) cancelAnimationFrame(overlayRafRef.current);
            if (wheelRafRef.current) cancelAnimationFrame(wheelRafRef.current);
        };
    }, []);

    const handleMouseDown = (e) => {
        activePointers.current.set(e.pointerId, e);
        if (activePointers.current.size === 2) {
            const pts = Array.from(activePointers.current.values());
            pinchStartDist.current = Math.hypot(pts[0].clientX - pts[1].clientX, pts[0].clientY - pts[1].clientY);
            pinchStartBars.current = visibleBars;
            dragStart.current = null;
            return;
        }

        if (!e.isPrimary) return;
        isFollowing.current = false;
        const rect = containerRef.current.getBoundingClientRect();
        const math = getMath(rect.width, rect.height, dData);
        if (!math) return; // Guard during lazy-load
        const x = e.clientX - rect.left, y = e.clientY - rect.top;

        // Ignore clicks on header (26px height) to allow scrolling and avoid accidental drags
        if (y < 26) return;

        // Check if a hotkey order (B = Buy, S = Sell, E = Exit) or Ctrl/Alt was triggered
        let hotkey = activeHotkeyRef.current;
        if (!hotkey) {
            if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey) hotkey = 'BUY';
            else if (e.altKey && !e.ctrlKey) hotkey = 'EXIT';
        }
        const isChartArea = x > 0 && x < (rect.width - math.pr) && y >= math.pt && y <= (math.pt + math.ch);
        if (hotkey && isChartArea && (!e.pointerType || e.pointerType === 'mouse')) {
            e.preventDefault();
            e.stopPropagation();
            handleChartClickOrder(hotkey, y, math);
            return;
        }

        const hit = orderLines.find(l => Math.abs(math.p2y(l.price) - y) < 10);
        if (hit) {
            e.target.setPointerCapture(e.pointerId);
            draggingOrderRef.current = { id: hit.id, originalPrice: hit.price };
            return;
        }

        const isYAxis = (e.clientX - rect.left) > (rect.width - math.pr);
        const isXAxis = (e.clientY - rect.top) > (rect.height - math.pb);
        const isInRenko = showRenko && math.panelCh > 0 && y >= math.panelPt && y < (math.panelPt + math.panelCh);
        const isRenkoYAxis = isYAxis && isInRenko;
        const isMainYAxis = isYAxis && !isInRenko && (y >= math.pt && y < math.panelPt);

        // Capture pointer if on axis to ensure we get events even if user moves out
        if (isYAxis || isXAxis) {
            e.target.setPointerCapture(e.pointerId);
        }

        let startRenkoLow = 0;
        let startRenkoHigh = 100;
        if (renkoMathRef.current && typeof renkoMathRef.current.low === 'number' && typeof renkoMathRef.current.high === 'number' && renkoMathRef.current.high > renkoMathRef.current.low) {
            startRenkoLow = renkoMathRef.current.low;
            startRenkoHigh = renkoMathRef.current.high;
        } else if (renkoYRange && renkoYRange.high > renkoYRange.low) {
            startRenkoLow = renkoYRange.low;
            startRenkoHigh = renkoYRange.high;
        }

        if (e.pointerType === 'touch' && (crosshairActiveRef.current || isCrosshairForced)) {
            dragStart.current = {
                x: e.clientX,
                y: e.clientY,
                isCrosshair: true,
                hasMoved: false
            };
            const r = containerRef.current.getBoundingClientRect();
            mouseRef.current = { x: e.clientX - r.left, y: e.clientY - r.top };
            crosshairActiveRef.current = true;
            renderOverlayRef.current?.();
            return;
        }

        dragStart.current = {
            x: e.clientX,
            y: e.clientY,
            scroll: scrollOffset.current,
            mainYRangeStart: { low: math.low, high: math.high },
            renkoYRangeStart: { low: startRenkoLow, high: startRenkoHigh },
            yRangeStart: isInRenko ? { low: startRenkoLow, high: startRenkoHigh } : { low: math.low, high: math.high },
            visibleBarsStart: visibleBars,
            isYAxis,
            isRenkoYAxis,
            isMainYAxis,
            isXAxis,
            isInRenko
        };

        if (e.pointerType === 'touch') {
            crosshairActiveRef.current = false;
        } else {
            crosshairActiveRef.current = true;
        }
    };

    const handleMouseUp = (e) => {
        if (longPressTimerRef.current) {
            clearTimeout(longPressTimerRef.current);
            longPressTimerRef.current = null;
        }
        if (e.pointerType === 'touch') {
            if (crosshairActiveRef.current && dragStart.current && dragStart.current.isCrosshair && !dragStart.current.hasMoved) {
                if (!isCrosshairForced) {
                    crosshairActiveRef.current = false;
                    mouseRef.current = { x: -1, y: -1 };
                    renderOverlayRef.current?.();
                }
            }
        }

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
                const finalPrice = dragInfo.currentPrice !== undefined ? dragInfo.currentPrice : line.price;
                if (dragInfo.currentPrice !== undefined && finalPrice !== line.price) {
                    line.price = finalPrice;
                    updateOrderLines(nx => nx.map(l => l.id === dragInfo.id ? { ...l, price: finalPrice } : l));
                }

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
        } catch (err) { 
            const errMsg = err.response?.data?.error || err.message;
            showToast('error', `Cancel failed: ${errMsg}`); 
            if (errMsg.includes('already filled')) {
                updateOrderLines(nx => nx.filter(l => l.id !== id));
            }
        }
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
                bcRef.current?.postMessage({ type: 'SYNC_ZOOM', count: newBars });
            }
            return;
        }

        const rect = containerRef.current.getBoundingClientRect();
        const x = e.clientX - rect.left, y = e.clientY - rect.top;
        if (e.pointerType === 'touch') {
            if (!crosshairActiveRef.current) {
                if (dragStart.current && (Math.abs(e.clientX - dragStart.current.x) > 5 || Math.abs(e.clientY - dragStart.current.y) > 5)) {
                    if (longPressTimerRef.current) {
                        clearTimeout(longPressTimerRef.current);
                        longPressTimerRef.current = null;
                    }
                }
                mouseRef.current = { x: -1, y: -1 };
            } else {
                mouseRef.current = { x, y };
                if (dragStart.current && dragStart.current.isCrosshair) {
                    if (Math.abs(e.clientX - dragStart.current.x) > 5 || Math.abs(e.clientY - dragStart.current.y) > 5) {
                        dragStart.current.hasMoved = true;
                    }
                }
            }
        } else {
            mouseRef.current = { x, y };
        }
        const math = getMath(rect.width, rect.height, dData);
        if (!math) return; // Guard against still-loading or empty data

        if (x > 0 && x < math.cw && y > math.pt && y < math.pt + math.ch) {
            // Mouse is inside chart area
        }

        if (draggingOrderRef.current) {
            const newPrice = math.y2p(y);
            draggingOrderRef.current.currentPrice = newPrice;
            
            const domNode = document.getElementById(`order-dom-${draggingOrderRef.current.id}`);
            if (domNode) {
                domNode.style.top = `${y - 12}px`;
                const priceInput = domNode.querySelector('.order-price-input');
                if (priceInput) priceInput.value = newPrice.toFixed(2);
            }
            if (!overlayRafRef.current) overlayRafRef.current = requestAnimationFrame(() => { overlayRafRef.current = null; renderOverlayRef.current?.(); });
        }

        if (dragStart.current && activePointers.current.size === 1 && !dragStart.current.isCrosshair) {
            const dx = e.clientX - dragStart.current.x, dy = e.clientY - dragStart.current.y;
            if (dragStart.current.isRenkoYAxis) {
                const base = dragStart.current.renkoYRangeStart;
                if (base && base.high > base.low) {
                    const scale = Math.exp(dy * 0.005);
                    const mid = (base.high + base.low) / 2;
                    const newRange = ((base.high - base.low) / 2) * scale;
                    if (newRange > 0.05) {
                        setRenkoYRange({ low: mid - newRange, high: mid + newRange });
                    }
                }
            } else if (dragStart.current.isMainYAxis || (dragStart.current.isYAxis && !dragStart.current.isInRenko)) {
                const base = dragStart.current.mainYRangeStart || dragStart.current.yRangeStart;
                if (base && base.high > base.low) {
                    const scale = Math.exp(dy * 0.005);
                    const mid = (base.high + base.low) / 2;
                    const newRange = ((base.high - base.low) / 2) * scale;
                    if (newRange > 0.05) {
                        setYRange({ low: mid - newRange, high: mid + newRange });
                    }
                }
            } else if (dragStart.current.isXAxis) {
                const scale = Math.exp(-dx * 0.003);
                setVisibleBars(Math.max(20, Math.min(800, Math.round(dragStart.current.visibleBarsStart * scale))));
            } else {
                scrollOffset.current = dragStart.current.scroll + Math.round(dx / (rect.width / visibleBars));
                if (dy !== 0) {
                    if (dragStart.current.isInRenko) {
                        const base = dragStart.current.renkoYRangeStart;
                        if (base && base.high > base.low) {
                            const plotH = (renkoMathRef.current && renkoMathRef.current.ch) || math.panelCh || 100;
                            const pShift = (dy / plotH) * (base.high - base.low);
                            setRenkoYRange({ low: base.low + pShift, high: base.high + pShift });
                        }
                    } else {
                        const base = dragStart.current.mainYRangeStart || dragStart.current.yRangeStart;
                        if (base && base.high > base.low) {
                            const pShift = (dy / math.ch) * (base.high - base.low);
                            setYRange({ low: base.low + pShift, high: base.high + pShift });
                        }
                    }
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
            } else if (y > (rect.height - math.pb)) {
                containerRef.current.style.cursor = 'ew-resize';
            } else {
                containerRef.current.style.cursor = 'crosshair';
            }
        }

        // Throttle overlay redraws — at most one pending RAF frame at a time
        if (!overlayRafRef.current) {
            overlayRafRef.current = requestAnimationFrame(() => {
                overlayRafRef.current = null;
                if (dragStart.current) {
                    renderRef.current?.();
                    renkoRenderRef.current?.();
                }
                renderOverlayRef.current?.();
            });
        }
    };

    const handleDoubleClick = (e) => {
        const rect = containerRef.current?.getBoundingClientRect();
        if (!rect) return;
        const math = getMath(rect.width, rect.height, dData);
        if (!math) return;
        const x = e.clientX - rect.left, y = e.clientY - rect.top;
        const isYAxis = x > (rect.width - math.pr);
        const isInRenko = showRenko && math.panelCh > 0 && y >= math.panelPt && y < (math.panelPt + math.panelCh);

        if (isYAxis) {
            if (isInRenko) {
                setRenkoYRange(null);
            } else {
                setYRange(null);
            }
        } else {
            setYRange(null);
            setRenkoYRange(null);
        }
    };

    const placeDirectLimitOrder = async (txnType, price, specifiedQty = null, isExit = false) => {
        const roundedPrice = Math.round(price * 20.0) / 20.0;
        let qty = specifiedQty;

        if (!qty) {
            try {
                const mq = await axios.get(`${API_BASE}/api/account/max-qty?symbol=${symbol}&price=${roundedPrice}&leverage=5`);
                qty = mq.data?.maxQty || 1;
            } catch (err) {
                qty = 1;
            }
        }

        const tempId = Date.now();
        // Optimistically add to order lines
        const optimisticLine = { id: tempId, price: roundedPrice, type: txnType, qty, isPending: true, isExit };
        updateOrderLines(nx => [...nx, optimisticLine]);

        try {
            const res = await axios.post(`${API_BASE}/api/order/place`, {
                symbol,
                txnType,
                qty,
                price: roundedPrice,
                exchange: symbol.includes('SENSEX') ? 'BSE' : 'NSE',
                product: 'MIS'
            });

            const realOrderId = res.data.orderId;
            updateOrderLines(nx => nx.map(ol => ol.id === tempId ? { ...ol, id: realOrderId, price: roundedPrice, isPending: false } : ol));

            showToast('success', `${isExit ? 'Exit' : txnType} Limit Placed: ${qty} @ ${roundedPrice.toFixed(2)} (${formatSymbol(symbol)})`);
            fetchSymbolPosition();
        } catch (err) {
            updateOrderLines(nx => nx.filter(ol => ol.id !== tempId));
            const msg = err.response?.data?.error || err.message;
            showToast('error', `Order failed: ${msg}`);
        }
    };

    const handleChartClickOrder = async (hotkey, y, math) => {
        const rawPrice = math.y2p(y);
        const price = Math.round(rawPrice * 20.0) / 20.0;
        if (price <= 0) return;

        if (hotkey === 'BUY') {
            await placeDirectLimitOrder('BUY', price);
        } else if (hotkey === 'SELL') {
            await placeDirectLimitOrder('SELL', price);
        } else if (hotkey === 'EXIT') {
            const exitInfo = getExitTradeInfo();
            if (!exitInfo.hasTrade) {
                showToast('error', `No active trade to exit on ${formatSymbol(symbol)}. Use B to Buy or S to Sell.`);
                return;
            }
            await placeDirectLimitOrder(exitInfo.exitTxnType, price, exitInfo.exitQty, true);
        }
    };

    const handleBuySell = async (type) => {
        const rect = containerRef.current?.getBoundingClientRect();
        if (!rect) return;
        const math = getMath(rect.width, rect.height, dData);
        if (!math) return;
        const midY = math.pt + (math.ch / 2);
        const price = Math.round(math.y2p(midY) * 20.0) / 20.0;
        await placeDirectLimitOrder(type, price);
    };

    const handleExitTrade = async () => {
        const rect = containerRef.current?.getBoundingClientRect();
        if (!rect) return;
        const math = getMath(rect.width, rect.height, dData);
        if (!math) return;
        const midY = math.pt + (math.ch / 2);
        const price = Math.round(math.y2p(midY) * 20.0) / 20.0;
        const exitInfo = getExitTradeInfo();
        if (!exitInfo.hasTrade) {
            showToast('error', `No active trade to exit on ${formatSymbol(symbol)}. Use BL to Buy or SL to Sell.`);
            return;
        }
        await placeDirectLimitOrder(exitInfo.exitTxnType, price, exitInfo.exitQty, true);
    };


    return (
        <div ref={containerRef} className={`trading-canvas-wrapper ${isHighlighted ? 'glow-highlight' : ''}`} style={{ width: '100%', height: '100%', position: 'relative', background: COLORS.bg, overflow: 'hidden', borderRadius: 'inherit', touchAction: isMaximized ? 'none' : 'pan-y' }}
            onWheel={e => {
                e.preventDefault();
                if (wheelRafRef.current) return;
                const deltaY = e.deltaY;
                wheelRafRef.current = requestAnimationFrame(() => {
                    wheelRafRef.current = null;
                    setVisibleBars(p => {
                        const next = Math.max(20, Math.min(800, p + (deltaY > 0 ? 8 : -8)));
                        bcRef.current?.postMessage({ type: 'SYNC_ZOOM', count: next });
                        return next;
                    });
                });
            }}
            onPointerDown={handleMouseDown}
            onPointerMove={(e) => { isHoveredRef.current = true; handleMouseMove(e); }}
            onPointerUp={handleMouseUp}
            onPointerEnter={() => { isHoveredRef.current = true; }}
            onDoubleClick={handleDoubleClick}
            onPointerCancel={(e) => { isHoveredRef.current = false; activeHotkeyRef.current = null; if (longPressTimerRef.current) clearTimeout(longPressTimerRef.current); activePointers.current.clear(); pinchStartDist.current = null; dragStart.current = null; draggingOrderRef.current = null; if (e.pointerType !== 'touch' || !crosshairActiveRef.current) { crosshairActiveRef.current = false; mouseRef.current = { x: -1, y: -1 }; renderOverlay(); } }}
            onPointerLeave={(e) => { isHoveredRef.current = false; activeHotkeyRef.current = null; activePointers.current.delete(e.pointerId); if (activePointers.current.size === 0) { dragStart.current = null; draggingOrderRef.current = null; if (e.pointerType !== 'touch' || !crosshairActiveRef.current) { mouseRef.current = { x: -1, y: -1 }; renderOverlay(); } } }}>

            {/* Y-Axis Touch Capture Zone (Allows scaling without triggering page scroll) */}
            <div style={{ position: 'absolute', top: '26px', right: 0, bottom: '20px', width: `${(typeof window !== 'undefined' && window.innerWidth <= 768) ? 32 : 38}px`, touchAction: 'none', zIndex: 100 }} />

            <div style={{ position: 'absolute', top: 0, left: 0, right: 0, height: '26px', zIndex: 10, display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: 'rgba(15,23,42,0.95)', padding: '0 8px', borderBottom: '1px solid rgba(255,255,255,0.1)', backdropFilter: 'blur(8px)', touchAction: 'pan-y' }} onPointerDown={e => e.stopPropagation()}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                    <span style={{ color: '#fbbf24', fontSize: '11px', fontWeight: 'bold' }}>{formatSymbol(symbol)}</span>
                    <CountdownTimer interval={interval} isReplayMode={isReplayMode} />
                    <div style={{ display: 'flex', gap: '4px', marginLeft: '8px' }}>
                        {(isMaximized ? ['1min', '3min', '5min'] : []).map(tf => (
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
                        <button
                            onClick={() => setShowRenko(!showRenko)}
                            className={`chart-tool-btn btn-renko ${showRenko ? 'active' : ''}`}
                            data-tooltip="Toggle Renko Guppy"
                            style={{ background: showRenko ? 'rgba(0, 138, 230, 0.4)' : '' }}
                        >
                            RG
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
                        <button onClick={() => handleBuySell('BUY')} className="chart-tool-btn btn-buy" data-tooltip="Buy Limit (Hold 'B' + Click on Chart)">BL</button>
                        <button onClick={() => handleBuySell('SELL')} className="chart-tool-btn btn-sell" data-tooltip="Sell Limit (Hold 'S' + Click on Chart)">SL</button>
                        <button onClick={handleExitTrade} className="chart-tool-btn btn-exit" data-tooltip="Exit Limit (Hold 'E' + Click on Chart)">EL</button>
                        {!hideMaximize && (
                            <button
                                onClick={() => onMaximize(interval, symbol)}
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
            <canvas ref={mainCanvasRef} style={{ position: 'absolute', top: 0, left: 0, borderRadius: 'inherit', display: 'block' }} />
            <canvas ref={renkoCanvasRef} style={{ position: 'absolute', top: 0, left: 0, borderRadius: 'inherit', display: showRenko ? 'block' : 'none' }} />
            <canvas ref={overlayCanvasRef} style={{ position: 'absolute', top: 0, left: 0, pointerEvents: 'none', borderRadius: 'inherit' }} />
            {loading && !dData.length && (
                <div style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', background: 'rgba(2, 6, 23, 0.65)', backdropFilter: 'blur(3px)', zIndex: 12, pointerEvents: 'none' }}>
                    <div className="loader-mini" style={{ width: '22px', height: '22px', border: '2.5px solid #3b82f6', borderTopColor: 'transparent', borderRadius: '50%', animation: 'spin 0.8s linear infinite' }} />
                    <span style={{ marginTop: '8px', fontSize: '11px', color: '#94a3b8', fontWeight: 600, letterSpacing: '0.4px' }}>Loading {interval.replace('min', 'M').toUpperCase()}...</span>
                </div>
            )}
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
                    <div key={l.id} ref={node => {
                            // Issue 7 fix: register/unregister in the ref map for O(1) canvas lookup
                            if (node) orderDomRefs.current.set(l.id, node);
                            else orderDomRefs.current.delete(l.id);
                        }}
                        id={`order-dom-${l.id}`}
                        onPointerDown={(e) => {
                            e.stopPropagation();
                            e.target.setPointerCapture(e.pointerId);
                            draggingOrderRef.current = { id: l.id, originalPrice: l.price };
                        }}
                        style={{
                        position: 'absolute',
                        display: 'none',
                        alignItems: 'center',
                        gap: '4px',
                        zIndex: 100,
                        left: '5px',
                        top: 0,
                        touchAction: 'none'
                    }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '6px', background: color, padding: '3px 8px', borderRadius: '4px', boxShadow: '0 2px 4px rgba(0,0,0,0.5)', height: '28px' }}>
                            <span style={{ fontSize: '11px', fontWeight: 'bold', color: '#fff' }}>{l.isExit ? `EXIT ${l.type}` : l.type}</span>
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
                                style={{ width: '50px', height: '20px', background: 'rgba(0,0,0,0.3)', border: '1px solid rgba(255,255,255,0.2)', color: '#fff', fontSize: '12px', outline: 'none', fontWeight: 'bold', textAlign: 'center', borderRadius: '4px' }}
                            />
                        </div>

                        <div style={{ display: 'flex', alignItems: 'center', background: '#0f172a', padding: '3px 8px', borderRadius: '4px', border: `1px solid ${color}80`, boxShadow: '0 2px 4px rgba(0,0,0,0.5)', height: '28px' }}>
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
                                style={{ width: '70px', height: '20px', background: 'transparent', border: 'none', color: '#fff', fontSize: '12px', outline: 'none', fontWeight: 'bold', textAlign: 'center' }}
                            />
                        </div>

                        <button
                            onClick={(e) => {
                                e.stopPropagation();
                                handleCancel(l.id);
                            }}
                            onPointerDown={e => e.stopPropagation()}
                            style={{ background: '#0f172a', border: `1px solid ${color}80`, color: color, borderRadius: '4px', width: '28px', height: '28px', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '13px', fontWeight: 'bold', boxShadow: '0 2px 4px rgba(0,0,0,0.5)' }}>✕</button>
                    </div>
                );
            })}

            {/* Mobile Crosshair Toggle (Floating Bottom Left) */}
            {isMaximized && (
                <button
                    onClick={() => setIsCrosshairForced(!isCrosshairForced)}
                    className={`chart-tool-btn btn-crosshair-floating ${isCrosshairForced ? 'active' : ''}`}
                    style={{ 
                        position: 'absolute', 
                        bottom: '120px', 
                        left: '12px', 
                        zIndex: 50,
                        width: '36px',
                        height: '36px',
                        borderRadius: '50%',
                        background: isCrosshairForced ? 'rgba(59, 130, 246, 0.9)' : 'rgba(15, 23, 42, 0.8)', 
                        color: isCrosshairForced ? '#ffffff' : '#94a3b8',
                        border: isCrosshairForced ? '1px solid #60a5fa' : '1px solid rgba(255,255,255,0.2)',
                        boxShadow: '0 4px 12px rgba(0,0,0,0.5)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        cursor: 'pointer'
                    }}
                >
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="12" y1="8" x2="12" y2="16"></line><line x1="8" y1="12" x2="16" y2="12"></line></svg>
                </button>
            )}
        </div>
    );
});

const SafeCanvasTradingChart = React.memo((props) => (
    <ErrorBoundary fallbackTitle={`Chart Error (${props.symbol || 'Symbol'})`}>
        <CanvasTradingChart {...props} />
    </ErrorBoundary>
));

export default SafeCanvasTradingChart;
