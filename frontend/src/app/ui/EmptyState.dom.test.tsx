// @vitest-environment jsdom
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { router as appRouter } from '../router';
import { EmptyState } from './EmptyState';

// The one empty list (#520): one line and at most one action.

afterEach(cleanup);

function show(ui: ReactElement) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(<RouterContextProvider router={router}>{ui}</RouterContextProvider>);
}

describe('EmptyState', () => {
  it('says what is missing in one line, with one link that fills it', () => {
    show(<EmptyState text="No sales today yet." action={{ label: 'Record a sale', to: '/staff/retail/sale' }} />);
    const box = document.querySelector('.empty-line')!;
    expect(box.querySelectorAll('p')).toHaveLength(1);
    expect(screen.getByText('No sales today yet.')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Record a sale' }).getAttribute('href')).toBe('/staff/retail/sale');
    expect(box.querySelectorAll('a, button')).toHaveLength(1);
  });

  it('takes a button action, or none', () => {
    const add = vi.fn();
    show(<EmptyState text="No items match." action={{ label: 'Clear the search', onClick: add }} />);
    fireEvent.click(screen.getByRole('button', { name: 'Clear the search' }));
    expect(add).toHaveBeenCalledOnce();
    cleanup();
    show(<EmptyState text="Nothing waiting." />);
    expect(document.querySelectorAll('.empty-line a, .empty-line button')).toHaveLength(0);
  });
});
