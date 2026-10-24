import { useQuery } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retailAnalytics, type CoverRow, type StockHealth } from '../../../api/retail-analytics';
import { SkeletonList } from '../../../components/states';
import { Choice, DateRange, Empty, Section, ToStock, defaultRange, moneyOrNone, plural, rangeProblem } from './analytics-ui';
import { showQty } from './maths';
import { StockMinimumEditor } from './stock-minimum';
import { SlowMoversSection } from './slow-movers';
import { Gate, Problem, money, useBranchName, useBranchView, useIsPhone } from './ui';

// Stock health (issue #149, step 3): how long the stock lasts at the pace of the last 30 days, what to
// buy, what sells too slowly (issue #414), what has not sold in 90 days, and what was used, damaged or found
// missing. Cost figures arrive only with retail.profit.read in the branch.

const TOPS = [5, 10, 20, 50].map((n) => ({ value: n, label: `Top ${n}` }));
const LEADS = [7, 14, 21, 30, 45].map((n) => ({ value: n, label: `${n} days` }));
const COVERS = [30, 45, 60, 90].map((n) => ({ value: n, label: `${n} days` }));

type NameOf = (id: string | undefined) => string;

/** "Below your minimum of 10" from the server's words, which start in lower case for use inside a sentence. */
export function lowWords(row: CoverRow): string | null {
  const words = row.low_words;
  return words ? words.charAt(0).toUpperCase() + words.slice(1) + '.' : null;
}

/** Warn me when stock is below, opened on a tap from a reorder row, so the list itself loads nothing more (issue #412). */
function LevelToggle({ row }: { row: CoverRow }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <br />
      <button type="button" className="btn-ghost btn-sm" aria-expanded={open} onClick={() => setOpen(!open)}>
        {open ? 'Close' : row.min_qty ? 'Change the level' : 'Set a level'}
      </button>
      {open && <StockMinimumEditor productId={row.product_id ?? ''} unit={row.unit} />}
    </>
  );
}

function CoverTable({ rows, nameOf, reorder }: { rows: CoverRow[]; nameOf: NameOf; reorder: boolean }) {
  const phone = useIsPhone();
  if (rows.length === 0) return <Empty>{reorder ? 'Nothing needs buying at this pace.' : 'No item sold in the last 30 days.'}</Empty>;
  return (
    <div className="table-wrap" tabIndex={0}><table>
      <thead>
        <tr>
          <th>Item</th>
          {!phone && <th className="num">In stock</th>}
          <th className="num">Cover</th>
          {reorder && <th className="num">Buy</th>}
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={`${r.branch_id}-${r.product_id}`}>
            <td>
              <ToStock>{r.description}</ToStock><br />
              <span className="hint">{r.code}, {nameOf(r.branch_id)}</span>
              {reorder && lowWords(r) && <><br /><span className="badge badge-warning">{lowWords(r)}</span></>}
              {reorder && <LevelToggle row={r} />}
              {phone && <><br /><span className="hint">{showQty(r.qty_on_hand ?? '0')} {r.unit} in stock, {showQty(r.sold_last30 ?? '0')} sold in 30 days</span></>}
            </td>
            {!phone && <td className="num">{showQty(r.qty_on_hand ?? '0')} {r.unit}</td>}
            <td className="num">{r.capped ? 'over ' : ''}{r.days_of_cover} days</td>
            {reorder && <td className="num">{showQty(r.suggested_qty ?? '0')} {r.unit}</td>}
          </tr>
        ))}
      </tbody>
    </table></div>
  );
}

export function StockHealthView({ data, nameOf }: { data: StockHealth; nameOf: NameOf }) {
  const phone = useIsPhone();
  const dead = data.dead_stock;
  const withCost = data.profit_visible === true || (dead?.branches ?? []).some((b) => b.value_at_cost_minor !== undefined);
  const reorder = data.reorder ?? { items: [], total: 0 };
  const cover = data.cover ?? { items: [], total: 0 };
  const shrink = data.shrinkage ?? [];
  const shrinkCost = shrink.some((s) => s.net_cost_minor !== undefined);
  return (
    <>
      <Section
        title={`Reorder: under ${data.lead_days} days of cover, or below your level`}
        hint={`Below the level you set for the item, or running out within ${data.lead_days} days at the pace of the last ${data.velocity_days} days, whichever comes first. Buy enough to reach ${data.cover_days} days of cover and at least the level.`}
      >
        <CoverTable rows={reorder.items ?? []} nameOf={nameOf} reorder />
        {(reorder.total ?? 0) > (reorder.items ?? []).length && <p className="hint">Showing {(reorder.items ?? []).length} of {reorder.total}. Raise the Top number to see more.</p>}
      </Section>
      <Section title="Days of cover" hint="How long the stock lasts if sales go on at the pace of the last 30 days. The lowest first. Items with no sale in 30 days are not listed.">
        <CoverTable rows={cover.items ?? []} nameOf={nameOf} reorder={false} />
      </Section>
      <SlowMoversSection data={data.slow_movers} nameOf={nameOf} />
      <Section title={`Dead stock: no sale in ${dead?.days ?? 90} days`} hint="Stock on hand that did not sell in the branch.">
        {(dead?.branches ?? []).length === 0 ? <Empty>Everything in stock has sold lately.</Empty> : (
          <>
            <div className="table-wrap" tabIndex={0}><table>
              <thead><tr><th>Branch</th><th className="num">Items</th><th className="num">At price</th>{withCost && !phone && <th className="num">At cost</th>}</tr></thead>
              <tbody>
                {(dead?.branches ?? []).map((b) => (
                  <tr key={b.branch_id}>
                    <td><ToStock>{nameOf(b.branch_id)}</ToStock></td>
                    <td className="num">{b.item_count}</td>
                    <td className="num">
                      {b.value_at_price_minor !== undefined ? money(b.value_at_price_minor) : ''}
                      {withCost && phone && b.value_at_cost_minor !== undefined && <><br /><span className="hint">cost {money(b.value_at_cost_minor)}</span></>}
                    </td>
                    {withCost && !phone && <td className="num">{moneyOrNone(b.value_at_cost_minor)}</td>}
                  </tr>
                ))}
                <tr>
                  <td><strong>All</strong></td>
                  <td className="num">{dead?.items_total}</td>
                  <td className="num">
                    {dead?.value_at_price_minor !== undefined ? money(dead.value_at_price_minor) : ''}
                    {withCost && phone && dead?.value_at_cost_minor !== undefined && <><br /><span className="hint">cost {money(dead.value_at_cost_minor)}</span></>}
                  </td>
                  {withCost && !phone && <td className="num">{moneyOrNone(dead?.value_at_cost_minor)}</td>}
                </tr>
              </tbody>
            </table></div>
            <p className="hint">The biggest by value at price:</p>
            <div className="table-wrap" tabIndex={0}><table>
              <thead><tr><th>Item</th>{!phone && <th className="num">In stock</th>}<th className="num">At price</th>{withCost && !phone && <th className="num">At cost</th>}</tr></thead>
              <tbody>
                {(dead?.items ?? []).map((i) => (
                  <tr key={`${i.branch_id}-${i.product_id}`}>
                    <td>
                      <ToStock>{i.description}</ToStock><br /><span className="hint">{i.code}, {nameOf(i.branch_id)}</span>
                      {phone && <><br /><span className="hint">{showQty(i.qty_on_hand ?? '0')} {i.unit} in stock</span></>}
                    </td>
                    {!phone && <td className="num">{showQty(i.qty_on_hand ?? '0')} {i.unit}</td>}
                    <td className="num">
                      {i.value_at_price_minor !== undefined ? money(i.value_at_price_minor) : ''}
                      {withCost && phone && i.value_at_cost_minor !== undefined && <><br /><span className="hint">cost {money(i.value_at_cost_minor)}</span></>}
                    </td>
                    {withCost && !phone && <td className="num">{moneyOrNone(i.value_at_cost_minor)}</td>}
                  </tr>
                ))}
              </tbody>
            </table></div>
          </>
        )}
      </Section>
      <Section title="Used, damaged and found missing" hint="In the date range. Usage and damage reports, and the differences stock counts found.">
        {shrink.length === 0 ? <Empty /> : phone ? (
          <ul className="an-cards">
            {shrink.map((x) => (
              <li key={x.branch_id} className="rt-card">
                <strong>{nameOf(x.branch_id)}</strong>
                <div className="rt-row"><span>Used</span><span className="num">{plural(x.used_reports, 'report')}</span></div>
                <div className="rt-row"><span>Damaged</span><span className="num">{plural(x.damaged_reports, 'report')}</span></div>
                <div className="rt-row"><span>Found missing</span><span className="num">{plural(x.short_lines, 'line')}</span></div>
                {x.net_cost_minor !== undefined && <div className="rt-row"><span>Cost</span><span className="num">{money(x.net_cost_minor)}</span></div>}
              </li>
            ))}
          </ul>
        ) : (
          <div className="table-wrap" tabIndex={0}><table>
            <thead>
              <tr>
                <th>Branch</th>
                <th className="num">Used</th>
                <th className="num">Damaged</th>
                <th className="num">Found missing</th>
                {shrinkCost && <th className="num">Cost</th>}
              </tr>
            </thead>
            <tbody>
              {shrink.map((x) => (
                <tr key={x.branch_id}>
                  <td>{nameOf(x.branch_id)}</td>
                  <td className="num">{plural(x.used_reports, 'report')}</td>
                  <td className="num">{plural(x.damaged_reports, 'report')}</td>
                  <td className="num">{plural(x.short_lines, 'line')}</td>
                  {shrinkCost && <td className="num">{moneyOrNone(x.net_cost_minor)}</td>}
                </tr>
              ))}
            </tbody>
          </table></div>
        )}
        {shrinkCost && <p className="hint">Cost is used and damaged stock plus what counts found missing, less what they found extra, at cost.</p>}
      </Section>
    </>
  );
}

export function StockHealthPage() {
  const { all, branchId } = useBranchView();
  const nameOf = useBranchName();
  const initial = defaultRange();
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [top, setTop] = useState(10);
  const [lead, setLead] = useState(14);
  const [cover, setCover] = useState(30);
  const problem = rangeProblem(from, to);
  const report = useQuery({
    queryKey: ['retail', 'stock-health', all ? 'all' : branchId, from, to, top, lead, cover],
    queryFn: () => retailAnalytics.stockHealth({ branchId: all ? undefined : (branchId ?? ''), from, to, top, leadDays: lead, coverDays: Math.max(cover, lead) }),
    enabled: !problem && (all || branchId !== null),
  });
  return (
    <Gate screen="stockHealth" title="Stock health" wide>
      <div data-tour="health-range"><DateRange from={from} to={to} onFrom={setFrom} onTo={setTo} /></div>
      <div className="rt-row" data-tour="health-buy">
        <Choice id="sh-lead" label="Buy when cover is under" value={lead} options={LEADS} onChange={setLead} />
        <Choice id="sh-cover" label="Buy enough for" value={cover} options={COVERS} onChange={setCover} />
        <Choice id="sh-top" label="Rows to show" value={top} options={TOPS} onChange={setTop} />
      </div>
      <div data-tour="health-results">
      {report.isPending && !problem && <SkeletonList label="Loading stock health" />}
      <Problem error={report.error} />
      {report.data && <StockHealthView data={report.data} nameOf={nameOf} />}
      </div>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stock-health')({ component: StockHealthPage });
