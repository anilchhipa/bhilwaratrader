import React from 'react';

/**
 * Enterprise React Error Boundary to catch render failures in Chart or Widgets
 * without crashing the parent dashboard.
 */
class ErrorBoundary extends React.Component {
    constructor(props) {
        super(props);
        this.state = { hasError: false, error: null, errorInfo: null };
    }

    static getDerivedStateFromError(error) {
        return { hasError: true, error };
    }

    componentDidCatch(error, errorInfo) {
        console.error('[ErrorBoundary caught error]:', error, errorInfo);
        this.setState({ errorInfo });
    }

    handleReset = () => {
        this.setState({ hasError: false, error: null, errorInfo: null });
        if (this.props.onReset) {
            this.props.onReset();
        }
    };

    render() {
        if (this.state.hasError) {
            return (
                <div style={{
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    justifyContent: 'center',
                    height: '100%',
                    width: '100%',
                    backgroundColor: '#090d16',
                    color: '#f8fafc',
                    padding: '20px',
                    borderRadius: '6px',
                    border: '1px solid rgba(239, 68, 68, 0.3)',
                    fontFamily: 'system-ui, sans-serif'
                }}>
                    <span style={{ fontSize: '24px', marginBottom: '8px' }}>⚠️</span>
                    <h4 style={{ margin: '0 0 8px 0', fontSize: '14px', color: '#f87171' }}>
                        {this.props.fallbackTitle || 'Component Render Error'}
                    </h4>
                    <p style={{
                        margin: '0 0 14px 0',
                        fontSize: '11px',
                        color: '#94a3b8',
                        textAlign: 'center',
                        maxWidth: '280px',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap'
                    }}>
                        {this.state.error?.message || 'An unexpected error occurred.'}
                    </p>
                    <button
                        onClick={this.handleReset}
                        style={{
                            background: '#1e293b',
                            color: '#38bdf8',
                            border: '1px solid #38bdf8',
                            borderRadius: '4px',
                            padding: '5px 12px',
                            fontSize: '11px',
                            cursor: 'pointer',
                            fontWeight: 600
                        }}
                    >
                        🔄 Reload Chart
                    </button>
                </div>
            );
        }

        return this.props.children;
    }
}

export default ErrorBoundary;
