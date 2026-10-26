// @vitest-environment jsdom
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import type { CollateralItem } from '../../../api/collateral';
import type { Me } from '../../../api/client';
import type { Loan } from '../../../api/lending';
import { LendingApiContext } from '../../../api/lending-context';
import { FormError } from '../../../api/lending-forms';
import type { ApplicationsApi, LoanAppraisal, LoanProduct } from '../../../api/loan-applications';
import { createCollateralMock, createMembersMock } from '../../../api/members-mock';
import type { MemberRecord } from '../../../api/members';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { ApplicationForm, EditApplicationBody } from './application-form';
import { AppraiseBody, AppraiseForm } from './appraise';
import { DecideBody, DecisionForm } from './decide';
import { LoanView } from './loan';
import { Loans } from './loans';

// The loan application, appraisal and decision screens as a person uses them (issue #280 slices 3 and 4, FR-ORG-01 to FR-ORG-07,
// FR-APR-03): what each needs, the words under a field, what each role is offered, and exactly what is sent. Every name, number and
// amount is fabricated.

afterEach(cleanup);

const BRANCH = '00000000-0000-4000-8000-0000000000c1';
const OTHER = '00000000-0000-4000-8000-0000000000c2';
const ME_ID = '00000000-0000-4000-8000-00000000aa01';
const COLLEAGUE = '00000000-0000-4000-8000-00000000aa02';

const staff = (permissions: string[], scope?: Me['permission_scopes']): Me => ({
  user_id: ME_ID, full_name: 'Test Officer 01', kind: 'staff', all_branches: false, modules: ['lending'],
  permissions: ['lending.loans.read', 'lending.members.read', ...permissions], permission_scopes: scope,
  branches: [{ id: BRANCH, code: 'BR1', name: 'Test Branch A' }, { id: OTHER, code: 'BR2', name: 'Test Branch B' }],
});
const OFFICER = staff(['lending.loans.create', 'lending.collateral.read', 'lending.collateral.manage']);
const APPRAISER = staff(['lending.loans.appraise']);
const MANAGER = staff(['lending.loans.approve', 'lending.loans.cancel']);
const READER = staff([]);

const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/lending'] }) });

const borrower: MemberRecord = { id: '00000000-0000-4000-8000-000000000201', branch_id: BRANCH, member_no: 'M000201', full_name: 'Test Borrower 01', phone_e164: '+256700000021', kyc_status: 'verified', status: 'active', monthly_income_minor: 800_000, currency: 'UGX', version: 1 };
const guarantor: MemberRecord = { id: '00000000-0000-4000-8000-000000000202', branch_id: BRANCH, member_no: 'M000202', full_name: 'Test Guarantor 01', phone_e164: '+256700000022', kyc_status: 'verified', status: 'active', version: 1 };

const product: LoanProduct = {
  id: 'p1', code: 'TEST-MONTHLY', name: 'Test monthly loan', status: 'active',
  current_version: { currency: 'UGX', min_principal_minor: 100_000, max_principal_minor: 5_000_000, min_term_count: 1, max_term_count: 12, default_term_count: 6, term_unit: 'month', interest_rate_bp: 300, rate_unit: 'per_month', interest_method: 'flat', requires_guarantor: false, requires_collateral: false },
};
const strict: LoanProduct = { ...product, id: 'p2', code: 'TEST-SECURED', name: 'Test secured loan', current_version: { ...product.current_version, requires_guarantor: true, requires_collateral: true } };

const plot: CollateralItem = { id: 'c1', branch_id: BRANCH, member_id: borrower.id, collateral_type: 'land_title', description: 'Test plot', custody_status: 'pledged', currency: 'UGX', collateral_value_minor: 3_000_000, version: 1 };
const unvalued: CollateralItem = { id: 'c2', branch_id: BRANCH, member_id: borrower.id, collateral_type: 'household_item', description: 'Test fridge', custody_status: 'pledged', currency: 'UGX', version: 1 };

const loan = (status: string, over: Partial<Loan> = {}): Loan => ({
  id: 'l1', loan_no: 'LN000001', branch_id: BRANCH, member_id: borrower.id, member_no: 'M000201', product_id: 'p1', product_code: 'TEST-MONTHLY', status, currency: 'UGX',
  requested_principal_minor: 1_000_000, requested_term_count: 6, term_unit: 'month', interest_rate_bp: 300, rate_unit: 'per_month', interest_method: 'flat',
  purpose_category: 'business', guarantors: [], collateral: [], provisional_schedule: [{ no: 1, due_date: '2026-12-01', principal_minor: 166_667, interest_minor: 30_000, fee_minor: 0, total_minor: 196_667 }],
  created_by: ME_ID, version: 3, ...over,
});

const appraisal: LoanAppraisal = {
  id: 'a1', loan_id: 'l1', score: 68, band: 'B', recommendation: 'approve', flags: ['NEW_MEMBER'],
  components: { repayment_history: { points: 15, max: 30 } as never, affordability: { points: 28, max: 30 } as never },
  exposure: { own_loans: [] as never, guaranteed_loans: [] as never, linked_party_loans: [] as never }, declared_monthly_income_minor: 800_000, monthly_obligations_minor: 0, created_at: '2026-10-20T08:00:00Z',
};

/** An in-memory application client that records each call, the server's version rule included. */
function createApplicationsMock(start?: Loan, tightened = false) {
  let current: Loan | undefined = start;
  const calls: { name: string; version?: number; body?: unknown }[] = [];
  let failOnce: { name: string; error: FormError } | null = null;
  // The call goes through on the server but its answer never arrives.
  let loseOnce: string | null = null;
  const step = (name: string, version: number | undefined, body: unknown, apply: (l: Loan) => Loan): Loan => {
    calls.push({ name, version, body });
    if (failOnce?.name === name) {
      const e = failOnce.error;
      failOnce = null;
      throw e;
    }
    if (!current) throw new FormError('Not found.', 404, 'not_found');
    if (version !== current.version) throw new FormError('Changed.', 409, 'version_conflict');
    current = apply({ ...current, version: (current.version ?? 0) + 1 });
    if (loseOnce === name) {
      loseOnce = null;
      throw new FormError('The connection dropped before an answer came back.', 0, 'network');
    }
    return { ...current };
  };
  const api: ApplicationsApi = {
    async products() { return tightened ? [{ ...product, current_version: { ...product.current_version, id: 'v2', max_principal_minor: 500_000 } }, strict] : [product, strict]; },
    // The product was tightened after the draft: v2 lends at most 500,000; the draft was made on v1, which lends up to 5,000,000.
    async product(id) {
      const v1 = { ...product.current_version, id: 'v1' };
      return { ...(id === 'p2' ? strict : product), current_version: { ...v1, id: 'v2', max_principal_minor: 500_000 }, versions: [{ ...v1, id: 'v2', max_principal_minor: 500_000 }, v1] };
    },
    async get() { if (!current) throw new FormError('Not found.', 404, 'not_found'); return { ...current }; },
    async drafts() { return []; },
    async history() { return []; },
    async create(body) {
      calls.push({ name: 'create', body });
      if (failOnce?.name === 'create') { const e = failOnce.error; failOnce = null; throw e; }
      current = loan('draft', { requested_principal_minor: body.requested_principal_minor, requested_term_count: body.requested_term_count ?? 6, purpose_category: body.purpose_category, version: 1 });
      return { ...current };
    },
    async update(_id, version, body) { return step('update', version, body, (l) => ({ ...l, ...body })); },
    async setGuarantors(_id, version, guarantors) { return step('guarantors', version, guarantors, (l) => ({ ...l, guarantors: guarantors.map((g) => ({ ...g, member_no: 'M000202' })) })); },
    async setCollateral(_id, version, collateral) { return step('collateral', version, collateral, (l) => ({ ...l, collateral: collateral.map((p) => ({ ...p, collateral_type: 'land_title', collateral_value_minor: 3_000_000 })) })); },
    async submit(_id, version) { return step('submit', version, undefined, (l) => ({ ...l, status: 'submitted', submitted_by: ME_ID })); },
    async appraise(_id, version, body) { step('appraise', version, body, (l) => ({ ...l, status: 'appraised' })); return appraisal; },
    async appraisals() { return [appraisal]; },
    async decide(_id, version, body) { return step('decide', version, body, (l) => ({ ...l, status: body.decision === 'approve' ? 'approved' : 'rejected' })); },
    async sendBack(_id, version, note) { return step('send_back', version, note, (l) => ({ ...l, status: 'draft' })); },
    async cancel(_id, version, note) { return step('cancel', version, note, (l) => ({ ...l, status: 'cancelled' })); },
  };
  return { api, calls, failNext: (name: string, error: FormError) => { failOnce = { name, error }; }, loseNext: (name: string) => { loseOnce = name; } };
}

function mount(node: ReactElement, opts: { me?: Me; loan?: Loan; items?: CollateralItem[]; tightened?: boolean } = {}) {
  const applications = createApplicationsMock(opts.loan, opts.tightened);
  const members = createMembersMock([borrower, guarantor]);
  const collateral = createCollateralMock(opts.items ?? [plot, unvalued]);
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <LendingApiContext.Provider value={{ members: members.api, collateral: collateral.api, applications: applications.api }}>
          <StaffContext.Provider value={{ me: opts.me ?? OFFICER, branch: BRANCH }}>{node}</StaffContext.Provider>
        </LendingApiContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return applications;
}

const type = (label: RegExp | string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });
const errorOf = (id: string) => document.getElementById(`${id}-error`)?.textContent ?? '';
const send = () => fireEvent.click(screen.getByRole('button', { name: 'Send for appraisal' }));
// A press that the form refused holds its button for two more tasks (BusyButton), as against a quick second tap.
const tick = () => new Promise((r) => setTimeout(r, 20));

describe('the loan application form', () => {
  it('renders the needed fields with a star and the line that explains it', async () => {
    mount(<ApplicationForm />);
    expect(screen.getByText(/are needed\./).textContent).toBe('Fields marked * are needed.');
    expect(screen.getByLabelText(/^Find the member/).getAttribute('aria-required')).toBe('true');
    for (const label of [/^Loan product/, /^Amount asked for \(UGX\)/, /^What the loan is for/]) {
      expect(screen.getByLabelText(label).getAttribute('aria-required'), String(label)).toBe('true');
    }
    expect(await screen.findByRole('option', { name: 'Test monthly loan (TEST-MONTHLY)' })).toBeTruthy();
    // Nothing is marked wrong on a form nobody has tried to send yet.
    expect(screen.queryByText(/Some entries need a look/)).toBeNull();
  });

  it('names what is missing under each field on Send, and the product\'s limits once a field is left', async () => {
    const api = mount(<ApplicationForm />);
    send();
    expect(errorOf('af-member-id')).toMatch(/Choose the member/);
    expect(errorOf('af-product-id')).toMatch(/Choose the loan product/);
    expect(errorOf('af-requested-principal-minor')).toMatch(/how much/);
    expect(errorOf('af-purpose-category')).toMatch(/what the loan is for/);
    expect(api.calls).toEqual([]);
    cleanup();
    mount(<ApplicationForm memberId={borrower.id} />);
    await screen.findByRole('option', { name: /Test monthly loan/ });
    type(/^Loan product/, 'p1');
    type(/^Amount asked for/, '50,000');
    expect(errorOf('af-requested-principal-minor')).toBe('');
    fireEvent.blur(screen.getByLabelText(/^Amount asked for/));
    expect(errorOf('af-requested-principal-minor')).toBe('This product lends from UGX 100,000 to UGX 5,000,000.');
    type(/^How long, in months/, '24');
    fireEvent.blur(screen.getByLabelText(/^How long, in months/));
    expect(errorOf('af-requested-term-count')).toBe('This product allows from 1 month to 12 months.');
  });

  it('in a KES business, types and checks the amount in KES before a product is chosen (review of #591)', async () => {
    mount(<ApplicationForm />, { me: { ...OFFICER, tenant_currency: 'KES' } });
    const amount = screen.getByLabelText(/^Amount asked for \(KES\)/);
    expect(amount.getAttribute('inputmode')).toBe('decimal');
    type(/^Amount asked for/, '1,500.50');
    fireEvent.blur(amount);
    expect(errorOf('af-requested-principal-minor')).toBe('');
  });

  it('asks for a guarantor and collateral when the product needs them', async () => {
    mount(<ApplicationForm memberId={borrower.id} />);
    await screen.findByRole('option', { name: /Test secured loan/ });
    type(/^Loan product/, 'p2');
    send();
    expect(errorOf('af-guarantors')).toMatch(/at least one guarantor/);
    expect(errorOf('af-collateral')).toMatch(/needs collateral/);
  });

  it('creates the draft, pledges the chosen item, then submits, each with the version the last answer gave', async () => {
    const api = mount(<ApplicationForm memberId={borrower.id} />);
    await screen.findByText('Test Borrower 01');
    await screen.findByRole('option', { name: /Test monthly loan/ });
    type(/^Loan product/, 'p1');
    type(/^Amount asked for/, '1,200,000');
    type(/^How long, in months/, '6');
    type(/^What the loan is for/, 'business');
    // Only an item that is held and valued can secure the loan; the unvalued one is counted, not offered.
    const box = await screen.findByRole('checkbox', { name: /Land title: Test plot, worth UGX 3,000,000/ });
    expect(screen.getByText(/One item is not shown/)).toBeTruthy();
    fireEvent.click(box);
    type(/^Amount of the loan it secures/, '1,500,000');
    send();
    await waitFor(() => expect(api.calls.map((c) => c.name)).toEqual(['create', 'collateral', 'submit']));
    expect(api.calls[0]?.body).toMatchObject({ member_id: borrower.id, product_id: 'p1', requested_principal_minor: 1_200_000, requested_term_count: 6, purpose_category: 'business' });
    expect(api.calls[1]).toMatchObject({ version: 1, body: [{ collateral_id: 'c1', pledged_value_minor: 1_500_000 }] });
    expect(api.calls[2]).toMatchObject({ version: 2 });
  });

  it('keeps the draft when the submit is refused, says so, and carries on from it on the next press', async () => {
    const api = mount(<ApplicationForm memberId={borrower.id} />);
    await screen.findByRole('option', { name: /Test monthly loan/ });
    type(/^Loan product/, 'p1');
    type(/^Amount asked for/, '1,000,000');
    type(/^What the loan is for/, 'business');
    api.failNext('submit', new FormError('The member\'s details have not been checked yet.', 422, 'kyc_not_verified'));
    send();
    expect(await screen.findByText(/saved as draft LN000001, but it was not sent for appraisal\. The member's details have not been checked yet\./)).toBeTruthy();
    send();
    await waitFor(() => expect(api.calls.map((c) => c.name)).toEqual(['create', 'submit', 'submit']));
    expect(api.calls.filter((c) => c.name === 'create')).toHaveLength(1);
  });

  it('opens the loan when a submit went through but its answer was lost, and sends nothing twice (review of #591)', async () => {
    await router.navigate({ to: '/staff/lending' });
    const api = mount(<ApplicationForm loan={loan('draft')} />, { loan: loan('draft') });
    api.loseNext('submit');
    send();
    await waitFor(() => expect(router.state.location.pathname).toBe('/staff/lending/loans/l1'));
    expect(api.calls.map((c) => c.name)).toEqual(['submit']);
  });

  it('adds a guarantor found by searching, and refuses the borrower as their own guarantor', async () => {
    const api = mount(<ApplicationForm memberId={borrower.id} />);
    await screen.findByRole('option', { name: /Test monthly loan/ });
    type(/^Loan product/, 'p1');
    type(/^Amount asked for/, '1,000,000');
    type(/^What the loan is for/, 'business');
    fireEvent.click(screen.getByRole('button', { name: 'Add a guarantor' }));
    type(/^Find the guarantor/, 'Guarantor');
    fireEvent.click(await screen.findByRole('button', { name: /Test Guarantor 01/ }));
    type(/^Amount they guarantee/, '400,000');
    send();
    await waitFor(() => expect(api.calls.map((c) => c.name)).toEqual(['create', 'guarantors', 'submit']));
    expect(api.calls[1]?.body).toEqual([{ member_id: guarantor.id, guaranteed_amount_minor: 400_000, relationship: undefined }]);
  });
});

describe('changing a draft', () => {
  it('checks the draft against the product version it was made on, as the server does, not a newer one', async () => {
    // The product's current version (in the list and on the detail) now lends at most 500,000.
    const api = mount(<ApplicationForm loan={loan('draft', { product_version_id: 'v1' })} />, { loan: loan('draft', { product_version_id: 'v1' }), tightened: true });
    expect(await screen.findByText('UGX 100,000 to UGX 5,000,000')).toBeTruthy();
    type(/^Amount asked for/, '2,000,000');
    fireEvent.blur(screen.getByLabelText(/^Amount asked for/));
    expect(errorOf('af-requested-principal-minor')).toBe('');
    send();
    await waitFor(() => expect(api.calls.map((c) => c.name)).toEqual(['update', 'submit']));
    expect(api.calls[0]).toMatchObject({ version: 3, body: { requested_principal_minor: 2_000_000 } });
  });
});

describe('the route gates (applicationSteps on the loaded loan)', () => {
  const scoped = (permission: string) => staff([permission], { [permission]: { all_branches: false, branch_ids: [OTHER] }, 'lending.loans.read': { all_branches: true } });
  const gate = async (node: ReactElement, opts: { me: Me; loan: Loan }, form: string) => {
    mount(node, opts);
    await screen.findByText(/You do not have access/);
    expect(screen.queryByRole('form', { name: form })).toBeNull();
    cleanup();
  };

  it('opens Change the application only to a creator in the loan\'s branch, on a draft', async () => {
    mount(<EditApplicationBody loanId="l1" />, { loan: loan('draft') });
    expect(await screen.findByRole('form', { name: 'Change the application' })).toBeTruthy();
    cleanup();
    await gate(<EditApplicationBody loanId="l1" />, { me: APPRAISER, loan: loan('draft') }, 'Change the application');
    await gate(<EditApplicationBody loanId="l1" />, { me: scoped('lending.loans.create'), loan: loan('draft') }, 'Change the application');
    await gate(<EditApplicationBody loanId="l1" />, { me: OFFICER, loan: loan('submitted') }, 'Change the application');
  });

  it('opens Appraise only to an appraiser in the loan\'s branch, on a submitted or appraised loan', async () => {
    mount(<AppraiseBody loanId="l1" />, { me: APPRAISER, loan: loan('submitted') });
    expect(await screen.findByRole('form', { name: 'Appraise the application' })).toBeTruthy();
    cleanup();
    await gate(<AppraiseBody loanId="l1" />, { me: MANAGER, loan: loan('submitted') }, 'Appraise the application');
    await gate(<AppraiseBody loanId="l1" />, { me: scoped('lending.loans.appraise'), loan: loan('submitted') }, 'Appraise the application');
    await gate(<AppraiseBody loanId="l1" />, { me: APPRAISER, loan: loan('draft') }, 'Appraise the application');
  });

  it('opens the decision only to an approver in the loan\'s branch, on an appraised loan', async () => {
    mount(<DecideBody loanId="l1" />, { me: MANAGER, loan: loan('appraised') });
    expect(await screen.findByRole('form', { name: 'Decide on the application' })).toBeTruthy();
    cleanup();
    await gate(<DecideBody loanId="l1" />, { me: APPRAISER, loan: loan('appraised') }, 'Decide on the application');
    await gate(<DecideBody loanId="l1" />, { me: scoped('lending.loans.approve'), loan: loan('appraised') }, 'Decide on the application');
    await gate(<DecideBody loanId="l1" />, { me: MANAGER, loan: loan('submitted') }, 'Decide on the application');
  });
});

describe('the application on the loan page', () => {
  it('offers Send for appraisal, as the one primary action, only to who may create', () => {
    mount(<LoanView loan={loan('draft')} />);
    const primary = screen.getByRole('button', { name: 'Send for appraisal' });
    expect(primary.className).toMatch(/rt-primary/);
    expect(primary.closest('.mf-savebar')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Change the application' })).toBeTruthy();
    cleanup();
    mount(<LoanView loan={loan('draft')} />, { me: READER });
    expect(screen.queryByRole('button', { name: 'Send for appraisal' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Change the application' })).toBeNull();
    expect(screen.getByText('Draft: not sent for appraisal yet.')).toBeTruthy();
  });

  it('shows an approved loan\'s payments once, with Disburse as its action', async () => {
    mount(<LoanView loan={loan('approved', { approved_principal_minor: 1_000_000, approved_term_count: 6 })} />, { me: staff(['lending.disbursements.request']) });
    expect(screen.getByRole('button', { name: 'Disburse' })).toBeTruthy();
    expect(screen.getAllByRole('table')).toHaveLength(1);
    expect(screen.getByText(/Until then, see Payments it would mean above\./)).toBeTruthy();
  });

  it('sends a draft for appraisal with its version', async () => {
    const api = mount(<LoanView loan={loan('draft')} />, { loan: loan('draft') });
    fireEvent.click(screen.getByRole('button', { name: 'Send for appraisal' }));
    await waitFor(() => expect(api.calls).toEqual([{ name: 'submit', version: 3, body: undefined }]));
  });

  it('gives the appraiser Appraise, the manager Decide, Send back and Cancel, and neither to the other', async () => {
    mount(<LoanView loan={loan('submitted')} />, { me: APPRAISER });
    expect(screen.getByRole('link', { name: 'Appraise' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Decide' })).toBeNull();
    cleanup();
    mount(<LoanView loan={loan('appraised')} />, { me: MANAGER });
    expect(screen.getByRole('link', { name: 'Decide' }).className).toMatch(/rt-primary/);
    expect(screen.getByRole('button', { name: 'Send back for changes' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Cancel the application' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: /Appraise/ })).toBeNull();
    expect(await screen.findByText('68 of 100')).toBeTruthy();
    expect(screen.getByText('The member joined less than 30 days ago.')).toBeTruthy();
  });

  it('keeps the appraisal on a cancelled application, and marks an appraisal earlier once the application was sent back and sent again', async () => {
    mount(<LoanView loan={loan('cancelled', { cancelled_reason: 'Member withdrew.' })} />, { me: MANAGER });
    expect(await screen.findByText('68 of 100')).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Appraisal' })).toBeTruthy();
    cleanup();
    mount(<LoanView loan={loan('submitted')} />, { me: APPRAISER });
    expect(await screen.findByRole('heading', { name: 'Earlier appraisal' })).toBeTruthy();
    expect(screen.getByText(/It no longer stands/)).toBeTruthy();
  });

  it('follows the branch scope of the permission, as the server does', () => {
    mount(<LoanView loan={loan('appraised')} />, { me: staff(['lending.loans.approve'], { 'lending.loans.approve': { all_branches: false, branch_ids: [OTHER] }, 'lending.loans.read': { all_branches: true } }) });
    expect(screen.queryByRole('link', { name: 'Decide' })).toBeNull();
  });

  it('needs a note to send an application back, said under the box', async () => {
    const api = mount(<LoanView loan={loan('appraised')} />, { me: MANAGER, loan: loan('appraised') });
    fireEvent.click(screen.getByRole('button', { name: 'Send back for changes' }));
    const form = screen.getByRole('form', { name: 'Send back for changes' });
    fireEvent.click(form.querySelector('button[type="submit"]') as HTMLButtonElement);
    expect(errorOf('sb-note')).toMatch(/short note/);
    type(/^What needs changing/, 'Add a second guarantor.');
    await tick();
    fireEvent.click(form.querySelector('button[type="submit"]') as HTMLButtonElement);
    await waitFor(() => expect(api.calls).toEqual([{ name: 'send_back', version: 3, body: 'Add a second guarantor.' }]));
  });

  it('lets an officer cancel their own draft but not someone else\'s', () => {
    const officer = staff(['lending.loans.create', 'lending.loans.cancel']);
    mount(<LoanView loan={loan('draft')} />, { me: officer });
    expect(screen.getByRole('button', { name: 'Cancel the application' })).toBeTruthy();
    cleanup();
    mount(<LoanView loan={loan('draft', { created_by: COLLEAGUE })} />, { me: officer });
    expect(screen.queryByRole('button', { name: 'Cancel the application' })).toBeNull();
  });

  it('starts a new application from the loans list only for who may create one', () => {
    mount(<Loans />);
    expect(screen.getByRole('link', { name: 'New loan application' })).toBeTruthy();
    cleanup();
    mount(<Loans />, { me: READER });
    expect(screen.queryByRole('link', { name: 'New loan application' })).toBeNull();
  });
});

describe('appraising', () => {
  it('says the member\'s declared income is used when the box is empty, and sends what was typed in minor units', async () => {
    const api = mount(<AppraiseForm loan={loan('submitted')} />, { me: APPRAISER, loan: loan('submitted') });
    expect(await screen.findByText(/Leave empty to use the income on the member's details, UGX 800,000\./)).toBeTruthy();
    expect(screen.getByText('UGX 196,667')).toBeTruthy();
    type(/^Other payments a month/, '12.5');
    fireEvent.blur(screen.getByLabelText(/^Other payments a month/));
    expect(errorOf('apf-monthly-obligations-minor')).toMatch(/in shillings, with no letters or decimals/);
    type(/^Other payments a month/, '150,000');
    type(/^Notes from the visit/, 'Test shop visited, stock seen.');
    fireEvent.click(screen.getByRole('button', { name: 'Appraise' }));
    await waitFor(() => expect(api.calls).toHaveLength(1));
    expect(api.calls[0]).toEqual({ name: 'appraise', version: 3, body: { declared_monthly_income_minor: undefined, monthly_obligations_minor: 150_000, visit_notes: 'Test shop visited, stock seen.' } });
  });
});

describe('deciding', () => {
  it('switches Approve off, with the reason, for whoever sent or appraised the application, and leaves Decline (FR-APR-03)', () => {
    mount(<DecisionForm loan={loan('appraised', { submitted_by: COLLEAGUE, appraised_by: ME_ID })} appraisal={appraisal} />, { me: MANAGER });
    expect((screen.getByRole('radio', { name: 'Approve' }) as HTMLInputElement).disabled).toBe(true);
    expect((screen.getByRole('radio', { name: 'Decline' }) as HTMLInputElement).disabled).toBe(false);
    expect(screen.getByText(/You appraised this application, so someone else must approve it/)).toBeTruthy();
  });

  it('needs a choice, and a reason to decline, each said under its field', async () => {
    const api = mount(<DecisionForm loan={loan('appraised')} appraisal={appraisal} />, { me: MANAGER, loan: loan('appraised') });
    fireEvent.click(screen.getByRole('button', { name: 'Record the decision' }));
    expect(errorOf('dc-decision')).toMatch(/Choose Approve or Decline/);
    fireEvent.click(screen.getByRole('radio', { name: 'Decline' }));
    fireEvent.click(screen.getByRole('button', { name: 'Decline the application' }));
    expect(errorOf('dc-note')).toMatch(/Say why/);
    type(/^Reason/, 'Income too low for this amount.');
    await tick();
    fireEvent.click(screen.getByRole('button', { name: 'Decline the application' }));
    await waitFor(() => expect(api.calls).toEqual([{ name: 'decide', version: 3, body: { decision: 'reject', note: 'Income too low for this amount.' } }]));
  });

  it('approves at or below what was asked, sending only what the approver changed', async () => {
    const api = mount(<DecisionForm loan={loan('appraised')} appraisal={appraisal} />, { me: MANAGER, loan: loan('appraised') });
    fireEvent.click(screen.getByRole('radio', { name: 'Approve' }));
    expect((screen.getByLabelText(/^Amount to lend/) as HTMLInputElement).value).toBe('1,000,000');
    type(/^Amount to lend/, '1,500,000');
    fireEvent.blur(screen.getByLabelText(/^Amount to lend/));
    expect(errorOf('dc-approved-principal-minor')).toBe('You can approve at most what was asked for, UGX 1,000,000.');
    type(/^Amount to lend/, '800,000');
    fireEvent.click(screen.getByRole('button', { name: 'Approve the loan' }));
    await waitFor(() => expect(api.calls).toHaveLength(1));
    expect(api.calls[0]).toEqual({ name: 'decide', version: 3, body: { decision: 'approve', approved_principal_minor: 800_000, approved_term_count: undefined, note: undefined } });
  });

  it('shows a refusal from the server in plain words', async () => {
    const api = mount(<DecisionForm loan={loan('appraised')} appraisal={appraisal} />, { me: MANAGER, loan: loan('appraised') });
    api.failNext('decide', new FormError('The collateral covers less of the loan than this product needs. Approve a smaller amount, or send it back for more collateral.', 422, 'collateral_below_product_minimum'));
    fireEvent.click(screen.getByRole('radio', { name: 'Approve' }));
    fireEvent.click(screen.getByRole('button', { name: 'Approve the loan' }));
    expect(await screen.findByText(/covers less of the loan than this product needs/)).toBeTruthy();
  });
});

describe('tap targets', () => {
  const buttons = readFileSync(join(process.cwd(), 'src/app/ui/buttons.css'), 'utf8');
  const lending = readFileSync(join(process.cwd(), 'src/app/ui/lending.css'), 'utf8');
  const block = (css: string, selector: string) => new RegExp(`(?:^|\\n)${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`).exec(css)?.[1] ?? '';

  it('keeps every Remove button (.btn-sm) and every choice row at the 48px target', () => {
    mount(<ApplicationForm loan={loan('draft', { guarantors: [{ member_id: guarantor.id, member_no: 'M000202', guaranteed_amount_minor: 100_000 }] })} />);
    const remove = screen.getByRole('button', { name: /^Remove/ });
    expect(remove.tagName).toBe('BUTTON');
    // Every button takes min-height: var(--tap), 48px; .btn-sm changes only its padding and type size.
    expect(block(buttons, 'button,\n.btn')).toMatch(/min-height:\s*var\(--tap\)/);
    expect(block(buttons, '.btn-sm')).toMatch(/padding/);
    expect(block(buttons, '.btn-sm')).not.toMatch(/height/);
    expect(block(lending, '.mf-check')).toMatch(/min-height:\s*var\(--tap\)/);
  });
});
