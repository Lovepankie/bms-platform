import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider } from '@tanstack/react-router';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { registerSW } from 'virtual:pwa-register';
import { AutoUpdateLayer, setWorkerRegistration, startAutoUpdate, updater } from './app/auto-update';
import { router } from './app/router';
import { reloadOnce } from './app/stale-app';
import './app/theme.css';
import './app/ui/index.css';

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1 } } });

// A new worker that has taken over replaces the old app at a safe moment, never by an immediate reload (#576, ADR-088): the
// plugin's own answer to a new worker is location.reload(), which could land in the middle of a sale.
registerSW({
  immediate: true,
  onNeedReload: () => updater.workerChanged(),
  onRegisteredSW: (_url, registration) => setWorkerRegistration(registration),
});
startAutoUpdate(router);

// Vite reports a screen file it could not preload (a deploy replaced it): load the new app once.
window.addEventListener('vite:preloadError', (event) => {
  if (reloadOnce()) event.preventDefault();
});

const root = document.getElementById('root');
if (!root) throw new Error('missing #root');

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
      <AutoUpdateLayer />
    </QueryClientProvider>
  </StrictMode>,
);
