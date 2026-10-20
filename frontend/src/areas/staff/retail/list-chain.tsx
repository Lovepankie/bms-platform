import { keepPreviousData, useInfiniteQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { shutGroup, toggleGroup, trailOf, type OpenGroups } from './sales-chain-state';
import {
  addTo, dropFrom, isTime, moveIn, pathOf, readChainOf, readView, selectIn, writeChainOf, writeView,
  type ListName, type ListSelection, type ListView,
} from './list-chain-state';
import { Problem } from './ui';

// The Group by control, the grouped view switch and the tree of groups shared by the stock list and the restocks list (issue #350),
// the same shape as the sales lists' (sales-chain.tsx, sales-group-by.tsx; issue #341). Each level is one request that carries what
// was opened above it as filters; every header is a figure the server summed. What is open lives in the screen, so coming back from
// a restock opened for correction finds the same groups open.

/** The view the person chose and the chain, remembered per user and list; changing either closes everything. */
export function useListView<L extends string>(user: string, list: ListName, order: readonly L[], known: readonly L[] = order) {
  const [view, setViewState] = useState<ListView>(() => readView(user, list));
  const [chain, setChainState] = useState<L[]>(() => readChainOf(user, list, order, known));
  const [open, setOpen] = useState<OpenGroups>(new Map());
  return {
    view,
    setView: (next: ListView) => { setViewState(next); writeView(user, list, next); },
    chain,
    setChain: (next: L[]) => { setChainState(next); writeChainOf(user, list, next); setOpen(new Map()); },
    open,
    toggle: (key: string, label: string) => setOpen((o) => toggleGroup(o, key, label)),
    shut: (key: string) => setOpen((o) => shutGroup(o, key)),
    shutAll: () => setOpen(new Map()),
  };
}
export type ListViewState<L extends string> = ReturnType<typeof useListView<L>>;

/** "Show: Flat list or Grouped", so the list people know stays the way it is until they choose to group it. */
export function ViewSwitch({ view, onChange }: { view: ListView; onChange: (view: ListView) => void }) {
  return (
    <div className="cluster filters" role="group" aria-label="View" data-tour="list-view">
      <button type="button" className="btn-sm" aria-pressed={view === 'flat'} onClick={() => onChange('flat')}>Flat list</button>
      <button type="button" className="btn-sm" aria-pressed={view === 'grouped'} onClick={() => onChange('grouped')}>Grouped</button>
    </div>
  );
}

export function ListGroupBy<L extends string>({ chain, order, names, onChange }: { chain: L[]; order: readonly L[]; names: Record<L, string>; onChange: (next: L[]) => void }) {
  const out = order.filter((l) => !chain.includes(l));
  const usual = chain.length === order.length && chain.every((l, i) => l === order[i]);
  return (
    <details className="rt-groupby" data-tour="list-group-by">
      <summary>
        <strong>Group by</strong>{' '}
        <span className="rt-groupby-now">{chain.map((l) => names[l]).join(', ')}</span>
      </summary>
      <ol className="rt-groupby-list">
        {chain.map((level, i) => (
          <li key={level}>
            <span className="rt-groupby-name">{names[level]}</span>
            <button type="button" aria-label={`Move ${names[level]} up`} disabled={i === 0} onClick={() => onChange(moveIn(chain, level, -1))}>Up</button>
            <button type="button" aria-label={`Move ${names[level]} down`} disabled={i === chain.length - 1} onClick={() => onChange(moveIn(chain, level, 1))}>Down</button>
            <button type="button" aria-label={`Remove ${names[level]}`} disabled={chain.length === 1} onClick={() => onChange(dropFrom(chain, level))}>Remove</button>
          </li>
        ))}
      </ol>
      {out.length > 0 && (
        <p className="rt-groupby-add">
          Not used:{' '}
          {out.map((level) => (
            <button key={level} type="button" aria-label={`Add ${names[level]}`} onClick={() => onChange(addTo(chain, level, order))}>{names[level]}</button>
          ))}
        </p>
      )}
      {!usual && <button type="button" onClick={() => onChange([...order])}>Use the usual order</button>}
    </details>
  );
}

/** A group as the tree needs it. */
export interface TreeGroup {
  key?: string;
  label?: string;
  from?: string;
  to?: string;
}

interface Page<G> {
  items?: G[];
  has_more?: boolean;
  next_to?: string;
  next_offset?: number;
}

/** What differs between the lists: the request, the words and the figures of a header. */
export interface TreeKind<L extends string, G extends TreeGroup> {
  /** The first part of the query key, so each list caches its own groups. */
  name: string;
  plurals: Record<L, string>;
  fetch: (level: L, chain: L[], sel: ListSelection, page: { to?: string; offset?: number }, signal?: AbortSignal) => Promise<Page<G>>;
  title: (level: L, group: G) => string;
  figures: (level: L, group: G, sel: ListSelection) => ReactNode;
  /** The words for a level with nothing in it. */
  nothing: string;
}

interface Shared<L extends string, G extends TreeGroup> {
  kind: TreeKind<L, G>;
  /** The list's own filters: part of the query key, passed to every level by the kind's fetch. */
  filterKey: unknown;
  chain: L[];
  enabled: boolean;
  view: ListViewState<L>;
  /** The rows of a group (the stock items, the restocks), drawn with the screen's own components. */
  renderRows: (sel: ListSelection) => ReactNode;
  /** Whether a group that opens onto more groups can also show its rows, and what the button calls them. */
  offersRows?: (index: number) => boolean;
  rowsWord?: string;
}

/** The groups of one level under a selection. Callers with the same arguments share one request. */
function useLevel<L extends string, G extends TreeGroup>({ kind, filterKey, chain, enabled }: Shared<L, G>, level: L, sel: ListSelection) {
  const time = isTime(level);
  const chosen = Object.fromEntries(Object.entries(sel).filter(([, v]) => v !== undefined)) as ListSelection;
  const query = useInfiniteQuery({
    queryKey: ['retail', kind.name, 'summary', level, chain, filterKey, chosen],
    queryFn: ({ pageParam, signal }) => kind.fetch(level, chain, chosen, time ? { to: typeof pageParam === 'string' ? pageParam : chosen.to } : { offset: typeof pageParam === 'number' ? pageParam : 0 }, signal),
    initialPageParam: undefined as string | number | undefined,
    getNextPageParam: (last) => (last.has_more ? (time ? last.next_to : last.next_offset) : undefined),
    enabled,
    placeholderData: keepPreviousData,
  });
  // A group can come twice when pages are cut by an order that moves between requests: the first one stands.
  const seen = new Set<string>();
  const items = (query.data?.pages ?? []).flatMap((p) => p.items ?? []).filter((g) => {
    const key = g.key ?? '';
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  return { query, items };
}

export function ListChain<L extends string, G extends TreeGroup>({ range, rootLabel, empty, ...shared }: Shared<L, G> & { range: ListSelection; rootLabel: string; empty?: ReactNode }) {
  const { view } = shared;
  const crumbs = trailOf(view.open);
  return (
    <div data-tour="list-groups" className="rt-groups">
      {crumbs.length > 0 && (
        <nav aria-label="Where you are" className="rt-group-trail">
          <ol>
            <li><button type="button" className="btn-link" onClick={view.shutAll}>{rootLabel}</button></li>
            {crumbs.map((c, i) => {
              const next = crumbs[i + 1];
              return (
                <li key={c.key}>
                  {next ? <button type="button" className="btn-link" onClick={() => view.shut(next.key)}>{c.label}</button> : <span aria-current="page">{c.label}</span>}
                </li>
              );
            })}
          </ol>
        </nav>
      )}
      <LevelList<L, G> index={0} sel={range} parent="" empty={empty} {...shared} />
    </div>
  );
}

function LevelList<L extends string, G extends TreeGroup>({ index, sel, parent, empty, ...shared }: Shared<L, G> & { index: number; sel: ListSelection; parent: string; empty?: ReactNode }) {
  const { kind, chain } = shared;
  const level = chain[index] as L;
  const { query, items } = useLevel(shared, level, sel);
  if (query.isPending && query.fetchStatus !== 'idle') return <p className="loading">Loading</p>;
  if (query.error) {
    return (
      <>
        <Problem error={query.error} />
        <button type="button" onClick={() => void query.refetch()}>Try again</button>
      </>
    );
  }
  if (query.data && items.length === 0) return <>{empty ?? <p className="empty-state">{kind.nothing}</p>}</>;
  return (
    <>
      <ul className="rt-group-list">
        {items.map((g) => <GroupRow<L, G> key={g.key} index={index} level={level} group={g} sel={sel} parent={parent} {...shared} />)}
      </ul>
      {query.hasNextPage && (
        <button type="button" disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>
          {query.isFetchingNextPage ? 'Loading' : `Show ${isTime(level) ? 'earlier' : 'more'} ${kind.plurals[level]}`}
        </button>
      )}
    </>
  );
}

function GroupRow<L extends string, G extends TreeGroup>({ index, level, group, sel, parent, ...shared }: Shared<L, G> & { index: number; level: L; group: G; sel: ListSelection; parent: string }) {
  const { kind, chain, view, renderRows, offersRows, rowsWord } = shared;
  const key = group.key ?? '';
  const path = pathOf(parent, level, key);
  const title = kind.title(level, group);
  const last = index === chain.length - 1;
  const bodyId = `group-${path.replace(/[^a-zA-Z0-9_-]/g, '-')}`;
  const isOpen = view.open.has(path);
  const here = selectIn(sel, level, { key, from: group.from, to: group.to });
  return (
    <li className="rt-group">
      {/* No aria-label: the visible words are the name, so the figures are read too. */}
      <button type="button" className="rt-group-head" aria-expanded={isOpen} aria-controls={isOpen ? bodyId : undefined} onClick={() => view.toggle(path, title)}>
        <span className="rt-group-title">{title}</span>{' '}
        {kind.figures(level, group, sel)}
      </button>
      {isOpen && (
        <div className="rt-group-body" id={bodyId}>
          {last ? (
            renderRows(here)
          ) : (
            <>
              <LevelList<L, G> index={index + 1} sel={here} parent={path} {...shared} />
              {offersRows?.(index) && <RowsButton word={rowsWord ?? 'rows'} sel={here} renderRows={renderRows} />}
            </>
          )}
        </div>
      )}
    </li>
  );
}

/** A group that opens onto more groups can still show its rows: on request, so the groups stay the first thing seen. */
function RowsButton({ word, sel, renderRows }: { word: string; sel: ListSelection; renderRows: (sel: ListSelection) => ReactNode }) {
  const [shown, setShown] = useState(false);
  return (
    <div className="rt-day-sales">
      <button type="button" aria-expanded={shown} onClick={() => setShown(!shown)}>{shown ? `Hide the ${word} here` : `Show the ${word} here`}</button>
      {shown && renderRows(sel)}
    </div>
  );
}
