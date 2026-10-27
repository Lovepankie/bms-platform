import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { EmptyChoice } from '../../../app/ui/EmptyChoice';
import { Field, FieldsNeededNote } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { Segmented } from '../../../app/ui/Segmented';
import { Sheet } from '../../../app/ui/Sheet';
import { retail, type Unit, type UsualPackRequest } from '../../../api/retail';
import { PURPOSES, asPurpose, factorProblem, type Purpose } from './catalogue-packs';
import { showQty } from './maths';
import { Problem, requireVersion } from './ui';

// Issue #637 (ADR-100 note): the owner changes or removes a unit's usual pack on the Units page, so a size set by mistake in Set
// up your shop does not fill in every item's pack form. Items' packs never follow. A usual pack is one level only: its smaller
// unit is one with no usual pack of its own.

/** The units a usual pack of `unit` may count: switched on, not itself, and not a pack themselves. */
export function usualPackChoices(unit: Unit, units: Unit[]): Unit[] {
  return units.filter((u) => u.active !== false && u.id !== unit.id && !u.usual_pack);
}

/** Whether `unit` may have a usual pack: switched on, and no other unit's usual pack counts it (one level only). */
export function mayHaveUsualPack(unit: Unit, units: Unit[]): boolean {
  return unit.active !== false && !units.some((u) => u.id !== unit.id && u.usual_pack?.of_unit_id === unit.id);
}

/** The size's problem in the words of units, or null: at least 1, at most three places, as an item's pack (factorProblem). */
export function usualSizeProblem(size: string, unit: Unit, of: Unit | undefined): string | null {
  return factorProblem(size) === null ? null : `Enter how many ${of?.name ?? 'of the smaller unit'} are in one ${unit.name ?? ''}: 1 or more, for example 12 or 12.5.`;
}

/** The request that saves the sheet, or null when nothing changed. */
export function usualPackBody(unit: Unit, form: { ofUnitId: string; size: string; purpose: Purpose }): UsualPackRequest | null {
  const had = unit.usual_pack;
  const same = had !== undefined && had.of_unit_id === form.ofUnitId && showQty(had.size ?? '') === form.size.trim() && asPurpose(had.purpose) === form.purpose;
  return same ? null : { size: form.size.trim(), of_unit_id: form.ofUnitId, purpose: form.purpose };
}

export function UsualPackSheet({ unit, units, onClose, onSaved }: {
  unit: Unit;
  units: Unit[];
  onClose: () => void;
  onSaved: (text: string) => void;
}) {
  const queryClient = useQueryClient();
  const had = unit.usual_pack;
  const choices = usualPackChoices(unit, units);
  const [picked, setOfUnitId] = useState(had?.of_unit_id ?? '');
  // Review of #637: a smaller unit that is no longer a choice (switched off, or a pack now) counts as not chosen.
  const ofUnitId = choices.some((u) => u.id === picked) ? picked : '';
  const [size, setSize] = useState(had?.size ? showQty(had.size) : '');
  const [purpose, setPurpose] = useState<Purpose>(asPurpose(had?.purpose));
  const [sent, setSent] = useState(false);
  // Rule 10 (review of #637): each field's error shows once that field is left, or once Save is pressed.
  const [touched, setTouched] = useState({ of: false, size: false });
  const save = useMutation({
    mutationFn: (body: { usual_pack?: UsualPackRequest; clear_usual_pack?: boolean }) => retail.updateUnit(unit.id ?? '', requireVersion(unit.version), body),
    onSuccess: (row, body) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved(body.clear_usual_pack ? `${row.name} has no usual pack now. Items keep their packs.` : `Saved the usual pack of ${row.name}. Items keep their packs.`);
      onClose();
    },
  });
  const sizeProblem = usualSizeProblem(size, unit, choices.find((u) => u.id === ofUnitId));
  const body = ofUnitId === '' || sizeProblem !== null ? null : usualPackBody(unit, { ofUnitId, size, purpose });
  const formId = `usual-pack-${unit.id ?? ''}`;
  return (
    <Sheet
      open
      onClose={onClose}
      title={`Usual pack of ${unit.name ?? ''}`}
      actions={
        <>
          <BusyButton
            busy={save.isPending && save.variables?.clear_usual_pack !== true}
            type="submit"
            form={formId}
            className="rt-primary"
            notReady={ofUnitId === '' || sizeProblem !== null}
            disabled={save.isPending}
          >
            Save
          </BusyButton>
          {had && (
            <BusyButton busy={save.isPending && save.variables?.clear_usual_pack === true} type="button" disabled={save.isPending} onClick={() => save.mutate({ clear_usual_pack: true })}>
              Remove usual pack
            </BusyButton>
          )}
        </>
      }
    >
      <form
        id={formId}
        className="stack"
        onSubmit={(e) => {
          e.preventDefault();
          setSent(true);
          if (body) save.mutate({ usual_pack: body });
          else if (ofUnitId !== '' && sizeProblem === null) onClose();
        }}
      >
        <FieldsNeededNote />
        <Hint>A starting number for an item&apos;s pack form; items keep their own size.</Hint>
        {choices.length === 0 ? (
          <EmptyChoice
            id={`${formId}-of`}
            label="It holds"
            required
            text="No other unit for this pack to hold yet."
            add={{
              label: 'Add a new unit', name: 'Name of the new unit, such as piece', send: 'Add unit', maxLength: 30,
              create: (name) => retail.createUnit(name),
              onAdded: (added) => {
                setOfUnitId(added.id ?? '');
                void queryClient.invalidateQueries({ queryKey: ['retail', 'catalogue', 'units'] });
              },
            }}
          />
        ) : (
          <Field
            id={`${formId}-of`}
            label="It holds"
            required
            error={ofUnitId === '' ? 'Choose the unit this pack holds.' : null}
            showError={sent || touched.of}
            control={(props) => (
              // empty: with no choice the EmptyChoice above takes this field's place.
              <select {...props} value={ofUnitId} onChange={(e) => setOfUnitId(e.target.value)} onBlur={(e) => { props.onBlur(e); setTouched((t) => ({ ...t, of: true })); }}>
                <option value="">Choose a unit</option>
                {choices.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
            )}
          />
        )}
        <Field
          id={`${formId}-size`}
          label={`How many in one ${unit.name ?? ''}`}
          required
          error={sizeProblem}
          showError={sent || touched.size}
          onBlur={() => setTouched((t) => ({ ...t, size: true }))}
          inputMode="decimal"
          value={size}
          onChange={(e) => setSize(e.target.value)}
          autoComplete="off"
        />
        {/* The group carries the name for a screen reader, so the visible label is not read twice. */}
        <p className="field-label" aria-hidden="true">Used for</p>
        <Segmented label="Used for" options={PURPOSES} value={purpose} onChange={setPurpose} />
        <Problem error={save.error} />
      </form>
    </Sheet>
  );
}
