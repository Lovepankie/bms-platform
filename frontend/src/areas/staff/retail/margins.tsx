import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retailAnalytics, type MarginRow, type Margins, type PriceChangeImpact } from '../../../api/retail-analytics';
import { SkeletonList } from '../../../components/states';
import { Choice, DateRange, Empty, Section, defaultRange, percentOrNone, rangeProblem } from './analytics-ui';
import { showPercent, showQty } from './maths';
import { Gate, Problem, money, useBranchView, useIsPhone } from './ui';

// Margins (issue #149, step 2): profit and margin by item and category for a range, items sold under a
// target margin, and what a price change did. Cost is the snapshot on each sale line, never today's
// cost. The whole screen needs retail.profit.read.

const TOPS = [5, 10, 20, 50].map((n) => ({ value: n, label: `Top ${n}` }));
const TARGETS = [10, 15, 20, 25, 30, 40, 50].map((n) => ({ value: n, label: `${n}%` }));

function MarginTable({ title, rows, flag }: { title: string; rows: MarginRow[]; flag?: number }) {
  const phone = useIsPhone();
  if (rows.length === 0) return <Empty />;
  return (
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>{title}</th>
          {!phone && <th className="num">Sales</th>}
          {!phone && <th className="num">Cost</th>}
          <th className="num">Profit</th>
          <th className="num">Margin</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.id ?? r.label}>
            <td>
              {r.label}
              {r.code && <><br /><span className="hint">{r.code}</span></>}
              {phone && <><br /><span className="hint">Sales {money(r.sales_minor ?? 0)}, cost {money(r.cost_minor ?? 0)}, {showQty(r.qty ?? '0')}{r.unit ? ` ${r.unit}` : ''} sold</span></>}
            </td>
            {!phone && <td className="num">{money(r.sales_minor ?? 0)}</td>}
            {!phone && <td className="num">{money(r.cost_minor ?? 0)}</td>}
            <td className="num">{money(r.profit_minor ?? 0)}{(r.profit_minor ?? 0) < 0 ? ' (loss)' : ''}</td>
            <td className="num">
              {flag !== undefined && (r.margin_bp === undefined || r.margin_bp === null || r.margin_bp < flag) ? <span className="rt-flag">{percentOrNone(r.margin_bp) || 'n/a'}</span> : percentOrNone(r.margin_bp)}
            </td>
          </tr>
        ))}
      </tbody>
    </table></div>
  );
}

function Pace({ units, days }: { units?: string; days?: number }) {
  return <>{showQty(units ?? '0')} sold in {days ?? 0} {days === 1 ? 'day' : 'days'}</>;
}

function PriceChanges({ items }: { items: PriceChangeImpact[] }) {
  const phone = useIsPhone();
  if (items.length === 0) return <Empty>No selling price changed in this range.</Empty>;
  if (phone) {
    return (
      <ul className="an-cards">
        {items.map((c) => (
          <li key={c.product_id} className="rt-card">
            <strong>{c.description}</strong>
            <span className="hint">{c.code}</span>
            <p>Price {money(c.old_sell_minor ?? 0)} to {money(c.new_sell_minor ?? 0)} on {c.changed_on}</p>
            <p>Before: <Pace units={c.units_before} days={c.days_before} />, margin {percentOrNone(c.margin_before_bp) || 'n/a'}</p>
            <p>After: <Pace units={c.units_after} days={c.days_after} />, margin {percentOrNone(c.margin_after_bp) || 'n/a'}</p>
          </li>
        ))}
      </ul>
    );
  }
  return (
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr><th>Item</th><th className="num">Sold before</th><th className="num">Sold after</th><th className="num">Margin before</th><th className="num">Margin after</th></tr>
      </thead>
      <tbody>
        {items.map((c) => (
          <tr key={c.product_id}>
            <td>
              {c.description}<br />
              <span className="hint">{c.code}: {money(c.old_sell_minor ?? 0)} to {money(c.new_sell_minor ?? 0)} on {c.changed_on}</span>
            </td>
            <td className="num"><Pace units={c.units_before} days={c.days_before} /></td>
            <td className="num"><Pace units={c.units_after} days={c.days_after} /></td>
            <td className="num">{percentOrNone(c.margin_before_bp) || 'n/a'}</td>
            <td className="num">{percentOrNone(c.margin_after_bp) || 'n/a'}</td>
          </tr>
        ))}
      </tbody>
    </table></div>
  );
}

export function MarginsView({ data }: { data: Margins }) {
  const totals = data.totals ?? { sales_minor: 0, cost_minor: 0, profit_minor: 0 };
  const below = data.below_target ?? { items: [], total: 0 };
  const changes = data.price_changes ?? { items: [], total: 0 };
  return (
    <>
      <div className="table-wrap"><table>
        <tbody>
          <tr><td>Sales</td><td className="num">{money(totals.sales_minor ?? 0)}</td></tr>
          <tr><td>Cost of what was sold</td><td className="num">{money(totals.cost_minor ?? 0)}</td></tr>
          <tr><td>Profit</td><td className="num">{money(totals.profit_minor ?? 0)}</td></tr>
          <tr><td>Margin (profit over sales)</td><td className="num">{percentOrNone(totals.margin_bp)}</td></tr>
        </tbody>
      </table></div>
      <p className="hint">Margin is profit as a share of what was sold for. Cost is what each item cost when it was sold, not today's cost.</p>

      <Section title={`Sold below ${showPercent(data.target_bp)} margin`} hint="Lowest margin first. An item that sold for nothing counts.">
        <MarginTable title="Item" rows={below.items ?? []} flag={data.target_bp} />
        {(below.total ?? 0) > (below.items ?? []).length && <p className="hint">Showing {(below.items ?? []).length} of {below.total}. Raise the Top number to see more.</p>}
      </Section>
      <Section title="Price changes" hint="Items whose selling price changed in the range, split at the day of the last change. Margins use the cost and price each sale had.">
        <PriceChanges items={changes.items ?? []} />
        {(changes.total ?? 0) > (changes.items ?? []).length && <p className="hint">Showing {(changes.items ?? []).length} of {changes.total}.</p>}
      </Section>
      <Section title="By item" hint="The items that made the most profit."><MarginTable title="Item" rows={data.by_product ?? []} /></Section>
      <Section title="By category"><MarginTable title="Category" rows={data.by_category ?? []} /></Section>
    </>
  );
}

function MarginsPage() {
  const { all, branchId, branchName } = useBranchView();
  const initial = defaultRange();
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [top, setTop] = useState(10);
  const [target, setTarget] = useState(20);
  const problem = rangeProblem(from, to);
  const report = useQuery({
    queryKey: ['retail', 'margins', all ? 'all' : branchId, from, to, top, target],
    queryFn: () => retailAnalytics.margins({ branchId: all ? undefined : (branchId ?? ''), from, to, top, targetBp: target * 100 }),
    enabled: !problem && (all || branchId !== null),
  });
  return (
    <Gate screen="margins" title="Margins" wide>
      <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
      <DateRange from={from} to={to} onFrom={setFrom} onTo={setTo} />
      <div className="rt-row">
        <Choice id="mg-target" label="Margin target" value={target} options={TARGETS} onChange={setTarget} />
        <Choice id="mg-top" label="Rows to show" value={top} options={TOPS} onChange={setTop} />
      </div>
      {report.isPending && !problem && <SkeletonList label="Loading margins" />}
      <Problem error={report.error} />
      {report.data && <MarginsView data={report.data} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/margins')({ component: MarginsPage });
