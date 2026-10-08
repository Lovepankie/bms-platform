import { QueryClient } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { RetailError, businessToday, daysBefore, type Expense, type ExpenseCategory } from '../../../api/retail';
import { createMockRetail, mockMe, setMockProfitAccess } from '../../../api/retail-mock';
import { branch, html, page } from './cashbook-test-utils';
import { CategoryCard, ExpenseLists } from './expense-lists';
import { ExpenseForm, ExpenseGroups, ExpenseRecords } from './expenses';
import { activeCategories, itemsOf, needsExplanation, planExpense } from './expenses-state';

const today = businessToday();
const categories: ExpenseCategory[] = [
  { id: 'c1', name: 'Transport', active: true, version: 1, items: [
    { id: 'i1', name: 'Fuel', requires_explanation: false, active: true, version: 1 },
    { id: 'i2', name: 'Old route', requires_explanation: false, active: false, version: 1 },
  ] },
  { id: 'c2', name: 'Utilities', active: true, version: 3, items: [
    { id: 'i3', name: 'Electricity', requires_explanation: false, active: true, version: 1 },
    { id: 'i4', name: 'Others', requires_explanation: true, active: true, version: 2 },
  ] },
  { id: 'c3', name: 'Closed category', active: false, version: 1, items: [] },
];

describe('expense rules', () => {
  it('offers the items of the chosen category only, and only active ones', () => {
    expect(itemsOf(categories, 'c1').map((i) => i.name)).toEqual(['Fuel']);
    expect(itemsOf(categories, 'c2').map((i) => i.name)).toEqual(['Electricity', 'Others']);
    expect(itemsOf(categories, '')).toEqual([]);
    expect(activeCategories(categories).map((c) => c.name)).toEqual(['Transport', 'Utilities']);
  });

  it('asks for an explanation only for an item flagged for one', () => {
    expect(needsExplanation(categories, 'c2', 'i4')).toBe(true);
    expect(needsExplanation(categories, 'c2', 'i3')).toBe(false);
    // An item of another category is not the chosen one.
    expect(needsExplanation(categories, 'c1', 'i4')).toBe(false);
  });

  const base = { branchId: branch, date: today, categoryId: 'c2', itemId: 'i4', partyId: '', amount: '5,000', explanation: '' };

  it('blocks an expense that needs an explanation until it has 3 characters', () => {
    expect(planExpense(base, categories).problem).toMatch(/at least 3/);
    expect(planExpense({ ...base, explanation: 'ab' }, categories).problem).toMatch(/at least 3/);
    const ok = planExpense({ ...base, explanation: 'Generator oil' }, categories);
    expect(ok.problem).toBeNull();
    expect(ok.request).toMatchObject({ category_id: 'c2', item_id: 'i4', amount_minor: 5000, explanation: 'Generator oil' });
  });

  it('sends no explanation for an item that does not need one, even if one was typed earlier', () => {
    const plan = planExpense({ ...base, itemId: 'i3', explanation: 'left over text' }, categories);
    expect(plan.problem).toBeNull();
    expect('explanation' in plan.request).toBe(false);
  });

  it('refuses a missing category, an item of another category, and a bad amount', () => {
    expect(planExpense({ ...base, categoryId: '' }, categories).problem).toMatch(/category/i);
    expect(planExpense({ ...base, categoryId: 'c1' }, categories).problem).toMatch(/item/i);
    expect(planExpense({ ...base, itemId: 'i3', amount: 'abc' }, categories).problem).toMatch(/whole shillings/);
  });

  it('sends the beneficiary only when one is chosen', () => {
    const plan = planExpense({ ...base, itemId: 'i3', partyId: 'p1' }, categories);
    expect(plan.request.party_id).toBe('p1');
    expect('party_id' in planExpense({ ...base, itemId: 'i3' }, categories).request).toBe(false);
  });
});

describe('expense form', () => {
  const clientWith = () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'expense-categories', true], categories);
    client.setQueryData(['retail', 'cash-parties'], [{ id: 'p1', name: 'Test Vendor 01', kind: 'supplier', active: true }]);
    return client;
  };
  const draftName = `retail-draft:expense:${mockMe('admin').user_id}:${branch}`;
  const store = new Map<string, string>();
  const original = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
  beforeEach(() => {
    store.clear();
    Object.defineProperty(globalThis, 'sessionStorage', {
      configurable: true,
      value: { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v), removeItem: (k: string) => void store.delete(k) },
    });
  });
  afterEach(() => {
    if (original) Object.defineProperty(globalThis, 'sessionStorage', original);
    else Reflect.deleteProperty(globalThis, 'sessionStorage');
  });
  const draftOf = (categoryId: string, itemId: string) => store.set(draftName, JSON.stringify({ key: 'k', draft: { date: today, categoryId, itemId, partyId: '', amount: '', explanation: '' } }));

  it('starts with the item list waiting for a category, no explanation field, and every input labelled', () => {
    const out = page('admin', <ExpenseForm branchId={branch} />, clientWith());
    expect(out).toContain('Choose a category first');
    expect(out).not.toContain('Fuel');
    expect(out).not.toContain('expense-explanation');
    expect(out).not.toContain('Closed category');
    for (const id of ['expense-date', 'expense-category', 'expense-item', 'expense-party', 'expense-amount']) expect(out).toContain(`for="${id}"`);
    expect(out).toContain('Add a new beneficiary');
    expect(out).toContain('Test Vendor 01');
  });

  it('shows only the items of the chosen category', () => {
    draftOf('c1', '');
    const out = page('admin', <ExpenseForm branchId={branch} />, clientWith());
    expect(out).toContain('>Fuel</option>');
    expect(out).not.toContain('Electricity');
    expect(out).not.toContain('Old route');
  });

  it('shows the explanation field for an item that needs one, and not for one that does not', () => {
    draftOf('c2', 'i4');
    expect(page('admin', <ExpenseForm branchId={branch} />, clientWith())).toContain('for="expense-explanation"');
    draftOf('c2', 'i3');
    expect(page('admin', <ExpenseForm branchId={branch} />, clientWith())).not.toContain('expense-explanation');
  });
});

describe('expense records, report and lists', () => {
  const rows: Expense[] = [
    { id: 'x1', branch_id: branch, business_date: today, amount_minor: 40000, category_name: 'Transport', item_name: 'Fuel', party_name: 'Test Vendor 01', by_name: 'Test User 01', voided: false },
    { id: 'x2', branch_id: branch, business_date: today, amount_minor: 1000, category_name: 'Utilities', item_name: 'Others', explanation: 'Generator oil', voided: true, void_reason: 'Wrong day' },
  ];

  it('lists expenses grouped by shop and month with Void only for a holder of the void permission', () => {
    const client = new QueryClient();
    client.setQueryData(['retail', 'expenses', [branch], daysBefore(today, 30), today], rows);
    const admin = page('admin', <ExpenseRecords />, client);
    expect(admin).toContain('Test Branch A');
    expect(admin).toContain('Transport / Fuel, for Test Vendor 01');
    expect(admin).toContain('Generator oil');
    expect(admin).toContain('>Void<');
    expect(admin).toContain('Wrong day');
    expect(page('cashier', <ExpenseRecords />, client)).not.toContain('>Void<');
  });

  it('shows totals and counts by group', () => {
    const out = page('admin', <ExpenseGroups groups={[{ key: 'c1', label: 'Transport', count: 2, total_minor: 70000 }, { key: 'c2', label: 'Utilities', count: 1, total_minor: 5000 }]} total={75000} count={3} by="category" />);
    expect(out).toContain('Transport');
    expect(out).toContain('UGX 70,000');
    expect(out).toContain('2 expenses');
    expect(out).toContain('Total UGX 75,000');
    expect(page('admin', <ExpenseGroups groups={[]} total={0} count={0} by="item" />)).toContain('No expenses in this period');
  });

  it('shows switched off categories and items, the explanation flag and a switch button each', () => {
    const out = html(<ExpenseLists categories={categories} busy={false} error={null} change={() => undefined} />);
    expect(out).toContain('Closed category');
    expect(out).toContain('Switched off');
    expect(out).toContain('Needs an explanation');
    expect(out).toContain('Switch Transport off');
    expect(out).toContain('Switch Closed category on');
    expect(out).toContain('for="');
    expect(html(<CategoryCard category={categories[1]!} busy change={() => undefined} />)).toContain('disabled');
  });
});

describe('expenses against the mock', () => {
  beforeEach(() => setMockProfitAccess(true));

  it('refuses a missing explanation, an item of another category and an inactive category', async () => {
    const mock = createMockRetail();
    const cats = await mock.listExpenseCategories({ activeOnly: true });
    const utilities = cats.find((c) => c.name === 'Utilities')!;
    const transport = cats.find((c) => c.name === 'Transport')!;
    const others = utilities.items!.find((i) => i.requires_explanation)!;
    const code = async (body: Parameters<typeof mock.createExpense>[0], key: string) => ((await mock.createExpense(body, key).catch((e: unknown) => e)) as RetailError).code;
    expect(await code({ branch_id: branch, category_id: utilities.id!, item_id: others.id!, amount_minor: 1000 }, 'e1')).toBe('explanation_required');
    expect(await code({ branch_id: branch, category_id: transport.id!, item_id: others.id!, amount_minor: 1000 }, 'e2')).toBe('item_not_in_category');
    const saved = await mock.createExpense({ branch_id: branch, category_id: utilities.id!, item_id: others.id!, amount_minor: 1000, explanation: 'Generator oil' }, 'e3');
    expect(saved.category_name).toBe('Utilities');
    expect((await mock.createExpense({ branch_id: branch, category_id: utilities.id!, item_id: others.id!, amount_minor: 1000, explanation: 'Generator oil' }, 'e3')).id).toBe(saved.id);
  });

  it('adds a beneficiary on the fly and refuses a duplicate in plain words', async () => {
    const mock = createMockRetail();
    const created = await mock.createCashParty({ name: 'Test Beneficiary 01', kind: 'other' });
    expect((await mock.listCashParties()).some((p) => p.id === created.id)).toBe(true);
    const again = (await mock.createCashParty({ name: 'test beneficiary 01', kind: 'other' }).catch((e: unknown) => e)) as RetailError;
    expect(again.code).toBe('duplicate_party');
    expect(again.message).toMatch(/already someone/);
  });

  it('changes a list only with the version it showed, and refuses a stale one', async () => {
    const mock = createMockRetail();
    const [first] = await mock.listExpenseCategories();
    const renamed = await mock.updateExpenseCategory(first!.id!, { name: 'Transport and fuel' }, first!.version!);
    expect(renamed.name).toBe('Transport and fuel');
    expect(renamed.version).toBe(first!.version! + 1);
    const stale = (await mock.updateExpenseCategory(first!.id!, { active: false }, first!.version!).catch((e: unknown) => e)) as RetailError;
    expect(stale.code).toBe('version_conflict');
    const item = first!.items![0]!;
    const flagged = await mock.updateExpenseItem(first!.id!, item.id!, { requires_explanation: true }, item.version!);
    expect(flagged.requires_explanation).toBe(true);
    expect((await mock.listExpenseCategories({ activeOnly: true })).find((c) => c.id === first!.id)?.items?.find((i) => i.id === item.id)?.requires_explanation).toBe(true);
  });

  it('totals the report by category and leaves voided expenses out', async () => {
    const mock = createMockRetail();
    const cats = await mock.listExpenseCategories({ activeOnly: true });
    const transport = cats[0]!;
    const fuel = transport.items![0]!;
    const one = await mock.createExpense({ branch_id: branch, business_date: today, category_id: transport.id!, item_id: fuel.id!, amount_minor: 30000 }, 'r1');
    await mock.createExpense({ branch_id: branch, business_date: today, category_id: transport.id!, item_id: fuel.id!, amount_minor: 20000 }, 'r2');
    const before = await mock.expensesReport({ groupBy: 'category', from: today, to: today });
    expect(before.total_minor).toBe(50000);
    expect(before.count).toBe(2);
    await mock.voidExpense(one.id!, 'Wrong shop', 'rv1');
    const after = await mock.expensesReport({ groupBy: 'item', from: today, to: today });
    expect(after.total_minor).toBe(20000);
    expect(after.items?.[0]?.label).toBe('Fuel');
  });
});
