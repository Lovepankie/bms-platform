import type { Me } from '../../../api/client';
import type { SmsSettings, SmsSettingsChange } from '../../../api/sms';

// The tenant SMS settings screen's logic (extras slice B3, issue #218), apart from the screen so it can be tested: the draft the
// form edits, the problems it can see before asking the server (which decides, and says the same), and the partial change
// to send. The one rule to keep in mind is that a cap of 0 means stop: it is a number, never "empty", and never read as
// "no cap of my own". An empty cap box means "no cap of my own" and nothing else.

export interface Draft {
  senderId: string;
  windowStart: string;
  windowEnd: string;
  dailyCap: string;
  monthlyCap: string;
  includeBalance: boolean;
}

export function draftOf(s: SmsSettings): Draft {
  return {
    senderId: s.sender_id ?? '',
    windowStart: s.window_start,
    windowEnd: s.window_end,
    // 0 is shown as "0": a cap that stops everything, not an empty box.
    dailyCap: s.daily_cap === null ? '' : String(s.daily_cap),
    monthlyCap: s.monthly_cap === null ? '' : String(s.monthly_cap),
    includeBalance: s.include_balance,
  };
}

/** A cap written in a box: null for an empty box, a whole number (0 included), or 'invalid'. */
export function parseCap(text: string): number | null | 'invalid' {
  const t = text.trim();
  if (t === '') return null;
  return /^\d{1,7}$/.test(t) ? Number(t) : 'invalid';
}

/** What is wrong with a cap as typed, or null. A cap can be lowered under the ceiling and never raised above it. */
export function capProblem(text: string, ceiling: number): string | null {
  const cap = parseCap(text);
  if (cap === null) return null;
  if (cap === 'invalid') return 'Use a whole number. 0 stops every message.';
  if (cap > ceiling) return `At most ${ceiling}. A cap can be lowered, not raised.`;
  return null;
}

const minutes = (hhmm: string): number => Number(hhmm.slice(0, 2)) * 60 + Number(hhmm.slice(3, 5));
const CLOCK = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;

/** The window rules the server also enforces (SmsSettingsGroup): a time, start before end, an hour at least, inside the limits. */
export function windowProblem(
  start: string,
  end: string,
  limits: Pick<SmsSettings, 'window_earliest' | 'window_latest' | 'window_min_minutes'>,
): string | null {
  if (!CLOCK.test(start) || !CLOCK.test(end)) return 'Choose a time for both.';
  if (minutes(start) >= minutes(end)) return 'The window must end later than it starts.';
  if (minutes(end) - minutes(start) < limits.window_min_minutes) return `The window must be at least ${limits.window_min_minutes} minutes.`;
  if (minutes(start) < minutes(limits.window_earliest)) return `Messages cannot start before ${limits.window_earliest}.`;
  if (minutes(end) > minutes(limits.window_latest)) return `Messages cannot go after ${limits.window_latest}.`;
  return null;
}

/** Every problem the form can see, by field; empty when it can be saved. */
export function problemsOf(saved: SmsSettings, d: Draft): Record<string, string> {
  const out: Record<string, string> = {};
  const window = windowProblem(d.windowStart, d.windowEnd, saved);
  if (window) out.window = window;
  const daily = capProblem(d.dailyCap, saved.daily_ceiling);
  if (daily) out.daily_cap = daily;
  const monthly = capProblem(d.monthlyCap, saved.monthly_ceiling);
  if (monthly) out.monthly_cap = monthly;
  return out;
}

/**
 * The partial change that turns the saved settings into the draft, only what differs. An emptied cap box on a cap that
 * was set goes back to "no cap of my own" by setting the ceiling itself (the same effect: the ceiling applies).
 */
export function changeOf(saved: SmsSettings, d: Draft): SmsSettingsChange {
  const change: SmsSettingsChange = {};
  if (d.senderId !== (saved.sender_id ?? '')) change.sender_id = d.senderId;
  if (d.windowStart !== saved.window_start) change.window_start = d.windowStart;
  if (d.windowEnd !== saved.window_end) change.window_end = d.windowEnd;
  const daily = capChange(d.dailyCap, saved.daily_cap, saved.daily_ceiling);
  if (daily !== undefined) change.daily_cap = daily;
  const monthly = capChange(d.monthlyCap, saved.monthly_cap, saved.monthly_ceiling);
  if (monthly !== undefined) change.monthly_cap = monthly;
  if (d.includeBalance !== saved.include_balance) change.include_balance = d.includeBalance;
  return change;
}

function capChange(text: string, saved: number | null, ceiling: number): number | undefined {
  const cap = parseCap(text);
  if (cap === 'invalid') return undefined;
  if (cap === null) return saved === null ? undefined : ceiling;
  return cap === saved ? undefined : cap;
}

export const isChange = (change: SmsSettingsChange): boolean => Object.keys(change).length > 0;

/** A cap in words, the effective one: 0 says plainly that nothing is sent. */
export function capWords(effective: number, noun: string): string {
  if (effective === 0) return `0 messages a ${noun}: nothing is sent while it is 0`;
  return `${effective} ${effective === 1 ? 'message' : 'messages'} a ${noun}`;
}

/** What this system does with a message, in words for the administrator. */
export function modeNote(settings: Pick<SmsSettings, 'mode' | 'operator_enabled' | 'enabled'>): string | null {
  if (!settings.operator_enabled) {
    return 'The platform has not switched SMS on for your business yet. Your choices here are saved, and nothing is sent until it does.';
  }
  switch (settings.mode) {
    case 'off':
      return 'SMS is switched off on this system, so nothing is sent whatever you choose here.';
    case 'sink':
      return 'This is a test system: messages are recorded but never leave the platform.';
    case 'allowlist':
      return 'This is a test system: only a few listed numbers receive messages.';
    default:
      return null;
  }
}

/** The SMS tab is for those who may read settings or notifications; the server decides what they may change. */
export function showSms(me: Pick<Me, 'permissions'>): boolean {
  const held = me.permissions ?? [];
  return held.includes('core.settings.read') || held.includes('core.notifications.read');
}

export const mayManageSettings = (me: Pick<Me, 'permissions'>): boolean => (me.permissions ?? []).includes('core.settings.manage');
export const mayManageWording = (me: Pick<Me, 'permissions'>): boolean =>
  (me.permissions ?? []).includes('core.notification_templates.manage');
export const mayReadSettings = (me: Pick<Me, 'permissions'>): boolean => (me.permissions ?? []).includes('core.settings.read');
export const mayReadNotifications = (me: Pick<Me, 'permissions'>): boolean =>
  (me.permissions ?? []).includes('core.notifications.read');
