// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api, type Me } from '../../api/client';
import { setBusinessZone } from '../../api/retail';
import { router as appRouter } from '../../app/router';
import { setAccessToken } from '../../auth/session';

// Issue #667: the staff shell hands the business's own zone from /me (tenant_timezone) to businessToday, so a form's today is
// the business's date. At 22:30 UTC on 28 Oct a business in Lagos (UTC+1) is still on the 28th while Kampala is on the 29th.
// The real shell and the real Insights screen are drawn; only the network is faked. Every name is invented.

function officer(zone?: string): Me {
  return {
    user_id: '00000000-0000-4000-8000-0000000000c7',
    full_name: 'Test Officer 07',
    kind: 'staff',
    permissions: ['lending.insights.read'],
    modules: ['lending'],
    branches: [],
    tours: { 'tours-off': { status: 'dismissed', version: 1 } },
    ...(zone ? { tenant_timezone: zone } : {}),
  } as unknown as Me;
}

async function insightsTo(me: Me): Promise<HTMLInputElement> {
  setAccessToken('test-token-officer-07');
  vi.spyOn(api, 'GET').mockImplementation(((path: string) =>
    path === '/api/v1/me' ? Promise.resolve({ data: me, response: new Response() }) : new Promise(() => undefined)) as never);
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/insights'] }) });
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
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
});
