import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { retail, type NameHints } from '../../../api/retail';
import { SEARCH_DELAY_MS, createDebouncer } from '../../../app/ui/useLiveSearch';

// While a new item's name is typed (issue #353, ADR-093): the items it may already be ("Did you mean"), found ignoring case,
// accents, spaces and punctuation, and the category most items like it are in. Both are suggestions: the person opens or uses
// an existing item, or goes on; a suggested category is chosen only by tapping it, never typed in or chosen for them. Rule
// based, from GET /retail/products/name-hints, read once the typing settles. The save-time name check of #352 is unchanged.

/** The fewest letters and digits, spaces left out, before anything is suggested; as the server's. */
export const SHORTEST_HINTED = 3;

/** The hints for the name as it settles; undefined while too short, loading, or when it could not be read. */
export function useNameHints(description: string, enabled: boolean): NameHints | undefined {
  const [settled, setSettled] = useState(description);
  useEffect(() => {
    const debouncer = createDebouncer(SEARCH_DELAY_MS, setSettled);
    debouncer.push(description);
    return debouncer.cancel;
  }, [description]);
  const text = settled.trim();
  const hints = useQuery({
    queryKey: ['retail', 'name-hints', text],
    queryFn: () => retail.nameHints(text),
    enabled: enabled && text.replace(/[^\p{L}\p{N}]/gu, '').length >= SHORTEST_HINTED,
    staleTime: 60_000,
    retry: false,
  });
  return enabled && description.trim() !== '' ? hints.data : undefined;
}

export function NameHintsPanel({ id, hints, categoryId, categoryFixed = false, choosable, openLabel, onOpen, onUseCategory }: {
  id: string;
  hints: NameHints | undefined;
  /** The category chosen so far ('' for none). */
  categoryId: string;
  /** The item is added inside a category (issue #493): no category is suggested. */
  categoryFixed?: boolean;
  /** The categories the form offers; a suggested one it does not offer (switched off) is not suggested. */
  choosable: (categoryId: string) => boolean;
  /** "Open" on Items, "Use this item" on Buy stock and Count stock (issue #505). */
  openLabel: string;
  onOpen: (productId: string) => void;
  onUseCategory: (categoryId: string) => void;
}) {
  const items = hints?.did_you_mean ?? [];
  const category = !categoryFixed && hints?.category && hints.category.id !== categoryId && choosable(hints.category.id ?? '') ? hints.category : undefined;
  if (items.length === 0 && !category) return null;
  return (
    <div className="name-hints" data-testid={`${id}-hints`}>
      {items.length > 0 && (
        <section className="alert alert-info" role="note" aria-label="Did you mean">
          <div>
            <p><strong>Did you mean {items.length === 1 ? 'this item' : 'one of these'}?</strong></p>
            <ul className="name-hints-list">
              {items.map((h) => (
                <li key={h.id}>
                  <span>{h.description} ({h.code}), {h.category}, per {h.unit}</span>
                  <button type="button" onClick={() => onOpen(h.id ?? '')} aria-label={`${openLabel}: ${h.description}`}>{openLabel}</button>
                </li>
              ))}
            </ul>
          </div>
        </section>
      )}
      {category && (
        <div className="name-hints-category" data-testid={`${id}-category-hint`}>
          <p className="hint">
            {categoryId === '' ? 'Items like this are in' : 'Items like this are usually in'} <strong>{category.name}</strong>
            {category.example ? `, such as ${category.example}.` : '.'}
          </p>
          <button type="button" onClick={() => onUseCategory(category.id ?? '')}>{categoryId === '' ? `Choose ${category.name}` : `Choose ${category.name} instead`}</button>
        </div>
      )}
    </div>
  );
}
