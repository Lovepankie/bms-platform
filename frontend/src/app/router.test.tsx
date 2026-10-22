import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { router as appRouter, showsRefresh } from './router';

// The real composition of the header (issue #430): the refresh button is drawn by the app's own root layout on the screens with
// data, and not on the public pages or the operator's sign-in and setup forms. Deleting it from the layout fails this.

async function header(path: string): Promise<string> {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [path] }) });
  await router.load();
  return renderToString(
    <QueryClientProvider client={new QueryClient()}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

describe('where the header shows the refresh button', () => {
  it('draws it from the root layout on the staff, member and platform data screens', async () => {
    for (const path of ['/staff', '/staff/retail/sales', '/member', '/platform']) {
      expect(showsRefresh(path), path).toBe(true);
      expect(await header(path), path).toContain('data-tour="refresh"');
    }
  });

  it('leaves it off public pages and the operator sign-in and setup forms', async () => {
    for (const path of ['/', '/sign-in', '/sign-up', '/platform/sign-in', '/platform/setup']) {
      expect(showsRefresh(path), path).toBe(false);
      expect(await header(path), path).not.toContain('data-tour="refresh"');
    }
  });
});
