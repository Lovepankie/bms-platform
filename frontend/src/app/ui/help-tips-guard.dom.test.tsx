// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { CollateralApi } from '../../api/collateral';
import { LendingApiContext, type LendingApis } from '../../api/lending-context';
import type { LoanSchedule } from '../../api/lending';
import type { LoanAppraisal } from '../../api/loan-applications';
import type { AllBranchesStock, StockRow } from '../../api/retail';
import { mockMe } from '../../api/retail-mock';
import { StaffContext } from '../../areas/staff/context';
import { AppraisalCard } from '../../areas/staff/lending/application-view';
import { ScheduleTable } from '../../areas/staff/lending/loan';
import { MemberCollateral } from '../../areas/staff/lending/member-page';
import { GroupCard } from '../../areas/staff/retail/customer-groups';
import { AllBranchesList, AllBranchesTable, StockTable } from '../../areas/staff/retail/stock';
import { HELP_TIPS, type HelpTipKey } from '../help-tips';

// The help tips guard (issue #707): every label the owner found confusing renders a HelpTip, as many times as it shows. The
// parts that can be drawn on their own are drawn here and their tip buttons counted by name; a label that needs its whole
// screen is drawn by that screen's own test, named in ELSEWHERE, which looks for the button by the same name. The first test
// proves no tip in help-tips.ts is left unproven. The labels on Add an item, the item's packages and Count stock wait for pull
// request #710 to merge. Fabricated names and figures only.

// The tests run from frontend/; under jsdom import.meta.url is not a file path.
const SRC = `${process.cwd()}/src/`;

const ELSEWHERE: Partial<Record<HelpTipKey, string>> = {
  lowStockLevel: 'areas/staff/retail/stock-minimum.dom.test.tsx',
  onCredit: 'areas/staff/retail/credit-recognition.test.tsx',
  joinSizes: 'areas/staff/retail/catalogue-variants.dom.test.tsx',
  countItNow: 'areas/staff/retail/next-steps.dom.test.tsx',
  saveCount: 'areas/staff/retail/stocktake.dom.test.tsx',
  itemSizes: 'areas/staff/retail/new-item-sizes.dom.test.tsx',
  guarantor: 'areas/staff/lending/application.dom.test.tsx',
  inArrears: 'areas/staff/home-cards.dom.test.tsx',
  // Issue #709 (ADR-110): on the Units page's two parts, and on the item forms and the ladder.
  singleUnit: 'areas/staff/retail/units-sections.dom.test.tsx',
  package: 'areas/staff/retail/units-sections.dom.test.tsx',
  // Issue #798: the words the rule 3 audit of #336 left without a tip.
  tokensADay: 'areas/staff/rule-three.dom.test.tsx',
  kyc: 'areas/staff/rule-three.dom.test.tsx',
  portfolioAtRisk: 'areas/staff/rule-three.dom.test.tsx',
  postedLinesOnly: 'areas/staff/rule-three.dom.test.tsx',
};
const HERE: HelpTipKey[] = ['averageBuyingPrice', 'sellingPrice', 'priceList', 'appraisal', 'outstanding', 'collateral'];

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const tips = (tip: HelpTipKey) => screen.queryAllByRole('button', { name: `More about ${HELP_TIPS[tip].label}` });

function screenAt(phone: boolean) {
  vi.stubGlobal('matchMedia', (media: string) => ({ matches: phone && media === '(max-width: 719px)', media, addEventListener: () => undefined, removeEventListener: () => undefined }));
}

function show(node: ReactNode) {
  const me = mockMe('admin');
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const rows: StockRow[] = [
  { product_id: 'p1', code: 'TE-1', description: 'Test lamp 01', category: 'Test lights', unit: 'piece', qty: '3.000', negative: false, sell_minor: 8000, cost_minor: 6000 },
  { product_id: 'p2', code: 'TE-2', description: 'Test lamp 02', category: 'Test lights', unit: 'piece', qty: '4.000', negative: false, sell_minor: 9000, cost_minor: 7000 },
];
const everywhere = {
  branches: [{ id: 'b1', code: 'BR1', name: 'Test Branch A' }],
  items: [{ product_id: 'p1', description: 'Test lamp 01', unit: 'piece', total_qty: '3.000', sell_minor: 8000, cost_minor: 6000, balances: [{ branch_id: 'b1', qty: '3.000' }] }],
} as AllBranchesStock;

describe('the help tips guard', () => {
  it('proves every tip is drawn, here or on its screen', () => {
    expect([...HERE, ...Object.keys(ELSEWHERE)].sort()).toEqual(Object.keys(HELP_TIPS).sort());
  });

  for (const [tip, file] of Object.entries(ELSEWHERE)) {
    it(`has ${file} look for the tip on ${HELP_TIPS[tip as HelpTipKey].label}`, () => {
      expect(readFileSync(`${SRC}${file}`, 'utf8')).toContain(`More about ${HELP_TIPS[tip as HelpTipKey].label}`);
    });
  }

  it('draws one tip on each price header of both My stock tables, once and not per row', () => {
    screenAt(false);
    show(<><StockTable rows={rows} showCost flat /><AllBranchesTable data={everywhere} showCost flat /></>);
    expect(tips('averageBuyingPrice')).toHaveLength(2);
    expect(tips('sellingPrice')).toHaveLength(2);
    // The column keeps its own name: the cells are not read with the tip's button.
    expect(screen.getAllByRole('columnheader', { name: 'Average buying price' })).toHaveLength(2);
  });

  it('draws the same two price tips once above each list of cards on a phone', () => {
    screenAt(true);
    show(<><StockTable rows={rows} showCost flat /><AllBranchesList data={everywhere} showCost flat /></>);
    expect(tips('averageBuyingPrice')).toHaveLength(2);
    expect(tips('sellingPrice')).toHaveLength(2);
    // Without the buying price, only Selling price.
    show(<StockTable rows={rows} showCost={false} flat />);
    expect(tips('averageBuyingPrice')).toHaveLength(2);
    expect(tips('sellingPrice')).toHaveLength(3);
  });

  it('draws the tip beside Price list on a customer group being edited', () => {
    render(<GroupCard group={{ id: 'g1', name: 'Test Group 01', active: true, customer_count: 0, version: 1 }} lists={[{ id: 'l1', name: 'Test list', active: true, version: 1 }]} listsLoad={{ isError: false, refetch: () => undefined, data: [] }} onSave={() => undefined} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit Test Group 01' }));
    expect(tips('priceList')).toHaveLength(1);
  });

  it('draws the tip beside the Appraisal heading, outside its name', () => {
    const appraisal = { score: 72, band: 'B', recommendation: 'approve', flags: [] } as unknown as LoanAppraisal;
    show(<AppraisalCard appraisal={appraisal} currency="UGX" />);
    expect(tips('appraisal')).toHaveLength(1);
    expect(screen.getByRole('heading', { name: 'Appraisal' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Appraisal' })).toBeTruthy();
  });

  it('draws the tip on the Outstanding column of a loan schedule', () => {
    const schedule = { currency: 'UGX', items: [{ no: 1, due_date: '2026-11-01', principal_due_minor: 100, interest_due_minor: 10, fees_due_minor: 0, total_due_minor: 110, total_paid_minor: 0, outstanding_minor: 110, status: 'due' }] } as LoanSchedule;
    show(<ScheduleTable schedule={schedule} currency="UGX" />);
    expect(tips('outstanding')).toHaveLength(1);
    expect(screen.getByRole('columnheader', { name: 'Outstanding' })).toBeTruthy();
  });

  it('draws the tip beside the Collateral heading of a member', () => {
    const collateral = { list: () => Promise.resolve({ items: [] }) } as unknown as CollateralApi;
    render(
      <QueryClientProvider client={new QueryClient()}>
        <LendingApiContext.Provider value={{ collateral } as unknown as LendingApis}>
          <MemberCollateral memberId="m1" canAdd={false} />
        </LendingApiContext.Provider>
      </QueryClientProvider>,
    );
    expect(tips('collateral')).toHaveLength(1);
    expect(screen.getByRole('heading', { name: 'Collateral' })).toBeTruthy();
  });
});
