// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { SEARCH_DELAY_MS } from '../../../app/ui/useLiveSearch';
import { StaffContext } from '../context';
import { FeedbackForm } from '../feedback/page';
import { HowDoILink } from './how-do-i';
import { ArticleView, HowDoIPage } from './page';

// How do I...? (issue #598, ADR-095): the page groups the articles by job and shows only what the person may do, the search box
// narrows them as the person types, Ask us carries the search to Feedback, which starts as a question with it typed, a screen's
// How do I? link opens its article, an article shows its steps with their captioned pictures, and all of it works with no network.
// Every person is fabricated.

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  // The search keeps its words in the address (?q=), which jsdom keeps from one test to the next.
  window.history.replaceState({}, '', '/');
});

const shopAdmin = mockMe('admin') as Me;
const owner = { ...shopAdmin, permissions: [...(shopAdmin.permissions ?? []), 'core.users.read', 'core.users.manage', 'core.branches.read', 'core.branches.manage'] } as Me;
const seller = mockMe('sales') as Me;

function show(me: Me, node: ReactNode, at = '/staff/help') {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: [at] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? null }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

const headings = () => screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent);
const titles = () => screen.queryAllByRole('link').map((l) => l.textContent ?? '');

function type(text: string) {
  fireEvent.change(screen.getByLabelText('What do you want to do?'), { target: { value: text } });
  act(() => {
    vi.advanceTimersByTime(SEARCH_DELAY_MS + 10);
  });
}

describe('How do I...?', () => {
  it('groups the answers by job, in the order of the jobs', () => {
    show(owner, <HowDoIPage />);
    expect(headings()).toEqual(['Sell', 'Buy stock', 'Move stock', 'Count stock', 'Items and categories', 'Branches and stores', 'Users and invitations', 'Money']);
    expect(titles()).toContain('How do I add a shop or a store?');
  });

  it('shows a seller only the answers for what a seller does', () => {
    show(seller, <HowDoIPage />);
    expect(headings()).not.toContain('Buy stock');
    expect(titles()).toContain('How do I record a sale?');
    expect(titles()).not.toContain('How do I add a shop or a store?');
    expect(titles()).not.toContain('How do I invite someone to use BMS?');
  });

  it('narrows the answers as the person types, best first, and says when none answers', () => {
    vi.useFakeTimers();
    show(owner, <HowDoIPage />);
    type('move stock from the store');
    const answers = within(screen.getByRole('list', { name: 'Answers' })).getAllByRole('link');
    expect(answers[0]?.textContent).toBe('How do I move stock from a store to a shop?');
    type('xylophone');
    expect(screen.getByText('No article answers that yet.')).toBeTruthy();
  });

  it('carries what was typed to Feedback with Ask us', () => {
    vi.useFakeTimers();
    show(owner, <HowDoIPage />);
    expect(screen.getByRole('link', { name: 'Ask us' }).getAttribute('href')).toBe('/staff/feedback');
    type('print a receipt twice');
    expect(screen.getByRole('link', { name: 'Ask us' }).getAttribute('href')).toBe('/staff/feedback?ask=print+a+receipt+twice');
  });

  it('starts Feedback as a question with the search typed in', () => {
    show(owner, <FeedbackForm send={vi.fn()} from={() => '/staff/help'} ask="print a receipt twice" />);
    expect((screen.getByLabelText(/A question/) as HTMLInputElement).checked).toBe(true);
    expect((screen.getByLabelText('Your message') as HTMLTextAreaElement).value).toBe('print a receipt twice');
  });
});

describe('an answer', () => {
  it('shows each step with its picture, whose text is the caption drawn on it', () => {
    show(owner, <ArticleView articleId="move-stock-store-to-shop" />, '/staff/help/move-stock-store-to-shop');
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('How do I move stock from a store to a shop?');
    const steps = within(screen.getByRole('list')).getAllByRole('listitem');
    expect(steps).toHaveLength(4);
    const picture = within(steps[0] as HTMLElement).getByRole('img');
    expect(picture.getAttribute('alt')).toBe('Choose the store with Switch branch');
    expect(picture.getAttribute('src')).toBe('/help/move-stock-store-to-shop-1.webp');
    expect(screen.getByRole('note').textContent).toMatch(/Send stock/);
  });

  it('says so to someone the answer is not for, and for an answer that does not exist', () => {
    show(seller, <ArticleView articleId="add-a-branch" />, '/staff/help/add-a-branch');
    expect(screen.getByText(/This article is for another role/)).toBeTruthy();
    expect(screen.queryByRole('img')).toBeNull();
    cleanup();
    show(seller, <ArticleView articleId="no-such-thing" />, '/staff/help/no-such-thing');
    expect(screen.getByText('This article could not be found.')).toBeTruthy();
  });
});

describe('the How do I? link of a screen', () => {
  it('opens the article of the screen', () => {
    show(owner, <HowDoILink pathname="/staff/retail/transfer" me={owner} />, '/staff/retail/transfer');
    expect(screen.getByRole('link', { name: /^How do I\?/ }).getAttribute('href')).toBe('/staff/help/move-stock-store-to-shop');
    cleanup();
    show(owner, <HowDoILink pathname="/staff/setup/branches" me={owner} />, '/staff/setup/branches');
    expect(screen.getByRole('link', { name: /^How do I\?/ }).getAttribute('href')).toBe('/staff/help/add-a-branch');
  });

  it('is not drawn on a screen with no article, on the help itself, or for someone the article is not for', () => {
    const { container } = show(owner, <HowDoILink pathname="/staff/notifications" me={owner} />, '/staff/notifications');
    expect(container.textContent).toBe('');
    cleanup();
    expect(show(owner, <HowDoILink pathname="/staff/help/switch-branch" me={owner} />).container.textContent).toBe('');
    cleanup();
    expect(show(seller, <HowDoILink pathname="/staff/retail/transfer" me={seller} />).container.textContent).toBe('');
  });
});

describe('offline', () => {
  it('shows the answers and their pictures with no network: nothing is fetched', () => {
    const fetch = vi.fn(() => Promise.reject(new TypeError('offline')));
    vi.stubGlobal('fetch', fetch);
    vi.stubGlobal('navigator', { ...navigator, onLine: false });
    show(owner, <HowDoIPage />);
    expect(titles()).toContain('How do I count stock?');
    cleanup();
    show(owner, <ArticleView articleId="count-stock" />, '/staff/help/count-stock');
    expect(screen.getAllByRole('img').length).toBe(4);
    expect(fetch).not.toHaveBeenCalled();
  });

  it('has the service worker keep the pictures, and the articles ship inside the app', () => {
    // Tests run from the frontend folder (jsdom gives no file address for this module).
    const config = readFileSync(join(process.cwd(), 'vite.config.ts'), 'utf8');
    expect(config).toMatch(/includeAssets: \[[^\]]*'help\/\*\.webp'/);
    expect(config).toMatch(/virtual:help-corpus/);
  });
});
