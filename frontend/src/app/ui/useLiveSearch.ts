import { useEffect, useState, useSyncExternalStore } from 'react';
import { foldText } from './fold';

// Realtime search for every screen (#241, ADR-039, docs/specs/search.md). One hook owns the text a
// person types, the term the screen queries with, and the rules between them:
//
//  - the text stays responsive; the term follows 250 ms after the last key (Enter settles it at once);
//  - the term is trimmed with its white space collapsed, and is empty below the minimum length;
//  - a changed term sends paging back to the first page;
//  - Escape and the clear button empty both at once.
//
// Stale answers never show: the term is part of the query key, so an old answer belongs to an old key,
// and a screen passes `placeholderData: keepPreviousData` so the list stays until the new answer is in
// (no flicker) and the box says "Searching" meanwhile. The query function passes React Query's
// `signal` to the request, which aborts a request whose key no longer has an observer.

/** The pause after the last key before the term changes, in milliseconds. */
export const SEARCH_DELAY_MS = 250;

/** The most characters the box takes; the server ignores more (Search.MAX_LENGTH). */
export const SEARCH_MAX_LENGTH = 100;

/** Trimmed, with each run of white space made one space, and accents written as one letter (NFC). */
export function normaliseSearch(text: string): string {
  return text.normalize('NFC').trim().replace(/\s+/g, ' ');
}

/** The term to query with: the normalised text, or '' while it is shorter than `minLength`. */
export function searchTerm(text: string, minLength: number): string {
  const term = normaliseSearch(text);
  return term.length >= minLength ? term : '';
}

/** Calls `onValue` with the last pushed value once `ms` have passed without another push. */
export function createDebouncer<T>(ms: number, onValue: (value: T) => void) {
  let timer: ReturnType<typeof setTimeout> | undefined;
  return {
    push(value: T) {
      clearTimeout(timer);
      timer = setTimeout(() => onValue(value), ms);
    },
    cancel() {
      clearTimeout(timer);
    },
  };
}

export interface LiveSearchOptions {
  /** Milliseconds between the last key and the term changing. */
  delayMs?: number;
  /** The fewest characters that make a term; shorter text queries nothing (an unfiltered list). */
  minLength?: number;
  /**
   * A URL query parameter that mirrors the term, so a search can be reloaded and shared. Never on a screen
   * that searches people (a name, a phone or a national id would reach the history, shared links and the
   * Referer header); none of the lending screens sets it.
   */
  urlParam?: string;
}

export interface LiveSearchState {
  /** What the box shows. */
  text: string;
  /** What the screen queries with: settled, normalised, long enough. '' means no search. */
  term: string;
  /** The text has changed and the term has not caught up yet (the debounce is running). */
  pending: boolean;
  /** There is text, but too little of it to search with. */
  tooShort: boolean;
  /** The page of an offset-paged list; 1 whenever the term changes. Cursor lists page through the query key instead. */
  page: number;
}

/**
 * The search state machine, free of React so its timing can be tested with fake timers. Listeners
 * added with `subscribe` are called after every change; `state()` is the same object until then.
 */
export function createLiveSearch(options: { delayMs?: number; minLength?: number }, initialText: string) {
  const listeners = new Set<() => void>();
  const delayMs = options.delayMs ?? SEARCH_DELAY_MS;
  const minLength = options.minLength ?? 1;
  const bounded = (text: string) => text.slice(0, SEARCH_MAX_LENGTH);
  const derive = (text: string, term: string, page: number): LiveSearchState => {
    const wanted = searchTerm(text, minLength);
    return {
      text,
      term,
      pending: wanted !== term,
      tooShort: normaliseSearch(text) !== '' && wanted === '',
      page,
    };
  };
  const first = bounded(initialText);
  let state = derive(first, searchTerm(first, minLength), 1);
  const publish = (next: LiveSearchState) => {
    state = next;
    listeners.forEach((listener) => listener());
  };
  const settle = (text: string) => {
    const wanted = searchTerm(text, minLength);
    // A term that did not change keeps its page: typing a trailing space is not a new search.
    publish(derive(text, wanted, wanted === state.term ? state.page : 1));
  };
  const debouncer = createDebouncer<string>(delayMs, settle);
  return {
    state: () => state,
    subscribe(listener: () => void) {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    setText(text: string) {
      const next = bounded(text);
      publish(derive(next, state.term, state.page));
      debouncer.push(next);
    },
    /** Settle now: Enter, or the end of a form submit. */
    flush() {
      debouncer.cancel();
      settle(state.text);
    },
    /** Empty the box and the term at once. */
    clear() {
      debouncer.cancel();
      publish(derive('', '', state.term === '' ? state.page : 1));
    },
    setPage(page: number) {
      publish({ ...state, page: Math.max(1, page) });
    },
    dispose() {
      debouncer.cancel();
    },
  };
}

/** The term a URL carries in `param`, or ''. `search` is `location.search`. */
export function termFromUrl(search: string, param: string): string {
  return normaliseSearch(new URLSearchParams(search).get(param) ?? '');
}

/** `href` with `param` set to `term`, or removed when the term is empty; other parameters are kept. */
export function urlWithTerm(href: string, param: string, term: string): string {
  const url = new URL(href, 'http://localhost');
  if (term) url.searchParams.set(param, term);
  else url.searchParams.delete(param);
  return `${url.pathname}${url.search}${url.hash}`;
}

/** The parts of `window` the URL mirror touches, so it can be tested without a browser. */
export interface UrlTarget {
  location: { pathname: string; search: string; hash: string };
  history: { state: unknown; replaceState: (state: unknown, title: string, url: string) => void };
}

/** Writes `term` into `param` of the address (replacing the history entry, not adding one). Returns whether it wrote. */
export function syncUrl(target: UrlTarget, param: string, term: string): boolean {
  const href = `${target.location.pathname}${target.location.search}${target.location.hash}`;
  const next = urlWithTerm(href, param, term);
  if (next === href) return false;
  target.history.replaceState(target.history.state, '', next);
  return true;
}

export interface LiveSearch extends LiveSearchState {
  setText: (text: string) => void;
  clear: () => void;
  flush: () => void;
  setPage: (page: number) => void;
  /** Spread on the box's `<input>`. */
  inputProps: {
    type: 'search';
    value: string;
    autoComplete: 'off';
    enterKeyHint: 'search';
    maxLength: number;
    onChange: (e: { target: { value: string } }) => void;
    onKeyDown: (e: { key: string; preventDefault: () => void }) => void;
  };
}

/** The hook every search box uses. See the notes at the top of this file. */
export function useLiveSearch(options: LiveSearchOptions = {}): LiveSearch {
  const { delayMs, minLength, urlParam } = options;
  const [live] = useState(() =>
    createLiveSearch(
      { delayMs, minLength },
      urlParam && typeof window !== 'undefined' ? termFromUrl(window.location.search, urlParam) : '',
    ),
  );
  const state = useSyncExternalStore(live.subscribe, live.state, live.state);

  useEffect(() => () => live.dispose(), [live]);

  useEffect(() => {
    if (urlParam && typeof window !== 'undefined') syncUrl(window, urlParam, state.term);
  }, [urlParam, state.term]);

  return {
    ...state,
    setText: live.setText,
    clear: live.clear,
    flush: live.flush,
    setPage: live.setPage,
    inputProps: {
      type: 'search',
      value: state.text,
      autoComplete: 'off',
      enterKeyHint: 'search',
      maxLength: SEARCH_MAX_LENGTH,
      onChange: (e) => live.setText(e.target.value),
      onKeyDown: (e) => {
        if (e.key === 'Escape') {
          e.preventDefault();
          live.clear();
        } else if (e.key === 'Enter') {
          e.preventDefault();
          live.flush();
        }
      },
    },
  };
}

/** The words of the status line under a search box, or '' when there is nothing to say. */
export function searchStatus(s: {
  term: string;
  tooShort: boolean;
  minLength: number;
  searching: boolean;
  count: number | undefined;
  more?: boolean;
}): string {
  if (s.tooShort) return `Type at least ${s.minLength} characters to search.`;
  if (s.term === '') return '';
  if (s.searching || s.count === undefined) return 'Searching';
  if (s.count === 0) return `No results for "${s.term}"`;
  const n = s.count === 1 ? '1 result' : `${s.count} results`;
  return s.more ? `${n} so far, more below` : n;
}

/**
 * The parts of `text` split at the places any word of `term` matches, ignoring case and accents:
 * `[{ text: 'Amina ', hit: false }, { text: 'Kato', hit: true }]`. No term gives the whole text unmarked.
 */
export function highlightParts(text: string, term: string): { text: string; hit: boolean }[] {
  const words = foldText(normaliseSearch(term)).split(' ').filter(Boolean);
  if (words.length === 0 || text === '') return [{ text, hit: false }];
  let folded = '';
  const origin: number[] = [];
  Array.from(text.normalize('NFC')).forEach((c, i) => {
    const f = foldText(c);
    for (let k = 0; k < f.length; k++) origin.push(i);
    folded += f;
  });
  const chars = Array.from(text.normalize('NFC'));
  const marked = new Array<boolean>(chars.length).fill(false);
  for (const word of words) {
    for (let at = folded.indexOf(word); at !== -1; at = folded.indexOf(word, at + 1)) {
      for (let k = at; k < at + word.length; k++) marked[origin[k] ?? 0] = true;
    }
  }
  const parts: { text: string; hit: boolean }[] = [];
  chars.forEach((c, i) => {
    const hit = marked[i] === true;
    const last = parts[parts.length - 1];
    if (last && last.hit === hit) last.text += c;
    else parts.push({ text: c, hit });
  });
  return parts;
}

/**
 * True when every word of `term` appears in `text`, ignoring case and accents: the in-browser form of
 * the server's matching, for a list that is already loaded (a stock-take sheet). Empty term: true.
 */
export function matchesSearch(text: string, term: string): boolean {
  const words = foldText(normaliseSearch(term)).split(' ').filter(Boolean);
  if (words.length === 0) return true;
  const folded = foldText(text);
  return words.every((word) => folded.includes(word));
}
