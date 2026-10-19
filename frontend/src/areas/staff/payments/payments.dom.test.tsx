// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MerchantAccount } from '../../../api/payments';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// The "Receive payments" screen in a browser (extras slice A7, issue #233), with the API client replaced: the secret is a password
// field that is cleared after saving and never shown again; test connection appears once something is saved; a failed test says
// why in plain words; removing needs a second click; someone who may only read settings sees the status and no form. Every key
// and secret below is invented.

const api = { account: vi.fn(), save: vi.fn(), test: vi.fn(), remove: vi.fn() };

vi.mock('../../../api/payments', async (original) => {
  const real = await original<typeof import('../../../api/payments')>();
  const forward = (name: string) => (...args: unknown[]) => ((api as Record<string, (...a: unknown[]) => unknown>)[name] as (...a: unknown[]) => unknown)(...args);
  return { ...real, payments: new Proxy({}, { get: (_t, name: string) => forward(name) }) };
});

const { PaymentsError } = await import('../../../api/payments');
const { ReceivePaymentsPage } = await import('./page');

const none: MerchantAccount = { status: 'not_connected', credentials_saved: false, key_hint: null, last_checked_at: null, failure_reason: null, host_ready: true };
const saved: MerchantAccount = { ...none, credentials_saved: true, key_hint: '0001' };
const verified: MerchantAccount = { ...saved, status: 'verified', last_checked_at: '2026-10-18T10:00:00Z' };
const failed: MerchantAccount = { ...saved, status: 'failed', last_checked_at: '2026-10-18T10:00:00Z', failure_reason: 'invalid_credentials' };

const ADMIN = ['core.settings.read', 'core.settings.manage', 'core.payment_account.manage'];

function shell(node: ReactElement, permissions: string[] = ADMIN) {
  const me = { ...mockMe('admin'), permissions };
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/payments'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

beforeEach(() => {
  api.account.mockResolvedValue(none);
});

afterEach(() => {
  cleanup();
  Object.values(api).forEach((m) => m.mockReset());
});

describe('connecting the account', () => {
  it('says plainly where the money goes and asks for the key and secret, the secret in a password field', async () => {
    shell(<ReceivePaymentsPage />);
    expect(await screen.findByText('Not connected')).toBeTruthy();
    expect(screen.getAllByText(/your own/).length).toBeGreaterThan(0);
    expect((screen.getByLabelText('Consumer secret') as HTMLInputElement).type).toBe('password');
    expect(screen.queryByRole('button', { name: 'Test connection' })).toBeNull();
    expect((screen.getByRole('button', { name: 'Save the key and secret' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('refuses a value with a space before anything is sent, then saves and clears the secret box', async () => {
    api.save.mockResolvedValue(saved);
    shell(<ReceivePaymentsPage />);
    await screen.findByText('Not connected');
    fireEvent.change(screen.getByLabelText('Consumer key'), { target: { value: 'invented-key-0001' } });
    fireEvent.change(screen.getByLabelText('Consumer secret'), { target: { value: 'has a space' } });
    expect(screen.getAllByText(/with no spaces/).length).toBeGreaterThan(0);
    expect((screen.getByRole('button', { name: 'Save the key and secret' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Consumer secret'), { target: { value: 'invented-secret-0001' } });
    expect((screen.getByRole('button', { name: 'Save the key and secret' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change((document.getElementById('receive-code') as HTMLInputElement), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the key and secret' }));
    await waitFor(() => expect(api.save).toHaveBeenCalledWith('invented-key-0001', 'invented-secret-0001', '123456'));
    // Saved: the key's last four show, the secret never does, and the test is now offered.
    expect(await screen.findByText(/Consumer key ending 0001/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Test connection' })).toBeTruthy();
    expect(document.body.innerHTML).not.toContain('invented-secret-0001');
    // Nothing typed stays in the boxes once sent.
    expect((screen.getByLabelText('Consumer secret') as HTMLInputElement).value).toBe('');
    expect(((document.getElementById('receive-code') as HTMLInputElement) as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText('Consumer secret') as HTMLInputElement).autocomplete).toBe('new-password');
    expect(((document.getElementById('receive-code') as HTMLInputElement) as HTMLInputElement).autocomplete).toBe('one-time-code');
  });

  it('shows a refusal from the server in words and keeps what was typed out of the page', async () => {
    api.save.mockRejectedValue(new PaymentsError('Paste the consumer key exactly as Pesapal shows it, with no spaces.', 422, 'validation_failed'));
    shell(<ReceivePaymentsPage />);
    await screen.findByText('Not connected');
    fireEvent.change(screen.getByLabelText('Consumer key'), { target: { value: 'invented-key-0001' } });
    fireEvent.change(screen.getByLabelText('Consumer secret'), { target: { value: 'invented-secret-0001' } });
    fireEvent.change((document.getElementById('receive-code') as HTMLInputElement), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save the key and secret' }));
    expect(await screen.findByText(/Paste the consumer key exactly/)).toBeTruthy();
  });
});

describe('testing the connection', () => {
  it('turns the status to connected and working when the test passes', async () => {
    api.account.mockResolvedValue(saved);
    api.test.mockResolvedValue(verified);
    shell(<ReceivePaymentsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Test connection' }));
    await waitFor(() => expect(api.test).toHaveBeenCalledTimes(1));
    expect(await screen.findByText('Connected and working')).toBeTruthy();
    expect(screen.getByText(/go straight into your own Pesapal account/)).toBeTruthy();
    expect(screen.getByText(/Last tested/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Test connection' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Test again' })).toBeTruthy();
  });

  it('says in plain words why a test failed, and lets the administrator try again', async () => {
    api.account.mockResolvedValue(saved);
    api.test.mockResolvedValue(failed);
    shell(<ReceivePaymentsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Test connection' }));
    expect(await screen.findByText('Not working')).toBeTruthy();
    expect(screen.getAllByText(/did not accept this consumer key and secret/).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Test connection' })).toBeTruthy();
  });

  it('shows an unreachable provider as a message and changes nothing', async () => {
    api.account.mockResolvedValue(verified);
    api.test.mockRejectedValue(new PaymentsError('Pesapal could not be reached just now. Nothing was changed. Try again in a moment.', 502, 'provider_unreachable'));
    shell(<ReceivePaymentsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Test again' }));
    expect(await screen.findByRole('alert')).toBeTruthy();
    expect(screen.getByText('Connected and working')).toBeTruthy();
  });

  it('says when the system has no payment provider set up and offers no test', async () => {
    api.account.mockResolvedValue({ ...saved, host_ready: false });
    shell(<ReceivePaymentsPage />);
    expect(await screen.findAllByText(/not set up on this system yet/)).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Test connection' }) as HTMLButtonElement).disabled).toBe(true);
  });
});

describe('removing the account', () => {
  it('needs a second click and says what stops', async () => {
    api.account.mockResolvedValue(verified);
    api.remove.mockResolvedValue(none);
    shell(<ReceivePaymentsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Remove the saved key and secret' }));
    expect(api.remove).not.toHaveBeenCalled();
    expect(screen.getByText(/Collecting stops until you connect an account again/)).toBeTruthy();
    fireEvent.change(document.getElementById('remove-code') as HTMLInputElement, { target: { value: '654321' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove it' }));
    await waitFor(() => expect(api.remove).toHaveBeenCalledWith('654321'));
    expect(await screen.findByText('Not connected')).toBeTruthy();
  });

  it('shows the reason when payments are still being confirmed', async () => {
    api.account.mockResolvedValue(verified);
    api.remove.mockRejectedValue(new PaymentsError('Some payments are still being confirmed with this account.', 409, 'payments_in_flight'));
    shell(<ReceivePaymentsPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Remove the saved key and secret' }));
    fireEvent.change(document.getElementById('remove-code') as HTMLInputElement, { target: { value: '654321' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, remove it' }));
    expect(await screen.findByText(/still being confirmed/)).toBeTruthy();
  });
});

describe('who sees what', () => {
  it('shows the status and no form or button to someone who may only read settings, and no key hint', async () => {
    api.account.mockResolvedValue({ ...verified, key_hint: null, host_ready: false });
    shell(<ReceivePaymentsPage />, ['core.settings.read']);
    expect(await screen.findByText('Connected and working')).toBeTruthy();
    expect(screen.queryByLabelText('Consumer secret')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Test again' })).toBeNull();
    expect(screen.getByText(/Only the business administrator can change the key and secret/)).toBeTruthy();
  });

  it('opens nothing for someone without settings access', () => {
    shell(<ReceivePaymentsPage />, ['core.approvals.read']);
    expect(screen.getByText('You do not have access to this page.')).toBeTruthy();
    expect(api.account).not.toHaveBeenCalled();
  });
});
