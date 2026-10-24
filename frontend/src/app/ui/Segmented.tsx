import * as ToggleGroup from '@radix-ui/react-toggle-group';
import type { ReactNode } from 'react';

// The one "pick one" control (#520, docs/ui-rules.md rule 11): a choice of 2 to 5 options drawn as one tray with the chosen
// option raised, replacing the rows of separate pressed buttons (.cluster.filters). It is a Radix ToggleGroup of one choice,
// styled by our own tokens: a named group that is one Tab stop, the arrow keys moving between the options. A press of the
// chosen option asks for nothing unless the screen lets the choice be cleared (`clearable`), when it hands back null. The look
// is .segmented in src/app/ui/kit.css.

export interface SegmentedOption<T extends string> {
  value: T;
  label: ReactNode;
}

type SegmentedProps<T extends string> = {
  /** Names the group for a screen reader ("Show", "View"). */
  label: string;
  options: readonly SegmentedOption<T>[];
  /** The chosen option, or null while none is. */
  value: T | null;
  tour?: string;
} & (
  | { clearable?: false; onChange: (value: T) => void }
  /** A press of the chosen option clears the choice. */
  | { clearable: true; onChange: (value: T | null) => void }
);

export function Segmented<T extends string>(props: SegmentedProps<T>) {
  const { label, options, value, tour } = props;
  // Radix names "no choice" by the empty string; an option whose value is '' (All items) is told apart by a prefix.
  const key = (v: T) => `o:${v}`;
  return (
    <ToggleGroup.Root
      type="single"
      className="segmented"
      aria-label={label}
      data-tour={tour}
      loop
      value={value === null ? '' : key(value)}
      onValueChange={(next) => {
        if (next) props.onChange(next.slice(2) as T);
        else if (props.clearable) props.onChange(null);
      }}
    >
      {options.map((o) => (
        <ToggleGroup.Item key={o.value} type="button" value={key(o.value)}>
          {o.label}
        </ToggleGroup.Item>
      ))}
    </ToggleGroup.Root>
  );
}
