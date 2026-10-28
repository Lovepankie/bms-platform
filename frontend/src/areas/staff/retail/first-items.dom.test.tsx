// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { RetailError, type Product, type Stocktake } from '../../../api/retail';
import { bigDrop } from './maths';
import { mockMe } from '../../../api/retail-mock';
import { branchLabel } from '../../../auth/branch';
import { StaffContext } from '../context';
import { PICKER_LABEL } from '../../../test-utils/item-picker';
import { chooseCategory } from '../../../test-utils/category-picker';

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
    },
  };
});
vi.mock('../../../api/retail-analytics', async (original) => {
  const real = await original<typeof import('../../../api/retail-analytics')>();
  return { ...real, retailAnalytics: { ...real.retailAnalytics, home: () => Promise.resolve({}) } };
});

const { RecordSale } = await import('./sale');
const { StaffHome } = await import('../home');

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
  similarProducts.mockReset();
  similarProducts.mockResolvedValue({ exact: [], similar: [] });
  getProduct.mockReset();
  listProducts.mockReset();
  chooseBranch.mockReset();
  listProducts.mockImplementation(() => Promise.resolve(catalogue));
});
listProducts.mockImplementation(() => Promise.resolve(catalogue));

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
    fireEvent.click(within(form()).getByRole('button', { name: 'Add a category' }));
    fireEvent.change(screen.getByLabelText(/^Name of the new category/), { target: { value: 'Duvets' } });
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

  it('never sends a big drop to be refused, nor leaves its draft open: the line asks its tick on Sell (issues #636, #656)', async () => {
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('10.000'));
    commitStocktake.mockImplementation(serverCommit(draftOf('10.000')));
    open(<RecordSale />, owner);
    await fill('4');
    const ask = await screen.findByTestId('sale-count-drop');
    expect(ask.textContent).toContain('The records show 10 of Test duvet cover here, and you counted 4. This removes 6. Is that right?');
    expect(screen.queryByText(/its count is not/)).toBeNull();
    expect(commitStocktake).not.toHaveBeenCalled();
    // Commit the count waits for the tick.
    fireEvent.click(within(ask).getByRole('button', { name: 'Commit the count' }));
    expect(commitStocktake).not.toHaveBeenCalled();
    fireEvent.click(within(ask).getByRole('checkbox', { name: 'Yes, Test duvet cover is right' }));
    fireEvent.click(within(ask).getByRole('button', { name: 'Commit the count' }));
    await waitFor(() => expect(commitStocktake).toHaveBeenCalledWith('s1', ['p9']));
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    expect(screen.queryByTestId('sale-count-drop')).toBeNull();
  });

  it('keeps the tick on Sell when the commit fails, so the count can be sent again (issue #656)', async () => {
    createProduct.mockResolvedValue(made);
    createStocktake.mockResolvedValue(draftOf('10.000'));
    commitStocktake.mockRejectedValueOnce(new RetailError('Offline', 503, 'unavailable')).mockImplementation(serverCommit(draftOf('10.000')));
    open(<RecordSale />, owner);
    await fill('4');
    const ask = await screen.findByTestId('sale-count-drop');
    fireEvent.click(within(ask).getByRole('checkbox', { name: 'Yes, Test duvet cover is right' }));
    fireEvent.click(within(ask).getByRole('button', { name: 'Commit the count' }));
    expect(await screen.findByText('The count of Test duvet cover is not committed. Try again, or use Count stock.')).toBeTruthy();
    fireEvent.click(within(screen.getByTestId('sale-count-drop')).getByRole('button', { name: 'Commit the count' }));
    expect(await screen.findByRole('button', { name: 'Remove Test duvet cover' })).toBeTruthy();
    expect(commitStocktake).toHaveBeenCalledTimes(2);
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
