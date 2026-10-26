import { useRef, type ReactNode } from 'react';
import { highlightParts, searchStatus, type LiveSearch } from './useLiveSearch';

// The one search box (#241): a label, the input with its clear button, and a status line that a screen
// reader announces ("Searching", "12 results", 'No results for "x"'). Drive it with useLiveSearch.

/**
 * `fetching` is true while an answer for the current term is on its way, or while the list still shows
 * the answer to the previous term. `count` is how many rows the answer holds so far (undefined until
 * the first answer), `more` that another page is available.
 */
export function SearchBox({ id, label, search, fetching, count, more, minLength = 1, hint, required }: {
  id: string;
  label: string;
  search: LiveSearch;
  fetching: boolean;
  count: number | undefined;
  more?: boolean;
  minLength?: number;
  hint?: string;
  /** The search picks something the form needs: the label carries the star and the input aria-required. */
  required?: boolean;
}) {
  const status = searchStatus({
    term: search.term,
    tooShort: search.tooShort,
    minLength,
    searching: search.pending || fetching,
    count,
    more,
  });
  const searching = status === 'Searching';
  const input = useRef<HTMLInputElement>(null);
  return (
    <div className="sb">
      <label htmlFor={id}>{label}{required ? <span className="field-star" aria-hidden="true"> *</span> : null}</label>
      <div className="sb-field">
        <input id={id} ref={input} aria-describedby={`${id}-status`} aria-required={required || undefined} {...search.inputProps} />
        {search.text !== '' && (
          <button type="button" className="sb-clear" aria-label="Clear search" onClick={() => { search.clear(); input.current?.focus(); }}>
            Clear
          </button>
        )}
      </div>
      {hint && search.text === '' && <p className="hint">{hint}</p>}
      <p id={`${id}-status`} className={searching ? 'sb-status sb-searching' : 'sb-status'} role="status" aria-live="polite">
        {status}
      </p>
    </div>
  );
}

/** The list under a search box. While `fetching`, the old answer stays in place, dimmed and marked busy. */
export function SearchResults({ fetching, children }: { fetching: boolean; children: ReactNode }) {
  return (
    <div className="sb-results" aria-busy={fetching}>
      {children}
    </div>
  );
}

/** The empty answer to a search: what was asked, and the way out. */
export function NoResults({ term, onClear, noun = 'results' }: { term: string; onClear: () => void; noun?: string }) {
  return (
    <div className="empty-state">
      <p>{`No ${noun} for "${term}".`}</p>
      <button type="button" className="btn-sm" onClick={onClear}>
        Clear search
      </button>
    </div>
  );
}

/** `text` with the words of `term` marked, ignoring case and accents. */
export function Highlight({ text, term }: { text: string; term: string }) {
  return (
    <>
      {highlightParts(text, term).map((part, i) =>
        part.hit ? (
          <mark key={i} className="sb-mark">
            {part.text}
          </mark>
        ) : (
          part.text
        ),
      )}
    </>
  );
}
