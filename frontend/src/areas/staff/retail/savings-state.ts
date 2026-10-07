import type { SavingsRequest, SavingsSuggestion } from '../../../api/retail';
import { amountOrNull } from './cash-state';

// The savings form's rules (FR-RET-18, FR-RET-19, ADR-022 decisions 11 to 13), apart from the screen so
// they can be tested. A caller without retail.profit.read gets a suggestion with no `suggested_minor`:
// that caller sends no amount and no reason at all and the server records the standard amount.

export const MIN_REASON = 5;

export interface SavingsInput {
  branchId: string;
  date: string;
  suggestion: SavingsSuggestion;
  /** What the person typed; blank takes the suggestion. */
  amount: string;
  reason: string;
  /** True when the session holds retail.savings.overwrite. */
  canOverwrite: boolean;
}

/** True when the suggestion carries an amount, which only a caller with retail.profit.read gets. */
export const seesAmounts = (s: SavingsSuggestion | undefined): boolean => s?.suggested_minor !== undefined;

export interface SavingsPlan {
  /** What stops the save, in plain words, or null. */
  problem: string | null;
  /** True when the typed amount differs from the suggestion, so a reason is needed. */
  overwrite: boolean;
  request: SavingsRequest;
}

/** What to send, and what (if anything) blocks it. The token is always echoed. */
export function planSavings(input: SavingsInput): SavingsPlan {
  const { suggestion } = input;
  const request: SavingsRequest = { branch_id: input.branchId, business_date: input.date, suggestion_token: suggestion.suggestion_token };
  if (!seesAmounts(suggestion)) return { problem: null, overwrite: false, request };
  if (input.amount.trim() === '') return { problem: null, overwrite: false, request };
  const typed = input.amount.trim() === '0' ? 0 : amountOrNull(input.amount);
  if (typed === null) return { problem: 'Enter the amount in whole shillings, for example 20000.', overwrite: false, request };
  if (typed === suggestion.suggested_minor) return { problem: null, overwrite: false, request: { ...request, amount_minor: typed } };
  if (!input.canOverwrite) {
    return { problem: 'Only an administrator can change the amount. Clear the amount to save the suggested one.', overwrite: true, request };
  }
  if (input.reason.trim().length < MIN_REASON) {
    return { problem: `Give a reason of at least ${MIN_REASON} characters for changing the amount.`, overwrite: true, request };
  }
  return { problem: null, overwrite: true, request: { ...request, amount_minor: typed, overwrite_reason: input.reason.trim() } };
}

/** The new token (and suggestion) a 409 suggestion_changed carries, or null when the body has no token. */
export function changedSuggestion(problem: Record<string, unknown> | undefined): { token: string; suggested?: number } | null {
  const token = problem?.['suggestion_token'];
  if (typeof token !== 'string' || token === '') return null;
  const suggested = problem?.['suggested_minor'];
  return typeof suggested === 'number' ? { token, suggested } : { token };
}
