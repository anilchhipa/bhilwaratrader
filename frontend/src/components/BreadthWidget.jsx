import React, { useState, useEffect, useRef } from 'react';
import axios from 'axios';
import { API_BASE } from '../config';

const TF_LABELS = {
    '1min': '1m',
    '3min': '3m',
    '5min': '5m',
    '15min': '15m',
};

const TIMEFRAMES = ['15min', '5min', '3min', '1min'];

function BreadthBar({ up, down, total }) {
    if (total === 0) return <div className="breadth-bar-empty">—</div>;
    const upPct = Math.round((up / total) * 100);
    const dnPct = Math.round((down / total) * 100);

    const upColor = upPct >= 70 ? '#22c55e' : upPct >= 50 ? '#86efac' : '#6b7280';
    const dnColor = dnPct >= 70 ? '#ef4444' : dnPct >= 50 ? '#fca5a5' : '#6b7280';

    return (
        <div className="breadth-bar-track">
            <div
                className="breadth-bar-fill"
                style={{ width: `${upPct}%`, background: upColor }}
                title={`${up} UP`}
            />
            <div
                className="breadth-bar-fill-dn"
                style={{ width: `${dnPct}%`, background: dnColor }}
                title={`${down} DOWN`}
            />
        </div>
    );
}

function BreadthRow({ tf, data, prevData }) {
    if (!data) return null;
    const { stUp, stDown, emaUp, emaDown, total } = data;
    const neutral = total - stUp - stDown;

    const stUpPct = total > 0 ? Math.round((stUp / total) * 100) : 0;
    const emaUpPct = total > 0 ? Math.round((emaUp / total) * 100) : 0;

    let momentum = null;
    if (prevData) {
        const prevPct = dData => dData.total > 0 ? Math.round((dData.stUp / dData.total) * 100) : 0;
        const currentP = stUpPct;
        const previousP = prevPct(prevData);
        if (currentP > previousP + 3) momentum = '↑';
        else if (currentP < previousP - 3) momentum = '↓';
    }

    const isStrongBull = stUpPct >= 75;
    const isStrongBear = stUpPct <= 25;
    const rowClass = isStrongBull ? 'breadth-row bull-strong' : isStrongBear ? 'breadth-row bear-strong' : 'breadth-row';

    return (
        <div className={rowClass}>
            <div className="breadth-tf-label">{TF_LABELS[tf]}</div>
            <div className="breadth-counts">
                <span className="breadth-up">{stUp}↑</span>
                <span className="breadth-separator">/</span>
                <span className="breadth-down">{stDown}↓</span>
                {neutral > 0 && <span className="breadth-neutral"> {neutral}~</span>}
            </div>
            <div className="breadth-bar-wrapper">
                <BreadthBar up={stUp} down={stDown} total={total} />
            </div>
            <div className="breadth-pct" style={{
                color: isStrongBull ? '#22c55e' : isStrongBear ? '#ef4444' : '#94a3b8'
            }}>
                {stUpPct}%
                {momentum && (
                    <span className="breadth-momentum" style={{ color: momentum === '↑' ? '#22c55e' : '#ef4444' }}>
                        {momentum}
                    </span>
                )}
            </div>
            <div className="breadth-ema-pct" title="EMA15/EMA15_5 Bias">
                <span style={{ color: emaUpPct >= 50 ? '#60a5fa' : '#f87171', fontSize: '9px' }}>
                    E:{emaUpPct}%
                </span>
            </div>
        </div>
    );
}

export default function BreadthWidget({ breadth, prevBreadth }) {
    const [isOpen, setIsOpen] = useState(false);

    // Draggable position state
    const [position, setPosition] = useState(() => {
        const saved = localStorage.getItem('breadth_pos');
        return saved ? JSON.parse(saved) : { x: 20, y: window.innerHeight - 80 };
    });
    const [isDragging, setIsDragging] = useState(false);
    const offsetRef = useRef({ x: 0, y: 0 });

    const sentiment = (() => {
        if (!breadth) return null;
        let totalUp = 0, totalTotal = 0;
        for (const tf of ['1min', '3min', '5min']) {
            const d = breadth[tf];
            if (d) { totalUp += d.stUp; totalTotal += d.total; }
        }
        if (totalTotal === 0) return null;
        const pct = Math.round((totalUp / totalTotal) * 100);
        if (pct >= 70) return { label: 'BULL', color: '#22c55e', pct };
        if (pct <= 30) return { label: 'BEAR', color: '#ef4444', pct };
        return { label: 'MIXED', color: '#94a3b8', pct };
    })();

    const startDrag = (e) => {
        setIsDragging(true);
        offsetRef.current = {
            x: e.clientX - position.x,
            y: e.clientY - position.y
        };
        e.preventDefault();
    };

    useEffect(() => {
        if (!isDragging) return;
        const handleMove = (e) => {
            const newX = e.clientX - offsetRef.current.x;
            const newY = e.clientY - offsetRef.current.y;
            const pos = { x: newX, y: newY };
            setPosition(pos);
            localStorage.setItem('breadth_pos', JSON.stringify(pos));
        };
        const stopDrag = () => setIsDragging(false);
        window.addEventListener('mousemove', handleMove);
        window.addEventListener('mouseup', stopDrag);
        return () => {
            window.removeEventListener('mousemove', handleMove);
            window.removeEventListener('mouseup', stopDrag);
        };
    }, [isDragging]);

    return (
        <>
            <div
                className={`breadth-fab ${isDragging ? 'dragging' : ''}`}
                onMouseDown={startDrag}
                onClick={(e) => { if (!isDragging) setIsOpen(true); }}
                title="Hold & Drag to move, Click to open"
                style={{
                    left: `${position.x}px`,
                    top: `${position.y}px`,
                    borderColor: sentiment ? sentiment.color + '66' : 'rgba(255,255,255,0.1)'
                }}
            >
                <div className="fab-content">
                    <span className="fab-icon">📡</span>
                    {sentiment && (
                        <span className="fab-pct" style={{ color: sentiment.color }}>
                            {sentiment.pct}%
                        </span>
                    )}
                </div>
            </div>

            {isOpen && (
                <div
                    className="breadth-floating-panel"
                    style={{
                        left: `${Math.min(window.innerWidth - 340, Math.max(20, position.x))}px`,
                        top: `${Math.min(window.innerHeight - 340, Math.max(20, position.y - 300))}px`
                    }}
                >
                    <div className="breadth-header">
                        <div className="breadth-title">
                            <span className="breadth-icon">📡</span>
                            <span>Market Breadth</span>
                        </div>
                        <div className="breadth-header-right">
                            {sentiment && (
                                <span className="breadth-sentiment-badge" style={{
                                    color: sentiment.color,
                                    borderColor: sentiment.color + '44',
                                }}>
                                    {sentiment.label} {sentiment.pct}%
                                </span>
                            )}
                            <button className="breadth-close-btn" onClick={() => setIsOpen(false)}>✕</button>
                        </div>
                    </div>

                    <div className="breadth-body">
                        <div className="breadth-legend">
                            <span>TF</span>
                            <span>ST Trend</span>
                            <span>Bar</span>
                            <span>%</span>
                            <span>EMA</span>
                        </div>
                        {!breadth ? (
                            <div className="breadth-loading">Loading...</div>
                        ) : (
                            TIMEFRAMES.map(tf => (
                                <BreadthRow
                                    key={tf}
                                    tf={tf}
                                    data={breadth[tf]}
                                    prevData={prevBreadth?.[tf]}
                                />
                            ))
                        )}
                    </div>
                </div>
            )}
        </>
    );
}
