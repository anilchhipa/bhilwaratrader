export const API_BASE = window.location.origin.includes('localhost') ? 'http://localhost:8085' : '';
export const WS_BASE = (window.location.origin.includes('localhost') ? 'http://localhost:8085' : window.location.origin) + '/ws-trading';
