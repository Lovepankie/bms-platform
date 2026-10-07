import type { Me } from '../api/client';
import { showRetail } from '../areas/staff/retail/permissions';

// The guided tour framework (issue #19, ADR-025). A tour is data: an id, a version and a list of
// steps, each anchored to an element by a stable data-tour attribute (never a CSS class). Who sees
// a step is decided here, from the same /me permissions the menus use, the modules the tenant has,
// and the availability flags of features that are not built yet. The spotlight component only
// draws what this module hands it.

export type Module = 'retail' | 'lending';

/**
 * Features a step may wait for. A step behind a flag that is false is registered but never shown,
 * so the slice that builds the feature switches it on here (and bumps the tour version) and
 * nothing else changes. See docs/specs/self-onboarding-and-subscriptions.md for #90 to #92.
 */
export const FEATURES = {
  /** The tenant billing screen: plan, period, next due date (issue #90). */
  billing: false,
  /** The I have paid claim and the payment instructions (issue #90). */
  payments: false,
  /** The trial clock and its reminders (issue #91). */
  trial: false,
  /** Agents and commissions (issue #92). */
  agents: false,
  /** The staff screen: invite a person, give them roles (no issue yet; the API exists). */
  staffAdmin: false,
  /** The insights pages: trends from the data already kept (#156, not merged yet). */
  insights: false,
} satisfies Record<string, boolean>;

export type Feature = keyof typeof FEATURES;

export type Placement = 'auto' | 'top' | 'bottom';

export interface TourStep {
  /** Unique inside its tour. */
  id: string;
  /** The data-tour id of the element to light up; none means a card in the middle of the page. */
  target?: string;
  /** The page the target lives on; the tour goes there first. None means the current page. */
  route?: string;
  title: string;
  body: string;
  /** Where the card sits; auto puts it on the side of the screen away from the target. */
  placement?: Placement;
  /** Every one of these permissions is needed to see the step. */
  needs?: readonly string[];
  /** At least one of these permissions is needed to see the step. */
  needsAny?: readonly string[];
  /** Hidden from a user who holds any of these (the step for those who cannot do it themselves). */
  unless?: readonly string[];
  /** The tenant must have this module. */
  module?: Module;
  /** The step waits for this feature to be built. */
  feature?: Feature;
  /** Skip the step quietly when its target is not on the page (a permission or a setting hides it). */
  optional?: boolean;
}

export interface Tour {
  id: string;
  /** Bump when steps change in a way a user who finished it should see; a dismissal stays. */
  version: number;
  title: string;
  /** Who the tour is for. */
  audience: (me: Me) => boolean;
  /** Starts by itself on first sign-in; false for page tours, which only start from Help. */
  autoStart: boolean;
  /** For a page tour: the page it explains. A `$name` segment matches any one segment. */
  page?: string;
  steps: readonly TourStep[];
}

export type TourStatus = 'completed' | 'dismissed';
export type Progress = Record<string, { status?: TourStatus; version?: number } | undefined>;

/** The modules the tenant has. /me has no module list yet, so the permissions imply it. */
export function modulesOf(me: Me & { modules?: string[] }): Module[] {
  const held = me.permissions ?? [];
  const modules: Module[] = [];
  if (showRetail(me)) modules.push('retail');
  if ((!me.modules || me.modules.includes('lending')) && held.some((p) => p.startsWith('lending.'))) {
    modules.push('lending');
  }
  return modules;
}

/** Whether this user, on this tenant, today, may see the step. */
export function stepVisible(step: TourStep, me: Me, features: Record<Feature, boolean> = FEATURES): boolean {
  const held = me.permissions ?? [];
  if (step.feature && !features[step.feature]) return false;
  if (step.module && !modulesOf(me).includes(step.module)) return false;
  if (step.needs && !step.needs.every((p) => held.includes(p))) return false;
  if (step.needsAny && !step.needsAny.some((p) => held.includes(p))) return false;
  if (step.unless && step.unless.some((p) => held.includes(p))) return false;
  return true;
}

/** The steps of a tour this user sees, in order. */
export function stepsFor(tour: Tour, me: Me, features: Record<Feature, boolean> = FEATURES): TourStep[] {
  return tour.steps.filter((step) => stepVisible(step, me, features));
}

/** The first-run tour for this user: the first auto tour of the registry whose audience matches. */
export function mainTourFor(tours: readonly Tour[], me: Me): Tour | undefined {
  return tours.find((tour) => tour.autoStart && tour.audience(me) && stepsFor(tour, me).length > 0);
}

/** The page tour for the page the user is on, if this user may see it. */
export function pageTourFor(tours: readonly Tour[], me: Me, pathname: string): Tour | undefined {
  const path = (pathname.replace(/\/+$/, '') || '/').split('/');
  const matches = (page: string) => {
    const want = page.split('/');
    return want.length === path.length && want.every((part, i) => part.startsWith('$') || part === path[i]);
  };
  return tours.find((tour) => tour.page !== undefined && matches(tour.page) && tour.audience(me) && stepsFor(tour, me).length > 0);
}

/**
 * Whether the tour starts by itself: not dismissed (a dismissal is for good), and not completed in
 * this version or a later one (a new version with new steps shows once more).
 */
export function shouldAutoStart(tour: Tour, progress: Progress | undefined): boolean {
  if (!tour.autoStart) return false;
  const seen = progress?.[tour.id];
  if (!seen?.status) return true;
  if (seen.status === 'dismissed') return false;
  return (seen.version ?? 0) < tour.version;
}

/**
 * The next step to show when going in a direction, skipping optional steps whose target is known
 * to be missing (`present` answers for the page the user is on; a step on another page counts as
 * present until the tour gets there). Returns -1 past either end.
 */
export function nextIndex(
  steps: readonly TourStep[],
  from: number,
  direction: 1 | -1,
  present: (step: TourStep) => boolean,
): number {
  for (let i = from + direction; i >= 0 && i < steps.length; i += direction) {
    const step = steps[i] as TourStep;
    if (!step.optional || !step.target || present(step)) return i;
  }
  return -1;
}

/** The path without a trailing slash, so /staff/ and /staff are the same page. */
export function samePage(pathname: string, route: string): boolean {
  const clean = (p: string) => p.replace(/\/+$/, '') || '/';
  return clean(pathname) === clean(route);
}

/** Which side of the screen the card sits on: away from the target, so it never covers it. */
export function cardSide(placement: Placement | undefined, target: { top: number; height: number } | null, viewport: number): 'top' | 'bottom' {
  if (placement === 'top' || placement === 'bottom') return placement;
  if (!target) return 'bottom';
  return target.top + target.height / 2 > viewport / 2 ? 'top' : 'bottom';
}

/** Tab and Shift+Tab inside the card: from the last control to the first and back (focus trap). */
export function trapFocus(count: number, current: number, backwards: boolean): number {
  if (count === 0) return -1;
  if (current < 0) return backwards ? count - 1 : 0;
  if (backwards) return current === 0 ? count - 1 : current - 1;
  return current === count - 1 ? 0 : current + 1;
}

/** What a key does while a tour is open. */
export function keyAction(key: string): 'next' | 'back' | 'close' | null {
  if (key === 'Escape') return 'close';
  if (key === 'ArrowRight') return 'next';
  if (key === 'ArrowLeft') return 'back';
  return null;
}

/** What a horizontal swipe on the card does: left goes on, right goes back. */
export function swipeAction(dx: number, dy: number): 'next' | 'back' | null {
  if (Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy) * 1.5) return null;
  return dx < 0 ? 'next' : 'back';
}

/** The words a screen reader hears when a step opens. */
export function announcement(index: number, total: number, step: TourStep): string {
  return `Step ${index + 1} of ${total}. ${step.title}. ${step.body}`;
}
