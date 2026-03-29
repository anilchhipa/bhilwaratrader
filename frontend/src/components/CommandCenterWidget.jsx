import SignalMatrixList from './SignalMatrixList';

export default function CommandCenterWidget({
    isOpen,
    onClose,
    trackedSymbols,
    selectedSymbol,
    onSymbolSelect,
    symbolPrices,
    toggleWidget,
    activeWidgets
}) {
    if (!isOpen) return null;

    return (
        <aside className="sidebar dedicated-sidebar">
            <div className="sidebar-header chart-inspired-header" style={{ userSelect: 'none' }}>
                <h3 className="outfit" style={{ margin: 0, fontSize: '11px', fontWeight: 800, letterSpacing: '-0.2px', display: 'flex', gap: '4px' }}>
                    <span style={{ color: '#f8fafc' }}>My</span>
                    <span style={{ color: '#fbbf24' }}>Room</span>
                </h3>
                <div className="header-actions">
                    <button
                        className={`icon-action-btn ${activeWidgets?.indicators ? 'active' : ''}`}
                        onClick={() => toggleWidget('indicators')}
                        title="Indicators"
                    >
                        📊
                    </button>
                    <button
                        className={`icon-action-btn ${activeWidgets?.manage ? 'active' : ''}`}
                        onClick={() => toggleWidget('manage')}
                        title="Manage Symbols"
                    >
                        ⚙️
                    </button>
                    <button className="icon-action-btn close-sidebar-btn" onClick={onClose} title="Minimize">−</button>
                </div>
            </div>

            <div className="sidebar-scroll-area">
                <SignalMatrixList
                    symbols={trackedSymbols}
                    onSymbolSelect={onSymbolSelect}
                    selectedSymbol={selectedSymbol}
                    symbolPrices={symbolPrices}
                />
            </div>
        </aside>
    );
}
