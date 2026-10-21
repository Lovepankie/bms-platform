import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { renderToString } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me } from '../api/client';
import { router as appRouter } from '../app/router';
import { FindSignIn } from '../areas/onboarding/find-sign-in';
import { pageTourFor, stepsFor } from './model';
import { PUBLIC_TOURS } from './tours-public';
import { TOURS } from './tours';

// The page tours of the public pages (issue #383): the page is rendered for a visitor with no sign-in, and every step's data-tour
// anchor must be in the markup.

const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/find-sign-in'] }) });
const VISITOR: Me = {};

describe('public page tours', () => {
  it('are in the registry and start only from the page', () => {
    for (const tour of PUBLIC_TOURS) {
      expect(TOURS).toContain(tour);
      expect(tour.autoStart).toBe(false);
      expect(Object.keys(appRouter.routesByPath)).toContain(tour.page);
    }
  });

  it('is found for the visitor on its page', () => {
    expect(pageTourFor(TOURS, VISITOR, '/find-sign-in')?.id).toBe('page-find-sign-in');
  });

  it('has every anchor on the find-your-sign-in page', () => {
    const html = renderToString(
      <RouterContextProvider router={router}>
        <FindSignIn />
      </RouterContextProvider>,
    );
    for (const tour of PUBLIC_TOURS) {
      const steps = stepsFor(tour, VISITOR);
      expect(steps.length).toBeGreaterThan(1);
      for (const step of steps) expect(html, `${tour.id}/${step.id}`).toContain(`data-tour="${step.target}"`);
    }
  });
});
