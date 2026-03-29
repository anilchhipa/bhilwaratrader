import React, { useState, useEffect, useRef } from 'react';

const IndicatorsWidget = ({ isOpen, onClose, visibility, setVisibility, themeColors, setThemeColors }) => {
    const [position, setPosition] = useState(() => {
        const saved = localStorage.getItem('indicators_pos');
        return saved ? JSON.parse(saved) : { x: 80, y: 150 };
    });

    const [isDragging, setIsDragging] = useState(false);
    const offsetRef = useRef({ x: 0, y: 0 });

    const [zoomVal, setZoomVal] = useState(() => localStorage.getItem('chart_zoom_global') || '160');
    const [logEnabled, setLogEnabled] = useState(() => localStorage.getItem('chart_log_scale') === 'true');

    const sections = [
        {
            title: "Analysis Helpers",
            items: [
                { key: 'CLOUD', label: 'Supertrend Cloud (ST42)' },
                { key: 'CLOUD2', label: 'Supertrend Cloud (ST100)' }
            ]
        },
        {
            title: "EMA Indicators (Cascaded)",
            items: [
                { key: 'EMA15', label: 'EMA 15 (White)' },
                { key: 'EMA15_5', label: 'EMA 15/5 (Red)' },
                { key: 'EMA15_15', label: 'EMA 15/15 (Blue)' },
                { key: 'EMA15_15_15', label: 'EMA 15/15/15 (Red)' },
                { key: 'EMA15_15_15_15', label: 'EMA 15/15/15/15 (Yellow)' },
                { key: 'EMA15_15_15_15_30', label: 'EMA 15/30 (Red)' },
            ]
        },
    ];

    const handleToggle = (key) => {
        setVisibility(prev => {
            const next = { ...prev, [key]: !prev[key] };
            localStorage.setItem('chartVisibility', JSON.stringify(next));
            return next;
        });
    };

    const handleColorChange = (key, value) => {
        setThemeColors(prev => ({ ...prev, [key]: value }));
    };

    const startDrag = (e) => {
        if (e.target.closest('.indicators-header')) {
            setIsDragging(true);
            offsetRef.current = {
                x: e.clientX - position.x,
                y: e.clientY - position.y
            };
            e.preventDefault();
        }
    };

    useEffect(() => {
        if (!isDragging) return;
        const handleMove = (e) => {
            const newX = e.clientX - offsetRef.current.x;
            const newY = e.clientY - offsetRef.current.y;
            const pos = { x: newX, y: newY };
            setPosition(pos);
            localStorage.setItem('indicators_pos', JSON.stringify(pos));
        };
        const stopDrag = () => setIsDragging(false);
        window.addEventListener('mousemove', handleMove);
        window.addEventListener('mouseup', stopDrag);
        return () => {
            window.removeEventListener('mousemove', handleMove);
            window.removeEventListener('mouseup', stopDrag);
        };
    }, [isDragging]);

    if (!isOpen) return null;

    return (
        <div
            className="indicators-floating-panel"
            style={{
                left: `${Math.min(window.innerWidth - 340, Math.max(20, position.x))}px`,
                top: `${Math.min(window.innerHeight - 340, Math.max(20, position.y))}px`,
                width: '340px',
                zIndex: 1100
            }}
        >
            <div className="indicators-header" onMouseDown={startDrag} style={{ cursor: 'move' }}>
                <div className="indicators-title">
                    <span className="indicators-icon">📊</span>
                    <span>Indicators</span>
                </div>
                <button className="indicators-close-btn" onClick={onClose}>✕</button>
            </div>

            <div className="indicators-body" style={{ maxHeight: '400px', overflowY: 'auto' }}>
                <div className="categories-container" style={{ gap: '12px' }}>
                    {/* Global Viewport Controls */}
                    <div className="category-group" style={{ borderTop: '1px solid rgba(255,255,255,0.05)', paddingTop: '12px' }}>
                        <label style={{ fontSize: '10px', color: '#fbbf24' }}>Global Viewport Controls</label>
                        <div style={{ display: 'flex', gap: '8px', padding: '8px 4px' }}>
                            {/* Bar Zoom Dropdown */}
                            <div style={{
                                flex: 1,
                                display: 'flex',
                                alignItems: 'center',
                                background: 'rgba(0,0,0,0.2)',
                                padding: '0 8px',
                                borderRadius: '4px',
                                border: '1px solid rgba(255,255,255,0.05)',
                                height: '28px'
                            }}>
                                <span style={{ fontSize: '9px', color: '#94a3b8', marginRight: '8px', fontWeight: '800' }}>ZOOM</span>
                                <select
                                    value={zoomVal}
                                    onChange={(e) => {
                                        const count = parseInt(e.target.value);
                                        setZoomVal(count);
                                        localStorage.setItem('chart_zoom_global', count);
                                        // Save to all intervals as a shortcut
                                        ['1min', '3min', '5min', '15min'].forEach(tf => localStorage.setItem(`chart_zoom_${tf}`, count));

                                        const bc = new BroadcastChannel('grid_chart_sync');
                                        bc.postMessage({ type: 'SYNC_ZOOM', count });
                                        bc.close();
                                    }}
                                    style={{ background: 'transparent', border: 'none', color: '#d1d4dc', fontSize: '11px', fontWeight: '900', cursor: 'pointer', outline: 'none', width: '100%' }}
                                >
                                    {[50, 80, 120, 160, 240, 320, 500, 800].map(cnt => (
                                        <option key={cnt} value={cnt} style={{ background: '#1e222d' }}>{cnt} Bars</option>
                                    ))}
                                </select>
                            </div>

                            {/* Log Toggle */}
                            <div style={{
                                width: '60px',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                height: '28px',
                                background: logEnabled ? 'rgba(52, 211, 153, 0.2)' : 'rgba(0,0,0,0.2)',
                                borderRadius: '4px',
                                border: `1px solid ${logEnabled ? '#059669' : 'rgba(255,255,255,0.05)'}`,
                                cursor: 'pointer'
                            }}
                                onClick={() => {
                                    const next = !logEnabled;
                                    setLogEnabled(next);
                                    localStorage.setItem('chart_log_scale', next);

                                    const bc = new BroadcastChannel('grid_chart_sync');
                                    bc.postMessage({ type: 'SYNC_LOG_SCALE', enabled: next });
                                    bc.close();
                                }}
                                title="Toggle Logarithmic Scale Globally"
                            >
                                <span style={{ fontSize: '10px', color: logEnabled ? '#34d399' : '#94a3b8', fontWeight: '800' }}>LOG</span>
                            </div>
                        </div>
                    </div>

                    {sections.map(section => (
                        <div key={section.title} className="category-group">
                            <label style={{ fontSize: '10px' }}>{section.title}</label>
                            <div className="checkbox-list" style={{ gridTemplateColumns: '1fr' }}>
                                {section.items.map(item => (
                                    <div key={item.key} className="checkbox-item" style={{ height: '32px' }}>
                                        <label style={{ padding: '0 8px' }}>
                                            <input
                                                type="checkbox"
                                                checked={!!visibility[item.key]}
                                                onChange={() => handleToggle(item.key)}
                                            />
                                            <span className="inst-name" style={{ fontSize: '11px' }}>{item.label}</span>
                                        </label>
                                    </div>
                                ))}
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        </div>
    );
};

export default IndicatorsWidget;
