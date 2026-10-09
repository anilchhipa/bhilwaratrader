import React, { useState, useEffect, useRef } from 'react';

const IndicatorsWidget = ({ isOpen, onClose, visibility, setVisibility, themeColors, setThemeColors, gridLayout, setGridLayout, setPageIndex }) => {
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
                { key: 'CLOUD', label: 'Cloud 1' },
                { key: 'CLOUD2', label: 'Cloud 2' },
            ]
        },
        {
            title: "Indicators (Cascaded)",
            items: [
                { key: 'EMA15', label: 'Ind 1' },
                { key: 'EMA15_5', label: 'Ind 2' },
                { key: 'EMA15_15', label: 'Ind 3' },
                { key: 'EMA15_15_15', label: 'Ind 4' },
            ]
        },
        {
            title: "Renko Panel",
            items: [
                { key: 'RENKO2', label: 'Renko 2 (White)' },
                { key: 'RENKO3', label: 'Renko 3 (Blue)' },
                { key: 'RENKO_CLOSE', label: 'Close MA (Red)' },
            ]
        },
        {
            title: "Supertrend MTF Heikin Ashi",
            items: [
                { key: 'ST_HA_CURRENT', label: 'Supertrend HA (Current TF)' },
                { key: 'ST_HA_HTF', label: 'Supertrend HTF (MTF Line)' },
                { key: 'ST_HA_FLIPS', label: 'HTF Trend Flip Dots' },
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
        if (e.target.closest('.premium-widget-header')) {
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
            className="premium-widget-panel"
            style={window.innerWidth > 768 ? {
                left: `${Math.min(window.innerWidth - 680, Math.max(20, position.x))}px`,
                top: `${Math.min(window.innerHeight - 100, Math.max(20, position.y))}px`,
                width: '680px',
                maxHeight: 'calc(100vh - 40px)',
                zIndex: 1100
            } : { zIndex: 1100, maxHeight: '85vh' }}
        >
            <div className="premium-widget-header" onMouseDown={startDrag} style={{ cursor: 'move' }}>
                <div className="premium-widget-title">
                    <span className="premium-widget-icon">📊</span>
                    <span>Configuration</span>
                </div>
                <button className="premium-widget-close-btn" onClick={onClose}>✕</button>
            </div>

            <div className="premium-widget-body">
                <div className="categories-container" style={{ display: 'grid', gridTemplateColumns: window.innerWidth > 768 ? '1fr 1fr' : '1fr', gap: '16px' }}>
                    {/* Global Viewport Controls */}
                    <div className="category-group" style={{ borderTop: '1px solid rgba(255,255,255,0.05)', paddingTop: '16px' }}>
                        <label className="group-label">Global Viewport Controls</label>
                        <div style={{ display: 'flex', gap: '10px', marginTop: '10px' }}>
                            {/* Bar Zoom Dropdown */}
                            <div className="control-item-wrapper" style={{ flex: 1 }}>
                                <span className="control-label">ZOOM</span>
                                <select
                                    value={zoomVal}
                                    onChange={(e) => {
                                        const count = parseInt(e.target.value);
                                        setZoomVal(count);
                                        localStorage.setItem('chart_zoom_global', count);
                                        ['1min', '3min', '5min'].forEach(tf => localStorage.setItem(`chart_zoom_${tf}`, count));
                                        const bc = new BroadcastChannel('grid_chart_sync');
                                        bc.postMessage({ type: 'SYNC_ZOOM', count });
                                        bc.close();
                                    }}
                                    className="premium-select"
                                >
                                    {[50, 80, 120, 160, 240, 320, 500, 800].map(cnt => (
                                        <option key={cnt} value={cnt}>{cnt} Bars</option>
                                    ))}
                                </select>
                            </div>

                            {/* Log Toggle */}
                            <div
                                className={`log-toggle-btn ${logEnabled ? 'active' : ''}`}
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
                                <span>LOG</span>
                            </div>
                        </div>

                        {/* Scanner Grid Layout */}
                        {gridLayout && (
                            <div style={{ marginTop: '16px' }}>
                                <label className="control-label" style={{ marginBottom: '8px', display: 'block', fontSize: '11px', color: '#94a3b8', letterSpacing: '1px' }}>GRID LAYOUT</label>
                                <div className="scanner-tf-selector" style={{ margin: 0, justifyContent: 'flex-start', background: 'rgba(0, 0, 0, 0.2)', padding: '4px', borderRadius: '6px' }}>
                                    {['2x2', '2x3', '2x4'].map(layout => (
                                        <button
                                            key={layout}
                                            className={`tf-tab ${gridLayout === layout ? 'active' : ''}`}
                                            style={{ flex: 1, textAlign: 'center', padding: '6px' }}
                                            onClick={() => {
                                                if (setGridLayout) setGridLayout(layout);
                                                localStorage.setItem('scanner_grid_layout', layout);
                                                if (setPageIndex) setPageIndex(0);
                                            }}
                                        >
                                            {layout}
                                        </button>
                                    ))}
                                </div>
                            </div>
                        )}
                    </div>

                    {sections.map(section => (
                        <div key={section.title} className="category-group">
                            <label className="group-label">{section.title}</label>
                            <div className="checkbox-list" style={{ gridTemplateColumns: section.items.length > 3 ? '1fr 1fr' : '1fr', marginTop: '10px', gap: '8px' }}>
                                {section.items.map(item => (
                                    <div key={item.key} className="checkbox-item premium-checkbox" style={{ height: '36px' }}>
                                        <label>
                                            <input
                                                type="checkbox"
                                                checked={!!visibility[item.key]}
                                                onChange={() => handleToggle(item.key)}
                                            />
                                            <span className="inst-name">{item.label}</span>
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
