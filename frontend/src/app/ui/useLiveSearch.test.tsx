import { renderToString } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NoResults, SearchBox } from './SearchBox';
import {
  SEARCH_DELAY_MS,
  createDebouncer,
  createLiveSearch,
  highlightParts,
  normaliseSearch,
  searchStatus,
  searchTerm,
  termFromUrl,
  urlWithTerm,
  useLiveSearch,
} from './useLiveSearch';

// The timing of realtime search (#241): the term follows the text 250 ms after the last key, never
// before; a changed term sends paging back to page 1; Escape and Clear empty at once; a short text
// searches nothing. Driven through the state machine with fake timers, since the hook is a thin
// binding of it to React (its markup is checked with renderToString below).

describe('the text a search sends', () => {
  it('is trimmed with its white space collapsed', () => {
    expect(normaliseSearch('  john \t  doe  ')).toBe('john doe');
    expect(searchTerm('  john   doe ', 1)).toBe('john doe');
  });

  it('is empty below the minimum length, counted after trimming', () => {
    expect(searchTerm('a', 2)).toBe('');
    expect(searchTerm(' a ', 2)).toBe('');
    expect(searchTerm('ab', 2)).toBe('ab');
    expect(searchTerm('   ', 1)).toBe('');
  });
});

describe('the debounce', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('delivers only the last value, after the pause', () => {
    const seen: string[] = [];
    const d = createDebouncer<string>(SEARCH_DELAY_MS, (v) => seen.push(v));
    d.push('a');
    vi.advanceTimersByTime(100);
    d.push('ab');
    vi.advanceTimersByTime(100);
    d.push('abc');
    vi.advanceTimersByTime(SEARCH_DELAY_MS - 1);
    expect(seen).toEqual([]);
    vi.advanceTimersByTime(1);
    expect(seen).toEqual(['abc']);
  });

  it('can be cancelled', () => {
    const seen: string[] = [];
    const d = createDebouncer<string>(SEARCH_DELAY_MS, (v) => seen.push(v));
    d.push('a');
    d.cancel();
    vi.advanceTimersByTime(1000);
    expect(seen).toEqual([]);
  });
});

describe('the search state machine', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  function machine(options: { delayMs?: number; minLength?: number } = {}, initial = '') {
    const search = createLiveSearch(options, initial);
    const terms: string[] = [];
    search.subscribe(() => {
      const t = search.state().term;
      if (terms[terms.length - 1] !== t) terms.push(t);
    });
    return { search, terms };
  }

  it('keeps the text live and settles the term 250 ms after the last key', () => {
    const { search, terms } = machine();
    search.setText('k');
    search.setText('ka');
    expect(search.state()).toMatchObject({ text: 'ka', term: '', pending: true });
    vi.advanceTimersByTime(SEARCH_DELAY_MS - 1);
    expect(search.state().term).toBe('');
    vi.advanceTimersByTime(1);
    expect(search.state()).toMatchObject({ text: 'ka', term: 'ka', pending: false });
    expect(terms).toEqual(['', 'ka']);
  });

  it('restarts the pause on every key, so a burst is one search', () => {
    const { search, terms } = machine();
    for (const text of ['k', 'ka', 'kat', 'kato']) {
      search.setText(text);
      vi.advanceTimersByTime(SEARCH_DELAY_MS - 50);
    }
    expect(terms).toEqual(['']);
    vi.advanceTimersByTime(50);
    expect(terms).toEqual(['', 'kato']);
  });

  it('never queries with a stale term: a key after settling makes the old term pending, not current', () => {
    const { search } = machine();
    search.setText('kato');
    vi.advanceTimersByTime(SEARCH_DELAY_MS);
    search.setText('katoo');
    // the screen still queries "kato" until the new term settles, and knows an answer is due
    expect(search.state()).toMatchObject({ term: 'kato', pending: true });
    vi.advanceTimersByTime(SEARCH_DELAY_MS);
    expect(search.state()).toMatchObject({ term: 'katoo', pending: false });
  });

  it('trims, so a trailing space is no new search and keeps the page', () => {
    const { search, terms } = machine();
    search.setText('kato');
    vi.advanceTimersByTime(SEARCH_DELAY_MS);
    search.setPage(3);
    search.setText('kato ');
    expect(search.state().pending).toBe(false);
    vi.advanceTimersByTime(SEARCH_DELAY_MS);
    expect(search.state()).toMatchObject({ term: 'kato', page: 3 });
    expect(terms).toEqual(['', 'kato']);
  });

  it('applies the minimum length and says when the text is too short', () => {
    const { search } = machine({ minLength: 2 });
    search.setText('k');
    expect(search.state().tooShort).toBe(true);
    vi.advanceTimersByTime(SEARCH_DELAY_MS);
    expect(search.state()).toMatchObject({ term: '', tooShort: true, pending: false });
    search.setText('ka');
    vi.advanceTimersByTime(SEARCH_DELAY_MS);
    expect(search.state()).toMatchObject({ term: 'ka', tooShort: false });
  });

  it('sends paging back to page 1 when the term changes, and only then', () => {
    const { search } = machine();
    search.setText('kato');
    vi.advanceTimersByTime(SEARCH_DELAY_MS);
    search.setPage(4);
    expect(search.state().page).toBe(4);
    search.setText('katoo');
    vi.advanceTimersByTime(SEARCH_DELAY_MS);
    expect(search.state()).toMatchObject({ term: 'katoo', page: 1 });
    search.setPage(0);
    expect(search.state().page).toBe(1);
  });

  it('settles at once on flush (Enter), cancelling the pause', () => {
    const { search, terms } = machine();
    search.setText('kato');
    search.flush();
    expect(search.state()).toMatchObject({ term: 'kato', pending: false });
    vi.advanceTimersByTime(1000);
    expect(terms).toEqual(['', 'kato']);
  });

  it('empties text and term at once on clear (Escape, the clear button) and cancels a pending term', () => {
    const { search, terms } = machine();
    search.setText('kato');
    vi.advanceTimersByTime(SEARCH_DELAY_MS);
    search.setPage(2);
    search.setText('katoo');
    search.clear();
    expect(search.state()).toMatchObject({ text: '', term: '', pending: false, page: 1 });
    vi.advanceTimersByTime(1000);
    expect(search.state().term).toBe('');
    expect(terms).toEqual(['', 'kato', '']);
  });

  it('starts from a given text, such as one read from the URL, already settled', () => {
    const { search } = machine({}, '  amina  kato ');
    expect(search.state()).toMatchObject({ text: '  amina  kato ', term: 'amina kato', pending: false });
  });

  it('takes no more than the longest text the server reads', () => {
    const { search } = machine();
    search.setText('x'.repeat(300));
    expect(search.state().text).toHaveLength(100);
  });

  it('stops delivering after dispose', () => {
    const { search, terms } = machine();
    search.setText('kato');
    search.dispose();
    vi.advanceTimersByTime(1000);
    expect(terms).toEqual(['']);
  });
});

describe('the URL parameter', () => {
  it('reads the term from the query string', () => {
    expect(termFromUrl('?q=amina+kato&status=active', 'q')).toBe('amina kato');
    expect(termFromUrl('?status=active', 'q')).toBe('');
    expect(termFromUrl('', 'q')).toBe('');
  });

  it('writes the term in and out, keeping the other parameters and the hash', () => {
    expect(urlWithTerm('/staff/lending?status=active#top', 'q', 'amina kato')).toBe('/staff/lending?status=active&q=amina+kato#top');
    expect(urlWithTerm('/staff/lending?q=old', 'q', 'new')).toBe('/staff/lending?q=new');
    expect(urlWithTerm('/staff/lending?q=old&status=active', 'q', '')).toBe('/staff/lending?status=active');
    expect(urlWithTerm('/staff/lending', 'q', '')).toBe('/staff/lending');
  });
});

describe('the words under the box', () => {
  const base = { term: 'kato', tooShort: false, minLength: 1, searching: false, count: 3 };

  it('says nothing when there is no search', () => {
    expect(searchStatus({ ...base, term: '', count: 10 })).toBe('');
  });

  it('says Searching while a term is pending, fetching or unanswered', () => {
    expect(searchStatus({ ...base, searching: true })).toBe('Searching');
    expect(searchStatus({ ...base, count: undefined })).toBe('Searching');
  });

  it('counts the results, one and many, and notes that there is more', () => {
    expect(searchStatus({ ...base, count: 1 })).toBe('1 result');
    expect(searchStatus({ ...base, count: 12 })).toBe('12 results');
    expect(searchStatus({ ...base, count: 25, more: true })).toBe('25 results so far, more below');
  });

  it('names the term when nothing matches', () => {
    expect(searchStatus({ ...base, count: 0 })).toBe('No results for "kato"');
  });

  it('asks for more letters when the text is too short', () => {
    expect(searchStatus({ ...base, term: '', tooShort: true, minLength: 2 })).toBe('Type at least 2 characters to search.');
  });
});

describe('highlighting', () => {
  it('marks each word of the term, ignoring case and accents', () => {
    expect(highlightParts('Amina Kato', 'kato')).toEqual([
      { text: 'Amina ', hit: false },
      { text: 'Kato', hit: true },
    ]);
    expect(highlightParts('José Mukasa', 'JOSE muk')).toEqual([
      { text: 'José', hit: true },
      { text: ' ', hit: false },
      { text: 'Muk', hit: true },
      { text: 'asa', hit: false },
    ]);
  });

  it('marks every occurrence, joining neighbours, and leaves a text without a match whole', () => {
    expect(highlightParts('banana', 'an').map((p) => p.text)).toEqual(['b', 'anan', 'a']);
    expect(highlightParts('Amina', 'zzz')).toEqual([{ text: 'Amina', hit: false }]);
    expect(highlightParts('Amina', '')).toEqual([{ text: 'Amina', hit: false }]);
  });
});

describe('the search box markup', () => {
  function Box({ hasText, term }: { hasText: boolean; term: string }) {
    const search = useLiveSearch({ minLength: 2 });
    const shown = { ...search, text: hasText ? term : '', term };
    return <SearchBox id="t-q" label="Find" search={shown} fetching={false} count={0} minLength={2} />;
  }

  it('labels the input, links it to a polite live status and offers Clear only when there is text', () => {
    const empty = renderToString(<Box hasText={false} term="" />);
    expect(empty).toContain('for="t-q"');
    expect(empty).toContain('type="search"');
    expect(empty).toContain('aria-describedby="t-q-status"');
    expect(empty).toContain('role="status"');
    expect(empty).toContain('aria-live="polite"');
    expect(empty).not.toContain('Clear search');

    const typed = renderToString(<Box hasText term="kato" />);
    expect(typed).toContain('aria-label="Clear search"');
    expect(typed).toContain('No results for &quot;kato&quot;');
  });

  it('offers the way out of an empty answer', () => {
    const html = renderToString(<NoResults term="zzz" onClear={() => undefined} noun="members" />);
    expect(html).toContain('No members for &quot;zzz&quot;.');
    expect(html).toContain('Clear search');
  });
});
