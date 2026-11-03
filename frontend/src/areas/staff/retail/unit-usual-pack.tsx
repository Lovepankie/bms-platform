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
import { quantityIn, unitWord } from './units-state';
import { Problem, requireVersion } from './ui';
import { orExistingUnit } from './units-state';

// Issue #637 (ADR-100 note): the owner changes or removes a unit's usual pack on the Units page, so a size set by mistake in Set
// up your shop does not fill in every item's pack form. Items' packs never follow. A usual pack is one level only: its smaller
// unit is one with no usual pack of its own. Issue #709 (ADR-110): the screen says it in plain words, "Usually holds 10
// pieces. Each item can be different.", and a dozen always holds 12, which cannot be changed.

/** What a package row on the Units page says it holds, or null for a single unit. */
export function holdsText(unit: Unit): string | null {
  if (unit.fixed_count) return `Always holds ${unit.fixed_count}. It cannot change.`;
  const u = unit.usual_pack;
  if (u) return `Usually holds ${quantityIn(showQty(u.size ?? ''), u.of_unit ?? '').toLowerCase()}. Each item can be different.`;
  return unit.is_package ? 'Each item says how many it holds.' : null;
}

/** "Used for: buying and selling", in the words of PURPOSES. */
export function usedForText(purpose: string | undefined): string {
  return `Used for: ${(PURPOSES.find((o) => o.value === asPurpose(purpose)) ?? PURPOSES[0]).use.replace(/^for /, '')}`;
}

/** The units a usual pack of `unit` may count: switched on, not itself, and not a pack themselves. */
export function usualPackChoices(unit: Unit, units: Unit[]): Unit[] {
  return units.filter((u) => u.active !== false && u.id !== unit.id && !u.usual_pack);
}

/** Whether `unit` may have a usual pack: switched on, and no other unit's usual pack counts it (one level only). */
export function mayHaveUsualPack(unit: Unit, units: Unit[]): boolean {
  return unit.active !== false && !units.some((u) => u.id !== unit.id && u.usual_pack?.of_unit_id === unit.id);
}

/**
 * Issue #662: why a unit that has a usual pack may not change it, or null when it may. The sheet then offers only Remove, so a
 * usual pack set before the unit was switched off, or before another pack counted it, can still be cleared.
 */
export function removeOnlyReason(unit: Unit, units: Unit[]): string | null {
  if (mayHaveUsualPack(unit, units)) return null;
  if (unit.active === false) return `${unit.name ?? ''} is switched off, so what it usually holds can only be removed.`;
  const counting = units.find((u) => u.id !== unit.id && u.usual_pack?.of_unit_id === unit.id);
  return `${counting?.name ?? ''} holds ${unit.name ?? ''}, so ${unit.name ?? ''} cannot hold another unit too. You can remove what it usually holds.`;
}

/** The size's problem in the words of units, or null: at least 1, at most three places, as an item's pack (factorProblem). */
export function usualSizeProblem(size: string, unit: Unit, of: Unit | undefined): string | null {
  if (unit.fixed_count && size.trim() !== String(unit.fixed_count)) return `A ${unitWord(unit.name).toLowerCase()} always holds ${unit.fixed_count}.`;
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
  const removeOnly = removeOnlyReason(unit, units);
  const choices = usualPackChoices(unit, units);
  const [picked, setOfUnitId] = useState(had?.of_unit_id ?? '');
  // Review of #637: a smaller unit that is no longer a choice (switched off, or a pack now) counts as not chosen.
  const ofUnitId = choices.some((u) => u.id === picked) ? picked : '';
  const [size, setSize] = useState(unit.fixed_count ? String(unit.fixed_count) : had?.size ? showQty(had.size) : '');
  const [purpose, setPurpose] = useState<Purpose>(asPurpose(had?.purpose));
  const [sent, setSent] = useState(false);
  // toast-exempt: said elsewhere: the Units list in catalogue-lists shows the success toast through onSaved
  const save = useMutation({
    mutationFn: (body: { usual_pack?: UsualPackRequest; clear_usual_pack?: boolean }) => retail.updateUnit(unit.id ?? '', requireVersion(unit.version), body),
    onSuccess: (row, body) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved(body.clear_usual_pack ? `${row.name} has no usual number now. Items keep their packages.` : `Saved what one ${row.name} usually holds. Items keep their packages.`);
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
      title={`What one ${unit.name ?? ''} usually holds`}
      actions={
        <>
          {removeOnly === null && <BusyButton
            busy={save.isPending && save.variables?.clear_usual_pack !== true}
            type="submit"
            form={formId}
            className="rt-primary"
            notReady={ofUnitId === '' || sizeProblem !== null}
            disabled={save.isPending}
          >
            Save
          </BusyButton>}
          {had && (
            <BusyButton busy={save.isPending && save.variables?.clear_usual_pack === true} type="button" disabled={save.isPending} onClick={() => save.mutate({ clear_usual_pack: true })}>
              Remove the usual number
            </BusyButton>
          )}
        </>
      }
    >
      {removeOnly !== null ? (
        <div className="stack">
          <p>{removeOnly}</p>
          <Problem error={save.error} />
        </div>
      ) : <form
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
        <Hint>A starting number for an item&apos;s package. Each item can be different.</Hint>
        {choices.length === 0 ? (
          <EmptyChoice
            id={`${formId}-of`}
            label="It holds"
            required
            text="No other unit for this pack to hold yet."
            add={{
              label: 'Add a new unit', name: 'Name of the new unit, such as piece', send: 'Add unit', maxLength: 30,
              // Issue #698: "ctn" while editing Carton is this unit, which never holds itself (review of #715).
              create: (name) => orExistingUnit(retail.createUnit(name)).then((added) => {
                if (added.id === unit.id) throw new Error(`${name.trim()} is ${unit.name ?? ''} itself. Type the unit one ${unit.name ?? ''} holds, such as piece.`);
                return added;
              }),
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
            showError={sent}
            control={(props) => (
              // empty: with no choice the EmptyChoice above takes this field's place.
              <select {...props} value={ofUnitId} onChange={(e) => setOfUnitId(e.target.value)}>
                <option value="">Choose a unit</option>
                {choices.map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
              </select>
            )}
          />
        )}
        <Field
          id={`${formId}-size`}
          label="How many does it usually hold?"
          required
          error={sizeProblem}
          showError={sent}
          inputMode="decimal"
          value={size}
          readOnly={unit.fixed_count !== undefined}
          hint={unit.fixed_count ? `A ${unitWord(unit.name).toLowerCase()} always holds ${unit.fixed_count}. For another number, use a box.` : undefined}
          onChange={(e) => setSize(e.target.value)}
          autoComplete="off"
        />
        <Segmented label="Used for" options={PURPOSES} value={purpose} onChange={setPurpose} />
        <Problem error={save.error} />
      </form>}
    </Sheet>
  );
}
