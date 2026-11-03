// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Every shop's stock in one view (issue #807, ADR-119): a business owner asked that everyone on My stock sees how many of each item
// every shop has, without the Switch button that moved them there. Each card lists every shop, this one first as "In stock here",
// a shop with none at 0 and one below zero in words too. With the owner's switch off the rows carry no shops and the old view
// stays, minus the button. Every name, figure and id below is invented.

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const listStockAllBranches = vi.fn();
const changeCapabilities = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listStockAllBranches: (...a: unknown[]) => listStockAllBranches(...a),
      changeCapabilities: (...a: unknown[]) => changeCapabilities(...a),
    },
  };
});

const { StockTable } = await import('./stock');
const { useStockElsewhere } = await import('./stock-elsewhere');
const { OtherShopsSetting } = await import('./settings');

const owner = mockMe('admin');
const shop = owner.branches?.[0]?.id ?? '';
const other = owner.branches?.[1]?.id ?? '';
// A seller whose role covers this shop only.
const sales = mockMe('sales');
const seller = {
  ...sales,
  branches: sales.branches?.slice(0, 1),
  permission_scopes: Object.fromEntries(Object.keys(sales.permission_scopes ?? {}).map((p) => [p, { all_branches: false, branch_ids: [shop] }])),
} as Me;

let phone = true;
beforeEach(() => {
  phone = true;
  vi.stubGlobal('matchMedia', (media: string) => ({ matches: phone && media === '(max-width: 719px)', media, addEventListener: () => undefined, removeEventListener: () => undefined }));
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  listStockAllBranches.mockReset();
  changeCapabilities.mockReset();
});

function show(node: ReactNode, me: Me = seller, chooseBranch = vi.fn(() => true)) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: shop, chooseBranch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
  return chooseBranch;
}

const shops = (here: string, there: string) => [
  { branch_id: shop, name: 'Test Branch A', qty: here, negative: here.startsWith('-'), here: true },
  { branch_id: other, name: 'Another Shop', qty: there, negative: there.startsWith('-'), here: false },
];
const row = (id: string, here: string, there: string, withShops = true): StockRow => ({
  product_id: id, code: id, description: `Test Lamp ${id}`, category: 'Lamps', unit: 'piece', qty: here, negative: here.startsWith('-'), sell_minor: 1500,
  ...(withShops ? { shops: shops(here, there) } : {}),
});

describe('My stock lists every shop (issue #807)', () => {
  it('shows a seller at one shop the count in every shop, here first, with no Switch button', () => {
    const choose = show(<StockTable rows={[row('L-1', '0.000', '1.000')]} showCost={false} />);
    const list = screen.getByTestId('shops-L-1');
    const lines = within(list).getAllByRole('listitem').map((li) => li.textContent);
    expect(lines).toEqual(['In stock here: 0 pieces', 'Another Shop: 1 piece']);
    expect(screen.queryByRole('button', { name: /Switch to/ })).toBeNull();
    expect(screen.queryByTestId('elsewhere-L-1')).toBeNull();
    expect(choose).not.toHaveBeenCalled();
  });

  it('shows a shop with none as 0 and one below zero with its minus sign and in words', () => {
    show(<StockTable rows={[row('L-2', '0.000', '0.000'), row('L-3', '4.000', '-2.000')]} showCost={false} />);
    expect(within(screen.getByTestId('shops-L-2')).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['In stock here: 0 pieces', 'Another Shop: 0 pieces']);
    const below = within(screen.getByTestId('shops-L-3')).getAllByRole('listitem')[1];
    expect(below?.textContent).toBe('Another Shop: -2 pieces Below zero');
  });

  it('shows the selling price as before and no buying price for a role without it', () => {
    show(<StockTable rows={[row('L-4', '3.000', '1.000')]} showCost={false} />);
    const card = screen.getByTestId('shops-L-4').closest('li.stock-card') as HTMLElement;
    expect(card.textContent).toContain('Selling price');
    expect(card.textContent).not.toMatch(/buying price/i);
  });

  it('keeps the row\'s own quantity and Negative flag when the list holds no entry for this shop (review of #809)', () => {
    const odd: StockRow = { ...row('L-8', '-3.000', '2.000'), shops: shops('-3.000', '2.000').filter((s) => !s.here) };
    show(<StockTable rows={[odd]} showCost={false} />);
    expect(screen.queryByTestId('shops-L-8')).toBeNull();
    expect(screen.getByText('In stock:', { exact: false }).textContent).toContain('-3 pieces');
    expect(screen.getByText('Negative')).toBeTruthy();
  });

  it('lists every shop on a wide screen too', () => {
    phone = false;
    show(<StockTable rows={[row('L-5', '2.000', '5.000')]} showCost={false} />);
    expect(within(screen.getByTestId('shops-L-5')).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['In stock here: 2 pieces', 'Another Shop: 5 pieces']);
  });

  it('asks the server nothing more when the rows carry every shop (one request for the page)', () => {
    function Probe({ rows }: { rows: StockRow[] }) {
      useStockElsewhere(shop, rows, { query: '', categoryId: '', includeInactive: false });
      return null;
    }
    show(<Probe rows={[row('L-6', '0.000', '3.000')]} />, owner);
    expect(listStockAllBranches).not.toHaveBeenCalled();
  });
});

describe('with the owner switch off (issue #807)', () => {
  it('keeps the old view of this shop alone, minus the Switch button', () => {
    const elsewhere = new Map([['L-7', { branchId: other, name: 'Another Shop', qty: '3.000' }]]);
    show(<StockTable rows={[row('L-7', '0.000', '3.000', false)]} showCost={false} elsewhere={elsewhere} />, owner);
    expect(screen.queryByTestId('shops-L-7')).toBeNull();
    expect(screen.getByText('In stock:', { exact: false }).textContent).toContain('0 pieces');
    expect(within(screen.getByTestId('elsewhere-L-7')).getByText('0 here. 3 at Another Shop')).toBeTruthy();
    expect(screen.queryByRole('button', { name: /Switch to/ })).toBeNull();
  });

  it('is the owner switch on Retail settings, on by default, saved with the settings version', async () => {
    changeCapabilities.mockResolvedValue({ capabilities: { other_shops_stock: false }, version: 8 });
    const onSaved = vi.fn();
    show(<OtherShopsSetting read={{ capabilities: { profile: 'shops' } as never, version: 7 }} onSaved={onSaved} />, owner);
    const box = screen.getByRole('checkbox', { name: /Show other shops' stock to everyone/ }) as HTMLInputElement;
    expect(box.checked).toBe(true);
    const save = screen.getByRole('button', { name: 'Save' }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.click(box);
    fireEvent.click(save);
    await waitFor(() => expect(changeCapabilities).toHaveBeenCalledWith({ other_shops_stock: false }, 7));
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
  });
});
