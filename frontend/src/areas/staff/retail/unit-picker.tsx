import { useQueryClient } from '@tanstack/react-query';
import { useContext } from 'react';
import { retail, type Unit } from '../../../api/retail';
import { AddByName } from '../../../app/ui/EmptyChoice';
import { HelpTip } from '../../../app/ui/HelpTip';
import { PickerWithAdd } from '../../../app/ui/PickerWithAdd';
import { WORDS } from '../../../app/glossary';
import { useStaff } from '../context';
import { ScreenToast } from './toast';
import { orExistingUnit, unitAddedText, type AddedUnit } from './units-state';

// The Single unit field of both item forms, Items and Add it here (issue #619, after a tester found no units), and Quick add: the
// units to choose from, and "+ Add your own" at the foot of the list for whoever manages the catalogue (issue #688, the shared
// PickerWithAdd). Typing a name in the add sheet adds the unit and chooses it at once; it is put in the catalogue's list of
// units before the refetch answers, so the select already holds it. With no units at all the field is the empty choice: "No
// units yet." and the same add, or who to ask. Issue #709 (ADR-110): the label is "Single unit", or "Package" for a package
// row (`kind`), each with its HelpTip; the server lists the standard units first, so the picker keeps its order.

const UNITS = ['retail', 'catalogue', 'units'];

export function UnitPicker({ id, units, loading, load, value, onChange, error, showError, required = true, kind = 'single', label = kind === 'single' ? WORDS.singleUnit : WORDS.package, hint }: {
  id: string;
  /** Issue #709 (ADR-110): the item's single unit, or one of its packages; each label has its HelpTip. */
  kind?: 'single' | 'package';
  label?: string;
  hint?: string;
  /** The units the form offers (it decides about switched-off ones). */
  units: Unit[];
  /** The list is not known yet (on its way, or its read failed): the select shows, never "No units yet." */
  loading: boolean;
  /** The read of the units: a failed one says so with Try again (EmptyChoice). */
  load?: { isError: boolean; refetch: () => unknown };
  value: string;
  onChange: (unitId: string) => void;
  error: string | null;
  showError: boolean;
  /** Marked needed with the red asterisk; off on a form that marks none (Add an item with sizes and colours). */
  required?: boolean;
}) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const screenToast = useContext(ScreenToast);
  return (
    <PickerWithAdd
      id={id}
      label={label}
      hint={hint}
      tip={<HelpTip tip={kind === 'single' ? 'singleUnit' : 'package'} />}
      addText="+ Add your own"
      thing="unit"
      // Issue #830: a deleted (switched off) unit is hidden from the picker, unless it is the one already chosen.
      options={units.filter((u) => u.active !== false || u.id === value).map((u) => ({ value: u.id ?? '', label: `${u.name ?? ''}${u.active === false ? ' (deleted)' : ''}` }))}
      value={value}
      onChange={onChange}
      placeholder="Choose a unit"
      canAdd={(me.permissions ?? []).includes('retail.catalogue.manage')}
      required={required}
      error={error}
      showError={showError}
      loading={loading}
      load={load}
      empty="No units yet."
      ask="Ask the owner to add a unit first."
      form={(done, cancel) => (
        <AddByName
          id={`${id}-add`}
          onCancel={cancel}
          add={{
            label: 'Add a unit',
            name: 'Name of the new unit',
            send: 'Add unit',
            maxLength: 50,
            create: (name) => orExistingUnit(retail.createUnit(name)),
            onAdded: (unit: AddedUnit) => {
              if (!unit.reused) queryClient.setQueryData<Unit[]>(UNITS, (was) => [...(was ?? []).filter((u) => u.id !== unit.id), unit]);
              void queryClient.invalidateQueries({ queryKey: UNITS });
              screenToast?.(unitAddedText(unit), 'success');
              done({ value: unit.id ?? '', label: unit.name ?? '' });
            },
          }}
        />
      )}
    />
  );
}
