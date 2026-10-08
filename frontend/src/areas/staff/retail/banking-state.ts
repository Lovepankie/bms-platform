import type { BankingExpected } from '../../../api/retail';
import { showDate } from './transfers';

// What the banking form prefills (FR-RET-21). A caller with retail.profit.read gets the net amount to bank
// (`expected_minor`, after cash restock cost and savings); anyone else gets `cash_expected_minor` only. The
// amount is that figure less what is already banked that day, never below zero. Nothing is computed from a
// figure the server left out.

/** The figure the form works from: the net expected amount when the caller may see it, else the cash expected. */
export function expectedToBank(e: BankingExpected | undefined): number | undefined {
  return e?.expected_minor ?? e?.cash_expected_minor;
}

/** The amount to prefill, in whole money units as text; blank until the figures arrive. */
export function bankingPrefill(e: BankingExpected | undefined): string {
  const expected = expectedToBank(e);
  if (expected === undefined) return '';
  return String(Math.max(0, expected - (e?.banked_so_far_minor ?? 0)));
}

/** "Already banked today" for today's date, else the chosen day by name, so a back-dated entry is not mislabelled. */
export function alreadyBankedLabel(date: string | undefined, today: string): string {
  return date === undefined || date === today ? 'Already banked today' : `Already banked on ${showDate(date)}`;
}
