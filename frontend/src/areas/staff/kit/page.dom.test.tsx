// @vitest-environment jsdom
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { KitPage } from './page';

// The component kit page (#520): every kit control for an administrator, a short notice for anyone else.

afterEach(cleanup);

const person = (role: string): Me => ({ user_id: '00000000-0000-4000-8000-0000000000f1', full_name: 'Test Person 01', roles: [{ role_key: role }] as Me['roles'] });

function show(me: Me) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/kit'] }) });
  render(
    <RouterContextProvider router={router}>
      <StaffContext.Provider value={{ me, branch: null }}>
        <KitPage />
      </StaffContext.Provider>
    </RouterContextProvider>,
  );
}

describe('the component kit page', () => {
  it('shows an administrator one example of every kit control', () => {
    show(person('tenant_admin'));
    const sections = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
    expect(sections).toEqual(expect.arrayContaining(['Segmented', 'Stepper', 'Field', 'MoneyInput', 'ListRow and Card', 'Sheet', 'EmptyState', 'Skeleton', 'SuccessScreen']));
    expect(screen.getByRole('radiogroup', { name: 'Show' })).toBeTruthy();
    expect(screen.getByLabelText('Quantity (piece)')).toBeTruthy();
    expect(screen.getByLabelText('Amount paid in UGX').getAttribute('inputmode')).toBe('numeric');
    expect(screen.getByText('UGX 57,500')).toBeTruthy();
  });

  it('shows anyone else only that the page is for administrators', () => {
    show(person('retail_seller'));
    expect(screen.getByText('This page is for administrators.')).toBeTruthy();
    expect(screen.queryByRole('radiogroup')).toBeNull();
  });
});
