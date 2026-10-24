import { useId, useState } from 'react';
import { businessToday, daysBefore } from '../../api/retail';
import { Segmented } from './Segmented';
import { Sheet } from './Sheet';

// The one period picker of a list or report (issue #526, docs/ui-rules.md rule 11): a Segmented of Today, This week, This month
// and Custom, and under it the days in force in words ("17 Oct to 24 Oct 2026"). Custom opens the kit's Sheet with the two
// dates; the days apply once Show these days is pressed. A screen keeps its own default window (the last 30 days, say): it shows
// as Custom, and a tap on its days opens the sheet to change them. Dates are always shown by showDate, the one date formatter.

export type Period = 'today' | 'week' | 'month' | 'custom';

const PERIODS: readonly { value: Period; label: string }[] = [
  { value: 'today', label: 'Today' },
  { value: 'week', label: 'This week' },
  { value: 'month', label: 'This month' },
  { value: 'custom', label: 'Custom' },
];

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** The one date formatter: "6 Oct 2026" from "2026-10-06", with no hyphen for a narrow row to break at and no timezone shift. */
export function showDate(iso: string | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso ?? '');
  return m ? `${Number(m[3])} ${MONTHS[Number(m[2]) - 1] ?? m[2]} ${m[1]}` : (iso ?? '');
}

/** The days in words: "24 Oct 2026", "17 Oct to 24 Oct 2026", or both years when they differ. */
export function showRange(from: string, to: string): string {
  if (from === to) return showDate(to);
  const start = from.slice(0, 4) === to.slice(0, 4) ? showDate(from).replace(/ \d{4}$/, '') : showDate(from);
  return `${start} to ${showDate(to)}`;
}

/** The days of a preset, both ends counted: today; Monday to today; the 1st of the month to today. */
export function presetRange(period: Exclude<Period, 'custom'>, today: string): { from: string; to: string } {
  if (period === 'today') return { from: today, to: today };
  if (period === 'week') {
    const weekday = new Date(`${today}T00:00:00Z`).getUTCDay();
    return { from: daysBefore(today, (weekday + 6) % 7), to: today };
  }
  return { from: `${today.slice(0, 8)}01`, to: today };
}

/** The preset a range is, or custom. */
export function periodOf(from: string, to: string, today: string): Period {
  for (const period of ['today', 'week', 'month'] as const) {
    const range = presetRange(period, today);
    if (range.from === from && range.to === to) return period;
  }
  return 'custom';
}

/** Plain words when two typed days cannot be used, else null. Both ends count; `maxDays` is the server's limit when it has one. */
export function customProblem(from: string, to: string, today: string, maxDays?: number): string | null {
  if (!from || !to) return 'Choose both days.';
  if (from > to) return 'The first day must not be after the last.';
  if (to > today) return 'Choose days up to today.';
  const days = (Date.parse(`${to}T00:00:00Z`) - Date.parse(`${from}T00:00:00Z`)) / 86_400_000;
  if (maxDays !== undefined && days >= maxDays) return `Choose at most ${maxDays} days.`;
  return null;
}

export function PeriodPicker({ from, to, onChange, label = 'Days to show', maxDays, tour }: {
  from: string;
  to: string;
  onChange: (range: { from: string; to: string }) => void;
  /** Names the choice for a screen reader. */
  label?: string;
  maxDays?: number;
  tour?: string;
}) {
  const id = useId();
  const today = businessToday();
  const period = periodOf(from, to, today);
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState({ from, to });
  const [problem, setProblem] = useState<string | null>(null);

  const openSheet = () => {
    setDraft({ from, to });
    setProblem(null);
    setOpen(true);
  };
  const show = () => {
    const p = customProblem(draft.from, draft.to, today, maxDays);
    setProblem(p);
    if (p) return;
    setOpen(false);
    onChange(draft);
  };

  return (
    <div className="period-picker" data-tour={tour}>
      <Segmented<Period>
        label={label}
        options={PERIODS}
        value={period}
        clearable
        onChange={(next) => {
          // A press of the chosen option hands back null: on Custom it opens the days again, on a preset it does nothing.
          if (next === null) {
            if (period === 'custom') openSheet();
          } else if (next === 'custom') openSheet();
          else onChange(presetRange(next, today));
        }}
      />
      {period === 'custom' ? (
        <button type="button" className="btn-ghost btn-sm period-days" onClick={openSheet} aria-label={`${showRange(from, to)}. Change the days`}>
          {showRange(from, to)}
        </button>
      ) : (
        <p className="period-days">{showRange(from, to)}</p>
      )}
      <Sheet
        open={open}
        onClose={() => setOpen(false)}
        title="Choose the days"
        actions={
          <button type="button" className="btn-primary" onClick={show}>
            Show these days
          </button>
        }
      >
        <div className="rt-row">
          <div>
            <label htmlFor={`${id}-from`}>From</label>
            <input id={`${id}-from`} type="date" value={draft.from} max={draft.to || today} onChange={(e) => setDraft((d) => ({ ...d, from: e.target.value }))} />
          </div>
          <div>
            <label htmlFor={`${id}-to`}>To</label>
            <input id={`${id}-to`} type="date" value={draft.to} min={draft.from || undefined} max={today} onChange={(e) => setDraft((d) => ({ ...d, to: e.target.value }))} />
          </div>
        </div>
        {problem && <p role="alert" className="rt-flag">{problem}</p>}
      </Sheet>
    </div>
  );
}
