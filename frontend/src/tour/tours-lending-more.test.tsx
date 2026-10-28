import type { QueryClient } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import type { Me } from '../api/client';
import type { Investment, InvestmentCertificate, InvestmentProduct } from '../api/investments';
import { mockMe } from '../api/retail-mock';
import type { SmsSettings, SmsTemplate } from '../api/sms';
import type { SavingsAccount } from '../api/savings';
import { StaffHome } from '../areas/staff/home';
import { Approvals } from '../areas/staff/approvals';
import { Insights } from '../areas/staff/insights/page';
import { CollateralForm } from '../areas/staff/lending/collateral-form';
import { ExistingLoansPage } from '../areas/staff/lending/existing-loans';
import { ExistingLoansImport } from '../areas/staff/lending/existing-loans-import';
import { CertificateBody } from '../areas/staff/lending/investment-certificate';
import { NewInvestment } from '../areas/staff/lending/investment-new';
import { Products as InvestmentProducts } from '../areas/staff/lending/investment-products';
import { InvestmentView } from '../areas/staff/lending/investment';
import { Investments, InvestmentsGate, MemberInvestmentsBody } from '../areas/staff/lending/investments';
import { Maturities } from '../areas/staff/lending/maturities';
import { MemberForm } from '../areas/staff/lending/member-form';
import { MemberSavingsBody } from '../areas/staff/lending/member-savings';
import { CollateralGate, MembersGate } from '../areas/staff/lending/member-ui';
import { AccountView } from '../areas/staff/lending/savings-account';
import { Products as SavingsProducts } from '../areas/staff/lending/savings-products';
import { StatementBody } from '../areas/staff/lending/savings-statement';
import { Accounts, SavingsGate } from '../areas/staff/lending/savings';
import { ReceivePaymentsPage } from '../areas/staff/payments/page';
import { Reports } from '../areas/staff/reports/page';
import { OptOutsScreen } from '../areas/staff/sms/opt-outs';
import { SmsSettingsScreen } from '../areas/staff/sms/settings';
import { WordingScreen } from '../areas/staff/sms/wording';
import { InviteScreen } from '../areas/staff/users/invite';
import { OwnTwoStep } from '../areas/staff/two-step/own';
import { describeTours } from './test-kit';
import { LENDING_MORE_TOURS } from './tours-lending-more';

// The page tours of lending savings and investments and the remaining staff pages (issue #337): each page is rendered as the
// users its tour is written for, and every step's data-tour anchor must be in the markup. Pages that read their id from the
// address are drawn through their body component with a fabricated id. All names, numbers and amounts are fabricated.

const BRANCH = '00000000-0000-4000-8000-0000000000a1';
const branches = mockMe('sales').branches;

const ALL = [
  'lending.savings.read', 'lending.savings.deposit', 'lending.savings.withdraw', 'lending.savings.open', 'lending.savings_products.manage',
  'lending.members.read', 'lending.members.create', 'lending.members.update', 'lending.collateral.read', 'lending.collateral.manage',
  'lending.investments.read', 'lending.investments.open', 'lending.investments.fund', 'lending.investments.payout', 'lending.investment_products.manage',
  'lending.loans.read', 'lending.loans.bring_in', 'lending.insights.read', 'lending.reports.portfolio',
  'core.approvals.read', 'core.settings.read', 'core.settings.manage', 'core.payment_account.manage', 'core.users.manage',
  'core.notifications.read', 'core.notification_templates.manage',
];

const person = (name: string, permissions: string[]): Me =>
  ({ ...mockMe('sales'), modules: ['lending'], full_name: name, permission_scopes: undefined, permissions, branches, reports_enabled: true }) as Me;

// A business administrator with lending: everything these pages offer.
const OWNER = person('Test Owner 01', ALL);
// A clerk who can only look: no buttons, but every page still explains itself.
const SAVER_READER = person('Test Reader 01', ['lending.savings.read', 'lending.members.read']);
const INVESTMENT_READER = person('Test Reader 02', ['lending.investments.read']);
const ASKER = person('Test Asker 01', ['core.approvals.read', 'core.settings.read', 'core.notifications.read']);
// The owner of a shop (issue #485): Home shows the shop's summary cards.
const SHOP_OWNER = { ...mockMe('admin'), full_name: 'Test Shop Owner 01', modules: ['retail'], permission_scopes: undefined, branches } as Me;

const account: SavingsAccount = {
  id: 'a1', account_no: 'SV000001', member_id: 'm1', member_no: 'M000001', member_name: 'Test Saver 01', product_code: 'TEST-SAVE', product_name: 'Test passbook savings',
  currency: 'UGX', status: 'active', balance_minor: 150000, available_minor: 144000, opened_on: '2026-01-05',
};

const investment: Investment = {
  id: 'i1', account_no: 'IV000001', member_id: 'm1', member_no: 'M000001', member_name: 'Test Investor 01', product_name: 'Test fixed term', currency: 'UGX',
  status: 'active', principal_minor: 1000000, principal_held_minor: 1000000, return_available_minor: 5000, certificate_no: 'CERT-000001', term_months: 12, return_rate_bp: 1200,
  return_method: 'flat', payout_frequency: 'monthly', start_date: '2026-01-05', maturity_date: '2027-01-05',
};

const certificate: InvestmentCertificate = {
  tenant_name: 'Test Trading Co', branch_name: 'Test Branch A', certificate_no: 'CERT-000001', account_no: 'IV000001', member_name: 'Test Investor 01', member_no: 'M000001',
  product_name: 'Test fixed term', currency: 'UGX', principal_minor: 1000000, return_rate_bp: 1200, return_method: 'flat', payout_frequency: 'monthly', term_months: 12,
  start_date: '2026-01-05', maturity_date: '2027-01-05', agreed_return_minor: 120000, maturity_value_minor: 1120000, issued_on: '2026-01-05',
};

const product: InvestmentProduct = {
  id: 'p1', code: 'FIX12', name: 'Test fixed term', status: 'active', currency: 'UGX', return_rate_bp: 1200, return_method: 'flat', payout_frequency: 'monthly',
  allowed_terms_months: [6, 12], min_amount_minor: 100000, product_type: 'fixed', early_withdrawal_allowed: false,
};

const smsSettings: SmsSettings = {
  enabled: false, operator_enabled: true, mode: 'live', test_message_available: false, sender_id: null, sender_status: 'none', approved_sender_ids: ['BrandTest1'],
  window_start: '08:00', window_end: '20:00', window_zone: 'Africa/Kampala', window_earliest: '06:00', window_latest: '21:00', window_min_minutes: 60,
  daily_cap: null, monthly_cap: null, daily_ceiling: 300, monthly_ceiling: 9300, effective_daily_cap: 300, effective_monthly_cap: 9300, used_today: 0, used_this_month: 0,
  include_balance: false, acknowledgement: { text_version: 'sms-1', text: 'Switching on SMS sends messages to your members. Every message costs money.' },
  acknowledged_text_version: null, acknowledged_at: null, acknowledged_by: null, version: 1,
};

const smsTemplate: SmsTemplate = {
  event_key: 'repayment_reminder', channel: 'sms', title: 'Repayment reminder', default_text: 'Test Trading Co: {amount} is due on {date}.',
  placeholders: [{ name: 'amount', description: 'The amount due', sample: 'UGX 10,000' }], override: null, sample_text: 'Test Trading Co: UGX 10,000 is due on 12 Oct.',
  measure: { encoding: 'gsm7', units: 44, segments: 1, worst_case_segments: 1, estimated_cost_minor: null, currency: null }, note: null, max_chars: 160, max_worst_case_segments: 2,
} as SmsTemplate;

// What the pages read before they can draw their body.
function seed(client: QueryClient, me: Me): void {
  // The setup helper's box shows only while the platform has it on (issue #626); on here, so its tour step has its anchor.
  client.setQueryData(['help', 'setup'], { enabled: true, photos: true });
  const branch = me.branches?.[0]?.id ?? null;
  client.setQueryData(['approvals', branch], {
    items: [{ id: 'ap1', action_type: 'refund', amount_minor: 50000, currency: 'UGX', requested_by: 'u9', requested_by_name: 'Test Clerk 09', requested_at: '2026-10-01T08:00:00Z', can_decide: true, can_cancel: false }],
  });
  client.setQueryData(['payments', 'account'], { status: 'failed', credentials_saved: true, key_hint: 'ab12', last_checked_at: null, failure_reason: 'invalid_credentials', host_ready: true });
  client.setQueryData(['reports', 'catalogue'], [{ key: 'portfolio', title: 'Portfolio report', description: 'The loan book', permission: 'lending.reports.portfolio', formats: ['pdf', 'xlsx'], params: [] }]);
  client.setQueryData(['reports', 'runs'], []);
  client.setQueryData(['sms', 'settings'], smsSettings);
  client.setQueryData(['sms', 'templates'], [smsTemplate]);
  client.setQueryData(['lending', 'investment-products'], [product]);
  client.setQueryData(['lending', 'investment', 'i1', 'certificate'], certificate);
}

function screen(path: string): ReactElement {
  switch (path) {
    case '/staff/lending/savings':
      return <Accounts />;
    case '/staff/lending/savings/products':
      return <SavingsProducts />;
    case '/staff/lending/savings/accounts/$accountId':
      return <SavingsGate title="Savings account"><AccountView account={account} /></SavingsGate>;
    case '/staff/lending/savings/accounts/$accountId/statement':
      return <SavingsGate title="Statement"><StatementBody accountId="a1" /></SavingsGate>;
    case '/staff/lending/members/$memberId/savings':
      return <SavingsGate title="Member savings"><MemberSavingsBody memberId="m1" /></SavingsGate>;
    case '/staff/lending/members/$memberId/edit':
      return (
        <MembersGate title="Change member details" tourId="member-edit">
          <MemberForm member={{ id: 'm1', branch_id: BRANCH, member_no: 'M000001', full_name: 'Test Member 01', kyc_status: 'incomplete', status: 'active', id_type: 'nin', version: 1 }} />
        </MembersGate>
      );
    case '/staff/lending/collateral/$collateralId/edit':
      return (
        <CollateralGate title="Change collateral" tourId="collateral-edit">
          <CollateralForm item={{ id: 'c1', branch_id: BRANCH, member_id: 'm1', collateral_type: 'land_title', description: 'Test plot', custody_status: 'pledged', currency: 'UGX', version: 1 }} />
        </CollateralGate>
      );
    case '/staff/lending/investments':
      return <Investments />;
    case '/staff/lending/investments/new':
      return <InvestmentsGate title="New investment"><NewInvestment /></InvestmentsGate>;
    case '/staff/lending/investments/member/$memberId/new':
      return <InvestmentsGate title="New investment"><NewInvestment memberId="m1" /></InvestmentsGate>;
    case '/staff/lending/investments/maturities':
      return <Maturities />;
    case '/staff/lending/investments/products':
      return <InvestmentProducts />;
    case '/staff/lending/investments/member/$memberId':
      return <InvestmentsGate title="Member investments"><MemberInvestmentsBody memberId="m1" /></InvestmentsGate>;
    case '/staff/lending/investments/$investmentId':
      return <InvestmentsGate title="Investment"><InvestmentView inv={investment} /></InvestmentsGate>;
    case '/staff/lending/investments/$investmentId/certificate':
      return <InvestmentsGate title="Certificate"><CertificateBody investmentId="i1" /></InvestmentsGate>;
    case '/staff':
      return <StaffHome />;
    case '/staff/approvals':
      return <Approvals />;
    case '/staff/insights':
      return <Insights />;
    case '/staff/payments':
      return <ReceivePaymentsPage />;
    case '/staff/users/new':
      return <InviteScreen />;
    case '/staff/reports':
      return <Reports />;
    case '/staff/sms':
      return <SmsSettingsScreen />;
    case '/staff/sms/wording':
      return <WordingScreen />;
    case '/staff/two-step':
      return <OwnTwoStep />;
    case '/staff/sms/opt-outs':
      return <OptOutsScreen />;
    case '/staff/lending/existing-loans':
      return <ExistingLoansPage />;
    case '/staff/lending/existing-loans/import':
      return <ExistingLoansImport />;
    default:
      throw new Error(`No screen is mapped for ${path}: add it to tours-lending-more.test.tsx`);
  }
}

const AUDIENCE: Record<string, Me[]> = {
  'page-lending-savings': [OWNER, SAVER_READER],
  'page-savings-products': [OWNER],
  'page-savings-account': [OWNER, SAVER_READER],
  'page-savings-statement': [OWNER, SAVER_READER],
  'page-member-savings': [OWNER, SAVER_READER],
  'page-member-edit': [OWNER],
  'page-collateral-edit': [OWNER],
  'page-investments': [OWNER, INVESTMENT_READER],
  'page-investment-new': [OWNER],
  'page-investment-new-member': [OWNER],
  'page-investment-maturities': [OWNER, INVESTMENT_READER],
  'page-investment-products': [OWNER],
  'page-member-investments': [OWNER],
  'page-investment': [OWNER, INVESTMENT_READER],
  'page-investment-certificate': [OWNER, INVESTMENT_READER],
  'page-staff-home': [OWNER, ASKER, SHOP_OWNER],
  'page-approvals': [OWNER, ASKER],
  'page-insights': [OWNER],
  'page-payments': [OWNER, ASKER],
  'page-invite-user': [OWNER],
  'page-reports': [OWNER],
  'page-sms-settings': [OWNER, ASKER],
  'page-sms-wording': [OWNER],
  'page-sms-opt-outs': [OWNER],
  'page-own-two-step': [OWNER],
  'page-existing-loans': [OWNER],
  'page-existing-loans-import': [OWNER],
};

describeTours('lending more', LENDING_MORE_TOURS, AUDIENCE, screen, seed);
