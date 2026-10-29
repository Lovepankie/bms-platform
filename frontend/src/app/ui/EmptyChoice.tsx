import { Link, useRouter } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { BusyButton } from './BusyButton';
import { Field } from './Field';

// The empty choice (issue #619, docs/ui-rules.md rule 25): a list a form needs is never a dead end. When a select or picker has
// no options it shows one plain line ("No units yet.") and one way to fill it: a form right there that adds one by its name and
// chooses it, or a button to the screen that adds one, which comes back here once it is added (`back`, read by useComeBack).
// Someone who may not add one is told who can ("Ask the owner to add a unit first."). Never only a "Choose a unit" with nothing
// under it. The field's label stays, so the form still names what is missing. design-guard.test.ts fails a select of options
// read at run time that has no empty handling. The look is .empty-choice in src/app/ui/kit.css.

/** What a field says when the list it needs could not be read. */
export const LOAD_FAILED = 'This list could not be loaded. Check the connection.';

/** True when a form shows the EmptyChoice for a list: it answered with none, or its read failed. Never while it loads. */
export const showsEmptyChoice = (load: { isError: boolean; data?: readonly unknown[] }, rows = load.data): boolean =>
  load.isError || (rows !== undefined && rows.length === 0);

/** Adds a row by its name, in place: `create` makes it, `onAdded` puts it in the screen's list and chooses it. */
export interface AddInline<T> {
  label: string;
  /** The label of the name field, such as "Name of the new unit". */
  name: string;
  /** The button that sends it, such as "Add unit". */
  send: string;
  create: (name: string) => Promise<T>;
  onAdded: (row: T) => void;
  maxLength?: number;
}

export type ChoiceAdd<T> = AddInline<T> | { label: string; to: string } | { label: string; onClick: () => void };

const isInline = <T,>(add: ChoiceAdd<T>): add is AddInline<T> => 'create' in add;

/** The page to come back to, for a link to the screen that adds what is missing. */
function here(): string {
  return typeof window === 'undefined' ? '' : `${window.location.pathname}${window.location.search}`;
}

/**
 * Where a screen opened from an empty choice goes back to once it has added what was missing, or null when it was not opened
 * that way. Only a page of the app is followed, never another site.
 */
export function useComeBack(): (() => void) | null {
  const router = useRouter({ warn: false });
  const back = typeof window === 'undefined' ? null : new URLSearchParams(window.location.search).get('back');
  // A backslash is refused too: a browser reads /\\host as //host, another site.
  if (!back || !back.startsWith('/') || back.startsWith('//') || back.includes('\\')) return null;
  return () => {
    if (router) void router.navigate({ href: back });
    else window.location.assign(back);
  };
}

/**
 * A short form that adds a row by its name. It sits inside other forms: its Enter adds, and goes no further. Without `title` it
 * draws no heading, as in the add sheet of a picker (PickerWithAdd), whose title says it.
 */
export function AddByName<T>({ id, title, add, onCancel }: { id: string; title?: string; add: AddInline<T>; onCancel: () => void }) {
  const [name, setName] = useState('');
  const [showErrors, setShowErrors] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const trimmed = name.trim();
  const submit = async () => {
    if (trimmed === '') {
      setShowErrors(true);
      document.getElementById(`${id}-name`)?.focus();
      return;
    }
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      add.onAdded(await add.create(trimmed));
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div
      role="group"
      {...(title === undefined ? { 'aria-label': add.label } : { 'aria-labelledby': `${id}-title` })}
      className="cp-add"
      onKeyDown={(e) => {
        if (e.key !== 'Enter' || (e.target as HTMLElement).tagName === 'BUTTON') return;
        e.preventDefault();
        e.stopPropagation();
        void submit();
      }}
    >
      {title !== undefined && <h3 id={`${id}-title`}>{title}</h3>}
      <Field
        id={`${id}-name`}
        label={add.name}
        required
        error={trimmed === '' ? 'Type the name.' : null}
        showError={showErrors}
        value={name}
        maxLength={add.maxLength ?? 100}
        autoComplete="off"
        autoFocus
        onChange={(e) => setName(e.target.value)}
      />
      {error ? <p role="alert" className="alert alert-danger">{error instanceof Error ? error.message : 'Something went wrong.'}</p> : null}
      <p className="cluster">
        <BusyButton busy={busy} type="button" className="rt-primary" onClick={() => void submit()}>{add.send}</BusyButton>
        <button type="button" className="btn-ghost" onClick={onCancel}>Cancel</button>
      </p>
    </div>
  );
}

/**
 * What a form shows in place of a select whose list is empty: the field's label, `text` ("No units yet."), and `add` when the
 * session may add one, or else `ask` ("Ask the owner to add a unit first."). A `required` field's action is what Save takes the
 * person to (focusFirstMissing reads `data-needed`).
 */
export function EmptyChoice<T>({ id, label, text, ask, add, required = false, load }: {
  id: string;
  label?: ReactNode;
  /**
   * The read of the list. When it failed, the list is not known to be empty: the field says it could not be loaded and offers
   * Try again, never "No units yet." and a way to add one that may already exist (review of #619).
   */
  load?: { isError: boolean; refetch: () => unknown };
  text: string;
  /** Who to ask, for a session that may not add one; left out where everyone may add one. */
  ask?: string;
  add?: ChoiceAdd<T> | null;
  required?: boolean;
}) {
  const [adding, setAdding] = useState(false);
  const needed = required ? { 'data-needed': '' } : {};
  if (add && isInline(add) && adding) {
    return <AddByName id={id} title={add.label} add={{ ...add, onAdded: (row) => { setAdding(false); add.onAdded(row); } }} onCancel={() => setAdding(false)} />;
  }
  return (
    <div className="field empty-choice" data-empty-choice="" data-testid={`${id}-empty`} {...(label !== undefined ? { role: 'group', 'aria-labelledby': `${id}-label` } : {})}>
      {label !== undefined && (
        <span className="field-label" id={`${id}-label`}>
          {label}
          {required && <span className="field-star" aria-hidden="true"> *</span>}
        </span>
      )}
      {load?.isError ? (
        <>
          <p role="alert">{LOAD_FAILED}</p>
          <button type="button" id={id} {...needed} onClick={() => void load.refetch()}>Try again</button>
        </>
      ) : add ? (
        <>
          <p>{text}</p>
          {isInline(add) ? (
            <button type="button" id={id} {...needed} onClick={() => setAdding(true)}>{add.label}</button>
          ) : 'to' in add ? (
            <Link id={id} className="btn" {...needed} to={add.to as never} search={{ back: here() } as never}>{add.label}</Link>
          ) : (
            <button type="button" id={id} {...needed} onClick={add.onClick}>{add.label}</button>
          )}
        </>
      ) : (
        <p id={id} tabIndex={-1} {...needed}>{ask ? `${text} ${ask}` : text}</p>
      )}
    </div>
  );
}
