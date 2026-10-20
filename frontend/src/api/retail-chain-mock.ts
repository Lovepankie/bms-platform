import type { Sale, SalesSummary, SalesSummaryGroup, SalesSummaryQuery, SummaryLevel } from './retail';
import { summariseSales } from './retail-summary-mock';

// The mock of GET /retail/sales/summary for a chain of levels (issue #341, docs/sdd/07-api-design.md section 7.11.20):
// the same rules as the server. Under a category or an item (or at those levels) the figures are summed over the
// sale lines; every other level sums whole sales. Completed sales make the figures, voided ones are only counted
// apart, profit exists only for a session holding retail.profit.read, groups are bounded and the rest is reached by
// next_to (year, month, day) or next_offset.

const LEVELS: SummaryLevel[] = ['branch', 'year', 'month', 'seller', 'day', 'category', 'item', 'variant'];
const METHODS = ['cash', 'mobile_money', 'bank', 'credit'] as const;
const BOUND: Record<SummaryLevel, number> = { year: 20, month: 12, day: 31, branch: 100, seller: 50, category: 50, item: 50, variant: 50 };

/** What the mock knows of the world around a sale. */
export interface ChainContext {
  profit: boolean;
  branchName: (id: string | undefined) => string;
  sellerName: (id: string | undefined) => string;
  /** The product, and the item with sizes and colours it is a variant of (issue #384). */
  product: (id: string | undefined) => { name: string; categoryId: string; categoryName: string; familyId?: string; familyName?: string } | undefined;
  /** Whether the tenant has an item with sizes and colours: only then is the variant level offered. */
  variants?: boolean;
}

type Line = NonNullable<Sale['lines']>[number];
interface Entry {
  sale: Sale;
  /** The line, when the figures are summed over lines. */
  line?: Line;
}

const milli = (qty: string | undefined) => Math.round(Number(qty ?? '0') * 1000);

export function summariseChain(sales: Sale[], q: SalesSummaryQuery, ctx: ChainContext): SalesSummary {
  const chain = q.chain ?? [q.level];
  const at = chain.indexOf(q.level);
  const next = at >= 0 ? chain[at + 1] : undefined;
  const wrap = (s: SalesSummary): SalesSummary => ({ ...s, group_by: q.level, levels: chain, available_levels: LEVELS.filter((l) => ctx.variants || l !== 'variant'), ...(next ? { next_level: next } : {}) });
  const overLines = q.level === 'category' || q.level === 'item' || q.level === 'variant' || !!q.categoryId || !!q.itemId;
  if (!overLines && (q.level === 'year' || q.level === 'month' || q.level === 'day')) {
    const r = summariseSales(sales, q.level, ctx.profit);
    return wrap({ ...r, items: (r.items ?? []).map((g) => ({ ...g, key: g.period, label: g.period })) });
  }
  const entries: Entry[] = sales.flatMap((sale) => {
    if (!overLines) return [{ sale }];
    return (sale.lines ?? [])
      .filter((l) => (!q.itemId || l.product_id === q.itemId || ctx.product(l.product_id)?.familyId === q.itemId) && (!q.categoryId || ctx.product(l.product_id)?.categoryId === q.categoryId))
      .map((line) => ({ sale, line }));
  });
  const keyOf = (e: Entry): { key: string; label: string; from?: string; to?: string } => {
    const date = e.sale.sale_date ?? '';
    switch (q.level) {
      case 'branch': return { key: e.sale.branch_id ?? '', label: ctx.branchName(e.sale.branch_id) };
      case 'seller': return { key: e.sale.created_by ?? 'unknown', label: ctx.sellerName(e.sale.created_by) };
      case 'category': { const p = ctx.product(e.line?.product_id); return { key: p?.categoryId ?? 'unknown', label: p?.categoryName ?? 'Unknown' }; }
      case 'item': { const p = ctx.product(e.line?.product_id); return p?.familyId ? { key: p.familyId, label: p.familyName ?? p.name } : { key: e.line?.product_id ?? '', label: e.line?.description ?? p?.name ?? 'Unknown' }; }
      case 'variant': return { key: e.line?.product_id ?? '', label: ctx.product(e.line?.product_id)?.name ?? e.line?.description ?? 'Unknown' };
      case 'year': return { key: date.slice(0, 4), label: date.slice(0, 4), from: `${date.slice(0, 4)}-01-01`, to: `${date.slice(0, 4)}-12-31` };
      case 'month': return { key: date.slice(0, 7), label: date.slice(0, 7), from: `${date.slice(0, 7)}-01`, to: `${date.slice(0, 7)}-${new Date(Date.UTC(Number(date.slice(0, 4)), Number(date.slice(5, 7)), 0)).getUTCDate()}` };
      default: return { key: date, label: date, from: date, to: date };
    }
  };
  const groups = new Map<string, SalesSummaryGroup & { sold?: number; sales: Set<string>; voided: Set<string> }>();
  const counted = new Map<string, Record<(typeof METHODS)[number], Set<string>>>();
  for (const e of entries) {
    const k = keyOf(e);
    const g = groups.get(k.key) ?? {
      key: k.key, label: k.label, ...(k.from ? { period: k.key, from: k.from, to: k.to } : {}),
      sales_count: 0, total_minor: 0, voided_count: 0, ...(ctx.profit ? { profit_minor: 0 } : {}),
      by_method: Object.fromEntries(METHODS.map((m) => [m, { count: 0, total_minor: 0 }])),
      sold: 0, sales: new Set<string>(), voided: new Set<string>(),
    };
    groups.set(k.key, g);
    const id = e.sale.id ?? '';
    if (e.sale.status === 'voided') {
      g.voided.add(id);
      continue;
    }
    const amount = e.line ? (e.line.line_total_minor ?? 0) : (e.sale.total_minor ?? 0);
    const cost = e.line ? (e.line.line_cost_minor ?? 0) : (e.sale.total_minor ?? 0) - (e.sale.profit_minor ?? 0);
    g.sales.add(id);
    g.total_minor = (g.total_minor ?? 0) + amount;
    if (ctx.profit) g.profit_minor = (g.profit_minor ?? 0) + amount - cost;
    g.sold = (g.sold ?? 0) + milli(e.line?.qty);
    const m = g.by_method?.[e.sale.payment_method as (typeof METHODS)[number]];
    if (m) {
      m.total_minor = (m.total_minor ?? 0) + amount;
      const seen = counted.get(k.key) ?? { cash: new Set(), mobile_money: new Set(), bank: new Set(), credit: new Set() };
      counted.set(k.key, seen);
      seen[e.sale.payment_method as (typeof METHODS)[number]].add(id);
      m.count = seen[e.sale.payment_method as (typeof METHODS)[number]].size;
    }
  }
  const time = q.level === 'year' || q.level === 'month' || q.level === 'day';
  const all = [...groups.values()].sort((a, b) => (time ? (b.key ?? '').localeCompare(a.key ?? '') : (b.total_minor ?? 0) - (a.total_minor ?? 0) || (a.key ?? '').localeCompare(b.key ?? '')));
  const offset = time ? 0 : (q.offset ?? 0);
  const size = BOUND[q.level];
  const page = all.slice(offset, offset + size).map(({ sold, sales: s, voided, ...g }) => ({
    ...g, sales_count: s.size, voided_count: voided.size, ...(q.level === 'item' ? { quantity: String((sold ?? 0) / 1000) } : {}),
  }));
  const more = all.length > offset + size;
  const oldest = page.at(-1)?.from;
  const nextTo = more && time && oldest ? new Date(Date.parse(`${oldest}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10) : undefined;
  return wrap({
    group_by: q.level, currency: 'UGX', items: page, has_more: more,
    ...(nextTo ? { next_to: nextTo } : {}), ...(more && !time ? { next_offset: offset + size } : {}),
  });
}
