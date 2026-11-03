import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { retail, type Category, type Product } from '../../../api/retail';
import { WORDS } from '../../../app/glossary';
import { ListRow } from '../../../app/ui/ListRow';
import { ScreenLink } from './screen-link';
import { money } from './ui';
import { chipsOf, itemStock, knownChip, looksLikeBarcode, noItemCase, noItemText, readChip, writeChip, type FindPage } from './phone-find-state';

// Finding items on a phone (issue #690): the search box and the category chips stay at the top while the list scrolls, the
// chips scroll sideways inside their own row, an item is one big row that opens it, and a search that finds nothing offers
// Add it only when nothing narrowed it. Add it opens Quick add with the name typed (its `name` route param), the add form that
// takes a name from the link. Quick add needs what Items and My stock need, so whoever sees these pages may use it.

/**
 * The page's chosen chip, remembered per page. `ready` is false while a remembered chip waits for the categories, so the list
 * never asks with a category that has gone; one that has gone reads as All and is forgotten.
 */
export function useChosenChip(page: FindPage, categories: Category[] | undefined, failed = false): [string, (id: string) => void, boolean] {
  const [id, setId] = useState(() => readChip(page));
  const choose = (next: string) => {
    setId(next);
    writeChip(page, next);
  };
  const known = knownChip(id, categories);
  useEffect(() => {
    if (categories && id && !known) writeChip(page, '');
  }, [categories, id, known, page]);
  // Categories that cannot load (offline, a server error) never hold the list back: it shows All (review of #703).
  if (failed && categories === undefined) return ['', choose, true];
  return [known, choose, id === '' || categories !== undefined];
}

/**
 * The query a list asks with: digits that are a known barcode become that item's code, which the server search matches, so a
 * barcode typed or scanned into the box finds its item. `match` is that item's id, so a list shows it alone and not the items
 * whose code only begins the same. `looking` is true while the barcode is being looked up.
 */
export function useFindTerm(term: string): { query: string; match?: string; looking: boolean } {
  const code = looksLikeBarcode(term) ? term.trim() : '';
  const found = useQuery({
    queryKey: ['retail', 'by-barcode', code],
    queryFn: ({ signal }) => retail.findByBarcode(code, undefined, signal),
    enabled: code !== '',
    staleTime: 60_000,
    retry: false,
  });
  const product = found.data?.product;
  return { query: product?.code ?? term, match: product?.id, looking: code !== '' && found.isPending };
}

/** The rows of a list, only the barcode's own item when the search was a known barcode. */
export function onlyMatch<T>(rows: T[] | undefined, match: string | undefined, idOf: (row: T) => string | undefined): T[] | undefined {
  return rows && match ? rows.filter((r) => idOf(r) === match) : rows;
}

/**
 * The search and the chips: sticky under the top bar on a phone (`.find-bar` in retail.css). The top bar's measured height is
 * the offset, so a bar that grows with a large font never covers the search.
 */
export function FindBar({ children, tour }: { children: ReactNode; tour?: string }) {
  const bar = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = bar.current;
    const top = document.querySelector<HTMLElement>('.staff-bar');
    if (!el || !top) return;
    const measure = () => {
      const height = Math.ceil(top.getBoundingClientRect().height);
      if (height > 0) el.style.setProperty('--find-top', `${height}px`);
    };
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    observer?.observe(top);
    return () => observer?.disconnect();
  }, []);
  return <div className="find-bar" ref={bar} data-tour={tour}>{children}</div>;
}

/** One row of chips, All first, each category with its item count; the chosen one is pressed and scrolled into the row's view. */
export function CategoryChips({ categories, value, onChange }: { categories: Category[]; value: string; onChange: (id: string) => void }) {
  const row = useRef<HTMLDivElement>(null);
  useEffect(() => {
    let live = true;
    const bring = () => {
      const box = row.current;
      const chosen = box?.querySelector<HTMLElement>('[aria-pressed="true"]');
      if (!live || !box || !chosen) return;
      // Measured against the row itself, whatever its offset parent: only the row scrolls, never the page.
      const edge = box.getBoundingClientRect();
      const chip = chosen.getBoundingClientRect();
      if (chip.left < edge.left || chip.right > edge.right) box.scrollLeft += chip.left - edge.left - 16;
    };
    bring();
    // The web font widens the chips when it arrives, which can push the chosen one out again.
    void document.fonts?.ready.then(bring);
    return () => { live = false; };
  }, [value, categories.length]);
  return (
    <div className="find-chips" ref={row} role="group" aria-label="Categories">
      {chipsOf(categories, value).map((c) => (
        <button key={c.id || 'all'} type="button" className="find-chip" aria-pressed={c.id === value} onClick={() => onChange(c.id)}>
          {c.name}{c.count !== undefined && <> <span className="find-chip-count num">{c.count}</span></>}
        </button>
      ))}
    </div>
  );
}

/**
 * A search that found nothing. With a chip chosen it says so, with another filter on it says the filters hid it, and both offer
 * Search all categories, which clears the chip and the filters, shows inactive items too and searches again, whatever was typed;
 * digits are a code or a barcode and never offer Add it; only a name that nothing narrowed offers Add it.
 */
export function NoItem({ term, onClear, category, onSearchAll, filtered = false }: {
  term: string;
  onClear: () => void;
  /** The name of the chosen chip's category, when one is chosen. */
  category?: string;
  /** Clears the chip and every filter, keeping the search. */
  onSearchAll?: () => void;
  /** Another filter narrowed the search: a level, negative only, inactive items only. */
  filtered?: boolean;
}) {
  const why = noItemCase(term, category, filtered);
  const name = term.trim();
  return (
    <div className="empty-state find-none">
      <p>{noItemText(term, why)}</p>
      <p className="cluster">
        {(why.kind === 'category' || why.kind === 'filtered') && onSearchAll && <button type="button" className="rt-primary" onClick={onSearchAll}>Search all categories</button>}
        {why.kind === 'add' && <ScreenLink screen="quickAdd" to="/staff/retail/quick-add" search={{ name }} label={`Add ${name}`} action>Add it</ScreenLink>}
        <button type="button" onClick={onClear}>Clear search</button>
      </p>
    </div>
  );
}

/** An item as one row of at least 56px: its name, the category and the stock under it, the selling price at the right. */
export function ItemRow({ product, onOpen }: { product: Product; onOpen: () => void }) {
  const stock = itemStock(product);
  return (
    <ListRow
      title={<>{product.description}{product.active === false && <span className="badge badge-warning">Deleted</span>}</>}
      meta={[product.category, stock].filter(Boolean).join(' · ')}
      amount={product.sell_minor !== undefined ? <><span className="find-row-word">{WORDS.sellingPrice}</span>{money(product.sell_minor)}</> : undefined}
      onClick={onOpen}
    />
  );
}
