import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import type { ReactElement } from 'react';
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me, TenantSettings } from '../api/client';
import { router as appRouter } from '../app/router';
import { StaffContext } from '../areas/staff/context';
import { RefreshButton } from '../app/refresh-button';
import { StaffBar } from '../areas/staff/route';
import { type Tour, stepsFor } from './model';

// The harness the page-tour tests share (issue #337): render the screen a tour step points at, under the staff bar and a
// router as the staff layout draws them, as the user the tour is written for, and prove the step's data-tour anchor is in
// the static markup. A screen that shows its anchor only after a request has answered cannot be a target: put the anchor on
// markup the screen draws before any data arrives (its heading, its filters, its primary link), or seed the query cache.

const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });

const settings = { version: 1, display_name: 'Test Trading Co', display_name_set: true, setup_dismissed: false } as TenantSettings;

export type Seed = (client: QueryClient, me: Me) => void;

/** The static markup of `screen` for `me`, in the staff context; `seed` puts fabricated answers in the query cache first. */
export function renderStaff(me: Me, screen: ReactElement, seed?: Seed): string {
  const client = new QueryClient();
  client.setQueryData(['settings'], settings);
  seed?.(client, me);
  const branch = me.branches?.[0]?.id ?? null;
  return renderToString(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch }}>
          <RefreshButton />
          <StaffBar me={me} branch={me.default_branch_id ?? null} onBranch={() => undefined} onSignOut={() => undefined} />
          {screen}
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

/**
 * One test per tour of a slice: every user it is written for sees more than one step, and every step's anchor is on the page
 * the step points at. `screenFor` maps a tour's `page` (or a step's `route`) to the screen drawn for it.
 */
export function describeTours(name: string, tours: readonly Tour[], audience: Record<string, Me[]>, screenFor: (path: string) => ReactElement, seed?: Seed): void {
  describe(`${name}: every tour target exists on its screen`, () => {
    it('has an audience for every tour and a page that is routed', () => {
      expect(Object.keys(audience).sort()).toEqual(tours.map((t) => t.id).sort());
      for (const tour of tours) {
        expect(tour.page, tour.id).toBeTruthy();
        expect(tour.autoStart, `${tour.id} is a page tour and starts from Help`).toBe(false);
        expect(Object.keys(appRouter.routesByPath), `${tour.id}: ${tour.page} is a route`).toContain(tour.page);
      }
    });
    for (const tour of tours) {
      it(tour.id, () => {
        for (const user of audience[tour.id] ?? []) {
          expect(tour.audience(user), `${tour.id} is for ${user.full_name}`).toBe(true);
          const steps = stepsFor(tour, user);
          expect(steps.length).toBeGreaterThan(1);
          for (const step of steps) {
            if (!step.target) continue;
            const path = step.route ?? (tour.page as string);
            expect(renderStaff(user, screenFor(path), seed), `${tour.id}/${step.id}: data-tour="${step.target}" on ${path}`).toContain(`data-tour="${step.target}"`);
          }
        }
      });
    }
  });
}
