import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  define: {
    // Polyfill `global` for @stomp/stompjs in both main thread and workers
    global: 'globalThis',
  },
  worker: {
    format: 'es', // Bundle SharedWorker as an ES module (required for @stomp/stompjs imports)
  },
  server: {
    host: true
  }
})

