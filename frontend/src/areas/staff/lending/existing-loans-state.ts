import type { Me } from '../../../api/client';
import type { GoLiveDate, RecordExistingLoanRequest } from '../../../api/existing-loans';
import { isIsoDate, parseAmount } from './loan-state';

// Pure helpers of the existing-loans screens (issue #466, ADR-071): who may bring loans in, what the form becomes as a
// request (amounts typed in the currency's major units become minor units, ADR-004), the CSV template, and the go-live
// date in words. The server checks everything again; these only stop a form that cannot be right from being sent.

export const BRING_IN = 'lending.loans.bring_in';

/** Administrators and branch managers hold the permission (chapter 8 section 8.3.2). */
export function canBringIn(me: Pick<Me, 'permissions'> & { modules?: string[] }): boolean {
  if (me.modules && !me.modules.includes('lending')) return false;
  return (me.permissions ?? []).includes(BRING_IN);
}

export interface RepaymentDraft {
  date: string;
  amount: string;
  method: string;
  note: string;
}

export interface LoanDraft {
  memberId: string | null;
  productId: string;
  principal: string;
  disbursementDate: string;
  term: string;
  agreedTotal: string;
  registerRef: string;
  repayments: RepaymentDraft[];
}

export const emptyRepayment = (): RepaymentDraft => ({ date: '', amount: '', method: 'cash', note: '' });

export const emptyDraft = (): LoanDraft => ({
  memberId: null,
  productId: '',
  principal: '',
  disbursementDate: '',
  term: '',
  agreedTotal: '',
  registerRef: '',
  repayments: [],
});

/**
 * The request for a draft, or the problems by field (the server's field names) when it cannot be sent. Blank repayment rows
 * are left out; a row with some fields and not others is a problem.
 */
export function toRequest(d: LoanDraft, currency: string | undefined): { body?: RecordExistingLoanRequest; problems: Record<string, string> } {
  const problems: Record<string, string> = {};
  if (!d.memberId) problems.member_id = 'Choose the member.';
  if (!d.productId) problems.product_id = 'Choose the loan product.';
  const principal = parseAmount(d.principal, currency);
  if (principal === null) problems.principal_minor = 'Enter the amount lent.';
  if (!isIsoDate(d.disbursementDate)) problems.disbursement_date = 'Enter the date the money was given out.';
  let term: number | undefined;
  if (d.term.trim() !== '') {
    term = Number(d.term.trim());
    if (!Number.isInteger(term) || term < 1) problems.term_count = 'The term is a whole number, or empty for the product\'s default.';
  }
  let agreed: number | undefined;
  if (d.agreedTotal.trim() !== '') {
    agreed = parseAmount(d.agreedTotal, currency) ?? undefined;
    if (agreed === undefined) problems.agreed_total_minor = 'Enter the amount to repay, or leave it empty to use the product\'s rate.';
  }
  const repayments: RecordExistingLoanRequest['repayments'] = [];
  d.repayments.forEach((r) => {
    if (r.date.trim() === '' && r.amount.trim() === '' && r.note.trim() === '') return;
    const i = repayments.length;
    const amount = parseAmount(r.amount, currency);
    if (!isIsoDate(r.date)) problems[`repayments[${i}].value_date`] = 'Enter the date it was paid.';
    if (amount === null) problems[`repayments[${i}].amount_minor`] = 'Enter the amount paid.';
    repayments.push({ value_date: r.date, amount_minor: amount ?? 0, payment_method_key: r.method, note: r.note.trim() || undefined });
  });
  if (Object.keys(problems).length > 0) return { problems };
  return {
    problems,
    body: {
      member_id: d.memberId ?? '',
      product_id: d.productId,
      principal_minor: principal ?? 0,
      disbursement_date: d.disbursementDate,
      term_count: term,
      agreed_total_minor: agreed,
      register_ref: d.registerRef.trim() || undefined,
      repayments,
    },
  };
}

/** The go-live date and where it comes from, in plain words. */
export function goLiveWords(g: GoLiveDate | undefined): string {
  if (!g || !g.go_live_date) return 'The business has no go-live date yet: the Owner sets it in Business set-up.';
  const from = g.source === 'set' ? 'set by the Owner or the operator' : 'the day an administrator first signed in';
  return `The go-live date is ${g.go_live_date} (${from}). Only loans given out before it, and repayments made before it, come in here.`;
}

/** The columns of the import, in order. */
export const IMPORT_COLUMNS = [
  'kind',
  'ref',
  'member_no',
  'product_code',
  'principal',
  'disbursement_date',
  'term',
  'agreed_total',
  'purpose',
  'repayment_date',
  'amount',
  'method',
  'note',
];

/** The template to download: the header, one loan with two repayments and one loan with an agreed total. Fabricated. */
export const IMPORT_TEMPLATE = [
  IMPORT_COLUMNS.join(','),
  'loan,REG-001,M000001,LOAN1,1000000,2026-01-15,3,,business,,,,',
  'repayment,REG-001,,,,,,,,2026-02-15,400000,cash,Receipt 101',
  'repayment,REG-001,,,,,,,,2026-03-15,400000,mtn_momo,',
  'loan,REG-002,M000002,LOAN1,500000,2026-02-01,2,600000,,,,,',
  '',
].join('\n');
