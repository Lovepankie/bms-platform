import type {
  Advance, AdvanceParty, Banking, BankingDay, CashDailyRow, CashParty, CashQuery, Expense, ExpenseCategory, ExpenseGroup, RetailApi, Savings, Withdrawal,
} from './retail';
import { RetailError, daysBefore } from './retail';
import { retailMessage } from './retail-errors';

// The cash book half of the fabricated retail mock (ADR-022): savings, banking, withdrawals, expenses,
// parties, advances and the reports, with the real snake_case shapes and the real refusals. Every
// figure that needs retail.profit.read (every savings amount, the suggestion, profit, cash purchases
// and every figure net of savings) is left out of a body unless the session holds it, so the screens
// can be checked against what a cashier really gets. Names and amounts are invented.

export interface CashbookEnv {
  /** True when the session holds retail.profit.read. */
  profit: () => boolean;
  today: () => string;
  /** The day's sales at a branch: the total sold, the part paid in cash, and what it cost. */
  sold: (branchId: string, date: string) => { total: number; cash: number; cost: number };
  branchIds: string[];
  delay: <T>(value: T) => Promise<T>;
  run: <T>(fn: () => T) => Promise<T>;
  once: <T>(key: string, request: unknown, make: () => T) => T;
  nextId: (prefix: string) => string;
}

type Keys = 'savingsSuggestion' | 'createSavings' | 'listSavings' | 'voidSavings' | 'bankingExpected' | 'createBanking' | 'listBankings' | 'voidBanking'
  | 'createWithdrawal' | 'listWithdrawals' | 'voidWithdrawal' | 'listExpenseCategories' | 'createExpenseCategory' | 'updateExpenseCategory'
  | 'createExpenseItem' | 'updateExpenseItem' | 'listCashParties' | 'createCashParty' | 'createExpense' | 'listExpenses' | 'voidExpense'
  | 'createAdvance' | 'listAdvances' | 'getAdvance' | 'voidAdvance' | 'createRepayment' | 'voidRepayment' | 'cashDaily' | 'bankingReport'
  | 'expensesReport' | 'advancesReport';

const refuse = (status: number, code: string, detail: string, extra: Record<string, unknown> = {}): never => {
  throw new RetailError(retailMessage({ code, detail }, status), status, code, { code, detail, ...extra });
};

const USER = { by: '00000000-0000-4000-8000-0000000000f1', by_name: 'Test User 01' };
const PARTY_IDS = { owner: 'a0000000-0000-4000-8000-000000000001', staff: 'a0000000-0000-4000-8000-000000000002', related: 'a0000000-0000-4000-8000-000000000003', supplier: 'a0000000-0000-4000-8000-000000000004' };

/** The shape of every stored record: the fields all of them share. */
interface Rec { id: string; branch_id: string; business_date: string; at: string; voided: boolean; void_reason?: string; voided_at?: string; historical: boolean }

export function createCashbookMock(env: CashbookEnv): Pick<RetailApi, Keys> {
  const { delay, run, once, nextId } = env;
  const savings: (Rec & { amount: number; total_sold: number; suggested: number; overwritten: boolean; reason?: string })[] = [];
  const bankings: (Rec & { amount: number; expected: number; banked_at: string; reference?: string; entryBy: typeof USER })[] = [];
  const withdrawals: (Rec & { amount: number; purpose?: string; withdrawn_at: string })[] = [];
  const expenses: (Rec & { amount: number; category_id: string; item_id: string; party_id?: string; explanation?: string })[] = [];
  const advances: (Rec & { advance_no: string; party_id: string; taken_by_party_id?: string; principal: number; purpose?: string })[] = [];
  const repayments: (Rec & { advance_id: string; amount: number; method: string; paid_on: string })[] = [];
  const parties: CashParty[] = [
    { id: PARTY_IDS.owner, name: 'Test Owner 01', kind: 'owner', active: true },
    { id: PARTY_IDS.staff, name: 'Test Staff 01', kind: 'staff', active: true },
    { id: PARTY_IDS.related, name: 'Test Company 01', kind: 'related_entity', active: true },
    { id: PARTY_IDS.supplier, name: 'Test Vendor 01', kind: 'supplier', active: true },
  ];
  const categories: ExpenseCategory[] = [
    { id: 'e0000000-0000-4000-8000-000000000001', name: 'Transport', active: true, sort_order: 1, version: 1, items: [
      { id: 'e1000000-0000-4000-8000-000000000001', name: 'Fuel', requires_explanation: false, active: true, version: 1 },
      { id: 'e1000000-0000-4000-8000-000000000002', name: 'Boda fare', requires_explanation: false, active: true, version: 1 },
      { id: 'e1000000-0000-4000-8000-000000000003', name: 'Old route', requires_explanation: false, active: false, version: 1 },
    ] },
    { id: 'e0000000-0000-4000-8000-000000000002', name: 'Utilities', active: true, sort_order: 2, version: 1, items: [
      { id: 'e1000000-0000-4000-8000-000000000004', name: 'Electricity', requires_explanation: false, active: true, version: 1 },
      { id: 'e1000000-0000-4000-8000-000000000005', name: 'Others', requires_explanation: true, active: true, version: 1 },
    ] },
  ];
  let advanceNo = 0;

  const stamp = () => new Date().toISOString();
  const base = (branch_id: string | undefined, business_date: string | undefined, prefix: string): Rec => {
    const date = business_date ?? env.today();
    if (date > env.today()) refuse(422, 'validation_failed', 'The date is in the future.', { errors: [{ field: 'business_date', code: 'future_date', message: 'The date cannot be in the future.' }] });
    return { id: nextId(prefix), branch_id: branch_id ?? env.branchIds[0] ?? '', business_date: date, at: stamp(), voided: false, historical: false };
  };
  const inWindow = (r: Rec, q: CashQuery) =>
    (!q.branchIds || q.branchIds.length === 0 || q.branchIds.includes(r.branch_id)) && (!q.from || r.business_date >= q.from) && (!q.to || r.business_date <= q.to) && (q.includeVoided || !r.voided);
  const newestFirst = <T extends Rec>(rows: T[]) => [...rows].sort((a, b) => (a.business_date === b.business_date ? b.at.localeCompare(a.at) : b.business_date.localeCompare(a.business_date)));
  const voidIt = <T extends Rec>(row: T | undefined, reason: string): T => {
    if (!row) return refuse(404, 'not_found', 'That could not be found.');
    if (row.voided) refuse(409, 'cash_record_voided', 'Already voided.');
    Object.assign(row, { voided: true, void_reason: reason, voided_at: stamp() });
    return row;
  };
  const common = (r: Rec) => ({
    id: r.id, branch_id: r.branch_id, business_date: r.business_date, at: r.at, ...USER, currency: 'UGX', voided: r.voided, historical: r.historical,
    ...(r.void_reason ? { void_reason: r.void_reason } : {}), ...(r.voided_at ? { voided_at: r.voided_at } : {}),
  });

  // What the day looked like at a branch: the cash book's own figures, before and after savings.
  const figures = (branch: string, date: string) => {
    const live = (r: Rec) => !r.voided && r.branch_id === branch && r.business_date === date;
    const sum = (rows: (Rec & { amount: number })[]) => rows.filter(live).reduce((s, r) => s + r.amount, 0);
    const sold = env.sold(branch, date);
    const expensesMinor = sum(expenses);
    const advancesOut = advances.filter(live).reduce((s, r) => s + r.principal, 0);
    const repaymentsIn = repayments.filter((r) => !r.voided && r.branch_id === branch && r.paid_on === date && r.method === 'cash').reduce((s, r) => s + r.amount, 0);
    const savingsMinor = sum(savings);
    const cashExpected = sold.cash - expensesMinor - advancesOut + repaymentsIn;
    return { sold, expensesMinor, advancesOut, repaymentsIn, savingsMinor, cashExpected, expected: cashExpected - savingsMinor, banked: sum(bankings), withdrawn: sum(withdrawals) };
  };
  const flagOf = (expected: number, banked: number): 'ok' | 'shortfall' | 'surplus' | 'not_banked' => (banked === 0 ? 'not_banked' : banked === expected ? 'ok' : banked < expected ? 'shortfall' : 'surplus');
  const suggestion = (branch: string, date: string) => {
    const f = figures(branch, date);
    const profit = f.sold.total - f.sold.cost;
    const suggested = Math.max(0, Math.round(profit / 2));
    return { total: f.sold.total, profit, suggested, token: `tok-${branch.slice(-2)}-${date}-${suggested}` };
  };

  const savingsRow = (r: (typeof savings)[number]): Savings => ({
    ...common(r), total_sold_minor: r.total_sold, overwritten: r.overwritten,
    ...(env.profit() ? { amount_minor: r.amount, suggested_minor: r.suggested } : {}),
  });
  const bankingRow = (r: (typeof bankings)[number]): Banking => {
    const f = figures(r.branch_id, r.business_date);
    const row: Banking = { ...common(r), ...r.entryBy, amount_minor: r.amount, banked_at: r.banked_at, ...(r.reference ? { reference: r.reference } : {}) };
    if (env.profit()) Object.assign(row, { expected_minor: r.expected, difference_minor: f.banked - r.expected, flag: flagOf(r.expected, f.banked) });
    return row;
  };
  const expenseRow = (r: (typeof expenses)[number]): Expense => {
    const category = categories.find((c) => c.id === r.category_id);
    return {
      ...common(r), amount_minor: r.amount, category_id: r.category_id, category_name: category?.name, item_id: r.item_id,
      item_name: category?.items?.find((i) => i.id === r.item_id)?.name, ...(r.party_id ? { party_id: r.party_id, party_name: parties.find((p) => p.id === r.party_id)?.name } : {}),
      ...(r.explanation ? { explanation: r.explanation } : {}),
    };
  };
  const repayRow = (r: (typeof repayments)[number]): NonNullable<Advance['repayments']>[number] => {
    const adv = advances.find((a) => a.id === r.advance_id);
    const paid = repayments.filter((x) => x.advance_id === r.advance_id && !x.voided).reduce((s, x) => s + x.amount, 0);
    return { ...common(r), advance_id: r.advance_id, amount_minor: r.amount, method: r.method, paid_on: r.paid_on, balance_minor: (adv?.principal ?? 0) - paid };
  };
  const advanceRow = (a: (typeof advances)[number], withRepayments = false): Advance => {
    const mine = repayments.filter((r) => r.advance_id === a.id);
    const repaid = mine.filter((r) => !r.voided).reduce((s, r) => s + r.amount, 0);
    return {
      ...common(a), advance_no: a.advance_no, party_id: a.party_id, party_name: parties.find((p) => p.id === a.party_id)?.name, principal_minor: a.principal,
      repaid_minor: repaid, balance_minor: a.voided ? 0 : a.principal - repaid, ...(a.purpose ? { purpose: a.purpose } : {}),
      ...(a.taken_by_party_id ? { taken_by_party_id: a.taken_by_party_id, taken_by_name: parties.find((p) => p.id === a.taken_by_party_id)?.name } : {}),
      ...(withRepayments ? { repayments: newestFirst(mine).map(repayRow) } : {}),
    };
  };

  // One imported day of each kind, so the reports can show it apart from the live days.
  const old = daysBefore(env.today(), 100);
  const branchA = env.branchIds[0] ?? '';
  const oldRec = (prefix: string): Rec => ({ id: nextId(prefix), branch_id: branchA, business_date: old, at: `${old}T12:00:00Z`, voided: false, historical: true });
  bankings.push({ ...oldRec('b'), amount: 300000, expected: 320000, banked_at: `${old}T12:00:00Z`, entryBy: { by: 'imported', by_name: 'Test Import 01' } });
  expenses.push({ ...oldRec('x'), amount: 40000, category_id: categories[0]?.id ?? '', item_id: categories[0]?.items?.[0]?.id ?? '' });

  const group = (rows: Expense[], by: string): ExpenseGroup[] => {
    const keyOf = (r: Expense) => (by === 'item' ? [r.item_id ?? '', r.item_name ?? ''] : by === 'branch' ? [r.branch_id ?? '', r.branch_id ?? ''] : by === 'month' ? [(r.business_date ?? '').slice(0, 7), (r.business_date ?? '').slice(0, 7)] : [r.category_id ?? '', r.category_name ?? '']);
    const map = new Map<string, ExpenseGroup>();
    rows.forEach((r) => {
      const [key, label] = keyOf(r) as [string, string];
      const g = map.get(key) ?? { key, label, count: 0, total_minor: 0, ...(by === 'branch' ? { branch_id: r.branch_id } : {}) };
      g.count = (g.count ?? 0) + 1;
      g.total_minor = (g.total_minor ?? 0) + (r.amount_minor ?? 0);
      map.set(key, g);
    });
    return [...map.values()].sort((a, b) => (b.total_minor ?? 0) - (a.total_minor ?? 0));
  };

  return {
    savingsSuggestion: ({ branchId, date }) =>
      run(() => {
        const s = suggestion(branchId, date);
        const existing = savings.find((r) => !r.voided && r.branch_id === branchId && r.business_date === date);
        return {
          branch_id: branchId, business_date: date, total_sold_minor: s.total, suggestion_token: s.token, ...(existing ? { existing_id: existing.id } : {}),
          ...(env.profit() ? { suggested_minor: s.suggested, daily_profit_minor: s.profit } : {}),
        };
      }),

    createSavings: (body, key) =>
      run(() => once(`savings|${key}`, body, (): Savings => {
        const r = base(body.branch_id, body.business_date, 'v');
        const s = suggestion(r.branch_id, r.business_date);
        if (!body.suggestion_token) refuse(422, 'suggestion_token_required', 'The suggestion token is required.');
        if (!env.profit() && (body.amount_minor !== undefined || body.overwrite_reason !== undefined)) refuse(422, 'amount_requires_profit_access', 'Only the default can be recorded without profit access.');
        if (body.suggestion_token !== s.token) refuse(409, 'suggestion_changed', 'The suggestion changed.', { suggestion_token: s.token, ...(env.profit() ? { suggested_minor: s.suggested } : {}) });
        if (savings.some((x) => !x.voided && x.branch_id === r.branch_id && x.business_date === r.business_date)) refuse(409, 'savings_exists', 'Savings exist for that day.');
        const amount = body.amount_minor ?? s.suggested;
        const overwritten = amount !== s.suggested;
        if (overwritten && (body.overwrite_reason ?? '').trim().length < 5) refuse(422, 'reason_required', 'A reason is required.');
        const row = { ...r, amount, total_sold: s.total, suggested: s.suggested, overwritten, ...(overwritten ? { reason: body.overwrite_reason } : {}) };
        savings.push(row);
        // The writer sees the amount only when the writer typed it; a default taken without profit access is not echoed.
        const { amount_minor: _hidden, ...rest } = savingsRow(row);
        return body.amount_minor !== undefined ? savingsRow(row) : rest;
      })),

    listSavings: (q) => delay(newestFirst(savings.filter((r) => inWindow(r, q))).map(savingsRow)),
    voidSavings: (id, reason, key) => run(() => once(`void|${key}`, { id, reason }, () => savingsRow(voidIt(savings.find((r) => r.id === id), reason)))),

    bankingExpected: ({ branchId, date }) =>
      run(() => {
        const f = figures(branchId, date);
        return {
          branch_id: branchId, business_date: date, cash_takings_minor: f.sold.cash, cash_sale_voids_minor: 0, expense_voids_minor: 0, advance_voids_minor: 0,
          repayment_voids_minor: 0, expenses_minor: f.expensesMinor, advances_out_minor: f.advancesOut, repayments_in_minor: f.repaymentsIn,
          cash_expected_minor: f.cashExpected, banked_so_far_minor: f.banked,
          ...(env.profit() ? { cash_purchases_minor: 0, savings_minor: f.savingsMinor, savings_voids_minor: 0, expected_minor: f.expected } : {}),
        };
      }),

    createBanking: (body, key) =>
      run(() => once(`banking|${key}`, body, (): Banking => {
        const r = base(body.branch_id, body.business_date, 'b');
        const row = { ...r, amount: body.amount_minor, expected: figures(r.branch_id, r.business_date).expected, banked_at: body.banked_at ?? r.at, entryBy: USER, ...(body.reference ? { reference: body.reference } : {}) };
        bankings.push(row);
        const out = bankingRow(row);
        if (env.profit()) out.warnings = row.amount > Math.max(0, figures(r.branch_id, r.business_date).cashExpected) ? ['cash_below_banked'] : [];
        return out;
      })),

    listBankings: (q) => delay(newestFirst(bankings.filter((r) => inWindow(r, q))).map(bankingRow)),
    voidBanking: (id, reason, key) => run(() => once(`void|${key}`, { id, reason }, () => bankingRow(voidIt(bankings.find((r) => r.id === id), reason)))),

    createWithdrawal: (body, key) =>
      run(() => once(`withdrawal|${key}`, body, (): Withdrawal => {
        const r = base(body.branch_id, body.business_date, 'w');
        const bank = bankings.filter((x) => !x.voided).reduce((s, x) => s + x.amount, 0) - withdrawals.filter((x) => !x.voided).reduce((s, x) => s + x.amount, 0);
        const row = { ...r, amount: body.amount_minor, withdrawn_at: body.withdrawn_at ?? r.at, ...(body.purpose ? { purpose: body.purpose } : {}) };
        withdrawals.push(row);
        return { ...common(row), ...USER, amount_minor: row.amount, withdrawn_at: row.withdrawn_at, ...(row.purpose ? { purpose: row.purpose } : {}), ...(row.amount > bank ? { warnings: ['bank_balance_negative'] } : {}) };
      })),
    listWithdrawals: (q) =>
      delay(newestFirst(withdrawals.filter((r) => inWindow(r, q))).map((r): Withdrawal => ({ ...common(r), ...USER, amount_minor: r.amount, withdrawn_at: r.withdrawn_at, ...(r.purpose ? { purpose: r.purpose } : {}) }))),
    voidWithdrawal: (id, reason, key) =>
      run(() => once(`void|${key}`, { id, reason }, (): Withdrawal => {
        const r = voidIt(withdrawals.find((x) => x.id === id), reason);
        return { ...common(r), amount_minor: r.amount, withdrawn_at: r.withdrawn_at };
      })),

    listExpenseCategories: ({ activeOnly } = {}) =>
      delay(categories.filter((c) => !activeOnly || c.active).map((c) => ({ ...c, items: (c.items ?? []).filter((i) => !activeOnly || i.active).map((i) => ({ ...i })) }))),
    createExpenseCategory: ({ name }) =>
      run(() => {
        if (categories.some((c) => c.name?.toLowerCase() === name.trim().toLowerCase())) refuse(409, 'duplicate_category', 'Duplicate.');
        const c: ExpenseCategory = { id: nextId('e'), name: name.trim(), active: true, sort_order: categories.length + 1, version: 1, items: [] };
        categories.push(c);
        return { ...c };
      }),
    updateExpenseCategory: (id, body, version) =>
      run(() => {
        const c = categories.find((x) => x.id === id);
        if (!c) return refuse(404, 'not_found', 'Not found.');
        if (c.version !== version) refuse(409, 'version_conflict', 'Stale.');
        if (body.name && categories.some((x) => x.id !== id && x.name?.toLowerCase() === body.name?.trim().toLowerCase())) refuse(409, 'duplicate_category', 'Duplicate.');
        Object.assign(c, { ...(body.name ? { name: body.name.trim() } : {}), ...(body.active !== undefined ? { active: body.active } : {}), version: (c.version ?? 1) + 1 });
        return { ...c };
      }),
    createExpenseItem: (categoryId, body) =>
      run(() => {
        const c = categories.find((x) => x.id === categoryId);
        if (!c) return refuse(404, 'not_found', 'Not found.');
        if ((c.items ?? []).some((i) => i.name?.toLowerCase() === body.name.trim().toLowerCase())) refuse(409, 'duplicate_item', 'Duplicate.');
        const item = { id: nextId('e'), name: body.name.trim(), requires_explanation: body.requires_explanation ?? false, active: true, version: 1 };
        c.items = [...(c.items ?? []), item];
        return { ...item };
      }),
    updateExpenseItem: (categoryId, itemId, body, version) =>
      run(() => {
        const item = categories.find((x) => x.id === categoryId)?.items?.find((i) => i.id === itemId);
        if (!item) return refuse(404, 'not_found', 'Not found.');
        if (item.version !== version) refuse(409, 'version_conflict', 'Stale.');
        Object.assign(item, { ...(body.name ? { name: body.name.trim() } : {}), ...(body.active !== undefined ? { active: body.active } : {}), ...(body.requires_explanation !== undefined ? { requires_explanation: body.requires_explanation } : {}), version: (item.version ?? 1) + 1 });
        return { ...item };
      }),

    listCashParties: ({ kind } = {}) => delay(parties.filter((p) => !kind || p.kind === kind).map((p) => ({ ...p }))),
    createCashParty: (body) =>
      run(() => {
        if (parties.some((p) => p.name?.toLowerCase() === body.name.trim().toLowerCase())) refuse(409, 'duplicate_party', 'Duplicate.');
        const p: CashParty = { id: nextId('a'), name: body.name.trim(), kind: body.kind, active: true, ...(body.contact ? { contact: body.contact } : {}) };
        parties.push(p);
        return { ...p };
      }),

    createExpense: (body, key) =>
      run(() => once(`expense|${key}`, body, (): Expense => {
        const r = base(body.branch_id, body.business_date, 'x');
        const category = categories.find((c) => c.id === body.category_id);
        const item = category?.items?.find((i) => i.id === body.item_id);
        if (!category || !item) refuse(422, 'item_not_in_category', 'The item is not in the category.');
        if (!category?.active) refuse(422, 'category_inactive', 'Inactive.');
        if (item?.requires_explanation && (body.explanation ?? '').trim().length < 3) refuse(422, 'explanation_required', 'Explanation required.');
        const row = { ...r, amount: body.amount_minor, category_id: body.category_id, item_id: body.item_id, ...(body.party_id ? { party_id: body.party_id } : {}), ...(body.explanation ? { explanation: body.explanation } : {}) };
        expenses.push(row);
        return expenseRow(row);
      })),
    listExpenses: (q) =>
      delay(newestFirst(expenses.filter((r) => inWindow(r, q) && (!q.categoryId || r.category_id === q.categoryId) && (!q.itemId || r.item_id === q.itemId))).map(expenseRow)),
    voidExpense: (id, reason, key) => run(() => once(`void|${key}`, { id, reason }, () => expenseRow(voidIt(expenses.find((r) => r.id === id), reason)))),

    createAdvance: (body, key) =>
      run(() => once(`advance|${key}`, body, (): Advance => {
        const r = base(body.branch_id, body.business_date, 'd');
        const party = parties.find((p) => p.id === body.party_id);
        if (!party) refuse(422, 'validation_failed', 'No such party.');
        if (party && !['owner', 'staff', 'related_entity'].includes(party.kind ?? '')) refuse(422, 'party_kind_not_allowed', 'Not allowed.');
        const row = { ...r, advance_no: `ADV-${String(++advanceNo).padStart(6, '0')}`, party_id: body.party_id, principal: body.principal_minor, ...(body.taken_by_party_id ? { taken_by_party_id: body.taken_by_party_id } : {}), ...(body.purpose ? { purpose: body.purpose } : {}) };
        advances.push(row);
        return advanceRow(row);
      })),
    listAdvances: (q) =>
      delay(newestFirst(advances.filter((r) => inWindow(r, q) && (!q.partyId || r.party_id === q.partyId))).map((r) => advanceRow(r)).filter((a) => !q.openOnly || (a.balance_minor ?? 0) > 0)),
    getAdvance: (id) => {
      const a = advances.find((x) => x.id === id);
      return a ? delay(advanceRow(a, true)) : Promise.reject(new RetailError('That could not be found.', 404, 'not_found'));
    },
    voidAdvance: (id, reason, key) =>
      run(() => once(`void|${key}`, { id, reason }, () => {
        const a = advances.find((x) => x.id === id);
        if (a && repayments.some((r) => r.advance_id === id && !r.voided)) refuse(409, 'advance_has_repayments', 'Has repayments.');
        return advanceRow(voidIt(a, reason));
      })),
    createRepayment: (advanceId, body, key) =>
      run(() => once(`repayment|${key}`, { advanceId, body }, () => {
        const a = advances.find((x) => x.id === advanceId);
        if (!a) return refuse(404, 'not_found', 'Not found.');
        const balance = advanceRow(a).balance_minor ?? 0;
        if (balance <= 0) refuse(422, 'advance_settled', 'Settled.');
        if (body.amount_minor > balance) refuse(422, 'repayment_exceeds_balance', 'Too much.');
        const r = base(body.branch_id ?? a.branch_id, body.paid_on, 'r');
        const row = { ...r, advance_id: advanceId, amount: body.amount_minor, method: body.method, paid_on: r.business_date };
        repayments.push(row);
        return repayRow(row);
      })),
    voidRepayment: (advanceId, repaymentId, reason, key) =>
      run(() => once(`void|${key}`, { repaymentId, reason }, () => repayRow(voidIt(repayments.find((x) => x.id === repaymentId && x.advance_id === advanceId), reason)))),

    cashDaily: (q) =>
      run(() => {
        const from = q.from ?? daysBefore(env.today(), 30);
        const to = q.to ?? env.today();
        const days = new Set<string>();
        const add = (r: { branch_id: string; business_date: string }) => {
          if (r.business_date >= from && r.business_date <= to && (!q.branchIds || q.branchIds.length === 0 || q.branchIds.includes(r.branch_id))) days.add(`${r.branch_id}|${r.business_date}`);
        };
        [...savings, ...bankings, ...withdrawals, ...expenses, ...advances].forEach(add);
        env.branchIds.forEach((b) => { for (let i = 0; i < 10; i++) if (env.sold(b, daysBefore(env.today(), i)).total > 0) add({ branch_id: b, business_date: daysBefore(env.today(), i) }); });
        let running = 0;
        const items = [...days].sort((a, b) => a.split('|')[1]!.localeCompare(b.split('|')[1]!)).map((k): CashDailyRow => {
          const [branch, date] = k.split('|') as [string, string];
          const f = figures(branch, date);
          const isOld = bankings.some((b) => b.historical && b.branch_id === branch && b.business_date === date) || expenses.some((b) => b.historical && b.branch_id === branch && b.business_date === date);
          running += f.expected - f.banked;
          const row: CashDailyRow = {
            branch_id: branch, business_date: date, cash_takings_minor: f.sold.cash, cash_sale_voids_minor: 0, expense_voids_minor: 0, advance_voids_minor: 0, repayment_voids_minor: 0,
            banking_voids_minor: 0, withdrawal_voids_minor: 0, expenses_minor: f.expensesMinor, advances_out_minor: f.advancesOut, repayments_in_minor: f.repaymentsIn,
            withdrawals_in_minor: f.withdrawn, banked_minor: f.banked, cash_expected_minor: f.cashExpected, ledger_basis: !isOld, historical: isOld,
          };
          if (env.profit() && !isOld) {
            Object.assign(row, {
              cash_purchases_minor: 0, savings_minor: f.savingsMinor, savings_voids_minor: 0, opening_minor: 0, closing_minor: f.expected - f.banked + f.withdrawn, other_movements_minor: 0,
              expected_to_bank_minor: f.expected, unbanked_running_minor: running, daily_profit_minor: f.sold.total - f.sold.cost,
            });
          }
          return row;
        });
        return { currency: 'UGX', from, to, items: items.reverse() };
      }),

    bankingReport: (q) =>
      run(() => {
        const from = q.from ?? daysBefore(env.today(), 30);
        const to = q.to ?? env.today();
        const keys = new Set<string>();
        bankings.filter((r) => inWindow(r, { ...q, from, to })).forEach((r) => keys.add(`${r.branch_id}|${r.business_date}`));
        env.branchIds.forEach((b) => { for (let i = 0; i < 10; i++) { const d = daysBefore(env.today(), i); if (d >= from && d <= to && env.sold(b, d).cash > 0 && (!q.branchIds || q.branchIds.length === 0 || q.branchIds.includes(b))) keys.add(`${b}|${d}`); } });
        let running = 0;
        const items = [...keys].sort((a, b) => a.split('|')[1]!.localeCompare(b.split('|')[1]!)).map((k): BankingDay => {
          const [branch, date] = k.split('|') as [string, string];
          const f = figures(branch, date);
          const own = bankings.filter((r) => r.branch_id === branch && r.business_date === date);
          const historical = own.some((r) => r.historical);
          const day: BankingDay = {
            branch_id: branch, business_date: date, cash_expected_minor: f.cashExpected, banked_minor: f.banked, historical,
            entries: own.map((r) => ({ id: r.id, amount_minor: r.amount, banked_at: r.banked_at, by: r.entryBy.by, by_name: r.entryBy.by_name, voided: r.voided })),
          };
          if (env.profit()) {
            Object.assign(day, { expected_minor: f.expected, difference_minor: f.banked - f.expected, flag: flagOf(f.expected, f.banked) });
            if (!historical) { running += f.expected - f.banked; day.unbanked_running_minor = running; }
          }
          return day;
        }).filter((d) => !q.flag || d.flag === q.flag);
        return { currency: 'UGX', from, to, items: items.reverse() };
      }),

    expensesReport: (q) =>
      run(() => {
        const from = q.from ?? daysBefore(env.today(), 30);
        const to = q.to ?? env.today();
        const rows = expenses.filter((r) => inWindow(r, { ...q, from, to })).map(expenseRow);
        return { currency: 'UGX', from, to, group_by: q.groupBy, items: group(rows, q.groupBy), count: rows.length, total_minor: rows.reduce((s, r) => s + (r.amount_minor ?? 0), 0) };
      }),

    advancesReport: ({ branchIds }) =>
      run(() => {
        const open = advances.filter((a) => !a.voided && (!branchIds || branchIds.length === 0 || branchIds.includes(a.branch_id)));
        const map = new Map<string, AdvanceParty>();
        open.forEach((a) => {
          const row = advanceRow(a);
          if ((row.balance_minor ?? 0) <= 0) return;
          const g = map.get(a.party_id) ?? { party_id: a.party_id, party_name: row.party_name, count: 0, principal_minor: 0, repaid_minor: 0, balance_minor: 0, oldest_advance_date: a.business_date };
          g.count = (g.count ?? 0) + 1;
          g.principal_minor = (g.principal_minor ?? 0) + (row.principal_minor ?? 0);
          g.repaid_minor = (g.repaid_minor ?? 0) + (row.repaid_minor ?? 0);
          g.balance_minor = (g.balance_minor ?? 0) + (row.balance_minor ?? 0);
          if (a.business_date < (g.oldest_advance_date ?? '9999')) g.oldest_advance_date = a.business_date;
          map.set(a.party_id, g);
        });
        const items = [...map.values()];
        return { currency: 'UGX', items, balance_minor: items.reduce((s, g) => s + (g.balance_minor ?? 0), 0) };
      }),
  };
}
