// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type Me } from '../../api/client';
import { businessToday, businessZone, setBusinessZone } from '../../api/retail';
import { router as appRouter } from '../../app/router';
import { setAccessToken, subscribe } from '../../auth/session';
import { SAVINGS_READ } from './lending/savings-permissions';

// Issue #667: the staff shell hands the business's own zone from /me (tenant_timezone) to businessToday, so a form's today is
// the business's date. At 22:30 UTC on 28 Oct a business in Lagos (UTC+1) is still on the 28th while Kampala is on the 29th.
// Issue #670: the zone belongs to the person signed in, so it goes back to Kampala when they sign out or the shell unmounts.
// The real shell and the real screens are drawn; only the network is faked. Every name is invented.

function officer(zone?: string, permissions = ['lending.insights.read']): Me {
  return {
    user_id: '00000000-0000-4000-8000-0000000000c7',
    full_name: 'Test Officer 07',
    kind: 'staff',
    permissions,
    modules: ['lending'],
    branches: [],
    tours: { 'tours-off': { status: 'dismissed', version: 1 } },
    ...(zone ? { tenant_timezone: zone } : {}),
  } as unknown as Me;
}

function shellAt(me: Me, path: string, answers: Record<string, unknown> = {}): void {
  setAccessToken('test-token-officer-07');
  const data: Record<string, unknown> = { ...answers, '/api/v1/me': me };
  vi.spyOn(api, 'GET').mockImplementation(((url: string) =>
    url in data ? Promise.resolve({ data: data[url], response: new Response() }) : new Promise(() => undefined)) as never);
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [path] }) });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}

async function insightsTo(me: Me): Promise<HTMLInputElement> {
  shellAt(me, '/staff/insights');
  return (await screen.findByLabelText('To', {}, { timeout: 10_000 })) as HTMLInputElement;
}

beforeEach(() => {
  window.scrollTo = vi.fn();
  window.localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-10-28T22:30:00Z'));
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  setAccessToken(null);
  setBusinessZone(undefined);
  window.localStorage.clear();
});

describe("today in the business's own zone", () => {
  it('gives a business in Lagos the 28th at 22:30 UTC', async () => {
    const to = await insightsTo(officer('Africa/Lagos'));
    expect(to.value).toBe('2026-10-28');
    expect(to.max).toBe('2026-10-28');
  });

  it('gives Kampala, the 29th, when /me names no zone', async () => {
    const to = await insightsTo(officer());
    expect(to.value).toBe('2026-10-29');
    expect(to.max).toBe('2026-10-29');
  });

  it("gives a lending form's default in Lagos the 28th too", async () => {
    // A savings statement runs from three months back to today unless changed (savings-statement.tsx).
    shellAt(officer('Africa/Lagos', [SAVINGS_READ]), '/staff/lending/savings/accounts/00000000-0000-4000-8000-0000000000e1/statement');
    fireEvent.click(await screen.findByRole('button', { name: /Change the days$/ }, { timeout: 10_000 }));
    const to = screen.getByLabelText('To') as HTMLInputElement;
    expect(to.value).toBe('2026-10-28');
    expect(to.max).toBe('2026-10-28');
  }, 15_000);

  it("gives a Lagos repayment its own today when it restores an earlier day's draft (#670)", async () => {
    // The shell sets the zone as it draws, before the loan page and its form, so the form's first today is already Lagos's.
    const loan = { id: '00000000-0000-4000-8000-0000000000a7', loan_no: 'LN-BR1-000007', status: 'active', currency: 'UGX' };
    const me = officer('Africa/Lagos', ['lending.loans.read', 'lending.repayments.create']);
    const draft = { amount: '50000', valueDate: '2026-10-27', method: 'cash', reference: '', startedOn: '2026-10-27' };
    sessionStorage.setItem(`retail-draft:lending-repay:${me.user_id}:${loan.id}`, JSON.stringify({ key: 'test-key-670-lagos', draft }));
    shellAt(me, `/staff/lending/loans/${loan.id}`, { '/api/v1/lending/loans/{loan_id}': loan });
    fireEvent.click(await screen.findByRole('button', { name: 'Record repayment' }, { timeout: 10_000 }));
    const date = screen.getByLabelText('Date paid') as HTMLInputElement;
    expect([date.value, date.max]).toEqual(['2026-10-28', '2026-10-28']);
    expect((screen.getByLabelText('Amount (UGX)') as HTMLInputElement).value).toBe('50000');
    sessionStorage.clear();
  }, 15_000);
});

describe("forgetting the business's zone", () => {
  it('goes back to Kampala when the staff shell unmounts', async () => {
    await insightsTo(officer('Africa/Lagos'));
    expect(businessZone()).toBe('Africa/Lagos');
    cleanup();
    expect(businessZone()).toBe('Africa/Kampala');
    expect(businessToday()).toBe('2026-10-29');
  });

  it('goes back to Kampala on sign out, before the session ends', async () => {
    vi.spyOn(api, 'POST').mockImplementation((() => Promise.resolve({ response: new Response(null, { status: 204 }) })) as never);
    await insightsTo(officer('Africa/Lagos'));
    // The zone as it stood when the access token was dropped, which is before the shell unmounts.
    const zones: string[] = [];
    const stop = subscribe(() => zones.push(businessZone()));
    fireEvent.click(within(document.querySelector('.side-nav') as HTMLElement).getByRole('button', { name: 'Sign out' }));
    await waitFor(() => expect(zones[0]).toBe('Africa/Kampala'));
    stop();
  });
});
