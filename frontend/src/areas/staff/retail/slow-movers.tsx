import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, useRouter } from '@tanstack/react-router';
import { useContext, useState } from 'react';
import { retail, type CapabilitiesRead } from '../../../api/retail';
import type { SlowMover, SlowMovers } from '../../../api/retail-analytics';
import { StaffContext } from '../context';
import { Empty, Section, ToStock, moneyOrNone } from './analytics-ui';
import { showQty } from './maths';
import { TRANSFER, canUse, holdsAt } from './permissions';
import { Problem, changeFailureText, money, useIsPhone } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';

// Slow movers on Stock health (issue #414): items that do sell, but so slowly that the stock lasts more than the
// business's number of months (or sold fewer than its minimum in 90 days). Separate from dead stock, which did not sell
// at all. Money tied up arrives only with retail.profit.read in the branch; the server leaves the field out otherwise.
// An item newer in the branch than 90 days is judged on the days it has been there (days_counted), so it is not slow just
// for being new. "Sells better at" names another branch of the person's own where the item sells faster, with a Move stock link
// filled in for someone who may move stock from this branch.

type NameOf = (id: string | undefined) => string;

/** "Move 8" to Move stock with the branches, the item and the quantity filled in, for a person who may move stock here. */
function MoveLink({ row }: { row: SlowMover }) {
  const staff = useContext(StaffContext);
  const router = useRouter({ warn: false });
  const to = row.better_at;
  if (!to || !router || !staff || !canUse(staff.me, 'transfer') || !holdsAt(staff.me, TRANSFER, row.branch_id)) return null;
  const search = { from: row.branch_id ?? '', to: to.branch_id ?? '', product: row.product_id ?? '', code: row.code ?? '', qty: showQty(to.suggested_qty ?? '0') };
  return (
    <Link className="btn-sm" to={'/staff/retail/transfer' as never} search={search as never} aria-label={`Move ${showQty(to.suggested_qty ?? '0')} ${row.description ?? ''}`}>
      Move {showQty(to.suggested_qty ?? '0')}
    </Link>
  );
}

function Better({ row, nameOf }: { row: SlowMover; nameOf: NameOf }) {
  const to = row.better_at;
  if (!to) return null;
  return (
    <span className="hint">
      Sells better at {nameOf(to.branch_id)} ({showQty(to.sold_last90 ?? '0')} sold in 90 days). Move {showQty(to.suggested_qty ?? '0')} {row.unit} there.{' '}
      <MoveLink row={row} />
    </span>
  );
}

export function SlowMoversSection({ data, nameOf }: { data: SlowMovers | undefined; nameOf: NameOf }) {
  const phone = useIsPhone();
  const months = data?.slow_months ?? 4;
  const items = data?.items ?? [];
  const withCost = items.some((i) => i.value_at_cost_minor !== undefined);
  const rule = (data?.min_sold ?? 0) > 0 ? `, or fewer than ${data?.min_sold} sold in 90 days` : '';
  return (
    <Section
      title={`Slow movers: more than ${months} months of stock`}
      hint={`Items that sold in the last 90 days, but at that pace the stock lasts more than ${months} months${rule}. Items that did not sell at all are under Dead stock.`}
    >
      <div data-tour="health-slow">
        {items.length === 0 ? <Empty>No slow movers. Everything that sells, sells at a good pace.</Empty> : (
          <div className="table-wrap" tabIndex={0}><table>
            <thead>
              <tr>
                <th>Item</th>
                {!phone && <th className="num">In stock</th>}
                {!phone && <th>Last sold</th>}
                <th className="num">Months to sell</th>
                {withCost && !phone && <th className="num">Money tied up</th>}
              </tr>
            </thead>
            <tbody>
              {items.map((r) => (
                <tr key={`${r.branch_id}-${r.product_id}`}>
                  <td>
                    <ToStock>{r.description}</ToStock><br />
                    <span className="hint">{r.code}, {nameOf(r.branch_id)}</span>
                    {phone && <><br /><span className="hint">{showQty(r.qty_on_hand ?? '0')} {r.unit} in stock, last sold {r.last_sold_on}</span></>}
                    {phone && r.value_at_cost_minor !== undefined && <><br /><span className="hint">Money tied up {money(r.value_at_cost_minor)}</span></>}
                    {r.better_at && <><br /><Better row={r} nameOf={nameOf} /></>}
                  </td>
                  {!phone && <td className="num">{showQty(r.qty_on_hand ?? '0')} {r.unit}</td>}
                  {!phone && <td>{r.last_sold_on}</td>}
                  <td className="num">
                    {r.capped ? 'over ' : ''}{r.months_of_stock}
                    {(r.days_counted ?? 90) < 90 && <><br /><span className="hint">new, pace of {r.days_counted} {r.days_counted === 1 ? 'day' : 'days'}</span></>}
                  </td>
                  {withCost && !phone && <td className="num">{moneyOrNone(r.value_at_cost_minor)}</td>}
                </tr>
              ))}
            </tbody>
          </table></div>
        )}
        {(data?.total ?? 0) > items.length && <p className="hint">Showing {items.length} of {data?.total}. Raise the Top number to see more.</p>}
        {data?.value_at_cost_minor !== undefined && (
          <p><strong>Money tied up in slow stock: {money(data.value_at_cost_minor)}</strong> <span className="hint">at cost, all {data.total} slow items in the branches shown.</span></p>
        )}
      </div>
    </Section>
  );
}

/** The two slow mover rules on Retail settings, saved with the settings version like every other retail setting. */
export function SlowMoverSetting({ read, onSaved }: { read: CapabilitiesRead; onSaved?: () => void }) {
  const queryClient = useQueryClient();
  const [months, setMonths] = useState(String(read.capabilities.slow_mover_months ?? 4));
  const [minSold, setMinSold] = useState(String(read.capabilities.slow_mover_min_sold ?? 0));
  const m = Number(months);
  const n = Number(minSold);
  const problem = !Number.isInteger(m) || m < 1 || m > 24 ? 'Months: a whole number from 1 to 24.'
    : !Number.isInteger(n) || n < 0 || n > 1_000_000 || minSold.trim() === '' ? 'Sold in 90 days: a whole number, 0 for none.' : null;
  const changed = m !== read.capabilities.slow_mover_months || n !== read.capabilities.slow_mover_min_sold;
  const save = useMutation({
    mutationFn: () => retail.changeCapabilities({ slow_mover_months: m, slow_mover_min_sold: n }, read.version),
    onSuccess: (next) => {
      queryClient.setQueryData(['retail', 'capabilities'], next);
      void queryClient.invalidateQueries({ queryKey: ['retail', 'stock-health'] });
      onSaved?.();
    },
  });
  return (
    <fieldset className="stack" data-tour="retail-slow-movers">
      <legend>Slow movers on Stock health</legend>
      <label htmlFor="slow-months">Slow when the stock lasts more than this many months</label>
      <input id="slow-months" inputMode="numeric" value={months} onChange={(e) => setMonths(e.target.value)} />
      <label htmlFor="slow-min-sold">Also slow when fewer than this many sold in 90 days (0 for none)</label>
      <input id="slow-min-sold" inputMode="numeric" value={minSold} onChange={(e) => setMinSold(e.target.value)} />
      {problem && <p className="hint" role="alert">{problem}</p>}
      <Problem error={save.error && new Error(changeFailureText(save.error, queryClient))} />
      <p className="cluster">
        <BusyButton type="button" className="rt-primary" busy={save.isPending} disabled={problem !== null || !changed} onClick={() => save.mutate()}>
          Save
        </BusyButton>

      </p>
    </fieldset>
  );
}
