import type { Me, TenantSettings } from '../../../api/client';
import { type Choice, type Policy, relaxes, widens } from '../../../api/two-step';

// The rules of the Two-step sign-in screens (issue #348; ADR-046), kept out of the components so they can be tested: who may change the
// policy, what the stored settings mean as a choice, the plain-words review before saving, the date shown to people, the reminder for a
// person the policy covers, and the recommendation for administrators while the policy is off.

const MANAGE = 'core.settings.manage';

/** Only the people who may change the settings change the policy (no new permission, ADR-046 decision 2). */
export const canManageTwoStep = (me: Pick<Me, 'permissions'>): boolean => (me.permissions ?? []).includes(MANAGE);

/** The grace periods offered; a stored value outside this list is added by {@link graceChoices} so it still reads. */
export const GRACE_CHOICES: { days: number; label: string }[] = [
  { days: 0, label: 'No wait: from the next sign-in' },
  { days: 7, label: '7 days' },
  { days: 14, label: '14 days' },
  { days: 30, label: '30 days' },
];

/** What the screen suggests for a business turning it on for the first time. */
export const DEFAULT_GRACE = 14;

export const graceChoices = (current: number): { days: number; label: string }[] =>
  GRACE_CHOICES.some((g) => g.days === current) ? GRACE_CHOICES : [...GRACE_CHOICES, { days: current, label: `${current} days` }].sort((a, b) => a.days - b.days);

type Stored = Pick<TenantSettings, 'require_mfa_all_staff' | 'require_mfa_roles' | 'mfa_grace_days'>;

/** The policy the stored settings amount to: everyone wins over roles, and neither means nobody is required. */
export function policyOf(settings: Stored): Policy {
  if (settings.require_mfa_all_staff) return 'everyone';
  return (settings.require_mfa_roles ?? []).length > 0 ? 'roles' : 'off';
}

/** The stored settings as the screen's starting choice. */
export function choiceOf(settings: Stored): Choice {
  return { policy: policyOf(settings), roles: [...(settings.require_mfa_roles ?? [])].sort(), graceDays: settings.mfa_grace_days ?? DEFAULT_GRACE };
}

/**
 * Whether saving this would change anything. The grace period counts only while something is required, and it counts because
 * sending it moves the deadline to now plus these days.
 */
export function differs(a: Choice, b: Choice): boolean {
  if (a.policy !== b.policy) return true;
  if (a.policy === 'off') return false;
  if (a.graceDays !== b.graceDays) return true;
  return a.policy === 'roles' && [...a.roles].sort().join('|') !== [...b.roles].sort().join('|');
}

/** A save needs the roles it names: "chosen roles" with none chosen is not a choice yet. */
export const ready = (choice: Choice): boolean => choice.policy !== 'roles' || choice.roles.length > 0;

/** The zone a deadline is a day's end in until the server says otherwise (the tenant's own zone is in `/me` and the settings). */
export const DEFAULT_ZONE = 'Africa/Kampala';

/** "3 Nov 2026" in the business's time zone, or null for something that is not a date. */
export function dateWords(iso: string | null | undefined, zone: string = DEFAULT_ZONE): string | null {
  if (!iso) return null;
  const when = new Date(iso);
  if (Number.isNaN(when.getTime())) return null;
  return when.toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: zone });
}

const localDay = (at: Date, zone: string): string => at.toLocaleDateString('en-CA', { timeZone: zone });

const HOUR = 60 * 60 * 1000;

/** How far the zone is ahead of UTC at this instant, in milliseconds. */
function zoneOffsetMs(at: number, zone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(new Date(at));
  const part = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  return Date.UTC(part('year'), part('month') - 1, part('day'), part('hour'), part('minute'), part('second')) - Math.floor(at / 1000) * 1000;
}

/**
 * The instant a calendar day starts in the zone, as java.time's `LocalDate.atStartOfDay(zone)` gives it (issue #377). Midnight is
 * read with the offset in force well before it and well after it (a zone's local midnight is within 15 hours of UTC midnight): an
 * offset that really applies at that instant is a valid start, and when both are (the clocks went back over midnight) the earlier
 * instant is the start. When neither is, midnight was skipped (the clocks went forward at 00:00, as in America/Santiago) and the
 * day starts when the clocks jump, which is midnight read with the earlier offset.
 */
function startOfDay(year: number, month: number, day: number, zone: string): number {
  const guess = Date.UTC(year, month - 1, day);
  const before = zoneOffsetMs(guess - 15 * HOUR, zone);
  const after = zoneOffsetMs(guess + 15 * HOUR, zone);
  const valid = [before, after].map((offset) => ({ at: guess - offset, offset })).filter((c) => zoneOffsetMs(c.at, zone) === c.offset);
  return valid.length > 0 ? Math.min(...valid.map((c) => c.at)) : guess - before;
}

/**
 * The instant the server stores for a grace period of `graceDays` saved at `now`: the start of the day after the last day people may
 * sign in, so "by 3 Nov" means the whole of 3 Nov in the business's zone. Mirrors SettingsService.deadlineAfter.
 */
export function deadlineOf(graceDays: number, now: Date, zone: string = DEFAULT_ZONE): string {
  const [year, month, day] = localDay(now, zone).split('-').map(Number) as [number, number, number];
  const target = new Date(Date.UTC(year, month - 1, day + graceDays + 1));
  return new Date(startOfDay(target.getUTCFullYear(), target.getUTCMonth() + 1, target.getUTCDate(), zone)).toISOString();
}

/** The last day people may sign in for a stored deadline: the day before the instant, "3 Nov 2026". */
export function deadlineWords(iso: string | null | undefined, zone: string = DEFAULT_ZONE): string | null {
  if (!iso) return null;
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? null : dateWords(new Date(at.getTime() - 1).toISOString(), zone);
}

const listWords = (names: string[]): string => (names.length <= 1 ? (names[0] ?? '') : `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`);

/** What the screen knows of the saved state, so the review can say what happens to the date already set. */
export type Saved = { choice: Choice; enforceFrom: string | null | undefined; timezone?: string };

/**
 * Whether sending this choice would put a deadline back: a grace period is sent (the policy widens or the grace changed) and its date
 * is later than the one set, or people were required at once and now get a date. The server asks for the same proof then.
 */
export function postpones(saved: Saved, choice: Choice, now: Date): boolean {
  if (saved.choice.policy === 'off' || choice.policy === 'off' || choice.graceDays === 0) return false;
  if (!(widens(saved.choice, choice) || choice.graceDays !== saved.choice.graceDays)) return false;
  const next = new Date(deadlineOf(choice.graceDays, now, saved.timezone)).getTime();
  return !saved.enforceFrom || next > new Date(saved.enforceFrom).getTime();
}

/** Whether saving needs the administrator's proof: the rule gets weaker, or a date is put back. */
export const needsProof = (saved: Saved, choice: Choice, now: Date): boolean => relaxes(saved.choice, choice) || postpones(saved, choice, now);

/**
 * The review shown before the policy is saved, one plain sentence per line. It says who is asked, until when, whether the date already
 * set moves (and when it moves earlier), that the person saving is asked too when they are covered, what it takes to relax the rule,
 * and what happens when someone loses their phone.
 */
export function reviewLines(choice: Choice, roleNames: Record<string, string>, me: Pick<Me, 'roles'>, now: Date, saved?: Saved): string[] {
  const lines: string[] = [];
  if (choice.policy === 'off') {
    lines.push('Nobody will be required to use it. People who have already set it up keep using it, and anyone can still turn it on for themselves.');
    lines.push('Because this makes the rule weaker, you will be asked to confirm that it is you, and the other administrators are told by email.');
    return lines;
  }
  if (choice.policy === 'everyone') {
    lines.push('Everyone who signs in to the staff area will be asked to use it.');
  } else {
    const names = choice.roles.map((key) => roleNames[key] ?? key);
    lines.push(`People with the ${names.length === 1 ? 'role' : 'roles'} ${listWords(names)} will be asked to use it.`);
  }
  const sendsGrace = !saved || widens(saved.choice, choice) || choice.graceDays !== saved.choice.graceDays;
  const zone = saved?.timezone ?? DEFAULT_ZONE;
  const old = deadlineWords(saved?.enforceFrom, zone);
  if (sendsGrace) {
    if (choice.graceDays === 0) {
      lines.push('There is no wait: anyone who has not set it up is asked the next time they sign in, and cannot use the app until they have.');
      if (old) lines.push(`This replaces the date now set, the end of ${old}.`);
    } else {
      const date = deadlineWords(deadlineOf(choice.graceDays, now, zone), zone);
      lines.push(
        `Nobody is locked out before the end of ${date}. Until then people who have not set it up can keep signing in and see a reminder. After that, they are asked at their next sign-in and cannot use the app until they have set it up.`,
      );
      if (old && saved?.enforceFrom && new Date(deadlineOf(choice.graceDays, now, zone)).getTime() < new Date(saved.enforceFrom).getTime()) {
        lines.push(`This moves the date earlier than the one now set, the end of ${old}.`);
      } else if (old) {
        lines.push(`This replaces the date now set, the end of ${old}.`);
      }
    }
  } else if (old) {
    lines.push(`The date already set does not change: the end of ${old}.`);
  }
  const mine = (me.roles ?? []).map((r) => r.role_key);
  if (choice.policy === 'everyone' || choice.roles.some((key) => mine.includes(key))) {
    lines.push('You are covered too, so you will be asked as well.');
  }
  if (saved && relaxes(saved.choice, choice)) {
    lines.push('Because this makes the rule weaker, you will be asked to confirm that it is you, and the other administrators are told by email.');
  } else if (saved && postpones(saved, choice, now)) {
    lines.push('Because this gives people more time, you will be asked to confirm that it is you, and the other administrators are told by email.');
  }
  lines.push(
    'If someone loses their phone, an administrator can reset their two-step sign-in. If you have only one administrator, we have to help them, so ask them to keep their recovery codes safe.',
  );
  return lines;
}

/**
 * The reminder for a person the policy covers who has not set it up yet, or null. Before the date it names the last day; after it,
 * the person is blocked until they have set it up (the staff layout shows the set-up screen), and the reminder says why.
 */
export function reminderWords(me: Pick<Me, 'mfa_due_by' | 'mfa_enabled' | 'mfa_required' | 'tenant_timezone'>): string | null {
  if (me.mfa_enabled) return null;
  if (me.mfa_required) return 'Your business now requires two-step sign-in. Set it up to go on using the app.';
  const date = deadlineWords(me.mfa_due_by, me.tenant_timezone ?? DEFAULT_ZONE);
  return date ? `Your business asks you to set up two-step sign-in by ${date} (you can sign in until the end of that day). After that you cannot sign in without it.` : null;
}

/** Whether the person must set it up now: the business requires it of them and they have no second factor. */
export const mustSetUp = (me: Pick<Me, 'mfa_required' | 'mfa_enabled'>): boolean => me.mfa_required === true && me.mfa_enabled !== true;

/** The recommendation is for people who may change the policy, while it is off. */
export const recommends = (me: Pick<Me, 'permissions' | 'two_step_policy'>): boolean => canManageTwoStep(me) && me.two_step_policy === 'off';

// "Not now" puts the recommendation away for 14 days. It is a convenience of this browser only (storage can be empty, blocked or
// throw), so the page works without it and the recommendation simply comes back.
const HIDE_DAYS = 14;
const keyOf = (userId: string | undefined) => `bms.twoStep.recommendation.${userId ?? 'me'}`;

type Box = Pick<Storage, 'getItem' | 'setItem'>;

function box(): Box | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage;
  } catch {
    return null;
  }
}

export function recommendationHidden(userId: string | undefined, now: number, storage: Box | null = box()): boolean {
  try {
    const until = Number(storage?.getItem(keyOf(userId)));
    return Number.isFinite(until) && until > now;
  } catch {
    return false;
  }
}

export function hideRecommendation(userId: string | undefined, now: number, storage: Box | null = box()): void {
  try {
    storage?.setItem(keyOf(userId), String(now + HIDE_DAYS * 86_400_000));
  } catch {
    // Nothing to do: the recommendation will show again.
  }
}
