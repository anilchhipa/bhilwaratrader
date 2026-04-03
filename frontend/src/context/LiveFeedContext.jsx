import React, { createContext, useContext, useEffect, useRef, useState, useCallback } from 'react';
import SockJS from 'sockjs-client';
import Stomp from 'stompjs';
import { WS_BASE } from '../config';

const LiveFeedContext = createContext(null);

export const LiveFeedProvider = ({ children }) => {
    const [status, setStatus] = useState('disconnected');
    const stompClientRef = useRef(null);
    const subscriptionsRef = useRef({}); // topic -> { count, subObject, listeners: [] }
    const snapshotListenersRef = useRef({}); // `${symbol}/${interval}` -> callback
    const [reconnectCount, setReconnectCount] = useState(0);

    const connect = useCallback(() => {
        console.log("LiveFeed: Attempting WebSocket connection...");
        const socket = new SockJS(WS_BASE);
        const stomp = Stomp.over(socket);
        stomp.debug = null; // Disable noisy logs

        stomp.connect({}, () => {
            console.log("LiveFeed: WebSocket Connected!");
            stompClientRef.current = stomp;
            setStatus('connected');

            // Subscribe to the personal snapshot queue (session-specific)
            stomp.subscribe('/user/queue/snapshot', (msg) => {
                try {
                    const data = JSON.parse(msg.body);
                    if (data.type === 'SNAPSHOT' && data.symbol && data.interval) {
                        const key = `${data.symbol}/${data.interval}`;
                        const listener = snapshotListenersRef.current[key];
                        if (listener) {
                            console.log(`LiveFeed: Snapshot received — ${data.candles?.length} candles for ${key}`);
                            listener(data.candles);
                        }
                    }
                } catch (e) { console.warn('Snapshot parse error', e); }
            });

            // Re-subscribe to all existing live-tick topics on reconnection
            Object.keys(subscriptionsRef.current).forEach(topic => {
                const subEntry = subscriptionsRef.current[topic];
                subEntry.subObject = stomp.subscribe(topic, (msg) => {
                    const data = JSON.parse(msg.body);
                    subEntry.listeners.forEach(cb => cb(data));
                });
            });
        }, (err) => {
            console.warn("LiveFeed: Connection lost, retrying in 3s...", err);
            setStatus('error');
            setTimeout(() => setReconnectCount(c => c + 1), 3000);
        });
    }, []);

    useEffect(() => {
        connect();
        return () => {
            if (stompClientRef.current) stompClientRef.current.disconnect();
        };
    }, [reconnectCount, connect]);

    // Register a one-time snapshot listener for a specific symbol/interval
    const registerSnapshotListener = useCallback((symbol, interval, callback) => {
        const key = `${symbol}/${interval}`;
        snapshotListenersRef.current[key] = callback;
        return () => { delete snapshotListenersRef.current[key]; };
    }, []);

    const subscribe = useCallback((symbol, interval, callback) => {
        const topic = `/topic/updates/${symbol}/${interval}`;

        if (!subscriptionsRef.current[topic]) {
            subscriptionsRef.current[topic] = { count: 0, subObject: null, listeners: [] };

            // If already connected, subscribe immediately
            if (stompClientRef.current && stompClientRef.current.connected) {
                subscriptionsRef.current[topic].subObject = stompClientRef.current.subscribe(topic, (msg) => {
                    const data = JSON.parse(msg.body);
                    subscriptionsRef.current[topic].listeners.forEach(cb => cb(data));
                });
            }
        }

        const entry = subscriptionsRef.current[topic];
        entry.count++;
        entry.listeners.push(callback);

        // Return unsubscribe function
        return () => {
            entry.count--;
            entry.listeners = entry.listeners.filter(cb => cb !== callback);

            if (entry.count <= 0) {
                if (entry.subObject) entry.subObject.unsubscribe();
                delete subscriptionsRef.current[topic];
            }
        };
    }, []);

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
