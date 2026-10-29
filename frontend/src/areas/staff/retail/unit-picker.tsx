import { useQueryClient } from '@tanstack/react-query';
import { useContext } from 'react';
import { retail, type Unit } from '../../../api/retail';
import { AddByName } from '../../../app/ui/EmptyChoice';
import { PickerWithAdd } from '../../../app/ui/PickerWithAdd';
import { WORDS } from '../../../app/glossary';
import { useStaff } from '../context';
import { ScreenToast } from './toast';

// The Counted in field of both item forms, Items and Add it here (issue #619, after a tester found no units), and Quick add: the
// units to choose from, and "+ Add a unit" at the foot of the list for whoever manages the catalogue (issue #688, the shared
// PickerWithAdd). Typing a name in the add sheet adds the unit and chooses it at once; it is put in the catalogue's list of
// units before the refetch answers, so the select already holds it. With no units at all the field is the empty choice: "No
// units yet." and the same add, or who to ask.

const UNITS = ['retail', 'catalogue', 'units'];

export function UnitPicker({ id, units, loading, load, value, onChange, error, showError, required = true }: {
  id: string;
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
      label={WORDS.countedIn}
      thing="unit"
      options={units.map((u) => ({ value: u.id ?? '', label: `${u.name ?? ''}${u.active === false ? ' (switched off)' : ''}` }))}
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
            create: (name) => retail.createUnit(name),
            onAdded: (unit: Unit) => {
              queryClient.setQueryData<Unit[]>(UNITS, (was) => [...(was ?? []).filter((u) => u.id !== unit.id), unit]);
              void queryClient.invalidateQueries({ queryKey: UNITS });
              screenToast?.(`Added the unit ${unit.name ?? ''}.`, 'success');
              done({ value: unit.id ?? '', label: unit.name ?? '' });
            },
          }}
        />
      )}
    />
  );
}
