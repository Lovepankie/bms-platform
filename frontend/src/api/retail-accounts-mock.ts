import type { AccountPayment, Customer, CustomerAccount, OpenSale, RetailApi, Sale, SaleRequest, Statement, StatementLine } from './retail';
import { RetailError } from './retail';
import { retailMessage } from './retail-errors';

// Fabricated customer accounts for VITE_RETAIL_MOCK and for tests (ADR-033, issue #196): the REAL snake_case shapes of
// docs/sdd/07-api-design.md section 7.11.20 held in memory, with the server's rules in small: a limit and terms per
// buyer, the limit check of a credit sale, payment on account allocated oldest first or as chosen, reversal as a whole,
// and the statement. Every route answers 404 capability_not_enabled while the capability is off. Nothing here is real.

export interface AccountsEnv {
  /** The customer_accounts capability of this mock instance. */
  on: () => boolean;
  today: () => string;
  nextId: (prefix: string) => string;
  delay: <T>(value: T) => Promise<T>;
  run: <T>(fn: () => T) => Promise<T>;
  once: <T>(key: string, request: unknown, make: () => T) => T;
  refuse: (status: number, code: string, detail: string) => never;
  customers: () => Customer[];
  sales: () => Sale[];
}

export type AccountsMock = Pick<
  RetailApi,
  'customerAccount' | 'setCustomerAccount' | 'customerStatement' | 'customerBalance' | 'listAccountPayments' | 'payOnAccount' | 'reverseAccountPayment'
> & {
  /** The sale's part: refuses a typed buyer or a sale above the limit, and gives the due date terms imply. */
  forSale: (body: SaleRequest, totalMinor: number, saleDate: string) => { dueDate?: string };
  /** The limit and the terms of a buyer, for the buyer lists. */
  figures: (customerId: string | undefined) => { credit_limit_minor?: number; terms_days?: number };
};

interface Row {
  date: string;
  kind: string;
  reference: string;
  sale_id?: string;
  receipt_id?: string;
  method?: string;
  debit_minor: number;
  credit_minor: number;
  order: number;
}

interface Terms {
  limit?: number;
  terms?: number;
}

interface Receipt extends AccountPayment {
  customer: string;
  parts: { sale: Sale; amount: number }[];
  reversedOn?: string;
}

const addDays = (date: string, days: number): string => {
  const d = new Date(`${date}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
};

export function createAccountsMock(env: AccountsEnv): AccountsMock {
  const terms = new Map<string, Terms>();
  const receipts: Receipt[] = [];
  const gate = <T>(fn: () => T): Promise<T> => env.run(() => (env.on() ? fn() : env.refuse(404, 'capability_not_enabled', 'This capability is not enabled for the tenant.')));
  const customer = (id: string): Customer => env.customers().find((c) => c.id === id) ?? env.refuse(404, 'not_found', 'That could not be found.');
  const open = (id: string): Sale[] =>
    env.sales().filter((s) => s.customer_id === id && s.payment_method === 'credit' && s.status === 'completed' && (s.balance_minor ?? 0) > 0);
  const owed = (id: string): number => open(id).reduce((sum, s) => sum + (s.balance_minor ?? 0), 0);
  const sorted = (rows: Sale[]): Sale[] => [...rows].sort((a, b) => (a.sale_date ?? '').localeCompare(b.sale_date ?? ''));
  const asOpen = (s: Sale): OpenSale => ({
    sale_id: s.id ?? '', sale_no: s.sale_no ?? '', branch_id: s.branch_id ?? '', sale_date: s.sale_date ?? '', ...(s.due_date ? { due_date: s.due_date } : {}),
    total_minor: s.total_minor ?? 0, paid_minor: s.paid_minor ?? 0, balance_minor: s.balance_minor ?? 0,
  });
  const view = (r: Receipt): AccountPayment => ({
    id: r.id, receipt_no: r.receipt_no, customer_id: r.customer, branch_id: r.branch_id, amount_minor: r.amount_minor, currency: 'UGX', method: r.method, paid_on: r.paid_on,
    allocation: r.allocation, status: r.status,
    allocations: r.parts.map((p) => ({ sale_id: p.sale.id, sale_no: p.sale.sale_no, branch_id: p.sale.branch_id, amount_minor: p.amount, sale_payment_id: `${r.id}-${p.sale.id}` })),
    journal_entry_id: `${r.id}-entry`, created_at: r.created_at, created_by: r.created_by,
    ...(r.status === 'reversed' ? { reversed_at: r.reversed_at, reversed_by: r.reversed_by, reverse_reason: r.reverse_reason } : {}),
  });
  const account = (id: string): CustomerAccount => {
    const c = customer(id);
    const t = terms.get(id) ?? {};
    const exposure = owed(id);
    return {
      customer_id: id, name: c.name, currency: 'UGX', balance_minor: exposure, exposure_minor: exposure, version: c.version ?? 1,
      ...(t.limit !== undefined ? { credit_limit_minor: t.limit, available_minor: Math.max(0, t.limit - exposure), over_limit: exposure > t.limit } : {}),
      ...(t.terms !== undefined ? { terms_days: t.terms } : {}),
    };
  };

  return {
    figures: (id) => {
      const t = id && env.on() ? terms.get(id) : undefined;
      return { ...(t?.limit !== undefined ? { credit_limit_minor: t.limit } : {}), ...(t?.terms !== undefined ? { terms_days: t.terms } : {}) };
    },

    forSale: (body, total, saleDate) => {
      if (!env.on() || body.payment_method !== 'credit') return {};
      if (!body.customer_id) env.refuse(422, 'customer_required', 'A credit sale names a credit buyer from the list.');
      const t = terms.get(body.customer_id ?? '') ?? {};
      if (t.limit !== undefined) {
        const exceeded = owed(body.customer_id ?? '') + total - t.limit;
        if (exceeded > 0) {
          // The mock stands in for a user who may override when a reason is sent; the server checks the permission.
          if (!body.credit_override_reason || body.credit_override_reason.trim().length < 5) {
            throw new RetailError(retailMessage({ code: 'credit_limit_exceeded' }, 422), 422, 'credit_limit_exceeded', { code: 'credit_limit_exceeded', exceeded_by_minor: exceeded });
          }
        }
      }
      const due = body.due_date ?? (t.terms !== undefined ? addDays(saleDate, t.terms) : undefined);
      return due ? { dueDate: due } : {};
    },

    customerAccount: (id) => gate(() => account(id)),

    setCustomerAccount: (id, version, body) =>
      gate(() => {
        const c = customer(id);
        if ((c.version ?? 1) !== version) env.refuse(409, 'version_conflict', 'This was changed by someone else. Reload and try again.');
        // As the server: each field is a value or an explicit clear, never left out, never both.
        const decided = (value: number | undefined, clear: boolean | undefined) => (value !== undefined) !== (clear === true);
        if (!decided(body.credit_limit_minor, body.clear_credit_limit) || !decided(body.terms_days, body.clear_terms_days)) {
          env.refuse(422, 'validation_failed', 'Give the limit and the terms, or clear them on purpose.');
        }
        terms.set(id, { limit: body.credit_limit_minor, terms: body.terms_days });
        c.version = (c.version ?? 1) + 1;
        return account(id);
      }),

    customerBalance: (id) =>
      gate(() => {
        customer(id);
        const rows = sorted(open(id));
        return { customer_id: id, currency: 'UGX', balance_minor: owed(id), open_sales: rows.map(asOpen) };
      }),

    listAccountPayments: (id) =>
      gate(() => {
        customer(id);
        return { items: receipts.filter((r) => r.customer === id).reverse().map(view) };
      }),

    payOnAccount: (id, body, key) =>
      gate(() =>
        env.once(`account-payment|${key}`, body, (): AccountPayment => {
          customer(id);
          const paidOn = body.paid_on ?? env.today();
          if (paidOn > env.today()) env.refuse(422, 'validation_failed', 'A payment cannot be dated in the future.');
          const parts: { sale: Sale; amount: number }[] = [];
          if (body.allocations && body.allocations.length > 0) {
            const total = body.allocations.reduce((sum, a) => sum + a.amount_minor, 0);
            if (total !== body.amount_minor) env.refuse(422, 'allocations_must_equal_amount', 'The allocations must add up to the payment.');
            for (const a of body.allocations) {
              const sale = open(id).find((s) => s.id === a.sale_id) ?? env.refuse(422, 'validation_failed', 'No such open credit sale of this buyer.');
              if (a.amount_minor > (sale.balance_minor ?? 0)) env.refuse(422, 'payment_exceeds_balance', 'The sale owes less than that.');
              parts.push({ sale, amount: a.amount_minor });
            }
          } else {
            let left = body.amount_minor;
            for (const sale of sorted(open(id)).filter((s) => (s.sale_date ?? '') <= paidOn)) {
              if (left <= 0) break;
              const part = Math.min(left, sale.balance_minor ?? 0);
              parts.push({ sale, amount: part });
              left -= part;
            }
            if (left > 0) throw new RetailError(retailMessage({ code: 'payment_exceeds_allocatable' }, 422), 422, 'payment_exceeds_allocatable', { code: 'payment_exceeds_allocatable', allocatable_minor: body.amount_minor - left });
          }
          for (const p of parts) {
            p.sale.paid_minor = (p.sale.paid_minor ?? 0) + p.amount;
            p.sale.balance_minor = (p.sale.balance_minor ?? 0) - p.amount;
          }
          const r: Receipt = {
            id: env.nextId('r'), receipt_no: `RC${String(receipts.length + 1).padStart(8, '0')}`, customer: id, customer_id: id,
            branch_id: body.branch_id ?? parts[0]?.sale.branch_id ?? '', amount_minor: body.amount_minor, currency: 'UGX', method: body.method, paid_on: paidOn,
            allocation: body.allocations && body.allocations.length > 0 ? 'manual' : 'oldest_first', status: 'posted', allocations: [], journal_entry_id: '',
            created_at: new Date().toISOString(), created_by: 'mock-user', parts,
          };
          receipts.push(r);
          return view(r);
        }),
      ),

    reverseAccountPayment: (id, paymentId, reason, key) =>
      gate(() =>
        env.once(`account-reverse|${key}`, { id, paymentId, reason }, (): AccountPayment => {
          const r = receipts.find((x) => x.id === paymentId && x.customer === id) ?? env.refuse(404, 'not_found', 'That could not be found.');
          if (r.status === 'reversed') env.refuse(409, 'receipt_reversed', 'This payment has been reversed already.');
          for (const p of r.parts) {
            p.sale.paid_minor = (p.sale.paid_minor ?? 0) - p.amount;
            p.sale.balance_minor = (p.sale.balance_minor ?? 0) + p.amount;
          }
          r.status = 'reversed';
          r.reversed_at = new Date().toISOString();
          r.reversed_by = 'mock-user';
          r.reverse_reason = reason;
          r.reversedOn = env.today();
          return view(r);
        }),
      ),

    customerStatement: (id, { from, to }) =>
      gate(() => {
        customer(id);
        const end = to ?? env.today();
        const start = from ?? addDays(end, -89);
        const all: Row[] = [];
        for (const s of env.sales().filter((x) => x.customer_id === id && x.payment_method === 'credit')) {
          all.push({ date: s.sale_date ?? '', kind: 'sale', reference: s.sale_no ?? '', sale_id: s.id, debit_minor: s.total_minor ?? 0, credit_minor: 0, order: 1 });
          if (s.status === 'voided') all.push({ date: env.today(), kind: 'void', reference: s.sale_no ?? '', sale_id: s.id, debit_minor: 0, credit_minor: s.total_minor ?? 0, order: 4 });
        }
        for (const r of receipts.filter((x) => x.customer === id)) {
          all.push({ date: r.paid_on ?? '', kind: 'account_payment', reference: r.receipt_no ?? '', receipt_id: r.id, method: r.method, debit_minor: 0, credit_minor: r.amount_minor ?? 0, order: 2 });
          if (r.status === 'reversed') all.push({ date: r.reversedOn ?? env.today(), kind: 'reversal', reference: r.receipt_no ?? '', receipt_id: r.id, method: r.method, debit_minor: r.amount_minor ?? 0, credit_minor: 0, order: 3 });
        }
        all.sort((a, b) => a.date.localeCompare(b.date) || a.order - b.order);
        const opening = all.filter((l) => l.date < start).reduce((sum, l) => sum + l.debit_minor - l.credit_minor, 0);
        let running = opening;
        const lines: StatementLine[] = all
          .filter((l) => l.date >= start && l.date <= end)
          .map(({ order: _order, ...l }) => {
            running += l.debit_minor - l.credit_minor;
            return { ...l, balance_minor: running };
          });
        const out: Statement = { customer_id: id, name: customer(id).name, currency: 'UGX', from: start, to: end, opening_minor: opening, lines, closing_minor: running, balance_minor: running };
        return out;
      }),
  };
}
