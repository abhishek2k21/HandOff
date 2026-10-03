import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vitest/config'

const backendPort = process.env.BACKEND_PORT || '8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // Proxy: when the frontend calls /api or /ws, Vite forwards the
    // request to the Spring Boot backend running on BACKEND_PORT (default 8080).
    // This avoids CORS issues during development.
    proxy: {
      '/api': {
        target: `http://localhost:${backendPort}`,
        changeOrigin: true,
      },
      '/ws': {
        target: `ws://localhost:${backendPort}`,
        ws: true,
      },
    },
  },
  test: {
    // Vitest configuration — runs in a simulated browser (jsdom)
    // so we can test React components without a real browser.
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
  },
})
