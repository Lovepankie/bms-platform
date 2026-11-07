import type { Reconciliation, ReconciliationCheck, ReconciliationItem, SignOffRun } from '../../../api/imports';
import { formatMinor } from '../../../components/money';
import { showDate } from '../../../app/ui/PeriodPicker';

// Imports to sign off (issue #134, spec sections 3.9 and 3.10): the words and figures of the screen, kept apart so they are
// tested without a page. Values arrive as exact decimal strings (money in minor units); they are shown as they are, grouped,
// and never rounded.

/** Groups the whole part of an exact decimal string by thousands: "1234567.5" reads "1,234,567.5". No float touches it. */
export function grouped(value: string): string {
  const m = /^(-?)(\d+)(\.\d+)?$/.exec(value.trim());
  if (!m) return value;
  return `${m[1]}${(m[2] ?? '').replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${m[3] ?? ''}`;
}

/** A figure of a check in its unit: money through the one formatter, every other unit grouped as it is. */
export function figure(value: string | undefined, unit: string | undefined, currency: string): string {
  const v = value ?? '0';
  if (unit === 'minor') {
    const n = Number(v);
    return /^-?\d+$/.test(v) && Number.isSafeInteger(n) ? formatMinor(n, currency) : `${currency} ${grouped(v)}`;
  }
  return grouped(v);
}

/** Why a difference is explained, in plain words. */
export function reasonWords(reason: string | undefined): string {
  switch (reason) {
    case 'excluded':
      return 'Left out';
    case 'overridden':
      return 'Warning accepted';
    case 'policy':
      return 'Your decision';
    default:
      return 'Explained';
  }
}

/** Where an explained difference comes from: the file and line, or the import as a whole. */
export function itemPlace(item: ReconciliationItem): string {
  if (!item.line || item.line <= 0) return 'The whole import';
  return `${item.file ?? 'File'}, line ${item.line}`;
}

/** The state of an imported run in plain words, for its row on the screen. */
export function runState(run: SignOffRun): string {
  if (run.status === 'signed_off') return run.signed_at ? `Signed on ${showDate(run.signed_at.slice(0, 10))}` : 'Signed';
  if (run.version == null) return 'Being checked';
  return run.signable ? 'Ready to sign' : 'A difference is not explained';
}

/** The title of an imported run's row: the day it was imported. */
export function runTitle(run: SignOffRun): string {
  return run.committed_at ? `Imported on ${showDate(run.committed_at.slice(0, 10))}` : 'Imported';
}

/** The checks of a reconciliation that do not tie, which hold the sign-off. */
export function notTied(r: Reconciliation): ReconciliationCheck[] {
  return (r.sections ?? []).flatMap((s) => (s.checks ?? []).filter((c) => !c.ties));
}

/** What is still missing before the sign-off can be sent, or null when it can: the first problem only. */
export function signOffProblem(r: Reconciliation, ticked: Set<string>, name: string, date: string, today: string): string | null {
  if (!r.signable) return 'A difference is not explained, so this cannot be signed yet.';
  const missing = (r.sections ?? []).filter((s) => !ticked.has(s.key ?? ''));
  if (missing.length > 0) return `Tick every section you have checked. Still to tick: ${missing.map((s) => s.title).join(', ')}.`;
  if (name.trim() === '') return 'Type your name.';
  if (date !== today) return `Type today's date, ${showDate(today)}.`;
  return null;
}
