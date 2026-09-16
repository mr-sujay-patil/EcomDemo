import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The dev server proxies /api to the Spring Boot application.
//
// This is what keeps the two sides genuinely separate: the browser only ever talks to
// localhost:5173, so the request is same-origin and no CORS configuration is needed on the
// backend. Nothing in src/main/java has to know this frontend exists.
//
// Point it somewhere else with:  VITE_API_TARGET=http://localhost:9090 npm run dev
const target = process.env.VITE_API_TARGET ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target,
        changeOrigin: true,
      },
    },
  },
});
