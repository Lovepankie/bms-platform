import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { businessToday } from '../../../api/retail';
import { retailAnalytics, type DamageShare, type DamageShareItem } from '../../../api/retail-analytics';
import { PeriodPicker, presetRange } from '../../../app/ui/PeriodPicker';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { Empty, Section } from './analytics-ui';
import { shown } from './ladder-state';
import { milliOf } from './maths';
import { PROFIT, canSeeProfit, holdsAt } from './permissions';
import { Problem, useBranchView } from './ui';

// Damage and use on Stock health (issue #822, ADR-127), as the owner's old app showed it: how much of each item was damaged
// and how much was used in the shop, each as a whole percent of the stock that passed through (the stock at the start plus
// what came in), with the quantities beside it, per item and per shop with a shop total, the highest first. Only a person with
// retail.profit.read asks for it; the server answers nobody else and only for the shops where they hold it. Words and numbers,
// never colour alone.

type NameOf = (id: string | undefined) => string;

export const BASE_HINT = 'Out of the stock that passed through: the stock at the start plus what came in.';

/** A quantity the server sent ("9247.000") for people, thousands grouped as elsewhere ("9,247"; issue #854). */
const showQty = (text: string): string => shown(BigInt(milliOf(text)));

/** "10%", "under 1%" for a little out of a lot, or null when nothing passed through to compare with. */
export function percentWords(pct: number | undefined, qty: string | undefined): string | null {
  if (pct === undefined || pct === null) return null;
  if (pct === 0 && Number(qty ?? '0') > 0) return 'under 1%';
  return `${pct}%`;
}

/** "Damaged 10%, 5 of 50 piece", or "Damaged 1 piece, no stock came in to compare with" when the base is 0. */
export function shareWords(label: string, pct: number | undefined, qty: string | undefined, base: string | undefined, unit?: string): string {
  const u = unit ? ` ${unit}` : '';
  const percent = percentWords(pct, qty);
  if (percent === null) return `${label} ${showQty(qty ?? '0')}${u}, no stock came in to compare with`;
  return `${label} ${percent}, ${showQty(qty ?? '0')} of ${showQty(base ?? '0')}${u}`;
}

function ItemRow({ item }: { item: DamageShareItem }) {
  return (
    <li className="rt-card" data-testid="damage-share-item">
      <strong>{item.description}</strong>
      <span className="hint">{item.code}</span>
      <div className="rt-row"><span className="num">{shareWords('Damaged', item.damaged_pct, item.damaged_qty, item.base_qty, item.unit)}</span></div>
      <div className="rt-row"><span className="num">{shareWords('Used', item.used_pct, item.used_qty, item.base_qty, item.unit)}</span></div>
      <span className="hint">{showQty(item.opening_qty ?? '0')} at the start, {showQty(item.received_qty ?? '0')} came in</span>
    </li>
  );
}

export function DamageShareView({ data, nameOf }: { data: DamageShare; nameOf: NameOf }) {
  const shops = data.branches ?? [];
  if (shops.length === 0) return <Empty>Nothing passed through in these days.</Empty>;
  return (
    <ul className="an-cards" data-testid="damage-share">
      {shops.map((shop) => (
        <li key={shop.branch_id} className="rt-card" data-testid="damage-share-shop">
          <h3>{nameOf(shop.branch_id)}</h3>
          <div className="rt-row"><span>Shop total</span></div>
          <div className="rt-row"><span className="num">{shareWords('Damaged', shop.damaged_pct, shop.damaged_qty, shop.base_qty)}</span></div>
          <div className="rt-row"><span className="num">{shareWords('Used', shop.used_pct, shop.used_qty, shop.base_qty)}</span></div>
          {(shop.items ?? []).length === 0 ? (
            <p className="hint">Nothing damaged or used here.</p>
          ) : (
            <ul className="an-cards">
              {(shop.items ?? []).map((item) => <ItemRow key={item.product_id} item={item} />)}
            </ul>
          )}
        </li>
      ))}
    </ul>
  );
}

/**
 * The section of Stock health, with its own period picker on This month; nothing at all without retail.profit.read at the shop
 * chosen (any shop when all are chosen, and the server then answers only those where it is held). A manager with it at one shop
 * looking at another sees no section, not a refusal (issue #854).
 */
export function DamageShareSection({ nameOf }: { nameOf: NameOf }) {
  const { me } = useStaff();
  const { all, branchId } = useBranchView();
  const owner = all ? canSeeProfit(me) : Boolean(branchId) && holdsAt(me, PROFIT, branchId);
  const [days, setDays] = useState(() => presetRange('month', businessToday()));
  const report = useQuery({
    queryKey: ['retail', 'damage-share', all ? 'all' : branchId, days.from, days.to],
    queryFn: () => retailAnalytics.damageShare({ branchId: all ? undefined : (branchId ?? ''), from: days.from, to: days.to }),
    enabled: owner && (all || branchId !== null),
  });
  if (!owner) return null;
  return (
    <Section title="Damage and use as a share of stock" hint={BASE_HINT}>
      <div data-tour="health-damage-share">
        <PeriodPicker from={days.from} to={days.to} label="Days of damage and use" maxDays={366} onChange={setDays} />
        {report.isPending && <SkeletonList label="Loading damage and use" />}
        <Problem error={report.error} />
        {report.data && <DamageShareView data={report.data} nameOf={nameOf} />}
      </div>
    </Section>
  );
}
