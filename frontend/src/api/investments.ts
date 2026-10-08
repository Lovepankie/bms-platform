import { api, problemOf } from './client';
import { LendingError, lendingMessage } from './lending';
import type { RetailProblem } from './retail-errors';
import type { components } from './schema';

// The investments client for the staff screens (increment 10, chapter 7 section 7.11.16), typed by
// the generated schema like lending.ts. Money is integer minor units in the investment's currency.
// Every call that moves money carries the Idempotency-Key its form generated for the draft
// (chapter 7 section 7.8). Funding, early withdrawal and reversal answer 201 when executed and 202
// when they wait for a checker (`executed` says which).

type S = components['schemas'];

export type Investment = S['Investment'];
export type InvestmentPage = S['InvestmentPage'];
export type InvestmentProduct = S['InvestmentProduct'];
export type InvestmentProductTerms = S['InvestmentProductTerms'];
export type InvestmentSchedule = S['InvestmentSchedule'];
export type InvestmentStatement = S['InvestmentStatement'];
export type InvestmentStatementLine = S['InvestmentStatementLine'];
export type InvestmentTransaction = S['InvestmentTransaction'];
export type InvestmentCertificate = S['InvestmentCertificate'];
export type InvestmentMaturities = S['InvestmentMaturities'];
export type InvestmentMetrics = S['InvestmentMetrics'];
export type EarlyQuote = S['InvestmentEarlyWithdrawalQuote'];
export type ReturnPreview = S['InvestmentReturnPreview'];
export type ActionOutcome = S['InvestmentActionOutcome'];
export type TransactionResult = S['InvestmentTransactionResult'];
export type RolloverResult = S['InvestmentRolloverResult'];
export type MemberListItem = S['MemberListItem'];

// Investment refusals in plain words; anything else falls through to the lending and retail lists.
const BY_CODE: Record<string, string> = {
  invalid_status_transition: 'The investment is not in a state that allows this. Reload it to see its status.',
  term_not_offered: 'The product does not offer this term.',
  amount_out_of_range: 'The amount is outside the product\'s minimum and maximum.',
  product_archived: 'The product is archived. Choose another product.',
  member_not_active: 'The member is not active.',
  member_not_found: 'No such member in your branches.',
  no_return_due: 'No return is due for payout yet.',
  early_withdrawal_not_allowed: 'The product does not allow early withdrawal.',
  investment_matured: 'The investment has matured: pay it out or roll it over instead.',
  nothing_to_pay: 'The returns already paid are more than an early withdrawal leaves to pay.',
  not_reversible: 'Only a funding (before any return accrues), a return payout or a maturity payout can be reversed.',
  not_funded: 'The certificate is issued once the investment is funded.',
  value_date_out_of_range: 'The date must be between maturity and today.',
  duplicate_code: 'Another product already uses this code.',
  compounding_needs_maturity_payout: 'A compounding return is paid at maturity only.',
};

function message(problem: RetailProblem | undefined, status: number): string {
  const known = problem?.code ? BY_CODE[problem.code] : undefined;
  return known ?? lendingMessage(problem, status);
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    throw new LendingError(message(problem, result.response.status), result.response.status, problem.code);
  }
  return result.data;
}

function action(result: { data?: ActionOutcome; error?: unknown; response: Response }): { executed: boolean; outcome: ActionOutcome } {
  const outcome = unwrap(result);
  return { executed: outcome.executed ?? result.response.status === 201, outcome };
}

const key = (k: string) => ({ 'Idempotency-Key': k });
const path = (id: string) => ({ investment_id: id });

export const investments = {
  async list(q: { q?: string; status?: string; memberId?: string; branchIds?: string[]; cursor?: string }): Promise<InvestmentPage> {
    return unwrap(
      await api.GET('/api/v1/lending/investments', {
        params: {
          query: {
            q: q.q || undefined,
            status: q.status ? [q.status] : undefined,
            member_id: q.memberId || undefined,
            branch_id: q.branchIds,
            limit: 25,
            cursor: q.cursor,
          },
        },
      }),
    );
  },

  async get(id: string): Promise<Investment> {
    return unwrap(await api.GET('/api/v1/lending/investments/{investment_id}', { params: { path: path(id) } }));
  },

  async open(body: S['OpenInvestmentRequest']): Promise<Investment> {
    return unwrap(await api.POST('/api/v1/lending/investments', { body }));
  },

  async schedule(id: string): Promise<InvestmentSchedule> {
    return unwrap(await api.GET('/api/v1/lending/investments/{investment_id}/schedule', { params: { path: path(id) } }));
  },

  async statement(id: string): Promise<InvestmentStatement> {
    return unwrap(await api.GET('/api/v1/lending/investments/{investment_id}/statement', { params: { path: path(id) } }));
  },

  async certificate(id: string): Promise<InvestmentCertificate> {
    return unwrap(await api.GET('/api/v1/lending/investments/{investment_id}/certificate', { params: { path: path(id) } }));
  },

  async instruct(id: string, instruction: string): Promise<Investment> {
    return unwrap(
      await api.PUT('/api/v1/lending/investments/{investment_id}/maturity-instruction', { params: { path: path(id) }, body: { instruction } }),
    );
  },

  async fund(id: string, body: S['InvestmentFundingRequest'], idempotencyKey: string) {
    return action(
      await api.POST('/api/v1/lending/investments/{investment_id}/funding', { params: { path: path(id), header: key(idempotencyKey) }, body }),
    );
  },

  async payReturn(id: string, body: S['InvestmentPaymentRequest'], idempotencyKey: string): Promise<TransactionResult> {
    return unwrap(
      await api.POST('/api/v1/lending/investments/{investment_id}/return-payouts', { params: { path: path(id), header: key(idempotencyKey) }, body }),
    );
  },

  async payout(id: string, body: S['InvestmentPaymentRequest'], idempotencyKey: string): Promise<TransactionResult> {
    return unwrap(
      await api.POST('/api/v1/lending/investments/{investment_id}/payout', { params: { path: path(id), header: key(idempotencyKey) }, body }),
    );
  },

  async rollover(id: string, mode: 'rollover_principal' | 'rollover_all', idempotencyKey: string): Promise<RolloverResult> {
    return unwrap(
      await api.POST('/api/v1/lending/investments/{investment_id}/rollover', {
        params: { path: path(id), header: key(idempotencyKey) },
        body: { mode },
      }),
    );
  },

  async earlyQuote(id: string, valueDate?: string): Promise<EarlyQuote> {
    return unwrap(
      await api.GET('/api/v1/lending/investments/{investment_id}/early-withdrawal-quote', {
        params: { path: path(id), query: { value_date: valueDate } },
      }),
    );
  },

  async earlyWithdraw(id: string, body: S['InvestmentEarlyWithdrawalRequest'], idempotencyKey: string) {
    return action(
      await api.POST('/api/v1/lending/investments/{investment_id}/early-withdrawal', {
        params: { path: path(id), header: key(idempotencyKey) },
        body,
      }),
    );
  },

  async reverse(id: string, txnId: string, reason: string, idempotencyKey: string) {
    return action(
      await api.POST('/api/v1/lending/investments/{investment_id}/transactions/{txn_id}/reverse', {
        params: { path: { investment_id: id, txn_id: txnId }, header: key(idempotencyKey) },
        body: { reason },
      }),
    );
  },

  async maturities(branchIds?: string[]): Promise<InvestmentMaturities> {
    return unwrap(await api.GET('/api/v1/lending/investments/maturities', { params: { query: { days: 90, branch_id: branchIds } } }));
  },

  async metrics(branchIds?: string[]): Promise<InvestmentMetrics> {
    return unwrap(await api.GET('/api/v1/lending/investments/metrics', { params: { query: { branch_id: branchIds } } }));
  },

  async products(): Promise<InvestmentProduct[]> {
    const list = unwrap(await api.GET('/api/v1/lending/investment-products'));
    return list.items ?? [];
  },

  async createProduct(body: S['CreateInvestmentProductRequest']): Promise<InvestmentProduct> {
    return unwrap(await api.POST('/api/v1/lending/investment-products', { body }));
  },

  async updateProduct(id: string, version: number, body: S['UpdateInvestmentProductRequest']): Promise<InvestmentProduct> {
    return unwrap(
      await api.PUT('/api/v1/lending/investment-products/{product_id}', {
        params: { path: { product_id: id }, header: { 'If-Match': `"${version}"` } },
        body,
      }),
    );
  },

  async archiveProduct(id: string, version: number): Promise<InvestmentProduct> {
    return unwrap(
      await api.POST('/api/v1/lending/investment-products/{product_id}/archive', {
        params: { path: { product_id: id }, header: { 'If-Match': `"${version}"` } },
      }),
    );
  },

  async preview(body: S['InvestmentReturnPreviewRequest']): Promise<ReturnPreview> {
    return unwrap(await api.POST('/api/v1/lending/investment-products/return-preview', { body }));
  },

  async findMembers(q: string): Promise<MemberListItem[]> {
    const page = unwrap(await api.GET('/api/v1/lending/members', { params: { query: { q, limit: 10 } } }));
    return page.items ?? [];
  },
};
