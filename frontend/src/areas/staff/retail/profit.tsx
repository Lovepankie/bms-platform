import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type DailyProfitRow, type Valuation } from '../../../api/retail';
import { showQty } from './maths';
import { BranchRequired, Gate, Problem, money, useSingleBranch } from './ui';

// Stock value (FR-RET-09) and daily profit (FR-RET-10). Both are offered only with
// retail.profit.read (the Gate), and the server refuses them otherwise.

export function ValuationTable({ valuation }: { valuation: Valuation }) {
  return (
    <>
      <p className="rt-total">
        At cost {valuation.totals.valueAtCostMinor !== undefined ? money(valuation.totals.valueAtCostMinor) : ''}
        <br />
        At selling price {money(valuation.totals.expectedSalesMinor)}
      </p>
      <table>
        <thead>
          <tr>
            <th>Item</th>
            <th className="num">Qty</th>
            <th className="num">At cost</th>
            <th className="num">At price</th>
          </tr>
        </thead>
        <tbody>
          {valuation.rows.map((r) => (
            <tr key={r.productId}>
              <td>{r.description}</td>
              <td className="num">{showQty(r.qty)}</td>
              <td className="num">{r.valueAtCostMinor !== undefined ? money(r.valueAtCostMinor) : ''}</td>
              <td className="num">{money(r.expectedSalesMinor)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function ValuationPage() {
  const { branchId, branchName } = useSingleBranch();
  const valuation = useQuery({ queryKey: ['retail', 'valuation', branchId], queryFn: () => retail.valuation({ branchId: branchId ?? '' }), enabled: branchId !== null });
  return (
    <Gate screen="valuation" title="Stock value">
      {branchId === null ? <BranchRequired /> : (
        <>
          <p>Branch: {branchName}</p>
          {valuation.isPending && <p>Loading</p>}
          <Problem error={valuation.error} />
          {valuation.data && <ValuationTable valuation={valuation.data} />}
        </>
      )}
    </Gate>
  );
}

export function ProfitTable({ rows }: { rows: DailyProfitRow[] }) {
  const total = rows.reduce((s, r) => s + r.profitMinor, 0);
  return (
    <>
      <p className="rt-total">Profit for the period {money(total)}</p>
      <table>
        <thead>
          <tr>
            <th>Day</th>
            <th className="num">Sales</th>
            <th className="num">Cost</th>
            <th className="num">Usage</th>
            <th className="num">Profit</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.date}>
              <td>{r.date}</td>
              <td className="num">{money(r.salesMinor)}</td>
              <td className="num">{money(r.costMinor)}</td>
              <td className="num">{money(r.usageMinor)}</td>
              <td className="num">{money(r.profitMinor)}{r.profitMinor < 0 ? ' (loss)' : ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

const iso = (d: Date) => d.toISOString().slice(0, 10);

function ProfitPage() {
  const { branchId, branchName } = useSingleBranch();
  const [to, setTo] = useState(() => iso(new Date()));
  const [from, setFrom] = useState(() => iso(new Date(Date.now() - 6 * 86400000)));
  const profit = useQuery({ queryKey: ['retail', 'profit', branchId, from, to], queryFn: () => retail.dailyProfit({ branchId: branchId ?? '', from, to }), enabled: branchId !== null && from <= to });
  return (
    <Gate screen="profit" title="Daily profit">
      {branchId === null ? <BranchRequired /> : (
        <>
          <p>Branch: {branchName}</p>
          <div className="rt-row">
            <div><label htmlFor="from">From</label><input id="from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></div>
            <div><label htmlFor="to">To</label><input id="to" type="date" value={to} onChange={(e) => setTo(e.target.value)} /></div>
          </div>
          {from > to && <p role="alert" className="rt-flag">The start date must not be after the end date.</p>}
          {profit.isPending && from <= to && <p>Loading</p>}
          <Problem error={profit.error} />
          {profit.data && <ProfitTable rows={profit.data} />}
        </>
      )}
    </Gate>
  );
}

export const ValuationRoute = createLazyRoute('/staff/retail/valuation')({ component: ValuationPage });
export const Route = createLazyRoute('/staff/retail/profit')({ component: ProfitPage });
