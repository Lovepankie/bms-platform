// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

// Issue #680: the real staff layout on the retail mock draws the shop label once, above the page's content, on a retail page and
// on pages outside retail alike, so no page can leave it out. The mock switch is read as the modules load, so it is set first.

vi.hoisted(() => vi.stubEnv('VITE_RETAIL_MOCK', '1'));
const { router: appRouter } = await import('../../app/router');

afterEach(cleanup);

describe('the shop on every staff page (issue #680)', () => {
  it.each(['/staff/retail/stock', '/staff/more', '/staff/approvals'])('draws the shop once above the content of %s', async (path) => {
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [path] }) });
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    );
    const label = await screen.findByTestId('shop-here', {}, { timeout: 10_000 });
    await waitFor(() => expect(document.querySelector('.nav-layout-main main')).not.toBeNull(), { timeout: 10_000 });
    expect(screen.getAllByTestId('shop-here')).toHaveLength(1);
    expect(label.parentElement?.classList.contains('nav-layout-main')).toBe(true);
    const content = document.querySelector('.nav-layout-main main') as Node;
    expect(label.compareDocumentPosition(content) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  }, 30_000);
});
