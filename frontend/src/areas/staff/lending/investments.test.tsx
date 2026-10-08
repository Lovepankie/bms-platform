import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createMemoryHistory, createRootRoute, createRouter, RouterProvider } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import type { Investment, InvestmentCertificate, InvestmentMaturities, InvestmentProduct, InvestmentSchedule, InvestmentStatement } from '../../../api/investments';
import { router } from '../../../app/router';
import { StaffContext } from '../context';
import { CertificateCard } from './investment-certificate';
import { EMPTY, ProductCard, draftOf, termsOf } from './investment-products';
import { actionsFor, canReverse, parsePercent, percent, showInvestments } from './investment-state';
import { InvestmentView } from './investment';
import { InvestmentsGate } from './investments';
import { Ladder, MaturityRows } from './maturities';
import { SubscribeForm } from './investment-new';

// The investment screens rendered to static markup inside a memory router (Link needs one), as the
// platform portal tests do: what each page shows, and which actions each permission set and status
// is offered. All data fabricated.

const ID = '00000000-0000-4000-8000-0000000000e1';

const me = (permissions: string[]): Me => ({
  user_id: '00000000-0000-4000-8000-0000000000f2',
  full_name: 'Test Cashier 01',
  kind: 'staff',
  permissions,
  all_branches: true,
  branches: [{ id: '00000000-0000-4000-8000-0000000000b1', code: 'BR1', name: 'Test Branch A', is_head_office: true }],
});

const cashier = me(['lending.investments.read', 'lending.investments.fund', 'lending.investments.payout']);
const officer = me(['lending.investments.read', 'lending.investments.open']);
const viewer = me(['lending.investments.read']);

const inv: Investment = {
  id: ID,
  account_no: 'IV000001',
  member_id: '00000000-0000-4000-8000-0000000000c1',
  member_no: 'M000001',
  member_name: 'Test Investor 01',
  product_name: 'Test monthly income',
  product_type: 'fixed_term',
  status: 'active',
  currency: 'UGX',
  principal_minor: 1000000,
  return_rate_bp: 1250,
  return_method: 'flat',
  term_months: 6,
  payout_frequency: 'monthly',
  early_withdrawal_allowed: true,
  early_withdrawal_rule: 'reduced_rate',
  early_withdrawal_rate_bp: 600,
  early_withdrawal_penalty_bp: 100,
  start_date: '2026-05-01',
  maturity_date: '2026-11-01',
  agreed_return_minor: 62500,
  principal_held_minor: 1000000,
  return_accrued_minor: 31250,
  return_due_minor: 31250,
  return_paid_minor: 20833,
  return_available_minor: 10417,
  certificate_no: 'IC000001',
};

const schedule: InvestmentSchedule = {
  currency: 'UGX',
  total_return_minor: 62500,
  periods: [
    { period_no: 1, period_start: '2026-05-01', period_end: '2026-06-01', opening_balance_minor: 1000000, return_minor: 10417, cumulative_return_minor: 10417, payout: true, status: 'accrued' },
    { period_no: 2, period_start: '2026-06-01', period_end: '2026-07-01', opening_balance_minor: 1000000, return_minor: 10416, cumulative_return_minor: 20833, payout: true, status: 'scheduled' },
  ],
};

const statement: InvestmentStatement = {
  principal_balance_minor: 1000000,
  return_payable_minor: 10417,
  lines: [
    { transaction: { id: 't1', txn_type: 'funding', amount_minor: 1000000, value_date: '2026-05-01', receipt_no: 'RC-BR1-000001', payment_method_key: 'cash' }, principal_balance_minor: 1000000, return_payable_minor: 0 },
    { transaction: { id: 't2', txn_type: 'return_accrual', amount_minor: 10417, value_date: '2026-06-01', period_no: 1 }, principal_balance_minor: 1000000, return_payable_minor: 10417 },
    { transaction: { id: 't3', txn_type: 'return_payout', amount_minor: 10417, value_date: '2026-06-02', receipt_no: 'VC-BR1-000001', payment_method_key: 'mtn_momo' }, principal_balance_minor: 1000000, return_payable_minor: 0 },
  ],
};

async function page(session: Me, node: ReactElement, seed: (c: QueryClient) => void = () => undefined): Promise<string> {
  const client = new QueryClient();
  seed(client);
  const memory = createRouter({
    routeTree: createRootRoute({
      component: () => (
        <QueryClientProvider client={client}>
          <StaffContext.Provider value={{ me: session, branch: null }}>{node}</StaffContext.Provider>
        </QueryClientProvider>
      ),
    }),
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  await memory.load();
  return renderToStaticMarkup(<RouterProvider router={memory} />);
}

const seeded = (c: QueryClient) => {
  c.setQueryData(['lending', 'investment', ID, 'schedule'], schedule);
  c.setQueryData(['lending', 'investment', ID, 'statement'], statement);
};

describe('investment gating', () => {
  it('offers each action only with its permission and in the statuses the server accepts', () => {
    expect(actionsFor(cashier, inv)).toEqual(['pay_return', 'early']);
    expect(actionsFor(officer, inv)).toEqual(['instruct']);
    expect(actionsFor(viewer, inv)).toEqual([]);
    expect(actionsFor(cashier, { ...inv, status: 'pending_funding' })).toEqual(['fund']);
    expect(actionsFor(cashier, { ...inv, status: 'pending_funding', pending_approval_id: 'x' })).toEqual([]);
    expect(actionsFor(cashier, { ...inv, status: 'matured' })).toEqual(['pay_return', 'payout', 'rollover']);
    expect(actionsFor(cashier, { ...inv, return_available_minor: 0, early_withdrawal_allowed: false })).toEqual([]);
  });

  it('offers Reverse on a funding or a payout not yet reversed, never on an accrual', () => {
    expect(canReverse(cashier, { txn_type: 'return_payout' })).toBe(true);
    expect(canReverse(cashier, { txn_type: 'return_payout', reversed_by_txn_id: 'r' })).toBe(false);
    expect(canReverse(cashier, { txn_type: 'return_accrual' })).toBe(false);
    expect(canReverse(viewer, { txn_type: 'funding' })).toBe(false);
  });

  it('shows the menu entry with lending.investments.read only', () => {
    expect(showInvestments(viewer)).toBe(true);
    expect(showInvestments(me(['lending.loans.read']))).toBe(false);
    expect(showInvestments({ permissions: ['lending.investments.read'], modules: ['retail'] })).toBe(false);
  });

  it('reads and writes rates as percentages of basis points without floats', () => {
    expect(percent(1250)).toBe('12.5%');
    expect(percent(1200)).toBe('12%');
    expect(percent(1205)).toBe('12.05%');
    expect(parsePercent('12.5')).toBe(1250);
    expect(parsePercent('12.05')).toBe(1205);
    expect(parsePercent('abc')).toBeNull();
  });
});

describe('investment page', () => {
  it('shows the header, balances, the schedule and the statement with the balances after each line', async () => {
    const html = await page(cashier, <InvestmentView inv={inv} />, seeded);
    expect(html).toContain('IV000001');
    expect(html).toContain('Test Investor 01 (M000001)');
    expect(html).toContain('12.5% a year');
    expect(html).toContain('UGX 10,417');
    expect(html).toContain('Pay return due');
    expect(html).toContain('Early withdrawal');
    expect(html).toMatch(/class="table-wrap" tabindex="0" role="region"/);
    expect(html).toContain('UGX 62,500');
    expect(html).toContain('VC-BR1-000001');
    expect(html).toContain('After it: principal UGX 1,000,000, return payable UGX 0');
    expect(html).toContain('aria-label="Reverse Return paid VC-BR1-000001"');
    expect(html).not.toContain('aria-label="Reverse Return accrued');
    expect(html).toContain('Certificate and statement to print');
    expect(html).not.toContain('style=');
  });

  it('offers a read-only user nothing that moves money', async () => {
    const html = await page(viewer, <InvestmentView inv={inv} />, seeded);
    expect(html).not.toContain('Pay return due');
    expect(html).not.toContain('>Reverse<');
  });

  it('hides the pages from a user without lending.investments.read', async () => {
    const html = await page(me(['lending.loans.read']), <InvestmentsGate title="Investments"><p>SECRET-CONTENT</p></InvestmentsGate>);
    expect(html).toContain('You do not have access');
    expect(html).not.toContain('SECRET-CONTENT');
  });
});

describe('maturities', () => {
  const data: InvestmentMaturities = {
    currency: 'UGX',
    as_of: '2026-10-07',
    ladder: [
      { bucket: 'overdue', count: 1, principal_minor: 4000000, return_minor: 120000, total_minor: 4120000 },
      { bucket: '0_7', count: 1, principal_minor: 1000000, return_minor: 30000, total_minor: 1030000 },
      { bucket: '8_30', count: 0, principal_minor: 0, return_minor: 0, total_minor: 0 },
      { bucket: '31_90', count: 0, principal_minor: 0, return_minor: 0, total_minor: 0 },
    ],
    items: [{ investment_id: ID, account_no: 'IV000009', member_name: 'Test Investor 09', status: 'matured', maturity_date: '2026-10-01', days_to_maturity: -6, total_minor: 4120000 }],
  };

  it('shows what falls due in 7, 30 and 90 days and what waits since maturity', async () => {
    const html = await page(viewer, <><Ladder data={data} /><MaturityRows data={data} /></>);
    expect(html).toContain('Matured, not yet paid');
    expect(html).toContain('Within 7 days');
    expect(html).toContain('31 to 90 days');
    expect(html).toContain('UGX 4,120,000');
    expect(html).toContain('class="iv-due"');
    expect(html).toContain('Matured 2026-10-01');
  });
});

describe('products and subscribe', () => {
  const product: InvestmentProduct = {
    id: '00000000-0000-4000-8000-0000000000d1',
    code: 'FD12',
    name: 'Test fixed deposit',
    currency: 'UGX',
    status: 'active',
    version: 1,
    product_type: 'fixed_term',
    allowed_terms_months: [3, 6, 12],
    return_rate_bp: 1200,
    return_method: 'flat',
    payout_frequency: 'at_maturity',
    min_amount_minor: 500000,
    max_amount_minor: 50000000,
    early_withdrawal_allowed: false,
    early_withdrawal_penalty_bp: 0,
  };

  it('turns the product form into terms in basis points and minor units, and refuses what the server would', () => {
    const terms = termsOf({ ...EMPTY, name: 'X', rate: '12.5', min: '500,000', terms: '3, 6' }, 'UGX');
    expect(terms).toMatchObject({ return_rate_bp: 1250, min_amount_minor: 500000, allowed_terms_months: [3, 6] });
    expect(termsOf({ ...EMPTY, rate: '12', min: '1000', method: 'compound', payout: 'monthly' }, 'UGX')).toContain('at maturity');
    expect(draftOf(product)).toMatchObject({ rate: '12', min: '500,000', terms: '3, 6, 12' });
  });

  it('shows a product with its terms in words', async () => {
    const html = await page(viewer, <ul><ProductCard p={product} /></ul>);
    expect(html).toContain('FD12 Test fixed deposit');
    expect(html).toContain('12% a year flat, paid at maturity');
    expect(html).toContain('not allowed');
  });

  it('has labelled inputs for the subscribe form', async () => {
    const html = await page(officer, <SubscribeForm member={{ id: 'm1', label: 'Test Investor 01 (M000001)' }} products={[product]} />);
    expect(html).toContain('for="iv-product"');
    expect(html).toContain('for="iv-amount"');
    expect(html).toContain('for="iv-instruction"');
    expect(html).toContain('12 months');
    expect(html).toContain('Open the investment');
  });

  it('prints the certificate with its terms', async () => {
    const c: InvestmentCertificate = {
      certificate_no: 'IC000001', tenant_name: 'Test Tenant', branch_name: 'Test Branch A', account_no: 'IV000001', member_no: 'M000001',
      member_name: 'Test Investor 01', product_name: 'Test fixed deposit', currency: 'UGX', principal_minor: 1000000, return_rate_bp: 1200,
      return_method: 'flat', payout_frequency: 'at_maturity', term_months: 12, start_date: '2026-01-01', maturity_date: '2027-01-01',
      agreed_return_minor: 120000, maturity_value_minor: 1120000, early_withdrawal_allowed: false, early_withdrawal_penalty_bp: 0, issued_on: '2026-10-07',
    };
    const html = await page(viewer, <CertificateCard c={c} />);
    expect(html).toContain('Investment certificate IC000001');
    expect(html).toContain('UGX 1,120,000');
  });
});

describe('routes', () => {
  it('registers every investment screen', () => {
    const ids = Object.keys(router.routesById);
    for (const id of [
      '/staff/lending/investments',
      '/staff/lending/investments/new',
      '/staff/lending/investments/maturities',
      '/staff/lending/investments/products',
      '/staff/lending/investments/member/$memberId',
      '/staff/lending/investments/$investmentId',
      '/staff/lending/investments/$investmentId/certificate',
    ]) {
      expect(ids).toContain(id);
    }
  });
});
