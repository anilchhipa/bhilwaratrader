/**
 * tradingWorker.js — SharedWorker
 *
 * A single STOMP WebSocket connection is maintained here and shared across
 * ALL browser windows (monitors). Each window connects to this worker and
 * registers topic subscriptions. Updates are fanned-out to every window that
 * requested a given topic.
 *
 * Message protocol — Window → Worker:
 *   { type: 'SUBSCRIBE',   topic, symbol, interval }
 *   { type: 'UNSUBSCRIBE', topic }
 *   { type: 'REGISTER_SNAPSHOT', symbol, interval }
 *   { type: 'GET_STATUS' }
 *
 * Message protocol — Worker → Window (port):
 *   { type: 'UPDATE',   topic, isProto, binaryBody?, body? }
 *   { type: 'SNAPSHOT', key, data }
 *   { type: 'STATUS',   status }
 */

import { Client } from '@stomp/stompjs';

// ── State ────────────────────────────────────────────────────────────────────
let stompClient = null;
let currentStatus = 'disconnected';

/** All connected window ports */
const ports = [];

/** topic → { stompSub, refCount } — STOMP-level subscription tracking */
const topicSubs = {};

/** port → Set<topic> — which topics each window cares about */
const portTopics = new Map();

/** `symbol/interval` → port[] — which windows are waiting for a snapshot */
const snapshotWaiters = {};

/** `symbol/interval` → latest snapshot data for immediate delivery on tab/component remounts */
const snapshotCache = {};

// ── Helpers ──────────────────────────────────────────────────────────────────

function cleanupPort(port) {
    const idx = ports.indexOf(port);
    if (idx > -1) {
        ports.splice(idx, 1);
        console.log('[TradingWorker] Port cleaned up. Active ports left:', ports.length);
    }

    const topics = portTopics.get(port);
    if (topics) {
        topics.forEach(topic => {
            if (topicSubs[topic]) {
                topicSubs[topic].refCount = Math.max(0, topicSubs[topic].refCount - 1);
                if (topicSubs[topic].refCount === 0) {
                    try { topicSubs[topic].stompSub?.unsubscribe(); } catch (_) {}
                    delete topicSubs[topic];
                    console.log('[TradingWorker] STOMP unsubscribed from topic:', topic);
                }
            }
        });
    }
    portTopics.delete(port);

    Object.keys(snapshotWaiters).forEach(key => {
        const waiters = snapshotWaiters[key];
        if (waiters) {
            const wIdx = waiters.indexOf(port);
            if (wIdx > -1) waiters.splice(wIdx, 1);
            if (waiters.length === 0) delete snapshotWaiters[key];
        }
    });
}

function broadcast(msg) {
    const deadPorts = [];
    ports.forEach(p => {
        try {
            p.postMessage(msg);
        } catch (_) {
            deadPorts.push(p);
        }
    });
    if (deadPorts.length > 0) {
        deadPorts.forEach(cleanupPort);
    }
}

function setStatus(s) {
    currentStatus = s;
    broadcast({ type: 'STATUS', status: s });
}

/**
 * Subscribe to a STOMP topic if not already subscribed.
 * Must be called only when stompClient is connected.
 */
function ensureStompSub(topic) {
    if (topicSubs[topic]?.stompSub) return; // Already live

    const stompSub = stompClient.subscribe(topic, (msg) => {
        const ct = msg.headers?.['content-type'];
        const isProto = ct === 'application/x-protobuf' || ct === 'application/octet-stream';

        // Snapshot a copy of the binary body before the STOMP frame is recycled
        let binaryBody = null;
        if (isProto && msg.binaryBody && msg.binaryBody.byteLength > 0) {
            // Make an independent copy — the Uint8Array from STOMP may be a view
            // into a shared internal buffer that gets reused after this callback.
            binaryBody = msg.binaryBody.slice().buffer; // detached ArrayBuffer copy
        }
        const textBody = msg.body ?? null;

        const deadPorts = [];
        for (const [port, topics] of portTopics.entries()) {
            if (!topics.has(topic)) continue;
            try {
                if (binaryBody) {
                    // Structured-clone (copy) per recipient — binary is small (~few hundred bytes)
                    port.postMessage({ type: 'UPDATE', topic, isProto: true, binaryBody });
                } else {
                    port.postMessage({ type: 'UPDATE', topic, isProto: false, body: textBody });
                }
            } catch (_) {
                deadPorts.push(port);
            }
        }
        if (deadPorts.length > 0) {
            deadPorts.forEach(cleanupPort);
        }
    });

    topicSubs[topic] = { stompSub, refCount: 0 };
}

// ── STOMP Connection ─────────────────────────────────────────────────────────

function connect() {
    const protocol = self.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const host = self.location.hostname;
    const brokerUrl = `${protocol}//${host}:8085/ws-trading`;

    stompClient = new Client({
        brokerURL: brokerUrl,
        reconnectDelay: 2000,
        heartbeatIncoming: 4000,
        heartbeatOutgoing: 4000,
        splitLargeFrames: true,
    });

    stompClient.onConnect = () => {
        console.log('[TradingWorker] STOMP connected');

        // Personal snapshot queue — only 1 per connection
        stompClient.subscribe('/user/queue/snapshot', (msg) => {
            try {
                const text = msg.body
                    ? msg.body
                    : new TextDecoder().decode(msg.binaryBody);
                const data = JSON.parse(text);

                if (data?.type === 'SNAPSHOT' && data.symbol && data.interval) {
                    const key = `${data.symbol}/${data.interval}`;
                    snapshotCache[key] = { data, ts: Date.now() }; // Cache with timestamp (15s TTL)
                    const waiters = snapshotWaiters[key] ?? [];
                    const deadWaiters = [];
                    waiters.forEach(port => {
                        try {
                            port.postMessage({ type: 'SNAPSHOT', key, data });
                        } catch (_) {
                            deadWaiters.push(port);
                        }
                    });
                    if (deadWaiters.length > 0) {
                        deadWaiters.forEach(cleanupPort);
                    }
                    delete snapshotWaiters[key];
                }
            } catch (e) {
                console.warn('[TradingWorker] Snapshot decode error', e);
            }
        });

        // Re-subscribe to all pending topics (in case of reconnect)
        Object.keys(topicSubs).forEach(topic => {
            topicSubs[topic].stompSub = null; // reset so ensureStompSub re-subscribes
            ensureStompSub(topic);
        });

        setStatus('connected');
    };

    stompClient.onDisconnect  = () => { console.log('[TradingWorker] Disconnected'); setStatus('disconnected'); };
    stompClient.onStompError  = (f) => { console.error('[TradingWorker] STOMP error', f); setStatus('error'); };
    stompClient.onWebSocketError = (e) => { console.error('[TradingWorker] WS error', e); setStatus('error'); };

    stompClient.activate();
}

// ── Port Message Handler ─────────────────────────────────────────────────────

function handlePortMessage(port, event) {
    const { type, topic, symbol, interval } = event.data ?? {};

    switch (type) {

        case 'SUBSCRIBE': {
            if (!topic) break;

            // Register interest from this port
            if (!portTopics.has(port)) portTopics.set(port, new Set());
            portTopics.get(port).add(topic);

            // Track ref count so we know when STOMP-level unsub is safe
            if (!topicSubs[topic]) topicSubs[topic] = { stompSub: null, refCount: 0 };
            topicSubs[topic].refCount++;

            // Create STOMP subscription if connected
            if (stompClient?.connected) ensureStompSub(topic);

            // Send current status so the window can show connection state immediately
            port.postMessage({ type: 'STATUS', status: currentStatus });
            break;
        }

        case 'UNSUBSCRIBE': {
            if (!topic) break;

            portTopics.get(port)?.delete(topic);

            if (topicSubs[topic]) {
                topicSubs[topic].refCount = Math.max(0, topicSubs[topic].refCount - 1);

                // If nobody wants this topic anymore, drop the STOMP subscription
                if (topicSubs[topic].refCount === 0) {
                    try { topicSubs[topic].stompSub?.unsubscribe(); } catch (_) {}
                    delete topicSubs[topic];
                }
            }
            break;
        }

        case 'REGISTER_SNAPSHOT': {
            if (!symbol || !interval) break;
            const key = `${symbol}/${interval}`;
            const cached = snapshotCache[key];
            if (cached && cached.data && (Date.now() - (cached.ts || 0) < 15000)) {
                try {
                    port.postMessage({ type: 'SNAPSHOT', key, data: cached.data });
                } catch (_) {}
            }
            if (!snapshotWaiters[key]) snapshotWaiters[key] = [];
            if (!snapshotWaiters[key].includes(port)) snapshotWaiters[key].push(port);
            break;
        }

        case 'GET_STATUS': {
            port.postMessage({ type: 'STATUS', status: currentStatus });
            break;
        }

        case 'CLOSE': {
            cleanupPort(port);
            break;
        }

        default:
            break;
    }
}

// ── SharedWorker Entry Point ─────────────────────────────────────────────────

self.onconnect = (connectEvent) => {
    const port = connectEvent.ports[0];
    ports.push(port);
    portTopics.set(port, new Set());

    port.onmessage = (e) => handlePortMessage(port, e);
    port.start();

    // Clean up when the tab/window closes — prevents dead port accumulation
    port.addEventListener('close', () => {
        cleanupPort(port);
    });

    // Send current status immediately to the new window
    port.postMessage({ type: 'STATUS', status: currentStatus });
};

// Kick off the single shared WebSocket connection
connect();
