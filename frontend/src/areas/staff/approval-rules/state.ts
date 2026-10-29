import type { ApprovalRule, RuleChange } from '../../../api/approval-rules';
import { formatMinor } from '../../../components/money';

// The approval rules screen's state (issue #343, ADR-091): the draft of each action's switch and amount, what changed, and the
// review in plain words that is read before saving.

export type Entry = { on: boolean; amount: number | null };
export type Draft = Record<string, Entry>;

/**
 * What happens while each action waits, in one line, as the screen says it under the action. The words follow what the server
 * does: nothing changes until an administrator approves it.
 */
export const WHILE_IT_WAITS: Record<string, string> = {
  retail_sale_void: 'The sale stays as it is until an administrator approves the void.',
  retail_stock_correction: 'The quantity stays as it is until an administrator approves. Counted at the average buying price.',
  retail_expense: 'The expense is not in the cash book until an administrator approves it.',
  retail_sale_return: 'No money or goods move until a second administrator approves. Voiding such a return asks too.',
  retail_sale_write_off: 'With one administrator, a write-off goes through alone and the administrator is told.',
  retail_purchase_order: 'The order stays a draft until a second person approves. Deliveries without an order follow it.',
};

/** The line for an action this screen has no words for: the server's rule, plainly. */
export const GENERIC_WAIT = 'Nothing happens until a second person approves it.';

export function draftOf(rules: ApprovalRule[]): Draft {
  return Object.fromEntries(rules.map((r) => [r.action_type ?? '', { on: r.on === true, amount: r.has_amount ? (r.threshold_minor ?? 0) : null }]));
}

function changed(rule: ApprovalRule, entry: Entry | undefined): boolean {
  if (!entry) return false;
  return entry.on !== (rule.on === true) || (rule.has_amount === true && (entry.amount ?? 0) !== (rule.threshold_minor ?? 0));
}

/** The rules to send: only the actions that changed, each with its switch, and its amount when it has one. */
export function changesOf(rules: ApprovalRule[], draft: Draft): RuleChange[] {
  return rules
    .filter((r) => changed(r, draft[r.action_type ?? '']))
    .map((r) => {
      const entry = draft[r.action_type ?? ''] as Entry;
      return r.has_amount ? { action_type: r.action_type ?? '', on: entry.on, threshold_minor: entry.amount ?? 0 } : { action_type: r.action_type ?? '', on: entry.on };
    });
}

/**
 * What 0 means for an action, said under its amount. A credit write-off with no amount keeps ADR-043's rule: a second
 * administrator only while two or more administrators count, so a business with one writes off alone (and is told).
 */
export function zeroWords(actionType: string | undefined): string {
  return actionType === 'retail_sale_write_off' ? '0 means a second administrator when the business has two or more.' : '0 means every one.';
}

/** How a rule reads, as on or off, and from what amount. */
export function ruleWords(rule: Pick<ApprovalRule, 'has_amount' | 'action_type'>, entry: Entry, currency: string): string {
  if (!entry.on) return 'no second person';
  if (rule.action_type === 'retail_sale_write_off' && (entry.amount ?? 0) === 0) return 'a second administrator when the business has two or more';
  if (!rule.has_amount || (entry.amount ?? 0) === 0) return 'a second person for every one';
  return `a second person from ${formatMinor(entry.amount ?? 0, currency)}`;
}

/** One line per changed action for the review: what it was, and what it will be. */
export function reviewLines(rules: ApprovalRule[], draft: Draft, currency: string): string[] {
  return rules
    .filter((r) => changed(r, draft[r.action_type ?? '']))
    .map((r) => {
      const was = { on: r.on === true, amount: r.threshold_minor ?? 0 };
      const now = draft[r.action_type ?? ''] as Entry;
      return `${r.label}: ${ruleWords(r, now, currency)} (was ${ruleWords(r, was, currency)}).`;
    });
}

/** True when the change lets more happen without a second person: switched off, or a higher amount. The others are told. */
export function loosens(rules: ApprovalRule[], draft: Draft): boolean {
  return rules.some((r) => {
    const now = draft[r.action_type ?? ''];
    if (!now || r.on !== true) return false;
    return !now.on || (r.has_amount === true && (now.amount ?? 0) > (r.threshold_minor ?? 0));
  });
}

export const MODULE_TITLES: Record<string, string> = { retail: 'Shop', lending: 'Lending' };
