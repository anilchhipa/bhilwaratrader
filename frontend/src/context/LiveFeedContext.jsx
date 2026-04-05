import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import SockJS from 'sockjs-client';
import Stomp from 'stompjs';
import { WS_BASE } from '../config';
import protobuf from 'protobufjs';

const LiveFeedContext = createContext(null);

export const LiveFeedProvider = ({ children }) => {
    const [status, setStatus] = useState('disconnected');
    const [protoType, setProtoType] = useState(null);
    const stompClientRef = useRef(null);
    const subscriptionsRef = useRef({}); // topic -> { count, subObject, listeners: [] }
    const snapshotListenersRef = useRef({}); // `${symbol}/${interval}` -> callback
    const [reconnectCount, setReconnectCount] = useState(0);

    // Initialize Protobuf Decoder
    useEffect(() => {
        protobuf.load("/proto/api.proto", (err, root) => {
            if (err) {
                console.warn("LiveFeed: Failed to load proto schema", err);
                return;
            }
            const CandleUpdate = root.lookupType("com.algo.proto.CandleUpdate");
            setProtoType(CandleUpdate);
            console.log("LiveFeed: Protobuf schema loaded.");
        });
    }, []);

    const decodeMessage = useCallback((msg) => {
        // If message body is binary (Uint8Array), decode via Proto
        if (msg.binaryBody && protoType) {
            try {
                const decoded = protoType.decode(msg.binaryBody);
                const c = decoded.candle;

                // Map camelCase proto fields back to the format expected by our charts
                return {
                    symbol: decoded.symbol,
                    interval: decoded.timeframe,
                    ohlc: { t: Number(c.t), o: c.o, h: c.h, l: c.l, c: c.c, v: c.v, d: c.d },
                    indicators: {
                        ema15: c.ema15,
                        ema15_5: c.ema15Plus5,
                        ema15_15: c.ema15Plus15,
                        ema15_15_15: c.ema15Plus15Plus15,
                        ema15_15_15_15: c.ema15Plus15Plus15Plus15,
                        ema15_15_15_15_30: c.ema15Plus15Plus15Plus15Plus30,
                        stMa: c.stMa, stUp: c.stUp, stDn: c.stDn, stTrend: c.stTrend,
                        stMa2: c.stMa2, stUp2: c.stUp2, stDn2: c.stDn2, stTrend2: c.stTrend2,
                        whaleSignal: c.whaleSignal
                    }
                };
            } catch (e) {
                console.warn("LiveFeed: Proto decode failed", e);
            }
        }
        // Fallback to JSON
        try {
            return JSON.parse(msg.body);
        } catch (e) {
            return null;
        }
    }, [protoType]);

    const connect = useCallback(() => {
        console.log("LiveFeed: Attempting WebSocket connection...");
        const socket = new SockJS(WS_BASE);
        const stomp = Stomp.over(socket);
        stomp.debug = null; // Disable noisy logs

        stomp.connect({}, () => {
            console.log("LiveFeed: WebSocket Connected!");
            stompClientRef.current = stomp;
            setStatus('connected');

            // Subscribe to the personal snapshot queue
            stomp.subscribe('/user/queue/snapshot', (msg) => {
                const data = decodeMessage(msg);
                if (data && data.type === 'SNAPSHOT' && data.symbol && data.interval) {
                    const key = `${data.symbol}/${data.interval}`;
                    const listener = snapshotListenersRef.current[key];
                    if (listener) listener(data.candles);
                }
            });

            // Re-subscribe to all existing live-tick topics
            Object.keys(subscriptionsRef.current).forEach(topic => {
                const subEntry = subscriptionsRef.current[topic];
                subEntry.subObject = stomp.subscribe(topic, (msg) => {
                    const data = decodeMessage(msg);
                    if (data) subEntry.listeners.forEach(cb => cb(data));
                });
            });
        }, (err) => {
            console.warn("LiveFeed: Connection lost, retrying in 3s...", err);
            setStatus('error');
            setTimeout(() => setReconnectCount(c => c + 1), 3000);
        });
    }, [decodeMessage]);

    useEffect(() => {
        connect();
        return () => {
            if (stompClientRef.current) stompClientRef.current.disconnect();
        };
    }, [reconnectCount, connect]);

    const registerSnapshotListener = useCallback((symbol, interval, callback) => {
        const key = `${symbol}/${interval}`;
        snapshotListenersRef.current[key] = callback;
        return () => { delete snapshotListenersRef.current[key]; };
    }, []);

    const subscribe = useCallback((symbol, interval, callback) => {
        const topic = `/topic/updates/${symbol}/${interval}`;

        if (!subscriptionsRef.current[topic]) {
            subscriptionsRef.current[topic] = { count: 0, subObject: null, listeners: [] };

            if (stompClientRef.current && stompClientRef.current.connected) {
                subscriptionsRef.current[topic].subObject = stompClientRef.current.subscribe(topic, (msg) => {
                    const data = decodeMessage(msg);
                    if (data) subscriptionsRef.current[topic].listeners.forEach(cb => cb(data));
                });
            }
        }

        const entry = subscriptionsRef.current[topic];
        entry.count++;
        entry.listeners.push(callback);

        return () => {
            entry.count--;
            entry.listeners = entry.listeners.filter(cb => cb !== callback);
            if (entry.count <= 0) {
                if (entry.subObject) entry.subObject.unsubscribe();
                delete subscriptionsRef.current[topic];
            }
        };
    }, [decodeMessage]);

    return (
        <LiveFeedContext.Provider value={{ subscribe, registerSnapshotListener, status }}>
            {children}
        </LiveFeedContext.Provider>
    );
};

export const useLiveFeed = (symbol, interval, onUpdate, onSnapshot) => {
    const context = useContext(LiveFeedContext);
    const callbackRef = useRef(onUpdate);
    const snapshotRef = useRef(onSnapshot);

    useEffect(() => { callbackRef.current = onUpdate; }, [onUpdate]);
    useEffect(() => { snapshotRef.current = onSnapshot; }, [onSnapshot]);

    useEffect(() => {
        if (!context || !symbol || !interval) return;

        // Register snapshot listener if provided
        let unregisterSnapshot = () => { };
        if (context.registerSnapshotListener && snapshotRef.current) {
            unregisterSnapshot = context.registerSnapshotListener(symbol, interval, (candles) => {
                if (snapshotRef.current) snapshotRef.current(candles);
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
