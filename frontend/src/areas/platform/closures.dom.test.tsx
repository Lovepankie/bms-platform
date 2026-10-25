// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PlatformClosure } from '../../api/closure';
import { router as appRouter } from '../../app/router';

// The operator's two lists of issue #571 (ADR-090): businesses closing, removed only once the waiting period is over and only with
// the operator's own code and a reason; and businesses with no owner, where Set as the owner needs the same. Invented data only.

let closures: PlatformClosure[] = [];
const removed: { id: string; code: string; reason: string }[] = [];
const owners: { tenant: string; user: string; code: string; reason: string }[] = [];

vi.mock('../../api/closure', async (original) => ({
  ...(await original<typeof import('../../api/closure')>()),
  platformClosures: () => Promise.resolve(closures),
  removeClosedBusiness: (id: string, code: string, reason: string) => {
    removed.push({ id, code, reason });
    return Promise.resolve({ closure_id: id, keep_until: '2032-06-30', erased: { core: 5 } });
  },
  tenantsWithoutOwner: () =>
    Promise.resolve([
      { tenant_id: 't-4', slug: 'test-shop-four', name: 'Test Shop Four', status: 'active', admins: [{ id: 'u-4', full_name: 'Test Admin 04', email: 'admin04@example.test' }] },
      { tenant_id: 't-5', slug: 'test-shop-five', name: 'Test Shop Five', status: 'active', admins: [] },
    ]),
}));

vi.mock('../../api/operator', async (original) => ({
  ...(await original<typeof import('../../api/operator')>()),
  makeOwner: (tenant: string, user: string, code: string, reason: string) => {
    owners.push({ tenant, user, code, reason });
    return Promise.resolve();
  },
}));

const { ClosuresPage, closureLine } = await import('./closures');
const { NoOwnerPage } = await import('./no-owner');

function show(node: ReactNode) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/platform'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{node}</QueryClientProvider>
    </RouterContextProvider>,
  );
}

const waiting: PlatformClosure = {
  id: 'c-1',
  tenant_id: 't-1',
  tenant_name: 'Test Shop One',
  status: 'requested',
  requested_at: '2026-10-20T08:00:00Z',
  requested_by_name: 'Test Owner 01',
  remove_after: '2026-11-19',
  removable: false,
};

beforeEach(() => {
  closures = [];
  removed.length = 0;
  owners.length = 0;
});

afterEach(() => cleanup());

describe('businesses closing', () => {
  it('offers no removal during the waiting period', async () => {
    closures = [waiting];
    show(<ClosuresPage />);
    expect(await screen.findByText(/It can be removed from 19 Nov 2026/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Remove the business' })).toBeNull();
  });

  it('removes only with the code and a reason, after saying what is deleted', async () => {
    closures = [{ ...waiting, removable: true, remove_after: '2026-10-01' }];
    show(<ClosuresPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Remove the business' }));
    expect(screen.getByText(/This cannot be undone/)).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Why (kept with the change)'), { target: { value: 'Test: the owner asked and the wait is over' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove it' }));
    expect(await screen.findByText('Type the 6 digit code from your own authenticator app.')).toBeTruthy();
    expect(removed).toHaveLength(0);
    // A press that sent nothing holds the button for one more task, as a second tap of a person would come later.
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));
    fireEvent.change(screen.getByLabelText('Code from your own authenticator app'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove it' }));
    await waitFor(() => expect(removed).toEqual([{ id: 'c-1', code: '123456', reason: 'Test: the owner asked and the wait is over' }]));
    expect(await screen.findByText('Removed. Money records are kept until 30 Jun 2032.')).toBeTruthy();
  });

  it('words a removed and a cancelled request', () => {
    expect(closureLine({ ...waiting, status: 'cancelled', cancelled_at: '2026-10-21T08:00:00Z' })).toMatch(/^Cancelled by the owner on/);
    expect(closureLine({ ...waiting, status: 'removed', removed_at: '2026-11-20T08:00:00Z', keep_until: '2032-06-30' })).toMatch(/Money records kept until 30 Jun 2032/);
  });
});

describe('businesses with no owner', () => {
  it('sets an administrator as the owner with the code and a reason', async () => {
    show(<NoOwnerPage />);
    expect(await screen.findByText('Test Admin 04')).toBeTruthy();
    expect(screen.getByText(/No active administrator/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Set as the owner' }));
    fireEvent.change(screen.getByLabelText('Code from your own authenticator app'), { target: { value: '654321' } });
    fireEvent.change(screen.getByLabelText('Why (kept with the change)'), { target: { value: 'Test: the owner phoned us' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, make them the owner' }));
    await waitFor(() => expect(owners).toEqual([{ tenant: 't-4', user: 'u-4', code: '654321', reason: 'Test: the owner phoned us' }]));
    expect(await screen.findByText('Test Admin 04 is now the owner of Test Shop Four.')).toBeTruthy();
  });
});
