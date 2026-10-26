import { describe, expect, it } from 'vitest';
import { bodyOf, relaxes, widens } from '../../../api/two-step';
import {
  GRACE_CHOICES,
  canManageTwoStep,
  choiceOf,
  dateWords,
  deadlineOf,
  needsProof,
  postpones,
  deadlineWords,
  differs,
  graceChoices,
  hideRecommendation,
  mustSetUp,
  policyOf,
  ready,
  recommendationHidden,
  recommends,
  reminderWords,
  reviewLines,
} from './state';

// The rules of the Two-step sign-in screens (issue #348; ADR-046). All people and roles are invented.

const NOW = new Date('2026-10-20T09:00:00Z');
const NAMES = { cashier: 'Cashier', accountant: 'Accountant', tenant_admin: 'Tenant admin' };
const ADMIN_ME = { roles: [{ role_key: 'tenant_admin' }] };

describe('who may change it', () => {
  it('is whoever may change the settings, and no one else', () => {
    expect(canManageTwoStep({ permissions: ['core.settings.manage'] })).toBe(true);
    expect(canManageTwoStep({ permissions: ['core.settings.read'] })).toBe(false);
    expect(canManageTwoStep({})).toBe(false);
  });
});

describe('the stored settings as a choice', () => {
  it('reads nothing set as optional, with the suggested grace period', () => {
    expect(policyOf({})).toBe('off');
    expect(choiceOf({})).toEqual({ policy: 'off', roles: [], graceDays: 14 });
  });

  it('lets everyone win over roles, and roles win over nothing', () => {
    expect(policyOf({ require_mfa_all_staff: true, require_mfa_roles: ['cashier'] })).toBe('everyone');
    expect(policyOf({ require_mfa_roles: ['cashier'] })).toBe('roles');
    expect(policyOf({ require_mfa_all_staff: false, require_mfa_roles: [] })).toBe('off');
    expect(choiceOf({ require_mfa_roles: ['cashier', 'accountant'], mfa_grace_days: 7 })).toEqual({ policy: 'roles', roles: ['accountant', 'cashier'], graceDays: 7 });
  });

  it('offers a stored grace period that is not on the list, in its place', () => {
    expect(graceChoices(14)).toBe(GRACE_CHOICES);
    expect(graceChoices(5).map((g) => g.days)).toEqual([0, 5, 7, 14, 30]);
  });
});

describe('whether saving would change anything', () => {
  const off = { policy: 'off' as const, roles: [], graceDays: 14 };
  it('ignores the grace period while nothing is required', () => {
    expect(differs(off, { ...off, graceDays: 30 })).toBe(false);
    expect(differs(off, { ...off, policy: 'everyone' })).toBe(true);
  });

  it('counts a new grace period or other roles once something is required', () => {
    const roles = { policy: 'roles' as const, roles: ['cashier'], graceDays: 14 };
    expect(differs(roles, { ...roles })).toBe(false);
    expect(differs(roles, { ...roles, graceDays: 7 })).toBe(true);
    expect(differs(roles, { ...roles, roles: ['cashier', 'accountant'] })).toBe(true);
    expect(differs(roles, { ...roles, roles: ['cashier'] })).toBe(false);
  });

  it('needs a role when roles are chosen', () => {
    expect(ready({ policy: 'roles', roles: [], graceDays: 14 })).toBe(false);
    expect(ready({ policy: 'roles', roles: ['cashier'], graceDays: 14 })).toBe(true);
    expect(ready({ policy: 'everyone', roles: [], graceDays: 0 })).toBe(true);
  });
});

describe('what is sent', () => {
  const off = { policy: 'off' as const, roles: [], graceDays: 14 };
  const everyone = { policy: 'everyone' as const, roles: [], graceDays: 14 };
  const cashiers = { policy: 'roles' as const, roles: ['cashier'], graceDays: 14 };

  it('sends the grace period when the policy widens, and empties the roles unless roles are chosen', () => {
    expect(bodyOf({ ...everyone, roles: ['cashier'] }, off)).toEqual({ require_mfa_all_staff: true, require_mfa_roles: [], mfa_grace_days: 14 });
    expect(bodyOf({ ...cashiers, graceDays: 0 }, off)).toEqual({ require_mfa_all_staff: false, require_mfa_roles: ['cashier'], mfa_grace_days: 0 });
    expect(bodyOf({ ...cashiers, roles: ['cashier', 'auditor'] }, cashiers)).toMatchObject({ mfa_grace_days: 14 });
    expect(bodyOf(everyone, cashiers)).toMatchObject({ mfa_grace_days: 14 });
  });

  it('narrowing keeps the deadline: no grace period is sent, only the proof', () => {
    expect(bodyOf(cashiers, everyone, { password: 'Test-Password-1' })).toEqual({ require_mfa_all_staff: false, require_mfa_roles: ['cashier'], password: 'Test-Password-1' });
    expect(bodyOf({ ...cashiers, roles: ['cashier'] }, { ...cashiers, roles: ['cashier', 'auditor'] }, { totp_code: '123456' })).toEqual({
      require_mfa_all_staff: false,
      require_mfa_roles: ['cashier'],
      totp_code: '123456',
    });
    expect(bodyOf(off, everyone, { password: 'Test-Password-1' })).toEqual({ require_mfa_all_staff: false, require_mfa_roles: [], password: 'Test-Password-1' });
  });

  it('sends a grace period the administrator changed, and leaves the proof out of a change that asks more', () => {
    expect(bodyOf({ ...everyone, graceDays: 7 }, everyone)).toEqual({ require_mfa_all_staff: true, require_mfa_roles: [], mfa_grace_days: 7 });
    // The screen passes the proof only when saving needs it; the body carries whatever it is given.
    expect(bodyOf(everyone, everyone)).toEqual({ require_mfa_all_staff: true, require_mfa_roles: [] });
    expect(bodyOf({ ...everyone, graceDays: 90 }, everyone, { password: 'x' })).toEqual({ require_mfa_all_staff: true, require_mfa_roles: [], mfa_grace_days: 90, password: 'x' });
  });

  it('tells widening from relaxing', () => {
    expect(widens(off, cashiers)).toBe(true);
    expect(widens(cashiers, everyone)).toBe(true);
    expect(widens(everyone, cashiers)).toBe(false);
    expect(widens(cashiers, off)).toBe(false);
    expect(relaxes(everyone, off)).toBe(true);
    expect(relaxes(everyone, cashiers)).toBe(true);
    expect(relaxes({ ...cashiers, roles: ['cashier', 'auditor'] }, cashiers)).toBe(true);
    expect(relaxes(cashiers, everyone)).toBe(false);
    expect(relaxes(off, cashiers)).toBe(false);
  });
});

describe('dates', () => {
  it('shows a day in the business time zone and nothing for what is not a date', () => {
    expect(dateWords('2026-11-03T09:00:00Z')).toBe('3 Nov 2026');
    expect(dateWords('2026-11-03T22:30:00Z')).toBe('4 Nov 2026');
    expect(dateWords(null)).toBeNull();
    expect(dateWords('not a date')).toBeNull();
  });

  it('rounds the end of the grace period to the start of the day after, in Kampala', () => {
    // 20 Oct 2026 12:00 in Kampala; 14 days on is 3 Nov, so people may sign in until the end of 3 Nov: the stored instant is 4 Nov 00:00 +03:00.
    expect(deadlineOf(14, NOW)).toBe('2026-11-03T21:00:00.000Z');
    expect(deadlineOf(0, NOW)).toBe('2026-10-20T21:00:00.000Z');
    // 22:30 UTC is 01:30 on the 21st in Kampala: the day is counted there, so one day on is the 22nd and the instant is the 23rd at 00:00.
    expect(deadlineOf(1, new Date('2026-10-20T22:30:00Z'))).toBe('2026-10-22T21:00:00.000Z');
  });

  it("rounds in the business's own zone, not Kampala", () => {
    // 20 Oct 05:00 in New York; 14 days on is 3 Nov; the clocks go back on 1 Nov, so the 4th starts at 05:00 UTC.
    expect(deadlineOf(14, NOW, 'America/New_York')).toBe('2026-11-04T05:00:00.000Z');
    expect(deadlineWords('2026-11-04T05:00:00.000Z', 'America/New_York')).toBe('3 Nov 2026');
    expect(dateWords('2026-11-04T02:00:00Z', 'America/New_York')).toBe('3 Nov 2026');
  });

  // Issue #377: the day a zone skips midnight starts when the clocks jump, as java.time's atStartOfDay says on the server.
  it('starts a day whose midnight is skipped when the clocks jump, as the server does', () => {
    // Santiago puts its clocks forward from 00:00 to 01:00 on 6 Sep 2026: that day starts at 01:00 local, 04:00 UTC.
    expect(deadlineOf(5, new Date('2026-09-01T12:00:00Z'), 'America/Santiago')).toBe('2026-09-07T03:00:00.000Z');
    expect(deadlineOf(4, new Date('2026-09-01T12:00:00Z'), 'America/Santiago')).toBe('2026-09-06T04:00:00.000Z');
    // ICU spells September 'Sep' or 'Sept' depending on its version.
    expect(deadlineWords('2026-09-06T04:00:00.000Z', 'America/Santiago')).toMatch(/^5 Sept? 2026$/);
    // The day before the jump and an ordinary day are unchanged.
    expect(deadlineOf(3, new Date('2026-09-01T12:00:00Z'), 'America/Santiago')).toBe('2026-09-05T04:00:00.000Z');
    // New York's change at 02:00 is not at midnight: 8 Mar 2026 starts at 05:00 UTC and 9 Mar at 04:00 UTC.
    expect(deadlineOf(6, new Date('2026-03-01T17:00:00Z'), 'America/New_York')).toBe('2026-03-08T05:00:00.000Z');
    expect(deadlineOf(7, new Date('2026-03-01T17:00:00Z'), 'America/New_York')).toBe('2026-03-09T04:00:00.000Z');
  });

  it('names the last day for a stored deadline, not the day after', () => {
    expect(deadlineWords('2026-11-03T21:00:00.000Z')).toBe('3 Nov 2026');
    expect(deadlineWords(deadlineOf(14, NOW))).toBe('3 Nov 2026');
    expect(deadlineWords(null)).toBeNull();
  });
});

describe("when saving needs the administrator's proof", () => {
  const everyone = { policy: 'everyone' as const, roles: [], graceDays: 14 };
  const on = (enforceFrom: string | null) => ({ choice: everyone, enforceFrom });

  it('asks when the rule gets weaker or a date is put back, and not when it is brought forward', () => {
    expect(needsProof(on(null), { policy: 'off', roles: [], graceDays: 14 }, NOW)).toBe(true);
    // People were required at once: any date is a postponement.
    expect(postpones(on(null), { ...everyone, graceDays: 7 }, NOW)).toBe(true);
    // The date set is 3 Nov: 30 days is later, 7 days is earlier, 14 days is the same and is not even sent.
    const set = on(deadlineOf(14, NOW));
    expect(postpones(set, { ...everyone, graceDays: 30 }, NOW)).toBe(true);
    expect(postpones(set, { ...everyone, graceDays: 7 }, NOW)).toBe(false);
    expect(postpones(set, everyone, NOW)).toBe(false);
    expect(needsProof(set, { ...everyone, graceDays: 7 }, NOW)).toBe(false);
    expect(postpones(set, { ...everyone, graceDays: 0 }, NOW)).toBe(false);
    // Turning it on from off sets a first date for nobody who was covered.
    expect(postpones({ choice: { policy: 'off', roles: [], graceDays: 14 }, enforceFrom: null }, everyone, NOW)).toBe(false);
  });
});

describe('the review before saving', () => {
  const nov3 = '2026-11-03T21:00:00.000Z';
  const roles = { policy: 'roles' as const, roles: ['cashier', 'accountant'], graceDays: 14 };

  it('says who, until when, and what happens to a lost phone', () => {
    const lines = reviewLines(roles, NAMES, ADMIN_ME, NOW);
    expect(lines[0]).toBe('People with the roles Cashier and Accountant will be asked to use it.');
    expect(lines[1]).toContain('Nobody is locked out before the end of 3 Nov 2026.');
    expect(lines.join(' ')).not.toContain('You are covered too');
    expect(lines[lines.length - 1]).toContain('If someone loses their phone');
  });

  it('names one role in the singular and tells the administrator when they are covered too', () => {
    const lines = reviewLines({ policy: 'roles', roles: ['tenant_admin'], graceDays: 7 }, NAMES, ADMIN_ME, NOW);
    expect(lines[0]).toBe('People with the role Tenant admin will be asked to use it.');
    expect(lines).toContain('You are covered too, so you will be asked as well.');
    expect(reviewLines({ policy: 'everyone', roles: [], graceDays: 7 }, NAMES, { roles: [] }, NOW)).toContain('You are covered too, so you will be asked as well.');
  });

  it('says there is no wait when the grace period is none, and that making it optional is a weaker rule that needs confirming', () => {
    const now = reviewLines({ policy: 'everyone', roles: [], graceDays: 0 }, NAMES, ADMIN_ME, NOW);
    expect(now[0]).toBe('Everyone who signs in to the staff area will be asked to use it.');
    expect(now[1]).toContain('There is no wait');
    expect(now.join(' ')).not.toContain('locked out before');
    const off = reviewLines({ policy: 'off', roles: [], graceDays: 14 }, NAMES, ADMIN_ME, NOW);
    expect(off[0]).toContain('Nobody will be required to use it.');
    expect(off[1]).toContain('you will be asked to confirm that it is you');
  });

  it('says that narrowing leaves the date already set, and that relaxing needs confirming', () => {
    const saved = { choice: { policy: 'everyone' as const, roles: [], graceDays: 14 }, enforceFrom: nov3 };
    const lines = reviewLines({ policy: 'roles', roles: ['cashier'], graceDays: 14 }, NAMES, ADMIN_ME, NOW, saved);
    expect(lines).toContain('The date already set does not change: the end of 3 Nov 2026.');
    expect(lines.join(' ')).not.toContain('Nobody is locked out before');
    expect(lines.join(' ')).toContain('you will be asked to confirm that it is you, and the other administrators are told by email');
  });

  it('says in plain words when a new grace period moves the date earlier, or replaces it', () => {
    const saved = { choice: { policy: 'everyone' as const, roles: [], graceDays: 30 }, enforceFrom: deadlineOf(30, NOW) };
    const earlier = reviewLines({ policy: 'everyone', roles: [], graceDays: 7 }, NAMES, ADMIN_ME, NOW, saved);
    expect(earlier.join(' ')).toContain('This moves the date earlier than the one now set, the end of 19 Nov 2026.');
    const later = reviewLines({ policy: 'everyone', roles: [], graceDays: 60 }, NAMES, ADMIN_ME, NOW, saved);
    expect(later.join(' ')).toContain('This replaces the date now set, the end of 19 Nov 2026.');
    const none = reviewLines({ policy: 'everyone', roles: [], graceDays: 0 }, NAMES, ADMIN_ME, NOW, saved);
    expect(none.join(' ')).toContain('This replaces the date now set');
  });

  it('falls back to the role key for a role it has no name for', () => {
    expect(reviewLines({ policy: 'roles', roles: ['mystery'], graceDays: 7 }, NAMES, ADMIN_ME, NOW)[0]).toContain('role mystery');
  });
});

describe('the reminder for a covered person', () => {
  it('names the last day, only for someone without a second factor who has a deadline', () => {
    expect(reminderWords({ mfa_due_by: '2026-11-03T21:00:00Z', mfa_enabled: false })).toBe(
      'Your business asks you to set up two-step sign-in by 3 Nov 2026 (you can sign in until the end of that day). After that you cannot sign in without it.',
    );
    expect(reminderWords({ mfa_due_by: '2026-11-04T05:00:00Z', mfa_enabled: false, tenant_timezone: 'America/New_York' })).toContain('by 3 Nov 2026');
    expect(reminderWords({ mfa_due_by: '2026-11-03T21:00:00Z', mfa_enabled: true })).toBeNull();
    expect(reminderWords({ mfa_enabled: false })).toBeNull();
  });

  it('says it is required now once the date has passed, and blocks the person until it is set up', () => {
    expect(reminderWords({ mfa_required: true, mfa_enabled: false })).toBe('Your business now requires two-step sign-in. Set it up to go on using the app.');
    expect(mustSetUp({ mfa_required: true, mfa_enabled: false })).toBe(true);
    expect(mustSetUp({ mfa_required: true, mfa_enabled: true })).toBe(false);
    expect(mustSetUp({ mfa_required: false, mfa_enabled: false })).toBe(false);
    expect(mustSetUp({})).toBe(false);
  });
});

describe('the recommendation to administrators', () => {
  it('shows only to a settings manager while the policy is off', () => {
    expect(recommends({ permissions: ['core.settings.manage'], two_step_policy: 'off' })).toBe(true);
    expect(recommends({ permissions: ['core.settings.manage'], two_step_policy: 'roles' })).toBe(false);
    expect(recommends({ permissions: ['core.settings.manage'], two_step_policy: 'everyone' })).toBe(false);
    expect(recommends({ permissions: [], two_step_policy: 'off' })).toBe(false);
    expect(recommends({ permissions: ['core.settings.manage'] })).toBe(false);
  });

  it('is put away for 14 days per person and comes back by itself', () => {
    const store = new Map<string, string>();
    const box = { getItem: (k: string) => store.get(k) ?? null, setItem: (k: string, v: string) => void store.set(k, v) };
    const t0 = 1_000_000;
    expect(recommendationHidden('u1', t0, box)).toBe(false);
    hideRecommendation('u1', t0, box);
    expect(recommendationHidden('u1', t0 + 13 * 86_400_000, box)).toBe(true);
    expect(recommendationHidden('u1', t0 + 15 * 86_400_000, box)).toBe(false);
    expect(recommendationHidden('u2', t0 + 1, box)).toBe(false);
  });

  it('works without storage: it simply shows', () => {
    const broken = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    };
    expect(recommendationHidden('u1', 1, broken)).toBe(false);
    expect(() => hideRecommendation('u1', 1, broken)).not.toThrow();
    expect(recommendationHidden('u1', 1, null)).toBe(false);
    expect(() => hideRecommendation('u1', 1, null)).not.toThrow();
  });
});
