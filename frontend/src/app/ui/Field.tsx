import { useId, useState, type FocusEvent, type InputHTMLAttributes, type ReactNode } from 'react';
import { icons } from '../../components/icons';

// The one form field (#520, docs/ui-rules.md rule 10): the label above, a hint under it, and the error in place of the hint.
// The error shows only once the person has left the field (or the form was sent, `showError`), never while they type, and
// stays until it is fixed. The field is described by the hint and the error and marked invalid while the error shows. Pass
// the input's own props (inputMode, autoComplete, value, onChange) as to an <input>, or draw another control with `control`,
// which is handed the id and the aria props to put on it. The error line carries the error icon.
//
// Required fields (owner rule, 24 Oct 2026): a `required` field shows a red asterisk after its label (hidden from a screen
// reader, which hears aria-required instead), and a form with any of them starts with <FieldsNeededNote />. A form where most
// fields are needed marks the few others `optional` instead ("(optional)"), never both on one form. The send button stays
// enabled: on a press the form calls focusFirstMissing, which scrolls to the first missing or wrong field and focuses it, and
// passes showError. The browser's own required bubble is not used. The look is .field in src/app/ui/kit.css.

/** The line a form with any required field starts with. */
export function FieldsNeededNote() {
  return (
    <p className="fields-needed">
      Fields marked <span className="field-star">*</span> are needed.
    </p>
  );
}

/** Scrolls to the first required field left empty, or the first marked wrong, inside `root`, and focuses it; false when none. */
export function focusFirstMissing(root: ParentNode): boolean {
  // An empty list a field needs (EmptyChoice, issue #619) marks its way to add one `data-needed`: it is always missing.
  const fields = [...root.querySelectorAll<HTMLInputElement>('[aria-required="true"], [aria-invalid="true"], [data-needed]')];
  const first = fields.find((f) => f.hasAttribute('data-needed') || f.getAttribute('aria-invalid') === 'true' || ('value' in f && String(f.value).trim() === ''));
  if (!first) return false;
  first.scrollIntoView?.({ block: 'center' });
  first.focus();
  return true;
}

export interface FieldControlProps {
  id: string;
  'aria-describedby'?: string;
  'aria-invalid': boolean;
  'aria-required'?: boolean;
  onBlur: (e: FocusEvent<HTMLElement>) => void;
}

export type FieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> & {
  label: ReactNode;
  hint?: ReactNode;
  /** What is wrong with the value now, or nothing; shown once the field has been left. */
  error?: string | null;
  /** Shows the error at once, as after a press of Save. */
  showError?: boolean;
  id?: string;
  control?: (props: FieldControlProps) => ReactNode;
  /** Marks the field "(optional)", on a form where most fields are needed. */
  optional?: boolean;
};

export function Field({ label, hint, error, showError = false, id, control, onBlur, className, required = false, optional = false, ...input }: FieldProps) {
  const own = useId();
  const fieldId = id ?? own;
  const [left, setLeft] = useState(false);
  const shown = error && (left || showError) ? error : null;
  const describedBy = shown ? `${fieldId}-error` : hint ? `${fieldId}-hint` : undefined;
  const props: FieldControlProps = {
    id: fieldId,
    'aria-describedby': describedBy,
    'aria-invalid': shown !== null,
    'aria-required': required || undefined,
    onBlur: (e) => {
      setLeft(true);
      onBlur?.(e as FocusEvent<HTMLInputElement>);
    },
  };
  return (
    <div className={className ? `field ${className}` : 'field'}>
      <label htmlFor={fieldId}>
        {label}
        {required && (
          <span className="field-star" aria-hidden="true">
            {' '}*
          </span>
        )}
        {optional && <span className="field-optional"> (optional)</span>}
      </label>
      {control ? control(props) : <input {...input} {...props} />}
      {shown ? (
        <span className="field-error" id={`${fieldId}-error`}>
          {icons.error}
          {shown}
        </span>
      ) : (
        hint && (
          <span className="field-hint" id={`${fieldId}-hint`}>
            {hint}
          </span>
        )
      )}
    </div>
  );
}
