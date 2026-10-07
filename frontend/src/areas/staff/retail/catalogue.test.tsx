import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Category, Customer, PriceChange, Product } from '../../../api/retail';
import { createMockRetail, mockMe, setMockProfitAccess } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { catalogueLinksFor } from './catalogue';
import { ImportReport } from './catalogue-import';
import { NamedRow, usedText } from './catalogue-lists';
import { PersonRow } from './catalogue-people';
import { PriceForm, PriceHistory, ProductCard, ProductForm, belowCostHint, showWhen } from './catalogue-products';
import { CATALOGUE_LINKS, canUse, screensFor } from './permissions';
import { Gate } from './ui';

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');
const branch = mockMe('admin').branches?.[0]?.id ?? '';
const page = (role: 'sales' | 'admin', node: ReactElement, permissions?: string[]) => {
  const me = { ...mockMe(role), ...(permissions ? { permissions } : {}) };
  return renderToString(
    <QueryClientProvider client={new QueryClient()}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
};

const bulb: Product = { id: 'p1', code: 'P003', description: 'LED bulb 9W screw', category: 'Lighting', unit: 'piece', sell_minor: 6000, cost_minor: 3500, active: true, version: 3 };

describe('catalogue permission gating', () => {
  it('shows the Catalogue tile only to a session that holds a catalogue permission', () => {
    expect(screensFor(mockMe('admin')).map((s) => s.screen)).toContain('catalogue');
    expect(screensFor({ permissions: ['retail.sale.create', 'retail.stock.read'] }).map((s) => s.screen)).not.toContain('catalogue');
  });

  it('gives a sales user only Credit buyers, and an admin every screen', () => {
    const sales = mockMe('sales');
    const open = CATALOGUE_LINKS.filter((l) => canUse(sales, l.screen)).map((l) => l.screen);
    expect(open).toEqual(['buyers']);
    expect(CATALOGUE_LINKS.every((l) => canUse(mockMe('admin'), l.screen))).toBe(true);
    expect(catalogueLinksFor(sales).map((l) => l.label)).toEqual(['Credit buyers']);
  });

  it('refuses the item screens to a user without retail.catalogue.manage', () => {
    for (const screen of ['products', 'categories', 'units', 'suppliers'] as const) {
      const html = page('sales', <Gate screen={screen} title="Secret"><p>SECRET-CONTENT</p></Gate>);
      expect(html).toContain('You do not have access');
      expect(html).not.toContain('SECRET-CONTENT');
    }
  });

  it('lists a catalogue manager without the purchase permission no Suppliers link', () => {
    const permissions = ['retail.catalogue.manage', 'retail.stock.read'];
    const labels = catalogueLinksFor({ permissions }).map((l) => l.label);
    expect(labels).toEqual(['Items', 'Categories', 'Units']);
  });
});

describe('categories and units', () => {
  const row: Category = { id: 'c1', name: 'Lighting', active: true, product_count: 3 };

  it('says how many items use a row, and that a switched-off one cannot be chosen', () => {
    expect(usedText(0, 'unit')).toBe('No items use this unit yet.');
    expect(usedText(1, 'category')).toBe('Used by 1 item.');
    const html = renderToString(<ul><NamedRow row={{ ...row, active: false }} noun="category" onRename={() => undefined} onToggle={() => undefined} /></ul>);
    expect(html).toContain('Used by 3 items.');
    expect(html).toContain('Switched off');
    expect(html).toContain('New items cannot choose it.');
    expect(html).toContain('Switch on');
    expect(html).not.toMatch(/delete|remove/i);
  });
});

describe('suppliers and credit buyers', () => {
  it('shows what a buyer owes and offers no switch', () => {
    const buyer: Customer = { id: 'b1', name: 'Test Buyer 01', contact: '+256700000001', balance_minor: 12000 };
    const html = renderToString(<ul><PersonRow row={buyer} onSave={() => undefined} /></ul>);
    expect(html).toContain('Test Buyer 01');
    expect(html).toContain('UGX 12,000');
    expect(html).not.toContain('Switch');
  });
});

describe('items and prices', () => {
  it('shows cost on an item card only when asked to', () => {
    const withCost = renderToString(<ul><ProductCard product={bulb} showCost canPrice onEdit={() => undefined} onPrice={() => undefined} /></ul>);
    expect(withCost).toContain('cost UGX 3,500');
    expect(withCost).toContain('Change price');
    const without = renderToString(<ul><ProductCard product={{ ...bulb, cost_minor: undefined }} showCost={false} canPrice={false} onEdit={() => undefined} onPrice={() => undefined} /></ul>);
    expect(without).not.toMatch(/cost/i);
    expect(without).not.toContain('Change price');
  });

  it('asks for cost on a new item only with retail.profit.read', () => {
    expect(page('admin', <ProductForm onSaved={() => undefined} onCancel={() => undefined} />)).toContain('Cost price');
    const sales = ['retail.catalogue.manage', 'retail.stock.read'];
    const html = page('admin', <ProductForm onSaved={() => undefined} onCancel={() => undefined} />, sales);
    expect(html).not.toContain('Cost price');
    expect(html).toContain('The cost is set when you record a restock.');
  });

  it('edits the item fields but sends the user to the price form for prices', () => {
    const html = page('admin', <ProductForm product={bulb} onSaved={() => undefined} onCancel={() => undefined} />);
    expect(html).toContain('value="P003"');
    expect(html).toContain('Change price');
    expect(html).not.toContain('for="item-sell"');
  });

  it('hides cost in the price form and its history without retail.profit.read', () => {
    const history: PriceChange[] = [{ id: 'h1', at: '2026-10-06T22:30:00Z', source: 'manual', old_sell_minor: 5000, new_sell_minor: 6000, reason: 'Test supplier increase' }];
    const noCost = ['retail.catalogue.manage', 'retail.stock.read', 'retail.price.edit'];
    const html = page('admin', <PriceForm product={{ ...bulb, cost_minor: undefined }} onSaved={() => undefined} onCancel={() => undefined} />, noCost);
    expect(html).toContain('New selling price');
    expect(html).not.toContain('New cost price');
    expect(html).not.toMatch(/cost/i);
    const list = renderToString(<PriceHistory changes={history} />);
    expect(list).toContain('Selling price UGX 5,000 to UGX 6,000');
    expect(list).toContain('Changed by hand');
    expect(list).toContain('Reason: Test supplier increase');
    expect(list).not.toMatch(/cost/i);
    expect(page('admin', <PriceForm product={bulb} onSaved={() => undefined} onCancel={() => undefined} />)).toContain('New cost price');
  });

  it('hints, without blocking, when the price is not above a known cost', () => {
    expect(belowCostHint(3500, 3500)).toContain('above the cost');
    expect(belowCostHint(3501, 3500)).toBeNull();
    expect(belowCostHint(100, null)).toBeNull();
    expect(belowCostHint(100, 0)).toBeNull();
  });

  it('prints a moment in the business time zone, not UTC', () => {
    expect(showWhen('2026-10-06T22:30:00Z')).toBe('7 Oct 2026, 01:30');
    expect(showWhen(undefined)).toBe('');
  });
});

describe('the mock catalogue (the server rules the screens rely on)', () => {
  it('refuses a duplicate code ignoring case, and a stale version', async () => {
    setMockProfitAccess(true);
    const api = createMockRetail();
    const cats = await api.listCategories();
    const units = await api.listUnits();
    const body = { code: 'p003 ', description: 'Test copy', category_id: cats[0]?.id ?? '', unit_id: units[0]?.id ?? '', sell_minor: 10, cost_minor: 1 };
    await expect(api.createProduct(body)).rejects.toMatchObject({ status: 409, code: 'duplicate_product_code' });
    const made = await api.createProduct({ ...body, code: 'NEW-1' });
    expect(made.version).toBe(1);
    const edited = await api.updateProduct(made.id ?? '', 1, { description: 'Test renamed' });
    expect(edited.version).toBe(2);
    await expect(api.updateProduct(made.id ?? '', 1, { description: 'Again' })).rejects.toMatchObject({ status: 412 });
  });

  it('renames and switches off a category without losing its items, and counts them', async () => {
    const api = createMockRetail();
    const before = (await api.listCategories()).find((c) => c.name === 'Lighting');
    expect(before?.product_count).toBe(2);
    const renamed = await api.updateCategory(before?.id ?? '', { name: 'Lights' });
    expect(renamed.name).toBe('Lights');
    expect((await api.listCatalogue({ query: 'Lights' })).items).toHaveLength(2);
    const off = await api.updateCategory(before?.id ?? '', { active: false });
    expect(off.active).toBe(false);
    expect(off.product_count).toBe(2);
    await expect(api.updateCategory(before?.id ?? '', { name: 'cables' })).rejects.toMatchObject({ code: 'duplicate_category' });
  });

  it('refuses a price at or below cost, records history, and hides cost without the permission', async () => {
    setMockProfitAccess(true);
    const api = createMockRetail();
    const [first] = (await api.listCatalogue({ query: 'P003' })).items ?? [];
    await expect(api.editPrices(first?.id ?? '', first?.version ?? 1, { sell_minor: 3500, reason: 'Test' })).rejects.toMatchObject({ code: 'price_below_cost' });
    const changed = await api.editPrices(first?.id ?? '', first?.version ?? 1, { sell_minor: 6500, reason: 'Test increase' });
    expect(changed.sell_minor).toBe(6500);
    const history = await api.priceHistory(first?.id ?? '');
    expect(history.at(-1)).toMatchObject({ source: 'manual', old_sell_minor: 6000, new_sell_minor: 6500 });
    setMockProfitAccess(false);
    expect(JSON.stringify(await api.priceHistory(first?.id ?? ''))).not.toMatch(/cost/i);
    expect(JSON.stringify(await api.listCatalogue({}))).not.toMatch(/cost/i);
    setMockProfitAccess(true);
  });

  it('filters the item list by search, category and active', async () => {
    const api = createMockRetail();
    const cats = await api.listCategories();
    const solar = cats.find((c) => c.name === 'Solar');
    expect((await api.listCatalogue({ categoryId: solar?.id ?? '' })).items).toHaveLength(1);
    expect((await api.listCatalogue({ query: 'cable' })).items?.length).toBeGreaterThan(1);
    const [one] = (await api.listCatalogue({ query: 'P001' })).items ?? [];
    await api.updateProduct(one?.id ?? '', one?.version ?? 1, { active: false });
    expect((await api.listCatalogue({ active: false })).items).toHaveLength(1);
    expect((await api.listCatalogue({ active: true })).items).toHaveLength(11);
  });

  it('edits a buyer and a supplier and lists the buyer with what is owed', async () => {
    const api = createMockRetail();
    const [buyer] = await api.listCustomers();
    expect(buyer?.balance_minor).toBeGreaterThanOrEqual(0);
    const edited = await api.updateCustomer(buyer?.id ?? '', { contact: '+256700000009' });
    expect(edited.contact).toBe('+256700000009');
    const [supplier] = await api.listSuppliers();
    expect((await api.updateSupplier(supplier?.id ?? '', { active: false })).active).toBe(false);
  });
});

describe('importing items from a file', () => {
  const csv = 'code,description,category,unit,sell price\nIMP-1,Test cable,Cables,roll,12000\nP003,Test existing,Lighting,piece,500\nIMP-2,Test bulb,Lighting,piece,abc\n';

  it('is for an administrator only', () => {
    const catalogueManager = { permissions: ['retail.catalogue.manage', 'retail.stock.read'] };
    expect(canUse(catalogueManager, 'importer')).toBe(false);
    expect(canUse({ permissions: [...catalogueManager.permissions, 'core.settings.manage'] }, 'importer')).toBe(true);
    expect(canUse(mockMe('sales'), 'importer')).toBe(false);
    const html = page('admin', <Gate screen="importer" title="Import items"><p>FORM</p></Gate>, catalogueManager.permissions);
    expect(html).toContain('You do not have access');
    expect(html).not.toContain('FORM');
  });

  it('reports rows to add, skip and fix, in words', async () => {
    setMockProfitAccess(true);
    const api = createMockRetail();
    const dry = await api.importProducts(csv, true);
    expect([dry.added, dry.skipped, dry.errors]).toEqual([1, 1, 1]);
    const html = renderToString(<ImportReport result={dry} />);
    expect(html).toContain('What would happen');
    expect(html).toContain('<strong>1</strong> to add');
    expect(html).toContain('An item with this code exists already.');
    expect(html).toContain('The sell price must be a whole number');
    expect((await api.listCatalogue({ query: 'IMP-1' })).items).toHaveLength(0);
  });

  it('adds nothing while a row has a problem, and adding twice adds once', async () => {
    setMockProfitAccess(true);
    const api = createMockRetail();
    await expect(api.importProducts(csv, false)).rejects.toMatchObject({ code: 'import_has_errors' });
    const clean = csv.replace('abc', '900');
    const first = await api.importProducts(clean, false);
    expect([first.added, first.skipped]).toEqual([2, 1]);
    const again = await api.importProducts(clean, false);
    expect([again.added, again.skipped]).toEqual([0, 3]);
    expect((await api.listCatalogue({ query: 'IMP-' })).items).toHaveLength(2);
    expect(renderToString(<ImportReport result={first} />)).toContain('What was done');
  });

  it('refuses the cost column to a session that may not see costs', async () => {
    setMockProfitAccess(false);
    const api = createMockRetail();
    await expect(api.importProducts(csv.replace('sell price', 'sell price,cost price'), true)).rejects.toMatchObject({ code: 'cost_not_allowed' });
    setMockProfitAccess(true);
  });
});
