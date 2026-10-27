// @vitest-environment jsdom
import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { retailAnalytics } from '../../../api/retail-analytics';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { NewBadge, restartBlinkClock } from '../../../app/ui/NewBadge';
import { TourProvider } from '../../../tour/host';
import { StaffContext } from '../context';

// The help page's entry (issue #603): How do I?, or Ask BMS while the business has it on, just after Home on the More page, in the
// side menu and in feature search, with a New pill on the entry and a dot on the More tab. Both blink until the person opens
// /staff/help once, then stay gone on this device; under reduced motion they stand still, and blocked storage breaks nothing.
// Round 2: an Ask button in the top bar of every staff screen opens /staff/help with the cursor in the question box, and each
// article carries the Ask BMS box at its top.
// The server is a mock and every person is fabricated.

const ask = vi.hoisted(() => ({ status: vi.fn(), ask: vi.fn() }));

vi.mock('../../../api/help', async (original) => {
  const real = await original<typeof import('../../../api/help')>();
  return { ...real, askBms: ask };
});

const { ArticleView, HowDoIPage } = await import('./page');
const { MorePage } = await import('../tab-pages');
const { TabBar } = await import('../tabs');
const { StaffBar, StaffSideNav } = await import('../route');
const { articlesFor } = await import('../../../help/corpus');

const TOURS_OFF = { 'tours-off': { status: 'dismissed', version: 1 } };
const admin = { ...mockMe('admin'), tours: TOURS_OFF } as unknown as Me;

/** A window `px` wide, which asks for reduced motion when `still`. */
function screenIs(px: number, still = false) {
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      matches: query.includes('reduced-motion') ? still : px <= Number(/max-width: (\d+)px/.exec(query)?.[1] ?? Infinity),
      media: query,
      addEventListener: () => undefined,
      removeEventListener: () => undefined,
    })),
  );
}

function show(children: ReactNode, path = '/staff/more', me: Me = admin) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [path] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
  return { router, ...render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? null, signOut: () => undefined }}>
          <TourProvider me={me} saveProgress={false}>
            {children}
          </TourProvider>
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  ) };
}

const bar = (me: Me = admin) => <StaffBar me={me} branch={null} onBranch={() => undefined} onSignOut={() => undefined} />;
const askOrHowDoI = /^(Ask BMS|How do I\?)(, New)?$/;

const moreList = () => document.querySelector('[data-tour="more-list"] .row-list') as HTMLElement;
const rowNames = () => within(moreList()).getAllByRole('link').map((a) => a.querySelector('.list-row-title')?.firstChild?.textContent);
const pill = () => document.querySelector('.new-badge');
const dot = () => screen.queryByTestId('new-dot');

beforeEach(() => {
  screenIs(390);
  localStorage.clear();
  ask.status.mockClear();
  restartBlinkClock();
  ask.status.mockResolvedValue({ enabled: false, revision: 'r' });
  vi.spyOn(retailAnalytics, 'home').mockResolvedValue({ low_stock: 0 } as never);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('the help entry (issue #603)', () => {
  it('is How do I? just after Home on More while Ask BMS is off, with a blinking New pill, and a New dot on the More tab', async () => {
    show(
      <>
        <MorePage />
        <TabBar me={admin} />
      </>,
    );
    await waitFor(() => expect(ask.status).toHaveBeenCalled());
    expect(rowNames().slice(0, 2)).toEqual(['Home', 'How do I?']);
    expect(screen.getByRole('link', { name: 'How do I?, New' }).getAttribute('href')).toBe('/staff/help');
    expect(pill()?.className).toBe('new-badge new-blink');
    expect(dot()?.className).toBe('new-dot new-blink');
    expect(screen.getByRole('link', { name: 'More, New' })).toBeTruthy();
  });

  it('is named Ask BMS where the business has Ask BMS on, on More and in the side menu', async () => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    screenIs(1280);
    show(
      <>
        <MorePage />
        <StaffSideNav me={admin} />
      </>,
    );
    await waitFor(() => expect(rowNames().slice(0, 2)).toEqual(['Home', 'Ask BMS']));
    const menu = screen.getByRole('navigation', { name: 'Staff areas' });
    const entry = within(menu).getByRole('link', { name: 'Ask BMS, New' });
    expect(entry.getAttribute('href')).toBe('/staff/help');
    expect(within(menu).getAllByRole('link').indexOf(entry)).toBe(1);
  });

  it.each([
    [false, 'help', 'How do I?'],
    [false, 'ask', 'How do I?'],
    [true, 'help', 'Ask BMS'],
    [true, 'ask', 'Ask BMS'],
  ])('is found in feature search (Ask BMS on: %s) by typing %s', async (on, word, label) => {
    ask.status.mockResolvedValue({ enabled: on, revision: 'r' });
    show(<MorePage />);
    await waitFor(() => expect(rowNames()[1]).toBe(label));
    fireEvent.click(screen.getByRole('button', { name: 'Search for what you want to do' }));
    const box = (await screen.findByRole('combobox', { name: 'What do you want to do?' })) as HTMLInputElement;
    fireEvent.change(box, { target: { value: word } });
    await waitFor(() => expect(screen.getAllByRole('option').map((o) => o.querySelector('.cb-text')?.firstChild?.textContent)).toContain(label));
  });

  it('goes for good once the person opens /staff/help, on More, the side menu and the More tab at once', async () => {
    screenIs(1280);
    const more = show(
      <>
        <MorePage />
        <StaffSideNav me={admin} />
        <TabBar me={admin} />
      </>,
    );
    await waitFor(() => expect(document.querySelectorAll('.new-badge')).toHaveLength(2));
    expect(dot()).not.toBeNull();
    show(<HowDoIPage />);
    await waitFor(() => expect(document.querySelectorAll('.new-badge')).toHaveLength(0));
    expect(dot()).toBeNull();
    expect(screen.getByRole('link', { name: 'More' })).toBeTruthy();
    expect(localStorage.getItem('bms.new.ask-bms.seen')).toBe('1');
    more.unmount();
    cleanup();
    show(<MorePage />);
    await waitFor(() => expect(rowNames()[1]).toBe('How do I?'));
    expect(pill()).toBeNull();
  });

  it('stands still under reduced motion: the pill and the dot show without the blink', async () => {
    screenIs(390, true);
    show(
      <>
        <MorePage />
        <TabBar me={admin} />
      </>,
    );
    await waitFor(() => expect(pill()).not.toBeNull());
    expect(pill()?.className).toBe('new-badge');
    expect(dot()?.className).toBe('new-dot');
    expect(document.querySelector('.new-blink')).toBeNull();
  });

  it('keeps showing and never breaks when storage refuses to be read or written', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage blocked');
    });
    show(
      <>
        <MorePage />
        <TabBar me={admin} />
      </>,
    );
    await waitFor(() => expect(pill()).not.toBeNull());
    show(<HowDoIPage />);
    expect(await screen.findByRole('heading', { name: 'How do I...?' })).toBeTruthy();
    expect(pill()).not.toBeNull();
    expect(dot()).not.toBeNull();
  });
});

describe('the Ask button of the top bar (issue #603, round 2)', () => {
  it.each([
    ['Sell', '/staff/retail/sale'],
    ['My stock', '/staff/retail/stock'],
    ['Loans', '/staff/lending'],
  ])('shows on %s as Ask BMS next to Search, opening /staff/help at the question box, with the New dot', async (_screen, path) => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    show(bar(), path);
    const button = await screen.findByRole('link', { name: 'Ask BMS, New' });
    expect(button.getAttribute('href')).toBe('/staff/help#ask');
    expect(button.textContent).toBe('Ask');
    expect(button.previousElementSibling?.textContent).toBe('Search');
    expect(within(button).getByTestId('new-dot').className).toBe('new-dot new-blink');
  });

  it('says How do I? while Ask BMS is off for the business, never Help, which names the top menu tours sheet', async () => {
    show(bar(), '/staff/retail/sale');
    await waitFor(() => expect(ask.status).toHaveBeenCalled());
    const button = screen.getByRole('link', { name: 'How do I?, New' });
    expect(button.textContent).toBe('How do I?');
    expect(screen.queryByRole('link', { name: /Ask BMS/ })).toBeNull();
    expect(screen.queryByRole('link', { name: /^Help/ })).toBeNull();
  });

  it('is not drawn on /staff/help itself, nor while two-step set-up keeps the header minimal', async () => {
    show(bar(), '/staff/help');
    await waitFor(() => expect(ask.status).toHaveBeenCalled());
    expect(screen.queryByRole('link', { name: askOrHowDoI })).toBeNull();
    cleanup();
    // The layout hands StaffBar the profile; a person whose two-step set-up blocks the page gets the minimal header.
    show(bar({ ...admin, mfa_required: true, mfa_enabled: false } as Me), '/staff/retail/sale');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Search' })).toBeTruthy());
    expect(screen.queryByRole('link', { name: askOrHowDoI })).toBeNull();
  });

  it('opens /staff/help#ask, where the cursor lands in the Ask BMS question box', async () => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    const { router } = show(bar(), '/staff/retail/sale');
    fireEvent.click(await screen.findByRole('link', { name: 'Ask BMS, New' }));
    await waitFor(() => expect(router.history.location.pathname).toBe('/staff/help'));
    expect(router.history.location.hash).toBe('#ask');
    cleanup();
    show(<HowDoIPage />, '/staff/help#ask');
    const box = await screen.findByLabelText(/Your question/);
    await waitFor(() => expect(document.activeElement).toBe(box));
  });

  it('puts the cursor in the article search when Ask BMS is off', async () => {
    show(<HowDoIPage />, '/staff/help#ask');
    await waitFor(() => expect(document.activeElement?.id).toBe('help-q'));
    expect(screen.queryByLabelText(/Your question/)).toBeNull();
  });

  it('puts the cursor in the article search when the phone is offline and Ask BMS cannot be asked', async () => {
    onlineManager.setOnline(false);
    try {
      show(<HowDoIPage />, '/staff/help#ask');
      await waitFor(() => expect(document.activeElement?.id).toBe('help-q'));
      expect(ask.status).not.toHaveBeenCalled();
    } finally {
      onlineManager.setOnline(true);
    }
  });

  it('loses its dot and the New in its name once the person has opened /staff/help', async () => {
    show(bar(), '/staff/retail/stock');
    const button = await screen.findByRole('link', { name: 'How do I?, New' });
    expect(within(button).queryByTestId('new-dot')).not.toBeNull();
    show(<HowDoIPage />, '/staff/help');
    await waitFor(() => expect(within(button).queryByTestId('new-dot')).toBeNull());
    expect(button.getAttribute('aria-label')).toBe('How do I?');
  });

  it('loses its dot when the person reads an article, which is part of /staff/help', async () => {
    const [article] = articlesFor(admin);
    show(bar(), '/staff/retail/stock');
    const button = await screen.findByRole('link', { name: 'How do I?, New' });
    show(<ArticleView articleId={article!.id} />, `/staff/help/${article!.id}`);
    await waitFor(() => expect(within(button).queryByTestId('new-dot')).toBeNull());
  });

  it('shows the Ask BMS box at the top of an article while Ask BMS is on, and not while it is off', async () => {
    const [article] = articlesFor(admin);
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    show(<ArticleView articleId={article!.id} />, `/staff/help/${article!.id}`);
    const box = await screen.findByRole('form', { name: 'Ask BMS' });
    expect(box.compareDocumentPosition(screen.getByRole('heading', { level: 1 })) & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy();
    cleanup();
    ask.status.mockClear();
    ask.status.mockResolvedValue({ enabled: false, revision: 'r' });
    show(<ArticleView articleId={article!.id} />, `/staff/help/${article!.id}`);
    await waitFor(() => expect(ask.status).toHaveBeenCalled());
    await screen.findByRole('heading', { level: 1 });
    expect(screen.queryByRole('form', { name: 'Ask BMS' })).toBeNull();
  });
});

describe('the blink and the Ask focus at their edges (issue #603, round 4)', () => {
  it('blinks at most five seconds per page load: a badge drawn again after that stands still', async () => {
    const now = vi.spyOn(performance, 'now').mockReturnValue(1000);
    const first = show(<NewBadge feature="ask-bms" />);
    expect(pill()?.className).toBe('new-badge new-blink');
    first.unmount();
    now.mockReturnValue(4000);
    const again = show(<NewBadge feature="ask-bms" />);
    expect(pill()?.className).toBe('new-badge new-blink');
    again.unmount();
    now.mockReturnValue(6001);
    show(<NewBadge feature="ask-bms" />);
    expect(pill()?.className).toBe('new-badge');
  });

  it('stops a badge already drawn when the page load\'s five seconds are up', async () => {
    vi.useFakeTimers();
    try {
      vi.spyOn(performance, 'now').mockReturnValue(1000);
      show(<NewBadge feature="ask-bms" />);
      expect(pill()?.className).toBe('new-badge new-blink');
      await vi.advanceTimersByTimeAsync(5000);
      expect(pill()?.className).toBe('new-badge');
    } finally {
      vi.useRealTimers();
    }
  });

  it('puts the cursor in the article search when the Ask BMS status cannot be read', async () => {
    ask.status.mockRejectedValue(new Error('server down'));
    show(<HowDoIPage />, '/staff/help#ask');
    await waitFor(() => expect(document.activeElement?.id).toBe('help-q'));
    expect(ask.status).toHaveBeenCalled();
  });

  it('puts the cursor in the article search offline, even with Ask BMS remembered as on', async () => {
    ask.status.mockResolvedValue({ enabled: true, revision: 'r' });
    show(<HowDoIPage />, '/staff/help');
    await screen.findByLabelText(/Your question/);
    cleanup();
    onlineManager.setOnline(false);
    try {
      const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/help#ask'] }) });
      const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
      client.setQueryData(['help', 'ask', 'status'], { enabled: true, revision: 'r' });
      render(
        <RouterContextProvider router={router}>
          <QueryClientProvider client={client}>
            <StaffContext.Provider value={{ me: admin, branch: null }}>
              <HowDoIPage />
            </StaffContext.Provider>
          </QueryClientProvider>
        </RouterContextProvider>,
      );
      await waitFor(() => expect(document.activeElement?.id).toBe('help-q'));
    } finally {
      onlineManager.setOnline(true);
    }
  });

  it('keeps the badge for an article that is not found or is for another role', async () => {
    show(<ArticleView articleId="no-such-article" />, '/staff/help/no-such-article');
    await screen.findByText('This article could not be found.');
    expect(localStorage.getItem('bms.new.ask-bms.seen')).toBeNull();
    cleanup();
    // A cashier opening an article only an owner may read (issue #605).
    const cashier = { ...mockMe('cashier'), tours: TOURS_OFF } as unknown as Me;
    const forbidden = articlesFor(admin).find((a) => !articlesFor(cashier).some((b) => b.id === a.id));
    expect(forbidden).toBeDefined();
    show(<ArticleView articleId={forbidden?.id ?? ''} />, `/staff/help/${forbidden?.id ?? ''}`, cashier);
    await screen.findByText(/This article is for another role/);
    expect(screen.queryByText(forbidden?.title ?? '')).toBeNull();
    expect(localStorage.getItem('bms.new.ask-bms.seen')).toBeNull();
  });
});
