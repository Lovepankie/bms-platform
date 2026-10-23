// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The Plan and modules card (issue #472, ADR-073): the Owner adds or stops a module or cancels only after a plain-words confirm and their own
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
    applies_at_once: false,
    today: '2099-11-15',
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

  it('lets the Owner add a module only after the confirm and the password', async () => {
    base(true);
    plan.modules = ['lending'];
    plan.modules_available = ['retail'];
    show(<PlanCard mfaEnabled={false} />);
    fireEvent.click(await screen.findByRole('button', { name: 'Add Shop' }));
    expect(posts).toHaveLength(0);
    expect(screen.getByText(/Shop is switched on at once: your plan includes it/)).toBeTruthy();
    const yes = screen.getByRole('button', { name: 'Yes, add Shop' }) as HTMLButtonElement;
    expect(yes.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Your own password'), { target: { value: 'Fabricated-Pass-2026' } });
    fireEvent.click(yes);
    await waitFor(() => expect(posts).toHaveLength(1));
    expect(posts[0]).toEqual({ path: '/api/v1/plan/module-requests', body: { module: 'retail', password: 'Fabricated-Pass-2026' } });
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
    const now = { changes_take_effect_on: '2099-01-01', applies_at_once: true };
    expect(stopSentence('retail', now)).toMatch(/^Shop stops at once, because nothing is paid ahead/);
    expect(cancelSentence(now)).toMatch(/Nothing is deleted/);
    const later = { changes_take_effect_on: '2099-01-01', applies_at_once: false, paid_until: '2098-12-31' };
    expect(stopSentence('retail', later)).toMatch(/^Shop stops on 1 January 2099, after the last day paid for/);
  });

  it('offers the undo by the business date the server sends, not the browser clock', async () => {
    base(true);
    plan.today = '2099-12-01';
    plan.changes = [
      { id: 'c1', kind: 'cancel', effective_on: '2099-12-01', status: 'scheduled' },
      { id: 'c2', kind: 'stop_module', module_key: 'retail', effective_on: '2099-12-02', status: 'scheduled' },
    ];
    show(<PlanCard mfaEnabled={false} />);
    expect(await screen.findAllByRole('button', { name: 'Undo' })).toHaveLength(1);
  });
});
