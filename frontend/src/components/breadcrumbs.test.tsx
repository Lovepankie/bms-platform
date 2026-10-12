import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { router as appRouter } from '../app/router';
import { Breadcrumbs, StaffBreadcrumbs, trailFor, withExtras } from './breadcrumbs';

// The breadcrumbs of the staff area: the trail comes from the route table (a `crumb` label in each
// route's static data), the markup is a landmark with an ordered list and the current page marked.
// Static markup tests, as the other component tests are (the test setup has no DOM).

const labels: Record<string, string> = {
  '/staff': 'Home',
  '/staff/retail': 'Retail',
  '/staff/retail/stock': 'Stock',
  '/staff/lending': 'Lending',
  '/staff/lending/loans/$loanId': 'Loan',
};

describe('trailFor', () => {
  it('is the labelled routes on the way, in order, with their addresses', () => {
    expect(trailFor('/staff/retail/stock', {}, (p) => labels[p])).toEqual([
      { label: 'Home', to: '/staff' },
      { label: 'Retail', to: '/staff/retail' },
      { label: 'Stock', to: '/staff/retail/stock' },
    ]);
  });

  it('fills the route parameters into the address', () => {
    const trail = trailFor('/staff/lending/loans/$loanId', { loanId: 'a b' }, (p) => labels[p]);
    expect(trail.map((c) => c.label)).toEqual(['Home', 'Lending', 'Loan']);
    expect(trail[2]?.to).toBe('/staff/lending/loans/a%20b');
  });

  it('skips a route without a label', () => {
    expect(trailFor('/staff/retail/stock', {}, (p) => (p === '/staff/retail/stock' ? 'Stock' : undefined))).toHaveLength(1);
  });
});

describe('withExtras', () => {
  const trail = trailFor('/staff/lending/loans/$loanId', { loanId: 'x' }, (p) => labels[p]);

  it('names the page after the entity the screen loaded', () => {
    expect(withExtras(trail, { label: 'LN-BR1-000001' }).map((c) => c.label)).toEqual(['Home', 'Lending', 'LN-BR1-000001']);
  });

  it('adds a last crumb for a detail view and makes the screen a way back when it can go back', () => {
    const back = () => undefined;
    const items = withExtras(trail, { tail: { label: 'Sale S-1', onBack: back } });
    expect(items.map((c) => c.label)).toEqual(['Home', 'Lending', 'Loan', 'Sale S-1']);
    expect(items[2]?.onSelect).toBe(back);
    expect(items[2]?.to).toBeUndefined();
    expect(withExtras(trail, { tail: { label: 'By location' } })[2]?.to).toBe('/staff/lending/loans/x');
  });

  it('does not change the trail it was given', () => {
    withExtras(trail, { label: 'Other' });
    expect(trail[2]?.label).toBe('Loan');
  });
});

const memory = (path: string) => createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [path] }) });

describe('Breadcrumbs', () => {
  const html = renderToStaticMarkup(
    <RouterContextProvider router={memory('/sign-in')}>
      <Breadcrumbs items={[{ label: 'Home', to: '/staff' }, { label: 'Retail', to: '/staff/retail' }, { label: 'A very long label that the stylesheet cuts off with an ellipsis' }]} />
    </RouterContextProvider>,
  );

  it('is a Breadcrumb landmark with an ordered list', () => {
    expect(html).toContain('<nav aria-label="Breadcrumb"');
    expect(html).toContain('<ol>');
  });

  it('marks only the last item as the current page and does not link it', () => {
    expect(html.match(/aria-current="page"/g)).toHaveLength(1);
    expect(html).toMatch(/<span aria-current="page" title="A very long label[^"]*">A very long label/);
    expect(html).toContain('href="/staff/retail"');
  });

  it('keeps the full label as the title and gives the phone its Back to the parent', () => {
    expect(html).toContain('title="Retail"');
    expect(html).toContain('<li class="crumb-parent">');
    expect(html).toContain('<span class="crumb-back">Back to </span>');
  });

  it('draws nothing for a trail of one', () => {
    expect(renderToStaticMarkup(<Breadcrumbs items={[{ label: 'Home' }]} />)).toBe('');
  });
});

describe('the route table', () => {
  const routes = Object.entries(appRouter.routesById as unknown as Record<string, { options: { staticData?: { crumb?: string } } }>)
    .filter(([path]) => path.startsWith('/staff'));

  it('gives every staff screen a label, so every screen has a trail', () => {
    expect(routes.length).toBeGreaterThan(40);
    for (const [path, route] of routes) {
      // The index routes (/staff/, /staff/retail/, /staff/lending/) are drawn under their parent's label.
      if (path.endsWith('/') ) continue;
      expect(route.options.staticData?.crumb, path).toBeTruthy();
    }
  });

  it('writes the trail of the stock screen as Home, Retail, Stock', async () => {
    const router = memory('/staff/retail/stock');
    await router.load();
    const out = renderToStaticMarkup(<RouterContextProvider router={router}><StaffBreadcrumbs /></RouterContextProvider>);
    const names = [...out.matchAll(/<span class="crumb-text">([^<]*)<\/span>|aria-current="page"[^>]*>([^<]*)</g)].map((m) => m[1] ?? m[2]);
    expect(names).toEqual(['Home', 'Retail', 'Stock']);
  });

  it('writes a detail screen as its parent and the generic label until the entity is loaded', async () => {
    const router = memory('/staff/lending/loans/00000000-0000-4000-8000-0000000000a1');
    await router.load();
    const out = renderToStaticMarkup(<RouterContextProvider router={router}><StaffBreadcrumbs /></RouterContextProvider>);
    expect(out).toContain('aria-current="page" title="Loan">Loan<');
    expect(out).toContain('href="/staff/lending"');
  });

  it('draws no trail on the staff home', async () => {
    const router = memory('/staff');
    await router.load();
    expect(renderToStaticMarkup(<RouterContextProvider router={router}><StaffBreadcrumbs /></RouterContextProvider>)).toBe('');
  });
});
