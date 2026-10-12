import { Link, useRouter, useRouterState } from '@tanstack/react-router';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';

// Breadcrumbs for the staff area. The trail is not a second map of the app: every staff route carries
// a `crumb` label in its static data (app/router.tsx) and the trail is the routes whose path is a
// prefix of the current one, so a new route gets its place by declaring a label. A screen that has
// loaded an entity names the page with useCrumbLabel (a routed detail page) or adds a last crumb with
// useCrumbTail (a detail view inside a screen). Without a provider both hooks do nothing, and the
// label stays the route's generic one, so no request is made for a name.

export type Crumb = { label: string; to?: string; onSelect?: () => void };

const fill = (pattern: string, params: Record<string, string>) =>
  pattern.replace(/\$(\w+)/g, (_, name: string) => encodeURIComponent(params[name] ?? ''));

/** The routes on the way to `fullPath` that have a label, in order, each with its address. */
export function trailFor(fullPath: string, params: Record<string, string>, labelAt: (pattern: string) => string | undefined): Crumb[] {
  const segments = fullPath.split('/').filter(Boolean);
  const trail: Crumb[] = [];
  for (let i = 1; i <= segments.length; i++) {
    const pattern = `/${segments.slice(0, i).join('/')}`;
    const label = labelAt(pattern);
    if (label) trail.push({ label, to: fill(pattern, params) });
  }
  return trail;
}

/** The page's own label replaced by the entity's name, and a detail view's crumb added after it. */
export function withExtras(trail: Crumb[], extras: Extras): Crumb[] {
  const items = trail.map((c) => ({ ...c }));
  const last = items[items.length - 1];
  if (last && extras.label) last.label = extras.label;
  if (extras.tail) {
    if (last && extras.tail.onBack) {
      last.onSelect = extras.tail.onBack;
      delete last.to;
    }
    items.push({ label: extras.tail.label });
  }
  return items;
}

type Extras = { label?: string; tail?: { label: string; onBack?: () => void } };
const CrumbContext = createContext<{ extras: Extras; patch: (p: Extras) => void } | null>(null);

/** Holds what the open screen says about its crumbs; drawn once by the staff layout. */
export function CrumbProvider({ children }: { children: ReactNode }) {
  const [extras, setExtras] = useState<Extras>({});
  const patch = useCallback((p: Extras) => setExtras((prev) => ({ ...prev, ...p })), []);
  const value = useMemo(() => ({ extras, patch }), [extras, patch]);
  return <CrumbContext.Provider value={value}>{children}</CrumbContext.Provider>;
}

/** Name this page's crumb after the entity it shows, once the screen has loaded it. */
export function useCrumbLabel(label: string | undefined) {
  const patch = useContext(CrumbContext)?.patch;
  useEffect(() => {
    if (!patch || !label) return;
    patch({ label });
    return () => patch({ label: undefined });
  }, [patch, label]);
}

/** Add a last crumb for a detail view shown inside the screen; `onBack` makes the screen's own crumb a way back to its list. */
export function useCrumbTail(label: string | undefined, onBack?: () => void) {
  const patch = useContext(CrumbContext)?.patch;
  const back = useRef(onBack);
  back.current = onBack;
  const goes = onBack !== undefined;
  useEffect(() => {
    if (!patch || !label) return;
    patch({ tail: { label, onBack: goes ? () => back.current?.() : undefined } });
    return () => patch({ tail: undefined });
  }, [patch, label, goes]);
}

/** The list itself: a nav with an ordered list, the last item the current page. Wide screens show every crumb; a phone shows Back to the parent and the current page (CSS). */
export function Breadcrumbs({ items }: { items: Crumb[] }) {
  if (items.length < 2) return null;
  const parent = items.length - 2;
  return (
    <nav aria-label="Breadcrumb" className="breadcrumbs">
      <ol>
        {items.map((c, i) => {
          const current = i === items.length - 1;
          const prefix = i === parent ? <span className="crumb-back">Back to </span> : null;
          return (
            <li key={`${i}-${c.label}`} className={i === parent ? 'crumb-parent' : undefined}>
              {current ? (
                <span aria-current="page" title={c.label}>{c.label}</span>
              ) : c.onSelect ? (
                <button type="button" className="crumb-link" onClick={c.onSelect} title={c.label}>{prefix}<span className="crumb-text">{c.label}</span></button>
              ) : (
                <Link to={(c.to ?? '/staff') as never} activeOptions={{ exact: true }} title={c.label}>{prefix}<span className="crumb-text">{c.label}</span></Link>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}

/** The trail of the open staff screen, read from the router's route table. */
export function StaffBreadcrumbs() {
  const router = useRouter();
  const leaf = useRouterState({ select: (s) => s.matches[s.matches.length - 1] });
  const extras = useContext(CrumbContext)?.extras ?? {};
  const items = useMemo(() => {
    if (!leaf) return [];
    const route = (router.routesById as Record<string, { fullPath: string } | undefined>)[leaf.routeId];
    const byId = router.routesById as unknown as Record<string, { options: { staticData?: { crumb?: string } } } | undefined>;
    const full = (route?.fullPath ?? leaf.pathname).replace(/\/+$/, '') || '/';
    return withExtras(trailFor(full, leaf.params as Record<string, string>, (p) => byId[p]?.options.staticData?.crumb), extras);
  }, [router, leaf, extras]);
  return <Breadcrumbs items={items} />;
}
