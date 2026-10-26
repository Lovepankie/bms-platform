import type { LoanScheduleRow } from '../../../api/lending';
import { exponentOf } from '../../../components/money';
import { amountRule, currencyOf, money, parseAmount } from './loan-state';

// The checks of the Waive fee or interest form (FR-ARR-04, issue #265), apart from the screen so they are tested alone. The
// server checks them again: at most what is still due on the item's fees or interest, never principal, and a reason.

export type WaiveComponent = 'interest' | 'fee';

export interface WaiveDraft {
  component: WaiveComponent;
  amount: string;
  reason: string;
}

export interface WaiveStep {
  /** What is still due on the chosen component: the most the waiver may take. */
  dueMinor: number;
  amountMinor: number | null;
  amountProblem: string | null;
  reasonProblem: string | null;
  ready: boolean;
  /** The currency is typed with a decimal point (not UGX). */
  decimals: boolean;
}

/** Whether a schedule row has any fee or interest left to waive. */
export const waivable = (row: LoanScheduleRow): boolean => (row.interest_outstanding_minor ?? 0) + (row.fees_outstanding_minor ?? 0) > 0;

export function waiveStep(draft: WaiveDraft, row: LoanScheduleRow, currency: string | undefined): WaiveStep {
  const dueMinor = (draft.component === 'fee' ? row.fees_outstanding_minor : row.interest_outstanding_minor) ?? 0;
  const amountMinor = parseAmount(draft.amount, currency);
  const what = draft.component === 'fee' ? 'fees' : 'interest';
  const is = draft.component === 'fee' ? 'are' : 'is';
  let amountProblem: string | null = null;
  if (dueMinor <= 0) amountProblem = `No ${what} ${is} still due on this item.`;
  else if (draft.amount.trim() === '') amountProblem = 'Enter the amount to waive.';
  else if (amountMinor === null) amountProblem = `Enter an amount above zero, ${amountRule(currency)}.`;
  else if (amountMinor > dueMinor) amountProblem = `At most ${money(dueMinor, currency)} of the ${what} ${is} still due.`;
  const reasonProblem = draft.reason.trim() === '' ? 'Say why it is waived.' : null;
  return {
    dueMinor,
    amountMinor,
    amountProblem,
    reasonProblem,
    ready: amountProblem === null && reasonProblem === null,
    decimals: (exponentOf(currencyOf(currency)) ?? 0) > 0,
  };
}
