// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { RetailError } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import type { OutboxItem } from '../../../offline/outbox';
import { StaffContext } from '../context';

// Add many items on Import items (issue #870): a pasted list fills the rows for checking; Save the list runs the import's check
// on the good rows and then one apply of the rows it passed, under the list's one key, with how many there are now at the
// chosen shop; a name already in the items offers to add to its stock instead; a bad number is refused on its row and the rows
// that need a look stay; a lost answer sends exactly the same apply again; offline the apply is one entry on the phone, sent
// once. Every item, group and price is fabricated.

vi.setConfig({ testTimeout: 20000 });

const importProducts = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve([{ id: 'c1', name: 'Test Drinks', active: true, product_count: 0, version: 1, code_prefix: 'DRK' }]),
      listUnits: () => Promise.resolve([{ id: 'u1', name: 'Piece', active: true, product_count: 0, version: 1 }, { id: 'u2', name: 'Bottle', active: true, product_count: 0, version: 1 }]),
      importProducts: (...a: unknown[]) => importProducts(...a),
    },
  };
});

const { AddManyForm } = await import('./add-many');
const { OfflineProvider } = await import('../../../offline/offline-context');
const { testOffline } = await import('../../../offline/test-kit');

const owner = mockMe('admin');
const branch = owner.branches?.[0]?.id ?? '';

/** An owner who may not add stock anywhere: the rows have no How many, so the import may skip a row. */
function withoutCount(): Me {
  const scopes = { ...(owner.permission_scopes ?? {}) };
  delete scopes['retail.stocktake.commit'];
  return { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'retail.stocktake.commit'), permission_scopes: scopes };
}

function withoutProfit(): Me {
  return { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'retail.profit.read') };
}

function open(me: Me = owner, offline: ReturnType<typeof testOffline> | null = null) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/retail/catalogue/import'] }) });
  const form = <AddManyForm />;
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me, branch, chooseBranch: () => undefined }}>
          {offline ? <OfflineProvider value={offline}>{form}</OfflineProvider> : form}
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const input = (id: string) => document.getElementById(id) as HTMLInputElement;
const type = (id: string, value: string) => fireEvent.change(input(id), { target: { value } });
const saveButton = () => screen.getAllByRole('button', { name: 'Save the list' }).find((b) => b.getAttribute('type') === 'submit') as HTMLButtonElement;

/** The lines of an import file after its header, each as its cells (the screen quotes no cell of these names). */
const linesOf = (csv: string) => csv.trim().split('\n').slice(1).map((l) => l.split(','));
const headerOf = (csv: string) => csv.split('\n')[0];

/** Names the fabricated server already holds, as Test Rice 1kg (GNR-0009), and a name it refuses. */
let existing: Record<string, string> = {};
let refused: Record<string, string> = {};

/** The import's answer: each line added (or stock with stock_only), skipped for a name it holds, or refused. */
function answer(csv: string, dry: boolean) {
  const head = (headerOf(csv) ?? '').split(',');
  const stockAt = head.indexOf('stock_only');
  const rows = linesOf(csv).map((cells, i) => {
    const name = cells[0] ?? '';
    if (refused[name]) return { line: i + 2, description: name, outcome: 'error', message: refused[name] };
    if (cells[stockAt] === 'yes') return { line: i + 2, description: name, outcome: 'stock', message: 'Would add.', existing_code: existing[name] };
    if (existing[name]) return { line: i + 2, description: name, outcome: 'skipped', message: 'An item with this name exists already.', existing_code: existing[name] };
    return { line: i + 2, description: name, outcome: 'added', message: dry ? 'Would be added.' : 'Added.' };
  });
  return {
    dry_run: dry, rows_read: rows.length, rows, categories_created: [], units_created: [],
    added: rows.filter((r) => r.outcome === 'added').length,
    stock_added: rows.filter((r) => r.outcome === 'stock').length,
    skipped: rows.filter((r) => r.outcome === 'skipped').length,
    errors: rows.filter((r) => r.outcome === 'error').length,
  };
}

/** The applies sent: [csv, stock] of each call that was not a check. */
const applies = () => importProducts.mock.calls.filter((c) => c[1] === false) as [string, boolean, boolean, { branchId?: string; key?: string }][];

beforeEach(() => {
  existing = {};
  refused = {};
  importProducts.mockImplementation(async (csv: string, dry: boolean) => answer(csv, dry));
});

afterEach(() => {
  cleanup();
  importProducts.mockReset();
  sessionStorage.clear();
  localStorage.clear();
});

describe('Add many items', () => {
  it('starts with five empty rows and adds another', async () => {
    open();
    expect(screen.getAllByRole('listitem').filter((l) => l.className === 'am-row')).toHaveLength(5);
    fireEvent.click(screen.getByRole('button', { name: 'Add another row' }));
    await waitFor(() => expect(document.getElementById('am-name-6')).toBeTruthy());
  });

  it('fills the rows from a pasted list, checks them, and adds them all in one apply under one key', async () => {
    open();
    fireEvent.change(input('am-paste'), { target: { value: 'Name\tHow many\tBuying\tSelling\nTest Soda 300ml\t24\t1,000\t1,500\nTest Bar Soap\t10\t2000\t2500\nTest Candle\t5\t300\t500' } });
    fireEvent.click(screen.getByRole('button', { name: 'Fill the rows' }));
    expect(input('am-name-1').value).toBe('Test Soda 300ml');
    expect(input('am-qty-2').value).toBe('10');
    expect(input('am-sell-3').value).toBe('500');
    expect(screen.getByTestId('am-paste-note').textContent).toContain('3 lines filled in');
    // Filled for checking: nothing is sent until Save the list.
    expect(importProducts).not.toHaveBeenCalled();

    fireEvent.click(saveButton());
    await waitFor(() => expect(applies()).toHaveLength(1));
    const [check] = importProducts.mock.calls;
    expect(check?.[1]).toBe(true);
    const [csv, , codes, stock] = applies()[0] ?? [];
    expect(codes).toBe(false);
    expect(stock?.branchId).toBe(branch);
    expect(stock?.key).toMatch(/^[0-9a-f-]{36}$/);
    expect(headerOf(csv ?? '')).toBe('description,category,unit,qty,cost_price,sell_price,stock_only');
    // Numbers as typed, the thousands commas taken out; no group chosen is General, the unit the shop's piece.
    expect(linesOf(csv ?? '')).toEqual([
      ['Test Soda 300ml', 'General', 'Piece', '24', '1000', '1500', ''],
      ['Test Bar Soap', 'General', 'Piece', '10', '2000', '2500', ''],
      ['Test Candle', 'General', 'Piece', '5', '300', '500', ''],
    ]);
    expect((await screen.findByTestId('am-result')).textContent).toBe('3 items added.');
    // The saved rows leave the screen: five empty rows again.
    await waitFor(() => expect(input('am-name-1').value).toBe(''));
  });

  it('offers to add to the stock of an item already in the list instead of a duplicate', async () => {
    existing = { 'Test Rice 1kg': 'GNR-0009' };
    open();
    type('am-name-1', 'Test Rice 1kg');
    type('am-qty-1', '8');
    type('am-sell-1', '5000');
    fireEvent.click(saveButton());
    await screen.findByText('This product already exists: Test Rice 1kg (GNR-0009).');
    // The check found it: nothing was added.
    expect(applies()).toHaveLength(0);
    expect(screen.getByTestId('am-result').textContent).toBe('0 items added, 1 needs a look.');
    // One tap posts its quantity to the item already held, without Save the list.
    fireEvent.click(screen.getByRole('button', { name: 'Add to its stock instead' }));
    await waitFor(() => expect(applies()).toHaveLength(1));
    expect(linesOf(applies()[0]?.[0] ?? '')).toEqual([['Test Rice 1kg', 'General', 'Piece', '8', '', '', 'yes']]);
    expect((await screen.findByTestId('am-result')).textContent).toBe('1 item added.');
  });

  it('refuses a comma decimal on its row and saves the good rows, leaving the bad one on the screen', async () => {
    open();
    type('am-name-1', 'Test Bulb');
    type('am-qty-1', '6');
    type('am-sell-1', '3000');
    type('am-name-2', 'Test Rope');
    type('am-qty-2', '2,5');
    type('am-sell-2', '400');
    type('am-name-3', 'Test Hook');
    type('am-qty-3', '12');
    type('am-sell-3', '1,200');
    fireEvent.click(saveButton());
    await waitFor(() => expect(applies()).toHaveLength(1));
    expect(linesOf(applies()[0]?.[0] ?? '').map((l) => l[0])).toEqual(['Test Bulb', 'Test Hook']);
    expect((await screen.findByTestId('am-result')).textContent).toBe('2 items added, 1 needs a look.');
    // The bad row stays, with what was typed, never rewritten, and its problem under the field.
    expect(input('am-name-2').value).toBe('Test Rope');
    expect(input('am-qty-2').value).toBe('2,5');
    expect(screen.getByText('Use a point for decimals, like 2.5. A comma only goes between thousands, like 12,500.')).toBeTruthy();
  });

  it('keeps a row the import refused on the screen with its words, and adds the others', async () => {
    refused = { 'Test Lamp': 'The unit "Box" is switched off. Switch it on or use another unit.' };
    open();
    type('am-name-1', 'Test Fan');
    type('am-qty-1', '2');
    type('am-sell-1', '90000');
    type('am-name-2', 'Test Lamp');
    type('am-qty-2', '3');
    type('am-sell-2', '9000');
    fireEvent.click(saveButton());
    await waitFor(() => expect(applies()).toHaveLength(1));
    expect(linesOf(applies()[0]?.[0] ?? '').map((l) => l[0])).toEqual(['Test Fan']);
    expect((await screen.findByTestId('am-result')).textContent).toBe('1 item added, 1 needs a look.');
    expect(screen.getByTestId('am-problem-1').textContent).toContain('switched off');
    expect(input('am-name-2').value).toBe('Test Lamp');
  });

  it('warns, without blocking, when the selling price is below the buying price', async () => {
    open();
    type('am-name-1', 'Test Torch');
    type('am-qty-1', '1');
    type('am-cost-1', '5000');
    type('am-sell-1', '4000');
    expect(screen.getByText('The selling price is below the buying price. Check it, or save it like this.')).toBeTruthy();
    fireEvent.click(saveButton());
    await waitFor(() => expect(applies()).toHaveLength(1));
  });

  it('after a lost answer sends exactly the same apply again under the same key, so nothing is added twice', async () => {
    let first = true;
    importProducts.mockImplementation(async (csv: string, dry: boolean) => {
      if (!dry && first) {
        first = false;
        throw new RetailError('Bad gateway', 502, undefined);
      }
      return answer(csv, dry);
    });
    open();
    type('am-name-1', 'Test Kettle');
    type('am-qty-1', '1');
    type('am-sell-1', '45000');
    fireEvent.click(saveButton());
    await screen.findByTestId('am-not-sure');
    // Locked while it may have gone through.
    expect(input('am-name-1').closest('fieldset')?.disabled).toBe(true);
    fireEvent.click(saveButton());
    await waitFor(() => expect(applies()).toHaveLength(2));
    expect(applies()[1]).toEqual(applies()[0]);
    expect((await screen.findByTestId('am-result')).textContent).toBe('1 item added.');
  });

  it('after a certain refusal says why, saves nothing and sends the next try under a new key', async () => {
    let first = true;
    importProducts.mockImplementation(async (csv: string, dry: boolean) => {
      if (!dry && first) {
        first = false;
        throw new RetailError('Another change added an item named Test Fan. Nothing was added.', 409, 'import_conflict');
      }
      return answer(csv, dry);
    });
    open();
    type('am-name-1', 'Test Fan');
    type('am-qty-1', '2');
    type('am-sell-1', '9000');
    fireEvent.click(saveButton());
    expect(await screen.findByText('Another change added an item named Test Fan. Nothing was added.')).toBeTruthy();
    expect(input('am-name-1').value).toBe('Test Fan');
    fireEvent.click(saveButton());
    await waitFor(() => expect(applies()).toHaveLength(2));
    expect(applies()[1]?.[3]?.key).not.toBe(applies()[0]?.[3]?.key);
  });

  // JUSTIFICATION-A3: the review of #871 asks the two ways an apply can leave a row unadded; one test each.
  it('keeps a row the apply did not add on the screen, never counting it done (review of #871)', async () => {
    let applied = false;
    importProducts.mockImplementation(async (csv: string, dry: boolean) => {
      // Between the check and the apply another phone adds Test Soap: the apply skips it and adds Test Brush.
      if (!dry) {
        applied = true;
        existing = { 'Test Soap': 'GNR-0042' };
      }
      return answer(csv, dry);
    });
    open(withoutCount());
    type('am-name-1', 'Test Soap');
    type('am-sell-1', '2500');
    type('am-name-2', 'Test Brush');
    type('am-sell-2', '800');
    fireEvent.click(saveButton());
    await waitFor(() => expect(applied).toBe(true));
    expect((await screen.findByTestId('am-result')).textContent).toBe('1 item added, 1 needs a look.');
    expect(input('am-name-1').value).toBe('Test Soap');
  });

  it('keeps a row with a quantity the apply skipped, and one tap adds its quantity to the item (Lead, FAIL of 00210db)', async () => {
    importProducts.mockImplementation(async (csv: string, dry: boolean) => {
      // The online race: Test Soap is added on another phone between the check and the apply.
      if (!dry) existing = { 'Test Soap': 'GNR-0042' };
      return answer(csv, dry);
    });
    open();
    type('am-name-1', 'Test Soap');
    type('am-qty-1', '24');
    type('am-sell-1', '2500');
    type('am-name-2', 'Test Brush');
    type('am-qty-2', '6');
    type('am-sell-2', '800');
    fireEvent.click(saveButton());
    expect((await screen.findByTestId('am-result')).textContent).toBe('1 item added, 1 needs a look.');
    // The skipped row stays with what was typed, never counted done.
    expect(input('am-name-1').value).toBe('Test Soap');
    expect(input('am-qty-1').value).toBe('24');
    fireEvent.click(screen.getByRole('button', { name: 'Add to its stock instead' }));
    await waitFor(() => expect(applies()).toHaveLength(2));
    expect(linesOf(applies()[1]?.[0] ?? '')).toEqual([['Test Soap', 'General', 'Piece', '24', '', '', 'yes']]);
    expect(applies()[1]?.[3]?.key).not.toBe(applies()[0]?.[3]?.key);
    expect((await screen.findByTestId('am-result')).textContent).toBe('1 item added.');
  });

  it('moves to the next cell on Enter, and adds a row after the last one', async () => {
    open();
    input('am-name-1').focus();
    fireEvent.keyDown(input('am-name-1'), { key: 'Enter' });
    expect(document.activeElement?.id).toBe('am-qty-1');
    fireEvent.keyDown(input('am-qty-1'), { key: 'Enter' });
    expect(document.activeElement?.id).toBe('am-cost-1');
    input('am-sell-5').focus();
    fireEvent.keyDown(input('am-sell-5'), { key: 'Enter' });
    await waitFor(() => expect(document.activeElement?.id).toBe('am-name-6'));
  });

  it('asks no buying price of someone who may not see it, and sends no buying price column', async () => {
    open(withoutProfit());
    expect(screen.queryByLabelText(/Buying price/)).toBeNull();
    type('am-name-1', 'Test Comb');
    type('am-qty-1', '4');
    type('am-sell-1', '700');
    fireEvent.click(saveButton());
    await waitFor(() => expect(applies()).toHaveLength(1));
    expect(headerOf(applies()[0]?.[0] ?? '')).toBe('description,category,unit,qty,sell_price,stock_only');
  });

  it('keeps the list on the phone as one entry while offline and sends it once when back online', async () => {
    const recorded = new Map<string, number>();
    const sends: OutboxItem[] = [];
    const send = async (item: OutboxItem) => {
      sends.push(item);
      recorded.set(item.key, (recorded.get(item.key) ?? 0) + 1);
      return { status: 200, body: { added: 2, skipped: 0, errors: 0, rows: [] } };
    };
    const offline = testOffline(owner, { online: false, deps: { send } });
    open(owner, offline);
    expect(screen.getByTestId('offline-form-note')).toBeTruthy();
    type('am-name-1', 'Test Basin');
    type('am-qty-1', '3');
    type('am-sell-1', '8000');
    type('am-name-2', 'Test Jug');
    type('am-qty-2', '2');
    type('am-sell-2', '6000');
    fireEvent.click(saveButton());
    expect((await screen.findByTestId('am-result')).textContent).toBe('2 items saved on this phone. They are sent when the phone is back online.');
    // Nothing went to the server: no check and no apply.
    expect(importProducts).not.toHaveBeenCalled();

    const items = offline?.engine.snapshot().items ?? [];
    expect(items).toHaveLength(1);
    expect(items[0]?.entry?.kind).toBe('items');
    expect(items[0]?.entry?.path).toBe('/api/v1/retail/products/import?dry_run=false');
    const body = items[0]?.entry?.body as { csv: string; branch_id: string; offline: { occurred_at: string } };
    expect(linesOf(body.csv)).toHaveLength(2);
    expect(body.branch_id).toBe(branch);
    expect(body.offline.occurred_at).toBe(items[0]?.occurredAt);

    offline?.connectivity.set(true);
    await act(async () => {
      await offline?.engine.syncNow();
      await offline?.engine.syncNow();
    });
    expect(sends.map((s) => s.key)).toEqual([items[0]?.key]);
    expect(recorded.size).toBe(1);
  });

  // JUSTIFICATION-A3: the Lead's FAIL of 00210db asks a test of an offline list with an existing name; one new test.
  it('brings a list kept on the phone back with Fix when the server skipped a name it holds (Lead, FAIL of 00210db)', async () => {
    const send = async () => ({
      status: 200,
      body: { added: 1, skipped: 1, errors: 0, rows: [
        { line: 2, description: 'Test Basin', outcome: 'skipped', message: 'An item with this name exists already.', qty: 3, existing_code: 'GNR-0077' },
        { line: 3, description: 'Test Jug', outcome: 'added', message: 'Added.' },
      ] },
    });
    const offline = testOffline(owner, { online: false, deps: { send } });
    open(owner, offline);
    type('am-name-1', 'Test Basin');
    type('am-qty-1', '3');
    type('am-sell-1', '8000');
    type('am-name-2', 'Test Jug');
    type('am-qty-2', '2');
    type('am-sell-2', '6000');
    fireEvent.click(saveButton());
    await screen.findByTestId('am-result');
    offline?.connectivity.set(true);
    await act(async () => {
      await offline?.engine.syncNow();
    });
    // Never counted sent quietly: it waits with Fix, which opens only the skipped row, set to add to the item held.
    const [item] = offline?.engine.snapshot().items ?? [];
    expect(item?.state).toBe('failed');
    expect(item?.error?.code).toBe('items_skipped');
    expect(item?.error?.message).toBe('1 item is already in your list, so its stock was not added. Tap Fix to add it to its stock.');
    const draft = item?.entry?.fix?.draft as { rows: { name: string; qty: string; addTo?: { code: string } }[] };
    expect(draft.rows.map((r) => [r.name, r.qty, r.addTo?.code])).toEqual([['Test Basin', '3', 'GNR-0077']]);
  });
});
