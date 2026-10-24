import type { KeyboardEvent } from 'react';
import { icons } from '../../components/icons';

// The quantity control (#520, docs/ui-rules.md rule 11): minus and plus around a number field, each a 48px button, so a
// quantity is changed by a tap and still typed when it is large. The value stays the text the person typed (a screen may allow
// 1.5 kg); minus and plus move it by `step` and keep it within `min` and `max`. In the field ArrowUp and ArrowDown do the same;
// Home and End still move the caret. At an end the button is aria-disabled, not disabled, so it keeps focus. The field keeps its
// own id and label, so a screen's tests and tours still find it. The look is .stepper in src/app/ui/kit.css.

/** The value moved by `delta`, kept within the bounds; an empty or unreadable value counts as `min`. */
export function stepped(value: string, delta: number, min: number, max?: number): string {
  const now = Number(value.trim());
  const from = value.trim() === '' || !Number.isFinite(now) ? min : now;
  // Rounded to 6 places so 0.1 steps do not drift (0.30000000000000004).
  const next = Math.round((from + delta) * 1e6) / 1e6;
  return String(Math.min(max ?? Infinity, Math.max(min, next)));
}

export function Stepper({ id, label, value, onChange, min = 0, max, step = 1, name, inputMode = 'numeric', invalid = false, describedBy, tour }: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  min?: number;
  max?: number;
  step?: number;
  /** What is counted, for the buttons' names ("One more Sugar 1 kg"); without it they are "One less" and "One more". */
  name?: string;
  /** `decimal` where a part is allowed (1.5 kg). */
  inputMode?: 'numeric' | 'decimal';
  invalid?: boolean;
  /** The id of the hint or error under the field. */
  describedBy?: string;
  tour?: string;
}) {
  const now = Number(value);
  const known = value.trim() !== '' && Number.isFinite(now);
  const atMin = known && now <= min;
  const atMax = known && max !== undefined && now >= max;
  const keys = (e: KeyboardEvent<HTMLInputElement>) => {
    // At an end the key does nothing, as the button does: ArrowDown on 0.5 with a minimum of 1 never raises it to 1.
    const moves: Record<string, () => string | null> = {
      ArrowUp: () => (atMax ? null : stepped(value, step, min, max)),
      ArrowDown: () => (atMin ? null : stepped(value, -step, min, max)),
    };
    const next = moves[e.key]?.();
    if (next === undefined) return;
    e.preventDefault();
    if (next !== null) onChange(next);
  };
  const suffix = name ? ` ${name}` : '';
  return (
    <div className="stepper-field" data-tour={tour}>
      <label htmlFor={id}>{label}</label>
      <div className="stepper">
        <button type="button" aria-label={`One less${suffix}`} aria-controls={id} aria-disabled={atMin} onClick={() => atMin || onChange(stepped(value, -step, min, max))}>
          {icons.minus}
        </button>
        <input
          id={id}
          inputMode={inputMode}
          autoComplete="off"
          value={value}
          aria-invalid={invalid}
          aria-describedby={describedBy}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={keys}
        />
        <button type="button" aria-label={`One more${suffix}`} aria-controls={id} aria-disabled={atMax} onClick={() => atMax || onChange(stepped(value, step, min, max))}>
          {icons.plus}
        </button>
      </div>
    </div>
  );
}
