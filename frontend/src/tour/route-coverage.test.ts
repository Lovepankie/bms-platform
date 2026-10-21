import { describe, expect, it } from 'vitest';
import { router } from '../app/router';
import { TOURS } from './tours';

// The rule of ADR-049: every staff route has a page tour. tour_guard.py (CI) stops a pull request that adds a route without touching
// frontend/src/tour/; this test is the other half, so the table of routes and the table of tours cannot drift apart over time.
// A route that really needs none is listed here with its reason, which a reviewer reads.
const NO_TOUR: Record<string, string> = {};

/** A tour's `page` matches a route path segment by segment, a `$name` segment matching any one segment (model.ts, pageTourFor). */
const covers = (page: string, route: string) => {
  const want = page.split('/');
  const have = route.split('/');
  return want.length === have.length && want.every((part, i) => part.startsWith('$') || part === have[i]);
};

describe('every staff route has a page tour (ADR-049)', () => {
  const routes = Object.keys(router.routesByPath).filter((p) => p === '/staff' || p.startsWith('/staff/'));

  it('has a route table to check', () => {
    expect(routes.length).toBeGreaterThan(90);
  });

  it('gives every staff route a tour', () => {
    const missing = routes.filter((route) => !(route in NO_TOUR) && !TOURS.some((t) => t.page !== undefined && covers(t.page, route)));
    expect(missing).toEqual([]);
  });

  it('keeps no exemption for a route that has a tour or is gone', () => {
    for (const route of Object.keys(NO_TOUR)) {
      expect(routes, route).toContain(route);
      expect(TOURS.some((t) => t.page !== undefined && covers(t.page, route)), route).toBe(false);
    }
  });

  it('finds, for every route, the tour whose page is that route exactly (a fixed segment before its $ sibling)', () => {
    for (const route of routes) {
      const first = TOURS.find((t) => t.page !== undefined && covers(t.page, route));
      expect(first?.page, route).toBe(route);
    }
  });

  it('points every page tour at a route that exists', () => {
    // The public pages (find your sign-in page, issue #383) have a tour too, outside /staff.
    const every = Object.keys(router.routesByPath);
    for (const tour of TOURS.filter((t) => t.page !== undefined)) {
      expect(every.some((route) => covers(tour.page as string, route)), `${tour.id}: ${tour.page}`).toBe(true);
    }
  });
});
