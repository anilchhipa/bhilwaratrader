/**
 * Standard Theme Colors and Visibility Config for Canvas Charts.
 */
export const COLORS = Object.freeze({
    bg: '#000000',
    bull: '#10b981',
    bear: '#f43f5e',
    text: '#f8fafc',
    grid: 'rgba(30, 41, 59, 0.4)',
    crosshair: 'rgba(255, 255, 255, 0.3)',
    emas: ['#ffffff', '#ef4444', '#3b82f6', '#22c55e'],
    stBull: 'rgba(0, 110, 255, 0.45)',
    stBear: 'rgba(255, 35, 35, 0.45)',
    st2: 'rgba(168, 85, 247, 0.15)'
});

export const DEFAULT_VISIBILITY = Object.freeze({
    EMA15: true,
    EMA15_5: true,
    EMA15_15: true,
    EMA15_15_15: true,
    CLOUD: true,
    CLOUD2: true,
    RENKO2: false,
    RENKO3: false,
    RENKO_CLOSE: false,
    ST_HA_CURRENT: false,
    ST_HA_HTF: false,
    ST_HA_FLIPS: false
});
