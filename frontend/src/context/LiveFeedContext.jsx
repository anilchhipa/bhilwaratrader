/**
 * LiveFeedContext.jsx
 *
 * Architecture:
 *  ┌──────────────┐   port.postMessage   ┌────────────────────────┐
 *  │  Window / Tab │ ◄──────────────────► │  SharedWorker          │
 *  │  (this file)  │                      │  (tradingWorker.js)    │
 *  └──────────────┘                       │  Single STOMP WS conn  │
 *                                         └────────────────────────┘
 *
 * If the browser doesn't support SharedWorker, falls back to a direct
 * per-window STOMP connection (original behaviour).
 *
 * Protobuf decoding is done here (in the window) so the worker stays
 * dependency-free and lightweight. Proto messages are small (<1 KB),
 * so per-window decoding overhead is negligible.
 */
import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import { Client } from '@stomp/stompjs';
import protobuf from 'protobufjs';

const LiveFeedContext = createContext(null);

// ── Singleton SharedWorker ────────────────────────────────────────────────────
// One SharedWorker instance is shared across ALL React trees in the same origin.
// We create it at module level so it is truly a singleton even across HMR reloads.
let sharedWorkerPort = null;
let workerPortListeners = []; // callbacks to notify when port is ready

function getWorkerPort() {
    if (sharedWorkerPort) return sharedWorkerPort;

    if (typeof SharedWorker === 'undefined') return null; // Browser doesn't support it

    try {
        const worker = new SharedWorker(
            new URL('../workers/tradingWorker.js', import.meta.url),
            { type: 'module', name: 'trading-feed' }
        );
        sharedWorkerPort = worker.port;
        sharedWorkerPort.start();

        // Notify all pending listeners that the port is ready
        workerPortListeners.forEach(fn => fn(sharedWorkerPort));
        workerPortListeners = [];

        worker.onerror = (e) => console.error('[LiveFeed] SharedWorker error', e);

        return sharedWorkerPort;
    } catch (e) {
        console.warn('[LiveFeed] SharedWorker unavailable, falling back to direct WS', e);
        return null;
    }
}

// ── Provider ─────────────────────────────────────────────────────────────────
export const LiveFeedProvider = ({ children }) => {
    const [status, setStatus]   = useState('disconnected');
    const [protoType, setProtoType] = useState(null);

    // Refs for SharedWorker path
    const workerPortRef     = useRef(null);
    const workerMsgHandler  = useRef(null); // single onmessage handler on the port

    // Refs for fallback-WS path
    const stompClientRef    = useRef(null);

    // Shared state for both paths
    const subscriptionsRef      = useRef({}); // topic → { count, listeners: [] }
    const snapshotListenersRef  = useRef({}); // `symbol/interval` → callback

    const useSharedWorker = typeof SharedWorker !== 'undefined';

    // ── Protobuf loader (used by both paths) ─────────────────────────────────
    useEffect(() => {
        protobuf.load(`/proto/api.proto`, (err, root) => { // No cache-busting — allow HTTP cache
            if (err) { console.warn('[LiveFeed] Proto load failed', err); return; }
            setProtoType(root.lookupType('com.algo.proto.CandleUpdate'));
        });
    }, []);

    // ── Proto decoder (memoised, stable reference) ────────────────────────────
    const decodeProto = useCallback((binaryBody, protoT) => {
        if (!protoT || !binaryBody) return null;
        try {
            const bytes = binaryBody instanceof ArrayBuffer
                ? new Uint8Array(binaryBody)
                : binaryBody;
            const decoded = protoT.decode(bytes);
            const c = decoded.candle;
            return {
                symbol: decoded.symbol,
                interval: decoded.timeframe,
                ohlc: { t: Number(c.t), o: c.o, h: c.h, l: c.l, c: c.c, v: c.v, d: c.d },
                indicators: {
                    ema15: c.ema15,
                    ema15_5: c.ema15Plus5,
                    ema15_15: c.ema15Plus15,
                    ema15_15_15: c.ema15Plus15Plus15,
                    stMa: c.stMa, stUp: c.stUp, stDn: c.stDn, stTrend: c.stTrend,
                    stMa2: c.stMa2, stUp2: c.stUp2, stDn2: c.stDn2, stTrend2: c.stTrend2,
                    haTrailingSl: c.haTrailingSl,
                    haTrend: c.haTrend,
                    htfTrailingSl: c.htfTrailingSl,
                    htfTrend: c.htfTrend,
                }
            };
        } catch (e) {
            console.warn('[LiveFeed] Proto decode failed', e);
            return null;
        }
    }, []);

    const decodeJson = useCallback((body) => {
        try { return body ? JSON.parse(body) : null; } catch (_) { return null; }
    }, []);

    // ── Shared-Worker path ────────────────────────────────────────────────────
    const setupSharedWorkerPath = useCallback(() => {
        const port = getWorkerPort();
        if (!port) return false;

        workerPortRef.current = port;

        // Attach a single message handler on the shared port.
        // All routing happens here based on message type + topic.
        const onMessage = (e) => {
            const msg = e.data;
            if (!msg) return;

            if (msg.type === 'STATUS') {
                setStatus(msg.status);
                return;
            }

            if (msg.type === 'UPDATE') {
                const topic = msg.topic;
                const entry = subscriptionsRef.current[topic];
                if (!entry || !entry.listeners.length) return;

                // Decode (proto or JSON) — done here in the window, not the worker
                let data = null;
                if (msg.isProto && msg.binaryBody) {
                    data = decodeProto(msg.binaryBody, protoType);
                } else if (msg.body) {
                    data = decodeJson(msg.body);
                }
                if (data) entry.listeners.forEach(cb => cb(data));
                return;
            }

            if (msg.type === 'SNAPSHOT') {
                const listener = snapshotListenersRef.current[msg.key];
                if (listener && msg.data) listener(msg.data);
                return;
            }
        };

        // Remove old handler if any (HMR safety)
        if (workerMsgHandler.current) {
            port.removeEventListener('message', workerMsgHandler.current);
        }
        workerMsgHandler.current = onMessage;
        port.addEventListener('message', onMessage);

        // Ask for current status
        port.postMessage({ type: 'GET_STATUS' });
        return true;
    }, [decodeProto, decodeJson, protoType]);

    // ── Fallback direct-WS path (original behaviour) ──────────────────────────
    const setupDirectWsPath = useCallback(() => {
        const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const brokerUrl  = `${wsProtocol}//${window.location.hostname}:8085/ws-trading`;

        const decodeMsg = (msg) => {
            const isProto = msg.headers?.['content-type'] === 'application/x-protobuf';
            if (isProto && msg.binaryBody?.length > 0 && protoType) {
                return decodeProto(msg.binaryBody, protoType);
            }
            try {
                if (msg.body) return JSON.parse(msg.body);
                return JSON.parse(new TextDecoder().decode(msg.binaryBody));
            } catch (_) { return null; }
        };

        const client = new Client({
            brokerURL: brokerUrl,
            reconnectDelay: 1000,
            heartbeatIncoming: 4000,
            heartbeatOutgoing: 4000,
            splitLargeFrames: true,
        });

        client.onConnect = () => {
            stompClientRef.current = client;
            setStatus('connected');

            client.subscribe('/user/queue/snapshot', (msg) => {
                const data = decodeMsg(msg);
                if (data?.type === 'SNAPSHOT' && data.symbol && data.interval) {
                    const key = `${data.symbol}/${data.interval}`;
                    const listener = snapshotListenersRef.current[key];
                    if (listener) listener(data);
                }
            });

            // Re-subscribe to all existing topics after reconnect
            Object.entries(subscriptionsRef.current).forEach(([topic, entry]) => {
                entry.subObject = client.subscribe(topic, (msg) => {
                    const data = decodeMsg(msg);
                    if (data) entry.listeners.forEach(cb => cb(data));
                });
            });
        };

        client.onStompError   = (f) => console.error('[LiveFeed] STOMP error', f);
        client.onWebSocketError = (e) => { console.error('[LiveFeed] WS error', e); setStatus('error'); };
        client.activate();
        stompClientRef.current = client;
    }, [decodeProto, protoType]);

    // ── Initialise the correct path ───────────────────────────────────────────
    useEffect(() => {
        if (useSharedWorker) {
            setupSharedWorkerPath();
        } else {
            setupDirectWsPath();
        }

        const handleUnload = () => {
            if (useSharedWorker && workerPortRef.current) {
                try {
                    workerPortRef.current.postMessage({ type: 'CLOSE' });
                    workerPortRef.current.close();
                } catch (_) {}
            }
        };
        window.addEventListener('beforeunload', handleUnload);

        return () => {
            window.removeEventListener('beforeunload', handleUnload);
            // Cleanup fallback WS on unmount
            if (!useSharedWorker && stompClientRef.current) {
                stompClientRef.current.deactivate();
            }
        };
    }, [setupSharedWorkerPath, setupDirectWsPath, useSharedWorker]);

    // Re-attach message handler whenever protoType loads (needed to close over latest protoType)
    useEffect(() => {
        if (useSharedWorker && protoType) {
            setupSharedWorkerPath();
        }
    }, [protoType, setupSharedWorkerPath, useSharedWorker]);

    // ── Public API ────────────────────────────────────────────────────────────

    const registerSnapshotListener = useCallback((symbol, interval, callback) => {
        const key = `${symbol}/${interval}`;
        snapshotListenersRef.current[key] = callback;

        // Tell worker to expect a snapshot for this key
        if (useSharedWorker && workerPortRef.current) {
            workerPortRef.current.postMessage({ type: 'REGISTER_SNAPSHOT', symbol, interval });
        }

        return () => { delete snapshotListenersRef.current[key]; };
    }, [useSharedWorker]);

    const subscribe = useCallback((symbol, interval, callback) => {
        const topic = `/topic/updates/${symbol}/${interval}`;

        if (!subscriptionsRef.current[topic]) {
            subscriptionsRef.current[topic] = { count: 0, listeners: [], subObject: null };
        }
        const entry = subscriptionsRef.current[topic];
        entry.count++;
        entry.listeners.push(callback);

        if (useSharedWorker) {
            // Tell the worker to subscribe (it deduplicates STOMP-level subs internally)
            if (workerPortRef.current) {
                workerPortRef.current.postMessage({ type: 'SUBSCRIBE', topic, symbol, interval });
            }
        } else {
            // Direct WS path — subscribe on the STOMP client
            if (entry.count === 1 && stompClientRef.current?.connected) {
                entry.subObject = stompClientRef.current.subscribe(topic, (msg) => {
                    const ct = msg.headers?.['content-type'];
                    const isProto = ct === 'application/x-protobuf' || ct === 'application/octet-stream';
                    let data = null;
                    if (isProto && msg.binaryBody?.length > 0 && protoType) {
                        data = decodeProto(msg.binaryBody, protoType);
                    } else {
                        try { data = JSON.parse(msg.body); } catch (_) {}
                    }
                    if (data) entry.listeners.forEach(cb => cb(data));
                });
            }
        }

        return () => {
            entry.count--;
            entry.listeners = entry.listeners.filter(cb => cb !== callback);

            if (entry.count <= 0) {
                if (useSharedWorker) {
                    workerPortRef.current?.postMessage({ type: 'UNSUBSCRIBE', topic });
                } else {
                    try { entry.subObject?.unsubscribe(); } catch (_) {}
                }
                delete subscriptionsRef.current[topic];
            }
        };
    }, [useSharedWorker, decodeProto, protoType]);

    const subscribeTopic = useCallback((topic, callback) => {
        if (!subscriptionsRef.current[topic]) {
            subscriptionsRef.current[topic] = { count: 0, listeners: [], subObject: null };
        }
        const entry = subscriptionsRef.current[topic];
        entry.count++;
        entry.listeners.push(callback);

        if (useSharedWorker) {
            if (workerPortRef.current) {
                workerPortRef.current.postMessage({ type: 'SUBSCRIBE', topic });
            }
        } else {
            if (entry.count === 1 && stompClientRef.current?.connected) {
                entry.subObject = stompClientRef.current.subscribe(topic, (msg) => {
                    let data = null;
                    try { data = JSON.parse(msg.body); } catch (_) {}
                    if (data) entry.listeners.forEach(cb => cb(data));
                });
            }
        }

        return () => {
            entry.count--;
            entry.listeners = entry.listeners.filter(cb => cb !== callback);

            if (entry.count <= 0) {
                if (useSharedWorker) {
                    workerPortRef.current?.postMessage({ type: 'UNSUBSCRIBE', topic });
                } else {
                    try { entry.subObject?.unsubscribe(); } catch (_) {}
                }
                delete subscriptionsRef.current[topic];
            }
        };
    }, [useSharedWorker]);

    return (
        <LiveFeedContext.Provider value={{ subscribe, subscribeTopic, registerSnapshotListener, status }}>
            {children}
        </LiveFeedContext.Provider>
    );
};

// ── Hook ──────────────────────────────────────────────────────────────────────
export const useLiveFeed = (symbol, interval, onUpdate, onSnapshot) => {
    const context      = useContext(LiveFeedContext);
    const callbackRef  = useRef(onUpdate);
    const snapshotRef  = useRef(onSnapshot);

    useEffect(() => { callbackRef.current  = onUpdate;   }, [onUpdate]);
    useEffect(() => { snapshotRef.current  = onSnapshot; }, [onSnapshot]);

    useEffect(() => {
        if (!context || !symbol || !interval) return;

        let unregisterSnapshot = () => {};
        if (context.registerSnapshotListener && snapshotRef.current) {
            unregisterSnapshot = context.registerSnapshotListener(symbol, interval, (data) => {
                if (snapshotRef.current) snapshotRef.current(data);
            });
        }

        const stableCallback = (data) => {
            if (callbackRef.current) callbackRef.current(data);
        };
        const unsubscribe = context.subscribe(symbol, interval, stableCallback);

        return () => { unsubscribe(); unregisterSnapshot(); };
    }, [context, symbol, interval]);

    return context?.status;
};

export const useTopicFeed = (topic, onUpdate) => {
    const context = useContext(LiveFeedContext);
    const callbackRef = useRef(onUpdate);

    useEffect(() => { callbackRef.current = onUpdate; }, [onUpdate]);

    useEffect(() => {
        if (!context || !topic) return;

        const stableCallback = (data) => {
            if (callbackRef.current) callbackRef.current(data);
        };
        const unsubscribe = context.subscribeTopic(topic, stableCallback);

        return () => unsubscribe();
    }, [context, topic]);
};
