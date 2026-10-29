import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, daysBefore, retail, type DailyProfit, type DailyProfitRow, type Valuation } from '../../../api/retail';
import { showDate, showPercent, showQty } from './maths';
import { SkeletonList } from '../../../components/states';
import { KeyFigure, KeyFigureRow } from '../../../app/ui/KeyFigure';
import { ToSales, ToStock } from './analytics-ui';
import { BranchRequired, CategoryLabel, Gate, Problem, money, useBranchName, useBranchView, useIsPhone } from './ui';
import { needsOf } from './permissions';
import { PeriodPicker } from '../../../app/ui/PeriodPicker';
import { Hint } from '../../../app/ui/Hint';

// Stock value (FR-RET-09) is a stock read; its cost columns arrive only with retail.profit.read.
// Daily profit (FR-RET-10) needs retail.profit.read (the Gate), and the server refuses it otherwise.

/** True when the server sent any cost or profit figure on this total or row (retail.profit.read where it is held). */
const hasCost = (x: { value_at_cost_minor?: number; expected_profit_minor?: number }) =>
  x.value_at_cost_minor !== undefined || x.expected_profit_minor !== undefined;

type Sums = { sales?: number; cost?: number; profit?: number; bp?: number };

/** The headline figures of a valuation as a small two column table, so no figure wraps on a phone. */
/** The stock at price as the key figure (issue #526), with the cost and the profit expected under it for a reader of costs. */
function Headline({ sums }: { sums: Sums }) {
  const withCost = sums.cost !== undefined;
  return (
    <KeyFigure label="Stock at selling price" value={money(sums.sales ?? 0)} tour="valuation-total">
      {withCost && (
        <>
          <KeyFigureRow label="At buying price" value={money(sums.cost ?? 0)} />
          <KeyFigureRow label="Expected profit" value={money(sums.profit ?? 0)} />
          <KeyFigureRow label="Expected profit % (over buying price)" value={showPercent(sums.bp)} />
        </>
      )}
    </KeyFigure>
  );
}

/**
 * Rows of totals (branches, categories): a table with a column for each figure, or on a phone one card
 * each with a line per figure, so the profit is never off the edge of the screen.
 */
function SummaryRows({ label, rows, withCost }: { label: string; rows: { key: string; name: string; sums: Sums }[]; withCost: boolean }) {
  const phone = useIsPhone();
  if (phone) {
    return (
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {rows.map(({ key, name, sums }) => (
          <li key={key} className="rt-card">
            <strong>{label === 'Branch' ? <ToStock>{name}</ToStock> : name}</strong>
            <div className="rt-row"><span>At selling price</span><span className="num">{money(sums.sales ?? 0)}</span></div>
            {withCost && sums.cost !== undefined && <div className="rt-row"><span>At buying price</span><span className="num">{money(sums.cost)}</span></div>}
            {withCost && sums.profit !== undefined && <div className="rt-row"><span>Expected profit</span><span className="num">{money(sums.profit)}</span></div>}
            {withCost && sums.profit !== undefined && <div className="rt-row"><span>Profit % (over buying price)</span><span className="num">{showPercent(sums.bp)}</span></div>}
          </li>
        ))}
      </ul>
    );
  }
  return (
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>{label}</th>
          {withCost && <th className="num">At buying price</th>}
          <th className="num">At selling price</th>
          {withCost && <th className="num">Expected profit</th>}
          {withCost && <th className="num">Profit %</th>}
        </tr>
      </thead>
      <tbody>
        {rows.map(({ key, name, sums }) => (
          <tr key={key}>
            <td>{label === 'Branch' ? <ToStock>{name}</ToStock> : name}</td>
            {withCost && <td className="num">{sums.cost !== undefined ? money(sums.cost) : ''}</td>}
            <td className="num">{money(sums.sales ?? 0)}</td>
            {withCost && <td className="num">{sums.profit !== undefined ? money(sums.profit) : ''}</td>}
            {withCost && <td className="num">{sums.profit !== undefined ? showPercent(sums.bp) : ''}</td>}
          </tr>
        ))}
      </tbody>
    </table></div>
  );
}

const sumsOf = (r: { expected_sales_minor?: number; value_at_cost_minor?: number; expected_profit_minor?: number; expected_profit_bp?: number }): Sums => ({
  sales: r.expected_sales_minor, cost: r.value_at_cost_minor, profit: r.expected_profit_minor, bp: r.expected_profit_bp,
});

export function ValuationTable({ valuation, nameOf }: { valuation: Valuation; nameOf?: (id: string | undefined) => string }) {
  // A cost or profit column exists when the server sent a figure for the total, a row or a category:
  // a caller who holds retail.profit.read at some branches only still sees it where it was sent.
  const categories = valuation.categories ?? [];
  const withCost = hasCost(valuation) || categories.some(hasCost) || (valuation.rows ?? []).some(hasCost);
  const phone = useIsPhone();
  return (
    <>
      <Headline sums={sumsOf(valuation)} />
      {categories.length > 0 && (
        <>
          <h2>By category</h2>
          <SummaryRows label="Category" withCost={categories.some(hasCost)} rows={categories.map((c) => ({ key: c.category_id ?? '', name: c.category ?? '', sums: sumsOf(c) }))} />
        </>
      )}
      <h2>By item</h2>
      <div className="table-wrap" tabIndex={0}><table>
        <thead>
          <tr>
            <th>Item</th>
            {!phone && <th className="num">Qty</th>}
            {withCost && !phone && <th className="num">At buying price</th>}
            <th className="num">At selling price</th>
            {withCost && <th className="num">{phone ? 'Profit' : 'Expected profit'}</th>}
            {withCost && !phone && <th className="num">Profit %</th>}
          </tr>
        </thead>
        <tbody>
          {(valuation.rows ?? []).map((r) => (
            <tr key={`${r.branch_id}-${r.product_id}`}>
              <td>
                <ToStock>{r.description}</ToStock><br /><CategoryLabel category={r.category} />
                {nameOf && <><br /><span className="hint">{nameOf(r.branch_id)}</span></>}
                {phone && <><br /><span className="hint">{showQty(r.qty ?? '0')} in stock{withCost && r.value_at_cost_minor !== undefined ? `, cost ${money(r.value_at_cost_minor)}` : ''}</span></>}
              </td>
              {!phone && <td className="num">{showQty(r.qty ?? '0')}</td>}
              {withCost && !phone && <td className="num">{r.value_at_cost_minor !== undefined ? money(r.value_at_cost_minor) : ''}</td>}
              <td className="num">{money(r.expected_sales_minor ?? 0)}</td>
              {withCost && (
                <td className="num">
                  {r.expected_profit_minor !== undefined ? money(r.expected_profit_minor) : ''}
                  {phone && r.expected_profit_minor !== undefined && <><br /><span className="hint">{showPercent(r.expected_profit_bp)}</span></>}
                </td>
              )}
              {withCost && !phone && <td className="num">{r.expected_profit_minor !== undefined ? showPercent(r.expected_profit_bp) : ''}</td>}
            </tr>
          ))}
        </tbody>
      </table></div>
    </>
  );
}

/** Every branch's totals side by side: what the owner reads first in All branches (#144). */
export function BranchTotals({ valuation, nameOf }: { valuation: Valuation; nameOf: (id: string | undefined) => string }) {
  const branches = valuation.branches ?? [];
  const withCost = hasCost(valuation) || branches.some(hasCost);
  if (branches.length === 0) return null;
  return (
    <>
      <h2>By branch</h2>
      <SummaryRows label="Branch" withCost={withCost} rows={branches.map((b) => ({ key: b.branch_id ?? '', name: nameOf(b.branch_id), sums: sumsOf(b) }))} />
      <h2>All branches together</h2>
    </>
  );
}

export function ValuationPage() {
  const { all, branchId } = useBranchView();
  const nameOf = useBranchName();
  const valuation = useQuery({
    queryKey: ['retail', 'valuation', all ? 'all' : branchId],
    queryFn: () => retail.valuation({ branchId: all ? undefined : (branchId ?? '') }),
    enabled: all || branchId !== null,
  });
  return (
    <Gate screen="valuation" title="Stock value">
      {!all && branchId === null ? <BranchRequired permissions={needsOf('valuation')} /> : (
        <>
          {/* The key figure carries valuation-total once loaded; until then the skeleton holds its place for the tour. */}
          {valuation.isPending && <div data-tour="valuation-total"><SkeletonList label="Loading stock value" /></div>}
          <div data-tour="valuation-figures">
          <Problem error={valuation.error} />
          {valuation.data && (
            <>
              {all && <BranchTotals valuation={valuation.data} nameOf={nameOf} />}
              <ValuationTable valuation={valuation.data} nameOf={all ? nameOf : undefined} />
            </>
          )}
          </div>
        </>
      )}
    </Gate>
  );
}

type Figures = { sold: number; received: number; sales: number; cost: number; usage: number; bad: number; difference: number; profit: number };
const add = (to: Figures, r: DailyProfitRow): Figures => ({
  sold: to.sold + (r.sold_minor ?? 0),
  received: to.received + (r.received_minor ?? 0),
  sales: to.sales + (r.sales_minor ?? 0), cost: to.cost + (r.cost_of_sales_minor ?? 0), usage: to.usage + (r.usage_cost_minor ?? 0), bad: to.bad + (r.bad_debt_minor ?? 0), difference: to.difference + (r.stocktake_difference_minor ?? 0), profit: to.profit + (r.profit_minor ?? 0),
});
const none = (): Figures => ({ sold: 0, received: 0, sales: 0, cost: 0, usage: 0, bad: 0, difference: 0, profit: 0 });

function FiguresTable({ label, rows, onPayment }: { label: string; rows: { key: string; name: string; f: Figures }[]; onPayment?: boolean }) {
  // Debts written off (ADR-043) get a column only once there is one, so a shop that never writes a debt off sees no new column.
  const withBad = rows.some((r) => r.f.bad !== 0);
  // On a phone the cost and usage go under the name, so Sales and Profit stay in view.
  const phone = useIsPhone();
  return (
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>{label}</th>
          {onPayment && !phone && <th className="num">Sold</th>}
          {onPayment && <th className="num">Received</th>}
          <th className="num">{onPayment ? 'Counted for profit' : 'Sales'}</th>
          {!phone && <th className="num">Cost of goods sold</th>}
          {!phone && <th className="num">Usage</th>}
          {withBad && !phone && <th className="num">Debts<br />written off</th>}
          {!phone && <th className="num">Stock count<br />difference</th>}
          <th className="num">Profit</th>
        </tr>
      </thead>
      <tbody>
        {rows.map(({ key, name, f }) => (
          <tr key={key}>
            <td>{name}{phone && <><br /><span className="hint">{onPayment && <>Sold {money(f.sold)}<br /></>}Cost {money(f.cost)}<br />Usage {money(f.usage)}<br />{withBad && <>Debts written off {money(f.bad)}<br /></>}Stock count difference {money(f.difference)}</span></>}</td>
            {onPayment && !phone && <td className="num">{money(f.sold)}</td>}
            {onPayment && <td className="num">{money(f.received)}</td>}
            <td className="num">{money(f.sales)}</td>
            {!phone && <td className="num">{money(f.cost)}</td>}
            {!phone && <td className="num">{money(f.usage)}</td>}
            {withBad && !phone && <td className="num">{money(f.bad)}</td>}
            {!phone && <td className="num">{money(f.difference)}</td>}
            <td className="num">{money(f.profit)}{f.profit < 0 ? ' (loss)' : ''}</td>
          </tr>
        ))}
      </tbody>
    </table></div>
  );
}

/**
 * The profit report. With `nameOf` (All branches) the report is shown per branch and then per day over
 * every branch, so the total is the sum of the branch lines above it (#144).
 */
export function ProfitTable({ report, nameOf }: { report: DailyProfit; nameOf?: (id: string | undefined) => string }) {
  const rows = report.rows ?? [];
  const byDay = new Map<string, Figures>();
  const byBranch = new Map<string, Figures>();
  for (const r of rows) {
    byDay.set(r.date ?? '', add(byDay.get(r.date ?? '') ?? none(), r));
    byBranch.set(r.branch_id ?? '', add(byBranch.get(r.branch_id ?? '') ?? none(), r));
  }
  const days = [...byDay.entries()].sort(([x], [y]) => x.localeCompare(y)).map(([date, f]) => ({ key: date, name: showDate(date), f }));
  // ADR-043: with credit sales counted when the buyer pays, Sales becomes Received (money collected) and the profit is that of
  // the money received; what was sold, and what is still owed, come beside it.
  const onPayment = report.credit_sale_recognition === 'on_payment';
  // Credit sales made before the rule started were counted at the sale; they are part of the profit but nobody has paid them yet.
  const atSale = report.counted_at_sale_minor ?? 0;
  return (
    <>
      <KeyFigure label={onPayment && atSale === 0 ? 'Profit from money received, for the period' : 'Profit for the period'} value={money(report.profit_minor ?? 0)} />
      <p><ToSales>See the sales behind it</ToSales></p>
      {onPayment && (
        <ul className="an-stats" data-testid="credit-recognition-summary">
          <li className="an-stat"><span className="an-stat-label">Sold</span><strong className="an-stat-value num">{money(report.sold_minor ?? 0)}</strong><span className="hint">Everything sold, credit included</span></li>
          <li className="an-stat"><span className="an-stat-label">Received</span><strong className="an-stat-value num">{money(report.received_minor ?? 0)}</strong><span className="hint">Money collected, including payments on earlier credit sales</span></li>
          {atSale !== 0 && <li className="an-stat"><span className="an-stat-label">Net credit profit counted at the sale</span><strong className="an-stat-value num">{money(atSale)}</strong><span className="hint">From credit made before the rule started, after returns. In the profit, not taken off Received</span></li>}
          <li className="an-stat"><span className="an-stat-label">On credit now</span><strong className="an-stat-value num">{money(report.on_credit_minor ?? 0)}</strong><span className="hint">Not paid yet, not counted as profit. Credit sales from before the rule started are not in it</span></li>
        </ul>
      )}
      {nameOf && (
        <>
          <h2>By branch</h2>
          <FiguresTable label="Branch" onPayment={onPayment} rows={[...byBranch.entries()].map(([id, f]) => ({ key: id, name: nameOf(id), f }))} />
          <h2>By day, all branches</h2>
        </>
      )}
      <FiguresTable label="Day" onPayment={onPayment} rows={days} />
      {onPayment && <Hint>A credit sale counts on the day the buyer pays, with its share of the profit. A part payment gives part of the profit. Savings use this profit.</Hint>}
      {report.credit_recognition_starts_on && (
        <p className="hint" data-testid="credit-starts-on">
          {onPayment
            ? `Credit sales entered and dated before ${showDate(report.credit_recognition_starts_on)} count as they did, on the day of the sale.`
            : `From ${showDate(report.credit_recognition_starts_on)}, credit sales will count when the buyer pays.`}
        </p>
      )}
      <Hint>Usage and damage reports count here. A debt written off is a loss on the day it was written off. The stock count difference is the value of what a count found missing (a minus) or extra (a plus), at cost, on the day the count was committed.</Hint>
    </>
  );
}

export function ProfitPage() {
  const { all, branchId } = useBranchView();
  const nameOf = useBranchName();
  // The last seven days in the business's timezone, the server's own business date (#112 item 9).
  const [to, setTo] = useState(() => businessToday());
  const [from, setFrom] = useState(() => daysBefore(businessToday(), 6));
  const profit = useQuery({
    queryKey: ['retail', 'profit', all ? 'all' : branchId, from, to],
    queryFn: () => retail.dailyProfit({ branchId: all ? undefined : (branchId ?? ''), from, to }),
    enabled: (all || branchId !== null) && from <= to,
  });
  return (
    <Gate screen="profit" title="Daily profit" wide>
      {!all && branchId === null ? <BranchRequired permissions={needsOf('profit')} /> : (
        <>
          <PeriodPicker from={from} to={to} tour="profit-range" onChange={(range) => { setFrom(range.from); setTo(range.to); }} />
          {from > to && <p role="alert" className="rt-flag">The start date must not be after the end date.</p>}
          <div data-tour="profit-figures">
          {profit.isPending && from <= to && <SkeletonList label="Loading profit" />}
          <Problem error={profit.error} />
          {profit.data && <ProfitTable report={profit.data} nameOf={all ? nameOf : undefined} />}
          </div>
        </>
      )}
    </Gate>
  );
}

export const ValuationRoute = createLazyRoute('/staff/retail/valuation')({ component: ValuationPage });
export const Route = createLazyRoute('/staff/retail/profit')({ component: ProfitPage });
