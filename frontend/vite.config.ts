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
      // The app is installable (ADR-050): a name, an id that never changes, a start page, standalone display, icons of 192 and
      // 512 pixels and a maskable one, so the phone's home screen, the app list and the task switcher show it as an app.
      includeAssets: ['icon.svg', 'icons/apple-touch-icon.png', 'push-sw.js', 'theme-init.js'],
      manifest: {
        id: '/staff',
        name: 'BMS',
        short_name: 'BMS',
        description: 'Business management for lenders, retailers and their members',
        lang: 'en',
        start_url: '/staff',
        scope: '/',
        display: 'standalone',
        background_color: '#ffffff',
        theme_color: '#0077b6',
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: '/icons/icon-maskable-192.png', sizes: '192x192', type: 'image/png', purpose: 'maskable' },
          { src: '/icons/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
          { src: '/icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any' },
        ],
      },
      workbox: {
        // The push and notification-click handlers (ADR-050) live in public/push-sw.js and run inside the generated worker.
        importScripts: ['push-sw.js'],
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
