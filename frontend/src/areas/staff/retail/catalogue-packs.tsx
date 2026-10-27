import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail, type Pack, type Product, type Unit } from '../../../api/retail';
import { useStaff } from '../context';
import { showQty } from './maths';
import { Problem } from './ui';
import { UNITS, capabilityOn, holds } from './permissions';
import { factorMilli } from './units-state';
import { WORDS } from '../../../app/glossary';
import { EmptyChoice, showsEmptyChoice } from '../../../app/ui/EmptyChoice';

// Pack sizes of an item (ADR-033, issue #194): a unit and the number of base units in one pack, for
// example a carton of twelve pieces. Offered only while /me lists the units capability and the user may
// manage the catalogue. The sale and restock forms then offer the item in each active pack.

/** True when the text is a factor the server accepts: at least 1, at most three places, no comma. */
export function factorProblem(text: string): string | null {
  const milli = factorMilli(text);
  if (milli === null || milli < 1000n) return 'Enter how many of the item are in one pack: 1 or more, for example 12 or 12.5.';
  return null;
}

function PackRow({ productId, pack }: { productId: string; pack: Pack }) {
  const queryClient = useQueryClient();
  const [factor, setFactor] = useState(showQty(pack.factor ?? ''));
  const change = useMutation({
    mutationFn: (body: { factor?: string; active?: boolean }) => retail.updatePack(productId, pack.unit_id ?? '', pack.version ?? 1, body),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['retail'] }),
  });
  const problem = factorProblem(factor);
  return (
    <li className="rt-card">
      <p>
        <strong>{pack.unit}</strong> {pack.active === false && <span className="badge badge-warning">Switched off</span>}
      </p>
      <label htmlFor={`pack-factor-${pack.unit_id}`}>Items in one {pack.unit}</label>
      <input
        id={`pack-factor-${pack.unit_id}`}
        inputMode="decimal"
        value={factor}
        onChange={(e) => setFactor(e.target.value)}
        aria-invalid={problem !== null}
        aria-describedby={problem !== null ? `pack-factor-problem-${pack.unit_id}` : undefined}
      />
      {problem !== null && <p id={`pack-factor-problem-${pack.unit_id}`} className="rt-flag">{problem}</p>}
      <p className="cluster">
        <BusyButton busy={change.isPending && change.variables?.active === undefined} type="button" className="btn-sm" aria-label={`Save the number for ${pack.unit}`} disabled={problem !== null || change.isPending || factor.trim() === showQty(pack.factor ?? '')} onClick={() => change.mutate({ factor: factor.trim() })}>
          Save the number
        </BusyButton>
        {/* Switching on again confirms the number: the item's unit may have changed while the pack was off. */}
        <BusyButton busy={change.isPending && change.variables?.active !== undefined} type="button" className="btn-sm" aria-label={`${pack.active === false ? 'Switch on' : 'Switch off'} the ${pack.unit} pack`} disabled={change.isPending || (pack.active === false && problem !== null)} onClick={() => change.mutate(pack.active === false ? { active: true, factor: factor.trim() } : { active: false })}>
          {pack.active === false ? 'Switch on' : 'Switch off'}
        </BusyButton>
      </p>
      <Problem error={change.error} />
    </li>
  );
}

export function PackEditor({ product }: { product: Product }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const allowed = capabilityOn(me, 'retail', UNITS) && holds(me, 'retail.catalogue.manage');
  const id = product.id ?? '';
  const packs = useQuery({ queryKey: ['retail', 'packs', id], queryFn: () => retail.listPacks(id), enabled: allowed });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits(), enabled: allowed });
  const [unitId, setUnitId] = useState('');
  const [factor, setFactor] = useState('');
  const add = useMutation({
    mutationFn: () => retail.addPack(id, { unit_id: unitId, factor: factor.trim() }),
    onSuccess: () => {
      setUnitId('');
      setFactor('');
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });
  if (!allowed) return null;
  const used = new Set((packs.data ?? []).map((k) => k.unit_id));
  // The item's own unit and the units that are packs already are not offered; a switched-off unit cannot be chosen.
  const choices = (units.data ?? []).filter((u) => u.active !== false && u.id !== product.unit_id && !used.has(u.id));
  const problem = factorProblem(factor);
  return (
    <fieldset data-testid="pack-editor" onKeyDown={(e) => { if (e.key === 'Enter') e.preventDefault(); }}>
      <legend>{WORDS.packs}</legend>
      <p className="hint">
        Sell or buy this item by the pack, for example a carton of 12 {product.unit}. Stock is always counted in {product.unit}.
      </p>
      {packs.isPending && <p className="loading">Loading</p>}
      <Problem error={packs.error} />
      {packs.data && packs.data.length === 0 && <p className="empty-state">No cartons, boxes or other packs yet.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {(packs.data ?? []).map((k) => <PackRow key={`${k.unit_id}-${k.version}`} productId={id} pack={k} />)}
      </ul>
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
      <select id="pack-unit" value={unitId} onChange={(e) => setUnitId(e.target.value)}>
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
      <Problem error={add.error} />
      <BusyButton busy={add.isPending} type="button" disabled={unitId === '' || problem !== null} onClick={() => add.mutate()}>
        Add pack
      </BusyButton>
    </fieldset>
  );
}
