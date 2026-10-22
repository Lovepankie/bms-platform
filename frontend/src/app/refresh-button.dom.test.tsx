// @vitest-environment jsdom
import { QueryClient, QueryClientProvider, onlineManager, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RefreshButton, agoText, oldestLoad } from './refresh-button';

// The header refresh button (issue #430): it refetches the queries of the page on screen, spins while it runs, says when the data was
// last loaded and when the device is offline, and announces the result. The figures are invented.

const fetchFigures = vi.fn();

function Page() {
  const figures = useQuery({ queryKey: ['figures'], queryFn: () => fetchFigures() as Promise<string>, retry: false });
  return <p data-testid="figures">{figures.data ?? 'none'}</p>;
}

function setOnline(value: boolean) {
  Object.defineProperty(window.navigator, 'onLine', { configurable: true, value });
  // The query cache only hears the events while a query is mounted, so it is told directly as well.
  onlineManager.setOnline(value);
  act(() => {
    window.dispatchEvent(new Event(value ? 'online' : 'offline'));
  });
}

function draw() {
  const client = new QueryClient();
  render(
    <QueryClientProvider client={client}>
      <RefreshButton />
      <Page />
    </QueryClientProvider>,
  );
  return client;
}

beforeEach(() => {
  fetchFigures.mockReset();
  fetchFigures.mockResolvedValue('UGX 100');
  setOnline(true);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  setOnline(true);
});

describe('agoText', () => {
  const at = 1_000_000;
  it('speaks in minutes, hours and days', () => {
    expect(agoText(at, at + 10_000)).toBe('just now');
    expect(agoText(at, at + 60_000)).toBe('1 minute ago');
    expect(agoText(at, at + 120_000)).toBe('2 minutes ago');
    expect(agoText(at, at + 3_600_000)).toBe('1 hour ago');
    expect(agoText(at, at + 2 * 86_400_000)).toBe('2 days ago');
  });
});

describe('the refresh button', () => {
  it('is a labelled button a keyboard reaches, with the tour anchor', () => {
    draw();
    const button = screen.getByRole('button', { name: 'Refresh this page' });
    expect(button.tagName).toBe('BUTTON');
    expect(button.getAttribute('type')).toBe('button');
    expect(button.closest('[data-tour="refresh"]')).not.toBeNull();
    expect(button.getAttribute('tabindex')).toBeNull();
  });

  it('refetches the data of the current page, spins while it runs and announces the result', async () => {
    draw();
    await waitFor(() => expect(screen.getByTestId('figures').textContent).toBe('UGX 100'));
    expect(fetchFigures).toHaveBeenCalledTimes(1);

    let finish: (value: string) => void = () => undefined;
    fetchFigures.mockReturnValueOnce(new Promise<string>((resolve) => (finish = resolve)));
    const button = screen.getByRole('button', { name: 'Refresh this page' });
    fireEvent.click(button);

    await waitFor(() => expect(button.getAttribute('aria-busy')).toBe('true'));
    expect(button.querySelector('.refresh-spinning')).not.toBeNull();
    expect(screen.getByRole('status').textContent).toBe('Refreshing');
    expect(fetchFigures).toHaveBeenCalledTimes(2);

    await act(async () => finish('UGX 250'));
    await waitFor(() => expect(button.getAttribute('aria-busy')).toBe('false'));
    expect(button.querySelector('.refresh-spinning')).toBeNull();
    expect(screen.getByTestId('figures').textContent).toBe('UGX 250');
    expect(screen.getByRole('status').textContent).toBe('Refreshed. Updated just now.');
  });

  it('shows when the data was loaded and keeps the words up to date as time passes', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    draw();
    await waitFor(() => expect(screen.getByText('Updated just now')).toBeTruthy());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2 * 60_000 + 30_000);
    });
    expect(screen.getByText('Updated 2 minutes ago')).toBeTruthy();
  });

  it('says so when offline, keeps the old figures and does not refetch', async () => {
    draw();
    await waitFor(() => expect(screen.getByTestId('figures').textContent).toBe('UGX 100'));
    setOnline(false);
    expect(screen.getByText(/^Offline/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Refresh this page' }));
    expect(fetchFigures).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('figures').textContent).toBe('UGX 100');
    expect(screen.getByRole('status').textContent).toMatch(/offline, so the page was not refreshed\. Showing the figures from just now\./);
  });

  it('says the refresh failed and keeps the old figures', async () => {
    draw();
    await waitFor(() => expect(screen.getByTestId('figures').textContent).toBe('UGX 100'));
    fetchFigures.mockRejectedValueOnce(new Error('down'));
    fireEvent.click(screen.getByRole('button', { name: 'Refresh this page' }));
    await waitFor(() => expect(screen.getByRole('status').textContent).toMatch(/^Could not refresh\./));
    expect(screen.getByTestId('figures').textContent).toBe('UGX 100');
    // A sighted user sees it too, in an element that is not hidden at phone width, and it clears when a refresh works.
    const marker = document.querySelector('.refresh-problem');
    expect(marker?.textContent).toMatch(/Not refreshed/);
    fireEvent.click(screen.getByRole('button', { name: 'Refresh this page' }));
    await waitFor(() => expect(document.querySelector('.refresh-problem')).toBeNull());
  });

  it('says so, visibly, when only some of the page failed to refresh', async () => {
    const client = new QueryClient();
    function Two() {
      useQuery({ queryKey: ['a'], queryFn: () => fetchFigures() as Promise<string>, retry: false });
      useQuery({ queryKey: ['b'], queryFn: () => Promise.resolve('b'), retry: false });
      return null;
    }
    render(<QueryClientProvider client={client}><RefreshButton /><Two /></QueryClientProvider>);
    await waitFor(() => expect(client.getQueryState(['b'])?.status).toBe('success'));
    fetchFigures.mockRejectedValueOnce(new Error('403'));
    fireEvent.click(screen.getByRole('button', { name: 'Refresh this page' }));
    await waitFor(() => expect(document.querySelector('.refresh-problem')?.textContent).toMatch(/Partly updated/));
    expect(screen.getByRole('status').textContent).toMatch(/^Refreshed, but some figures/);
  });

  it('disables the button while it runs, so a second tap is not swallowed silently', async () => {
    draw();
    await waitFor(() => expect(fetchFigures).toHaveBeenCalledTimes(1));
    fetchFigures.mockReturnValueOnce(new Promise<string>(() => undefined));
    const button = screen.getByRole('button', { name: 'Refresh this page' }) as HTMLButtonElement;
    fireEvent.click(button);
    await waitFor(() => expect(button.disabled).toBe(true));
  });

  it('keeps every loaded page of an infinite list on screen, and still there when the refresh fails', async () => {
    const client = new QueryClient();
    const pageFor = vi.fn((n: number) => Promise.resolve([n]));
    function List() {
      useInfiniteQuery({ queryKey: ['list'], queryFn: ({ pageParam }) => pageFor(pageParam), initialPageParam: 0, retry: false, getNextPageParam: (_l, all) => all.length });
      return null;
    }
    render(<QueryClientProvider client={client}><RefreshButton /><List /></QueryClientProvider>);
    await waitFor(() => expect(pageFor).toHaveBeenCalledTimes(1));
    client.setQueryData(['list'], { pages: [[0], [1], [2]], pageParams: [0, 1, 2] }, { updatedAt: 5_000 });
    pageFor.mockRejectedValueOnce(new Error('down'));
    fireEvent.click(screen.getByRole('button', { name: 'Refresh this page' }));
    await waitFor(() => expect(document.querySelector('.refresh-problem')).not.toBeNull());
    expect((client.getQueryData(['list']) as { pages: unknown[] }).pages).toHaveLength(3);
    // The failure does not make old figures look fresh.
    expect(client.getQueryState(['list'])?.dataUpdatedAt).toBe(5_000);
  });

  it('forgets a failure marker when the page is left (the layout keys the button by path)', async () => {
    const client = new QueryClient();
    function Host({ path }: { path: string }) {
      return <RefreshButton key={path} />;
    }
    const view = render(<QueryClientProvider client={client}><Host path="/staff/a" /><Page /></QueryClientProvider>);
    await waitFor(() => expect(fetchFigures).toHaveBeenCalledTimes(1));
    fetchFigures.mockRejectedValueOnce(new Error('down'));
    fireEvent.click(screen.getByRole('button', { name: 'Refresh this page' }));
    await waitFor(() => expect(document.querySelector('.refresh-problem')).not.toBeNull());
    view.rerender(<QueryClientProvider client={client}><Host path="/staff/b" /><Page /></QueryClientProvider>);
    expect(document.querySelector('.refresh-problem')).toBeNull();
  });

  it('dates the page by its oldest steady query, not by a poller', async () => {
    const client = new QueryClient();
    function Mixed() {
      useQuery({ queryKey: ['slow'], queryFn: () => Promise.resolve(1) });
      useQuery({ queryKey: ['poll'], queryFn: () => Promise.resolve(2), refetchInterval: 5000 });
      return null;
    }
    render(<QueryClientProvider client={client}><Mixed /></QueryClientProvider>);
    await waitFor(() => expect(client.getQueryState(['poll'])?.status).toBe('success'));
    const queries = client.getQueryCache().findAll({ type: 'active' });
    const slow = queries.find((q) => q.queryKey[0] === 'slow')!;
    slow.state.dataUpdatedAt = 1_000;
    expect(oldestLoad(queries)).toBe(1_000);
  });
});
