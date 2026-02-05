import React, { useState, useEffect } from 'react';
import ETradingChart from './components/ETradingChart';
import SymbolList from './components/SymbolList';

import './App.css';

function App() {
  const [selectedSymbol, setSelectedSymbol] = useState('SENSEX2620581600CE');

  return (
    <div className="app-container">

      <div className="main-content">
        <aside className="sidebar">
          <SymbolList
            onSymbolSelect={setSelectedSymbol}
            selectedSymbol={selectedSymbol}
          />
        </aside>
        <section className="chart-section">
          <div className="charts-grid">
            {['1min', '3min', '5min', '15min'].map(tf => (
              <div key={tf} className="grid-chart-item">
                <ETradingChart
                  symbol={selectedSymbol}
                  initialInterval={tf}
                  isGrid={true}
                />
              </div>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}

export default App;
