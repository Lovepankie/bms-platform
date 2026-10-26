/// <reference types="vitest/config" />
import react from '@vitejs/plugin-react';
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { defineConfig, type Plugin } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';

// One PWA with a staff area and a member area, split by route and lazily loaded (ADR-009).
// In development the API runs on :8080 (make dev); the dev server proxies to it so the app is
// same-origin, as it is behind the reverse proxy on staging and production.
const api = process.env.VITE_API_PROXY ?? 'http://localhost:8080';

// The help corpus (issue #598, ADR-095): the articles in src/help/articles ship inside the bundle as `virtual:help-corpus`, with
// their revision, the first 16 hex digits of the SHA-256 of every file's name and raw bytes in name order (.gitattributes keeps
// them LF on every checkout, so #20 on the server gets the same revision). A changed, added or removed
// article changes the revision, which #20 puts in its answer cache key.
const HELP_DIR = fileURLToPath(new URL('./src/help/articles/', import.meta.url));
const HELP_ID = 'virtual:help-corpus';

function helpCorpus(): Plugin {
  return {
    name: 'bms-help-corpus',
    resolveId: (id) => (id === HELP_ID ? `\0${HELP_ID}` : undefined),
    // An article added or removed while the dev server runs is picked up; a changed one is watched by load below.
    configureServer(server) {
      server.watcher.add(HELP_DIR);
      const changed = (file: string) => {
        if (!file.startsWith(HELP_DIR)) return;
        const corpus = server.moduleGraph.getModuleById(`\0${HELP_ID}`);
        if (corpus) server.moduleGraph.invalidateModule(corpus);
        server.ws.send({ type: 'full-reload' });
      };
      server.watcher.on('add', changed);
      server.watcher.on('unlink', changed);
    },
    load(id) {
      if (id !== `\0${HELP_ID}`) return undefined;
      const names = readdirSync(HELP_DIR).filter((n) => n.endsWith('.md')).sort();
      const hash = createHash('sha256');
      const sources: Record<string, string> = {};
      for (const name of names) {
        const bytes = readFileSync(HELP_DIR + name);
        this.addWatchFile(HELP_DIR + name);
        hash.update(`${name}\0`);
        hash.update(bytes);
        hash.update('\0');
        sources[name.replace(/\.md$/, '')] = bytes.toString('utf8');
      }
      return `export const sources = ${JSON.stringify(sources)};\nexport const revision = ${JSON.stringify(hash.digest('hex').slice(0, 16))};\n`;
    },
  };
}

export default defineConfig({
  plugins: [
    react(),
    helpCorpus(),
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
        // The pictures of the help articles (issue #598), so How do I...? works offline once the app is installed.
        'help/*.webp',
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
