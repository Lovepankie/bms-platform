import { useQueryClient } from '@tanstack/react-query';
import { useContext, useState } from 'react';
import { retail, type Unit } from '../../../api/retail';
import { AddByName, EmptyChoice, type AddInline } from '../../../app/ui/EmptyChoice';
import { Field } from '../../../app/ui/Field';
import { WORDS } from '../../../app/glossary';
import { useStaff } from '../context';
import { ScreenToast } from './toast';

// The Counted in field of both item forms, Items and Add it here (issue #619, after a tester found no units): the units to
// choose from, and "Add a new unit" for whoever manages the catalogue. Typing a name adds the unit and chooses it at once; it is
// put in the catalogue's list of units before the refetch answers, so the select already holds it. With no units at all the
// field is the empty choice: "No units yet." and the same add, or who to ask. Issue #689: Add an item names it "Smallest unit
// you sell" with a hint, and its package rows "Package", through `label` and `hint`.

const UNITS = ['retail', 'catalogue', 'units'];

export function UnitPicker({ id, units, loading, load, value, onChange, error, showError, required = true, label = WORDS.countedIn, hint }: {
  id: string;
  label?: string; hint?: string;
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
  const [adding, setAdding] = useState(false);
  const canAdd = (me.permissions ?? []).includes('retail.catalogue.manage');
  const add: AddInline<Unit> = {
    label: 'Add a new unit',
    name: 'Name of the new unit',
    send: 'Add unit',
    maxLength: 50,
    create: (name) => retail.createUnit(name),
    onAdded: (unit) => {
      queryClient.setQueryData<Unit[]>(UNITS, (was) => [...(was ?? []).filter((u) => u.id !== unit.id), unit]);
      void queryClient.invalidateQueries({ queryKey: UNITS });
      setAdding(false);
      onChange(unit.id ?? '');
      screenToast?.(`Added the unit ${unit.name ?? ''}.`, 'success');
    },
  };
  if (load?.isError || (units.length === 0 && !loading)) {
    return <EmptyChoice id={id} label={label} required={required} load={load} text="No units yet." ask="Ask the owner to add a unit first." add={canAdd ? add : null} />;
  }
  if (adding) return <AddByName id={`${id}-add`} title={add.label} add={add} onCancel={() => setAdding(false)} />;
  return (
    <>
      <Field
        id={id}
        label={label} hint={hint}
        required={required}
        error={error}
        showError={showError}
        control={(props) => (
          <select {...props} value={value} onChange={(e) => onChange(e.target.value)}>
            <option value="">Choose a unit</option>
            {units.map((u) => <option key={u.id} value={u.id}>{u.name}{u.active === false ? ' (switched off)' : ''}</option>)}
          </select>
        )}
      />
      {canAdd && <button type="button" className="btn-ghost" onClick={() => setAdding(true)}>Add a new unit</button>}
    </>
  );
}
