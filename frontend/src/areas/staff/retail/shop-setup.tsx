import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useMemo, useState } from 'react';
import { retail, type Category, type Unit } from '../../../api/retail';
import { WORDS } from '../../../app/glossary';
import { ActionBar } from '../../../app/ui/ActionBar';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Hint } from '../../../app/ui/Hint';
import { Card, ListRow, RowList } from '../../../app/ui/ListRow';
import { Segmented } from '../../../app/ui/Segmented';
import { Skeleton } from '../../../app/ui/Skeleton';
import { SuccessScreen } from '../../../app/ui/SuccessScreen';
import { useOnline } from '../../../offline/offline-context';
import { PURPOSES, factorProblem, type Purpose } from './catalogue-packs';
import { SHOP_TYPES, shopTypeById } from './shop-starters';
import { applySetup, key, packNeeds, packText, planSetup, type SetupItem, type SetupResult } from './shop-setup-state';
import { useSetupHelper } from '../setup-helper/setup-helper';
import { Gate, Problem } from './ui';

// Set up your shop (issue #625, docs/specs/units-packs-and-costing.md section 9 step 5): a new shop owner should not build
// categories and units one by one. The owner says what kind of shop it is; the app lists that kind's categories, units and
// packs with their usual size, each ticked; the owner unticks what they do not want, changes a size or what a pack is used for, and
// taps Add these. The lists are bundled (shop-starters.ts), so they show offline; adding needs the network. Names the shop has
// are shown as already there and never added twice. Prices are never set here: each item's packs get their prices on the item.

export const SET_UP_TITLE = 'Set up your shop';
export const NOTHING_NEW = 'Your shop has all of these already.';
export const OFFLINE_ADD = 'You are offline. Connect to the internet to add these.';

/** What one pack row may change before it is added: its usual size and what it is for. */
type PackEdit = { size: string; purpose: Purpose };

/** What the success screen says under its title: what was added, else that some were left out, else that nothing was new. */
export function doneText(result: SetupResult): string {
  if (result.added.length > 0) return addedText(result);
  const n = result.skipped.length;
  return n === 0 ? NOTHING_NEW : `${n === 1 ? 'One was' : `${n} were`} left out. See why below.`;
}

export function addedText(result: SetupResult): string {
  const count = (kind: string) => result.added.filter((a) => a.kind === kind).length;
  const part = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
  return `Added ${part(count('category'), 'category', 'categories')}, ${part(count('unit'), 'unit', 'units')} and ${part(count('pack'), 'pack', 'packs')}.`;
}

function Choice({ item, ticked, onTick, meta }: { item: SetupItem; ticked: boolean; onTick: (on: boolean) => void; meta?: string }) {
  const id = `setup-${key(item.kind, item.name).replace(/[^a-z0-9]+/g, '-')}`;
  return (
    <label htmlFor={id}>
      <input id={id} type="checkbox" checked={ticked} onChange={(e) => onTick(e.target.checked)} /> {item.name}
      {meta && <span className="hint"> {meta}</span>}
    </label>
  );
}

export function ShopSetupPage() {
  const queryClient = useQueryClient();
  const online = useOnline();
  const helper = useSetupHelper();
  const [typeId, setTypeId] = useState('');
  const [unticked, setUnticked] = useState<Set<string>>(new Set());
  const [edits, setEdits] = useState<Record<string, PackEdit>>({});
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<SetupResult | null>(null);
  // A size's problem shows once its field is left, or Add these takes the person to it (docs/ui-rules.md rule 10).
  const [left, setLeft] = useState<Set<string>>(new Set());
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  const type = shopTypeById(typeId);
  const loading = categories.isPending || units.isPending;
  // Offline before the lists ever came, the bundled set still shows; what the shop has is checked again when Add these runs.
  const plan = useMemo(() => (type ? planSetup(type, categories.data ?? [], units.data ?? []) : []), [type, categories.data, units.data]);
  const open = plan.filter((i) => i.status !== 'there');
  const there = plan.filter((i) => i.status === 'there');
  const ticked = new Set(open.map((i) => key(i.kind, i.name)).filter((k) => !unticked.has(k)));
  const withEdits = (i: SetupItem): SetupItem => {
    const e = edits[key(i.kind, i.name)];
    return e ? { ...i, size: Number(e.size), purpose: e.purpose } : i;
  };
  const sizeProblem = (i: SetupItem) => factorProblem(edits[key(i.kind, i.name)]?.size ?? String(i.size));
  const chosen = open.filter((i) => ticked.has(key(i.kind, i.name)) && !packNeeds(i, ticked, units.data ?? []));
  const firstBadSize = chosen.find((i) => i.kind === 'pack' && sizeProblem(i) !== null);

  const tick = (i: SetupItem, on: boolean) => setUnticked((was) => {
    const next = new Set(was);
    if (on) next.delete(key(i.kind, i.name)); else next.add(key(i.kind, i.name));
    return next;
  });
  const edit = (i: SetupItem, change: Partial<PackEdit>) => setEdits((was) => {
    const k = key(i.kind, i.name);
    return { ...was, [k]: { size: was[k]?.size ?? String(i.size), purpose: was[k]?.purpose ?? i.purpose ?? 'both', ...change } };
  });

  const add = async () => {
    if (!type || busy) return;
    // Add these stays enabled; a wrong size sends nothing and the press takes the person to it (docs/ui-rules.md rule 6).
    if (firstBadSize) {
      setLeft((was) => new Set(was).add(key(firstBadSize.kind, firstBadSize.name)));
      document.getElementById(`setup-size-${firstBadSize.name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}`)?.focus();
      return;
    }
    setBusy(true);
    try {
      // Read what the shop has now, not what was on screen, so a name another phone added meanwhile is skipped too.
      const [nowCategories, nowUnits] = await Promise.all([
        queryClient.fetchQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories(), staleTime: 0 }) as Promise<Category[]>,
        queryClient.fetchQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits(), staleTime: 0 }) as Promise<Unit[]>,
      ]);
      const fresh = planSetup(type, nowCategories, nowUnits).map(withEdits);
      setResult(await applySetup(retail, fresh, ticked, nowUnits));
    } catch (error) {
      setResult({ added: [], skipped: [], error });
    } finally {
      setBusy(false);
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    }
  };

  if (result && !result.error) {
    return (
      <Gate screen="shopSetup" title={SET_UP_TITLE}>
        <SuccessScreen
          level={2}
          title={result.added.length > 0 ? 'Your shop is set up' : 'Nothing new was added'}
          detail={doneText(result)}
          actions={[
            // Step 2 (issue #626): with the setup helper on, the app reads the owner's list of items into the item import.
            ...(helper.enabled ? [{ label: 'Add your items from a list', to: '/staff/retail/catalogue/import', search: { shop: type?.label ?? '' } }] : []),
            { label: 'Add your first items', to: '/staff/retail/catalogue/products' },
            { label: 'Done', to: '/staff/retail/catalogue' },
          ]}
        />
        {result.added.length > 0 && (
          <Card title="What was added">
            <RowList label="What was added">
              {result.added.map((a) => <ListRow key={`${a.kind}-${a.name}`} title={a.name} meta={a.detail ?? (a.kind === 'unit' ? 'Unit' : 'Category')} />)}
            </RowList>
          </Card>
        )}
        {result.skipped.length > 0 && (
          <Card title="What was left out">
            <RowList label="What was left out">
              {result.skipped.map((s) => <ListRow key={s.name} title={s.name} meta={s.why} />)}
            </RowList>
          </Card>
        )}
      </Gate>
    );
  }

  return (
    <Gate screen="shopSetup" title={SET_UP_TITLE}>
      <fieldset data-tour="setup-type">
        <legend>What kind of shop is it?</legend>
        {SHOP_TYPES.map((t) => (
          <label key={t.id} htmlFor={`setup-type-${t.id}`}>
            <input id={`setup-type-${t.id}`} type="radio" name="setup-type" value={t.id} checked={typeId === t.id} onChange={() => { setTypeId(t.id); setUnticked(new Set()); setEdits({}); setLeft(new Set()); setResult(null); }} />{' '}
            {t.label} <span className="hint">{t.hint}</span>
          </label>
        ))}
      </fieldset>
      {type && (
        <Skeleton loading={loading && online} of={type.id}>
          <Hint>Untick what you do not sell, then tap Add these. Names your shop has are left as they are.</Hint>
          {open.length === 0 ? (
            <p className="empty-state" data-testid="setup-nothing-new">{NOTHING_NEW}</p>
          ) : (
            <div className="stack">
              {open.some((i) => i.kind === 'category') && (
                <Card title="Categories">
                  <Hint>The app makes the code letters of each category from its name, like BLB for Bulbs.</Hint>
                  {open.filter((i) => i.kind === 'category').map((i) => <Choice key={i.name} item={i} ticked={ticked.has(key(i.kind, i.name))} onTick={(on) => tick(i, on)} />)}
                </Card>
              )}
              {open.some((i) => i.kind === 'unit') && (
                <Card title="Units">
                  {open.filter((i) => i.kind === 'unit').map((i) => <Choice key={i.name} item={i} ticked={ticked.has(key(i.kind, i.name))} onTick={(on) => tick(i, on)} />)}
                </Card>
              )}
              {open.some((i) => i.kind === 'pack') && (
                <Card title={WORDS.packs}>
                  <Hint>The usual size is a starting number. Each item can have its own, and its own prices.</Hint>
                  {open.filter((i) => i.kind === 'pack').map((i) => {
                    const k = key(i.kind, i.name);
                    const on = ticked.has(k);
                    const needs = packNeeds(i, ticked, units.data ?? []);
                    const e = edits[k] ?? { size: String(i.size), purpose: i.purpose ?? 'both' };
                    const slug = i.name.toLowerCase().replace(/[^a-z0-9]+/g, '-');
                    const problem = left.has(k) ? factorProblem(e.size) : null;
                    return (
                      <div key={i.name} className="stack" data-testid={`setup-pack-${slug}`}>
                        <Choice item={i} ticked={on} onTick={(v) => tick(i, v)} meta={needs ?? (factorProblem(e.size) === null ? packText(e.size, i.of ?? '', e.purpose) : undefined)} />
                        {on && !needs && (
                          <>
                            <label htmlFor={`setup-size-${slug}`}>{`${i.of} in one ${i.name}`}</label>
                            <input id={`setup-size-${slug}`} inputMode="decimal" value={e.size} onChange={(ev) => edit(i, { size: ev.target.value })} onBlur={() => setLeft((was) => new Set(was).add(k))} aria-invalid={problem !== null} aria-describedby={problem !== null ? `setup-size-problem-${slug}` : undefined} />
                            {problem !== null && <p id={`setup-size-problem-${slug}`} className="rt-flag">{problem}</p>}
                            <p className="field-label">Used for</p>
                            <Segmented label={`Used for, ${i.name}`} options={PURPOSES} value={e.purpose} onChange={(purpose) => edit(i, { purpose })} />
                          </>
                        )}
                      </div>
                    );
                  })}
                </Card>
              )}
            </div>
          )}
          {there.length > 0 && <p className="hint" data-testid="setup-there">Already in your shop: {there.map((i) => i.name).join(', ')}.</p>}
          {result?.error != null && (
            <>
              {result.added.length > 0 && <p data-testid="setup-partly">{`${addedText(result)} Tap Add these again for the rest.`}</p>}
              <Problem error={result.error} />
            </>
          )}
          {!online && <p className="hint" data-testid="setup-offline">{OFFLINE_ADD}</p>}
          {open.length > 0 && (
            <ActionBar>
              <BusyButton busy={busy} type="button" className="rt-primary" disabled={!online || chosen.length === 0} onClick={() => void add()}>
                {chosen.length > 0 ? `Add these (${chosen.length})` : 'Add these'}
              </BusyButton>
            </ActionBar>
          )}
        </Skeleton>
      )}
    </Gate>
  );
}

export const ShopSetupRoute = createLazyRoute('/staff/retail/catalogue/set-up')({ component: ShopSetupPage });
