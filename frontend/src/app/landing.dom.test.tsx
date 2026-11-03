// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { PlatformLanding } from './landing';
import { router as appRouter } from './router';

// The platform host's landing page (issue #155): the buttons are for a new business and for staff who need their sign-in page;
// operator sign-in is a quiet link at the foot of the page, never one of the main buttons.

function show() {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <PlatformLanding />
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

afterEach(cleanup);

describe('the platform landing page (#155)', () => {
  it('offers Apply to start and Find your sign-in page as the main buttons, and no operator button', () => {
    const { container } = show();
    const actions = container.querySelector('.landing-actions') as HTMLElement;
    const buttons = within(actions).getAllByRole('link').map((a) => a.textContent);
    expect(buttons).toEqual(['Apply to start', 'Staff of a business? Find your sign-in page']);
    expect(within(actions).getByRole('link', { name: /Find your sign-in page/ }).getAttribute('href')).toBe('/find-sign-in');
    expect(within(actions).queryByText(/operator/i)).toBeNull();
  });

  it('keeps Operator sign-in as a quiet text link after the cards', () => {
    const { container } = show();
    const link = screen.getByRole('link', { name: 'Operator sign-in' });
    expect(link.getAttribute('href')).toBe('/platform/sign-in');
    expect(link.className).not.toMatch(/btn/);
    expect(link.closest('.landing-quiet')).not.toBeNull();
    const modules = container.querySelector('.landing-modules') as HTMLElement;
    expect(modules.compareDocumentPosition(link) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });
});
