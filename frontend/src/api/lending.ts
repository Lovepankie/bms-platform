import { api, problemOf } from './client';
import { retailMessage, type RetailProblem } from './retail-errors';
import type { components } from './schema';

// The loan servicing client for the staff lending screens (increment 5: disbursement and
// repayments, chapter 7 section 7.11.13), typed by the generated schema like retail.ts. Money is
// integer minor units in the loan's currency. Every money-moving call carries the Idempotency-Key
// the form generated for its draft (chapter 7 section 7.8). A disbursement, a reversal and a
// write-off answer 201 when executed and 202 when they wait for a checker (`executed` says which).

type S = components['schemas'];

export type LoanListItem = S['LoanListItem'];
export type LoanPage = S['LoanPage'];
export type Loan = S['Loan'];
export type LoanBalances = S['LoanBalances'];
export type LoanSchedule = S['LoanSchedule'];
export type LoanScheduleRow = S['LoanScheduleRow'];
export type LoanTransaction = S['LoanTransaction'];
export type LoanAllocation = S['LoanAllocation'];
export type RepaymentRequest = S['RepaymentRequest'];
export type RepaymentResult = S['RepaymentResult'];
export type DisbursementRequest = S['DisbursementRequest'];
export type LoanActionOutcome = S['LoanActionOutcome'];
export type PayoffQuote = S['PayoffQuote'];

/** The payment methods the server maps to an account (payment_method_key). */
export const PAYMENT_METHODS = [
  { value: 'cash', label: 'Cash' },
  { value: 'bank', label: 'Bank' },
  { value: 'mtn_momo', label: 'MTN Mobile Money' },
  { value: 'airtel_money', label: 'Airtel Money' },
] as const;

export type PaymentMethod = (typeof PAYMENT_METHODS)[number]['value'];

// Lending refusals in plain words. Codes shared with retail (permission, idempotency key reused or
// missing, validation fields, expired session) fall through to retailMessage, which also shows the
// server's own message for a code neither list knows.
const BY_CODE: Record<string, string> = {
  invalid_status_transition: 'The loan is not in a state that allows this. Reload the loan to see its status.',
  value_date_in_future: 'The date must be today or earlier.',
  before_disbursement: 'The date is before the loan was disbursed.',
  before_last_repayment: 'The date is before the latest repayment on this loan. Use that date or a later one.',
  payment_method_unmapped: 'This payment method is not set up for the business yet. Choose another, or ask an admin.',
  approval_already_pending: 'A request for this is already waiting for a checker in the approvals inbox.',
  already_reversed: 'This payment has already been reversed.',
  not_reversible: 'Only a repayment or a recovery can be reversed.',
  nothing_to_write_off: 'There is nothing outstanding to write off.',
  self_approval_forbidden: 'Someone else must approve this.',
  lending_chart_missing: 'The lending accounts are not set up in the ledger yet. Ask an admin.',
  idempotency_in_progress: 'This is still being saved. Wait a moment and reload the loan before trying again.',
  module_not_enabled: 'Lending is not switched on for this business.',
};

/** The message to show a person for a failed lending call. */
export function lendingMessage(problem: RetailProblem | undefined, status: number): string {
  const known = problem?.code ? BY_CODE[problem.code] : undefined;
  return known ?? retailMessage(problem, status);
}

/** A failed lending call: `message` is plain words for people, `code` is the server's identifier. */
export class LendingError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    throw new LendingError(lendingMessage(problem, result.response.status), result.response.status, problem.code);
  }
  return result.data;
}

/** True when the action ran now (201), false when it waits for a checker (202). */
function executed(outcome: LoanActionOutcome, status: number): boolean {
  return outcome.executed ?? status === 201;
}

const key = (k: string) => ({ 'Idempotency-Key': k });

export const lending = {
  async listLoans(q: { q?: string; status?: string; branchIds?: string[]; cursor?: string }): Promise<LoanPage> {
    return unwrap(
      await api.GET('/api/v1/lending/loans', {
        params: {
          query: { q: q.q || undefined, status: q.status ? [q.status] : undefined, branch_id: q.branchIds, limit: 25, cursor: q.cursor },
        },
      }),
    );
  },

  async getLoan(id: string): Promise<Loan> {
    return unwrap(await api.GET('/api/v1/lending/loans/{loan_id}', { params: { path: { loan_id: id } } }));
  },

  async getSchedule(id: string): Promise<LoanSchedule> {
    return unwrap(await api.GET('/api/v1/lending/loans/{loan_id}/schedule', { params: { path: { loan_id: id } } }));
  },

  async listTransactions(id: string): Promise<LoanTransaction[]> {
    const list = unwrap(await api.GET('/api/v1/lending/loans/{loan_id}/transactions', { params: { path: { loan_id: id } } }));
    return list.items ?? [];
  },

  async getMemberName(memberId: string): Promise<string | undefined> {
    const member = unwrap(await api.GET('/api/v1/lending/members/{member_id}', { params: { path: { member_id: memberId } } }));
    return member.full_name;
  },

  async repay(id: string, body: RepaymentRequest, idempotencyKey: string): Promise<RepaymentResult> {
    return unwrap(
      await api.POST('/api/v1/lending/loans/{loan_id}/repayments', { params: { path: { loan_id: id }, header: key(idempotencyKey) }, body }),
    );
  },

  async disburse(id: string, body: DisbursementRequest, idempotencyKey: string): Promise<{ executed: boolean; outcome: LoanActionOutcome }> {
    const result = await api.POST('/api/v1/lending/loans/{loan_id}/disbursements', {
      params: { path: { loan_id: id }, header: key(idempotencyKey) },
      body,
    });
    const outcome = unwrap(result);
    return { executed: executed(outcome, result.response.status), outcome };
  },

  async payoffQuote(id: string, valueDate: string): Promise<PayoffQuote> {
    return unwrap(
      await api.GET('/api/v1/lending/loans/{loan_id}/payoff-quote', { params: { path: { loan_id: id }, query: { value_date: valueDate } } }),
    );
  },

  async reverse(id: string, txnId: string, reason: string, idempotencyKey: string): Promise<{ executed: boolean; outcome: LoanActionOutcome }> {
    const result = await api.POST('/api/v1/lending/loans/{loan_id}/transactions/{txn_id}/reverse', {
      params: { path: { loan_id: id, txn_id: txnId }, header: key(idempotencyKey) },
      body: { reason },
    });
    const outcome = unwrap(result);
    return { executed: executed(outcome, result.response.status), outcome };
  },

  async writeOff(id: string, reason: string, idempotencyKey: string): Promise<{ executed: boolean; outcome: LoanActionOutcome }> {
    const result = await api.POST('/api/v1/lending/loans/{loan_id}/write-off', {
      params: { path: { loan_id: id }, header: key(idempotencyKey) },
      body: { reason },
    });
    const outcome = unwrap(result);
    return { executed: executed(outcome, result.response.status), outcome };
  },
};
