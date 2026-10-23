import { api, problemOf } from './client';
import { LendingError, lendingMessage } from './lending';
import type { RetailProblem } from './retail-errors';
import type { components } from './schema';

// Existing loans brought in from a paper register (issue #466, ADR-071; chapter 7 section 7.11.13), typed by the generated
// schema. A check (dry run) saves nothing; recording needs the Idempotency-Key the form generated for its draft and leaves
// the loan waiting for a second person's approval. Money is integer minor units in the product's currency.

type S = components['schemas'];

export type ExistingLoan = S['ExistingLoan'];
export type ExistingLoanSummary = S['ExistingLoanSummary'];
export type ExistingLoanRepayment = S['ExistingLoanRepayment'];
export type RecordExistingLoanRequest = S['RecordExistingLoanRequest'];
export type ExistingLoanImportResult = S['ExistingLoanImportResult'];
export type GoLiveDate = S['GoLiveDate'];
export type LoanProduct = S['LoanProduct'];

// The refusals of this route in plain words; the rest fall through to the lending and retail lists.
const BY_CODE: Record<string, string> = {
  go_live_not_set:
    'The business has no go-live date yet. The Owner sets it in Business set-up, or it is the day an administrator first signs in.',
  disbursement_not_before_go_live: 'Only a loan given out before the go-live date can be brought in. Record a new loan the usual way.',
  repayment_not_before_go_live: 'A repayment on or after the go-live date is recorded as a normal repayment once the loan is approved.',
  repayments_exceed_balance: 'The repayments come to more than the loan owed. Check the amounts against the register.',
  register_ref_taken: 'A loan with this register number is already in the app.',
  agreed_total_below_principal: 'The amount to repay must be at least the amount lent.',
  outside_product_limits: 'The amount or the term is outside what this product allows.',
  member_not_active: 'Only an active member\'s loan can be brought in.',
  product_archived: 'This product is archived. Choose another.',
  go_live_changed: 'The go-live date changed since this loan was recorded. Ask for approval again.',
  historic_not_reversible: 'A repayment from the paper register cannot be reversed here.',
};

/** A refusal with its field problems: the key is the server's field name (`repayments[0].value_date`), the words its own. */
export class ExistingLoanError extends LendingError {
  constructor(
    message: string,
    status: number,
    code: string | undefined,
    readonly fields: Record<string, string>,
  ) {
    super(message, status, code);
  }
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    const known = problem.code ? BY_CODE[problem.code] : undefined;
    const fields: Record<string, string> = {};
    for (const e of (problem as RetailProblem).errors ?? []) {
      if (e.field && !fields[e.field]) fields[e.field] = (e.code && BY_CODE[e.code]) || e.message || 'Check this entry.';
    }
    throw new ExistingLoanError(known ?? lendingMessage(problem, result.response.status), result.response.status, problem.code, fields);
  }
  return result.data;
}

export const existingLoans = {
  async goLive(): Promise<GoLiveDate> {
    return unwrap(await api.GET('/api/v1/lending/existing-loans/go-live'));
  },

  async products(): Promise<LoanProduct[]> {
    return (unwrap(await api.GET('/api/v1/lending/loan-products')).items ?? []).filter((p) => p.status === 'active');
  },

  /** What the loan would be: nothing is saved. */
  async check(body: RecordExistingLoanRequest): Promise<ExistingLoan> {
    return unwrap(await api.POST('/api/v1/lending/existing-loans', { params: { query: { dry_run: true } }, body }));
  },

  /** Records the loan; it waits as brought_in for a second person's approval. */
  async record(body: RecordExistingLoanRequest, idempotencyKey: string): Promise<ExistingLoan> {
    return unwrap(
      await api.POST('/api/v1/lending/existing-loans', {
        params: { query: { dry_run: false }, header: { 'Idempotency-Key': idempotencyKey } },
        body,
      }),
    );
  },

  async list(status?: string): Promise<ExistingLoanSummary[]> {
    const page = unwrap(await api.GET('/api/v1/lending/existing-loans', { params: { query: { status: status ? [status] : undefined } } }));
    return page.items ?? [];
  },

  async get(id: string): Promise<ExistingLoan> {
    return unwrap(await api.GET('/api/v1/lending/existing-loans/{loan_id}', { params: { path: { loan_id: id } } }));
  },

  async cancel(id: string, reason: string): Promise<ExistingLoan> {
    return unwrap(await api.POST('/api/v1/lending/existing-loans/{loan_id}/cancel', { params: { path: { loan_id: id } }, body: { reason } }));
  },

  async askAgain(id: string): Promise<ExistingLoan> {
    return unwrap(await api.POST('/api/v1/lending/existing-loans/{loan_id}/approval', { params: { path: { loan_id: id } } }));
  },

  async importCsv(csv: string, dryRun: boolean): Promise<ExistingLoanImportResult> {
    return unwrap(await api.POST('/api/v1/lending/existing-loans/import', { params: { query: { dry_run: dryRun } }, body: { csv } }));
  },
};

export type ExistingLoansApi = typeof existingLoans;
