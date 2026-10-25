// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Closure, Proof } from '../../../api/closure';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// Close the business (issue #571, ADR-090, FR-TEN-09): the Owner sees what closing means, then a review of exactly what happens
// and their own proof before anything is sent; an administrator sees the state and no button; while a request is open the Owner
// cancels it. Fabricated business, people and password.

let view: Closure = { status: 'none', cooling_off_days: 30, can_change: true };
const asked: Proof[] = [];
let cancels = 0;

vi.mock('../../../api/closure', async (original) => ({
  ...(await original<typeof import('../../../api/closure')>()),
  closureView: () => Promise.resolve(view),
  requestClosure: (proof: Proof) => {
    asked.push(proof);
    view = { status: 'requested', id: 'c-1', requested_by_name: 'Test Owner 01', remove_after: '2099-12-01', cooling_off_days: 30, can_change: true };
    return Promise.resolve(view);
  },
  cancelClosure: () => {
    cancels += 1;
    view = { status: 'none', cooling_off_days: 30, can_change: true };
    return Promise.resolve(view);
  },
}));

const { CloseScreen, whatHappens } = await import('./page');

function show(me: Me) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/setup/close'] }) });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <CloseScreen />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

beforeEach(() => {
  view = { status: 'none', cooling_off_days: 30, can_change: true };
  asked.length = 0;
  cancels = 0;
});

afterEach(() => cleanup());

describe('closing the business', () => {
  it('shows the review of what happens and sends nothing without the Owner\'s password', async () => {
    show({ ...mockMe('admin'), mfa_enabled: false } as Me);
    fireEvent.click(await screen.findByRole('button', { name: 'See what happens' }));
    expect(screen.getByRole('heading', { name: 'Check before you close' })).toBeTruthy();
    for (const line of whatHappens(30)) expect(screen.getByText(line)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Close the business' }));
    expect(asked).toHaveLength(0);
    fireEvent.change(screen.getByLabelText(/Your own password/), { target: { value: 'Fabricated-Pass-2026' } });
    fireEvent.click(screen.getByRole('button', { name: 'Close the business' }));
    await waitFor(() => expect(asked).toEqual([{ password: 'Fabricated-Pass-2026' }]));
    expect(await screen.findByText(/Rincol Tech may remove it from 1 December 2099/)).toBeTruthy();
  });

  it('asks for the authenticator code when the Owner has two-step sign-in', async () => {
    show({ ...mockMe('admin'), mfa_enabled: true } as Me);
    fireEvent.click(await screen.findByRole('button', { name: 'See what happens' }));
    fireEvent.change(screen.getByLabelText(/Code from your authenticator app/), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Close the business' }));
    await waitFor(() => expect(asked).toEqual([{ totp_code: '123456' }]));
  });

  it('lets the Owner cancel an open request', async () => {
    view = { status: 'requested', id: 'c-1', requested_by_name: 'Test Owner 01', remove_after: '2099-12-01', cooling_off_days: 30, can_change: true };
    show(mockMe('admin'));
    expect(await screen.findByText(/It is read only now/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel the closing' }));
    await waitFor(() => expect(cancels).toBe(1));
  });

  it('shows an administrator the state and no button', async () => {
    view = { status: 'requested', id: 'c-1', requested_by_name: 'Test Owner 01', remove_after: '2099-12-01', cooling_off_days: 30, can_change: false };
    show(mockMe('admin'));
    expect(await screen.findByText('Only the owner of the business can cancel the closing.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Cancel the closing' })).toBeNull();
  });

  it('tells an administrator that only the owner closes the business', async () => {
    view = { status: 'none', cooling_off_days: 30, can_change: false };
    show(mockMe('admin'));
    expect(await screen.findByText(/Only the owner of the business can close it/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'See what happens' })).toBeNull();
  });
});
