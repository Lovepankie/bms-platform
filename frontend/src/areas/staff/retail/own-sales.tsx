import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, daysBefore, retail, type SalesSummaryGroup } from '../../../api/retail';
import { useStaff } from '../context';
import { ChangeLine, ChartLegend, RangeChart, RangePicker, type ChartPoint } from './range-ui';
import { daysBetween, previousWords, rangeDays, rangeTitle, readRange, shortDate, stepWords, writeRange, type RangeChoice, type RangeDays } from './range-state';
import { money } from './ui';

// A seller's own figures on the Retail home (issue #399, ADR-055), over the range of issue #428. The server counts only the sales
// this person made (the sales summary of someone without retail.sale.all_sellers), so nothing here is anyone else's, and no cost,
// profit or stock value is ever in the answer. The summary is read by day up to 31 days, by month up to a year, by year for All.

export type OwnLevel = 'day' | 'month' | 'year';

export function ownLevel(days: RangeDays): OwnLevel {
  if (!days.from) return 'year';
  return daysBetween(days.from, days.to) <= 31 ? 'day' : 'month';
}

/** The first day of each step from one day to another: every day, or the first of every month. */
export function ownSteps(level: OwnLevel, from: string, to: string): string[] {
  const out: string[] = [];
  if (level === 'day') {
    for (let d = to; d >= from; d = daysBefore(d, 1)) out.unshift(d);
    return out;
  }
  let y = Number(from.slice(0, 4));
  let m = Number(from.slice(5, 7));
  const end = to.slice(0, 7);
  for (;;) {
    const key = `${y}-${String(m).padStart(2, '0')}`;
    if (key > end) return out;
    out.push(`${key}-01`);
    m += 1;
    if (m > 12) {
      m = 1;
      y += 1;
    }
  }
}

const startOf = (g: SalesSummaryGroup): string => g.from ?? g.key ?? '';

/** The total, the number of sales, the average sale and the best step of the summary's groups. */
export function ownFigures(groups: SalesSummaryGroup[]): { total: number; count: number; average: number | null; best: { start: string; total: number } | null } {
  let total = 0;
  let count = 0;
  let best: { start: string; total: number } | null = null;
  for (const g of groups) {
    const t = g.total_minor ?? 0;
    total += t;
    count += g.sales_count ?? 0;
    if (t > 0 && (best === null || t > best.total || (t === best.total && startOf(g) < best.start))) best = { start: startOf(g), total: t };
  }
  return { total, count, average: count > 0 ? Math.round(total / count) : null, best };
}

/** The chart: each step of the range with its total, and the same step of the period before, counted from its first day. */
export function ownSeries(level: OwnLevel, days: RangeDays, now: SalesSummaryGroup[], before: SalesSummaryGroup[] | undefined): ChartPoint[] {
  const byStart = (groups: SalesSummaryGroup[]) => new Map(groups.map((g) => [level === 'day' ? startOf(g) : `${startOf(g).slice(0, 7)}-01`, g.total_minor ?? 0]));
  const current = byStart(now);
  const steps = days.from ? ownSteps(level, days.from, days.to) : [...current.keys()].sort();
  const previous = before && days.previous ? byStart(before) : null;
  const prevSteps = previous && days.previous ? ownSteps(level, days.previous.from, days.previous.to) : [];
  return steps.map((start, i) => ({
    start,
    value: current.get(start) ?? 0,
    previous: previous ? (prevSteps[i] !== undefined ? previous.get(prevSteps[i]) ?? 0 : 0) : undefined,
  }));
}

/** A page of the summary is 31 days, 12 months or 20 years. */
const MONTHS_A_PAGE = 12;

/**
 * Every group of a level from one day to another. A page ends at the day before its oldest group, so pages that follow each other
 * cannot be asked for together; but a month range is split here into chunks of 12 months, each one page, and the chunks are read
 * at the same time (a year and a bit is two round trips at once, not two in a row). Days (up to 31) and years are one page, and
 * anything that still has more is followed page by page.
 */
async function ownGroups(level: OwnLevel, from: string | undefined, to: string): Promise<SalesSummaryGroup[]> {
  if (level === 'month' && from) {
    const chunks: { from: string; to: string }[] = [];
    let end = to;
    while (end >= from) {
      const y = Number(end.slice(0, 4));
      const m = Number(end.slice(5, 7)) - (MONTHS_A_PAGE - 1);
      const start = new Date(Date.UTC(y, m - 1, 1)).toISOString().slice(0, 10);
      chunks.push({ from: start > from ? start : from, to: end });
      end = daysBefore(start, 1);
    }
    return (await Promise.all(chunks.map((c) => pages(level, c.from, c.to)))).flat();
  }
  return pages(level, from, to);
}

async function pages(level: OwnLevel, from: string | undefined, to: string): Promise<SalesSummaryGroup[]> {
  const out: SalesSummaryGroup[] = [];
  let until: string | undefined = to;
  for (let page = 0; page < 6 && until; page++) {
    const r = await retail.salesSummary({ level, from, to: until });
    out.push(...(r.items ?? []));
    until = r.has_more ? r.next_to : undefined;
  }
  return out;
}

const BEST: Record<OwnLevel, string> = { day: 'Your best day', month: 'Your best month', year: 'Your best year' };

export function OwnSales() {
  const { me } = useStaff();
  const userId = me?.user_id ?? '';
  const [choice, setChoice] = useState<RangeChoice>(() => readRange(userId));
  // The business's own day, as DashboardService counts it, so the two cards of the page cannot disagree about today.
  const today = businessToday(new Date(), me?.tenant_timezone || undefined);
  const days = rangeDays(choice, today);
  const level = ownLevel(days);
  const now = useQuery({
    queryKey: ['retail', 'own-sales', level, days.from ?? '', days.to],
    queryFn: () => ownGroups(level, days.from, days.to),
  });
  const before = useQuery({
    queryKey: ['retail', 'own-sales', level, days.previous?.from ?? '', days.previous?.to ?? ''],
    queryFn: () => ownGroups(level, days.previous?.from, days.previous?.to ?? ''),
    enabled: days.previous !== undefined,
  });
  const pick = (next: RangeChoice) => {
    setChoice(next);
    writeRange(userId, next);
  };
  const figures = now.data ? ownFigures(now.data) : null;
  const prev = days.previous && before.data ? ownFigures(before.data) : null;
  const words = previousWords(choice, days);
  const title = rangeTitle(choice, days);
  const points = now.data ? ownSeries(level, days, now.data, days.previous ? before.data : undefined) : [];
  return (
    <section className="rt-card stack" aria-label="Your sales" data-tour="own-sales">
      <h2>Your sales, {title}</h2>
      <RangePicker value={choice} onChange={pick} today={today} />
      {now.isPending && <p className="loading">Adding up your sales</p>}
      {now.isError && <p className="hint">Your figures could not be loaded. Open your sales below.</p>}
      {figures && (
        <>
          <div className="rc-headline">
            <strong className="rc-total num">{money(figures.total)}</strong>
            <ChangeLine now={figures.total} before={prev?.total} words={words} />
          </div>
          {points.length > 1 && <RangeChart points={points} group={level} label={`Your sales ${title.toLowerCase()}, ${money(figures.total)} in all`} beforeWords={prev ? 'before' : undefined} legend={<ChartLegend before={prev ? words.replace(/^the /, 'The ') : undefined} />} />}
          <ul className="an-stats">
            <li className="an-stat"><span className="an-stat-label">Number of sales</span> <strong className="an-stat-value num">{figures.count}</strong> <ChangeLine now={figures.count} before={prev?.count} words={words} format={String} /></li>
            <li className="an-stat"><span className="an-stat-label">Average sale</span> <strong className="an-stat-value num">{figures.average === null ? 'No sales' : money(figures.average)}</strong> {figures.average !== null && prev?.average != null && <ChangeLine now={figures.average} before={prev.average} words={words} />}</li>
            {figures.best && <li className="an-stat"><span className="an-stat-label">{BEST[level]}</span> <strong className="an-stat-value num">{level === 'day' ? shortDate(figures.best.start) : stepWords(figures.best.start, level)}</strong> <span className="hint">{money(figures.best.total)}</span></li>}
          </ul>
        </>
      )}
      <p className="hint">Only the sales you made. <Link to="/staff/retail/sales">See your sales</Link></p>
    </section>
  );
}
