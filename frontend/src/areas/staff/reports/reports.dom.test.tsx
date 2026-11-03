// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { reports } from '../../../api/reports';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { staffMenu } from '../menu';
import { Reports } from './page';

// The Reports page and its menu entry while report runs are switched off for the business (issue #764): a report holder is told
// they are off, never sent to Staff users; someone who may change the business settings gets a link to the switch; a user with
// no report permission still sees the no access message. Fabricated people only.

const HOLDER = ['lending.reports.portfolio'];
const ADMIN = ['lending.reports.portfolio', 'core.settings.manage'];

const meWith = (permissions: string[], reportsEnabled: boolean): Me =>
  ({ ...mockMe('sales'), modules: ['lending'], permissions, reports_enabled: reportsEnabled, permission_scopes: undefined }) as Me;

function shell(me: Me) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/reports'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <Reports />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const NO_ACCESS = /You do not have access/;
const OFF = 'Reports are switched off for this business.';

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('the Reports page while report runs are switched off', () => {
  it('tells a report holder they are off and who can switch them on, without sending them to Staff users', () => {
    const catalogue = vi.spyOn(reports, 'catalogue');
    shell(meWith(HOLDER, false));
    expect(screen.getByText(OFF)).toBeTruthy();
    expect(screen.getByText('Ask the owner to switch them on in Business set-up.')).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Switch them on in Business set-up' })).toBeNull();
    expect(screen.queryByText(NO_ACCESS)).toBeNull();
    expect(screen.queryByText(/Staff users/)).toBeNull();
    expect(catalogue).not.toHaveBeenCalled();
  });

  it('gives someone who may change the business settings a link to the switch', () => {
    shell(meWith(ADMIN, false));
    expect(screen.getByText(OFF)).toBeTruthy();
    const link = screen.getByRole('link', { name: 'Switch them on in Business set-up' }) as HTMLAnchorElement;
    expect(link.getAttribute('href')).toBe('/staff/setup#reports');
    expect(screen.queryByText(/Ask the owner/)).toBeNull();
    expect(screen.queryByText(NO_ACCESS)).toBeNull();
  });
});

describe('the Reports page otherwise', () => {
  it('opens the report centre for a holder while report runs are on', async () => {
    vi.spyOn(reports, 'catalogue').mockResolvedValue([]);
    vi.spyOn(reports, 'runs').mockResolvedValue([]);
    shell(meWith(HOLDER, true));
    expect(await screen.findByText(/Each report is prepared in the background/)).toBeTruthy();
    expect(screen.queryByText(OFF)).toBeNull();
    expect(screen.queryByText(NO_ACCESS)).toBeNull();
  });

  it('keeps the no access message for a user without any report permission, whether runs are on or off', () => {
    for (const on of [true, false]) {
      shell(meWith(['lending.members.read', 'core.settings.manage'], on));
      expect(screen.getByText(NO_ACCESS)).toBeTruthy();
      expect(screen.queryByText(OFF)).toBeNull();
      cleanup();
    }
  });
});

describe('the Reports entry in the menu', () => {
  const entry = (me: Me) => staffMenu(me, { sells: false }).flatMap((g) => g.links).find((l) => l.to === '/staff/reports');

  it('stays for a report holder while runs are off, marked Off', () => {
    expect(entry(meWith(HOLDER, false))).toMatchObject({ label: 'Reports', count: 'Off' });
  });

  it('has no Off mark while runs are on, and is absent without a report permission', () => {
    expect(entry(meWith(HOLDER, true))?.count).toBeUndefined();
    expect(entry(meWith(['lending.members.read'], false))).toBeUndefined();
  });
});
