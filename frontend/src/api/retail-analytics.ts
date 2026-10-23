import { api } from './client';
import { unwrap } from './retail';
import type { components } from './schema';

// The retail analytics reports (issue #149), typed by the generated schema. Read only. Cost, profit
// and margin fields are absent from a body for a caller without retail.profit.read.

type S = components['schemas'];

export type SalesAnalysis = S['RetailSalesAnalysis'];
export type AnalysisRow = S['RetailSalesAnalysisRow'];
export type StockedItem = S['RetailStockedItem'];
export type StockHealth = S['RetailStockHealth'];
export type CoverRow = S['RetailCoverRow'];
/** Slow movers on Stock health (issue #414): money tied up is absent without retail.profit.read in the row's branch. */
export type SlowMovers = S['RetailSlowMovers'];
export type SlowMover = S['RetailSlowMover'];
export type CreditControl = S['RetailCreditControl'];
export type Ageing = S['RetailAgeing'];
/** Customer accounts (issue #196): ageing per credit buyer with a record, with the limit and the terms. */
export type CustomerAgeing = S['RetailCustomerAgeing'];
export type AgeingCustomer = S['RetailCustomerAgeingRow'];
export type Evaluation = S['RetailBusinessEvaluation'];
export type EvaluationBranch = S['RetailEvaluationBranch'];
export type Dashboard = S['RetailDashboard'];
/** The shop Home summary (issue #485): every part is absent for a caller who may not see it. */
export type HomeSummary = S['RetailHomeSummary'];
export type HomeTopItem = S['RetailHomeTopItem'];
export type Margins = S['RetailMargins'];
export type MarginRow = S['RetailMarginRow'];
export type PriceChangeImpact = S['RetailPriceChangeImpact'];

export interface SalesAnalysisQuery {
  /** One branch; absent means every branch the caller may read. */
  branchId?: string;
  from: string;
  to: string;
  group: 'day' | 'week' | 'month';
  top: number;
  slowDays: number;
}

export const retailAnalytics = {
  async salesAnalysis({ branchId, from, to, group, top, slowDays }: SalesAnalysisQuery): Promise<SalesAnalysis> {
    return unwrap(
      await api.GET('/api/v1/retail/reports/sales-analysis', {
        params: { query: { branch_id: branchId ? [branchId] : undefined, from, to, group, top, slow_days: slowDays } },
      }),
    );
  },
  async margins({ branchId, from, to, top, targetBp }: { branchId?: string; from: string; to: string; top: number; targetBp: number }): Promise<Margins> {
    return unwrap(
      await api.GET('/api/v1/retail/reports/margins', {
        params: { query: { branch_id: branchId ? [branchId] : undefined, from, to, top, target_bp: targetBp } },
      }),
    );
  },
  async stockHealth({ branchId, from, to, top, leadDays, coverDays }: { branchId?: string; from: string; to: string; top: number; leadDays: number; coverDays: number }): Promise<StockHealth> {
    return unwrap(
      await api.GET('/api/v1/retail/reports/stock-health', {
        params: { query: { branch_id: branchId ? [branchId] : undefined, from, to, top, lead_days: leadDays, cover_days: coverDays } },
      }),
    );
  },
  async creditControl({ branchId, from, to, top, sort }: { branchId?: string; from: string; to: string; top: number; sort: 'amount' | 'age' }): Promise<CreditControl> {
    return unwrap(
      await api.GET('/api/v1/retail/reports/credit-control', {
        params: { query: { branch_id: branchId ? [branchId] : undefined, from, to, top, sort } },
      }),
    );
  },
  async customerAgeing({ branchId, top }: { branchId?: string; top: number }): Promise<CustomerAgeing> {
    return unwrap(
      await api.GET('/api/v1/retail/reports/customer-ageing', {
        params: { query: { branch_id: branchId ? [branchId] : undefined, top } },
      }),
    );
  },
  async evaluation({ branchId, from, to, top }: { branchId?: string; from: string; to: string; top: number }): Promise<Evaluation> {
    return unwrap(
      await api.GET('/api/v1/retail/reports/business-evaluation', {
        params: { query: { branch_id: branchId ? [branchId] : undefined, from, to, top } },
      }),
    );
  },
  /** With a period, or period custom with from and to, the body has the range of issue #428. */
  async dashboard({ branchId, period, from, to }: { branchId?: string; period?: 'today' | '7d' | '30d' | 'ytd' | '1y' | 'all' | 'custom'; from?: string; to?: string }): Promise<Dashboard> {
    return unwrap(await api.GET('/api/v1/retail/reports/dashboard', { params: { query: { branch_id: branchId ? [branchId] : undefined, period, from, to } } }));
  },
  /** Everything the Shop cards on Home show, in one read (issue #485, ADR-076). */
  async home(branchId: string | undefined, signal?: AbortSignal): Promise<HomeSummary> {
    return unwrap(await api.GET('/api/v1/retail/reports/home', { params: { query: { branch_id: branchId ? [branchId] : undefined } }, signal }));
  },
};
