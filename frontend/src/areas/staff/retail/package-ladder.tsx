import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState } from 'react';
import { retail, type Pack, type Product, type Unit } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { WORDS } from '../../../app/glossary';
import { HelpTip } from '../../../app/ui/HelpTip';
import { Hint } from '../../../app/ui/Hint';
import { Stepper } from '../../../app/ui/Stepper';
import { icons } from '../../../components/icons';
import { asPurpose, usualFactor } from './catalogue-packs';
import { LOOP_WORDS, chain, changeWords, changesOf, hasLoop, holdsChain, holdsProblem, ladderOf, many, one, shown, soOne, type Rung } from './ladder-state';
import { Problem } from './ui';
import { UnitPicker } from './unit-picker';
import { showQty } from './maths';
import { hasOwnPrice, unitWord } from './units-state';

// Issue #699 (ADR-107): the package ladder, the same on the item page and on Add an item. Since issue #709 (D99 = A, ADR-107
// rule 8) it is built smallest first: the single unit, then each bigger package, one line per level,
// a dot with its number down a plain line, what one holds, and the pieces worked out in words under it: "So 1 carton = 120
// pieces". On the item page each number has a stepper; a change shows a check sheet with the before and after in words
// before it is saved, and the server works every level above out again. Look inside draws one package and what it holds.
// Words follow the owner's prototype (issue #699, package-ladder.html); colours are the theme's chart roles.

/** Issue #709 (D99 = A): the question that adds the next level up, on the item page and on Add an item. */
export const BIGGER_QUESTION = 'Does it come in a bigger pack?';

/** The colour role of a level: the smallest unit is muted, the packages take the chart colours in turn. */
const LEVEL_COLOURS = ['--color-text-muted', '--viz-1-value', '--viz-2-value', '--viz-3-value', '--color-link'];
const colourOf = (level: number) => LEVEL_COLOURS[level === 0 ? 0 : ((level - 1) % (LEVEL_COLOURS.length - 1)) + 1] as string;

/** A level's number on the ladder, 1 for the smallest unit. */
function levelOf(rungs: Rung[], unitId: string): number {
  const i = rungs.findIndex((r) => r.unitId === unitId);
  return i < 0 ? 1 : rungs.length - i + 1;
}

export type RungEdit = {
  /** The number shown for each level being changed, by unit id. */
  values: Record<string, string>;
  onChange: (rung: Rung, value: string) => void;
  /** Issue #709 (ADR-110): the units whose count is fixed, a dozen's 12, by unit id; their number has no stepper. */
  fixed?: Record<string, number>;
};

/**
 * The rungs as the ladder is built (D99 = A, ADR-107 rule 8): the single unit first, then each bigger package, each with its
 * total in single units. With `edit`, each number has a stepper.
 */
export function LadderRungs({ rungs, smallest, edit, changed, after }: {
  rungs: Rung[];
  smallest: string;
  edit?: RungEdit;
  changed?: string | null;
  /** The pieces each level will hold once a change is saved, by unit id, shown while it is checked. */
  after?: Record<string, bigint>;
}) {
  const id = useId();
  return (
    <ol className="ladder" aria-label="What one package holds, single unit first">
      <li className="ladder-rung" data-level={1}>
        <span className="ladder-dot" data-colour={colourOf(0)} aria-hidden="true">1</span>
        <div className="ladder-card">
          <div className="help-tip-heading">
            <p className="ladder-name">1 {unitWord(smallest)} <span className="ladder-tag">{WORDS.singleUnit}</span></p>
            <HelpTip tip="singleUnit" />
          </div>
          <p className="ladder-says">The one thing a customer can buy on its own. Stock is kept in {many(smallest)}.</p>
        </div>
      </li>
      {[...rungs].reverse().map((r0) => {
        const r = after?.[r0.unitId] !== undefined ? { ...r0, pieces: after[r0.unitId] as bigint } : r0;
        const level = levelOf(rungs, r.unitId);
        const value = edit?.values[r.unitId] ?? r.holdsQty;
        return (
          <li key={r.unitId} className="ladder-rung" data-level={level}>
            <span className="ladder-dot" data-colour={colourOf(level - 1)} aria-hidden="true">{level}</span>
            <div className={`ladder-card${changed === r.unitId ? ' ladder-card-changed' : ''}`}>
              <p className="ladder-name">1 {unitWord(r.unit)}</p>
              {edit && !edit.fixed?.[r.unitId] ? (
                <div className="ladder-holds">
                  <Stepper
                    id={`${id}-holds-${r.unitId}`}
                    label={`How many ${many(r.holdsUnit)} in one ${one(r.unit)}`}
                    name={`${one(r.holdsUnit)} in a ${one(r.unit)}`}
                    value={value}
                    min={2}
                    onChange={(next) => edit.onChange(r, next)}
                  />
                </div>
              ) : (
                <p className="ladder-holds">holds <strong>{showQty(r.holdsQty)}</strong> {Number(r.holdsQty) === 1 ? one(r.holdsUnit) : many(r.holdsUnit)}</p>
              )}
              <p className="ladder-says" aria-label={soOne(r, smallest)}>
                So 1 {one(r.unit)} = <strong>{shown(r.pieces)}</strong> {r.pieces === 1000n ? one(smallest) : many(smallest)}
              </p>
            </div>
          </li>
        );
      })}
    </ol>
  );
}

/** The chain under the ladder: "1 carton → 12 boxes → each 10 pieces", "= 120 pieces in one carton". */
export function LadderSum({ rungs, smallest }: { rungs: Rung[]; smallest: string }) {
  const said = chain(rungs, smallest);
  return (
    <p className="ladder-sum" aria-live="polite">
      {said ? (
        <>
          {said.words}
          <br />= <strong>{said.total}</strong>
        </>
      ) : `Sold only by the ${one(smallest)}. Add a bigger package if it comes in boxes or cartons.`}
    </p>
  );
}

/** Follows the theme, so the picture is drawn again in the dark colours. */
function useTheme(): string {
  const [theme, setTheme] = useState(() => (typeof document === 'undefined' ? '' : (document.documentElement.dataset.theme ?? '')));
  useEffect(() => {
    const watch = new MutationObserver(() => setTheme(document.documentElement.dataset.theme ?? ''));
    watch.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
    return () => watch.disconnect();
  }, []);
  return theme;
}

/** The colour of a role, read from the page, so the drawing follows the theme and the tenant colour. */
function roleColour(el: HTMLElement, role: string): string {
  return getComputedStyle(el).getPropertyValue(role).trim() || getComputedStyle(el).color;
}

/** Draws one of level `open` as nested boxes, down to three levels inside, then dots. */
export function drawPackage(canvas: HTMLCanvasElement, rungs: Rung[], open: number) {
  const ctx = canvas.getContext?.('2d');
  if (!ctx) return;
  const W = canvas.width;
  const H = canvas.height;
  ctx.clearRect(0, 0, W, H);
  // Levels by number: 1 is the smallest unit, then the rungs from the smallest up.
  const up = [...rungs].reverse();
  const CAP = 60;
  const BUDGET = 1500;
  let marks = 0;
  const holds = (level: number) => {
    const r = up[level - 2];
    return r ? Math.max(1, Math.round(Number(r.holdsQty))) : 0;
  };
  const inside = (level: number) => {
    const r = up[level - 2];
    return r ? levelOf(rungs, r.holdsUnitId) || 1 : 1;
  };
  const box = (level: number, x: number, y: number, w: number, h: number, depth: number) => {
    const colour = roleColour(canvas, colourOf(level - 1));
    ctx.strokeStyle = colour;
    ctx.lineWidth = Math.max(1, 3 - depth);
    const r = Math.min(10, w / 8, h / 8);
    ctx.beginPath();
    if (ctx.roundRect) ctx.roundRect(x, y, w, h, r);
    else ctx.rect(x, y, w, h);
    ctx.stroke();
    if (level <= 1) return;
    const held = holds(level);
    // Review of #710: at most CAP of what a package holds are drawn, the last cell saying how many more ("+9,941"), and
    // no more than BUDGET marks in all, so a package of 100,000 never stalls the phone.
    const n = Math.min(held, CAP);
    const child = inside(level);
    const pad = Math.max(2, Math.min(10, w / 30));
    const iw = w - pad * 2;
    const ih = h - pad * 2;
    let cols = Math.max(1, Math.round(Math.sqrt((n * iw) / ih)));
    const rows = Math.ceil(n / cols);
    while (cols * rows < n) cols += 1;
    const gw = iw / cols;
    const gh = ih / rows;
    const tiny = gw < 14 || gh < 14 || depth >= 3 || child <= 1;
    const more = (count: number, cx: number, cy: number, cw: number, ch: number) => {
      ctx.fillStyle = roleColour(canvas, '--color-text');
      ctx.font = `600 ${Math.max(10, Math.min(28, ch / 2.5))}px sans-serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(`+${count.toLocaleString('en-GB')}`, cx + cw / 2, cy + ch / 2, cw - 4);
    };
    let k = 0;
    let labelled = false;
    for (; k < n && marks < BUDGET; k++) {
      const cx = x + pad + (k % cols) * gw;
      const cy = y + pad + Math.floor(k / cols) * gh;
      marks += 1;
      // The last cell drawn says how many more there are: past the cap, or where the budget ran out (review of #710).
      const last = k === n - 1 || marks >= BUDGET;
      if (last && held > k + 1) {
        more(held - k, cx, cy, gw, gh);
        labelled = true;
      } else if (tiny) {
        ctx.fillStyle = roleColour(canvas, colourOf(child - 1));
        ctx.beginPath();
        ctx.arc(cx + gw / 2, cy + gh / 2, Math.max(1.2, Math.min(gw, gh) / 2.8), 0, Math.PI * 2);
        ctx.fill();
      } else {
        box(child, cx + 2, cy + 2, gw - 4, gh - 4, depth + 1);
      }
    }
    // Round 4 of the review of #710: the budget ran out inside a box this one holds, so this box says what it has left too,
    // in its own last corner.
    if (!labelled && held > k) more(held - k, x + w - pad - Math.min(gw, w / 3), y + h - pad - Math.min(gh, h / 4), Math.min(gw, w / 3), Math.min(gh, h / 4));
  };
  box(open, 8, 8, W - 16, H - 16, 0);
}

/** "1 carton holds 12 boxes, each holding 10 pieces. That is 120 pieces." */
export function lookInsideWords(rungs: Rung[], unitId: string, smallest: string): string {
  const top = rungs.find((r) => r.unitId === unitId);
  if (!top) return '';
  const parts: string[] = [];
  for (const at of holdsChain(rungs, top)) {
    parts.push(`${showQty(at.holdsQty)} ${Number(at.holdsQty) === 1 ? one(at.holdsUnit) : many(at.holdsUnit)}`);
  }
  return `1 ${one(top.unit)} holds ${parts.join(', each holding ')}. That is ${shown(top.pieces)} ${many(smallest)}.`;
}

/** Look inside: choose a package and see it drawn with what it holds, and the same in words. */
export function LookInside({ rungs, smallest }: { rungs: Rung[]; smallest: string }) {
  const id = useId();
  const [open, setOpen] = useState<string>('');
  const canvas = useRef<HTMLCanvasElement>(null);
  const theme = useTheme();
  const chosen = rungs.find((r) => r.unitId === open) ?? rungs[0];
  const level = chosen ? levelOf(rungs, chosen.unitId) : 0;
  useEffect(() => {
    if (canvas.current && chosen) drawPackage(canvas.current, rungs, level);
  }, [rungs, chosen, level, theme]);
  if (!chosen) return <p className="hint">Add a bigger package to see what it holds.</p>;
  const words = lookInsideWords(rungs, chosen.unitId, smallest);
  const shownLevels: { name: string; level: number }[] = [];
  for (const at of holdsChain(rungs, chosen)) {
    shownLevels.push({ name: unitWord(at.unit), level: levelOf(rungs, at.unitId) });
  }
  shownLevels.push({ name: unitWord(smallest), level: 1 });
  return (
    <section className="ladder-look stack" aria-labelledby={`${id}-h`}>
      <h3 id={`${id}-h`}>Look inside</h3>
      <label htmlFor={`${id}-open`}>Open one</label>
      {/* empty: Look inside is shown only while the item has a package (the early return above) */}
      <select id={`${id}-open`} value={chosen.unitId} onChange={(e) => setOpen(e.target.value)}>
        {rungs.map((r) => <option key={r.unitId} value={r.unitId}>1 {unitWord(r.unit)}</option>)}
      </select>
      <canvas ref={canvas} className="ladder-picture" width={960} height={560} role="img" aria-label={words} />
      <p className="ladder-caption">{words}</p>
      <ul className="ladder-legend" aria-label="Colours">
        {shownLevels.map((l) => (
          <li key={`${l.name}-${l.level}`}><span className="ladder-swatch" data-colour={colourOf(l.level - 1)} aria-hidden="true" />{l.name}</li>
        ))}
      </ul>
    </section>
  );
}

/** A level is changed: its unit, what it will hold and how many. */
type Pending = { rung: Rung; holdsUnitId: string; value: string };

/**
 * The item page's ladder (issue #699): the item's packages, single unit first (D99), each number with a stepper, a check sheet before a
 * change is saved, Add a bigger package and Remove the biggest. Shown with the item's packs while the units capability is on.
 */
export function ItemLadder({ product, packs, mayManage, mayPrice = true }: {
  product: Product;
  packs: Pack[];
  mayManage: boolean;
  /** retail.price.edit: a level with its own price is resized only by someone who may change prices (review of #710). */
  mayPrice?: boolean;
}) {
  const queryClient = useQueryClient();
  const smallest = product.unit ?? '';
  const smallestId = product.unit_id ?? '';
  const rungs = ladderOf(product, packs);
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits(), enabled: mayManage });
  const fixed = Object.fromEntries((units.data ?? []).filter((u) => u.fixed_count).map((u) => [u.id ?? '', u.fixed_count ?? 0]));
  const [pending, setPending] = useState<Pending | null>(null);
  const [adding, setAdding] = useState(false);
  const id = product.id ?? '';
  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['retail'] });
  const save = useMutation({
    mutationFn: (p: Pending) => retail.updatePack(id, p.rung.unitId, p.rung.pack?.version ?? 1, { holds_unit_id: p.holdsUnitId, holds_qty: Number(p.value.trim()) }),
    onSuccess: () => {
      setPending(null);
      refresh();
    },
    meta: { success: (_r: unknown, p: Pending) => `1 ${one(p.rung.unit)} of ${product.description ?? 'the item'} now holds ${p.value.trim()} ${many(p.rung.holdsUnit)}` },
  });
  const remove = useMutation({
    mutationFn: (r: Rung) => retail.updatePack(id, r.unitId, r.pack?.version ?? 1, { active: false }),
    onSuccess: refresh,
    meta: {
      success: (_r: unknown, r: Rung) => `${unitWord(r.unit)} removed. Stock is unchanged`,
      // A pack made before ADR-107 says nothing of what it holds and may be 12.5 pieces: it comes back with its own factor.
      undo: (done: Pack, r: Rung) => () => retail.updatePack(id, r.unitId, done.version ?? 1, r.pack?.holds_qty === undefined
        ? { active: true, factor: r.pack?.factor ?? '' }
        : { active: true, holds_unit_id: r.holdsUnitId, holds_qty: r.pack.holds_qty }),
    },
  });
  const changes = pending && holdsProblem(pending.value, pending.rung) === null
    ? changesOf(rungs, pending.rung.unitId, { unitId: pending.holdsUnitId, qty: pending.value.trim() }, smallestId)
    : [];
  const problem = pending ? holdsProblem(pending.value, pending.rung) : null;
  // A level the change resizes that has its own price: said before saving, not refused after it.
  const priced = mayPrice ? [] : changes.filter((c) => hasOwnPrice(rungs.find((r) => r.unitId === c.unitId)?.pack));
  const top = rungs[0];
  return (
    <section className="ladder-panel stack" data-testid="item-ladder" aria-labelledby={`ladder-${id}`}>
      <h3 id={`ladder-${id}`}>What one package holds</h3>
      <Hint full>Single unit first, then each bigger package. Change a number and the bigger packages follow. Your stock is kept in {many(smallest)} and never changes.</Hint>
      {hasLoop(rungs) && <p role="alert" className="alert alert-warning" data-testid="ladder-loop">{LOOP_WORDS}</p>}
      <LadderRungs
        rungs={rungs}
        smallest={smallest}
        changed={pending?.rung.unitId ?? null}
        after={Object.fromEntries(changes.map((c) => [c.unitId, c.after]))}
        edit={mayManage ? {
          fixed,
          values: pending ? { [pending.rung.unitId]: pending.value } : {},
          onChange: (rung, value) => {
            save.reset();
            setPending(value.trim() === rung.holdsQty ? null : { rung, holdsUnitId: rung.holdsUnitId, value });
          },
        } : undefined}
      />
      {pending && problem !== null && (
        <div className="stack" data-testid="ladder-refused">
          <p role="alert" className="alert alert-danger">{problem}</p>
          <button type="button" className="btn" onClick={() => setPending(null)}>Keep it as it was</button>
        </div>
      )}
      {pending && problem === null && (
        <div className="ladder-check stack" role="group" aria-labelledby={`ladder-check-${id}`} data-testid="ladder-check">
          <p id={`ladder-check-${id}`}><strong>Check before saving</strong></p>
          <ul>{changes.map((c) => <li key={c.unit}>{changeWords(c, smallest)}</li>)}</ul>
          <p>{product.stock_on_hand !== undefined ? `Your stock stays ${shown(BigInt(Math.round(Number(product.stock_on_hand) * 1000)))} ${many(smallest)}.` : 'Your stock stays as it is.'} Only how it is shown changes. Past sales keep the size they had.</p>
          {priced.length > 0 && (
            <p role="note" className="alert alert-warning" data-testid="ladder-priced">
              {`The ${priced.map((c) => one(c.unit)).join(' and the ')} ${priced.length > 1 ? 'have their own prices, so their sizes change' : 'has its own price, so its size changes'} what its pieces sell for. Ask someone who may change prices to save this.`}
            </p>
          )}
          <Problem error={save.error} />
          <div className="ladder-actions">
            <BusyButton busy={save.isPending} type="button" className="rt-primary" disabled={priced.length > 0} onClick={() => save.mutate(pending)}>OK, save</BusyButton>
            <button type="button" className="btn" onClick={() => { setPending(null); save.reset(); }}>Keep it as it was</button>
          </div>
        </div>
      )}
      <LadderSum rungs={rungs} smallest={smallest} />
      {mayManage && !adding && <p className="field-label">{BIGGER_QUESTION}</p>}
      {mayManage && (
        <div className="ladder-actions">
          {!adding && (
            <button type="button" className="btn" onClick={() => setAdding(true)}>
              {icons.plus}
              <span>Add a bigger package</span>
            </button>
          )}
          {top && !adding && (
            <BusyButton busy={remove.isPending} type="button" className="btn-ghost" onClick={() => remove.mutate(top)}>Remove the biggest</BusyButton>
          )}
        </div>
      )}
      <Problem error={remove.error} />
      {adding && <AddBigger product={product} rungs={rungs} onDone={() => setAdding(false)} />}
      {rungs.length > 0 && <LookInside rungs={rungs} smallest={smallest} />}
    </section>
  );
}

/** Add a bigger package on top of the ladder: its unit and how many of the biggest it holds. */
function AddBigger({ product, rungs, onDone }: { product: Product; rungs: Rung[]; onDone: () => void }) {
  const queryClient = useQueryClient();
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  const top = rungs[0];
  const holdsUnitId = top?.unitId ?? product.unit_id ?? '';
  const holdsUnit = top?.unit ?? product.unit ?? '';
  const [unitId, setUnitId] = useState('');
  const [typed, setValue] = useState('');
  const [tried, setTried] = useState(false);
  const used = new Set([product.unit_id, ...rungs.map((r) => r.unitId)]);
  const choices = (units.data ?? []).filter((u) => u.active !== false && !used.has(u.id));
  const chosen = choices.find((u) => u.id === unitId);
  const name = one(chosen?.name) || 'package';
  // Issue #709 (ADR-110): a dozen always holds 12, filled in and not changed.
  const fixed = chosen?.fixed_count;
  const value = fixed ? String(fixed) : typed;
  const problem = holdsProblem(value, { unit: name, holdsUnit });
  const add = useMutation({
    mutationFn: () => retail.addPack(product.id ?? '', { unit_id: unitId, holds_unit_id: holdsUnitId, holds_qty: Number(value.trim()), purpose: asPurpose(chosen?.usual_pack?.purpose) }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onDone();
    },
    meta: { success: `${unitWord(chosen?.name)} added. 1 ${name} holds ${value.trim()} ${many(holdsUnit)}` },
  });
  const choose = (next: string) => {
    setUnitId(next);
    const unit: Unit | undefined = choices.find((u) => u.id === next);
    const usual = usualFactor(unit, { unit_id: holdsUnitId });
    if (usual && typed.trim() === '') setValue(usual);
  };
  return (
    <div className="stack ladder-add" data-testid="ladder-add">
      <UnitPicker
        id={`ladder-add-unit-${product.id ?? ''}`}
        kind="package"
        label="The bigger package"
        hint="For example carton, bale or crate."
        units={choices}
        loading={units.data === undefined}
        load={units}
        value={unitId}
        onChange={choose}
        error={unitId === '' ? 'Choose the package.' : null}
        showError={tried}
      />
      {fixed
        ? <p data-testid="ladder-add-fixed">{`1 ${name} holds ${fixed} ${many(holdsUnit)}. A ${name} always holds ${fixed}.`}</p>
        : <Stepper id={`ladder-add-holds-${product.id ?? ''}`} label={`How many ${many(holdsUnit)} in one ${name}`} value={value} min={2} onChange={setValue} invalid={tried && problem !== null} />}
      {tried && problem !== null && <p className="rt-flag">{problem}</p>}
      {problem === null && <p className="ladder-says">So 1 {name} = <strong>{shown((top?.pieces ?? 1000n) * BigInt(value.trim()))}</strong> {many(product.unit)}</p>}
      <Problem error={add.error} />
      <div className="ladder-actions">
        <BusyButton busy={add.isPending} type="button" className="rt-primary" onClick={() => { setTried(true); if (unitId !== '' && problem === null) add.mutate(); }}>Add it</BusyButton>
        <button type="button" className="btn-ghost" onClick={onDone}>Cancel</button>
      </div>
    </div>
  );
}
