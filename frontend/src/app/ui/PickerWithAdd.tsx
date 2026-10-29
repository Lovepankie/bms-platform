import { useState, type ReactNode } from 'react';
import { EmptyChoice } from './EmptyChoice';
import { Field } from './Field';
import { Sheet } from './Sheet';

// Add it in place (issue #688): every picker that chooses a record (a category, a unit, a supplier, a customer) ends its list
// with "+ Add a <thing>". It opens a small sheet over the form with only the fields that record needs; on save the new record
// is chosen and the person is back on the same form with everything they typed still there, and on cancel nothing is lost.
// Nothing navigates away: the form stays mounted under the sheet. The screen decides who may add (the permission of the full
// add screen) and the option is hidden from anyone else. `useAddInPlace` is the sheet alone, for a picker that draws its own
// list (the category picker's search); `PickerWithAdd` is a select with it. design-guard.test.ts fails a record picker built
// without one of them, unless it is listed as exempt with a reason.

/** The value of the last option of a select: never a record's id. */
export const ADD_CHOICE = '+add';

/** What the add option says: "+ Add a group". */
export const addChoice = (thing: string) => `+ Add a ${thing}`;

/** A record just added, as the picker shows it. */
export interface Picked {
  value: string;
  label: string;
}

/**
 * The add sheet of a picker. `form` draws the fields of the new record; it calls `done` with the record once it is saved (the
 * sheet closes and `onAdded` chooses it) or `cancel`. Render `sheet` next to the picker and call `open` from its add option.
 */
export function useAddInPlace({ thing, form, onAdded }: {
  thing: string;
  form: (done: (row: Picked) => void, cancel: () => void) => ReactNode;
  onAdded: (row: Picked) => void;
}): { open: () => void; sheet: ReactNode } {
  const [open, setOpen] = useState(false);  const done = (row: Picked) => {
    setOpen(false);
    onAdded(row);
  };
  const sheet = (
    <Sheet open={open} onClose={() => setOpen(false)} title={`Add a ${thing}`}>
      {open && form(done, () => setOpen(false))}
    </Sheet>
  );
  return { open: () => setOpen(true), sheet };
}

/**
 * A select of records ending with "+ Add a <thing>" for whoever may add one. With no records it is the kit's EmptyChoice: "No
 * suppliers yet." and only "+ Add a supplier", or who to ask ("Ask the owner to add a supplier first."). A record added here is
 * named at once, before the screen's own list has read it again.
 */
export function PickerWithAdd({ id, label, thing, options, value, onChange, placeholder, canAdd, form, required = false, error = null, showError = false, hint, disabled = false, loading = false, load, empty, ask }: {
  id: string;
  label: ReactNode;
  /** The record in a few plain words, such as "supplier": the option says "+ Add a supplier". */
  thing: string;
  options: Picked[];
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  /** The permission of the full add screen; without it the add option is hidden. */
  canAdd: boolean;
  form: (done: (row: Picked) => void, cancel: () => void) => ReactNode;
  required?: boolean;
  error?: string | null;
  showError?: boolean;
  hint?: ReactNode;
  /** The choice is fixed (an order already sent): no change and no add. */
  disabled?: boolean;
  /** The list is on its way: the select shows, never "No suppliers yet." */
  loading?: boolean;
  load?: { isError: boolean; refetch: () => unknown };
  /** What an empty list says, such as "No suppliers yet." */
  empty: string;
  /** Who to ask, for someone who may not add one. */
  ask?: string;
}) {
  const [kept, setKept] = useState<Picked | null>(null);
  const add = useAddInPlace({ thing, form, onAdded: (row) => { setKept(row); onChange(row.value); } });
  const list = kept && !options.some((o) => o.value === kept.value) ? [...options, kept] : options;
  // Both ways of drawing the field keep the sheet second, so a list that arrives while it is open never resets it.
  if (load?.isError || (list.length === 0 && !loading)) {
    const addFirst = canAdd ? { label: addChoice(thing), onClick: add.open } : null;
    return <><EmptyChoice id={id} label={label} required={required} load={load} text={empty} ask={ask} add={addFirst} />{add.sheet}</>;
  }
  return (
    <>
      <Field
        id={id}
        label={label}
        required={required}
        error={error}
        showError={showError}
        hint={hint}
        control={(props) => (
          // empty: drawn only while the list has records or is on its way; with none, the EmptyChoice above stands in.
          <select {...props} value={value} disabled={disabled} onChange={(e) => (e.target.value === ADD_CHOICE ? add.open() : onChange(e.target.value))}>
            <option value="">{placeholder}</option>
            {list.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
            {canAdd && !disabled && <option value={ADD_CHOICE}>{addChoice(thing)}</option>}
          </select>
        )}
      />
      {add.sheet}
    </>
  );
}
