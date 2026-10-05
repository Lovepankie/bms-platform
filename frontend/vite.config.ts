/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';

// One PWA with a staff area and a member area, split by route and lazily loaded (ADR-009).
// In development the API runs on :8080 (make dev); the dev server proxies to it so the app is
// same-origin, as it is behind the reverse proxy on staging and production.
const api = process.env.VITE_API_PROXY ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      manifest: {
        name: 'BMS Platform',
        short_name: 'BMS',
        description: 'Business management for lenders and their members',
        start_url: '/',
        display: 'standalone',
        background_color: '#ffffff',
        theme_color: '#0d5c75',
        icons: [{ src: '/icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' }],
      },
      workbox: {
        // Staff data is never cached for offline use; API calls always go to the network
        // (ADR-009). Member read caches arrive with the member area.
        navigateFallbackDenylist: [/^\/api\//, /^\/healthz/, /^\/readyz/, /^\/version/],
      },
    }),
  ],
  server: {
    port: 5173,
    proxy: {
      '/api': api,
      '/healthz': api,
      '/readyz': api,
      '/version': api,
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
  },
});
