import { api, problemOf } from './client';
import { lendingMessage, LendingError } from './lending';
import type { components } from './schema';

// The savings client for the staff savings screens (increment 9, chapter 7 section 7.11.15),
// typed by the generated schema like lending.ts. Money is integer minor units in the account's
// currency. Every money-moving call carries the Idempotency-Key its form generated for the draft
// (chapter 7 section 7.8). A withdrawal, a closure and a reversal answer 201 when executed and 202
// when they wait for a checker (`executed` says which).

type S = components['schemas'];

export type SavingsProduct = S['SavingsProduct'];
export type SavingsProductTerms = S['SavingsProductTerms'];
export type SavingsAccount = S['SavingsAccount'];
export type SavingsAccountPage = S['SavingsAccountPage'];
export type SavingsTransaction = S['SavingsTransaction'];
export type SavingsDepositResult = S['SavingsDepositResult'];
export type SavingsActionOutcome = S['SavingsActionOutcome'];
export type SavingsStatement = S['SavingsStatement'];

// Savings refusals in plain words; anything else falls through to the lending and retail lists.
const BY_CODE: Record<string, string> = {
  below_minimum_opening: 'The first deposit must be at least the product\'s minimum opening balance.',
  insufficient_balance: 'The account cannot pay out that much: the balance must keep its minimum and cover the fee.',
  withdrawal_limit_exceeded: 'This is more than one withdrawal may take on this product.',
  withdrawal_count_exceeded: 'The product allows no more withdrawals this month.',
  account_dormant: 'The account is dormant. A branch manager must reactivate it before money can be taken out.',
  account_frozen: 'The account is frozen. Nothing can be taken out until it is unfrozen.',
  account_closed: 'The account is closed.',
  account_on_hold: 'Part of the balance is on hold, so the account cannot be closed.',
  value_date_closed: 'That day is already closed for savings. Use today\'s date.',
  before_opening: 'The date is before the account was opened.',
  product_in_use: 'Accounts already use this product, so its interest terms cannot change. Create a new product instead.',
  product_not_active: 'This product is archived. Choose an active product.',
  member_not_active: 'Only an active member can open a savings account.',
  duplicate_code: 'A savings product with this code already exists.',
  not_reversible: 'Only a deposit or a withdrawal can be reversed.',
  already_reversed: 'This transaction has already been reversed.',
  version_conflict: 'Someone changed this product since you opened it. Reload it and try again.',
};

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) {
    const problem = problemOf(result.error);
    const known = problem.code ? BY_CODE[problem.code] : undefined;
    throw new LendingError(known ?? lendingMessage(problem, result.response.status), result.response.status, problem.code);
  }
  return result.data;
}

/** The plain-words message for a savings refusal code, for tests and inline hints. */
export const savingsMessage = (code: string): string | undefined => BY_CODE[code];

const key = (k: string) => ({ 'Idempotency-Key': k });

async function action(
  call: Promise<{ data?: SavingsActionOutcome; error?: unknown; response: Response }>,
): Promise<{ executed: boolean; outcome: SavingsActionOutcome }> {
  const result = await call;
  const outcome = unwrap(result);
  return { executed: outcome.executed ?? result.response.status === 201, outcome };
}

export const savings = {
  async listProducts(): Promise<SavingsProduct[]> {
    return unwrap(await api.GET('/api/v1/lending/savings-products')).items ?? [];
  },

  async createProduct(code: string, terms: SavingsProductTerms): Promise<SavingsProduct> {
    return unwrap(await api.POST('/api/v1/lending/savings-products', { body: { code, terms } }));
  },

  async updateProduct(id: string, version: number, terms: SavingsProductTerms, status: 'active' | 'archived'): Promise<SavingsProduct> {
    return unwrap(
      await api.PUT('/api/v1/lending/savings-products/{product_id}', {
        params: { path: { product_id: id }, header: { 'If-Match': String(version) } },
        body: { terms, status },
      }),
    );
  },

  async listAccounts(q: { q?: string; status?: string; memberId?: string; branchIds?: string[]; cursor?: string }): Promise<SavingsAccountPage> {
    return unwrap(
      await api.GET('/api/v1/lending/savings-accounts', {
        params: {
          query: {
            q: q.q || undefined,
            status: q.status ? [q.status] : undefined,
            member_id: q.memberId,
            branch_id: q.branchIds,
            limit: 25,
            cursor: q.cursor,
          },
        },
      }),
    );
  },

  async open(memberId: string, productId: string): Promise<SavingsAccount> {
    return unwrap(await api.POST('/api/v1/lending/savings-accounts', { body: { member_id: memberId, product_id: productId } }));
  },

  async getAccount(id: string): Promise<SavingsAccount> {
    return unwrap(await api.GET('/api/v1/lending/savings-accounts/{account_id}', { params: { path: { account_id: id } } }));
  },

  async listTransactions(id: string, cursor?: string): Promise<{ items: SavingsTransaction[]; next?: string }> {
    const page = unwrap(
      await api.GET('/api/v1/lending/savings-accounts/{account_id}/transactions', {
        params: { path: { account_id: id }, query: { limit: 50, cursor } },
      }),
    );
    return { items: page.items ?? [], next: page.next_cursor || undefined };
  },

  async statement(id: string, from: string, to: string): Promise<SavingsStatement> {
    return unwrap(
      await api.GET('/api/v1/lending/savings-accounts/{account_id}/statement', {
        params: { path: { account_id: id }, query: { from, to } },
      }),
    );
  },

  async deposit(id: string, body: { amount_minor: number; payment_method_key: string; external_reference?: string }, idempotencyKey: string): Promise<SavingsDepositResult> {
    return unwrap(
      await api.POST('/api/v1/lending/savings-accounts/{account_id}/deposits', {
        params: { path: { account_id: id }, header: key(idempotencyKey) },
        body,
      }),
    );
  },

  async withdraw(id: string, body: { amount_minor: number; payment_method_key: string; external_reference?: string }, idempotencyKey: string) {
    return action(
      api.POST('/api/v1/lending/savings-accounts/{account_id}/withdrawals', {
        params: { path: { account_id: id }, header: key(idempotencyKey) },
        body,
      }),
    );
  },

  async close(id: string, body: { payment_method_key: string; reason?: string }, idempotencyKey: string) {
    return action(
      api.POST('/api/v1/lending/savings-accounts/{account_id}/close', {
        params: { path: { account_id: id }, header: key(idempotencyKey) },
        body,
      }),
    );
  },

  async reverse(id: string, txnId: string, reason: string, idempotencyKey: string) {
    return action(
      api.POST('/api/v1/lending/savings-accounts/{account_id}/transactions/{txn_id}/reverse', {
        params: { path: { account_id: id, txn_id: txnId }, header: key(idempotencyKey) },
        body: { reason },
      }),
    );
  },

  async setStatus(id: string, change: 'freeze' | 'unfreeze' | 'reactivate', reason: string): Promise<SavingsAccount> {
    const params = { params: { path: { account_id: id } }, body: { reason } };
    switch (change) {
      case 'freeze':
        return unwrap(await api.POST('/api/v1/lending/savings-accounts/{account_id}/freeze', params));
      case 'unfreeze':
        return unwrap(await api.POST('/api/v1/lending/savings-accounts/{account_id}/unfreeze', params));
      default:
        return unwrap(await api.POST('/api/v1/lending/savings-accounts/{account_id}/reactivate', params));
    }
  },
};
