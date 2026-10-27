import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from '@tanstack/react-router';
import type { Me } from '../../../api/client';
import { BusyButton } from '../../../app/ui/BusyButton';
import { RetailError, retail, type Pack, type Product, type Unit } from '../../../api/retail';
import { useStaff } from '../context';
import { showQty } from './maths';
import { Money, Problem, money } from './ui';
import { SETTINGS_MANAGE, UNITS, capabilityOn, holds } from './permissions';
import { factorMilli, hasOwnPrice, packPriceMinor } from './units-state';
import { WORDS } from '../../../app/glossary';
import { EmptyChoice, showsEmptyChoice } from '../../../app/ui/EmptyChoice';
import { Hint } from '../../../app/ui/Hint';
import { RowChevron } from '../../../app/ui/ListRow';
import { MoneyInput } from '../../../app/ui/MoneyInput';
import { Segmented } from '../../../app/ui/Segmented';
import { Sheet } from '../../../app/ui/Sheet';

// The packs of an item (ADR-033, issue #194): a unit and the number of base units in one pack, for example a carton of
// twelve pieces. Issue #624 (ADR-099): each pack says what it is for, and a selling pack has its own selling price, which a
// walk-in sale takes; the app suggests the piece price times the size. Edit item shows them in one small table (pack, size,
// price), and Change opens the pack in a sheet. Offered only while /me lists the units capability and the user may manage
// the catalogue; the price is set only by someone who may change prices (retail.price.edit).

export type Purpose = 'both' | 'sell' | 'buy';

export const PURPOSES = [
  { value: 'both', label: 'Both' },
  { value: 'sell', label: 'Selling' },
  { value: 'buy', label: 'Buying' },
] as const satisfies readonly { value: Purpose; label: string }[];

/** True when the text is a factor the server accepts: at least 1, at most three places, no comma. */
export function factorProblem(text: string): string | null {
  const milli = factorMilli(text);
  if (milli === null || milli < 1000n) return 'Enter how many of the item are in one pack: 1 or more, for example 12 or 12.5.';
  return null;
}

export function purposeOf(pack: Pack): Purpose {
  return pack.purpose === 'buy' || pack.purpose === 'sell' ? pack.purpose : 'both';
}

/** The price a walk-in sale takes for the pack, and whether it is the pack's own or the suggested one; null when not sold. */
export function shownPackPrice(product: Product, pack: Pack): { minor: number; own: boolean } | null {
  if (purposeOf(pack) === 'buy') return null;
  if (pack.sell_minor !== undefined) return { minor: pack.sell_minor, own: true };
  return { minor: pack.suggested_sell_minor ?? packPriceMinor(product.sell_minor ?? 0, pack.factor), own: false };
}

/** "Suggested UGX 1,200: 12 at UGX 100 each." The piece price times the size, as the server suggests it. */
export function suggestedHint(product: Product, factor: string): string | null {
  if (product.sell_minor === undefined || factorProblem(factor) !== null) return null;
  return `Suggested ${money(packPriceMinor(product.sell_minor, factor))}: ${showQty(factor)} at ${money(product.sell_minor)} each. Leave it empty to use it.`;
}

/**
 * The warning while a price is typed (review of #624), only when the caller can see cost: the pack's price (the typed one, else
 * the suggested one) is not above its size times the last price paid per piece. The saved pack's Below cost is judged on the
 * server against the average cost per piece (ADR-079); a sale below cost is refused unless the seller may sell below cost.
 */
export function packBelowCostHint(product: Product, factor: string, priceMinor: number | null): string | null {
  const cost = product.cost_minor;
  if (cost === undefined || cost <= 0 || factorProblem(factor) !== null) return null;
  const packCost = packPriceMinor(cost, factor);
  const price = priceMinor ?? packPriceMinor(product.sell_minor ?? 0, factor);
  if (price > packCost) return null;
  return `This price is not above what ${showQty(factor)} ${product.unit ?? ''} cost at the last price paid, ${money(packCost)}. A sale at it is refused unless the seller may sell below cost.`;
}

/**
 * The request that saves a pack's sheet: only what changed. An emptied price drops the pack's own price, so it sells at the
 * suggested one again; a pack made for buying only loses its price on the server, so none is sent.
 */
export function packChange(pack: Pack, form: { factor: string; purpose: Purpose; priceMinor: number | null }, mayPrice: boolean) {
  const body: { factor?: string; purpose?: Purpose; sell_minor?: number; clear_sell_minor?: boolean } = {};
  if (form.factor.trim() !== showQty(pack.factor ?? '')) body.factor = form.factor.trim();
  if (form.purpose !== purposeOf(pack)) body.purpose = form.purpose;
  if (mayPrice && form.purpose !== 'buy') {
    if (form.priceMinor === null && pack.sell_minor !== undefined) body.clear_sell_minor = true;
    else if (form.priceMinor !== null && form.priceMinor !== pack.sell_minor) body.sell_minor = form.priceMinor;
  }
  return body;
}

function PackSheet({ product, pack, mayPrice, onClose }: { product: Product; pack: Pack; mayPrice: boolean; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [factor, setFactor] = useState(showQty(pack.factor ?? ''));
  const [purpose, setPurpose] = useState<Purpose>(purposeOf(pack));
  const [priceMinor, setPriceMinor] = useState<number | null>(pack.sell_minor ?? null);
  const change = useMutation({
    mutationFn: (body: Parameters<typeof retail.updatePack>[3]) => retail.updatePack(product.id ?? '', pack.unit_id ?? '', pack.version ?? 1, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onClose();
    },
  });
  const problem = factorProblem(factor);
  const body = packChange(pack, { factor, purpose, priceMinor }, mayPrice);
  // Review of #624: a pack with its own price keeps its size and stays sold for someone who may not change prices. The flag
  // comes to every caller, the amount only to one who may see prices.
  const locked = hasOwnPrice(pack) && !mayPrice;
  const low = purpose === 'buy' ? null : packBelowCostHint(product, factor, mayPrice ? priceMinor : (pack.sell_minor ?? null));
  const id = pack.unit_id ?? '';
  return (
    <Sheet
      open
      onClose={onClose}
      title={`${pack.unit} of ${product.description}`}
      actions={
        <>
          <BusyButton busy={change.isPending && change.variables?.active === undefined} type="button" className="rt-primary" disabled={problem !== null || change.isPending || Object.keys(body).length === 0} onClick={() => change.mutate(body)}>
            Save
          </BusyButton>
          {/* Switching on again confirms the number: the item's unit may have changed while the pack was off. */}
          <BusyButton busy={change.isPending && change.variables?.active !== undefined} type="button" aria-label={`${pack.active === false ? 'Switch on' : 'Switch off'} the ${pack.unit} pack`} disabled={change.isPending || (pack.active === false && problem !== null)} onClick={() => change.mutate(pack.active === false ? { active: true, factor: factor.trim() } : { active: false })}>
            {pack.active === false ? 'Switch on' : 'Switch off'}
          </BusyButton>
        </>
      }
    >
      <label htmlFor={`pack-factor-${id}`}>{`${product.unit ?? 'Items'} in one ${pack.unit}`}</label>
      <input
        id={`pack-factor-${id}`}
        inputMode="decimal"
        value={factor}
        onChange={(e) => setFactor(e.target.value)}
        readOnly={locked}
        aria-invalid={problem !== null}
        aria-describedby={problem !== null ? `pack-factor-problem-${id}` : undefined}
      />
      {problem !== null && <p id={`pack-factor-problem-${id}`} className="rt-flag">{problem}</p>}
      <p className="field-label">Used for</p>
      <Segmented label="Used for" options={locked ? PURPOSES.filter((o) => o.value !== 'buy') : PURPOSES} value={purpose} onChange={setPurpose} />
      {purpose === 'buy' ? (
        <Hint>A pack only for buying is not offered when selling, so it has no selling price.</Hint>
      ) : mayPrice ? (
        <MoneyInput id={`pack-price-${id}`} label={`Selling price of one ${pack.unit}`} valueMinor={priceMinor} onChange={setPriceMinor} hint={suggestedHint(product, factor) ?? undefined} />
      ) : (
        <Hint full>
          {locked
            ? 'This pack has its own price. Ask someone who may change prices to change its size or make it only for buying.'
            : 'Only someone who may change prices sets a pack\'s selling price.'}
        </Hint>
      )}
      {low !== null && <p className="rt-flag">{low}</p>}
      {pack.below_cost === true && purpose !== 'buy' && (
        <p role="note" className="alert alert-warning">
          {`This price is not above what ${showQty(pack.factor ?? '')} ${product.unit ?? ''} cost you on average. A sale at it needs permission to sell below cost.`}
        </p>
      )}
      <Problem error={change.error} />
    </Sheet>
  );
}

/** Issue #625: the unit's usual pack size, when it counts what this item is counted in, as the number to start from. */
export function usualFactor(unit: Unit | undefined, product: Product): string | null {
  const pack = unit?.usual_pack;
  return pack?.size && pack.of_unit_id === product.unit_id ? showQty(pack.size) : null;
}

/**
 * Review of #628: the size to show when another unit is chosen. A size filled in from the last unit's usual pack (or none) makes
 * way for this unit's usual size, or for nothing when it has none; a size the owner typed stays.
 */
export function factorForUnit(factor: string, filled: string, usual: string | null): string {
  return factor.trim() === '' || factor === filled ? (usual ?? '') : factor;
}

/**
 * Merge of #627 and #628: a pack on an item starts with the unit's usual Used for, the same purpose (ADR-099, ADR-100). A Used
 * for filled in from the last unit (or the starting Both) makes way for it, or for Both when the unit has none; one the owner
 * chose stays.
 */
export function purposeForUnit(purpose: Purpose, filled: Purpose, unit: Unit | undefined): Purpose {
  if (purpose !== filled) return purpose;
  const usual = unit?.usual_pack?.purpose;
  return usual === 'buy' || usual === 'sell' ? usual : 'both';
}

export function PackEditor({ product }: { product: Product }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const allowed = capabilityOn(me, 'retail', UNITS) && holds(me, 'retail.catalogue.manage');
  const mayPrice = holds(me, 'retail.price.edit');
  const id = product.id ?? '';
  const packs = useQuery({ queryKey: ['retail', 'packs', id], queryFn: () => retail.listPacks(id), enabled: allowed });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits(), enabled: allowed });
  const [unitId, setUnitId] = useState('');
  const [factor, setFactor] = useState('');
  const [purpose, setPurpose] = useState<Purpose>('both');
  const [priceMinor, setPriceMinor] = useState<number | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  // The size and Used for last filled in from a unit's usual pack (issue #625): another unit replaces them, but never what the
  // owner chose.
  const [filled, setFilled] = useState<{ factor: string; purpose: Purpose }>({ factor: '', purpose: 'both' });
  const chooseUnit = (unit: Unit | undefined) => {
    setUnitId(unit?.id ?? '');
    const nextFactor = factorForUnit(factor, filled.factor, usualFactor(unit, product));
    const nextPurpose = purposeForUnit(purpose, filled.purpose, unit);
    setFactor(nextFactor);
    setPurpose(nextPurpose);
    // Only what changed counts as filled in, so a size or Used for the owner chose is never taken for one the app filled.
    setFilled({ factor: nextFactor === factor ? filled.factor : nextFactor, purpose: nextPurpose === purpose ? filled.purpose : nextPurpose });
  };
  const add = useMutation({
    mutationFn: () =>
      retail.addPack(id, {
        unit_id: unitId,
        factor: factor.trim(),
        purpose,
        ...(mayPrice && purpose !== 'buy' && priceMinor !== null ? { sell_minor: priceMinor } : {}),
      }),
    onSuccess: () => {
      setUnitId('');
      setFactor('');
      setPurpose('both');
      setPriceMinor(null);
      setFilled({ factor: '', purpose: 'both' });
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  if (!allowed) return null;
  const rows = packs.data ?? [];
  const used = new Set(rows.map((k) => k.unit_id));
  // The item's own unit and the units that are packs already are not offered; a switched-off unit cannot be chosen.
  const choices = (units.data ?? []).filter((u) => u.active !== false && u.id !== product.unit_id && !used.has(u.id));
  const problem = factorProblem(factor);
  const open = rows.find((k) => k.unit_id === editing);
  const low = purpose === 'buy' ? null : packBelowCostHint(product, factor, mayPrice ? priceMinor : null);
  return (
    <fieldset data-testid="pack-editor" onKeyDown={(e) => { if (e.key === 'Enter') e.preventDefault(); }}>
      <legend>{WORDS.packs}</legend>
      <Hint>Sell or buy this item by the pack, each with its own price. Stock is always counted in {product.unit}.</Hint>
      {packs.isPending && <p className="loading">Loading</p>}
      <Problem error={packs.error} />
      {packs.data && rows.length === 0 && <p className="empty-state">No cartons, boxes or other packs yet.</p>}
      {rows.length > 0 && (
        <div className="table-wrap" tabIndex={0}>
          <table data-testid="pack-table">
            <caption className="visually-hidden">{`The packs of ${product.description}: size and selling price`}</caption>
            <thead>
              <tr>
                <th scope="col">Pack</th>
                <th scope="col" className="num">Size</th>
                <th scope="col" className="num">Selling price</th>
                <th scope="col"><span className="visually-hidden">Change</span></th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <th scope="row">{product.unit}</th>
                <td className="num">1</td>
                <td className="num">{product.sell_minor === undefined ? '' : <Money minor={product.sell_minor} />}</td>
                <td />
              </tr>
              {rows.map((k) => {
                const price = shownPackPrice(product, k);
                return (
                  <tr key={`${k.unit_id}-${k.version}`}>
                    <th scope="row">
                      {k.unit}
                      {k.active === false && <> <span className="badge badge-warning">Switched off</span></>}
                      {purposeOf(k) === 'buy' && <> <span className="badge">Buying only</span></>}
                      {purposeOf(k) === 'sell' && <> <span className="badge">Selling only</span></>}
                    </th>
                    <td className="num">{`${showQty(k.factor ?? '')} ${product.unit ?? ''}`}</td>
                    <td className="num">
                      {price === null ? 'Not sold' : (
                        <>
                          <Money minor={price.minor} />
                          {!price.own && <span className="hint"> suggested</span>}
                          {k.below_cost === true && <> <span className="badge badge-warning">Below cost</span></>}
                        </>
                      )}
                    </td>
                    <td>
                      <button type="button" className="btn-sm" aria-label={`Change the ${k.unit} pack`} onClick={() => setEditing(k.unit_id ?? null)}>
                        Change
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {open && <PackSheet key={`${open.unit_id}-${open.version}`} product={product} pack={open} mayPrice={mayPrice} onClose={() => setEditing(null)} />}
      {showsEmptyChoice(units, units.data && choices) ? (
        <EmptyChoice id="pack-unit" label="Add a pack" load={units} text="No other unit to make a pack of yet." add={{
          label: 'Add a new unit', name: 'Name of the new unit, such as carton', send: 'Add unit', maxLength: 50,
          create: (name) => retail.createUnit(name),
          onAdded: (unit) => {
            queryClient.setQueryData<Unit[]>(['retail', 'catalogue', 'units'], (was) => [...(was ?? []).filter((u) => u.id !== unit.id), unit]);
            void queryClient.invalidateQueries({ queryKey: ['retail', 'catalogue', 'units'] });
            setUnitId(unit.id ?? '');
          },
        }} />
      ) : (
      <>
      <label htmlFor="pack-unit">Add a pack</label>
      <select id="pack-unit" value={unitId} onChange={(e) => chooseUnit(choices.find((u) => u.id === e.target.value))}>
        <option value="">Choose a unit</option>
        {choices.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
      </select>
      </>
      )}
      <label htmlFor="pack-factor">Items in one pack</label>
      <input
        id="pack-factor"
        inputMode="decimal"
        value={factor}
        onChange={(e) => setFactor(e.target.value)}
        aria-invalid={factor.trim() !== '' && problem !== null}
        aria-describedby={factor.trim() !== '' && problem !== null ? 'pack-factor-problem' : undefined}
        placeholder="12"
      />
      {factor.trim() !== '' && problem !== null && <p id="pack-factor-problem" className="rt-flag">{problem}</p>}
      <p className="field-label">Used for</p>
      <Segmented label="Used for" options={PURPOSES} value={purpose} onChange={setPurpose} />
      {mayPrice && purpose !== 'buy' && (
        <MoneyInput id="pack-price" label="Selling price of one pack" valueMinor={priceMinor} onChange={setPriceMinor} hint={suggestedHint(product, factor) ?? 'Leave it empty to sell at the piece price times the size.'} />
      )}
      {!mayPrice && purpose !== 'buy' && <Hint>Only someone who may change prices sets a pack&apos;s price; until then it sells at the piece price times the size.</Hint>}
      {low !== null && <p className="rt-flag">{low}</p>}
      <Problem error={add.error} />
      <BusyButton busy={add.isPending} type="button" disabled={unitId === '' || problem !== null} onClick={() => add.mutate()}>
        Add pack
      </BusyButton>
    </fieldset>
  );
}

export const PACKS_QUESTION = 'Also sold or bought in cartons or boxes?';
export const ASK_OWNER_PACKS = 'Ask the owner to turn on cartons in Retail settings.';
export const PACKS_ON_ADDING = 'Cartons are on. Once the item is added, tap Edit on it to add its cartons and boxes.';

/** The /me of every signed-in query with packs listed among the retail capabilities. */
export function withPacksOn(me: Me): Me {
  const retailOn = me.module_capabilities?.retail ?? [];
  return retailOn.includes(UNITS) ? me : { ...me, module_capabilities: { ...me.module_capabilities, retail: [...retailOn, UNITS].sort() } };
}

/**
 * Issue #630: while packs are off, the item form says in one line that they exist. Someone who manages settings turns them on
 * here, through the capabilities route with its If-Match, and the pack section shows at once; anyone else reads who can.
 */
export function PacksTurnOn({ adding }: { adding: boolean }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [done, setDone] = useState(false);
  const turnOn = useMutation({
    mutationFn: async () => {
      // Read, then send only units with that version; the server merges it. A change by someone else in between is read
      // again once, and a second conflict is shown.
      for (let attempt = 0; ; attempt += 1) {
        const read = await retail.capabilities();
        if (read.capabilities.units === true) return read;
        try {
          return await retail.changeCapabilities({ units: true }, read.version);
        } catch (error) {
          if (attempt > 0 || !(error instanceof RetailError && error.code === 'version_conflict')) throw error;
        }
      }
    },
    onSuccess: (next) => {
      queryClient.setQueryData(['retail', 'capabilities'], next);
      queryClient.setQueriesData<Me>({ queryKey: ['me'] }, (was) => (was ? withPacksOn(was) : was));
      void queryClient.invalidateQueries({ queryKey: ['me'] });
      setDone(true);
    },
  });
  if (!holds(me, 'retail.catalogue.manage')) return null;
  if (capabilityOn(me, 'retail', UNITS)) return done && adding ? <p role="status" data-testid="packs-on">{PACKS_ON_ADDING}</p> : null;
  return (
    <div className="stack" data-testid="packs-turn-on">
      <p>{PACKS_QUESTION}</p>
      {holds(me, SETTINGS_MANAGE) ? (
        <>
          <Problem error={turnOn.error} />
          {/* .btn is the secondary style (buttons.css): Add item or Save changes stays the one primary action (rule 6). */}
          <BusyButton busy={turnOn.isPending} type="button" className="btn" onClick={() => turnOn.mutate()}>Turn on cartons</BusyButton>
        </>
      ) : (
        <p className="hint" data-testid="packs-ask">{ASK_OWNER_PACKS}</p>
      )}
      <Link className="link-row" to="/staff/help/$articleId" params={{ articleId: 'sell-or-buy-in-cartons' }}>
        How do I sell or buy in cartons?
        <RowChevron />
      </Link>
    </div>
  );
}
