const fs = require('fs');
const content = fs.readFileSync('/home/anil-chhipa/Desktop/Algo/frontend/src/components/ETradingChart.jsx', 'utf8');
const lines = content.split('\n');
let stack = [];
lines.forEach((line, i) => {
    const openers = line.match(/<div(?!\s*\/>)/g);
    const closers = line.match(/<\/div>/g);
    if (openers) openers.forEach(() => stack.push(i + 1));
    if (closers) closers.forEach(() => {
        if (stack.length === 0) console.log(`Extra closer at line ${i + 1}`);
        else stack.pop();
    });
});
if (stack.length > 0) console.log(`Unclosed divs started at lines: ${stack.join(', ')}`);
