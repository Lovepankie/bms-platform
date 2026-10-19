import type { Sale, SalesSummary, SalesSummaryGroup } from './retail';

// The mock of GET /retail/sales/summary (issue #328, docs/sdd/07-api-design.md section 7.11.20): the same rules as the
// server, over the mock's in-memory sales. Completed sales make the figures, voided ones are only counted apart,
// profit exists only for a session holding retail.profit.read, periods come newest first and are bounded.

const LIMIT = { year: 20, month: 12, day: 31 } as const;
const METHODS = ['cash', 'mobile_money', 'bank', 'credit'] as const;

const pad = (n: number) => String(n).padStart(2, '0');

/** The first and last day of the period of `date` at `level`, and its key. */
function periodOf(date: string, level: 'year' | 'month' | 'day'): { key: string; from: string; to: string } {
  const [y = '', m = '', d = ''] = date.split('-');
  if (level === 'year') return { key: y, from: `${y}-01-01`, to: `${y}-12-31` };
  if (level === 'day') return { key: `${y}-${m}-${d}`, from: date, to: date };
  const last = new Date(Date.UTC(Number(y), Number(m), 0)).getUTCDate();
  return { key: `${y}-${m}`, from: `${y}-${m}-01`, to: `${y}-${m}-${pad(last)}` };
}

export function summariseSales(sales: (Sale & { costTotal?: number })[], level: 'year' | 'month' | 'day', profit: boolean): SalesSummary {
  const groups = new Map<string, SalesSummaryGroup>();
  for (const s of sales) {
    const p = periodOf(s.sale_date ?? '', level);
    const g = groups.get(p.key) ?? {
      period: p.key, from: p.from, to: p.to, sales_count: 0, total_minor: 0, voided_count: 0,
      ...(profit ? { profit_minor: 0 } : {}),
      by_method: Object.fromEntries(METHODS.map((m) => [m, { count: 0, total_minor: 0 }])),
    };
    groups.set(p.key, g);
    if (s.status === 'voided') {
      g.voided_count = (g.voided_count ?? 0) + 1;
      continue;
    }
    g.sales_count = (g.sales_count ?? 0) + 1;
    g.total_minor = (g.total_minor ?? 0) + (s.total_minor ?? 0);
    if (profit) g.profit_minor = (g.profit_minor ?? 0) + (s.profit_minor ?? 0);
    const m = g.by_method?.[s.payment_method as (typeof METHODS)[number]];
    if (m) {
      m.count = (m.count ?? 0) + 1;
      m.total_minor = (m.total_minor ?? 0) + (s.total_minor ?? 0);
    }
  }
  const all = [...groups.values()].sort((a, b) => (b.period ?? '').localeCompare(a.period ?? ''));
  const items = all.slice(0, LIMIT[level]);
  const oldest = items.at(-1)?.from;
  const more = all.length > items.length && oldest !== undefined;
  const nextTo = more ? new Date(Date.parse(`${oldest}T00:00:00Z`) - 86_400_000).toISOString().slice(0, 10) : undefined;
  return { group_by: level, currency: 'UGX', items, has_more: more, ...(nextTo ? { next_to: nextTo } : {}) };
}
