import type { CollateralItem } from '../../../api/collateral';
import type { FieldMessages } from '../../../api/lending-forms';
import type { Loan } from '../../../api/lending';
import type { CreateLoanBody, DecisionBody, GuarantorInput, LoanAppraisal, LoanProduct, PledgeInput, UpdateLoanBody } from '../../../api/loan-applications';
import { parseMinor } from '../../../components/money';
import { amountRule, amountText, currencyOf, money, words } from './loan-state';

// Pure rules of the loan application, appraisal and decision screens (issue #280 slices 3 and 4, FR-ORG-01 to FR-ORG-07): the
// application's draft and its checks against the product, the bodies sent, and plain words for the appraisal. Amounts are typed
// in the product's currency (the business's, issue #289) and sent as integer minor units (ADR-004). The server checks all of it
// again; these only say what is wrong before a round trip.

export const PURPOSES = [
  { value: 'business', label: 'Business' },
  { value: 'agriculture', label: 'Farming' },
  { value: 'school_fees', label: 'School fees' },
  { value: 'medical', label: 'Medical' },
  { value: 'household', label: 'Household' },
  { value: 'construction', label: 'Building' },
  { value: 'other', label: 'Other' },
] as const;

export const purposeWords = (value: string | undefined): string => PURPOSES.find((p) => p.value === value)?.label ?? words(value);

const UNITS: Record<string, [string, string]> = { day: ['day', 'days'], week: ['week', 'weeks'], month: ['month', 'months'] };

/** "1 month", "6 months". */
export function termWords(count: number | undefined, unit: string | undefined): string {
  const [one, many] = UNITS[unit ?? ''] ?? [unit ?? '', unit ?? ''];
  return `${count ?? 0} ${count === 1 ? one : many}`;
}

/** The terms of a product that the form checks against: its current version. */
export const termsOf = (p: LoanProduct | undefined) => p?.current_version;

/** "12% a month, flat" from basis points. */
export function rateWords(bp: number | undefined, unit: string | undefined, method: string | undefined): string {
  if (bp === undefined) return '';
  const whole = Math.trunc(bp / 100);
  const part = bp % 100;
  const percent = part === 0 ? `${whole}%` : `${whole}.${String(part).padStart(2, '0').replace(/0$/, '')}%`;
  const per: Record<string, string> = { per_term: 'for the whole loan', per_day: 'a day', per_week: 'a week', per_month: 'a month', per_year: 'a year' };
  return `${percent} ${per[unit ?? ''] ?? words(unit)}${method === 'declining' ? ', on the balance' : method === 'flat' ? ', flat' : ''}`;
}

export interface GuarantorDraft {
  memberId: string;
  name: string;
  no: string;
  amount: string;
  relationship: string;
}

export interface PledgeDraft {
  collateralId: string;
  /** What the item is, for the person reading the form. */
  label: string;
  /** The item's value for loan cover, in minor units. */
  value: number;
  amount: string;
}

export interface ApplicationDraft {
  memberId: string;
  productId: string;
  amount: string;
  term: string;
  purpose: string;
  purposeText: string;
  date: string;
  guarantors: GuarantorDraft[];
  pledges: PledgeDraft[];
}

export const blankApplication = (memberId = ''): ApplicationDraft => ({
  memberId, productId: '', amount: '', term: '', purpose: '', purposeText: '', date: '', guarantors: [], pledges: [],
});

/** A draft to change, from the loan as the server holds it. Guarantor names are not on the loan: the number stands in. */
export function draftOf(l: Loan): ApplicationDraft {
  const c = l.currency;
  return {
    memberId: l.member_id ?? '',
    productId: l.product_id ?? '',
    amount: l.requested_principal_minor != null ? amountText(l.requested_principal_minor, c) : '',
    term: l.requested_term_count != null ? String(l.requested_term_count) : '',
    purpose: l.purpose_category ?? '',
    purposeText: l.purpose_text ?? '',
    date: l.proposed_disbursement_date ?? '',
    guarantors: (l.guarantors ?? []).map((g) => ({
      memberId: g.member_id ?? '', name: `Member ${g.member_no ?? ''}`, no: g.member_no ?? '', amount: amountText(g.guaranteed_amount_minor ?? 0, c), relationship: g.relationship ?? '',
    })),
    pledges: (l.collateral ?? []).map((p) => ({
      collateralId: p.collateral_id ?? '', label: words(p.collateral_type), value: p.collateral_value_minor ?? 0, amount: amountText(p.pledged_value_minor ?? 0, c),
    })),
  };
}

/** A whole number above zero, or null. */
function count(text: string): number | null {
  const t = text.trim();
  return /^\d{1,6}$/.test(t) && Number(t) > 0 ? Number(t) : null;
}

const minor = (text: string, currency: string): number | null => {
  const m = parseMinor(text, currency);
  return m !== null && m > 0 ? m : null;
};

/** An item the borrower can pledge to a loan in `currency`: held for them, in that currency and valued (the server's rules). */
export function pledgeable(item: CollateralItem, currency: string): boolean {
  return ['pledged', 'in_custody'].includes(item.custody_status ?? '') && currencyOf(item.currency) === currency && item.collateral_value_minor != null;
}

/** Field keys the form ties its messages to, in the order the form shows them. Guarantor and pledge keys are the API's. */
export function applicationOrder(d: ApplicationDraft): string[] {
  return [
    'member_id', 'product_id', 'requested_principal_minor', 'requested_term_count', 'purpose_category', 'proposed_disbursement_date', 'guarantors',
    ...d.guarantors.flatMap((_, i) => [`guarantors[${i}].member_id`, `guarantors[${i}].guaranteed_amount_minor`]),
    'collateral',
    ...d.pledges.map((_, i) => `collateral[${i}].pledged_value_minor`),
  ];
}

/**
 * Per field, in plain words; empty when the application can be sent. `today` is the local date (YYYY-MM-DD). `currency` is the
 * one the form labels and sends (the loan's on a draft, else the product's, else the business's), so the checks never disagree
 * with the label.
 */
export function applicationProblems(d: ApplicationDraft, product: LoanProduct | undefined, today: string, currency: string): FieldMessages {
  const out: FieldMessages = {};
  const t = termsOf(product);
  const c = currencyOf(currency);
  if (d.memberId === '') out.member_id = 'Choose the member who is asking for the loan.';
  if (d.productId === '') out.product_id = 'Choose the loan product.';
  const amount = minor(d.amount, c);
  if (d.amount.trim() === '') out.requested_principal_minor = 'Type how much the member is asking for.';
  else if (amount === null) out.requested_principal_minor = `Type the amount ${amountRule(c)}.`;
  else if (t && ((t.min_principal_minor != null && amount < t.min_principal_minor) || (t.max_principal_minor != null && amount > t.max_principal_minor))) {
    out.requested_principal_minor = `This product lends from ${money(t.min_principal_minor, c)} to ${money(t.max_principal_minor, c)}.`;
  }
  if (d.term.trim() !== '') {
    const term = count(d.term);
    if (term === null) out.requested_term_count = 'Type a whole number, for example 6.';
    else if (t && ((t.min_term_count != null && term < t.min_term_count) || (t.max_term_count != null && term > t.max_term_count))) {
      out.requested_term_count = `This product allows from ${termWords(t.min_term_count, t.term_unit)} to ${termWords(t.max_term_count, t.term_unit)}.`;
    }
  }
  if (d.purpose === '') out.purpose_category = 'Choose what the loan is for.';
  if (d.date !== '' && d.date < today) out.proposed_disbursement_date = 'Choose today or a later day, or leave this empty.';
  if (t?.requires_guarantor && d.guarantors.length === 0) out.guarantors = 'This product needs at least one guarantor.';
  const seen = new Set<string>();
  d.guarantors.forEach((g, i) => {
    if (g.memberId === d.memberId) out[`guarantors[${i}].member_id`] = 'The member cannot guarantee their own loan.';
    else if (seen.has(g.memberId)) out[`guarantors[${i}].member_id`] = 'This guarantor is listed twice.';
    seen.add(g.memberId);
    if (minor(g.amount, c) === null) out[`guarantors[${i}].guaranteed_amount_minor`] = `Type how much they guarantee, ${amountRule(c)}.`;
  });
  if (t?.requires_collateral && d.pledges.length === 0) out.collateral = 'This product needs collateral. Choose at least one item.';
  d.pledges.forEach((p, i) => {
    const m = minor(p.amount, c);
    const key = `collateral[${i}].pledged_value_minor`;
    if (m === null) out[key] = `Type how much of the loan this item secures, ${amountRule(c)}.`;
    else if (m > p.value) out[key] = `The item is worth ${money(p.value, c)}. Type that or less.`;
  });
  return out;
}

const blank = (s: string): string | undefined => (s.trim() === '' ? undefined : s.trim());

/** The new draft. Call only when `applicationProblems` is empty. */
export function createBody(d: ApplicationDraft, currency: string): CreateLoanBody {
  return {
    member_id: d.memberId,
    product_id: d.productId,
    requested_principal_minor: minor(d.amount, currency) ?? 0,
    requested_term_count: count(d.term) ?? undefined,
    purpose_category: d.purpose,
    purpose_text: blank(d.purposeText),
    proposed_disbursement_date: blank(d.date),
  };
}

/** Only what changed on a draft, so an untouched field never overwrites someone else's change. */
export function updateBody(before: Loan, d: ApplicationDraft): UpdateLoanBody {
  const was = draftOf(before);
  const c = currencyOf(before.currency);
  const body: UpdateLoanBody = {};
  if (d.amount !== was.amount) body.requested_principal_minor = minor(d.amount, c) ?? undefined;
  if (d.term !== was.term && count(d.term) !== null) body.requested_term_count = count(d.term) ?? undefined;
  if (d.purpose !== was.purpose) body.purpose_category = d.purpose;
  if (d.purposeText !== was.purposeText) body.purpose_text = d.purposeText.trim();
  if (d.date !== was.date && d.date !== '') body.proposed_disbursement_date = d.date;
  return body;
}

export function guarantorsBody(d: ApplicationDraft, currency: string): GuarantorInput[] {
  return d.guarantors.map((g) => ({ member_id: g.memberId, guaranteed_amount_minor: minor(g.amount, currency) ?? 0, relationship: blank(g.relationship) }));
}

export function pledgesBody(d: ApplicationDraft, currency: string): PledgeInput[] {
  return d.pledges.map((p) => ({ collateral_id: p.collateralId, pledged_value_minor: minor(p.amount, currency) ?? 0 }));
}

/** True when the guarantors or pledges to send differ from what the loan holds now. */
export function partiesChanged(before: Loan | undefined, d: ApplicationDraft, currency: string): { guarantors: boolean; pledges: boolean } {
  const heldG = (before?.guarantors ?? []).map((g) => ({ member_id: g.member_id, guaranteed_amount_minor: g.guaranteed_amount_minor, relationship: g.relationship ?? undefined }));
  const heldP = (before?.collateral ?? []).map((p) => ({ collateral_id: p.collateral_id, pledged_value_minor: p.pledged_value_minor }));
  return {
    guarantors: JSON.stringify(heldG) !== JSON.stringify(guarantorsBody(d, currency)),
    pledges: JSON.stringify(heldP) !== JSON.stringify(pledgesBody(d, currency)),
  };
}

// --- Appraisal (FR-ORG-04, FR-ORG-05) -------------------------------------------------------------------

export interface AppraisalDraft {
  income: string;
  obligations: string;
  notes: string;
}

export const APPRAISAL_ORDER = ['declared_monthly_income_minor', 'monthly_obligations_minor', 'visit_notes'] as const;

export function appraisalProblems(d: AppraisalDraft, currency: string): FieldMessages {
  const out: FieldMessages = {};
  if (d.income.trim() !== '' && parseMinor(d.income, currency) === null) out.declared_monthly_income_minor = `Type the income ${amountRule(currency)}, or leave this empty.`;
  if (d.obligations.trim() !== '' && parseMinor(d.obligations, currency) === null) out.monthly_obligations_minor = `Type what the member pays each month ${amountRule(currency)}, or leave this empty.`;
  return out;
}

export function appraisalBody(d: AppraisalDraft, currency: string) {
  return {
    declared_monthly_income_minor: d.income.trim() === '' ? undefined : (parseMinor(d.income, currency) ?? undefined),
    monthly_obligations_minor: d.obligations.trim() === '' ? undefined : (parseMinor(d.obligations, currency) ?? undefined),
    visit_notes: blank(d.notes),
  };
}

const FLAG_WORDS: Record<string, string> = {
  INCOME_NOT_DECLARED: 'No income was given, so the loan could not be checked against what the member earns.',
  COLLATERAL_BELOW_PRODUCT_MINIMUM: 'The collateral covers less of the loan than the product needs.',
  EXISTING_LOAN_IN_ARREARS: 'The member has another loan that is behind on payments.',
  LINKED_PARTY_IN_ARREARS: 'A guarantor or a linked member has a loan more than 30 days behind.',
  MULTIPLE_ACTIVE_LOANS: 'The member already has a loan running.',
  NEW_MEMBER: 'The member joined less than 30 days ago.',
};

export const flagWords = (flag: string): string => FLAG_WORDS[flag] ?? words(flag.toLowerCase());

const RECOMMENDATION: Record<string, string> = { approve: 'Approve', review: 'Look closely', decline: 'Decline' };
export const recommendationWords = (r: string | undefined): string => RECOMMENDATION[r ?? ''] ?? words(r);

const COMPONENTS: Record<string, string> = {
  repayment_history: 'Past loans',
  affordability: 'Can afford it',
  collateral_cover: 'Collateral cover',
  exposure: 'Other loans',
};

/** The score's parts as "Past loans 20 of 30", in the server's order. */
export function scoreParts(a: LoanAppraisal): { label: string; points: number; max: number }[] {
  return Object.entries(a.components ?? {}).map(([key, v]) => {
    const part = v as { points?: number; max?: number };
    return { label: COMPONENTS[key] ?? words(key), points: Number(part.points ?? 0), max: Number(part.max ?? 0) };
  });
}

interface ExposureLoan {
  loan_no?: string;
  status?: string;
  outstanding_minor?: number;
  days_past_due?: number;
}

/** The other loans the appraisal looked at, by kind. */
export function exposureOf(a: LoanAppraisal): { label: string; loans: ExposureLoan[] }[] {
  const e = (a.exposure ?? {}) as Record<string, unknown>;
  const list = (key: string) => (Array.isArray(e[key]) ? (e[key] as ExposureLoan[]) : []);
  return [
    { label: 'The member\'s other loans', loans: list('own_loans') },
    { label: 'Loans the member guarantees', loans: list('guaranteed_loans') },
    { label: 'Loans of guarantors and linked members', loans: list('linked_party_loans') },
  ];
}

// --- Decision (FR-ORG-06, FR-APR-03) --------------------------------------------------------------------

export interface DecisionDraft {
  decision: '' | 'approve' | 'reject';
  amount: string;
  term: string;
  note: string;
}

export const DECISION_ORDER = ['decision', 'approved_principal_minor', 'approved_term_count', 'note'] as const;

export const blankDecision = (l: Loan): DecisionDraft => ({
  decision: '',
  amount: l.requested_principal_minor != null ? amountText(l.requested_principal_minor, l.currency) : '',
  term: l.requested_term_count != null ? String(l.requested_term_count) : '',
  note: '',
});

/** Why this user may not approve: maker-checker (FR-APR-03), the person who sent or appraised the application cannot. */
export function selfApproval(l: Loan, userId: string | undefined): string | null {
  if (!userId) return null;
  if (l.submitted_by === userId) return 'You sent this application, so someone else must approve it. You can still decline it or send it back.';
  if (l.appraised_by === userId) return 'You appraised this application, so someone else must approve it. You can still decline it or send it back.';
  return null;
}

export function decisionProblems(d: DecisionDraft, l: Loan): FieldMessages {
  const out: FieldMessages = {};
  const c = currencyOf(l.currency);
  if (d.decision === '') out.decision = 'Choose Approve or Decline.';
  if (d.decision === 'approve') {
    const amount = minor(d.amount, c);
    if (amount === null) out.approved_principal_minor = `Type the amount to lend ${amountRule(c)}.`;
    else if (amount > (l.requested_principal_minor ?? 0)) out.approved_principal_minor = `You can approve at most what was asked for, ${money(l.requested_principal_minor, c)}.`;
    const term = count(d.term);
    if (term === null) out.approved_term_count = 'Type a whole number, for example 6.';
    else if (term > (l.requested_term_count ?? 0)) out.approved_term_count = `You can approve at most what was asked for, ${termWords(l.requested_term_count, l.term_unit)}.`;
  }
  if (d.decision === 'reject' && d.note.trim() === '') out.note = 'Say why the application is declined.';
  return out;
}

export function decisionBody(d: DecisionDraft, l: Loan): DecisionBody {
  const c = currencyOf(l.currency);
  if (d.decision === 'reject') return { decision: 'reject', note: d.note.trim() };
  const amount = minor(d.amount, c) ?? undefined;
  const term = count(d.term) ?? undefined;
  return {
    decision: 'approve',
    approved_principal_minor: amount !== l.requested_principal_minor ? amount : undefined,
    approved_term_count: term !== l.requested_term_count ? term : undefined,
    note: blank(d.note),
  };
}
