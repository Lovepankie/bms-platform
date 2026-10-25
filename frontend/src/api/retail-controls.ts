import { api } from './client';
import { unwrap } from './retail';
import type { components } from './schema';

// The Controls page API (issue #357, group 1; ADR-047), typed by the generated schema. Read only. Values are sale prices;
// the below-cost card exists only for a caller who may read profit in every branch shown.

type S = components['schemas'];

export type ControlsSummary = S['RetailControls'];
export type ControlsCard = S['RetailControlsCard'];
export type ControlsPeriod = S['RetailControlsPeriod'];
export type ControlsGroups = S['RetailControlsGroups'];
export type ControlsGroup = S['RetailControlsGroup'];
export type ControlsRecords = S['RetailControlsRecords'];
export type ControlsRecord = S['RetailControlsRecord'];
export type ControlsPatterns = S['RetailControlsPatterns'];
export type ControlsPattern = S['RetailControlsPattern'];

export type Metric = 'voided_sales' | 'quick_cancels' | 'returns' | 'credit_writeoffs' | 'price_overrides' | 'discounts' | 'below_cost' | 'restock_voids' | 'quantity_corrections';

/** What the four routes share: the days, the shop (absent means every shop in scope) and the quick-cancel window. */
export interface ControlsRange {
  branchId?: string;
  from: string;
  to: string;
  withinMinutes?: number;
}

/** What a group or a record list is narrowed to by the groups opened above it. */
export interface ControlsNarrow {
  /** The person whose sales they were. */
  userId?: string;
  /** Who cancelled the sale or took the goods back. */
  approverId?: string;
  reason?: string;
}

/** A range narrowed by the groups opened above. */
export type ControlsNarrowedRange = ControlsRange & ControlsNarrow;

const common = ({ branchId, from, to, withinMinutes }: ControlsRange) => ({
  branch_id: branchId ? [branchId] : undefined,
  from,
  to,
  within_minutes: withinMinutes,
});

export const retailControls = {
  async summary(range: ControlsRange, signal?: AbortSignal): Promise<ControlsSummary> {
    return unwrap(await api.GET('/api/v1/retail/controls/summary', { params: { query: common(range) }, signal }));
  },
  async groups(
    metric: Metric,
    chain: string[],
    level: string,
    range: ControlsNarrowedRange,
    offset: number,
    signal?: AbortSignal,
  ): Promise<ControlsGroups> {
    return unwrap(
      await api.GET('/api/v1/retail/controls/groups', {
        params: { query: { metric, group_by: chain.join(','), level, ...common(range), user_id: range.userId, approver_id: range.approverId, reason: range.reason, offset } },
        signal,
      }),
    );
  },
  async records(metric: Metric, range: ControlsNarrowedRange, offset: number, signal?: AbortSignal): Promise<ControlsRecords> {
    return unwrap(
      await api.GET('/api/v1/retail/controls/records', {
        params: { query: { metric, ...common(range), user_id: range.userId, approver_id: range.approverId, reason: range.reason, offset } },
        signal,
      }),
    );
  },
  async patterns(range: ControlsRange, signal?: AbortSignal): Promise<ControlsPatterns> {
    return unwrap(await api.GET('/api/v1/retail/controls/patterns', { params: { query: { branch_id: range.branchId ? [range.branchId] : undefined, from: range.from, to: range.to } }, signal }));
  },
};
