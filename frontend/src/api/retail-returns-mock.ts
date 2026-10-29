import type { ReturnLine, ReturnOutcome, ReturnRequest, RetailApi, Sale, SaleReturn } from './retail';
import { milliOf, qtyString } from '../areas/staff/retail/maths';

// Fabricated customer returns for VITE_RETAIL_MOCK and for tests (ADR-033, issue #201): the REAL snake_case shapes of
// docs/sdd/07-api-design.md section 7.11.20 held in memory, with the server's rules in small: a return cannot exceed what
// was sold less what came back, the credit is the sale line's share of what it was charged (the last piece takes the
// remainder), an account refund only for a credit sale with a balance, the rest paid out in money, a number per branch,
// and a void that puts the balance back. Every route answers 404 capability_not_enabled while the capability is off.
// Stock and the ledger are not simulated. Nothing here is real.

export interface ReturnsEnv {
  /** The returns capability of this mock instance. */
  on: () => boolean;
  today: () => string;
  nextId: (prefix: string) => string;
  delay: <T>(value: T) => Promise<T>;
  run: <T>(fn: () => T) => Promise<T>;
  once: <T>(key: string, request: unknown, make: () => T) => T;
  refuse: (status: number, code: string, detail: string) => never;
  sales: () => Sale[];
  branchCode: (branchId: string | undefined) => string;
}

export type ReturnsMock = Pick<RetailApi, 'createSaleReturn' | 'listSaleReturns' | 'listReturns' | 'getReturn' | 'voidReturn'>;

const share = (amount: number, part: number, whole: number): number => Number((BigInt(amount) * BigInt(part) * 2n + BigInt(whole)) / (2n * BigInt(whole)));

export function createReturnsMock(env: ReturnsEnv): ReturnsMock {
  const returns: SaleReturn[] = [];
  const sequence = new Map<string, number>();
  const gate = <T>(fn: () => T): Promise<T> => env.run(() => (env.on() ? fn() : env.refuse(404, 'capability_not_enabled', 'This feature is not switched on for your business.')));
  const saleOf = (id: string): Sale => env.sales().find((s) => s.id === id) ?? env.refuse(404, 'not_found', 'That could not be found.');
  const done = (saleId: string, saleLineId: string | undefined): { qty: number; total: number } => {
    let qty = 0;
    let total = 0;
    for (const r of returns.filter((x) => x.sale_id === saleId && x.status === 'completed')) {
      for (const l of r.lines ?? []) if (l.sale_line_id === saleLineId) { qty += milliOf(l.qty ?? '0'); total += l.line_total_minor ?? 0; }
    }
    return { qty, total };
  };

  const record = (saleId: string, body: ReturnRequest): ReturnOutcome => {
    const sale = saleOf(saleId);
    if (sale.status === 'voided') env.refuse(409, 'sale_voided', 'This sale has been voided, which already took everything back.');
    const taken = new Map<string, { qty: number; total: number }>();
    const lines: ReturnLine[] = body.lines.map((l, i) => {
      const sl = (sale.lines ?? []).find((x) => x.id === l.sale_line_id) ?? env.refuse(422, 'validation_failed', 'That line is not on this sale.');
      const before = done(saleId, sl.id);
      const mine = taken.get(sl.id ?? '') ?? { qty: 0, total: 0 };
      const sold = milliOf(sl.qty ?? '0');
      const factor = l.unit_id && l.unit_id === sl.sale_unit_id ? milliOf(sl.unit_factor ?? '1.000') : 1000;
      const qty = Math.round((milliOf(l.qty) * factor) / 1000);
      const remaining = sold - before.qty - mine.qty;
      if (qty > remaining) env.refuse(422, 'return_exceeds_sold', `At most ${qtyString(remaining)} can still be returned.`);
      const charged = (sl.line_total_minor ?? 0) - before.total - mine.total;
      const total = qty === remaining ? charged : Math.min(share(sl.line_total_minor ?? 0, qty, sold), charged);
      taken.set(sl.id ?? '', { qty: mine.qty + qty, total: mine.total + total });
      return {
        id: env.nextId('retail-return-line'), line_no: i + 1, sale_line_id: sl.id, product_id: sl.product_id, code: sl.code, description: sl.description,
        qty: qtyString(qty), ...(l.unit_id && l.unit_id === sl.sale_unit_id ? { unit_id: l.unit_id, unit: sl.sale_unit, unit_qty: l.qty } : {}),
        line_total_minor: total, reason: l.reason, disposition: l.disposition, ...(l.disposition === 'restock' ? { restock_branch_id: l.restock_branch_id ?? sale.branch_id } : {}),
      };
    });
    const total = lines.reduce((sum, l) => sum + (l.line_total_minor ?? 0), 0);
    let account = 0;
    let money = total;
    if (total > 0 && body.refund_method === 'account') {
      const open = (sale.total_minor ?? 0) - (sale.paid_minor ?? 0);
      if (sale.payment_method !== 'credit' || open <= 0) env.refuse(422, 'account_refund_unavailable', 'There is no balance to take this off.');
      account = Math.min(total, open);
      money = total - account;
      if (money > 0 && !body.money_method) env.refuse(422, 'refund_remainder_method_required', 'Choose how the rest is paid out.');
    }
    const seq = (sequence.get(sale.branch_id ?? '') ?? 0) + 1;
    sequence.set(sale.branch_id ?? '', seq);
    if (account > 0) {
      sale.paid_minor = (sale.paid_minor ?? 0) + account;
      sale.balance_minor = (sale.total_minor ?? 0) - sale.paid_minor;
    }
    const made: SaleReturn = {
      id: env.nextId('retail-return'), credit_note_no: `CN${env.branchCode(sale.branch_id)}-${String(seq).padStart(6, '0')}`, sale_id: sale.id, sale_no: sale.sale_no,
      branch_id: sale.branch_id, ...(sale.customer_id ? { customer_id: sale.customer_id } : {}), returned_on: env.today(), currency: 'UGX', total_minor: total,
      account_credit_minor: account, refund_minor: money, ...(money > 0 ? { refund_method: body.refund_method === 'account' ? body.money_method : body.refund_method, refund_branch_id: body.refund_branch_id ?? sale.branch_id } : {}),
      refund_choice: body.refund_method, ...(body.note ? { note: body.note } : {}), status: 'completed', lines, created_at: new Date().toISOString(), created_by: 'mock-user', version: 1,
    };
    returns.push(made);
    return { executed: true, sale_return: made };
  };

  return {
    createSaleReturn: (saleId, body, key) => gate(() => env.once(key, { saleId, body }, () => record(saleId, body))),
    listSaleReturns: (saleId) => gate(() => returns.filter((r) => r.sale_id === saleId)),
    listReturns: ({ query, status }) =>
      gate(() => {
        const q = (query ?? '').trim().toLowerCase();
        const items = [...returns].reverse().filter((r) => (!status || r.status === status) && (q === '' || `${r.credit_note_no} ${r.sale_no} ${r.customer_name ?? ''}`.toLowerCase().includes(q)));
        return { items };
      }),
    getReturn: (id) => gate(() => returns.find((r) => r.id === id) ?? env.refuse(404, 'not_found', 'That could not be found.')),
    voidReturn: (id, reason, key) =>
      gate(() =>
        env.once(key, { id, reason }, (): ReturnOutcome => {
          const r = returns.find((x) => x.id === id) ?? env.refuse(404, 'not_found', 'That could not be found.');
          if (r.status === 'voided') env.refuse(409, 'return_voided', 'This return has been voided already.');
          if ((r.account_credit_minor ?? 0) > 0) {
            const sale = saleOf(r.sale_id ?? '');
            sale.paid_minor = (sale.paid_minor ?? 0) - (r.account_credit_minor ?? 0);
            sale.balance_minor = (sale.total_minor ?? 0) - sale.paid_minor;
          }
          Object.assign(r, { status: 'voided', voided_on: env.today(), voided_at: new Date().toISOString(), voided_by: 'mock-user', void_reason: reason, version: (r.version ?? 1) + 1 });
          return { executed: true, sale_return: r };
        }),
      ),
  };
}
