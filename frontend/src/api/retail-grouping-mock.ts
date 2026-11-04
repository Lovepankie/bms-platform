import { NO_SUPPLIER, type Purchase, type PurchaseLevel, type PurchasesSummary, type PurchasesSummaryGroup, type PurchasesSummaryQuery, type StockMovement, type StockSummary, type StockSummaryGroup, type StockSummaryQuery, type UsageSummary, type UsageSummaryGroup, type UsageSummaryQuery } from './retail';

// The mock of GET /retail/purchases/summary and GET /retail/stock/summary (issue #350, docs/sdd/07-api-design.md section 7.11.20):
// the same rules as the server. A voided restock is only counted apart, what was bought at cost exists only for a session holding
// retail.profit.read, groups are bounded and the rest is reached by next_to (year, month, day) or next_offset.

const BOUND: Record<PurchaseLevel, number> = { year: 20, month: 12, day: 31, branch: 100, supplier: 50, category: 50, item: 50, variant: 50 };
const milli = (qty: string | undefined) => Math.round(Number(qty ?? '0') * 1000);

/** What the mock knows of the world around a restock. */
export interface PurchasesContext {
  cost: boolean;
  branchName: (id: string | undefined) => string;
  supplierName: (id: string | undefined) => string;
  /** The product, and the item with sizes and colours it is a variant of (issue #384). */
  product: (id: string | undefined) => { name: string; categoryId: string; categoryName: string; unit: string; familyId?: string; familyName?: string } | undefined;
  /** Whether the tenant has an item with sizes and colours: only then is the variant level offered. */
  variants?: boolean;
}

interface Entry {
  purchase: Purchase;
  productId: string;
  branchId: string;
  qty: number;
  value: number;
}

export function summarisePurchases(purchases: Purchase[], q: PurchasesSummaryQuery, ctx: PurchasesContext): PurchasesSummary {
  const at = q.chain.indexOf(q.level);
  const next = q.chain[at + 1];
  const entries: Entry[] = purchases.flatMap((purchase) =>
    (purchase.lines ?? []).flatMap((l) =>
      (l.qty_by_branch ?? []).map((b) => ({ purchase, productId: l.product_id ?? '', branchId: b.branch_id ?? '', qty: milli(b.qty), value: Math.round((milli(b.qty) * (l.cost_minor ?? 0)) / 1000) })),
    ),
  ).filter((e) => {
    const day = e.purchase.purchased_on ?? '';
    const supplier = e.purchase.supplier_id ?? NO_SUPPLIER;
    return (!q.status || (q.status === 'voided') === (e.purchase.status === 'voided'))
      && (!q.branchId || e.branchId === q.branchId) && (!q.from || day >= q.from) && (!q.to || day <= q.to)
      && (!q.supplierId || supplier === q.supplierId) && (!q.itemId || e.productId === q.itemId || ctx.product(e.productId)?.familyId === q.itemId)
      && (!q.categoryId || ctx.product(e.productId)?.categoryId === q.categoryId);
  });
  const keyOf = (e: Entry): { key: string; label: string; from?: string; to?: string } => {
    const date = e.purchase.purchased_on ?? '';
    switch (q.level) {
      case 'branch': return { key: e.branchId, label: ctx.branchName(e.branchId) };
      case 'supplier': return { key: e.purchase.supplier_id ?? NO_SUPPLIER, label: e.purchase.supplier_id ? ctx.supplierName(e.purchase.supplier_id) : 'No supplier' };
      case 'category': { const p = ctx.product(e.productId); return { key: p?.categoryId ?? 'unknown', label: p?.categoryName ?? 'Unknown' }; }
      case 'item': { const p = ctx.product(e.productId); return p?.familyId ? { key: p.familyId, label: p.familyName ?? p.name } : { key: e.productId, label: p?.name ?? 'Unknown' }; }
      case 'variant': return { key: e.productId, label: ctx.product(e.productId)?.name ?? 'Unknown' };
      case 'year': return { key: date.slice(0, 4), label: date.slice(0, 4), from: `${date.slice(0, 4)}-01-01`, to: `${date.slice(0, 4)}-12-31` };
      case 'month': return { key: date.slice(0, 7), label: date.slice(0, 7), from: `${date.slice(0, 7)}-01`, to: `${date.slice(0, 7)}-${new Date(Date.UTC(Number(date.slice(0, 4)), Number(date.slice(5, 7)), 0)).getUTCDate()}` };
      default: return { key: date, label: date, from: date, to: date };
    }
  };
  const groups = new Map<string, PurchasesSummaryGroup & { sold: number; live: Set<string>; voided: Set<string> }>();
  // An item with sizes is keyed by its own id, which is not a product: its unit is the unit of any of its variants.
  const units = new Map<string, string>();
  for (const e of entries) units.set(keyOf(e).key, ctx.product(e.productId)?.unit ?? '');
  const unitOf = (key: string | undefined) => units.get(key ?? '') ?? '';
  for (const e of entries) {
    const k = keyOf(e);
    const g = groups.get(k.key) ?? {
      key: k.key, label: k.label, ...(k.from ? { period: k.key, from: k.from, to: k.to } : {}),
      purchases_count: 0, voided_count: 0, ...(ctx.cost ? { total_minor: 0 } : {}), sold: 0, live: new Set<string>(), voided: new Set<string>(),
    };
    groups.set(k.key, g);
    const id = e.purchase.id ?? '';
    if (e.purchase.status === 'voided') {
      g.voided.add(id);
      continue;
    }
    g.live.add(id);
    g.sold += e.qty;
    if (ctx.cost) g.total_minor = (g.total_minor ?? 0) + e.value;
  }
  const time = q.level === 'year' || q.level === 'month' || q.level === 'day';
  const all = [...groups.values()].sort((a, b) => (time ? (b.key ?? '').localeCompare(a.key ?? '') : b.live.size - a.live.size || (a.key ?? '').localeCompare(b.key ?? '')));
  const offset = time ? 0 : (q.offset ?? 0);
  const size = BOUND[q.level];
  const page = all.slice(offset, offset + size).map(({ sold, live, voided, ...g }) => ({
    ...g, purchases_count: live.size, voided_count: voided.size,
    ...(q.level === 'item' || q.level === 'variant' ? { quantity: String(sold / 1000), unit: unitOf(g.key) } : {}),
  }));
  const more = all.length > offset + size;
  const oldest = page.at(-1)?.from;
  const nextTo = more && time && oldest ? new Date(Date.parse(`${oldest}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10) : undefined;
  return {
    group_by: q.level, levels: q.chain, ...(next ? { next_level: next } : {}), available_levels: ['branch', 'year', 'month', 'supplier', 'day', 'category', 'item', ...(ctx.variants ? ['variant'] : [])],
    currency: 'UGX', items: page, has_more: more, ...(nextTo ? { next_to: nextTo } : {}), ...(more && !time ? { next_offset: offset + size } : {}),
  };
}

// JUSTIFICATION-A3: the mock of the new GET /retail/usage/summary route (issue #816), the shape of its two neighbours here.
/** What the mock knows around a usage or damage movement (issue #816). */
export interface UsageContext {
  cost: boolean;
  branchName: (id: string | undefined) => string;
  product: (id: string | undefined) => { name: string; categoryId: string; categoryName: string; unit: string } | undefined;
  /** The business day of a movement, as the list's from and to read it. */
  day: (m: StockMovement) => string;
}

/** The mock of GET /retail/usage/summary (issue #816): the movements of the list grouped, each record's cost summed as it shows. */
export function summariseUsage(movements: StockMovement[], q: UsageSummaryQuery, ctx: UsageContext): UsageSummary {
  const next = q.chain[q.chain.indexOf(q.level) + 1];
  const rows = movements.filter((m) => {
    const day = ctx.day(m);
    return (m.kind === 'usage' || m.kind === 'damage') && (!q.branchId || m.branch_id === q.branchId) && (!q.from || day >= q.from) && (!q.to || day <= q.to)
      && (!q.itemId || m.product_id === q.itemId) && (!q.categoryId || ctx.product(m.product_id)?.categoryId === q.categoryId);
  });
  const groups = new Map<string, UsageSummaryGroup & { qty: number }>();
  for (const m of rows) {
    const day = ctx.day(m);
    const p = ctx.product(m.product_id);
    const month = day.slice(0, 7);
    const k = q.level === 'branch' ? { key: m.branch_id ?? '', label: ctx.branchName(m.branch_id) }
      : q.level === 'category' ? { key: p?.categoryId ?? 'unknown', label: p?.categoryName ?? 'Unknown' }
        : q.level === 'item' ? { key: m.product_id ?? '', label: p?.name ?? 'Unknown', unit: p?.unit ?? '' }
          : { key: month, label: month, from: `${month}-01`, to: `${month}-${new Date(Date.UTC(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 0)).getUTCDate()}` };
    const g = groups.get(k.key) ?? { ...k, records_count: 0, used_count: 0, damaged_count: 0, ...(ctx.cost ? { cost_minor: 0 } : {}), qty: 0 };
    g.records_count = (g.records_count ?? 0) + 1;
    if (m.kind === 'usage') g.used_count = (g.used_count ?? 0) + 1;
    else g.damaged_count = (g.damaged_count ?? 0) + 1;
    if (ctx.cost) g.cost_minor = (g.cost_minor ?? 0) + (m.line_cost_minor ?? 0);
    g.qty += Math.abs(milli(m.qty));
    groups.set(k.key, g);
  }
  const time = q.level === 'month';
  const all = [...groups.values()].sort((a, b) => (time ? (b.key ?? '').localeCompare(a.key ?? '') : (a.label ?? '').localeCompare(b.label ?? '')));
  const size = time ? 12 : 100;
  const offset = time ? 0 : (q.offset ?? 0);
  const page = all.slice(offset, offset + size).map(({ qty, unit, ...g }) => (q.level === 'item' ? { ...g, quantity: String(qty / 1000), unit } : g));
  const more = all.length > offset + size;
  const oldest = page.at(-1)?.from;
  const nextTo = more && time && oldest ? new Date(Date.parse(`${oldest}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10) : undefined;
  return {
    group_by: q.level, levels: q.chain, ...(next ? { next_level: next } : {}), available_levels: ['branch', 'month', 'category', 'item'],
    currency: 'UGX', items: page, has_more: more, ...(nextTo ? { next_to: nextTo } : {}), ...(more && !time ? { next_offset: offset + size } : {}),
  };
}

/** What the mock knows of the stock: the products the search and category kept, the branches, and each balance in thousandths. */
export interface StockContext {
  cost: boolean;
  branches: { id: string; name: string }[];
  products: { id: string; category: string; categoryId: string; sellMinor: number; costMinor: number }[];
  balance: (branchId: string, productId: string) => number;
  /** The stock-level tab of the list: out or low, on a quantity in thousandths. */
  keepQty: (milliQty: number) => boolean;
}

export function summariseStock(q: StockSummaryQuery, ctx: StockContext): StockSummary {
  const at = q.chain.indexOf(q.level);
  const next = q.chain[at + 1];
  const branches = ctx.branches.filter((b) => !q.branchId || b.id === q.branchId);
  const perRow = q.level === 'branch' || branches.length === 1;
  const groups = new Map<string, { label: string; items: Set<string>; negative: Set<string>; value: number; cost: number }>();
  for (const p of ctx.products.filter((x) => !q.categoryId || x.categoryId === q.categoryId)) {
    const total = branches.reduce((n, b) => n + ctx.balance(b.id, p.id), 0);
    const anyNegative = branches.some((b) => ctx.balance(b.id, p.id) < 0);
    if (!perRow && ((q.negativeOnly && !anyNegative) || !ctx.keepQty(total))) continue;
    for (const b of branches) {
      const qty = ctx.balance(b.id, p.id);
      if (perRow && ((q.negativeOnly && qty >= 0) || !ctx.keepQty(qty))) continue;
      const key = q.level === 'branch' ? b.id : p.categoryId;
      const g = groups.get(key) ?? { label: q.level === 'branch' ? b.name : p.category, items: new Set<string>(), negative: new Set<string>(), value: 0, cost: 0 };
      groups.set(key, g);
      g.items.add(p.id);
      if (qty < 0) g.negative.add(p.id);
      g.value += Math.round((qty * p.sellMinor) / 1000);
      g.cost += Math.round((qty * p.costMinor) / 1000);
    }
  }
  const all = [...groups.entries()].sort(([, a], [, b]) => a.label.localeCompare(b.label));
  const offset = q.offset ?? 0;
  const items: StockSummaryGroup[] = all.slice(offset, offset + 100).map(([key, g]) => ({
    key, label: g.label, items_count: g.items.size, negative_count: g.negative.size, ...(ctx.cost ? { value_minor: g.value, cost_minor: g.cost } : {}),
  }));
  const more = all.length > offset + 100;
  return { group_by: q.level, levels: q.chain, ...(next ? { next_level: next } : {}), available_levels: ['branch', 'category'], currency: 'UGX', items, has_more: more, ...(more ? { next_offset: offset + 100 } : {}) };
}
