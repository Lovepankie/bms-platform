// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product } from '../api/retail';
import type { OutboxItem } from './outbox';

// The daily forms with no network (issue #812, ADR-067 decision 13): an expense, savings, banking, a withdrawal, a delivery
// (Buy stock), a stock move and usage or damage are each kept on the phone with their form's key while offline, the form
// shows Saved on this phone, and once online the entry is sent once, under that key, however often the outbox runs; an edit
// of a saved expense says it needs the internet and keeps nothing. Every request fails as with no network unless a test says
// otherwise. Fabricated people, items and amounts.

vi.setConfig({ testTimeout: 20000 });
vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new TypeError('Failed to fetch'))));

const online = vi.fn();
vi.mock('../api/retail', async (original) => {
  const real = await original<typeof import('../api/retail')>();
  const called = (name: string) => (...a: unknown[]) => online(name, ...a);
  return {
    ...real,
    retail: {
      ...real.retail,
      createExpense: called('createExpense'),
      createSavings: called('createSavings'),
      createBanking: called('createBanking'),
      createWithdrawal: called('createWithdrawal'),
      createPurchase: called('createPurchase'),
      createTransfer: called('createTransfer'),
      createUsage: called('createUsage'),
      replaceExpense: called('replaceExpense'),
    },
  };
});

const { businessToday } = await import('../api/retail');
const { mockMe } = await import('../api/retail-mock');
const { StaffContext } = await import('../areas/staff/context');
const { ExpenseForm } = await import('../areas/staff/retail/expenses');
const { SavingsForm } = await import('../areas/staff/retail/savings');
const { BankingForm } = await import('../areas/staff/retail/banking');
const { WithdrawalForm } = await import('../areas/staff/retail/withdrawals');
const { RestockForm } = await import('../areas/staff/retail/restock');
const { TransferForm } = await import('../areas/staff/retail/transfer');
const { UsageForm } = await import('../areas/staff/retail/usage');
const { OfflineProvider } = await import('./offline-context');
const { testOffline } = await import('./test-kit');

const admin = mockMe('admin');
const uid = admin.user_id ?? '';
const branch = admin.branches?.[0]?.id ?? '';
const other = admin.branches?.find((b) => b.id !== branch && b.stocks_goods !== false)?.id ?? '';
const cable = { id: 'p1', code: 'TST-1', description: 'Test Cable 1', category: 'Test Wiring', unit: 'pc', qty: '40.000', active: true } as Product;

afterEach(() => {
  cleanup();
  online.mockReset();
  sessionStorage.clear();
});

/** A draft the form opens on, as a form left it in this tab, under its own key. */
function seed(name: string, key: string, draft: unknown) {
  sessionStorage.setItem(`retail-draft:${name}`, JSON.stringify({ key, draft }));
}

/** The answers of a server that records each key once: a repeated key answers the same record. */
function server() {
  const recorded = new Map<string, string>();
  const sends: OutboxItem[] = [];
  return {
    sends,
    recorded,
    send: async (item: OutboxItem) => {
      sends.push(item);
      if (!recorded.has(item.key)) recorded.set(item.key, `rec-${recorded.size + 1}`);
      return { status: 201, body: { id: recorded.get(item.key) } };
    },
  };
}

function mount(form: ReactNode, offline: ReturnType<typeof testOffline>, prime?: (client: QueryClient) => void) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['retail', 'expense-categories', true], [
    { id: 'c1', name: 'Test Transport', active: true, version: 1, items: [{ id: 'i1', name: 'Test Fuel', requires_explanation: false, active: true, version: 1 }] },
  ]);
  client.setQueryData(['retail', 'cash-parties'], []);
  client.setQueryData(['retail', 'suppliers'], []);
  prime?.(client);
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: admin, branch }}>
        <OfflineProvider value={offline}>{form}</OfflineProvider>
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const today = businessToday();

const FORMS = [
  {
    kind: 'expense', save: 'Save expense', path: '/api/v1/retail/expenses',
    seed: () => seed(`expense:${uid}:${branch}`, 'k-expense', { date: today, categoryId: 'c1', itemId: 'i1', partyId: '', amount: '20000', explanation: '' }),
    form: () => <ExpenseForm branchId={branch} />,
    dateField: 'business_date',
  },
  {
    kind: 'savings', save: 'Save savings', path: '/api/v1/retail/savings',
    seed: () => seed(`savings:${uid}:${branch}`, 'k-savings', { date: today, amount: '', reason: '', other: '' }),
    form: () => <SavingsForm branchId={branch} />,
    prime: (client: QueryClient) => client.setQueryData(['retail', 'savings-suggestion', branch, today], { business_date: today, suggestion_token: 'tok-1', total_sold_minor: 0 }),
    dateField: 'business_date',
  },
  {
    kind: 'banking', save: 'Save banking', path: '/api/v1/retail/bankings',
    seed: () => seed(`banking:${uid}:${branch}`, 'k-banking', { date: today, amount: '30000', reference: 'Test slip 1' }),
    form: () => <BankingForm branchId={branch} />,
    dateField: 'business_date',
  },
  {
    kind: 'withdrawal', save: 'Save withdrawal', path: '/api/v1/retail/withdrawals',
    seed: () => seed(`withdrawal:${uid}:${branch}`, 'k-withdrawal', { date: today, amount: '10000', purpose: 'Test float' }),
    form: () => <WithdrawalForm branchId={branch} />,
    dateField: 'business_date',
  },
  {
    kind: 'purchase', save: /^Save/, path: '/api/v1/retail/purchases',
    seed: () => seed(`restock:${uid}`, 'k-purchase', { supplierId: '', purchasedOn: today, method: 'cash', lines: [{ product: cable, cost: '100', sell: '150', qtyByBranch: { [branch]: '5' } }] }),
    form: () => <RestockForm />,
    dateField: undefined,
  },
  {
    kind: 'transfer', save: /^(Move|Send|Save)/, path: '/api/v1/retail/transfers',
    seed: () => seed(`transfer:${uid}:${branch}`, 'k-transfer', { toBranchId: other, transferDate: today, note: '', lines: [{ product: cable, qty: '2' }] }),
    form: () => <TransferForm fromBranchId={branch} />,
    dateField: 'transfer_date',
  },
  {
    kind: 'usage', save: 'Save', path: '/api/v1/retail/usage',
    seed: () => seed(`usage:${uid}:${branch}`, 'k-usage', { kind: 'damaged', reason: 'Test breakage', lines: [{ product: cable, qty: '1' }] }),
    form: () => <UsageForm branchId={branch} />,
    dateField: 'occurred_on',
  },
] as const;

describe('a daily form with no network', () => {
  it.each(FORMS)('keeps the $kind on the phone and sends it once when back online', async (f) => {
    f.seed();
    const api = server();
    const offline = testOffline(admin, { online: false, deps: { send: api.send } });
    mount(f.form(), offline, 'prime' in f ? f.prime : undefined);
    expect(screen.getByTestId('offline-form-note')).toBeTruthy();
    const save = screen.getAllByRole('button', { name: f.save }).find((b) => b.getAttribute('type') === 'submit') as HTMLButtonElement;
    fireEvent.click(save);
    await screen.findByTestId('kept-on-phone');
    expect(screen.getByText('Waiting to be sent')).toBeTruthy();
    // Nothing went to the server: the form never called its online save.
    expect(online).not.toHaveBeenCalled();

    const [item] = offline?.engine.snapshot().items ?? [];
    expect(item?.entry?.kind).toBe(f.kind);
    expect(item?.entry?.path).toBe(f.path);
    expect(item?.key).toBe(`k-${f.kind}`);
    const body = item?.entry?.body as Record<string, unknown>;
    expect((body.offline as { occurred_at?: string }).occurred_at).toBe(item?.occurredAt);
    if (f.dateField) expect(body[f.dateField]).toBeUndefined();

    // Back online: sent once under the form's key, and a second run sends nothing more.
    offline?.connectivity.set(true);
    await act(async () => {
      await offline?.engine.syncNow();
      await offline?.engine.syncNow();
    });
    expect(api.sends.map((s) => s.key)).toEqual([`k-${f.kind}`]);
    expect(api.recorded.size).toBe(1);
    await waitFor(() => expect(screen.getByText('Sent. It is recorded.')).toBeTruthy());
  });

  it('says an edit of a saved expense needs the internet, and keeps nothing', async () => {
    const offline = testOffline(admin, { online: false });
    const replaces = { id: 'e1', branch_id: branch, business_date: today, category_id: 'c1', item_id: 'i1', amount_minor: 5000 };
    mount(<ExpenseForm branchId={branch} replaces={replaces} />, offline);
    expect(screen.getByTestId('edit-needs-internet').textContent).toContain('Changing a saved entry needs the internet');
    const save = screen.getAllByRole('button').find((b) => b.getAttribute('type') === 'submit') as HTMLButtonElement;
    fireEvent.click(save);
    await new Promise((r) => setTimeout(r, 50));
    expect(offline?.engine.snapshot().items).toEqual([]);
    expect(online).not.toHaveBeenCalled();
  });

  // JUSTIFICATION-A3: the review of #839 asks that Fix never writes over a form with work in it; one test of both outcomes.
  it('opens a refused entry in its form with Fix, but never over a form that has work in it', async () => {
    const { EntryState } = await import('./entry-state');
    const { offlineEntry } = await import('./entries');
    const offline = testOffline(admin, { online: true });
    const name = `withdrawal:${uid}:${branch}`;
    const refused = {
      ...offlineEntry({ kind: 'withdrawal', key: 'k-fix', scope: offline?.scope ?? '', branchId: branch, branchName: 'Test Shop A', body: {}, now: new Date(), fix: { to: '/staff/retail/withdrawals', draftName: name, draft: { date: today, amount: '700', purpose: 'Test fix' } } }),
      state: 'failed' as const,
      error: { message: 'Check the amount.' },
    };
    await offline?.engine.queue(refused);
    // Another withdrawal is half typed in this tab.
    sessionStorage.setItem(`retail-draft:${name}`, JSON.stringify({ key: 'k-other', draft: { date: today, amount: '5', purpose: '' } }));
    sessionStorage.setItem(`retail-draft-busy:${name}`, '1');
    mount(<EntryState item={refused} />, offline);
    fireEvent.click(screen.getByRole('button', { name: 'Fix' }));
    await screen.findByText('That form has other work in it. Save or clear it first, then tap Fix again.');
    expect(offline?.engine.snapshot().items.map((i) => i.id)).toEqual(['k-fix']);
    expect(JSON.parse(sessionStorage.getItem(`retail-draft:${name}`) ?? '{}').key).toBe('k-other');
    // Cleared: Fix takes it off the phone and puts its draft back under a new key.
    sessionStorage.removeItem(`retail-draft:${name}`);
    sessionStorage.removeItem(`retail-draft-busy:${name}`);
    fireEvent.click(screen.getByRole('button', { name: 'Fix' }));
    await waitFor(() => expect(offline?.engine.snapshot().items).toEqual([]));
    const back = JSON.parse(sessionStorage.getItem(`retail-draft:${name}`) ?? '{}');
    expect(back.draft.purpose).toBe('Test fix');
    expect(back.key).not.toBe('k-fix');
  });

  it('keeps an entry whose answer was lost online under the same key and body, so the server records it once', async () => {
    seed(`withdrawal:${uid}:${branch}`, 'k-lost', { date: today, amount: '10000', purpose: 'Test float' });
    const api = server();
    const offline = testOffline(admin, { online: true, deps: { send: api.send } });
    const { markDropped } = await import('../api/client');
    // The request reached the server and the answer was lost on the way back.
    online.mockImplementation(() => {
      void api.send({ key: 'k-lost' } as OutboxItem);
      return Promise.reject(markDropped(new TypeError('Failed to fetch')));
    });
    mount(<WithdrawalForm branchId={branch} />, offline);
    fireEvent.click(screen.getAllByRole('button', { name: 'Save withdrawal' })[0] as HTMLButtonElement);
    await screen.findByTestId('kept-on-phone');
    const [item] = offline?.engine.snapshot().items ?? [];
    expect(item?.key).toBe('k-lost');
    // Sent as it went online: no offline block, so the server answers the key it already holds.
    expect((item?.entry?.body as Record<string, unknown>).offline).toBeUndefined();
    await act(async () => {
      await offline?.engine.syncNow();
    });
    expect(api.recorded.size).toBe(1);
  });
});
