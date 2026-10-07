import { describe, expect, it } from 'vitest';
import type { Me } from '../api/client';
import {
  FEATURES,
  type Tour,
  type TourStep,
  announcement,
  cardSide,
  keyAction,
  mainTourFor,
  modulesOf,
  nextIndex,
  pageTourFor,
  samePage,
  shouldAutoStart,
  stepVisible,
  stepsFor,
  swipeAction,
  trapFocus,
} from './model';
import { TOURS, adminWelcome, retailSales, staffWelcome } from './tours';

// The tour framework (issue #19): who sees which step, when a tour starts by itself, skipping a
// missing anchor, and the keyboard and touch rules. All users are fabricated.

const me = (permissions: string[], extra: Partial<Me> = {}): Me => ({
  user_id: '00000000-0000-4000-8000-0000000000a1',
  full_name: 'Test User 01',
  permissions,
  all_branches: false,
  branches: [],
  ...extra,
});

const ADMIN = me([
  'core.settings.manage', 'core.settings.read', 'core.users.manage', 'core.users.read', 'core.branches.read',
  'core.approvals.read', 'lending.members.read', 'retail.sale.create', 'retail.stock.read', 'retail.purchase.create',
]);
const SELLER = me(['retail.sale.create', 'retail.sale.read', 'retail.stock.read', 'retail.usage.report']);
const CASHIER = me(['lending.members.read', 'lending.repayments.create']);

const step = (over: Partial<TourStep>): TourStep => ({ id: 's', title: 'T', body: 'B', ...over });

describe('step filtering', () => {
  it('needs every permission in needs, one in needsAny, and none in unless', () => {
    expect(stepVisible(step({ needs: ['retail.sale.create', 'retail.stock.read'] }), SELLER)).toBe(true);
    expect(stepVisible(step({ needs: ['retail.sale.create', 'retail.purchase.create'] }), SELLER)).toBe(false);
    expect(stepVisible(step({ needsAny: ['core.users.manage', 'retail.stock.read'] }), SELLER)).toBe(true);
    expect(stepVisible(step({ needsAny: ['core.users.manage'] }), SELLER)).toBe(false);
    expect(stepVisible(step({ unless: ['retail.purchase.create'] }), SELLER)).toBe(true);
    expect(stepVisible(step({ unless: ['retail.purchase.create'] }), ADMIN)).toBe(false);
  });

  it('follows the modules the tenant has', () => {
    expect(modulesOf(SELLER)).toEqual(['retail']);
    expect(modulesOf(CASHIER)).toEqual(['lending']);
    expect(modulesOf(ADMIN)).toEqual(['retail', 'lending']);
    expect(stepVisible(step({ module: 'lending' }), SELLER)).toBe(false);
    expect(stepVisible(step({ module: 'retail' }), SELLER)).toBe(true);
    // When /me lists modules, a module the tenant switched off hides its steps.
    expect(modulesOf({ ...ADMIN, modules: ['lending'] } as Me)).toEqual(['lending']);
  });

  it('hides a step whose feature is not built, and shows it once its flag is on', () => {
    const billing = step({ feature: 'billing' });
    expect(stepVisible(billing, ADMIN)).toBe(false);
    expect(stepVisible(billing, ADMIN, { ...FEATURES, billing: true })).toBe(true);
  });

  it('walks a seller through only the screens a seller can use', () => {
    const ids = stepsFor(retailSales, SELLER).map((s) => s.id);
    expect(ids).toContain('sale');
    expect(ids).toContain('stock');
    expect(ids).toContain('restock-ask');
    expect(ids).not.toContain('restock');
    const withRestock = stepsFor(retailSales, me([...(SELLER.permissions ?? []), 'retail.purchase.create'])).map((s) => s.id);
    expect(withRestock).toContain('restock');
    expect(withRestock).not.toContain('restock-ask');
  });

  it('keeps the admin tour free of steps for features that are not built yet', () => {
    const ids = stepsFor(adminWelcome, ADMIN).map((s) => s.id);
    for (const hidden of ['staff', 'billing', 'trial', 'pay', 'agent']) expect(ids).not.toContain(hidden);
    for (const shown of ['welcome', 'logo', 'colour', 'modules', 'branch', 'retail', 'lending', 'approvals', 'help']) {
      expect(ids).toContain(shown);
    }
  });

  it('registers a step for each pending feature, so its slice only switches the flag on', () => {
    const flagged = new Set(TOURS.flatMap((t) => t.steps).map((s) => s.feature).filter(Boolean));
    for (const feature of Object.keys(FEATURES)) expect(flagged.has(feature as keyof typeof FEATURES)).toBe(true);
    expect(Object.values(FEATURES).every((on) => on === false)).toBe(true);
  });
});

describe('choosing the tour', () => {
  it('gives each role its own first-run tour', () => {
    expect(mainTourFor(TOURS, ADMIN)?.id).toBe('admin-welcome');
    expect(mainTourFor(TOURS, SELLER)?.id).toBe('retail-sales');
    expect(mainTourFor(TOURS, CASHIER)?.id).toBe('staff-welcome');
  });

  it('a cashier is never walked to retail or admin screens', () => {
    const ids = stepsFor(staffWelcome, CASHIER).map((s) => s.id);
    expect(ids).toEqual(['welcome', 'branch', 'members', 'help']);
  });

  it('finds the page tour of the page the user is on, if the user may see it', () => {
    expect(pageTourFor(TOURS, SELLER, '/staff/retail/sale')?.id).toBe('page-sale');
    expect(pageTourFor(TOURS, SELLER, '/staff/retail/sale/')?.id).toBe('page-sale');
    expect(pageTourFor(TOURS, CASHIER, '/staff/retail/sale')).toBeUndefined();
    expect(pageTourFor(TOURS, ADMIN, '/staff/setup')?.id).toBe('page-setup');
    expect(pageTourFor(TOURS, ADMIN, '/staff/approvals')).toBeUndefined();
    // A $name segment stands for one segment: any loan, but not the list or a deeper page.
    const OFFICER = me(['lending.loans.read']);
    expect(pageTourFor(TOURS, OFFICER, '/staff/lending/loans/00000000-0000-4000-8000-0000000000a1')?.id).toBe('page-loan');
    expect(pageTourFor(TOURS, OFFICER, '/staff/lending')?.id).toBe('page-loans');
    expect(pageTourFor(TOURS, OFFICER, '/staff/lending/loans/x/y')).toBeUndefined();
  });

  it('has unique tour ids that the server accepts, and unique step ids per tour', () => {
    const ids = TOURS.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const tour of TOURS) {
      expect(tour.id).toMatch(/^[a-z0-9]+(-[a-z0-9]+)*$/);
      const steps = tour.steps.map((s) => s.id);
      expect(new Set(steps).size, tour.id).toBe(steps.length);
    }
  });
});

describe('first run and replay', () => {
  const tour: Tour = { ...adminWelcome, version: 2 };

  it('starts until finished in this version, and again when a new version adds steps', () => {
    expect(shouldAutoStart(tour, undefined)).toBe(true);
    expect(shouldAutoStart(tour, {})).toBe(true);
    expect(shouldAutoStart(tour, { [tour.id]: { status: 'completed', version: 2 } })).toBe(false);
    expect(shouldAutoStart(tour, { [tour.id]: { status: 'completed', version: 1 } })).toBe(true);
  });

  it('never starts again by itself after Do not show this again', () => {
    expect(shouldAutoStart(tour, { [tour.id]: { status: 'dismissed', version: 1 } })).toBe(false);
  });

  it('never starts a page tour by itself', () => {
    expect(shouldAutoStart({ ...tour, autoStart: false }, {})).toBe(false);
  });
});

describe('a missing anchor', () => {
  const steps = [step({ id: 'a' }), step({ id: 'b', target: 'gone', optional: true }), step({ id: 'c', target: 'here' })];
  const present = (s: TourStep) => s.target !== 'gone';

  it('skips an optional step whose target is not on the page, both ways', () => {
    expect(nextIndex(steps, 0, 1, present)).toBe(2);
    expect(nextIndex(steps, 2, -1, present)).toBe(0);
  });

  it('keeps a required step, which then shows in the middle of the page', () => {
    const required = [step({ id: 'a' }), step({ id: 'b', target: 'gone' })];
    expect(nextIndex(required, 0, 1, present)).toBe(1);
  });

  it('says when there is nothing more in that direction', () => {
    expect(nextIndex(steps, 2, 1, present)).toBe(-1);
    expect(nextIndex([step({ id: 'a' }), step({ id: 'b', target: 'gone', optional: true })], 0, 1, present)).toBe(-1);
  });

  it('treats /staff and /staff/ as the same page', () => {
    expect(samePage('/staff/', '/staff')).toBe(true);
    expect(samePage('/staff/retail', '/staff')).toBe(false);
  });
});

describe('keyboard, touch and screen readers', () => {
  it('keeps Tab inside the card, wrapping both ways', () => {
    expect(trapFocus(3, 2, false)).toBe(0);
    expect(trapFocus(3, 0, true)).toBe(2);
    expect(trapFocus(3, 1, false)).toBe(2);
    expect(trapFocus(3, -1, false)).toBe(0);
    expect(trapFocus(3, -1, true)).toBe(2);
    expect(trapFocus(0, -1, false)).toBe(-1);
  });

  it('maps Escape and the arrow keys', () => {
    expect(keyAction('Escape')).toBe('close');
    expect(keyAction('ArrowRight')).toBe('next');
    expect(keyAction('ArrowLeft')).toBe('back');
    expect(keyAction('Enter')).toBeNull();
  });

  it('turns a clear sideways swipe into next or back, and ignores a scroll', () => {
    expect(swipeAction(-120, 10)).toBe('next');
    expect(swipeAction(120, 10)).toBe('back');
    expect(swipeAction(-30, 0)).toBeNull();
    expect(swipeAction(-80, 120)).toBeNull();
  });

  it('puts the card on the side away from the target', () => {
    expect(cardSide('auto', { top: 600, height: 40 }, 800)).toBe('top');
    expect(cardSide('auto', { top: 100, height: 40 }, 800)).toBe('bottom');
    expect(cardSide(undefined, null, 800)).toBe('bottom');
    expect(cardSide('top', { top: 100, height: 40 }, 800)).toBe('top');
  });

  it('announces the position, title and text of each step', () => {
    expect(announcement(1, 5, step({ title: 'Your branch', body: 'Pick it.' }))).toBe('Step 2 of 5. Your branch. Pick it.');
  });
});

describe('copy', () => {
  it('uses no dashes, keeps sentences short, and every step has a title and a body', () => {
    for (const tour of TOURS) {
      for (const s of tour.steps) {
        expect(s.title.length, `${tour.id}/${s.id}`).toBeGreaterThan(0);
        expect(s.body.length, `${tour.id}/${s.id}`).toBeGreaterThan(0);
        expect(`${s.title} ${s.body}`).not.toMatch(/[\u2013\u2014]/);
        for (const sentence of s.body.split(/[.!?]\s/)) {
          expect(sentence.split(/\s+/).length, `${tour.id}/${s.id}: ${sentence}`).toBeLessThanOrEqual(22);
        }
      }
    }
  });
});
