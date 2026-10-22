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
      // The app is installable (ADR-050). Its manifest is the business's own, served by the API at
      // /api/v1/branding/app/manifest.webmanifest with icons drawn from the business's logo (ADR-066), and index.html
      // links it, so the plugin writes and injects no manifest of its own. The BMS icons stay here as the fallback the
      // API points to for a business with no logo.
      includeAssets: [
        'icon.svg',
        'icons/apple-touch-icon.png',
        'icons/icon-192.png',
        'icons/icon-512.png',
        'icons/icon-maskable-192.png',
        'icons/icon-maskable-512.png',
        'push-sw.js',
        'theme-init.js',
      ],
      manifest: false,
      workbox: {
        // The push and notification-click handlers (ADR-050) live in public/push-sw.js and run inside the generated worker.
        importScripts: ['push-sw.js'],
        // API calls always go to the network; the service worker caches no staff data (ADR-009). Retail sales offline
        // keep their own copy in IndexedDB (ADR-067, frontend/src/offline/). Member read caches arrive with the member area.
        // The manifest and icons of the business (ADR-066) are under /api/ too, so they always come from the network.
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
