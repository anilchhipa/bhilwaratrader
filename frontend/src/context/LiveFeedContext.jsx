import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import { Client } from '@stomp/stompjs';
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
        // Append a timestamp to bypass browser cache and ensure new fields (signal, tradeScore, er) are recognized
        protobuf.load(`/proto/api.proto?v=${Date.now()}`, (err, root) => {
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
        const isProto = msg.headers['content-type'] === 'application/x-protobuf';

        // If explicitly proto, decode via Proto
        if (isProto && msg.binaryBody && msg.binaryBody.length > 0 && protoType) {
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
                        whaleSignal: c.whaleSignal,
                        signal: c.signal,
                        tradeScore: c.tradeScore,
                        er: c.er
                    },
                    renko: decoded.renko ? decoded.renko.map(rb => ({
                        t: Number(rb.t),
                        et: Number(rb.et),
                        o: rb.o,
                        c: rb.c
                    })) : []
                };
            } catch (e) {
                console.warn("LiveFeed: Proto decode failed", e);
                // If proto fails, the data may still be JSON in string body
            }
        }

        // Fallback to JSON for snapshots or if proto header is missing
        try {
            if (msg.body) return JSON.parse(msg.body);
            // In some binary brokers, the body is stringified binaryBody
            const textDecoder = new TextDecoder();
            const text = textDecoder.decode(msg.binaryBody);
            return JSON.parse(text);
        } catch (e) {
            return null;
        }
    }, [protoType]);

    const connect = useCallback(() => {
        console.log("LiveFeed: Attempting pure WebSocket connection...");
        // Construct native WS URL directly (bypassing SockJS fallback)
        // If hosted on https, use wss. Otherwise ws.
        const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        // In dev, assuming API_BASE is on 8085
        const currentHost = window.location.hostname;
        const brokerUrl = `${wsProtocol}//${currentHost}:8085/ws-trading`;

        const stompClient = new Client({
            brokerURL: brokerUrl,
            reconnectDelay: 1000,
            heartbeatIncoming: 4000,
            heartbeatOutgoing: 4000,
            splitLargeFrames: true
        });

        stompClient.onConnect = (frame) => {
            console.log("LiveFeed: Binary WebSocket STOMP Connected!");
            stompClientRef.current = stompClient;
            setStatus('connected');

            // Subscribe to the personal snapshot queue
            stompClient.subscribe('/user/queue/snapshot', (msg) => {
                const data = decodeMessage(msg);
                if (data && data.type === 'SNAPSHOT' && data.symbol && data.interval) {
                    const key = `${data.symbol}/${data.interval}`;
                    const listener = snapshotListenersRef.current[key];
                    if (listener) listener(data);
                }
            });

            // Re-subscribe to all existing live-tick topics
            Object.keys(subscriptionsRef.current).forEach(topic => {
                const subEntry = subscriptionsRef.current[topic];
                subEntry.subObject = stompClient.subscribe(topic, (msg) => {
                    const data = decodeMessage(msg);
                    if (data) subEntry.listeners.forEach(cb => cb(data));
                });
            });
        };

        stompClient.onStompError = (frame) => {
            console.error('Broker reported error: ' + frame.headers['message']);
        };

        stompClient.onWebSocketError = (event) => {
            console.error('WebSocket Error', event);
            setStatus('error');
        }

        stompClient.activate();
    }, [decodeMessage]);

    useEffect(() => {
        connect();
        return () => {
            if (stompClientRef.current) stompClientRef.current.deactivate();
        };
    }, [connect]);

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
