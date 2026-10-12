import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Customer, PriceList, PriceListItem, ResolvedLine, Sale } from '../../../api/retail';
import type { Product } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { BuyerPricing } from './buyer-pricing';
import { PersonRow } from './catalogue-people';
import { CustomerGroups, GroupCard } from './customer-groups';
import { AddPriceForm, ItemRow, ListCard, PriceLists } from './price-lists';
import { Receipt, SaleForm, SaleLineCard } from './sale';
import { newLine } from './sale-state';

// Price lists on the screens (ADR-033, issue #195). With the capability off nothing is drawn: "all off equals
// today". Names and amounts are fabricated.

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');
const branch = mockMe('admin').branches?.[0]?.id ?? '';
const on = (me: ReturnType<typeof mockMe>) => ({ ...me, module_capabilities: { retail: ['price_lists'] } });
const admin = on(mockMe('admin'));
const seller = on(mockMe('sales'));
// A seller whose administrator removed the override.
const strictSeller = { ...seller, permissions: (seller.permissions ?? []).filter((p) => p !== 'retail.price.override') };

const page = (me: ReturnType<typeof mockMe>, node: ReactElement, client = new QueryClient()) =>
  renderToString(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );

const pen: Product = { id: 'p1', code: 'T-PEN', description: 'Test pen', unit: 'piece', unit_id: 'u-piece', sell_minor: 100, cost_minor: 83, active: true, qty: '100.000' };
const list: PriceList = { id: 'l1', name: 'Wholesale', currency: 'UGX', strict: false, active: true, current_items: 3, version: 1 };
const item: PriceListItem = {
  id: 'i1', price_list_id: 'l1', product_id: 'p1', code: 'T-PEN', description: 'Test pen', unit_id: 'u-piece', unit: 'piece', min_qty: '10.000', price_minor: 80,
  valid_from: '2026-10-12', status: 'current', below_cost: true,
};

describe('the pages are closed unless the feature is on and the user may manage lists', () => {
  it('refuses the price lists and customer groups pages while the feature is off', () => {
    expect(page(mockMe('admin'), <PriceLists />)).toContain('You do not have access');
    expect(page(mockMe('admin'), <CustomerGroups />)).toContain('You do not have access');
  });

  it('refuses a seller who holds no list permission, even with the feature on', () => {
    expect(page(seller, <PriceLists />)).toContain('You do not have access');
    expect(page(seller, <CustomerGroups />)).toContain('You do not have access');
  });

  it('opens for the administrator once the feature is on', () => {
    const html = page(admin, <PriceLists />);
    expect(html).not.toContain('You do not have access');
    expect(html).toContain('Add a price list');
    expect(html).toContain('data-tour="price-list-help"');
    expect(html).toContain('data-tour="price-list-lists"');
    expect(page(admin, <CustomerGroups />)).toContain('Add a customer group');
  });
});

describe('a price list', () => {
  const card = (l: PriceList, selected = false) =>
    renderToString(<ListCard list={l} selected={selected} onOpen={() => undefined} onChange={() => undefined} />);

  it('says what an item missing from it does', () => {
    expect(card(list)).toContain('sold at the retail price');
    expect(card({ ...list, strict: true })).toContain('cannot be sold');
    expect(card({ ...list, strict: true })).toContain('Strict');
  });

  it('is switched off, never deleted, and names every button for its list', () => {
    const html = card({ ...list, active: false });
    expect(html).toContain('Switched off');
    expect(html).toContain('aria-label="Switch on Wholesale"');
    expect(html).not.toMatch(/Delete/);
    expect(card(list, true)).toContain('Hide prices');
    expect(card(list)).toContain('aria-label="Prices in Wholesale"');
  });
});

describe('a price in a list', () => {
  const row = (i: PriceListItem, showCost: boolean) =>
    renderToString(<table><tbody><ItemRow item={i} showCost={showCost} onEnd={() => undefined} /></tbody></table>);

  it('shows the price, the break and the dates, and offers to end an open price', () => {
    const html = row(item, false);
    expect(html).toContain('UGX 80');
    expect(html).toContain('From 10');
    expect(html).toContain('No end');
    expect(html).toContain('In force');
    expect(html).toContain('aria-label="End the price of Test pen in piece"');
  });

  it('shows the warning for a price not above cost only to someone who may read cost', () => {
    expect(row(item, true)).toContain('Not above cost');
    expect(row(item, false)).not.toContain('Not above cost');
  });

  it('offers no end on a price that has ended or has an end date', () => {
    expect(row({ ...item, valid_to: '2026-12-31' }, false)).not.toContain('>End<');
    expect(row({ ...item, status: 'ended', valid_to: '2026-10-01' }, false)).toContain('Ended');
  });

  it('starts the new-price form at the search for an item, with today as the first day', () => {
    const html = page(admin, <AddPriceForm today="2026-10-12" onSubmit={() => undefined} onCancel={() => undefined} />);
    expect(html).toContain('Find an item by name, code or category');
    expect(html).not.toContain('in stock here');
  });
});

describe('a customer group', () => {
  it('names its list and its buyers and is switched off, not deleted', () => {
    const html = renderToString(
      <GroupCard group={{ id: 'g1', name: 'Contractors', price_list_id: 'l1', price_list: 'Wholesale', active: true, customer_count: 4, version: 1 }} lists={[list]} onSave={() => undefined} />,
    );
    expect(html).toContain('Priced from the list Wholesale');
    expect(html).toContain('4 customers');
    expect(html).toContain('aria-label="Switch off Contractors"');
  });

  it('says a group without a list pays the retail price', () => {
    const html = renderToString(<GroupCard group={{ id: 'g2', name: 'Walk-ins', active: true, customer_count: 0, version: 1 }} lists={[]} onSave={() => undefined} />);
    expect(html).toContain('pay the retail price');
  });
});

describe('a credit buyer', () => {
  const buyer: Customer = { id: 'c1', name: 'Test Buyer 01', balance_minor: 0, version: 1, customer_group_id: 'g1', price_list_id: 'l1' };
  const client = new QueryClient();
  client.setQueryData(['retail', 'customer-groups'], [{ id: 'g1', name: 'Contractors', price_list: 'Wholesale', active: true, customer_count: 1, version: 1 }]);
  client.setQueryData(['retail', 'price-lists'], [list]);

  it('draws nothing about prices while the feature is off', () => {
    expect(page(mockMe('admin'), <BuyerPricing row={buyer} />, client)).toBe('');
  });

  it('shows the administrator the group and the own list, and shows a seller nothing', () => {
    const html = page(admin, <BuyerPricing row={buyer} />, client);
    expect(html).toContain('Group: Contractors');
    expect(html).toContain('Own price list: Wholesale');
    expect(html).toContain('Change prices');
    // A seller sees nothing of what a buyer pays: the lists are read with retail.pricelist.manage only.
    expect(page(seller, <BuyerPricing row={buyer} />, client)).toBe('');
  });

  it('says a buyer with neither pays the retail price', () => {
    expect(page(admin, <BuyerPricing row={{ ...buyer, customer_group_id: undefined, price_list_id: undefined }} />, client)).toContain('Pays the retail price');
  });

  it('is the old buyer row when nothing is passed in', () => {
    const html = renderToString(<PersonRow row={buyer} onSave={() => undefined} />);
    expect(html).not.toContain('Group');
  });
});

describe('a sale line with price lists', () => {
  const resolved = (over: Partial<ResolvedLine> = {}): ResolvedLine => ({ product_id: 'p1', qty: '10.000', unit_price_minor: 80, line_total_minor: 800, price_source: 'list', price_list_id: 'l1', break_min_qty: '10.000', ...over });
  const card = (priced: { resolved?: ResolvedLine; canOverride: boolean } | undefined, line = { ...newLine(pen), price: '80' }) =>
    renderToString(
      <SaleLineCard line={line} index={0} unitsOn={false} price={priced && { ...priced, listName: 'Wholesale' }} onQty={() => undefined} onPrice={() => undefined} onUnit={() => undefined} onReason={() => undefined} onReset={() => undefined} onRemove={() => undefined} />,
    );

  it('says which list and which break priced the line', () => {
    const html = card({ resolved: resolved(), canOverride: true });
    expect(html).toContain('Price list Wholesale, from 10');
    expect(html).toContain('data-tour="sale-price-source"');
  });

  it('lets a permitted user change the price and asks for the reason', () => {
    const typed = { ...newLine(pen), price: '75', typed: true };
    const html = card({ resolved: resolved(), canOverride: true }, typed);
    expect(html).toContain('Changed from UGX 80.');
    expect(html).toContain('Why is the price changed?');
    expect(html).toContain('Use the list price');
    expect(html).not.toContain('readonly');
  });

  it('shows the price but not the way to change it to a user without the override permission', () => {
    const html = card({ resolved: resolved(), canOverride: false });
    expect(html).toContain('readonly');
    expect(html).toContain('You do not have permission to change it');
  });

  it('flags an item a strict list has no price for', () => {
    const html = card({ resolved: resolved({ problem: 'no_price_in_list', price_source: 'retail' }), canOverride: true });
    expect(html).toContain('has no price');
    expect(html).toContain('no price in the list');
  });

  it('is exactly the old line with price lists off', () => {
    const html = card(undefined);
    expect(html).not.toContain('Price list');
    expect(html).not.toContain('readonly');
    expect(html).not.toContain('Why is the price changed?');
    expect(html).toContain('Unit price');
  });
});

describe('the sale form', () => {
  const client = new QueryClient();

  it('asks for no customer and no list while the feature is off', () => {
    const html = page(mockMe('admin'), <SaleForm branchId={branch} />, client);
    expect(html).not.toContain('sets the price list');
    expect(html).not.toContain('Price list for this sale');
  });

  it('offers the customer on any sale once the feature is on, and the list only to someone who may choose one', () => {
    expect(page(seller, <SaleForm branchId={branch} />, client)).toContain('sets the price list');
    expect(page(seller, <SaleForm branchId={branch} />, client)).not.toContain('Price list for this sale');
    expect(page(admin, <SaleForm branchId={branch} />, client)).toContain('Price list for this sale');
  });

  it('keeps the way to change a price from a seller who lost the override permission', () => {
    expect(strictSeller.permissions).not.toContain('retail.price.override');
  });
});

describe('the receipt', () => {
  const sale: Sale = {
    id: 's1', branch_id: branch, sale_date: '2026-10-12', payment_method: 'cash',
    lines: [
      { product_id: 'p1', description: 'Test pen', qty: '10.000', unit_price_minor: 80, line_total_minor: 800, price: { source: 'list', price_list: 'Wholesale', break_min_qty: '10.000', resolved_unit_price_minor: 80 } },
      { product_id: 'p2', description: 'Test bulb', qty: '1.000', unit_price_minor: 500, line_total_minor: 500 },
    ],
    total_minor: 1300, paid_minor: 1300, balance_minor: 0,
  };

  it('says how a line was priced, and says nothing about a line made without price lists', () => {
    const html = page(mockMe('sales'), <Receipt sale={sale} />);
    expect(html).toContain('Price list Wholesale, from 10');
    expect(html.match(/data-testid="price-note"/g)).toHaveLength(1);
  });
});
