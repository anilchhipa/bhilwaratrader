import React from 'react';

const Header = () => {
    return (
        <header className="header">
            <div className="logo">
                <span className="logo-icon">⚡</span>
                <h1>Antigravity Algo Terminal</h1>
            </div>
            <div className="user-profile">
                <span className="status-indicator"></span>
                <span className="client-id">A436240</span>
            </div>
        </header>
    );
};

export default Header;
