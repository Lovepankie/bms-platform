// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../api/client';
import { retailAnalytics } from '../../api/retail-analytics';
import { mockMe } from '../../api/retail-mock';
import { offendingWords } from '../../app/glossary';
import { router as appRouter } from '../../app/router';
import { TourProvider } from '../../tour/host';
import { StaffContext } from './context';
import { isSearchShortcut } from './feature-search';
import { SYNONYMS } from './feature-search-state';
import { StaffBar } from './route';
import { MorePage } from './tab-pages';

// Feature search (issue #551): the Search button in the staff bar and on the More page opens a sheet with the one picker, which
// lists screens only while something is typed, as a combobox a screen reader follows; Up, Down and Enter or a tap open a screen,
// Escape closes it, and / or Ctrl+K opens it on a laptop. A seller's search never names a screen a seller cannot open, and a
// synonym typed never shows. Every name is invented.

const TOURS_OFF = { 'tours-off': { status: 'dismissed', version: 1 } };
const admin = { ...mockMe('admin'), tours: TOURS_OFF } as unknown as Me;
const seller = { ...mockMe('sales'), tours: TOURS_OFF } as unknown as Me;

function widthIs(px: number) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: px <= Number(/max-width: (\d+)px/.exec(query)?.[1] ?? Infinity),
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

function show(me: Me, children: ReactNode = <StaffBar me={me} branch={null} onBranch={() => undefined} onSignOut={() => undefined} />) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? null, signOut: () => undefined }}>
          <TourProvider me={me} saveProgress={false}>
            {children}
          </TourProvider>
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
  return { router };
}

const openButton = () => screen.getByRole('button', { name: 'Search' });
const box = () => screen.getByRole('combobox', { name: 'What do you want to do?' }) as HTMLInputElement;
const options = () => screen.queryAllByRole('option').map((o) => o.querySelector('.cb-text')?.firstChild?.textContent);

async function typeIn(text: string) {
  fireEvent.change(box(), { target: { value: text } });
  await waitFor(() => expect(box().getAttribute('aria-expanded')).toBe('true'));
  await waitFor(() => expect(screen.queryByText('Searching', { selector: '.cb-empty' })).toBeNull());
}

beforeEach(() => {
  widthIs(1280);
  vi.spyOn(retailAnalytics, 'home').mockResolvedValue({ low_stock: 0 } as never);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('feature search (issue #551)', () => {
  it('is a Search button in the staff bar that opens a dialog with the picker, focused and closed until something is typed', async () => {
    show(admin);
    expect(openButton().getAttribute('aria-haspopup')).toBe('dialog');
    expect(openButton().getAttribute('aria-keyshortcuts')).toContain('Control+K');
    expect(openButton().getAttribute('data-tour')).toBe('feature-search');
    fireEvent.click(openButton());
    const dialog = await screen.findByRole('dialog', { name: 'Find a screen' });
    await waitFor(() => expect(document.activeElement).toBe(box()));
    expect(within(dialog).getByRole('combobox')).toBe(box());
    expect(box().getAttribute('aria-expanded')).toBe('false');
    expect(box().getAttribute('aria-autocomplete')).toBe('list');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(screen.getByText(/Type a word, like sell, buy stock/)).toBeTruthy();
  });

  it('lists matching screens as options of a listbox the box controls, and says how many to a screen reader', async () => {
    show(admin);
    fireEvent.click(openButton());
    await screen.findByRole('dialog');
    await typeIn('restock');
    expect(options()[0]).toBe('Buy stock');
    const list = screen.getByRole('listbox');
    expect(box().getAttribute('aria-controls')).toBe(list.id);
    expect(list.getAttribute('aria-labelledby')).toBe('feature-search-label');
    const status = document.getElementById('feature-search-status');
    expect(status?.getAttribute('aria-live')).toBe('polite');
    expect(box().getAttribute('aria-describedby')).toBe('feature-search-status');
    expect(status?.textContent).toMatch(/^\d+ screens? found$/);
    await typeIn('zzzz');
    expect(options()).toEqual([]);
    expect(status?.textContent).toBe('Nothing matches "zzzz". Try another word, like sell, buy or owe.');
  });

  it('moves with Up and Down, announces the highlighted screen, and opens it with Enter', async () => {
    const { router } = show(admin);
    fireEvent.click(openButton());
    await screen.findByRole('dialog');
    await typeIn('count');
    expect(options()[0]).toBe('Count stock');
    fireEvent.keyDown(box(), { key: 'ArrowDown' });
    const first = screen.getAllByRole('option')[0];
    expect(first?.getAttribute('aria-selected')).toBe('true');
    expect(box().getAttribute('aria-activedescendant')).toBe(first?.id);
    fireEvent.keyDown(box(), { key: 'ArrowDown' });
    fireEvent.keyDown(box(), { key: 'ArrowUp' });
    expect(box().getAttribute('aria-activedescendant')).toBe(first?.id);
    fireEvent.keyDown(box(), { key: 'Enter' });
    await waitFor(() => expect(router.history.location.pathname).toBe('/staff/retail/stocktake'));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  });

  it('opens a screen with a tap', async () => {
    const { router } = show(admin);
    fireEvent.click(openButton());
    await screen.findByRole('dialog');
    await typeIn('move');
    fireEvent.click(screen.getByRole('option', { name: /^Move stock/ }));
    await waitFor(() => expect(router.history.location.pathname).toBe('/staff/retail/transfer'));
  });

  it('closes with one Escape, even with the list open, and gives focus back to the Search button', async () => {
    show(admin);
    openButton().focus();
    fireEvent.click(openButton());
    await screen.findByRole('dialog');
    await typeIn('sale');
    fireEvent.keyDown(box(), { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    await waitFor(() => expect(document.activeElement).toBe(openButton()));
  });

  it('opens with / outside a field and with Ctrl+K or Cmd+K anywhere, and leaves / alone in a field', async () => {
    show(admin, (
      <>
        <StaffBar me={admin} branch={null} onBranch={() => undefined} onSignOut={() => undefined} />
        <label htmlFor="other">Other field</label>
        <input id="other" />
      </>
    ));
    fireEvent.keyDown(screen.getByLabelText('Other field'), { key: '/' });
    expect(screen.queryByRole('dialog')).toBeNull();
    fireEvent.keyDown(document.body, { key: '/' });
    await screen.findByRole('dialog');
    fireEvent.keyDown(box(), { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    fireEvent.keyDown(screen.getByLabelText('Other field'), { key: 'k', ctrlKey: true });
    await screen.findByRole('dialog');
    fireEvent.keyDown(box(), { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    fireEvent.keyDown(document.body, { key: 'K', metaKey: true });
    await screen.findByRole('dialog');
  });

  it('never names a screen a seller cannot open, whatever is typed', async () => {
    show(seller);
    fireEvent.click(openButton());
    await screen.findByRole('dialog');
    for (const term of ['expense', 'profit', 'staff', 'settings', 'bank']) {
      await typeIn(term);
      expect(options(), term).toEqual([]);
    }
    await typeIn('sell');
    expect(options()).toContain('Record a sale');
  });

  it('never shows a synonym: a banned word typed finds the screen under its glossary name only', async () => {
    show(admin);
    fireEvent.click(openButton());
    await screen.findByRole('dialog');
    for (const term of ['restock', 'stocktake', 'stock take']) {
      await typeIn(term);
      expect(options().length, term).toBeGreaterThan(0);
      expect(offendingWords(screen.getByRole('dialog').textContent ?? ''), term).toEqual([]);
    }
    // The synonyms are not in the page at all.
    const shown = document.body.textContent ?? '';
    expect(SYNONYMS['/staff/retail/restock']?.filter((w) => w.length > 6 && shown.toLowerCase().includes(w))).toEqual([]);
  });

  it('shows the icon alone on a phone, still named Search, and a full width Search on the More page', async () => {
    widthIs(400);
    show(admin, (
      <>
        <StaffBar me={admin} branch={null} onBranch={() => undefined} onSignOut={() => undefined} />
        <MorePage />
      </>
    ));
    const [inBar, onMore] = screen.getAllByRole('button', { name: /^Search/ });
    expect(inBar?.querySelector('.visually-hidden')?.textContent).toBe('Search');
    expect(onMore?.textContent).toBe('Search for what you want to do');
    expect(onMore?.getAttribute('data-tour')).toBe('more-search');
    fireEvent.click(onMore as HTMLElement);
    await screen.findByRole('dialog', { name: 'Find a screen' });
    await typeIn('buy');
    expect(options()).toContain('Buy stock');
  });
});

describe('the search shortcut (issue #551)', () => {
  const key = (over: Partial<KeyboardEvent>) =>
    ({ key: '/', ctrlKey: false, metaKey: false, altKey: false, defaultPrevented: false, isComposing: false, target: document.body, ...over }) as KeyboardEvent;

  it('ignores a key typed while composing a word, and / in any editable element', () => {
    expect(isSearchShortcut(key({}))).toBe(true);
    expect(isSearchShortcut(key({ isComposing: true }))).toBe(false);
    expect(isSearchShortcut(key({ key: 'k', ctrlKey: true, isComposing: true }))).toBe(false);
    for (const value of ['', 'true', 'plaintext-only']) {
      const div = document.createElement('div');
      div.setAttribute('contenteditable', value);
      document.body.append(div);
      expect(isSearchShortcut(key({ target: div })), value).toBe(false);
      div.remove();
    }
    const off = document.createElement('div');
    off.setAttribute('contenteditable', 'false');
    document.body.append(off);
    expect(isSearchShortcut(key({ target: off }))).toBe(true);
    off.remove();
  });
});
