import React from 'react';

const ChartConfig = ({ visibility, setVisibility, themeColors, setThemeColors, onClose }) => {
    const sections = [
        {
            title: "Analysis Helpers",
            items: [
                { key: 'CLOUD', label: 'Supertrend Cloud' },
                { key: 'SIGNALS', label: 'Buy/Sell Signals' },
                { key: '1012_OPEN_CLOSE', label: '10:12 AM Ref Lines' }
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

    return (
        <div className="manage-symbols-modal">
            <div className="modal-content" style={{ width: '600px', height: 'auto', maxHeight: '85vh' }}>
                <div className="modal-header">
                    <h2>Chart Configuration</h2>
                    <button className="close-btn" onClick={onClose}>&times;</button>
                </div>
                <div className="manage-body">
                    <div className="categories-container" style={{ gap: '16px' }}>

                        {/* Theme Colors Section */}
                        <div className="category-group">
                            <label>Theme Colors</label>
                            <div style={{ display: 'flex', gap: '24px', padding: '10px' }}>
                                <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                                    <span style={{ fontSize: '13px', color: '#94a3b8' }}>Bullish Color:</span>
                                    <input
                                        type="color"
                                        value={themeColors.bullish}
                                        onChange={(e) => handleColorChange('bullish', e.target.value)}
                                        style={{ width: '40px', height: '30px', border: 'none', background: 'transparent', cursor: 'pointer' }}
                                    />
                                </div>
                                <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                                    <span style={{ fontSize: '13px', color: '#94a3b8' }}>Bearish Color:</span>
                                    <input
                                        type="color"
                                        value={themeColors.bearish}
                                        onChange={(e) => handleColorChange('bearish', e.target.value)}
                                        style={{ width: '40px', height: '30px', border: 'none', background: 'transparent', cursor: 'pointer' }}
                                    />
                                </div>
                            </div>
                        </div>

                        {sections.map(section => (
                            <div key={section.title} className="category-group">
                                <label>{section.title}</label>
                                <div className="checkbox-list" style={{ gridTemplateColumns: '1fr 1fr' }}>
                                    {section.items.map(item => (
                                        <div key={item.key} className="checkbox-item">
                                            <label>
                                                <input
                                                    type="checkbox"
                                                    checked={!!visibility[item.key]}
                                                    onChange={() => handleToggle(item.key)}
                                                />
                                                <span className="inst-name" style={{ fontSize: '12px' }}>{item.label}</span>
                                            </label>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
                <div className="modal-footer">
                    <button className="pill-btn active" onClick={onClose} style={{ padding: '8px 32px' }}>Done</button>
                </div>
            </div>
        </div>
    );
};

export default ChartConfig;
