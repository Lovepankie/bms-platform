import { useEffect, useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react';
import type { LiveSearch } from './useLiveSearch';

// The one item picker (issue #534): a search box whose matches show as a short list under it only while something is typed,
// like a dropdown, and close once one is picked. Picking adds the item, empties the box and puts the cursor back in it for the
// next item. It follows the combobox pattern of the WAI-ARIA practices: the input is the combobox, the list a listbox, the
// highlighted option is announced through aria-activedescendant, Up and Down move it, Enter picks it, Escape closes the list
// and a second Escape empties the box. The search is debounced by useLiveSearch; the screen owns where the matches come from.

/** The most matches the list shows; typing more narrows it. */
export const PICKER_LIMIT = 10;

/** The words for no match, unless the screen gives its own (the offline picker says what the phone holds). */
export const noMatch = (term: string) => `No item matches "${term}".`;

/** What the matches are called in the status line: items, or the screens of the feature search (issue #551). */
export interface Noun {
  one: string;
  many: string;
}

const ITEMS: Noun = { one: 'item', many: 'items' };

/**
 * What the status line says, and a screen reader announces. Nothing while a search is on its way, so a reader hears the
 * answer once per pause and not "Searching" before it; the list itself says Searching.
 */
export function pickerStatus(s: { text: string; term: string; settled: boolean; count: number | undefined; more: boolean; empty?: string; noun?: Noun }): string {
  if (s.text.trim() === '' || !s.settled || s.count === undefined) return '';
  if (s.count === 0) return s.empty ?? noMatch(s.term);
  const noun = s.noun ?? ITEMS;
  const n = s.count === 1 ? `1 ${noun.one}` : `${s.count} ${noun.many}`;
  return s.more ? `First ${n} shown. Type more to narrow.` : `${n} found`;
}

export function ItemCombobox<T>({ id, label, search, results, fetching = false, error, optionKey, renderOption, onPick, hint, emptyText, noun }: {
  id: string;
  label: string;
  search: LiveSearch;
  /** The matches of `search.term`, undefined until the first answer. More than PICKER_LIMIT says there are more. */
  results: T[] | undefined;
  /** An answer for the current term is on its way. */
  fetching?: boolean;
  /** Drawn in the list when the search failed. */
  error?: ReactNode;
  optionKey: (item: T) => string;
  renderOption: (item: T) => ReactNode;
  onPick: (item: T) => void;
  hint?: string;
  /** What no match says, in place of noMatch(term). */
  emptyText?: (term: string) => string;
  /** What the status line calls the matches; items unless the screen says otherwise. */
  noun?: Noun;
}) {
  const input = useRef<HTMLInputElement>(null);
  const listId = `${id}-list`;
  const optionId = useId();
  const [open, setOpen] = useState(true);
  // Matches show only once the answer is for what is in the box: while more is typed, or the answer is on its way, the list
  // says Searching and holds no option, so neither a tap nor Enter can pick a match of an earlier search.
  const settled = !search.pending && !fetching && results !== undefined;
  const shown = settled ? results.slice(0, PICKER_LIMIT) : [];
  const answer = settled ? `${search.term}\n${results.map(optionKey).join('|')}` : null;
  // The highlight belongs to one answer, so a new answer starts with nothing highlighted in the same render.
  const [highlight, setHighlight] = useState<{ answer: string | null; index: number }>({ answer: null, index: -1 });
  const active = highlight.answer === answer && answer !== null ? highlight.index : -1;
  const setActive = (index: number) => setHighlight({ answer, index });
  const typed = search.text.trim() !== '';
  const expanded = open && typed;
  const empty = emptyText?.(search.term);
  const status = pickerStatus({ text: search.text, term: search.term, settled, count: settled ? shown.length : undefined, more: (results?.length ?? 0) > PICKER_LIMIT, empty, noun });

  useEffect(() => {
    if (active >= 0) document.getElementById(`${optionId}-${active}`)?.scrollIntoView?.({ block: 'nearest' });
  }, [active, optionId]);

  const pick = (item: T) => {
    onPick(item);
    search.clear();
    setActive(-1);
    input.current?.focus();
  };

  const onKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      setOpen(true);
      if (shown.length === 0) return;
      setActive(e.key === 'ArrowDown' ? Math.min(active + 1, shown.length - 1) : Math.max(active - 1, 0));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const item = expanded && active >= 0 ? shown[active] : undefined;
      if (item !== undefined) pick(item);
      else search.flush();
    } else if (e.key === 'Escape') {
      // An Escape that closes the list or empties the box belongs to the picker, so a panel around it does not close as well;
      // with the list closed and the box empty it goes on to the panel.
      if (!expanded && search.text === '') return;
      e.preventDefault();
      e.stopPropagation();
      if (expanded) setOpen(false);
      else search.clear();
      setActive(-1);
    }
  };

  return (
    <div
      className="sb cb"
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setOpen(false);
      }}
    >
      <label id={`${id}-label`} htmlFor={id}>{label}</label>
      <div className="cb-anchor">
        <div className="sb-field">
          <input
            id={id}
            ref={input}
            {...search.inputProps}
            role="combobox"
            aria-expanded={expanded}
            // Names the list only while it is drawn, so it never points at nothing.
            aria-controls={expanded ? listId : undefined}
            aria-autocomplete="list"
            aria-activedescendant={expanded && active >= 0 ? `${optionId}-${active}` : undefined}
            aria-describedby={`${id}-status`}
            onChange={(e) => {
              search.setText(e.target.value);
              setOpen(true);
            }}
            onFocus={() => setOpen(true)}
            onKeyDown={onKeyDown}
          />
          {search.text !== '' && (
            <button type="button" className="sb-clear" aria-label="Clear search" onClick={() => { search.clear(); input.current?.focus(); }}>
              Clear
            </button>
          )}
        </div>
        {expanded && (
          <div className="cb-pop">
            <ul id={listId} role="listbox" aria-labelledby={`${id}-label`} className="cb-list">
              {shown.map((item, i) => (
                <li
                  key={optionKey(item)}
                  id={`${optionId}-${i}`}
                  role="option"
                  aria-selected={i === active}
                  className="cb-option"
                  // The press keeps the cursor in the box, so the list does not close before the tap lands.
                  onMouseDown={(e) => e.preventDefault()}
                  onMouseMove={() => { if (i !== active) setActive(i); }}
                  onClick={() => pick(item)}
                >
                  {renderOption(item)}
                </li>
              ))}
            </ul>
            {error}
            {settled && shown.length === 0 && <p className="cb-empty">{empty ?? noMatch(search.term)}</p>}
            {!settled && !error && <p className="cb-empty">Searching</p>}
          </div>
        )}
      </div>
      {!expanded && !typed && hint && <p className="hint">{hint}</p>}
      <p id={`${id}-status`} className="sb-status" role="status" aria-live="polite">
        {status}
      </p>
    </div>
  );
}
