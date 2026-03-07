export const formatSymbol = (symbol) => {
    if (!symbol) return '';

    // Precise regex for Zerodha Weekly/Monthly Options:
    // [Underlying][YY][M/Month][DD][Strike][CE/PE]
    // Example: SENSEX2630582400CE
    const optRegex = /^([A-Z]+)(\d{2})([1-9OND])(\d{2})(\d+)([CP]E)$/;
    const optMatch = symbol.match(optRegex);

    if (optMatch) {
        const months = {
            '1': 'Jan', '2': 'Feb', '3': 'Mar', '4': 'Apr', '5': 'May', '6': 'Jun',
            '7': 'Jul', '8': 'Aug', '9': 'Sep', 'O': 'Oct', 'N': 'Nov', 'D': 'Dec'
        };
        const name = optMatch[1];
        const month = months[optMatch[3]];
        const day = optMatch[4];
        const strike = optMatch[5];
        const type = optMatch[6];

        return `${name} ${day}-${month} ${strike} ${type}`;
    }

    // Fallback for simple Futures or other formats
    if (symbol.endsWith('FUT')) {
        return symbol.replace('FUT', ' FUT');
    }

    return symbol;
};
