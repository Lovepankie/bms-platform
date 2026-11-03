// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { AllBranchesStock, StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Where is my stock (issue #728): an owner added his items at his store, looked at My stock in his main shop, saw 0 and thought
// his numbers were lost; and he left two counts at Review the difference because the last step said Commit. My stock and the
// item's page say where the stock is (no switch there since issue #807); Quick add and Add an item name the shop beside How many; counts left
// unsaved are named with Finish it and Throw it away. Every name, figure and id below is invented.

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const listStockAllBranches = vi.fn();
const unfinishedStocktakes = vi.fn();
const discardStocktake = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    businessZone: () => 'Africa/Kampala',
    retail: {
      ...real.retail,
      listStockAllBranches: (...a: unknown[]) => listStockAllBranches(...a),
      unfinishedStocktakes: (...a: unknown[]) => unfinishedStocktakes(...a),
      discardStocktake: (...a: unknown[]) => discardStocktake(...a),
    },
  };
});

const { StockTable } = await import('./stock');
const { ItemElsewhere, otherStockShops, stockElsewhere } = await import('./stock-elsewhere');
const { UnfinishedCounts, savingCount, unfinishedWords } = await import('./unfinished-counts');
const { AddsTo } = await import('../branch-picker');

const owner = mockMe('admin');
const shop = owner.branches?.[0]?.id ?? '';
const store = owner.branches?.[1]?.id ?? '';
const storeName = owner.branches?.[1]?.name ?? '';

afterEach(() => {
  cleanup();
  listStockAllBranches.mockReset();
  unfinishedStocktakes.mockReset();
  discardStocktake.mockReset();
});

function show(node: ReactNode, me: Me = owner, chooseBranch = vi.fn(() => true)) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: shop, chooseBranch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
  return chooseBranch;
}

const row = (id: string, qty: string): StockRow => ({ product_id: id, code: id, description: `Test Bulb ${id}`, category: 'Bulbs', unit: 'piece', qty, negative: false, sell_minor: 2000 });
const everywhere: AllBranchesStock = {
  branches: [{ id: shop, name: 'Test Branch A' }, { id: store, name: storeName }],
  items: [
    { product_id: 'B-1', code: 'B-1', description: 'Test Bulb B-1', balances: [{ branch_id: shop, qty: '0.000' }, { branch_id: store, qty: '456.000' }] },
    { product_id: 'B-2', code: 'B-2', description: 'Test Bulb B-2', balances: [{ branch_id: shop, qty: '0.000' }, { branch_id: store, qty: '0.000' }] },
    { product_id: 'B-3', code: 'B-3', description: 'Test Bulb B-3', balances: [{ branch_id: shop, qty: '7.000' }, { branch_id: store, qty: '3.000' }] },
  ],
};

describe('My stock says where the stock is (issue #728)', () => {
  it('says "0 here. 456 at the store" under an item with none here, with no Switch button (issue #807)', () => {
    const elsewhere = stockElsewhere(everywhere, shop, otherStockShops(owner, shop));
    const choose = show(<StockTable rows={[row('B-1', '0.000'), row('B-2', '0.000'), row('B-3', '7.000')]} showCost={false} elsewhere={elsewhere} />);
    const note = screen.getByTestId('elsewhere-B-1');
    expect(within(note).getByText(`0 here. 456 at ${storeName}`)).toBeTruthy();
    expect(within(note).queryByRole('button')).toBeNull();
    expect(choose).not.toHaveBeenCalled();
    // None anywhere, or some here: nothing more is said.
    expect(screen.queryByTestId('elsewhere-B-2')).toBeNull();
    expect(screen.queryByTestId('elsewhere-B-3')).toBeNull();
  });

  it('names only a shop this person may read stock at, and the one holding the most', () => {
    const third = { id: 'b-third', code: 'BR3', name: 'Test Branch C' };
    const many: AllBranchesStock = { ...everywhere, items: [{ product_id: 'B-1', balances: [{ branch_id: shop, qty: '0' }, { branch_id: store, qty: '4' }, { branch_id: third.id, qty: '9' }] }] };
    const me = { ...owner, branches: [...(owner.branches ?? []), third] } as Me;
    expect(stockElsewhere(many, shop, otherStockShops(me, shop)).get('B-1')).toEqual({ branchId: third.id, name: 'Test Branch C', qty: '9' });
    // A seller who may read stock only at the shop is never told of the others.
    const seller = { ...me, permission_scopes: { ...me.permission_scopes, 'retail.stock.read': { all_branches: false, branch_ids: [shop] } } } as Me;
    expect(otherStockShops(seller, shop)).toEqual([]);
    expect(stockElsewhere(many, shop, otherStockShops(seller, shop)).size).toBe(0);
  });

  it('says the same on the item page, and reads nothing for someone with no other shop', async () => {
    listStockAllBranches.mockResolvedValue(everywhere);
    show(<ItemElsewhere product={{ id: 'B-1', code: 'B-1', description: 'Test Bulb B-1' }} />);
    expect(await screen.findByText(`0 here. 456 at ${storeName}`)).toBeTruthy();
    expect(listStockAllBranches).toHaveBeenCalledWith(expect.objectContaining({ query: 'B-1' }));
    cleanup();
    listStockAllBranches.mockClear();
    const alone = { ...owner, branches: owner.branches?.slice(0, 1) } as Me;
    show(<ItemElsewhere product={{ id: 'B-1', code: 'B-1' }} />, alone);
    expect(listStockAllBranches).not.toHaveBeenCalled();
  });
});

describe('the shop a form adds to (issue #728)', () => {
  it('names the shop beside How many and changes it with Change', () => {
    const choose = vi.fn();
    show(<AddsTo id="qa-where" value={store} places={owner.branches ?? []} onChoose={choose} />);
    expect(screen.getByTestId('qa-where-adds-to').textContent).toContain(`Adds to ${storeName} (BR2)`);
    fireEvent.change(screen.getByRole('combobox', { name: `Where: ${storeName} (BR2). Change` }), { target: { value: shop } });
    expect(choose).toHaveBeenCalledWith(shop);
  });

  it('has no Change with one place', () => {
    show(<AddsTo id="sale-new-where" value={shop} places={owner.branches?.slice(0, 1) ?? []} onChoose={vi.fn()} />);
    expect(screen.queryByRole('combobox')).toBeNull();
    expect(screen.queryByText('Change')).toBeNull();
  });
});

describe('counts left unsaved (issue #728)', () => {
  const open = { id: 'st-9', branch_id: shop, created_at: '2026-10-29T09:15:00Z', created_by: owner.user_id, lines: 4 };

  it('says when the count was started, and who', () => {
    const now = Date.parse('2026-11-12T10:00:00Z');
    expect(unfinishedWords(open, owner.user_id, 'Africa/Kampala', now)).toBe('You started a count here on 29 Oct and did not finish it.');
    expect(unfinishedWords(open, owner.user_id, 'Africa/Kampala', Date.parse('2026-10-29T15:00:00Z'))).toBe('You started a count here today and did not finish it.');
    expect(unfinishedWords({ ...open, created_by: 'someone-else' }, owner.user_id, 'Africa/Kampala', now)).toBe('Someone started a count here on 29 Oct and did not finish it.');
  });

  it('offers Finish it, and throws a count away only after Yes', async () => {
    unfinishedStocktakes.mockResolvedValue([open]);
    discardStocktake.mockResolvedValue({ ...open, status: 'discarded' });
    const onFinish = vi.fn();
    show(<UnfinishedCounts branchId={shop} onFinish={onFinish} />);
    const card = await screen.findByTestId('unfinished-st-9');
    expect(card.textContent).toMatch(/started a count here .* and did not finish it\./);
    fireEvent.click(within(card).getByRole('button', { name: 'Finish it' }));
    expect(onFinish).toHaveBeenCalledWith('st-9');

    fireEvent.click(within(card).getByRole('button', { name: 'Throw it away' }));
    expect(discardStocktake).not.toHaveBeenCalled();
    const ask = within(card).getByRole('group', { name: 'Throw this count away?' });
    fireEvent.click(within(ask).getByRole('button', { name: 'Keep it' }));
    expect(discardStocktake).not.toHaveBeenCalled();
    fireEvent.click(within(card).getByRole('button', { name: 'Throw it away' }));
    unfinishedStocktakes.mockResolvedValue([]);
    fireEvent.click(within(card).getByRole('button', { name: 'Yes, throw it away' }));
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('st-9'));
    expect(await screen.findByText('The count is thrown away. Your stock did not change.')).toBeTruthy();
    await waitFor(() => expect(screen.queryByTestId('unfinished-st-9')).toBeNull());
  });

  // Review of #729: a count Sell started says so, and throwing it away says the sale cannot save it any more.
  it('says when a count came from a sale, and what throwing it away does to the sale', async () => {
    const sale = { ...open, id: 'st-sale', note: 'Counted when the item was added on Sell' };
    expect(unfinishedWords(sale, owner.user_id, 'Africa/Kampala', Date.parse('2026-11-12T10:00:00Z'))).toBe('You started a count here from a sale on 29 Oct and did not finish it.');
    unfinishedStocktakes.mockResolvedValue([sale]);
    show(<UnfinishedCounts branchId={shop} />);
    const card = await screen.findByTestId('unfinished-st-sale');
    fireEvent.click(within(card).getByRole('button', { name: 'Throw it away' }));
    expect(card.textContent).toContain('the sale that started it cannot save it any more');
  });

  // Issue #730: a discard that reached the server first would cancel the save, so Throw it away waits for the save's answer.
  it('offers no Throw it away for a count whose save is on its way', async () => {
    unfinishedStocktakes.mockResolvedValue([open]);
    let answer: () => void = () => undefined;
    const save = savingCount('st-9', new Promise<void>((resolve) => { answer = resolve; }));
    show(<UnfinishedCounts branchId={shop} />);
    const card = await screen.findByTestId('unfinished-st-9');
    expect(within(card).queryByRole('button', { name: 'Throw it away' })).toBeNull();
    expect(card.textContent).toContain('This count is being saved now.');
    answer();
    await save;
    expect(await within(card).findByRole('button', { name: 'Throw it away' })).toBeTruthy();
  });

  it('on My stock, Finish it opens Count stock with the count', async () => {
    unfinishedStocktakes.mockResolvedValue([open]);
    show(<UnfinishedCounts branchId={shop} />);
    const link = await screen.findByRole('link', { name: 'Finish it' });
    expect(link.getAttribute('href')).toBe('/staff/retail/stocktake');
  });

  it('asks nothing for someone who may not count at the shop', () => {
    const seller = mockMe('sales');
    show(<UnfinishedCounts branchId={shop} />, seller);
    expect(unfinishedStocktakes).not.toHaveBeenCalled();
    expect(screen.queryByTestId('unfinished-counts')).toBeNull();
  });
});
