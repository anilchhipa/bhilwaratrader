export const formatSymbol = (symbol) => {
    if (!symbol) return '';

    // Regex 1: Weekly Options [SENSEX2630582400CE] -> SENSEX + 26 + 3 (Mar) + 05 (Day) + 82400 + CE
    const weeklyRegex = /^([A-Z]+)(\d{2})([1-9OND])(\d{2})(\d+)([CP]E)$/;
    // Regex 2: Monthly Options [SENSEX26MAR75600PE] -> SENSEX + 26 + MAR + 75600 + PE
    const monthlyRegex = /^([A-Z]+)(\d{2})([A-Z]{3})(\d+)([CP]E)$/;

    const match = symbol.match(weeklyRegex) || symbol.match(monthlyRegex);

    if (match) {
        // The strike is always the second to last group, CE/PE is the last one
        const strike = match[match.length - 2];
        const typeLetter = match[match.length - 1];
        const typeFull = typeLetter === 'CE' ? 'CALL' : 'PUT';

        return `${strike} ${typeFull}`;
    }

    // Fallback for simple Futures or other formats
    if (symbol.endsWith('FUT')) {
        return symbol.replace('FUT', ' FUT');
    }

    return symbol;
};
