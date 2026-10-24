import { useState, type ChangeEvent, type ReactNode } from 'react';
import { formatMinor } from '../../components/money';
import { Field } from './Field';

// The one amount field (#520, docs/ui-rules.md rules 8 and 11): Uganda shillings in whole numbers, the numeric keypad on a
// phone, and the thousands separators drawn as the person types (12,500), in tabular figures with UGX before it. It hands the
// screen the amount in minor units (UGX has none smaller, so shillings), or null while it is empty. A pasted "12,500.00" keeps
// what is before the point, so it is 12,500 and never 1,250,000. The separators come from
// formatMinor, the one money formatter. Built on Field, so the label, hint and error-on-leave work the same. The look is
// .money-input in src/app/ui/kit.css.

// 15 digits stays a safe integer.
const MAX_DIGITS = 15;

/** The digits typed, grouped in thousands ("1250000" becomes "1,250,000"); empty stays empty. */
export function grouped(digits: string): string {
  return digits === '' ? '' : formatMinor(Number(digits), 'UGX').replace(/^UGX /, '');
}

export function MoneyInput({ label, valueMinor, onChange, hint, error, showError, required, id, tour }: {
  label: string;
  valueMinor: number | null;
  onChange: (minor: number | null) => void;
  hint?: ReactNode;
  error?: string | null;
  showError?: boolean;
  required?: boolean;
  id?: string;
  tour?: string;
}) {
  // The text is kept while it says the same amount, so a typed "0" before a digit or a half-typed value is not redrawn.
  const [text, setText] = useState(valueMinor === null ? '' : grouped(String(valueMinor)));
  const shown = (text === '' ? null : Number(text.replace(/\D/g, ''))) === valueMinor ? text : valueMinor === null ? '' : grouped(String(valueMinor));

  const change = (e: ChangeEvent<HTMLInputElement>) => {
    const input = e.target;
    // Shillings have no cents: whatever follows a point is dropped, not run into the whole number.
    const whole = input.value.split('.')[0] ?? '';
    const caret = Math.min(input.selectionStart ?? input.value.length, whole.length);
    const digits = whole.replace(/\D/g, '').replace(/^0+(?=\d)/, '').slice(0, MAX_DIGITS);
    // Where the caret was among the digits, so it stays after the same digit once the separators move.
    const digitsBefore = Math.min(digits.length, whole.slice(0, caret).replace(/\D/g, '').replace(/^0+(?=\d)/, '').length);
    const next = grouped(digits);
    setText(next);
    onChange(digits === '' ? null : Number(digits));
    requestAnimationFrame(() => {
      if (document.activeElement !== input) return;
      let at = 0;
      for (let seen = 0; at < next.length && seen < digitsBefore; at++) if (/\d/.test(next[at] ?? '')) seen++;
      input.setSelectionRange(at, at);
    });
  };

  return (
    <Field
      label={
        <>
          {label}
          <span className="visually-hidden"> in UGX</span>
        </>
      }
      required={required}
      hint={hint}
      error={error}
      showError={showError}
      id={id}
      className="money-field"
      control={(props) => (
        <span className="money-input" data-tour={tour}>
          <span className="money-input-currency" aria-hidden="true">
            UGX
          </span>
          <input {...props} inputMode="numeric" autoComplete="off" enterKeyHint="done" value={shown} onChange={change} />
        </span>
      )}
    />
  );
}
