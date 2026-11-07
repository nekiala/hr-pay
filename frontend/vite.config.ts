import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In development the API runs on :8080 (override with API_URL); the proxy keeps cookies same-origin.
const api = process.env.API_URL ?? 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': api,
      '/oauth2': api,
      '/login/oauth2': api,
    },
  },
  build: { outDir: 'dist' },
})
