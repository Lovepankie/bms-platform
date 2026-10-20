import { api, problemOf } from './client';
import type { components } from './schema';

// The branches client (issue #329; ADR-041; chapter 7 section 7.11.4): list, close, reopen and the stocking switch. Every change
// carries the branch's version in If-Match. A close over stock or transfers on the road comes back as 422
// `branch_close_needs_reason` with the figures; the screen shows them and asks for a reason, then sends the same call again.

export type AdminBranch = components['schemas']['Branch'];

/** What a closing warning carries: the figures the server found on the branch. */
export type CloseFacts = {
  currency?: string;
  stockItems?: number;
  stockValueMinor?: number;
  transfersInFlight?: number;
};

export class BranchesError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly facts?: CloseFacts,
  ) {
    super(message);
  }
}

const MESSAGES: Record<string, string | undefined> = {
  branch_has_open_credit_sales: 'Credit sales made at this branch are still owing. Let the buyers pay, or settle them, before marking it as a store.',
  store_must_stock: 'A store holds stock, so it must keep goods. Switch it back to a shop first.',
  head_office_required: 'The head office cannot be closed. If it keeps no goods, switch off "Keeps goods" instead.',
  branch_has_open_accounts: 'This branch still has open loans, savings or investments. Close or move those first.',
  invalid_status_transition: 'This branch changed while you were looking. Reload the page to see how it is now.',
  version_conflict: 'Someone else changed this branch. Reload the page and try again.',
  plan_limit_reached: 'Your plan has no room for another open branch. Close one you no longer use, or ask us to change your plan.',
  permission_denied: 'You are not allowed to do this. Ask an administrator.',
};

export const NETWORK_WORDS = 'The answer did not reach your phone, so we cannot tell if it worked. Reload the page to see how the branch is now.';

function factsOf(problem: Record<string, unknown>): CloseFacts {
  const num = (k: string) => (typeof problem[k] === 'number' ? (problem[k] as number) : undefined);
  return {
    currency: typeof problem.currency === 'string' ? problem.currency : undefined,
    stockItems: num('stock_items'),
    stockValueMinor: num('stock_value_minor'),
    transfersInFlight: num('transfers_in_flight'),
  };
}

function fail(error: unknown, status: number, fallback: string): never {
  const problem = problemOf(error) as Record<string, unknown> & ReturnType<typeof problemOf>;
  const code = problem.code;
  const message = (code && MESSAGES[code]) || problem.detail || fallback;
  throw new BranchesError(message, status, code, factsOf(problem));
}

function unwrap<T>(result: { data?: T; error?: unknown; response: Response }, fallback: string): T {
  if (result.data === undefined || result.error !== undefined || !result.response.ok) fail(result.error, result.response.status, fallback);
  return result.data;
}

async function sent<T extends { response: Response }>(call: Promise<T>): Promise<T> {
  try {
    return await call;
  } catch (e) {
    if (e instanceof BranchesError) throw e;
    throw new BranchesError(NETWORK_WORDS, 0, 'network');
  }
}

const etag = (version: number | undefined) => `"${version ?? 0}"`;

export const branches = {
  /** Every branch in the caller's scope, open and closed, head office first. */
  async list(): Promise<AdminBranch[]> {
    return unwrap(await sent(api.GET('/api/v1/branches')), 'Could not load the branches.').items ?? [];
  },

  /** Closes a branch. Over stock or transfers on the road the server asks for a reason (code `branch_close_needs_reason`). */
  async close(branch: AdminBranch, reason?: string): Promise<AdminBranch> {
    return unwrap(
      await sent(
        api.POST('/api/v1/branches/{branch_id}/close', {
          params: { path: { branch_id: branch.id ?? '' }, header: { 'If-Match': etag(branch.version) } },
          body: reason ? { reason } : {},
        }),
      ),
      'Could not close this branch.',
    );
  },

  async reopen(branch: AdminBranch): Promise<AdminBranch> {
    return unwrap(
      await sent(
        api.POST('/api/v1/branches/{branch_id}/reopen', {
          params: { path: { branch_id: branch.id ?? '' }, header: { 'If-Match': etag(branch.version) } },
        }),
      ),
      'Could not reopen this branch.',
    );
  },

  /** Makes the branch a store (false: holds stock, takes purchases and transfers, does not sell) or a shop again (true). */
  async setSelling(branch: AdminBranch, sellsGoods: boolean): Promise<AdminBranch> {
    return unwrap(
      await sent(
        api.PATCH('/api/v1/branches/{branch_id}', {
          params: { path: { branch_id: branch.id ?? '' }, header: { 'If-Match': etag(branch.version) } },
          body: { sells_goods: sellsGoods },
        }),
      ),
      'Could not change this branch.',
    );
  },

  /** Switches whether the branch keeps goods; off is refused while it holds stock or a transfer is on the road. */
  async setStocking(branch: AdminBranch, stocksGoods: boolean): Promise<AdminBranch> {
    return unwrap(
      await sent(
        api.PATCH('/api/v1/branches/{branch_id}', {
          params: { path: { branch_id: branch.id ?? '' }, header: { 'If-Match': etag(branch.version) } },
          body: { stocks_goods: stocksGoods },
        }),
      ),
      'Could not change this branch.',
    );
  },
};
