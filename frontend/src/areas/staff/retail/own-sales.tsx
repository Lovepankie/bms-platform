import { useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { businessToday, daysBefore, retail } from '../../../api/retail';
import { money } from './ui';

// A seller's own figures on the Retail home (issue #399, ADR-055): what they sold today and in the last 7 days. The server counts
// only the sales this person made (the sales summary of someone without retail.sale.all_sellers), so nothing here is anyone
// else's, and no cost, profit or stock value is ever in the answer.

/** Today and the six days before, as the summary's from and to. */
export function lastSevenDays(now: Date = new Date()): { from: string; to: string } {
  const to = businessToday(now);
  return { from: daysBefore(to, 6), to };
}

/** The total of today and of the seven days, from the day groups of the summary. */
export function ownTotals(groups: { key?: string; from?: string; total_minor?: number }[], today: string): { today: number; week: number } {
  let week = 0;
  let day = 0;
  for (const g of groups) {
    const total = g.total_minor ?? 0;
    week += total;
    if ((g.from ?? g.key) === today) day += total;
  }
  return { today: day, week };
}

export function OwnSales() {
  const range = lastSevenDays();
  const summary = useQuery({
    queryKey: ['retail', 'own-sales', range.from, range.to],
    queryFn: () => retail.salesSummary({ level: 'day', from: range.from, to: range.to }),
  });
  const totals = summary.data ? ownTotals(summary.data.items ?? [], range.to) : null;
  return (
    <section className="rt-card stack" aria-label="Your sales" data-tour="own-sales">
      <h2>Your sales</h2>
      {summary.isPending && <p className="loading">Adding up your sales</p>}
      {summary.isError && <p className="hint">Your figures could not be loaded. Open your sales below.</p>}
      {totals && (
        <ul className="an-stats">
          <li><span>Today</span> <strong>{money(totals.today)}</strong></li>
          <li><span>Last 7 days</span> <strong>{money(totals.week)}</strong></li>
        </ul>
      )}
      <p className="hint">Only the sales you made. <Link to="/staff/retail/sales">See your sales</Link></p>
    </section>
  );
}
