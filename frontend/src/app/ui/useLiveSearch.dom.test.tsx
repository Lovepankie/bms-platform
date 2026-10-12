// @vitest-environment jsdom
import { QueryClient, QueryClientProvider, keepPreviousData, useQuery } from '@tanstack/react-query';
import { act, renderHook } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SEARCH_DELAY_MS, syncUrl, useLiveSearch } from './useLiveSearch';

// The hook as React runs it (#241): the state it hands the screen, the Escape and Enter keys, the clean-up on
// unmount, the optional URL mirror, and the request side of a search: a request whose term has moved on is
// aborted, its late answer is never shown, and the list stays while the new answer is on its way.

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  vi.useRealTimers();
  window.history.replaceState(null, '', '/');
});

const key = (k: string) => ({ key: k, preventDefault: () => undefined });

describe('useLiveSearch in React', () => {
  it('settles the term 250 ms after the last key and announces nothing before', () => {
    const { result } = renderHook(() => useLiveSearch());
    act(() => result.current.inputProps.onChange({ target: { value: 'kato' } }));
    expect(result.current).toMatchObject({ text: 'kato', term: '', pending: true });
    act(() => void vi.advanceTimersByTime(SEARCH_DELAY_MS));
    expect(result.current).toMatchObject({ text: 'kato', term: 'kato', pending: false });
  });

  it('Escape empties the box and the term at once, and a pending term never arrives', () => {
    const { result } = renderHook(() => useLiveSearch());
    act(() => result.current.setText('kato'));
    act(() => void vi.advanceTimersByTime(SEARCH_DELAY_MS));
    act(() => result.current.setText('katoo'));
    act(() => result.current.inputProps.onKeyDown(key('Escape')));
    expect(result.current).toMatchObject({ text: '', term: '', pending: false });
    act(() => void vi.advanceTimersByTime(1000));
    expect(result.current.term).toBe('');
  });

  it('Enter settles at once and does not submit a form', () => {
    const { result } = renderHook(() => useLiveSearch());
    act(() => result.current.setText('kato'));
    const prevented = vi.fn();
    act(() => result.current.inputProps.onKeyDown({ key: 'Enter', preventDefault: prevented }));
    expect(result.current.term).toBe('kato');
    expect(prevented).toHaveBeenCalled();
  });

  it('keeps no timer after unmount: a pending term is dropped, nothing is set on a gone component', () => {
    const { result, unmount } = renderHook(() => useLiveSearch());
    act(() => result.current.setText('kato'));
    expect(vi.getTimerCount()).toBe(1);
    unmount();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('returns the same functions on every render, so a screen can depend on them', () => {
    const { result, rerender } = renderHook(() => useLiveSearch());
    const { clear, flush, setText, setPage } = result.current;
    rerender();
    expect(result.current.clear).toBe(clear);
    expect(result.current.flush).toBe(flush);
    expect(result.current.setText).toBe(setText);
    expect(result.current.setPage).toBe(setPage);
  });

  it('does not touch the address unless it is asked to', () => {
    window.history.replaceState(null, '', '/staff/lending?status=active');
    const { result } = renderHook(() => useLiveSearch());
    act(() => result.current.setText('amina'));
    act(() => void vi.advanceTimersByTime(SEARCH_DELAY_MS));
    expect(window.location.search).toBe('?status=active');
  });

  it('mirrors the term in the address when asked, reads it on load, and keeps the other parameters', () => {
    window.history.replaceState(null, '', '/staff/stock?view=all&q=ledger');
    const { result } = renderHook(() => useLiveSearch({ urlParam: 'q' }));
    expect(result.current).toMatchObject({ text: 'ledger', term: 'ledger' });
    act(() => result.current.setText('ledger book'));
    act(() => void vi.advanceTimersByTime(SEARCH_DELAY_MS));
    expect(window.location.search).toBe('?view=all&q=ledger+book');
    act(() => result.current.clear());
    expect(window.location.search).toBe('?view=all');
  });

  it('replaces the history entry instead of adding one', () => {
    const target = { location: { pathname: '/a', search: '', hash: '' }, history: { state: { n: 1 }, replaceState: vi.fn() } };
    expect(syncUrl(target, 'q', 'x')).toBe(true);
    expect(target.history.replaceState).toHaveBeenCalledWith({ n: 1 }, '', '/a?q=x');
    target.location.search = '?q=x';
    expect(syncUrl(target, 'q', 'x')).toBe(false);
  });
});

describe('a search request that has moved on', () => {
  interface Call { term: string; resolve: (v: string) => void; signal: AbortSignal }

  function setup() {
    const calls: Call[] = [];
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    const hook = renderHook(
      () => {
        const search = useLiveSearch();
        const query = useQuery({
          queryKey: ['t', search.term],
          queryFn: ({ signal }) => new Promise<string>((resolve) => { calls.push({ term: search.term, resolve, signal }); }),
          placeholderData: keepPreviousData,
        });
        return { search, query };
      },
      { wrapper },
    );
    return { calls, ...hook };
  }

  /** Lets React Query deliver what was resolved: it hands results over on a zero-delay timer. */
  const flush = () => act(async () => void (await vi.advanceTimersByTimeAsync(0)));

  async function type(h: ReturnType<typeof setup>, text: string) {
    act(() => h.result.current.search.setText(text));
    await act(async () => void vi.advanceTimersByTime(SEARCH_DELAY_MS));
  }

  it('is aborted when a newer term replaces it, and its late answer is never shown', async () => {
    const h = setup();
    await type(h, 'ka');
    const first = h.calls.find((c) => c.term === 'ka');
    expect(first).toBeDefined();
    await type(h, 'kato');
    const second = h.calls.find((c) => c.term === 'kato');
    expect(second).toBeDefined();
    expect(first?.signal.aborted).toBe(true);
    expect(second?.signal.aborted).toBe(false);

    // the old answer arrives after the new request was sent: it must not become the data
    first?.resolve('answer for ka');
    await flush();
    expect(h.result.current.query.data).not.toBe('answer for ka');
    second?.resolve('answer for kato');
    await flush();
    expect(h.result.current.query.data).toBe('answer for kato');
    expect(h.result.current.query.isPlaceholderData).toBe(false);
  });

  it('keeps the previous answer on screen, marked as stale, until the new one arrives', async () => {
    const h = setup();
    await type(h, 'ka');
    h.calls.find((c) => c.term === 'ka')?.resolve('answer for ka');
    await flush();
    expect(h.result.current.query.data).toBe('answer for ka');
    await type(h, 'kato');
    expect(h.result.current.query.data).toBe('answer for ka');
    expect(h.result.current.query.isPlaceholderData).toBe(true);
    h.calls.find((c) => c.term === 'kato')?.resolve('answer for kato');
    await flush();
    expect(h.result.current.query.data).toBe('answer for kato');
    expect(h.result.current.query.isPlaceholderData).toBe(false);
  });

  it('sends no request for a burst of keys, only for the term they settle on', async () => {
    const h = setup();
    const before = h.calls.length;
    for (const text of ['k', 'ka', 'kat', 'kato']) {
      act(() => h.result.current.search.setText(text));
      await act(async () => void vi.advanceTimersByTime(100));
    }
    expect(h.calls.length).toBe(before);
    await act(async () => void vi.advanceTimersByTime(SEARCH_DELAY_MS));
    expect(h.calls.map((c) => c.term)).toEqual(['', 'kato']);
  });
});
