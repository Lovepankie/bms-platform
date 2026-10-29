// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { RetailError, type Product, type Stocktake } from '../../../api/retail';
import { bigDrop } from './maths';
import { mockMe } from '../../../api/retail-mock';
import { branchLabel } from '../../../auth/branch';
import { StaffContext } from '../context';
import { PICKER_LABEL } from '../../../test-utils/item-picker';
import { chooseCategory } from '../../../test-utils/category-picker';
import { OfflineProvider, type Offline } from '../../../offline/offline-context';
import { testConnectivity } from '../../../offline/test-kit';

// Issue #615: a new shop with no items opened Sell, searched, found nothing and had to ask how to add items. Now Sell and Home
// start with a card that leads to adding them (or says to ask the owner), and a Sell search that finds nothing offers Add it
// here, with what was typed, the category made inline when the shop has none, and the new item put on the sale with the count
// typed. Every item, category and amount is fabricated.

vi.setConfig({ testTimeout: 20000 });

let catalogue: Product[] = [];
let cats: Record<string, unknown>[] = [];
const createProduct = vi.fn();
const createCategory = vi.fn();
const createStocktake = vi.fn();
const commitStocktake = vi.fn();
const discardStocktake = vi.fn();
const similarProducts = vi.fn();
const listCatalogue = vi.fn();
const getProduct = vi.fn();
const listProducts = vi.fn();
const chooseBranch = vi.fn();
const made: Product = { id: 'p9', code: 'DUV-0001', description: 'Test duvet cover', category_id: 'c9', category: 'Duvets', unit_id: 'u1', unit: 'piece', sell_minor: 45000, cost_minor: 0, active: true, version: 1 };
const duvets = { id: 'c9', name: 'Duvets', active: true, product_count: 0, version: 1, code_prefix: 'DUV', next_code: 'DUV-0001' };

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to, className }: { children: unknown; to: string; className?: string }) => <a href={to} className={className}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCatalogue: (...a: unknown[]) => { listCatalogue(...a); return Promise.resolve({ items: catalogue }); },
      listProducts: (...a: unknown[]) => listProducts(...a),
      listCustomers: () => Promise.resolve([]),
      stockedBranches: () => Promise.resolve([]),
      listCategories: () => Promise.resolve(cats),
      createCategory: (...a: unknown[]) => createCategory(...a),
      suggestCategoryPrefix: () => Promise.resolve('DUV'),
      listUnits: () => Promise.resolve([{ id: 'u1', name: 'piece', active: true, product_count: 0, version: 1 }]),
      similarProducts: (...a: unknown[]) => similarProducts(...a),
      getProduct: (...a: unknown[]) => getProduct(...a),
      nameHints: () => Promise.resolve({}),
      createProduct: (...a: unknown[]) => createProduct(...a),
      createStocktake: (...a: unknown[]) => createStocktake(...a),
      commitStocktake: (...a: unknown[]) => commitStocktake(...a),
      discardStocktake: (...a: unknown[]) => discardStocktake(...a),
    },
  };
});
vi.mock('../../../api/retail-analytics', async (original) => {
  const real = await original<typeof import('../../../api/retail-analytics')>();
  return { ...real, retailAnalytics: { ...real.retailAnalytics, home: () => Promise.resolve({}) } };
});

const { RecordSale, SaleForm } = await import('./sale');
const { StaffHome } = await import('../home');
const { useActiveBranch } = await import('../branch-picker');

const owner = mockMe('admin');
const seller = mockMe('sales');

afterEach(() => {
  cleanup();
  sessionStorage.clear();
  catalogue = [];
  cats = [];
  createProduct.mockReset();
  createCategory.mockReset();
  createStocktake.mockReset();
  commitStocktake.mockReset();
  discardStocktake.mockReset();
  discardStocktake.mockImplementation((id: string) => Promise.resolve({ id, status: 'discarded' }));
  similarProducts.mockReset();
  similarProducts.mockResolvedValue({ exact: [], similar: [] });
  getProduct.mockReset();
  listProducts.mockReset();
  chooseBranch.mockReset();
  listProducts.mockImplementation(() => Promise.resolve(catalogue));
});
listProducts.mockImplementation(() => Promise.resolve(catalogue));
discardStocktake.mockImplementation((id: string) => Promise.resolve({ id, status: 'discarded' }));

similarProducts.mockResolvedValue({ exact: [], similar: [] });

function open(node: JSX.Element, me: Me) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '', chooseBranch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

// The kit's Card is a section with its title as a heading.
const card = async () => (await screen.findByRole('heading', { name: 'Get your shop ready' })).closest('section') as HTMLElement;

describe('the starting card of a shop with no items', () => {
  it.each([['Sell', <RecordSale key="sale" />], ['Home', <StaffHome key="home" />]])('leads the owner to add items or import a list on %s', async (_, page) => {
    open(page, owner);
    const shown = await card();
    expect(within(shown).getByText('Your shop has no items yet. Add the things you sell, then you can sell them.')).toBeTruthy();
    expect(within(shown).getByRole('link', { name: 'Add your first items' }).getAttribute('href')).toBe('/staff/retail/catalogue/products');
    expect(within(shown).getByRole('link', { name: 'Import a list' }).getAttribute('href')).toBe('/staff/retail/catalogue/import');
    // Issue #625: the starting categories, units and packs of a kind of shop are one tap away too.
    expect(within(shown).getByRole('link', { name: 'Set up your shop' }).getAttribute('href')).toBe('/staff/retail/catalogue/set-up');
    expect(within(shown).queryByText('Ask the owner to add the items first.')).toBeNull();
  });

  it.each([['Sell', <RecordSale key="sale" />], ['Home', <StaffHome key="home" />]])('tells someone who cannot add items to ask the owner on %s', async (_, page) => {
    open(page, seller);
    const shown = await card();
    expect(within(shown).getByText('Ask the owner to add the items first.')).toBeTruthy();
    expect(within(shown).queryByRole('link')).toBeNull();
  });

  it('is first on Sell, and the branch-holds-no-stock warning stays away', async () => {
    open(<RecordSale />, owner);
    const shown = await card();
    expect(shown.compareDocumentPosition(screen.getByRole('combobox', { name: PICKER_LABEL })) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.queryByText('This branch holds no stock. Switch branch?')).toBeNull();
  });

  it('is not drawn once the shop has an item, even one switched off: the read asks for every item, on or off', async () => {
    catalogue = [{ ...made, active: false }];
    open(<RecordSale />, owner);
    await screen.findByRole('combobox', { name: PICKER_LABEL });
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByRole('heading', { name: 'Get your shop ready' })).toBeNull();
    expect(listCatalogue.mock.calls[0]?.[0]).not.toHaveProperty('active');
  });
});

describe('a Sell search that finds nothing', () => {
  const search = async (text: string) => {
    fireEvent.change(await screen.findByRole('combobox', { name: PICKER_LABEL }), { target: { value: text } });
    fireEvent.keyDown(screen.getByRole('combobox', { name: PICKER_LABEL }), { key: 'Enter' });
  };
  const form = () => screen.getByRole('group', { name: 'Add a new item' });
  /** Searches, opens Add it here and fills the form with the category Duvets already in the shop. */
  const fill = async (count?: string) => {
    cats = [duvets];
    await search('Test duvet cover');
    fireEvent.click(await screen.findByRole('button', { name: 'Not in the list? Add it here' }));
    await chooseCategory(within(form()).getByLabelText(/^Category/), 'Duvets');
    fireEvent.change(within(form()).getByLabelText(/^Counted in/), { target: { value: 'u1' } });
    const cost = within(form()).queryByLabelText(/^Buying price/);
    if (cost) fireEvent.change(cost, { target: { value: '30000' } });
    fireEvent.change(within(form()).getByLabelText(/^Selling price/), { target: { value: '45000' } });
    if (count !== undefined) fireEvent.change(within(form()).getByLabelText(/^How many you have here/), { target: { value: count } });
    fireEvent.click(within(form()).getByRole('button', { name: 'Save the item' }));
  };

  it('offers Add it here with what was typed, makes the category inline, counts the item and puts it on the sale', async () => {
    createCategory.mockImplementation(async () => { cats = [duvets]; return duvets; });
    // The server now lists the item, so the card of a shop with no items goes once the catalogue reads again.
    createProduct.mockImplementation(async () => { catalogue = [made]; return made; });
    createStocktake.mockResolvedValue({ id: 's1' });
    commitStocktake.mockResolvedValue({ id: 's1', status: 'committed' });
    open(<RecordSale />, owner);
    await card();
    await search('Test duvet cover');
    fireEvent.click(await screen.findByRole('button', { name: 'Not in the list? Add it here' }));
    expect((within(form()).getByLabelText(/^Description/) as HTMLInputElement).value).toBe('Test duvet cover');

    // A first shop has no category: the field says so (issue #619), makes one right there and chooses it.
    expect(await within(form()).findByText('No categories yet.')).toBeTruthy();
    fireEvent.click(within(form()).getByRole('button', { name: '+ Add a category' }));
    fireEvent.change(await screen.findByLabelText(/^Name of the new category/), { target: { value: 'Duvets' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add category' }));
    await waitFor(() => expect(within(form()).getByLabelText(/^Category/).textContent).toBe('Duvets'));
    expect(createCategory).toHaveBeenCalledWith('Duvets');

    fireEvent.change(within(form()).getByLabelText(/^Counted in/), { target: { value: 'u1' } });
    fireEvent.change(within(form()).getByLabelText(/^Buying price/), { target: { value: '30000' } });
    fireEvent.change(within(form()).getByLabelText(/^Selling price/), { target: { value: '45000' } });
    fireEvent.change(within(form()).getByLabelText(/^How many you have here/), { target: { value: '4' } });
    fireEvent.click(within(form()).getByRole('button', { name: 'Save the item' }));

    await waitFor(() => expect(commitStocktake).toHaveBeenCalledWith('s1'));
    expect(createProduct.mock.calls[0]?.[0]).toMatchObject({ description: 'Test duvet cover', category_id: 'c9', unit_id: 'u1', sell_minor: 45000 });
    expect(createStocktake.mock.calls[0]?.[0]).toMatchObject({ branch_id: owner.branches?.[0]?.id, lines: [{ product_id: 'p9', counted_qty: '4.000' }] });
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    expect(screen.queryByRole('group', { name: 'Add a new item' })).toBeNull();
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Get your shop ready' })).toBeNull());
  });

  it('asks for a count above 0, so a new item never goes on the sale with nothing to sell', async () => {
    open(<RecordSale />, owner);
    await fill('0');
    expect(await within(form()).findByText('Type how many you have here, more than 0.')).toBeTruthy();
    expect(createProduct).not.toHaveBeenCalled();
  });

  it('never counts an existing item chosen with Use this item: it goes on the sale with its own stock', async () => {
    const existing: Product = { ...made, id: 'p1', code: 'DUV-0007' };
    similarProducts.mockResolvedValue({ exact: [{ id: 'p1', code: 'DUV-0007', description: 'Test duvet cover' }], similar: [] });
    getProduct.mockResolvedValue(existing);
    // The search a name differs from finds nothing; the item's own code finds it, with this branch's stock.
    listProducts.mockImplementation(({ query }: { query?: string }) => Promise.resolve(query === 'DUV-0007' ? [{ ...existing, qty: '3.000' }] : []));
    open(<RecordSale />, owner);
    await fill('4');
    fireEvent.click(await within(form()).findByRole('button', { name: 'Use this item' }));
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    expect(createProduct).not.toHaveBeenCalled();
    expect(createStocktake).not.toHaveBeenCalled();
    expect(await screen.findByText('Test duvet cover is already in your items, so its stock is not changed here. If it is wrong, use Count stock.')).toBeTruthy();
  });

  it('keeps an existing item with no stock here off the sale, and says to buy or count stock first (review of #616)', async () => {
    const existing: Product = { ...made, id: 'p1', code: 'DUV-0007' };
    similarProducts.mockResolvedValue({ exact: [{ id: 'p1', code: 'DUV-0007', description: 'Test duvet cover' }], similar: [] });
    getProduct.mockResolvedValue(existing);
    listProducts.mockImplementation(({ query }: { query?: string }) => Promise.resolve(query === 'DUV-0007' ? [{ ...existing, qty: '0.000' }] : []));
    open(<RecordSale />, owner);
    await fill('4');
    fireEvent.click(await within(form()).findByRole('button', { name: 'Use this item' }));
    expect(await screen.findByText('Test duvet cover is already in your items, but it has no stock here yet, so it is not on the sale. Use Buy stock or Count stock first.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Remove Test duvet cover' })).toBeNull();
    expect(createStocktake).not.toHaveBeenCalled();
  });

  it('keeps a new item off the sale for someone who may not count stock here, and says how to put stock in', async () => {
    const counts = 'retail.stocktake.commit';
    const manager = { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== counts), permission_scopes: Object.fromEntries(Object.entries(owner.permission_scopes ?? {}).filter(([p]) => p !== counts)) } as Me;
    createProduct.mockResolvedValue(made);
    open(<RecordSale />, manager);
    await fill();
    expect(await screen.findByText('Test duvet cover is saved. It has no stock here yet: use Buy stock or Count stock, then sell it.')).toBeTruthy();
    expect(createProduct).toHaveBeenCalled();
    expect(createStocktake).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Remove Test duvet cover' })).toBeNull();
  });

  it('keeps the item off the sale when its count fails, and says to count it', async () => {
    createProduct.mockResolvedValue(made);
    createStocktake.mockRejectedValue(new Error('down'));
    open(<RecordSale />, owner);
    await fill('4');
    expect(await screen.findByText('Test duvet cover is saved, but its count is not. Count it on Count stock, then sell it.')).toBeTruthy();
    expect(commitStocktake).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Remove Test duvet cover' })).toBeNull();
  });

  // Issue #636: the server refuses a commit that leaves out a big drop. Here the commit is the server's rule, on the shared bigDrop.
  const serverCommit = (draft: Stocktake) => (id: string, acknowledged: string[] = []) => {
    const unticked = (draft.lines ?? []).filter((l) => bigDrop(l) && !acknowledged.includes(l.product_id ?? ''));
    return unticked.length > 0 ? Promise.reject(new RetailError('Tick the lines marked before you commit', 409, 'big_drop_not_acknowledged')) : Promise.resolve({ ...draft, id, status: 'committed' });
  };
  const draftOf = (expected: string): Stocktake => ({ id: 's1', status: 'draft', lines: [{ product_id: 'p9', code: 'DUV-0001', description: 'Test duvet cover', expected_qty: expected, counted_qty: '4.000', variance_qty: (4 - Number(expected)).toFixed(3) }] });

  it('commits the count of a new item, which holds nothing, so the server never asks for a tick (issue #636)', async () => {
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('0.000'));
    commitStocktake.mockImplementation(serverCommit(draftOf('0.000')));
    open(<RecordSale />, owner);
    await fill('4');
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    expect(commitStocktake).toHaveBeenCalledWith('s1');
    expect(screen.queryByText(/its count is not/)).toBeNull();
  });

  const sheet: Product = { ...made, id: 'p8', code: 'DUV-0003', description: 'Test bed sheet' };
  // Issue #656: the records already hold 10 of the item just made, so a count of 4 is a big drop, asked before any draft exists.
  const holdsTen = () => listProducts.mockImplementation(({ query }: { query?: string }) => Promise.resolve(query === 'DUV-0001' ? [{ ...made, qty: '10.000' }] : []));
  const tickAndCommitPillow = (ask: HTMLElement) => {
    fireEvent.click(within(ask).getByRole('checkbox', { name: 'Yes, Test pillow case is right' }));
    fireEvent.click(within(ask).getByRole('button', { name: 'Commit the count' }));
  };
  const tickAndCommit = (ask: HTMLElement) => {
    fireEvent.click(within(ask).getByRole('checkbox', { name: 'Yes, Test duvet cover is right' }));
    fireEvent.click(within(ask).getByRole('button', { name: 'Commit the count' }));
  };

  it('asks the tick of a big drop before any draft is made, then makes and commits it acknowledged (issues #636, #656)', async () => {
    holdsTen();
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('10.000'));
    commitStocktake.mockImplementation(serverCommit(draftOf('10.000')));
    open(<RecordSale />, owner);
    await fill('4');
    const ask = await screen.findByTestId('sale-count-drop-p9');
    expect(ask.textContent).toContain('The records show 10 of Test duvet cover here, and you counted 4. This removes 6. Is that right?');
    expect(createStocktake).not.toHaveBeenCalled();
    // Commit the count waits for the tick.
    fireEvent.click(within(ask).getByRole('button', { name: 'Commit the count' }));
    expect(createStocktake).not.toHaveBeenCalled();
    tickAndCommit(ask);
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledWith('s1', ['p9']));
    expect(createStocktake).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    expect(screen.queryByTestId('sale-count-drop-p9')).toBeNull();
  });

  it('leaves no draft when the screen is left or Not now is pressed while the tick is asked (issue #656)', async () => {
    holdsTen();
    createProduct.mockResolvedValue(made);
    open(<RecordSale />, owner);
    await fill('4');
    fireEvent.click(within(await screen.findByTestId('sale-count-drop-p9')).getByRole('button', { name: 'Not now' }));
    expect(await screen.findByText('Test duvet cover is saved. It is not on the sale: count it on Count stock, then sell it.')).toBeTruthy();
    expect(screen.queryByTestId('sale-count-drop-p9')).toBeNull();
    cleanup();
    // Leaving the screen while the tick is asked.
    open(<RecordSale />, owner);
    await fill('4');
    expect(await screen.findByTestId('sale-count-drop-p9')).toBeTruthy();
    cleanup();
    expect(createStocktake).not.toHaveBeenCalled();
    expect(commitStocktake).not.toHaveBeenCalled();
  });

  it('gives two big drops on one sale a tick and a count each, and never carries a tick to the other item (issue #656)', async () => {
    const pillow: Product = { ...made, id: 'p10', code: 'DUV-0002', description: 'Test pillow case' };
    listProducts.mockImplementation(({ query }: { query?: string }) =>
      Promise.resolve(query === 'DUV-0001' ? [{ ...made, qty: '10.000' }] : query === 'DUV-0002' ? [{ ...pillow, qty: '6.000' }] : []));
    createProduct.mockResolvedValueOnce(made).mockResolvedValueOnce(pillow);
    createStocktake.mockImplementation(({ lines }: { lines: { product_id: string }[] }) => Promise.resolve({ id: `s-${lines[0]!.product_id}`, status: 'draft', lines: [] }));
    commitStocktake.mockImplementation((id: string) => Promise.resolve({ id, status: 'committed' }));
    open(<RecordSale />, owner);
    await fill('4');
    const first = await screen.findByTestId('sale-count-drop-p9');
    fireEvent.click(within(first).getByRole('checkbox', { name: 'Yes, Test duvet cover is right' }));
    await fill('2');
    const second = await screen.findByTestId('sale-count-drop-p10');
    expect(second.textContent).toContain('The records show 6 of Test pillow case here, and you counted 2. This removes 4. Is that right?');
    // The first tick stays with the first item: the second asks its own.
    expect((within(first).getByRole('checkbox') as HTMLInputElement).checked).toBe(true);
    expect((within(second).getByRole('checkbox', { name: 'Yes, Test pillow case is right' }) as HTMLInputElement).checked).toBe(false);
    fireEvent.click(within(second).getByRole('button', { name: 'Commit the count' }));
    expect(createStocktake).not.toHaveBeenCalled();
    fireEvent.click(within(first).getByRole('button', { name: 'Commit the count' }));
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledWith('s-p9', ['p9']));
    tickAndCommitPillow(await screen.findByTestId('sale-count-drop-p10'));
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledWith('s-p10', ['p10']));
    expect(createStocktake).toHaveBeenCalledTimes(2);
    expect(await screen.findByRole('button', { name: 'Remove Test pillow case' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
  });

  it('leaves no draft when the sale is cleared while a tick is asked (issue #656)', async () => {
    holdsTen();
    createProduct.mockResolvedValueOnce(sheet).mockResolvedValueOnce(made);
    createStocktake.mockResolvedValue({ id: 's8', status: 'draft', lines: [{ product_id: 'p8', expected_qty: '0.000', counted_qty: '3.000' }] });
    commitStocktake.mockResolvedValue({ id: 's8', status: 'committed' });
    open(<RecordSale />, owner);
    await fill('3');
    expect(await screen.findByRole('button', { name: 'Remove Test bed sheet' })).toBeTruthy();
    await fill('4');
    expect(await screen.findByTestId('sale-count-drop-p9')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    await waitFor(() => expect(screen.queryByTestId('sale-count-drop-p9')).toBeNull());
    expect(createStocktake).toHaveBeenCalledTimes(1);
    expect(commitStocktake).toHaveBeenCalledTimes(1);
  });

  it('leaves no draft when the branch is switched while a tick is asked (issue #656)', async () => {
    holdsTen();
    createProduct.mockResolvedValue(made);
    const [here, other] = [owner.branches?.[0]?.id ?? '', owner.branches?.[1]?.id ?? 'other'];
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    // RecordSale makes the form again for each branch, keyed by it.
    const at = (branch: string) => (
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: owner, branch, chooseBranch }}><SaleForm key={branch} branchId={branch} /></StaffContext.Provider>
      </QueryClientProvider>
    );
    const { rerender } = render(at(here));
    await fill('4');
    expect(await screen.findByTestId('sale-count-drop-p9')).toBeTruthy();
    rerender(at(other));
    await waitFor(() => expect(screen.queryByTestId('sale-count-drop-p9')).toBeNull());
    expect(createStocktake).not.toHaveBeenCalled();
    expect(commitStocktake).not.toHaveBeenCalled();
  });

  it('sends the same draft again after a lost commit, never a second one (issue #656)', async () => {
    holdsTen();
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('10.000'));
    commitStocktake.mockRejectedValueOnce(new RetailError('Offline', 503, 'unavailable')).mockImplementation(serverCommit(draftOf('10.000')));
    open(<RecordSale />, owner);
    await fill('4');
    tickAndCommit(await screen.findByTestId('sale-count-drop-p9'));
    expect(await screen.findByText('The count of Test duvet cover is not committed. Try again.')).toBeTruthy();
    fireEvent.click(within(screen.getByTestId('sale-count-drop-p9')).getByRole('button', { name: 'Commit the count' }));
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    expect(createStocktake).toHaveBeenCalledTimes(1);
    expect(commitStocktake.mock.calls).toEqual([['s1', ['p9']], ['s1', ['p9']]]);
  });

  // Issue #656, round 3: a draft exists once Commit the count was pressed; when its commit is lost it is discarded on every way out.
  const lostCommit = () => {
    holdsTen();
    createProduct.mockResolvedValueOnce(sheet).mockResolvedValue(made);
    createStocktake.mockImplementation(({ lines }: { lines: { product_id: string }[] }) =>
      Promise.resolve(lines[0]!.product_id === 'p8' ? { id: 's8', status: 'draft', lines: [{ product_id: 'p8', expected_qty: '0.000', counted_qty: '3.000' }] } : draftOf('10.000')));
    commitStocktake.mockImplementation((id: string) => (id === 's8' ? Promise.resolve({ id, status: 'committed' }) : Promise.reject(new RetailError('Offline', 503, 'unavailable'))));
  };
  /** A bed sheet on the sale, then the duvet cover's count ticked and its commit lost, so its draft s1 is open. */
  const toLostCommit = async () => {
    await fill('3');
    expect(await screen.findByRole('button', { name: 'Remove Test bed sheet' })).toBeTruthy();
    await fill('4');
    tickAndCommit(await screen.findByTestId('sale-count-drop-p9'));
    expect(await screen.findByText('The count of Test duvet cover is not committed. Try again.')).toBeTruthy();
    expect(createStocktake).toHaveBeenCalledTimes(2);
    expect(discardStocktake).not.toHaveBeenCalled();
  };

  it('discards the draft of a lost commit when the sale is cleared (issue #656)', async () => {
    lostCommit();
    open(<RecordSale />, owner);
    await toLostCommit();
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('s1'));
    expect(screen.queryByTestId('sale-count-drop-p9')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove Test bed sheet' })).toBeNull();
    expect(sessionStorage.getItem('sale-held-counts')).toBe('[]');
  });

  it('discards the draft of a lost commit when the screen is left (issue #656)', async () => {
    lostCommit();
    open(<RecordSale />, owner);
    await toLostCommit();
    cleanup();
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('s1'));
    expect(discardStocktake).toHaveBeenCalledTimes(1);
  });

  it('discards the draft of a lost commit when Not now is pressed (issue #656)', async () => {
    lostCommit();
    open(<RecordSale />, owner);
    await toLostCommit();
    fireEvent.click(within(screen.getByTestId('sale-count-drop-p9')).getByRole('button', { name: 'Not now' }));
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('s1'));
    expect(screen.queryByTestId('sale-count-drop-p9')).toBeNull();
    expect(screen.getByRole('button', { name: 'Remove Test bed sheet' })).toBeTruthy();
  });

  it('discards the draft of a lost commit when the branch is switched (issue #656)', async () => {
    lostCommit();
    const [here, other] = [owner.branches?.[0]?.id ?? '', owner.branches?.[1]?.id ?? 'other'];
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const at = (branch: string) => (
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: owner, branch, chooseBranch }}><SaleForm key={branch} branchId={branch} /></StaffContext.Provider>
      </QueryClientProvider>
    );
    const { rerender } = render(at(here));
    await toLostCommit();
    rerender(at(other));
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('s1'));
    expect(screen.queryByTestId('sale-count-drop-p9')).toBeNull();
  });

  it('offline, keeps the sale and the branch until the draft of a lost commit is committed or discarded (issue #656)', async () => {
    lostCommit();
    const network = testConnectivity(true);
    const offline = { scope: 'test', connectivity: network, catalogue: null, engine: { subscribe: () => () => undefined, snapshot: () => ({ items: [], syncing: false, needsSignIn: false }) } } as unknown as Offline;
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    // The staff area's branch switch, with the sale form made again for each branch as RecordSale does.
    function Shell() {
      const { branch, choose } = useActiveBranch(owner);
      if (!branch) return null;
      return (
        <StaffContext.Provider value={{ me: owner, branch, chooseBranch: choose }}>
          <p data-testid="branch-in-use">{branch}</p>
          <button type="button" onClick={() => choose((owner.branches ?? []).find((b) => b.id !== branch)?.id ?? '')}>Switch branch</button>
          <SaleForm key={branch} branchId={branch} />
        </StaffContext.Provider>
      );
    }
    render(<QueryClientProvider client={client}><OfflineProvider value={offline}><Shell /></OfflineProvider></QueryClientProvider>);
    const started = (await screen.findByTestId('branch-in-use')).textContent;
    await toLostCommit();
    act(() => network.set(false));
    const words = 'A count on this sale is not committed. Connect to the internet, then commit the count or press Not now, before you clear the sale or switch branch.';
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    expect(await screen.findByText(words)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Remove Test bed sheet' })).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Switch branch' }));
    expect(screen.getByTestId('branch-in-use').textContent).toBe(started);
    fireEvent.click(within(screen.getByTestId('sale-count-drop-p9')).getByRole('button', { name: 'Not now' }));
    expect(screen.getByTestId('sale-count-drop-p9')).toBeTruthy();
    expect(discardStocktake).not.toHaveBeenCalled();
    // Back online, the draft is still this sale's: nothing is discarded until the person clears it.
    act(() => network.set(true));
    expect(screen.queryByText(words)).toBeNull();
    expect(discardStocktake).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Switch branch' }));
    await waitFor(() => expect(screen.getByTestId('branch-in-use').textContent).not.toBe(started));
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('s1'));
  });

  it('moves off a branch taken away while offline once the connection comes back and the hold lifts (issue #664)', async () => {
    lostCommit();
    const network = testConnectivity(true);
    const offline = { scope: 'test', connectivity: network, catalogue: null, engine: { subscribe: () => () => undefined, snapshot: () => ({ items: [], syncing: false, needsSignIn: false }) } } as unknown as Offline;
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    function Shell({ me }: { me: Me }) {
      const { branch } = useActiveBranch(me);
      if (!branch) return null;
      return (
        <StaffContext.Provider value={{ me, branch, chooseBranch }}>
          <p data-testid="branch-in-use">{branch}</p>
          <SaleForm key={branch} branchId={branch} />
        </StaffContext.Provider>
      );
    }
    const at = (me: Me) => <QueryClientProvider client={client}><OfflineProvider value={offline}><Shell me={me} /></OfflineProvider></QueryClientProvider>;
    const { rerender } = render(at(owner));
    const started = (await screen.findByTestId('branch-in-use')).textContent;
    await toLostCommit();
    act(() => network.set(false));
    // The profile read again no longer lists the branch in use: offline the sale holds it, with its draft.
    rerender(at({ ...owner, branches: (owner.branches ?? []).filter((b) => b.id !== started) }));
    await act(async () => undefined);
    expect(screen.getByTestId('branch-in-use').textContent).toBe(started);
    expect(discardStocktake).not.toHaveBeenCalled();
    act(() => network.set(true));
    await waitFor(() => expect(screen.getByTestId('branch-in-use').textContent).not.toBe(started));
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('s1'));
  });

  it('sends a discard lost to leaving the screen again the next time Sell opens online (issue #656)', async () => {
    sessionStorage.setItem('sale-held-counts', JSON.stringify(['s-left']));
    open(<RecordSale />, owner);
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('s-left'));
    await waitFor(() => expect(sessionStorage.getItem('sale-held-counts')).toBe('[]'));
  });

  it('ignores a count answered after the sale was cleared, and discards its draft (issue #656)', async () => {
    holdsTen();
    createProduct.mockResolvedValueOnce(sheet).mockResolvedValue(made);
    let made9: (s: Stocktake) => void = () => undefined;
    createStocktake.mockImplementation(({ lines }: { lines: { product_id: string }[] }) =>
      lines[0]!.product_id === 'p8' ? Promise.resolve({ id: 's8', status: 'draft', lines: [] }) : new Promise<Stocktake>((resolve) => { made9 = resolve; }));
    commitStocktake.mockImplementation((id: string) => Promise.resolve({ id, status: 'committed' }));
    open(<RecordSale />, owner);
    await fill('3');
    expect(await screen.findByRole('button', { name: 'Remove Test bed sheet' })).toBeTruthy();
    await fill('4');
    tickAndCommit(await screen.findByTestId('sale-count-drop-p9'));
    await waitFor(() => expect(createStocktake).toHaveBeenCalledTimes(2));
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    await act(async () => made9(draftOf('10.000')));
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('s1'));
    expect(commitStocktake).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('button', { name: 'Remove Test duvet cover' })).toBeNull();
    expect(screen.queryByTestId('sale-count-drop-p9')).toBeNull();
  });

  it('puts nothing on a cleared sale when a commit on its way lands after Clear this sale (issue #656)', async () => {
    holdsTen();
    createProduct.mockResolvedValueOnce(sheet).mockResolvedValue(made);
    createStocktake.mockImplementation(({ lines }: { lines: { product_id: string }[] }) =>
      Promise.resolve(lines[0]!.product_id === 'p8' ? { id: 's8', status: 'draft', lines: [] } : draftOf('10.000')));
    let landed: (s: Stocktake) => void = () => undefined;
    commitStocktake.mockImplementation((id: string) => (id === 's8' ? Promise.resolve({ id, status: 'committed' }) : new Promise<Stocktake>((resolve) => { landed = resolve; })));
    open(<RecordSale />, owner);
    await fill('3');
    expect(await screen.findByRole('button', { name: 'Remove Test bed sheet' })).toBeTruthy();
    await fill('4');
    tickAndCommit(await screen.findByTestId('sale-count-drop-p9'));
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledWith('s1', ['p9']));
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    await act(async () => landed({ ...draftOf('10.000'), status: 'committed' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Remove Test bed sheet' })).toBeNull());
    expect(screen.queryByRole('button', { name: 'Remove Test duvet cover' })).toBeNull();
    expect(screen.queryByTestId('sale-count-drop-p9')).toBeNull();
  });

  it('keeps each item busy on its own while two counts are on their way (issue #656)', async () => {
    const pillow: Product = { ...made, id: 'p10', code: 'DUV-0002', description: 'Test pillow case' };
    listProducts.mockImplementation(({ query }: { query?: string }) =>
      Promise.resolve(query === 'DUV-0001' ? [{ ...made, qty: '10.000' }] : query === 'DUV-0002' ? [{ ...pillow, qty: '6.000' }] : []));
    createProduct.mockResolvedValueOnce(made).mockResolvedValueOnce(pillow);
    const waiting = new Map<string, (s: Stocktake) => void>();
    createStocktake.mockImplementation(({ lines }: { lines: { product_id: string }[] }) =>
      new Promise<Stocktake>((resolve) => waiting.set(lines[0]!.product_id, resolve)));
    commitStocktake.mockImplementation((id: string) => Promise.resolve({ id, status: 'committed' }));
    open(<RecordSale />, owner);
    await fill('4');
    await fill('2');
    tickAndCommit(await screen.findByTestId('sale-count-drop-p9'));
    tickAndCommitPillow(await screen.findByTestId('sale-count-drop-p10'));
    await waitFor(() => expect(waiting.size).toBe(2));
    // Both on their way: neither offers Not now, so neither can be dropped while its count is made.
    expect(within(screen.getByTestId('sale-count-drop-p9')).queryByRole('button', { name: 'Not now' })).toBeNull();
    expect(within(screen.getByTestId('sale-count-drop-p10')).queryByRole('button', { name: 'Not now' })).toBeNull();
    await act(async () => waiting.get('p10')!({ id: 's-p10', status: 'draft', lines: [] }));
    expect(await screen.findByRole('button', { name: 'Remove Test pillow case' })).toBeTruthy();
    // The first is still on its way: still busy, still no Not now.
    expect(within(screen.getByTestId('sale-count-drop-p9')).queryByRole('button', { name: 'Not now' })).toBeNull();
    await act(async () => waiting.get('p9')!({ id: 's-p9', status: 'draft', lines: [] }));
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    expect(commitStocktake.mock.calls).toEqual([['s-p10', ['p10']], ['s-p9', ['p9']]]);
    expect(discardStocktake).not.toHaveBeenCalled();
  });

  it('takes a commit sent again that the server already took as committed (issue #656)', async () => {
    holdsTen();
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('10.000'));
    commitStocktake.mockRejectedValueOnce(new RetailError('Offline', 503, 'unavailable')).mockRejectedValue(new RetailError('Stock count committed', 409, 'stocktake_committed'));
    open(<RecordSale />, owner);
    await fill('4');
    tickAndCommit(await screen.findByTestId('sale-count-drop-p9'));
    expect(await screen.findByText('The count of Test duvet cover is not committed. Try again.')).toBeTruthy();
    fireEvent.click(within(screen.getByTestId('sale-count-drop-p9')).getByRole('button', { name: 'Commit the count' }));
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    expect(discardStocktake).not.toHaveBeenCalled();
  });

  it('shows the server words of a refusal and stops asking, since a second press never passes (issue #656)', async () => {
    holdsTen();
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('10.000'));
    commitStocktake.mockRejectedValue(new RetailError('Count it again', 409, 'stock_moved_since_count', { detail: 'Stock of Test duvet cover moved since the count. Count it again.' }));
    open(<RecordSale />, owner);
    await fill('4');
    tickAndCommit(await screen.findByTestId('sale-count-drop-p9'));
    expect(await screen.findByText('Stock of Test duvet cover moved since the count. Count it again. Test duvet cover is not on the sale: use Count stock, then sell it.')).toBeTruthy();
    expect(screen.queryByTestId('sale-count-drop-p9')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Remove Test duvet cover' })).toBeNull();
    // A refusal never passes on a second press, so its draft is not left open.
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('s1'));
  });

  it('never sends a draft that turned into a big drop after the balance was read (issue #636)', async () => {
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('10.000'));
    commitStocktake.mockImplementation(serverCommit(draftOf('10.000')));
    open(<RecordSale />, owner);
    await fill('4');
    expect(await screen.findByText('Test duvet cover is saved, but its count is not. Count it on Count stock, then sell it.')).toBeTruthy();
    expect(commitStocktake).not.toHaveBeenCalled();
    // The draft that turned into a big drop is discarded, not left open (issue #656).
    expect(discardStocktake).toHaveBeenCalledWith('s1');
  });

  it('puts nothing on a cleared sale when the count of a new item lands after Clear this sale, and keeps the count (issue #656)', async () => {
    createProduct.mockResolvedValueOnce(sheet).mockResolvedValue(made);
    createStocktake.mockImplementation(({ lines }: { lines: { product_id: string }[] }) =>
      Promise.resolve(lines[0]!.product_id === 'p8' ? { id: 's8', status: 'draft', lines: [] } : draftOf('0.000')));
    let landed: (s: Stocktake) => void = () => undefined;
    commitStocktake.mockImplementation((id: string) => (id === 's8' ? Promise.resolve({ id, status: 'committed' }) : new Promise<Stocktake>((resolve) => { landed = resolve; })));
    open(<RecordSale />, owner);
    await fill('3');
    expect(await screen.findByRole('button', { name: 'Remove Test bed sheet' })).toBeTruthy();
    await fill('4');
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledWith('s1'));
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    await act(async () => landed({ ...draftOf('0.000'), status: 'committed' }));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Remove Test bed sheet' })).toBeNull());
    expect(screen.queryByRole('button', { name: 'Remove Test duvet cover' })).toBeNull();
    expect(screen.queryByText(/its count is not/)).toBeNull();
    expect(discardStocktake).not.toHaveBeenCalled();
  });

  it('asks no tick on a cleared sale when the balance read answers after Clear this sale (issue #656)', async () => {
    createProduct.mockResolvedValueOnce(sheet).mockResolvedValue(made);
    createStocktake.mockResolvedValue({ id: 's8', status: 'draft', lines: [] });
    commitStocktake.mockImplementation((id: string) => Promise.resolve({ id, status: 'committed' }));
    let read: (p: Product[]) => void = () => undefined;
    listProducts.mockImplementation(({ query }: { query?: string }) =>
      query === 'DUV-0001' ? new Promise<Product[]>((resolve) => { read = resolve; }) : Promise.resolve([]));
    open(<RecordSale />, owner);
    await fill('3');
    expect(await screen.findByRole('button', { name: 'Remove Test bed sheet' })).toBeTruthy();
    await fill('4');
    await waitFor(() => expect(listProducts).toHaveBeenCalledWith(expect.objectContaining({ query: 'DUV-0001' })));
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    await act(async () => read([{ ...made, qty: '10.000' }]));
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Remove Test bed sheet' })).toBeNull());
    expect(screen.queryByTestId('sale-count-drop-p9')).toBeNull();
    expect(createStocktake).toHaveBeenCalledTimes(1);
  });

  // Issue #664: the count of a new item (no tick asked) whose draft is still being made when the sale or the screen changes.
  // JUSTIFICATION-A3: the three tests issue #664 names, with the setup they share.
  /** Holds the duvet cover's count on its way; the answer it returns gives draft s1 when called. */
  const countOnItsWay = (first: Product = made) => {
    createProduct.mockResolvedValueOnce(first).mockResolvedValue(made);
    let answer: () => void = () => undefined;
    createStocktake.mockImplementation(({ lines }: { lines: { product_id: string }[] }) =>
      lines[0]!.product_id === 'p8' ? Promise.resolve({ id: 's8', status: 'draft', lines: [] }) : new Promise<Stocktake>((resolve) => { answer = () => resolve(draftOf('0.000')); }));
    commitStocktake.mockImplementation((id: string) => Promise.resolve({ id, status: 'committed' }));
    return async () => {
      await waitFor(() => expect(createStocktake).toHaveBeenCalledWith(expect.objectContaining({ lines: [expect.objectContaining({ product_id: 'p9' })] })));
      return () => answer();
    };
  };
  /** The draft s1 that came after the sale or screen changed is discarded, never committed, and nothing is said. */
  const discardedUnsent = async () => {
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('s1'));
    expect(commitStocktake).not.toHaveBeenCalledWith('s1');
    expect(screen.queryByRole('button', { name: 'Remove Test duvet cover' })).toBeNull();
    expect(screen.queryByText(/its count is not|may not be committed/)).toBeNull();
  };

  it('discards the count of a new item that answers after Clear this sale, and puts nothing on the sale (issue #664)', async () => {
    const onItsWay = countOnItsWay(sheet);
    open(<RecordSale />, owner);
    await fill('3');
    expect(await screen.findByRole('button', { name: 'Remove Test bed sheet' })).toBeTruthy();
    await fill('4');
    const answer = await onItsWay();
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    await act(async () => answer());
    await discardedUnsent();
  });

  it('discards the count of a new item that answers after the screen was left (issue #664)', async () => {
    const onItsWay = countOnItsWay();
    open(<RecordSale />, owner);
    await fill('4');
    const answer = await onItsWay();
    cleanup();
    await act(async () => answer());
    // The screen is gone, so only the requests carry weight.
    await waitFor(() => expect(discardStocktake).toHaveBeenCalledWith('s1'));
    expect(commitStocktake).not.toHaveBeenCalledWith('s1');
  });

  it('discards the count of a new item that answers after a branch switch, and puts nothing on the new branch sale (issue #664)', async () => {
    const onItsWay = countOnItsWay();
    // The staff area's branch switch through the picker and its holds, with the sale form made again for each branch.
    function Shell() {
      const { branch, choose } = useActiveBranch(owner);
      if (!branch) return null;
      return (
        <StaffContext.Provider value={{ me: owner, branch, chooseBranch: choose }}>
          <p data-testid="branch-in-use">{branch}</p>
          <button type="button" onClick={() => choose((owner.branches ?? []).find((b) => b.id !== branch)?.id ?? '')}>Switch branch</button>
          <SaleForm key={branch} branchId={branch} />
        </StaffContext.Provider>
      );
    }
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><Shell /></QueryClientProvider>);
    const started = (await screen.findByTestId('branch-in-use')).textContent;
    await fill('4');
    const answer = await onItsWay();
    fireEvent.click(screen.getByRole('button', { name: 'Switch branch' }));
    await waitFor(() => expect(screen.getByTestId('branch-in-use').textContent).not.toBe(started));
    await act(async () => answer());
    await discardedUnsent();
  });

  it('leaves the commit of a new item on its way alone when Sell is opened again, and the count stands (issue #664)', async () => {
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('0.000'));
    let landed: () => void = () => undefined;
    commitStocktake.mockImplementation((id: string) => new Promise((resolve) => { landed = () => resolve({ ...draftOf('0.000'), id, status: 'committed' }); }));
    open(<RecordSale />, owner);
    await fill('4');
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledWith('s1'));
    // The screen is left and Sell opened again online while the first form's commit is on its way.
    cleanup();
    open(<RecordSale />, owner);
    await act(async () => undefined);
    await act(async () => landed());
    await waitFor(() => expect(sessionStorage.getItem('sale-held-counts')).toBe('[]'));
    expect(commitStocktake.mock.calls).toEqual([['s1']]);
    expect(discardStocktake).not.toHaveBeenCalled();
  });

  it('sends the commit of a new item again on the same draft when its answer is lost, and takes one the server already took (issue #656)', async () => {
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('0.000'));
    commitStocktake.mockRejectedValueOnce(new Error('timed out')).mockRejectedValue(new RetailError('Stock count committed', 409, 'stocktake_committed'));
    open(<RecordSale />, owner);
    await fill('4');
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    expect(commitStocktake.mock.calls).toEqual([['s1'], ['s1']]);
    expect(createStocktake).toHaveBeenCalledTimes(1);
    expect(discardStocktake).not.toHaveBeenCalled();
    expect(screen.queryByText(/its count is not/)).toBeNull();
  });

  it('never discards the count of a new item whose commit is still lost after its second try, and says it may not be in (issue #656)', async () => {
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('0.000'));
    // Too many requests is no refusal: the count may still be taken, so it is tried again and never discarded.
    commitStocktake.mockRejectedValueOnce(new RetailError('Too many requests', 429, 'rate_limited')).mockRejectedValue(new RetailError('Offline', 503, 'unavailable'));
    open(<RecordSale />, owner);
    await fill('4');
    expect(await screen.findByText('The count of Test duvet cover may not be committed. Check it on Count stock before you sell it.')).toBeTruthy();
    expect(commitStocktake).toHaveBeenCalledTimes(2);
    expect(discardStocktake).not.toHaveBeenCalled();
    expect(JSON.parse(sessionStorage.getItem('sale-held-counts') ?? '[]')).toEqual(['s1']);
    expect(screen.queryByRole('button', { name: 'Remove Test duvet cover' })).toBeNull();
  });

  /** Sell inside the offline context, with a network the test turns off and on. */
  const openWithNetwork = () => {
    const network = testConnectivity(true);
    const offline = { scope: 'test', connectivity: network, catalogue: null, engine: { subscribe: () => () => undefined, snapshot: () => ({ items: [], syncing: false, needsSignIn: false }) } } as unknown as Offline;
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <OfflineProvider value={offline}><StaffContext.Provider value={{ me: owner, branch: owner.branches?.[0]?.id ?? '', chooseBranch }}><SaleForm branchId={owner.branches?.[0]?.id ?? ''} /></StaffContext.Provider></OfflineProvider>
      </QueryClientProvider>,
    );
    return network;
  };

  it('never discards the count of a new item whose commit is on its way when the connection comes back, and puts it on the sale (issue #664)', async () => {
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('0.000'));
    let first: (e: unknown) => void = () => undefined;
    commitStocktake.mockImplementationOnce(() => new Promise((_, reject) => { first = reject; })).mockResolvedValue({ ...draftOf('0.000'), status: 'committed' });
    const network = openWithNetwork();
    await fill('4');
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledTimes(1));
    // The connection drops and comes back before the first commit's answer, which is then lost: the commit is sent again.
    act(() => network.set(false));
    act(() => network.set(true));
    await act(async () => first(new RetailError('Offline', 503, 'unavailable')));
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    expect(commitStocktake.mock.calls).toEqual([['s1'], ['s1']]);
    expect(discardStocktake).not.toHaveBeenCalled();
    expect(sessionStorage.getItem('sale-held-counts')).toBe('[]');
  });

  it('says nothing of a lost commit of a new item once the sale is cleared, and keeps its draft for a later discard (issue #664)', async () => {
    createProduct.mockResolvedValueOnce(sheet).mockResolvedValue(made);
    createStocktake.mockImplementation(({ lines }: { lines: { product_id: string }[] }) =>
      Promise.resolve(lines[0]!.product_id === 'p8' ? { id: 's8', status: 'draft', lines: [] } : draftOf('0.000')));
    let first: (e: unknown) => void = () => undefined;
    commitStocktake.mockResolvedValueOnce({ id: 's8', status: 'committed' })
      .mockImplementationOnce(() => new Promise((_, reject) => { first = reject; })).mockRejectedValue(new RetailError('Offline', 503, 'unavailable'));
    open(<RecordSale />, owner);
    await fill('3');
    expect(await screen.findByRole('button', { name: 'Remove Test bed sheet' })).toBeTruthy();
    await fill('4');
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledWith('s1'));
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    await act(async () => first(new RetailError('Offline', 503, 'unavailable')));
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledTimes(3));
    await act(async () => undefined);
    expect(screen.queryByText(/may not be committed/)).toBeNull();
    expect(screen.queryByText(/its count is not/)).toBeNull();
    expect(JSON.parse(sessionStorage.getItem('sale-held-counts') ?? '[]')).toEqual(['s1']);
    expect(screen.queryByRole('button', { name: 'Remove Test duvet cover' })).toBeNull();
  });

  it('keeps an open Add it here with what was typed when a later search finds something (review of #616)', async () => {
    cats = [duvets];
    listProducts.mockImplementation(({ query }: { query?: string }) => Promise.resolve(query === 'Test duvet' ? [{ ...made, qty: '2.000' }] : []));
    open(<RecordSale />, owner);
    await search('Test duvet cover');
    fireEvent.click(await screen.findByRole('button', { name: 'Not in the list? Add it here' }));
    fireEvent.change(within(form()).getByLabelText(/^Selling price/), { target: { value: '45000' } });
    await search('Test duvet');
    expect(await screen.findByRole('option', { name: /Test duvet cover/ })).toBeTruthy();
    expect((within(form()).getByLabelText(/^Selling price/) as HTMLInputElement).value).toBe('45000');
    expect((within(form()).getByLabelText(/^Description/) as HTMLInputElement).value).toBe('Test duvet cover');
  });

  it('tells someone who cannot add items to ask the owner', async () => {
    catalogue = [];
    open(<RecordSale />, seller);
    await search('Test pillow');
    expect((await screen.findByTestId('sale-new-ask')).textContent).toBe('Not in the list? Ask the owner to add it.');
    expect(screen.queryByRole('button', { name: 'Not in the list? Add it here' })).toBeNull();
  });
});

describe('Sell it from Quick add (issue #632)', () => {
  const here = owner.branches?.[0]?.id ?? '';
  const other = owner.branches?.[1]?.id ?? '';

  it('puts the item on the sale at the branch Quick add added its stock at, and clears the address once it is on', async () => {
    catalogue = [{ ...made, qty: '600' }];
    getProduct.mockResolvedValue(made);
    const started = vi.fn();
    open(<RecordSale startItem="p9" startBranch={here} onStarted={started} />, owner);
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    await waitFor(() => expect(started).toHaveBeenCalledTimes(1));
    expect(listProducts.mock.calls.some((c) => (c[0] as { branchId?: string }).branchId === here)).toBe(true);
  });

  it('says where the item is when the branch in use is another, offers the switch, and never looks it up here', async () => {
    catalogue = [{ ...made, qty: '600' }];
    getProduct.mockResolvedValue(made);
    const started = vi.fn();
    open(<RecordSale startItem="p9" startBranch={other} onStarted={started} />, owner);
    const note = await screen.findByTestId('sale-start-elsewhere');
    const name = branchLabel(owner.branches?.[1]);
    expect(note.textContent).toContain(`The item you just added is at ${name}. Switch to ${name} to sell it.`);
    fireEvent.click(within(note).getByRole('button', { name: `Switch to ${name}` }));
    expect(chooseBranch).toHaveBeenCalledWith(other);
    expect(getProduct).not.toHaveBeenCalled();
    // Sell here without it forgets the item and clears the address, so the sale never waits.
    fireEvent.click(within(note).getByRole('button', { name: 'Sell here without it' }));
    expect(started).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('sale-start-elsewhere')).toBeNull();
    expect(getProduct).not.toHaveBeenCalled();
  });

  it('says to search for the item when the branch list does not return it, and never drops it in silence (issue #641)', async () => {
    catalogue = [];
    getProduct.mockResolvedValue(made);
    const started = vi.fn();
    open(<RecordSale startItem="p9" startBranch={here} onStarted={started} />, owner);
    expect((await screen.findByText('Test duvet cover did not come up here. Search for it to add it.'))).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Remove Test duvet cover' })).toBeNull();
    await waitFor(() => expect(started).toHaveBeenCalledTimes(1));
  });
});

describe('the first-run card order (issue #632)', () => {
  it('has Quick add as its one primary action, then Set up your shop, Add your first items and Import a list', async () => {
    open(<RecordSale key="sale" />, owner);
    const actions = await screen.findByTestId('first-items-actions');
    const links = within(actions).getAllByRole('link');
    expect(links.map((l) => l.textContent)).toEqual(['Quick add an item', 'Set up your shop', 'Add your first items', 'Import a list', 'How do I get my shop ready to sell?']);
    expect(links.filter((l) => (l.className ?? '').includes('btn-primary')).map((l) => l.textContent)).toEqual(['Quick add an item']);
  });
});
