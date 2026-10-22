// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The Plan and modules card (issue #472, ADR-073): the Owner stops a module or cancels only after a plain-words confirm and their own
// password; an administrator sees the plan and no buttons; every member of staff sees the end date, then the read-only notice.
// Fabricated business, dates and password.

const plan: Record<string, unknown> = {};
const posts: { path: string; body: unknown }[] = [];
let status: Record<string, unknown> = {};
const client = {
  GET: vi.fn(async (path: string) => (path === '/api/v1/plan/status' ? { data: status } : { data: { ...plan } })),
  POST: vi.fn(async (path: string, init: { body?: unknown }) => {
    posts.push({ path, body: init?.body });
    return { data: {} };
  }),
};

vi.mock('../../api/client', async (original) => ({
  ...(await original<typeof import('../../api/client')>()),
  api: new Proxy({}, { get: (_t, name: string) => (client as Record<string, unknown>)[name] }),
}));

const { PlanCard, PlanNotice, cancelSentence, endNotice, stopSentence } = await import('./plan');

function base(canChange: boolean) {
  Object.assign(plan, {
    plan_code: 'test-plan',
    plan_name: 'Test Plan',
    subscription_status: 'active',
    paid_until: '2099-11-30',
    pilot: false,
    modules: ['lending', 'retail'],
    modules_available: [],
    modules_in_plan: ['lending', 'retail'],
    changes_take_effect_on: '2099-12-01',
    can_change: canChange,
    requests: [],
    changes: [],
  });
}

function show(node: React.ReactNode) {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{node}</QueryClientProvider>);
}

beforeEach(() => {
  for (const key of Object.keys(plan)) delete plan[key];
  posts.length = 0;
  status = {};
});

afterEach(() => cleanup());

describe('the plan card', () => {
  it('lets the Owner stop a module only after the confirm and the password', async () => {
    base(true);
    show(<PlanCard mfaEnabled={false} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Stop using Shop' }));
    expect(screen.getByText(/Shop stops on 1 December 2099, after the last day paid for \(30 November 2099\)/)).toBeTruthy();
    expect(screen.getByText(/records are kept but hidden/)).toBeTruthy();
    const yes = screen.getByRole('button', { name: 'Yes, stop using Shop' }) as HTMLButtonElement;
    expect(yes.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Your own password'), { target: { value: 'Fabricated-Pass-2026' } });
    fireEvent.click(yes);
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toEqual({ path: '/api/v1/plan/modules/{module}/stop', body: { password: 'Fabricated-Pass-2026' } });
  });

  it('asks for the authenticator code instead when the Owner has two-step sign-in', async () => {
    base(true);
    show(<PlanCard mfaEnabled />);
    fireEvent.click(await screen.findByRole('button', { name: 'Cancel the subscription' }));
    fireEvent.change(screen.getByLabelText('Code from your authenticator app'), { target: { value: '123456' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, cancel the subscription' }));
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toEqual({ path: '/api/v1/plan/cancel', body: { totp_code: '123456' } });
  });

  it('shows an administrator the plan and no change buttons', async () => {
    base(false);
    show(<PlanCard mfaEnabled={false} />);
    expect(await screen.findByText('Only the owner of the business can change the plan.')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('does not offer to stop the last module', async () => {
    base(true);
    plan.modules = ['lending'];
    show(<PlanCard mfaEnabled={false} />);
    expect(await screen.findByText('(a business keeps at least one module)')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Stop using Lending' })).toBeNull();
  });
});

describe('the end of the subscription', () => {
  it('shows the end date while a cancellation waits, and the read-only notice after it', async () => {
    status = { ends_on: '2099-12-01' };
    show(<PlanNotice />);
    expect(await screen.findByText(/Everything works until 30 November 2099; from 1 December 2099 the business is read only/)).toBeTruthy();
    cleanup();
    status = { ended_on: '2099-12-01' };
    show(<PlanNotice />);
    expect(await screen.findByText(/ended on 1 December 2099\. It is read only/)).toBeTruthy();
  });

  it('says nothing while the subscription goes on, and at once when nothing is paid ahead', () => {
    expect(endNotice({})).toBeNull();
    const now = { changes_take_effect_on: '2099-01-01', paid_until: null };
    expect(stopSentence('retail', now, '2099-01-01')).toMatch(/^Shop stops at once, because nothing is paid ahead/);
    expect(cancelSentence(now, '2099-01-01')).toMatch(/Nothing is deleted/);
  });
});
