// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { RetailError } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { IDEMPOTENCY_UNSURE_CODES } from '../../../api/retail-errors';
import { readFileSync } from 'node:fs';
import { branchLabel } from '../../../auth/branch';
import { StaffContext } from '../context';

// Quick add (issue #632): one form and one tap for an item, its prices and how many there are. Add sends one request with the
// branch chosen, says what was added, and offers Add another (category and where kept) and Sell it. A name already in the list
// offers to use that item and just add stock. Without retail.stocktake.commit, which adding stock needs, the item is added with no stock. Offline, the form
// says it needs the internet. Every item, category and amount is fabricated.

vi.setConfig({ testTimeout: 20000 });

const quickAdd = vi.fn();
const setProductPhoto = vi.fn();
let units: Record<string, unknown>[] = [];

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve([{ id: 'c1', name: 'Test Bulbs', active: true, product_count: 0, version: 1, code_prefix: 'BLB' }]),
      listUnits: () => Promise.resolve(units),
      quickAdd: (...a: unknown[]) => quickAdd(...a),
      setProductPhoto: (...a: unknown[]) => setProductPhoto(...a),
    },
  };
});

const { QuickAddForm, addedLine, pieceUnit, refusedForCertain } = await import('./quick-add');

const owner = mockMe('admin');
const branch = owner.branches?.[0];
const other = owner.branches?.[1];

/** The owner who may not add stock (no retail.stocktake.commit): catalogue manage only. */
function catalogueOnly(): Me {
  const scopes = { ...(owner.permission_scopes ?? {}) };
  delete scopes['retail.stocktake.commit'];
  return { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'retail.stocktake.commit'), permission_scopes: scopes };
}

function open(me: Me, onSell = vi.fn(), initialName?: string) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: branch?.id ?? '', chooseBranch: () => undefined }}>
        <QuickAddForm onSell={onSell} initialName={initialName} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
  return onSell;
}

const type = (label: string | RegExp, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

afterEach(() => {
  cleanup();
  quickAdd.mockReset();
  setProductPhoto.mockReset();
  units = [{ id: 'u1', name: 'pc', active: true, product_count: 0, version: 1 }];
});
units = [{ id: 'u1', name: 'pc', active: true, product_count: 0, version: 1 }];

describe('quick add', () => {
  it('adds the item and its stock at the branch in use in one tap, and says so', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p1', code: 'GNR-0001', description: 'Test Bulb 60W', unit: 'pc', item_made: true, category_made: true, unit_made: false, branch_id: branch?.id, qty: '600' });
    const onSell = open(owner);
    // The shop's piece unit is chosen once the units are read.
    await waitFor(() => expect((screen.getByLabelText(/Counted in|Unit/) as HTMLSelectElement).value).toBe('u1'));
    type(/^Name/, 'Test Bulb 60W');
    type(/^Selling price/, '6,000');
    type(/^Buying price/, '4000');
    type(/How many do you have here now/, '600');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect(quickAdd).toHaveBeenCalledTimes(1);
    const [body, key] = quickAdd.mock.calls[0] as [Record<string, unknown>, string];
    expect(body).toEqual({ description: 'Test Bulb 60W', sell_minor: 6000, cost_minor: 4000, unit_id: 'u1', qty: '600.000', branch_id: branch?.id });
    expect(key).toMatch(/^[0-9a-f-]{36}$/);
    expect(screen.getByTestId('qa-done').textContent).toBe(`Test Bulb 60W added: 600 pc at ${branchLabel(branch)}`);
    fireEvent.click(screen.getByRole('button', { name: 'Sell it' }));
    expect(onSell).toHaveBeenCalledWith('p1', branch?.id);
  });

  it('adds the stock at the branch or store chosen in Where, and Add another keeps the category and where', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p2', code: 'BLB-0001', description: 'Test Tube', unit: 'pc', item_made: true, category_made: false, unit_made: false, branch_id: other?.id, qty: '3' });
    open(owner);
    fireEvent.change(screen.getByLabelText(/^Where/), { target: { value: other?.id } });
    type(/^Name/, 'Test Tube');
    type(/^Selling price/, '1000');
    type(/How many do you have here now/, '3');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[0] as [Record<string, unknown>])[0]).toMatchObject({ branch_id: other?.id, qty: '3.000' });
    fireEvent.click(screen.getByRole('button', { name: 'Add another' }));
    expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe('');
    expect((screen.getByLabelText(/^Where/) as HTMLSelectElement).value).toBe(other?.id);
  });

  it('leaves the category out, so the server files the item under General', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p3', description: 'Test Soap', unit: 'Piece', item_made: true, category_made: true, unit_made: true, qty: '1' });
    units = [];
    open(owner);
    await screen.findByTestId('qa-piece-made');
    type(/^Name/, 'Test Soap');
    type(/^Selling price/, '2500');
    type(/How many do you have here now/, '1');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    const body = (quickAdd.mock.calls[0] as [Record<string, unknown>])[0];
    expect(body).not.toHaveProperty('category_id');
    expect(body).not.toHaveProperty('unit_id');
  });

  it('offers the existing item to just add stock to when the name is in the list', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('This product already exists: Test Rice 1kg (GNR-0001).', 422, 'duplicate_product_name', {
      existing_id: 'p9', existing_code: 'GNR-0001', existing_name: 'Test Rice 1kg', may_add_anyway: false,
    }));
    quickAdd.mockResolvedValueOnce({ product_id: 'p9', code: 'GNR-0001', description: 'Test Rice 1kg', unit: 'pc', item_made: false, branch_id: branch?.id, qty: '8', total_qty: '58' });
    open(owner);
    type(/^Name/, 'Test Rice 1kg');
    type(/^Selling price/, '5000');
    type(/How many do you have here now/, '8');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const panel = await screen.findByTestId('name-exists');
    expect(screen.getByTestId('qa-adds-to').textContent).toBe('The 8 you typed is added to the stock it already has there.');
    fireEvent.click(within(panel).getByRole('button', { name: 'Use the existing one and just add stock' }));
    await screen.findByTestId('qa-done');
    const [body, key] = quickAdd.mock.calls[1] as [Record<string, unknown>, string];
    expect(body).toEqual({ product_id: 'p9', qty: '8.000', branch_id: branch?.id });
    // The server answered the first request, so the second is a new one.
    expect(key).not.toBe((quickAdd.mock.calls[0] as [unknown, string])[1]);
    expect(screen.getByTestId('qa-done').textContent).toBe(`Test Rice 1kg: 8 pc added at ${branchLabel(branch)}, 58 pc now`);
    expect(screen.queryByTestId('qa-adds-to')).toBeNull();
  });

  it('says why there is no Sell it when the person may not sell at that branch', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p7', description: 'Test Tray', unit: 'pc', item_made: true, branch_id: other?.id, qty: '2', total_qty: '2' });
    const scopes = { ...(owner.permission_scopes ?? {}), 'retail.sale.create': { all_branches: false, branch_ids: [branch?.id ?? ''] } };
    open({ ...owner, permission_scopes: scopes });
    fireEvent.change(screen.getByLabelText(/^Where/), { target: { value: other?.id } });
    type(/^Name/, 'Test Tray');
    type(/^Selling price/, '700');
    type(/How many do you have here now/, '2');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect(screen.queryByRole('button', { name: 'Sell it' })).toBeNull();
    expect(screen.getByTestId('qa-not-here').textContent).toBe(`You do not sell at ${branchLabel(other)}, so Sell it is not offered here.`);
  });

  it('asks for a buying price so profit is right, while it is empty', () => {
    open(owner);
    expect(screen.getByText('Add a buying price so profit is right. If you do not know it yet, buying stock sets it.')).toBeTruthy();
    type(/^Buying price/, '400');
    expect(screen.queryByText('Add a buying price so profit is right. If you do not know it yet, buying stock sets it.')).toBeNull();
  });

  it('adds the item with no stock for someone who may not add stock, and says to ask', async () => {
    quickAdd.mockResolvedValue({ product_id: 'p4', description: 'Test Pen', unit: 'pc', item_made: true });
    open(catalogueOnly());
    expect(screen.getByTestId('qa-where-empty').textContent).toContain('The item is added with no stock. Ask your manager to add its stock.');
    expect(screen.queryByLabelText(/How many do you have here now/)).toBeNull();
    type(/^Name/, 'Test Pen');
    type(/^Selling price/, '500');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    const body = (quickAdd.mock.calls[0] as [Record<string, unknown>])[0];
    expect(body).not.toHaveProperty('qty');
    expect(body).not.toHaveProperty('branch_id');
    expect(screen.queryByRole('button', { name: 'Sell it' })).toBeNull();
  });

  it('asks for a unit when the shop switched Piece off, and never sends without one', async () => {
    units = [{ id: 'u7', name: 'Piece', active: false, product_count: 0, version: 1 }, { id: 'u8', name: 'Box', active: true, product_count: 0, version: 1 }];
    open(owner);
    type(/^Name/, 'Test Glue');
    type(/^Selling price/, '1500');
    type(/How many do you have here now/, '4');
    // The units are read: Box is offered, and no line says Piece will be made.
    await screen.findByRole('option', { name: 'Box' });
    expect(screen.queryByTestId('qa-piece-made')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(await screen.findByText('The Piece unit is switched off. Choose a unit.')).toBeTruthy();
    expect(quickAdd).not.toHaveBeenCalled();
  });

  it('marks Where as needed', () => {
    open(owner);
    expect(screen.getByLabelText(/^Where/).getAttribute('aria-required')).toBe('true');
  });

  it('keeps the existing item for someone who may not add stock, and clears the name so Add never loops', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('This product already exists: Test Pen (GNR-0002).', 422, 'duplicate_product_name', {
      existing_id: 'p8', existing_code: 'GNR-0002', existing_name: 'Test Pen', may_add_anyway: false,
    }));
    open(catalogueOnly());
    type(/^Name/, 'Test Pen');
    type(/^Selling price/, '500');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const panel = await screen.findByTestId('name-exists');
    fireEvent.click(within(panel).getByRole('button', { name: 'Keep the existing one' }));
    expect(screen.getByTestId('qa-kept').textContent).toBe('Test Pen is already in your items. Nothing new was added. To add stock to it, ask your manager.');
    expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe('');
    expect(quickAdd).toHaveBeenCalledTimes(1);
  });

  it('after an answer that may hide a save (a 502), Add sends exactly the same request with the same key, whatever was typed since', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('Bad gateway', 502, 'bad_gateway'));
    quickAdd.mockResolvedValueOnce({ product_id: 'p5', description: 'Test Torch', unit: 'pc', item_made: true, branch_id: branch?.id, qty: '10', total_qty: '10' });
    open(owner);
    type(/^Name/, 'Test Torch');
    type(/^Selling price/, '8000');
    type(/How many do you have here now/, '10');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect((await screen.findByTestId('qa-not-sure')).textContent).toBe('We could not tell whether this was saved. Tap Add again: it sends exactly the same, and it is never added twice.');
    // A change made meanwhile is not sent: the request whose answer was lost goes again as it was.
    type(/How many do you have here now/, '20');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    const [first, second] = quickAdd.mock.calls as [Record<string, unknown>, string][];
    expect(second?.[1]).toBe(first?.[1]);
    expect(second?.[0]).toEqual(first?.[0]);
    expect((second?.[0] as { qty?: string }).qty).toBe('10.000');
  });

  it('locks the form while a send is not confirmed, and names the branch from the answer, never the select', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('Bad gateway', 502, 'bad_gateway'));
    // The replay adds at the first branch, whatever the select shows now.
    quickAdd.mockResolvedValueOnce({ product_id: 'p5', description: 'Test Kettle', unit: 'pc', item_made: true, branch_id: branch?.id, qty: '10', total_qty: '10' });
    open(owner);
    type(/^Name/, 'Test Kettle');
    type(/^Selling price/, '8000');
    type(/How many do you have here now/, '10');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-not-sure');
    expect((screen.getByTestId('qa-fields') as HTMLFieldSetElement).disabled).toBe(true);
    // Even a change that slips through (a script, an old browser) is not sent: the held request goes again.
    fireEvent.change(screen.getByLabelText(/^Where/), { target: { value: other?.id } });
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[1] as [Record<string, unknown>])[0]).toMatchObject({ branch_id: branch?.id });
    expect(screen.getByTestId('qa-done').textContent).toBe(`Test Kettle added: 10 pc at ${branchLabel(branch)}`);
  });

  it('offers Start again only after the server refused for certain, and it clears the form', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('Bad gateway', 502, 'bad_gateway'));
    quickAdd.mockRejectedValueOnce(new RetailError('Choose a category.', 422, 'validation_failed'));
    open(owner);
    type(/^Name/, 'Test Mat');
    type(/^Selling price/, '3000');
    type(/How many do you have here now/, '1');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-not-sure');
    expect(screen.queryByRole('button', { name: 'Start again' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Start again' }));
    expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe('');
    expect((screen.getByTestId('qa-fields') as HTMLFieldSetElement).disabled).toBe(false);
  });

  it('never refuses silently when the existing item is chosen with nothing to add', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('This product already exists: Test Rice 1kg (GNR-0001).', 422, 'duplicate_product_name', {
      existing_id: 'p9', existing_code: 'GNR-0001', existing_name: 'Test Rice 1kg', may_add_anyway: false,
    }));
    open(owner);
    type(/^Name/, 'Test Rice 1kg');
    type(/^Selling price/, '5000');
    type(/How many do you have here now/, '0');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    const panel = await screen.findByTestId('name-exists');
    fireEvent.click(within(panel).getByRole('button', { name: 'Use the existing one and just add stock' }));
    expect(screen.getByTestId('qa-panel-note').textContent).toBe('Type how many you are adding, more than 0, then tap Use the existing one and just add stock again.');
    expect(quickAdd).toHaveBeenCalledTimes(1);
  });

  it('takes a new key once the server refuses for certain', async () => {
    quickAdd.mockRejectedValueOnce(new RetailError('Choose a category.', 422, 'validation_failed'));
    quickAdd.mockResolvedValueOnce({ product_id: 'p6', description: 'Test Fan', unit: 'pc', item_made: true, qty: '1', total_qty: '1' });
    open(owner);
    type(/^Name/, 'Test Fan');
    type(/^Selling price/, '9000');
    type(/How many do you have here now/, '1');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await waitFor(() => expect(quickAdd).toHaveBeenCalledTimes(1));
    await screen.findByText('Choose a category.');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    await screen.findByTestId('qa-done');
    expect((quickAdd.mock.calls[1] as [unknown, string])[1]).not.toBe((quickAdd.mock.calls[0] as [unknown, string])[1]);
  });

  it('asks for the name, the price and how many before sending anything', async () => {
    open(owner);
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect(await screen.findByText('Type what the item is.')).toBeTruthy();
    expect(screen.getByText('Type how many you have, or 0.')).toBeTruthy();
    expect(quickAdd).not.toHaveBeenCalled();
  });

  it('says it needs the internet when offline, and keeps what was typed', async () => {
    const online = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false);
    open(owner, vi.fn(), 'Test Lamp');
    type(/^Selling price/, '3000');
    type(/How many do you have here now/, '2');
    fireEvent.click(screen.getByRole('button', { name: 'Add' }));
    expect((await screen.findByTestId('qa-offline')).textContent).toBe('You are offline. Quick add needs the internet. Connect, then tap Add again.');
    expect(quickAdd).not.toHaveBeenCalled();
    expect((screen.getByLabelText(/^Name/) as HTMLInputElement).value).toBe('Test Lamp');
    online.mockRestore();
  });
});

describe('the words and defaults', () => {
  it('names the same idempotency codes as the server', () => {
    const java = readFileSync(`${process.cwd()}/../backend/src/main/java/com/rincoltech/bms/core/operations/Idempotency.java`, 'utf8');
    for (const code of IDEMPOTENCY_UNSURE_CODES) expect(java).toContain(`= "${code}";`);
    expect(IDEMPOTENCY_UNSURE_CODES).toHaveLength(2);
  });

  it('counts only a 4xx refusal other than the answers about the key itself as certain', () => {
    expect(refusedForCertain(new RetailError('x', 422, 'validation_failed'))).toBe(true);
    for (const e of [new RetailError('x', 502), new RetailError('x', 504), new RetailError('x', 0), new RetailError('x', 409, 'idempotency_in_progress'), new RetailError('x', 422, 'idempotency_key_reused'), new TypeError('fetch')]) {
      expect(refusedForCertain(e)).toBe(false);
    }
  });

  it('prefers Piece, then pc, of the switched-on units', () => {
    expect(pieceUnit([{ id: 'a', name: 'Box' }, { id: 'b', name: 'pc' }, { id: 'c', name: 'Piece', active: false }])?.id).toBe('b');
    expect(pieceUnit([{ id: 'a', name: 'Box' }])).toBeUndefined();
  });

  it('says what was added, and nothing about stock when none was added', () => {
    expect(addedLine({ description: 'Test Bulb', unit: 'pc', item_made: true, qty: '600' }, 'Test Store')).toBe('Test Bulb added: 600 pc at Test Store');
    expect(addedLine({ description: 'Test Bulb', item_made: true }, 'Test Store')).toBe('Test Bulb added.');
    expect(addedLine({ description: 'Test Bulb', unit: 'pc', item_made: false, qty: '10', total_qty: '60' }, 'Test Store')).toBe('Test Bulb: 10 pc added at Test Store, 60 pc now');
  });
});
