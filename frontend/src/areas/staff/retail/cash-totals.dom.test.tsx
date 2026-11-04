// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, within } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Expense, Savings } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { RecordsByShop } from './cash-ui';
import { cashGroups } from './cash-state';
import { EXPENSE_ORDER } from './list-chain-state';
import { ALL_BRANCHES } from '../../../auth/branch';

// Issue #816, as the owner's old app did: the cash book record lists show a total at every group level (shop, year, month, and
// for expenses the category and the day), summed from the very rows the list shows, voided records counted apart. A fabricated
// set across two shops and two months; names and amounts are fabricated.

const admin = mockMe('admin');
const [a, b] = (admin.branches ?? []).map((x) => x.id ?? '');

const fuel = { category_id: 'c-fuel', category_name: 'Test Fuel' };
const rent = { category_id: 'c-rent', category_name: 'Test Rent' };
const rows: Expense[] = [
  { id: 'e1', branch_id: a, business_date: '2026-10-05', amount_minor: 3000, ...fuel, item_name: 'Test item 01' },
  { id: 'e2', branch_id: a, business_date: '2026-10-05', amount_minor: 2000, ...fuel, item_name: 'Test item 02' },
  { id: 'e3', branch_id: a, business_date: '2026-10-07', amount_minor: 10000, ...rent, item_name: 'Test item 03' },
  { id: 'e4', branch_id: a, business_date: '2026-10-06', amount_minor: 500, ...fuel, item_name: 'Test item 04', voided: true, void_reason: 'Entered twice' },
  { id: 'e5', branch_id: a, business_date: '2026-09-20', amount_minor: 1000, ...fuel, item_name: 'Test item 05' },
  { id: 'e6', branch_id: b, business_date: '2026-10-02', amount_minor: 4000, ...rent, item_name: 'Test item 06' },
  { id: 'e7', branch_id: b, business_date: '2026-09-03', amount_minor: 6000, ...fuel, item_name: 'Test item 07', historical: true },
];

const FIGURES = { amount: (r: Expense) => r.amount_minor, category: (r: Expense) => ({ id: r.category_id, name: r.category_name }) };

function show(list: Expense[] = rows) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <StaffContext.Provider value={{ me: admin, branch: ALL_BRANCHES }}>
        <RecordsByShop list="expenses" levels={EXPENSE_ORDER} figures={FIGURES} totalWord="Spent" rows={list} empty="No expenses." what="expense" renderRow={(r) => <span>{r.item_name}</span>} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

/** The header of a group, found by the start of its words, inside `within` when given. */
const head = (title: RegExp, scope: HTMLElement = document.body) => {
  // The trail above the groups names the open ones too: a group's own header is the one with its figures.
  const found = within(scope).getAllByRole('button', { name: title }).find((el) => el.classList.contains('rt-group-head'));
  if (!found) throw new Error(`no group header ${title}`);
  return found;
};
const bodyOf = (button: HTMLElement) => button.closest('li.rt-group') as HTMLElement;

afterEach(() => {
  cleanup();
  localStorage.clear();
});

describe('cash book totals at every level (issue #816)', () => {
  it('totals each shop, year, month, category and day of a fabricated set across two shops and two months', () => {
    show();
    const shopA = head(/^Test Branch A/);
    expect(shopA.textContent).toContain('UGX 16,000');
    expect(shopA.textContent).toContain('Spent in 4 expenses, 1 voided (not counted)');
    const shopB = head(/^Test Branch B/);
    expect(shopB.textContent).toContain('UGX 10,000');
    expect(shopB.textContent).toContain('2 expenses, 1 imported from the old app');

    // The newest records are open on arrival: the first shop, its newest year, month, category and day.
    const inA = bodyOf(shopA);
    expect(head(/^2026/, inA).textContent).toContain('UGX 16,000');
    const october = head(/^October 2026/, inA);
    expect(october.getAttribute('aria-expanded')).toBe('true');
    expect(october.textContent).toContain('UGX 15,000');
    expect(october.textContent).toContain('3 expenses, 1 voided (not counted)');
    expect(head(/^September 2026/, inA).textContent).toContain('UGX 1,000');
    const inOctober = bodyOf(october);
    const fuelGroup = head(/^Test Fuel/, inOctober);
    expect(fuelGroup.textContent).toContain('UGX 5,000');
    expect(fuelGroup.textContent).toContain('2 expenses, 1 voided (not counted)');
    expect(head(/^Test Rent/, inOctober).textContent).toContain('UGX 10,000');
    const inFuel = bodyOf(fuelGroup);
    const fifth = head(/^Monday 5 October 2026/, inFuel);
    expect(fifth.textContent).toContain('UGX 5,000');
    expect(fifth.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(fifth);
    expect(bodyOf(fifth).textContent).toContain('Test item 01');
    expect(bodyOf(fifth).textContent).toContain('Test item 02');
    // A day with only a voided record counts nothing and shows no total.
    const sixth = head(/^Tuesday 6 October 2026/, inFuel);
    expect(sixth.textContent).toContain('Nothing counted, 1 voided (not counted)');
    expect(sixth.querySelector('.rt-group-total')).toBeNull();

    // The other shop opens on a tap, with its own months.
    fireEvent.click(shopB);
    const inB = bodyOf(head(/^Test Branch B/));
    fireEvent.click(head(/^2026/, inB));
    expect(head(/^October 2026/, inB).textContent).toContain('UGX 4,000');
    expect(head(/^September 2026/, inB).textContent).toContain('UGX 6,000');
  });

  it('opens the newest groups again when the period brings other records, not the paths of rows no longer shown', () => {
    const { rerender } = show();
    expect(head(/^October 2026/).getAttribute('aria-expanded')).toBe('true');
    const september = rows.filter((r) => r.business_date?.startsWith('2026-09'));
    rerender(
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me: admin, branch: ALL_BRANCHES }}>
          <RecordsByShop list="expenses" levels={EXPENSE_ORDER} figures={FIGURES} totalWord="Spent" rows={september} empty="No expenses." what="expense" renderRow={(r) => <span>{r.item_name}</span>} />
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    const shopA = head(/^Test Branch A/);
    expect(shopA.getAttribute('aria-expanded')).toBe('true');
    expect(head(/^September 2026/, bodyOf(shopA)).getAttribute('aria-expanded')).toBe('true');
    expect(bodyOf(shopA).textContent).toContain('Test item 05');
  });

  it('draws every amount in tabular figures, in a header a thumb can reach', () => {
    show();
    for (const total of document.querySelectorAll('.rt-group-total')) expect(total.querySelector('.num')).not.toBeNull();
    expect(document.querySelectorAll('button.rt-group-head').length).toBeGreaterThan(5);
  });

  it('shows no total when the reader may not see the amounts, only the count', () => {
    const noAmounts: Savings[] = [
      { id: 's1', branch_id: a, business_date: '2026-10-05' },
      { id: 's2', branch_id: a, business_date: '2026-10-06' },
    ];
    const groups = cashGroups(noAmounts, 'branch', {}, { amount: (r) => r.amount_minor }, () => 'Test Branch A');
    expect(groups[0]).toMatchObject({ count: 2 });
    expect(groups[0]?.totalMinor).toBeUndefined();
    render(
      <QueryClientProvider client={new QueryClient()}>
        <StaffContext.Provider value={{ me: admin, branch: ALL_BRANCHES }}>
          <RecordsByShop list="savings" figures={{ amount: (r: Savings) => r.amount_minor }} totalWord="Saved" rows={noAmounts} empty="None." what="savings record" renderRow={(r) => <span>{r.id}</span>} />
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    expect(document.querySelector('.rt-group-total')).toBeNull();
    expect(head(/^Test Branch A/).textContent).toContain('2 savings records');
  });

  it('sums the same rows the list holds: the groups of a level add up to the level above', () => {
    const months = cashGroups(rows, 'month', { branchId: a }, FIGURES, () => '');
    const shop = cashGroups(rows, 'branch', {}, FIGURES, () => '').find((g) => g.key === a);
    expect(months.reduce((n, g) => n + (g.totalMinor ?? 0), 0)).toBe(shop?.totalMinor);
    expect(months.map((g) => [g.key, g.from, g.to])).toEqual([['2026-10', '2026-10-01', '2026-10-31'], ['2026-09', '2026-09-01', '2026-09-30']]);
  });
});
