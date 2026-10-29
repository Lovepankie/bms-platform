// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useEffect } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, type Me } from '../../api/client';
import { router as appRouter } from '../../app/router';
import { ALL_BRANCHES } from '../../auth/branch';
import { setAccessToken } from '../../auth/session';
import { ShopHere, switchedTo, useBranchSwitch } from './branch-picker';
import { TOAST_MS, useToast } from './retail/toast';

// Issue #680: the staff layout names the shop the page works in above every page, phone and laptop alike, and one tap there
// switches shop; every switch says "You are now in <shop>." in the house toast, which replaces any toast showing. Every name below
// is invented.

const me = {
  user_id: 'u-self',
  full_name: 'Test Person One',
  kind: 'staff',
  all_branches: true,
  modules: ['retail'],
  permissions: [],
  tours: { 'tours-off': { status: 'dismissed', version: 1 } },
  branches: [
    { id: 'b-one', code: 'HQ', name: 'Test Shop One', stocks_goods: true, sells_goods: true },
    { id: 'b-two', code: 'SHOP2', name: 'Test Shop Two', stocks_goods: true, sells_goods: true },
  ],
} as unknown as Me;

const one = { ...me, all_branches: false, branches: [me.branches?.[0]] } as unknown as Me;

/** A toast of the screen's own, shown once on opening, as a save just before a switch would leave it. */
function ScreenToastNow() {
  const { show, toast } = useToast();
  useEffect(() => show('Stock corrected.', 'success'), [show]);
  return <>{toast}</>;
}

function Harness({ who = me, branch = 'b-one', choose, screenToast = false }: { who?: Me; branch?: string; choose: (selection: string) => boolean; screenToast?: boolean }) {
  const { choose: switchTo, toast } = useBranchSwitch(who, branch, choose);
  return (
    <>
      <ShopHere me={who} branch={branch} onChoose={switchTo} />
      {screenToast && <ScreenToastNow />}
      {toast}
    </>
  );
}

/** The name the label shows, without the options of its select. */
const shown = () => screen.getByTestId('shop-here').querySelector('.shop-here-name')?.textContent;

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  setAccessToken(null);
  window.localStorage.clear();
});

describe('the shop above the page (issue #680)', () => {
  it('names the shop in use and switches shop in one tap', () => {
    const choose = vi.fn(() => true);
    render(<Harness choose={choose} />);
    expect(shown()).toBe('Test Shop One (HQ)');
    const select = screen.getByRole('combobox', { name: 'Shop: Test Shop One (HQ). Switch shop' });
    expect([...select.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['All branches', 'Test Shop One (HQ)', 'Test Shop Two (SHOP2)']);
    fireEvent.change(select, { target: { value: 'b-two' } });
    expect(choose).toHaveBeenCalledWith('b-two');
  });

  it('shows a lone shop as plain words with nothing to switch, All branches by name, and says branch without retail', () => {
    render(<Harness who={one} choose={() => true} />);
    expect(screen.getByTestId('shop-here').textContent).toBe('Test Shop One (HQ)');
    expect(screen.queryByRole('combobox')).toBeNull();
    cleanup();
    render(<Harness branch={ALL_BRANCHES} choose={() => true} />);
    expect(shown()).toBe('All branches');
    cleanup();
    render(<Harness who={{ ...me, modules: ['lending'] } as unknown as Me} choose={() => true} />);
    expect(screen.getByRole('combobox', { name: 'Branch: Test Shop One (HQ). Switch branch' })).toBeTruthy();
  });

  it('is drawn once by the staff layout, above a retail page and any other staff page', async () => {
    setAccessToken('test-token-person-one');
    vi.spyOn(api, 'GET').mockImplementation(((url: string) =>
      url === '/api/v1/me' ? Promise.resolve({ data: me, response: new Response() }) : new Promise(() => undefined)) as never);
    for (const path of ['/staff/retail/stock', '/staff/notifications']) {
      const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [path] }) });
      render(
        <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
          <RouterProvider router={router} />
        </QueryClientProvider>,
      );
      const label = await screen.findByTestId('shop-here', {}, { timeout: 10_000 });
      expect(screen.getAllByTestId('shop-here'), path).toHaveLength(1);
      expect(label.querySelector('.shop-here-name')?.textContent, path).toBe('Test Shop One (HQ)');
      cleanup();
    }
  }, 30_000);
});

describe('the toast on a change of shop (issue #680)', () => {
  it('says where the person now is, politely, in the dark neutral toast, for four seconds', () => {
    vi.useFakeTimers();
    render(<Harness choose={() => true} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'b-two' } });
    const toast = screen.getByRole('status');
    expect(toast.textContent).toBe('You are now in Test Shop Two (SHOP2).');
    expect(toast.getAttribute('aria-live')).toBe('polite');
    expect(toast.className).toBe('toast toast-info');
    act(() => void vi.advanceTimersByTime(TOAST_MS));
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('replaces a toast the screen is showing, so one toast shows at a time', () => {
    render(<Harness choose={() => true} screenToast />);
    expect(screen.getByRole('status').textContent).toBe('Stock corrected.');
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'b-two' } });
    expect(screen.getAllByRole('status').map((s) => s.textContent)).toEqual(['You are now in Test Shop Two (SHOP2).']);
  });

  it('says nothing when a screen holds the branch, or the same shop is chosen again', () => {
    render(<Harness choose={() => false} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'b-two' } });
    expect(screen.queryByRole('status')).toBeNull();
    cleanup();
    const choose = vi.fn(() => true);
    render(<Harness choose={choose} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'b-one' } });
    expect(choose).not.toHaveBeenCalled();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('words All branches as a view, not a place', () => {
    expect(switchedTo(me, ALL_BRANCHES)).toBe('You now see all branches.');
    expect(switchedTo(me, 'b-two')).toBe('You are now in Test Shop Two (SHOP2).');
  });
});
