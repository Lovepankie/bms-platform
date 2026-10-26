import { api } from './client';
import { guarded, unwrapForm } from './lending-forms';
import type { Loan, LoanListItem } from './lending';
import type { components } from './schema';

// The loan origination client for the staff application, appraisal and decision screens (issue #280 slices 3 and 4, chapter 7
// section 7.11.13), typed by the generated schema. Screens use only `ApplicationsApi`, so a test's in-memory stand-in can take its
// place through LendingApiContext. Every change to an application carries the loan's `version` as If-Match; failures are
// FormError with their field problems in plain words (lending-forms.ts).

type S = components['schemas'];

export type LoanProduct = S['LoanProduct'];
export type LoanAppraisal = S['LoanAppraisal'];
export type CreateLoanBody = S['CreateLoanRequest'];
export type UpdateLoanBody = S['UpdateLoanRequest'];
export type GuarantorInput = S['LoanGuarantorInput'];
export type PledgeInput = S['LoanPledgeInput'];
export type AppraisalBody = S['LoanAppraisalRequest'];
export type DecisionBody = S['LoanDecisionRequest'];
export type LoanStatusChange = S['LoanStatusChange'];

export interface ApplicationsApi {
  /** The loan products that take new applications. */
  products(): Promise<LoanProduct[]>;
  /** One product with every version, newest first (an archived one too): a draft is checked against the version it was made on. */
  product(id: string): Promise<LoanProduct>;
  get(id: string): Promise<Loan>;
  /** The member's drafts: what the screen looks at after a create whose answer was lost. */
  drafts(memberId: string): Promise<LoanListItem[]>;
  /** Every status move, oldest first: a send-back carries its note here. */
  history(id: string): Promise<LoanStatusChange[]>;
  create(body: CreateLoanBody): Promise<Loan>;
  update(id: string, version: number, body: UpdateLoanBody): Promise<Loan>;
  setGuarantors(id: string, version: number, guarantors: GuarantorInput[]): Promise<Loan>;
  setCollateral(id: string, version: number, collateral: PledgeInput[]): Promise<Loan>;
  submit(id: string, version: number): Promise<Loan>;
  appraise(id: string, version: number, body: AppraisalBody): Promise<LoanAppraisal>;
  /** Newest first. */
  appraisals(id: string): Promise<LoanAppraisal[]>;
  decide(id: string, version: number, body: DecisionBody): Promise<Loan>;
  /** Back to draft with a note for the officer. */
  sendBack(id: string, version: number, note: string): Promise<Loan>;
  cancel(id: string, version: number, note: string): Promise<Loan>;
}

const ifMatch = (version: number) => ({ 'If-Match': String(version) });
const loan = (id: string, version: number) => ({ path: { loan_id: id }, header: ifMatch(version) });

const rawApplications: ApplicationsApi = {
  async products() {
    return (unwrapForm(await api.GET('/api/v1/lending/loan-products')).items ?? []).filter((p) => p.status === 'active');
  },

  async product(id) {
    return unwrapForm(await api.GET('/api/v1/lending/loan-products/{product_id}', { params: { path: { product_id: id } } }));
  },

  async get(id) {
    return unwrapForm(await api.GET('/api/v1/lending/loans/{loan_id}', { params: { path: { loan_id: id } } }));
  },

  async drafts(memberId) {
    return unwrapForm(await api.GET('/api/v1/lending/loans', { params: { query: { member_id: memberId, status: ['draft'], limit: 25 } } })).items ?? [];
  },

  async history(id) {
    return unwrapForm(await api.GET('/api/v1/lending/loans/{loan_id}/status-history', { params: { path: { loan_id: id } } })).items ?? [];
  },

  async create(body) {
    return unwrapForm(await api.POST('/api/v1/lending/loans', { body }));
  },

  async update(id, version, body) {
    return unwrapForm(await api.PATCH('/api/v1/lending/loans/{loan_id}', { params: loan(id, version), body }));
  },

  async setGuarantors(id, version, guarantors) {
    return unwrapForm(await api.PUT('/api/v1/lending/loans/{loan_id}/guarantors', { params: loan(id, version), body: { guarantors } }));
  },

  async setCollateral(id, version, collateral) {
    return unwrapForm(await api.PUT('/api/v1/lending/loans/{loan_id}/collateral', { params: loan(id, version), body: { collateral } }));
  },

  async submit(id, version) {
    return unwrapForm(await api.POST('/api/v1/lending/loans/{loan_id}/submit', { params: loan(id, version) }));
  },

  async appraise(id, version, body) {
    return unwrapForm(await api.POST('/api/v1/lending/loans/{loan_id}/appraisals', { params: loan(id, version), body }));
  },

  async appraisals(id) {
    return unwrapForm(await api.GET('/api/v1/lending/loans/{loan_id}/appraisals', { params: { path: { loan_id: id } } })).items ?? [];
  },

  async decide(id, version, body) {
    return unwrapForm(await api.POST('/api/v1/lending/loans/{loan_id}/decision', { params: loan(id, version), body }));
  },

  async sendBack(id, version, note) {
    return unwrapForm(await api.POST('/api/v1/lending/loans/{loan_id}/return', { params: loan(id, version), body: { note } }));
  },

  async cancel(id, version, note) {
    return unwrapForm(await api.POST('/api/v1/lending/loans/{loan_id}/cancel', { params: loan(id, version), body: { note } }));
  },
};

export const realApplications: ApplicationsApi = guarded(rawApplications);
