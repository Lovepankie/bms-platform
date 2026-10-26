import type { ReactElement } from 'react';
import type { Me } from '../api/client';
import type { Loan } from '../api/lending';
import { mockMe } from '../api/retail-mock';
import { ApplicationForm } from '../areas/staff/lending/application-form';
import { AppraiseForm } from '../areas/staff/lending/appraise';
import { DecisionForm } from '../areas/staff/lending/decide';
import { LendingGate } from '../areas/staff/lending/loans';
import { describeTours } from './test-kit';
import { APPLICATION_TOURS } from './tours-applications';

// The page tours of the loan application, appraisal and decision screens (issue #280 slices 3 and 4): each page is rendered as
// the users its tour is written for, and every step's data-tour anchor must be in the markup. All values are fabricated.

const branches = mockMe('sales').branches;
const BRANCH = branches?.[0]?.id ?? '';

const person = (name: string, permissions: string[]): Me =>
  ({ ...mockMe('sales'), modules: ['lending'], full_name: name, permission_scopes: undefined, permissions, branches }) as Me;

const OFFICER = person('Test Officer 01', ['lending.loans.read', 'lending.members.read', 'lending.loans.create', 'lending.collateral.read']);
const APPRAISER = person('Test Appraiser 01', ['lending.loans.read', 'lending.members.read', 'lending.loans.appraise']);
const MANAGER = person('Test Manager 01', ['lending.loans.read', 'lending.members.read', 'lending.loans.approve', 'lending.loans.cancel']);

const loan = (status: string): Loan => ({
  id: 'l1', loan_no: 'LN000001', branch_id: BRANCH, member_id: 'm1', member_no: 'M000001', product_code: 'TEST-MONTHLY', status, currency: 'UGX',
  requested_principal_minor: 1_000_000, requested_term_count: 6, term_unit: 'month', interest_rate_bp: 300, rate_unit: 'per_month', interest_method: 'flat',
  purpose_category: 'business', guarantors: [], collateral: [], provisional_schedule: [], version: 3,
});

function screen(path: string): ReactElement {
  switch (path) {
    case '/staff/lending/applications/new':
      return <LendingGate title="New loan application"><ApplicationForm /></LendingGate>;
    case '/staff/lending/loans/$loanId/edit':
      return <LendingGate title="Change the application"><ApplicationForm loan={loan('draft')} /></LendingGate>;
    case '/staff/lending/loans/$loanId/appraise':
      return <LendingGate title="Appraise"><AppraiseForm loan={loan('submitted')} /></LendingGate>;
    case '/staff/lending/loans/$loanId/decide':
      return <LendingGate title="Decision"><DecisionForm loan={loan('appraised')} /></LendingGate>;
    default:
      throw new Error(`No screen is mapped for ${path}: add it to tours-applications.test.tsx`);
  }
}

describeTours('loan application tours', APPLICATION_TOURS, {
  'page-application-new': [OFFICER],
  'page-application-edit': [OFFICER],
  'page-loan-appraise': [APPRAISER],
  'page-loan-decide': [MANAGER],
}, screen);
