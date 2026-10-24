import type { QueryClient, QueryKey } from '@tanstack/react-query';
import { RETAIL_CURRENCY, RetailError, type SavingsRequest, type SavingsSuggestion } from '../../../api/retail';
import { amountOrNull } from './cash-state';
import { showDate } from './maths';
import { formatMinor } from '../../../components/money';

// The savings form's rules (FR-RET-18, FR-RET-19, ADR-022 decisions 11 to 13, ADR-080), apart from the screen so
// they can be tested. A caller without retail.profit.read gets a suggestion with no `suggested_minor`:
// that caller sends no amount and no reason at all and the server records the standard amount. The day's
// available amount is its profit less its expenses, never below 0; nothing above it is sent, and the server
// refuses it too. The reason for changing the amount is optional (#538).

const money = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY);

/** The short pick list behind "Add a reason (optional)"; "Other" opens a text field. */
export const SAVINGS_REASONS = ['Paying a debt', 'Buying stock', 'Owner took money', 'Slow day', 'Other'] as const;
export type SavingsReason = (typeof SAVINGS_REASONS)[number];

/** The reason to send: the pick, or what was typed under Other; '' for none. */
export function reasonText(pick: string, other: string): string {
  if (pick === 'Other') return other.trim();
  return (SAVINGS_REASONS as readonly string[]).includes(pick) ? pick : '';
}

/** "You can save at most UGX X today (profit UGX P minus expenses UGX E)." The server says the same. */
export function capWords(s: SavingsSuggestion, date: string, today: string): string {
  const when = date === today ? 'today' : `for ${showDate(date)}`;
  return `You can save at most ${money(s.available_minor ?? 0)} ${when} (profit ${money(s.daily_profit_minor ?? 0)} minus expenses ${money(s.day_expenses_minor ?? 0)}).`;
}

/**
 * "Saved UGX X; profit less expenses is now UGX Y." for a day whose savings are above what it has now, after an expense
 * or a void entered later (ADR-080 decision 3); null otherwise, and always without profit read.
 */
export function nowAboveWords(s: SavingsSuggestion): string | null {
  const saved = s.existing_amount_minor;
  const available = s.available_minor;
  if (s.existing_id == null || saved === undefined || available === undefined || saved <= available) return null;
  return `Saved ${money(saved)}; profit less expenses is now ${money(available)}.`;
}

/** The toast after a save: "Savings of UGX 4,000 saved for 24 Oct". No amount for a caller who may not see it. */
export function savedWords(saved: { amount_minor?: number; business_date?: string }): string {
  const day = showDate(saved.business_date).split(' ').slice(0, 2).join(' ');
  return saved.amount_minor !== undefined ? `Savings of ${money(saved.amount_minor)} saved for ${day}` : `Savings saved for ${day}`;
}

export interface SavingsInput {
  branchId: string;
  date: string;
  suggestion: SavingsSuggestion;
  /** What the person typed; blank takes the suggestion. */
  amount: string;
  /** Optional: the pick, or the text typed under Other. */
  reason: string;
  /** True when the session holds retail.savings.overwrite. */
  canOverwrite: boolean;
  /** The business date today, so the cap can say "today". */
  today?: string;
}

/** True when the suggestion carries an amount, which only a caller with retail.profit.read gets. */
export const seesAmounts = (s: SavingsSuggestion | undefined): boolean => s?.suggested_minor !== undefined;

export interface SavingsPlan {
  /** What stops the save, in plain words, or null. */
  problem: string | null;
  /** True when the typed amount differs from the suggestion, so a reason may be given. */
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
  if (suggestion.available_minor !== undefined && typed > suggestion.available_minor) {
    return { problem: capWords(suggestion, input.date, input.today ?? input.date), overwrite: false, request };
  }
  if (typed === suggestion.suggested_minor) return { problem: null, overwrite: false, request: { ...request, amount_minor: typed } };
  if (!input.canOverwrite) {
    return { problem: 'Only an administrator can change the amount. Clear the amount to save the suggested one.', overwrite: true, request };
  }
  const reason = input.reason.trim();
  return { problem: null, overwrite: true, request: { ...request, amount_minor: typed, ...(reason !== '' ? { overwrite_reason: reason } : {}) } };
}

/** The new token (and suggestion) a 409 suggestion_changed carries, or null when the body has no token. */
export function changedSuggestion(problem: Record<string, unknown> | undefined): { token: string; suggested?: number } | null {
  const token = problem?.['suggestion_token'];
  if (typeof token !== 'string' || token === '') return null;
  const suggested = problem?.['suggested_minor'];
  return typeof suggested === 'number' ? { token, suggested } : { token };
}

/**
 * What the savings form does with a refusal of the save (ADR-022 decision 11), apart from the screen so it can
 * be tested. `suggestion_changed`: nothing was written, take the new token (and suggestion) the server sent and
 * ask the person to look again. `savings_exists`: another record won the day, so refetch the suggestion, which
 * now names it and blocks the form, instead of letting a retry loop on the same 409. Anything else is left to
 * the form's error line.
 */
export function afterSavingsConflict(client: QueryClient, suggestionKey: QueryKey, error: unknown): 'look_again' | 'refetched' | 'none' {
  if (!(error instanceof RetailError)) return 'none';
  if (error.code === 'savings_exists') {
    void client.invalidateQueries({ queryKey: suggestionKey });
    return 'refetched';
  }
  if (error.code !== 'suggestion_changed') return 'none';
  const next = changedSuggestion(error.problem);
  if (next) {
    client.setQueryData(suggestionKey, (old: SavingsSuggestion | undefined) => ({
      ...old, suggestion_token: next.token, ...(next.suggested !== undefined ? { suggested_minor: next.suggested } : {}),
    }));
  }
  void client.invalidateQueries({ queryKey: suggestionKey });
  return 'look_again';
}
