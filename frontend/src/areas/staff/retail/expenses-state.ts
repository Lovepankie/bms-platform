import type { ExpenseCategory, ExpenseItem, ExpenseRequest } from '../../../api/retail';
import { amountOrNull } from './cash-state';

// The expense form's rules (FR-RET-17, FR-RET-24): the items offered depend on the chosen category and only
// active ones are offered, and an item flagged "needs an explanation" makes the explanation field appear and
// be required. The lists are tenant data; none is hard coded.

export const MIN_EXPLANATION = 3;

/** The categories a new expense may use: the active ones. */
export const activeCategories = (categories: ExpenseCategory[]): ExpenseCategory[] => categories.filter((c) => c.active !== false);

/** The items of the chosen category only, active ones only; none until a category is chosen. */
export function itemsOf(categories: ExpenseCategory[], categoryId: string): ExpenseItem[] {
  const category = categories.find((c) => c.id === categoryId);
  return (category?.items ?? []).filter((i) => i.active !== false);
}

/** True when the chosen item is flagged "needs an explanation". */
export function needsExplanation(categories: ExpenseCategory[], categoryId: string, itemId: string): boolean {
  return itemsOf(categories, categoryId).find((i) => i.id === itemId)?.requires_explanation === true;
}

export interface ExpenseInput {
  branchId: string;
  date: string;
  categoryId: string;
  itemId: string;
  partyId: string;
  amount: string;
  explanation: string;
}

export interface ExpensePlan {
  problem: string | null;
  request: ExpenseRequest;
}

/** What to send, and what (if anything) blocks it. The explanation is sent only for an item that needs one. */
export function planExpense(input: ExpenseInput, categories: ExpenseCategory[]): ExpensePlan {
  const minor = amountOrNull(input.amount);
  const explain = needsExplanation(categories, input.categoryId, input.itemId);
  const request: ExpenseRequest = {
    branch_id: input.branchId,
    business_date: input.date,
    category_id: input.categoryId,
    item_id: input.itemId,
    amount_minor: minor ?? 0,
    ...(input.partyId ? { party_id: input.partyId } : {}),
    ...(explain ? { explanation: input.explanation.trim() } : {}),
  };
  if (!input.categoryId) return { problem: 'Choose a category.', request };
  if (!input.itemId || !itemsOf(categories, input.categoryId).some((i) => i.id === input.itemId)) return { problem: 'Choose an item.', request };
  if (minor === null) return { problem: 'Enter the amount in whole shillings.', request };
  if (explain && input.explanation.trim().length < MIN_EXPLANATION) return { problem: `Explain this expense in at least ${MIN_EXPLANATION} characters.`, request };
  return { problem: null, request };
}
