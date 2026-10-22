import { useCallback, useEffect, useState, type ReactNode } from 'react';
import type { FieldMessages } from '../../../api/lending-forms';
import { BusyButton } from '../../../app/ui/BusyButton';

// Accessible form pieces shared by the member and collateral forms (issue #280): a label above every
// input, a hint and an error tied to the input with aria-describedby, aria-invalid on a refused input,
// and focus moved to the first refused input on a failed save. Errors show once a person has left the
// input or tried to save; a refusal from the server shows at once, on its input, and goes when that
// input is edited.

export interface FormErrors {
  /** The message for a field, when it should show. */
  error(name: string): string | undefined;
  /** The person left the field. */
  touch(name: string): void;
  /** The person edited the field: a server refusal on it goes. */
  edited(name: string): void;
  /** Save was pressed: shows every error and moves focus to the first one (in `order`). True when there is none. */
  attempt(order: readonly string[]): boolean;
  /** The server refused: shows its field messages and moves focus to the first (in `order`). */
  refuse(fields: FieldMessages, order: readonly string[]): void;
  /** How many entries are marked wrong right now. */
  count(order: readonly string[]): number;
  idOf(name: string): string;
}

/** `problems` are the client's checks of the whole draft; `prefix` makes the input ids unique on the page. */
export function useFormErrors(problems: FieldMessages, prefix: string): FormErrors {
  const [touched, setTouched] = useState<ReadonlySet<string>>(new Set());
  const [attempted, setAttempted] = useState(false);
  const [server, setServer] = useState<FieldMessages>({});
  const [focus, setFocus] = useState<{ id: string; n: number } | null>(null);
  const idOf = useCallback((name: string) => `${prefix}-${name.replace(/_/g, '-')}`, [prefix]);

  useEffect(() => {
    if (focus && typeof document !== 'undefined') document.getElementById(focus.id)?.focus();
  }, [focus]);

  const first = (all: FieldMessages, order: readonly string[]) => order.find((n) => all[n]) ?? Object.keys(all)[0];
  return {
    idOf,
    error: (name) => server[name] ?? (attempted || touched.has(name) ? problems[name] : undefined),
    touch: (name) => setTouched((t) => (t.has(name) ? t : new Set(t).add(name))),
    edited: (name) =>
      setServer((s) => {
        if (!(name in s)) return s;
        const { [name]: _gone, ...rest } = s;
        return rest;
      }),
    attempt: (order) => {
      setAttempted(true);
      const name = first(problems, order);
      if (name === undefined) return true;
      setFocus((f) => ({ id: idOf(name), n: (f?.n ?? 0) + 1 }));
      return false;
    },
    refuse: (fields, order) => {
      setServer(fields);
      const name = first(fields, order);
      if (name !== undefined) setFocus((f) => ({ id: idOf(name), n: (f?.n ?? 0) + 1 }));
    },
    count: (order) => order.filter((n) => problems[n] || server[n]).length,
  };
}

interface CommonProps {
  name: string;
  label: string;
  form: FormErrors;
  hint?: ReactNode;
  required?: boolean;
  disabled?: boolean;
}

function describedBy(form: FormErrors, name: string, hint: ReactNode, error: string | undefined): string | undefined {
  const id = form.idOf(name);
  const ids = [hint ? `${id}-hint` : '', error ? `${id}-error` : ''].filter(Boolean).join(' ');
  return ids || undefined;
}

function Messages({ id, hint, error }: { id: string; hint?: ReactNode; error?: string }) {
  return (
    <>
      {hint && <p id={`${id}-hint`} className="field-hint">{hint}</p>}
      {error && <p id={`${id}-error`} className="field-error" role="alert">{error}</p>}
    </>
  );
}

export function TextField({ name, label, form, hint, required, disabled, value, onChange, type = 'text', inputMode, autoComplete = 'off', maxLength, max, rows }: CommonProps & {
  value: string;
  onChange: (value: string) => void;
  type?: 'text' | 'tel' | 'date' | 'textarea';
  inputMode?: 'text' | 'tel' | 'numeric' | 'decimal';
  autoComplete?: string;
  maxLength?: number;
  max?: string;
  rows?: number;
}) {
  const id = form.idOf(name);
  const error = form.error(name);
  const shared = {
    id,
    value,
    disabled,
    'aria-required': required || undefined,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': describedBy(form, name, hint, error),
    onBlur: () => form.touch(name),
  };
  const change = (v: string) => {
    form.edited(name);
    onChange(v);
  };
  return (
    <div className="mf-field">
      <label htmlFor={id}>{label}{required ? <span className="mf-req"> (needed)</span> : null}</label>
      {type === 'textarea' ? (
        <textarea {...shared} rows={rows ?? 3} maxLength={maxLength} onChange={(e) => change(e.target.value)} />
      ) : (
        <input {...shared} type={type === 'tel' ? 'tel' : type} inputMode={inputMode} autoComplete={autoComplete} maxLength={maxLength} max={max} onChange={(e) => change(e.target.value)} />
      )}
      <Messages id={id} hint={hint} error={error} />
    </div>
  );
}

export function SelectField({ name, label, form, hint, required, disabled, value, onChange, options, blankLabel }: CommonProps & {
  value: string;
  onChange: (value: string) => void;
  options: readonly { value: string; label: string }[];
  /** An empty first choice ("Choose a branch"). */
  blankLabel?: string;
}) {
  const id = form.idOf(name);
  const error = form.error(name);
  return (
    <div className="mf-field">
      <label htmlFor={id}>{label}{required ? <span className="mf-req"> (needed)</span> : null}</label>
      <select
        id={id}
        value={value}
        disabled={disabled}
        aria-required={required || undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(form, name, hint, error)}
        onBlur={() => form.touch(name)}
        onChange={(e) => { form.edited(name); onChange(e.target.value); }}
      >
        {blankLabel !== undefined && <option value="">{blankLabel}</option>}
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <Messages id={id} hint={hint} error={error} />
    </div>
  );
}

/** A group of related inputs under a heading (a fieldset and legend, so a screen reader announces the group). */
export function Group({ title, children, tourId }: { title: string; children: ReactNode; tourId?: string }) {
  return (
    <fieldset className="mf-group" data-tour={tourId}>
      <legend>{title}</legend>
      {children}
    </fieldset>
  );
}

/**
 * The save bar: sticks to the bottom of the screen on a phone so the main action is always under the thumb, says in words
 * what is stopping it, and keeps Cancel next to it.
 */
export function SaveBar({ label, busy, blocked, cancel, tourId }: { label: string; busy: boolean; blocked?: string; cancel: ReactNode; tourId?: string }) {
  return (
    <div className="mf-savebar" data-tour={tourId}>
      {blocked && <p className="mf-savebar-note" role="status">{blocked}</p>}
      <div className="mf-savebar-row">
        {cancel}
        <BusyButton busy={busy} type="submit" className="rt-primary" disabled={!!blocked} aria-disabled={busy || !!blocked}>
          {label}
        </BusyButton>
      </div>
    </div>
  );
}
