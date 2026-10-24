// @vitest-environment jsdom
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { router as appRouter } from '../router';
import { Card, ListRow, RowList } from './ListRow';

// One row style for items, sales and people (#520): title, one muted line, the amount on the right, the whole row the target.

afterEach(cleanup);

function show(ui: ReactElement) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(<RouterContextProvider router={router}>{ui}</RouterContextProvider>);
}

describe('ListRow and Card', () => {
  it('draws a titled card holding a named list of rows', () => {
    show(
      <Card title="Today's sales">
        <RowList label="Sales">
          <ListRow title="Sale S-000101" meta="10:42, Sample Buyer" amount="UGX 12,500" />
        </RowList>
      </Card>,
    );
    expect(screen.getByRole('heading', { name: "Today's sales" }).closest('.ui-card')).toBeTruthy();
    const list = screen.getByRole('list', { name: 'Sales' });
    const row = list.querySelector('.list-row')!;
    expect([...row.children].map((c) => c.className)).toEqual(['list-row-text', 'list-row-amount']);
    expect(row.querySelector('.list-row-title')?.textContent).toBe('Sale S-000101');
    expect(row.querySelector('.list-row-meta')?.textContent).toBe('10:42, Sample Buyer');
    expect(row.querySelector('.list-row-amount')?.textContent).toBe('UGX 12,500');
    expect(row.tagName).toBe('DIV');
  });

  it('makes the whole row one link with a chevron when it opens a page', () => {
    show(
      <RowList>
        <ListRow title="Sample soap" meta="12 left" amount="UGX 3,000" to="/staff/retail/stock" />
      </RowList>,
    );
    const link = screen.getByRole('link');
    expect(link.className).toBe('list-row');
    expect(link.getAttribute('href')).toBe('/staff/retail/stock');
    expect(link.textContent).toBe('Sample soap12 leftUGX 3,000');
    expect(link.querySelector('.list-row-chevron svg')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('makes the whole row one button when it acts, and the chevron can be left off', () => {
    const open = vi.fn();
    show(
      <RowList>
        <ListRow title="Sample Person" meta="Seller, Main shop" onClick={open} chevron={false} />
      </RowList>,
    );
    const button = screen.getByRole('button', { name: /Sample Person/ });
    expect(button.getAttribute('type')).toBe('button');
    expect(button.querySelector('.list-row-chevron')).toBeNull();
    fireEvent.click(button);
    expect(open).toHaveBeenCalledOnce();
  });
});
