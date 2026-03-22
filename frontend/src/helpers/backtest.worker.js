import { runScalpBacktest } from './BacktestProcessor';

self.onmessage = (e) => {
    const { data, quantity, target, stop, costPerTrade, slippage } = e.data;
    const results = runScalpBacktest(data, quantity, target, stop, false, costPerTrade, slippage);
    self.postMessage(results);
};
