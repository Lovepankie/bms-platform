import { useQuery, type QueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { RETAIL_CURRENCY, RetailError, retail, type Product } from '../../../api/retail';
import { ALL_BRANCHES, branchLabel } from '../../../auth/branch';
import { formatMinor } from '../../../components/money';
import { useStockedBranches } from '../branch-picker';
import { useStaff } from '../context';
import { showQty } from './maths';
import { branchesWhere, canSeeProfit, canUse, type RetailScreen } from './permissions';

// Shared pieces of the retail screens. Phone first: one column, 44px tap targets, labels on every
// input, a visible focus ring, and state shown in words as well as colour.

export const money = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY);

// The look lives in src/app/ui/retail.css with the shared design tokens (#95). The screens used to
// carry an inline <style> here, which the production CSP (style-src 'self') refuses; this stays as
// a no-op so a screen that still renders it keeps compiling.
export function RetailStyles() {
  return null;
}

export function Note({ children }: { children: ReactNode }) {
  return (
    <p role="note" className="alert alert-info">
      {children}
    </p>
  );
}

/** A success message on the screen (read by assistive technology); the toast of {@link useToast} repeats it for a few seconds. */
export function Success({ children }: { children: ReactNode }) {
  return <p role="status" className="alert alert-success">{children}</p>;
}

/**
 * A short toast above the bottom bar (`.toast`), gone after four seconds. It repeats the on-screen
 * success message and is hidden from assistive technology, which already has that message (#146).
 * Render `toast` anywhere in the screen and call `show` when a save succeeds.
 */
export function useToast(): { show: (message: string) => void; toast: ReactNode } {
  const [text, setText] = useState<string | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);
  const show = (message: string) => {
    setText(message);
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setText(null), 4000);
  };
  return { show, toast: text ? <p className="toast" aria-hidden="true">{text}</p> : null };
}

/** Passes the latest pushed value on once no newer one has arrived for `ms`. */
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

/** The value after it has stopped changing for `ms`; the input that feeds it stays responsive. */
export function useDebounced<T>(value: T, ms = 300): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const debouncer = createDebouncer(ms, setSettled);
    debouncer.push(value);
    return debouncer.cancel;
  }, [value, ms]);
  return settled;
}

export const STALE_TEXT = 'This was changed by someone else. The list has been reloaded; please try again.';

/** A row with no version cannot be changed safely: that is a bug in the screen, so say so rather than guess. */
export function requireVersion(version: number | undefined): number {
  if (version === undefined) {
    throw new Error('This row has no version, so it cannot be changed safely. Reload the page and try again.');
  }
  return version;
}

/** Words for a failed change. A stale version also reloads the lists, so the retry carries the new version. */
export function changeFailureText(error: unknown, queryClient: QueryClient): string {
  if (error instanceof RetailError && error.code === 'version_conflict') {
    void queryClient.invalidateQueries({ queryKey: ['retail'] });
    return STALE_TEXT;
  }
  return error instanceof Error ? error.message : 'Something went wrong.';
}

export function Problem({ error }: { error: unknown }) {
  if (!error) return null;
  return <p role="alert" className="alert alert-danger">{error instanceof Error ? error.message : 'Something went wrong.'}</p>;
}

/** Shows its children only when the session may use the screen; otherwise nothing of the screen. */
export function Gate({ screen, title, children, wide }: { screen: RetailScreen; title: string; children: ReactNode; wide?: boolean }) {
  const { me } = useStaff();
  if (!canUse(me, screen)) {
    return (
      <main className="rt">
        <h1>{title}</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  return (
    <main className={wide ? 'rt rt-wide' : 'rt'}>
      <h1>{title}</h1>
      {children}
    </main>
  );
}

/** The active branch as one concrete branch, or a prompt to choose one. */
export function useSingleBranch(): { branchId: string | null; branchName: string } {
  const { me, branch } = useStaff();
  const found = (me.branches ?? []).find((b) => b.id === branch);
  return { branchId: found?.id ?? null, branchName: branchLabel(found) };
}

/**
 * The branch choice of a read screen: one concrete branch, or "All branches" (#144). The write screens
 * keep using {@link useSingleBranch}.
 */
export function useBranchView(): { all: boolean; branchId: string | null; branchName: string } {
  const { branch } = useStaff();
  const one = useSingleBranch();
  return { all: branch === ALL_BRANCHES, ...one };
}

/** The name of a branch of the session, for a branch id the server sent. */
export function useBranchName(): (id: string | undefined) => string {
  const { me } = useStaff();
  return (id) => branchLabel((me.branches ?? []).find((b) => b.id === id));
}

const PHONE = '(max-width: 719px)';
const subscribePhone = (notify: () => void) => {
  if (typeof window === 'undefined' || !window.matchMedia) return () => undefined;
  const query = window.matchMedia(PHONE);
  query.addEventListener('change', notify);
  return () => query.removeEventListener('change', notify);
};

/** True below 720px, where a wide table gives way to one card per row. False when rendered without a window. */
export function useIsPhone(): boolean {
  return useSyncExternalStore(
    subscribePhone,
    () => typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia(PHONE).matches,
    () => false,
  );
}

/**
 * Says plainly when the chosen branch holds no stock (#103), with a button for each of the user's
 * branches that does. Shows nothing while that is unknown or for a session without a stock read.
 */
export function NoStockHere({ branchId }: { branchId: string }) {
  const { me, chooseBranch } = useStaff();
  const stocked = useStockedBranches(me);
  if (!stocked.data || stocked.data.includes(branchId)) return null;
  const others = (me.branches ?? []).filter((b) => b.id && stocked.data.includes(b.id));
  return (
    <div role="status" className="alert alert-warning">
      <div className="stack">
        <p>This branch holds no stock. Switch branch?</p>
        {chooseBranch && others.length > 0 && (
          <p className="cluster">
            {others.map((b) => (
              <button key={b.id} type="button" className="btn-sm" onClick={() => chooseBranch(b.id ?? '')}>
                {branchLabel(b)}
              </button>
            ))}
          </p>
        )}
      </div>
    </div>
  );
}

/** The product's category as a small grey label under its name; nothing when the product has none. */
export function CategoryLabel({ category }: { category?: string }) {
  return category ? <span className="hint">{category}</span> : null;
}

/**
 * A screen that writes works on one branch. With "All branches" chosen it offers each of the user's
 * branches where the screen's `permissions` are all held as a button instead of pointing at the Branch
 * box (#144). With no such branch it says so.
 */
export function BranchRequired({ permissions }: { permissions: string[] }) {
  const { me, chooseBranch } = useStaff();
  const branches = (me.branches ?? []).filter(
    (b) => b.id && permissions.every((p) => branchesWhere(me, p).some((x) => x.id === b.id)),
  );
  if (branches.length === 0) {
    return (
      <div role="note" className="alert alert-info">
        <p>You have no branch where you can do this. Ask an administrator for access.</p>
      </div>
    );
  }
  return (
    <div role="note" className="alert alert-info">
      <div className="stack">
        <p>Choose a branch to continue:</p>
        <p className="cluster">
          {branches.map((b) => (
            <button key={b.id} type="button" className="btn-sm" disabled={!chooseBranch} onClick={() => chooseBranch?.(b.id ?? '')}>
              {branchLabel(b)}
            </button>
          ))}
        </p>
      </div>
    </div>
  );
}

export function useProfitAccess(): boolean {
  return canSeeProfit(useStaff().me);
}

/**
 * Search the catalogue and add an item, each with what `branchId` holds (negative flagged). The
 * sale screen shows the selling price too; the stock move screen shows the source branch's stock.
 */
export function ProductPicker({ id, branchId, onAdd, showPrice = false }: {
  id: string;
  branchId: string;
  onAdd: (p: Product) => void;
  showPrice?: boolean;
}) {
  const [search, setSearch] = useState('');
  const products = useQuery({
    queryKey: ['retail', 'products', branchId, search],
    queryFn: () => retail.listProducts({ query: search, branchId }),
  });
  return (
    <>
      <label htmlFor={id}>Find an item by name, code or category</label>
      <input id={id} type="search" value={search} onChange={(e) => setSearch(e.target.value)} autoComplete="off" />
      {products.isError && <Problem error={products.error} />}
      <ul style={{ listStyle: 'none', padding: 0, maxHeight: 220, overflowY: 'auto' }}>
        {(products.data ?? []).slice(0, 20).map((p) => (
          <li key={p.id} className="rt-card">
            <div className="rt-row">
              <span>
                <strong>{p.description}</strong> ({p.code})
                <br />
                <CategoryLabel category={p.category} />
                {p.category && <br />}
                {showPrice && `${money(p.sell_minor ?? 0)} each, `}in stock here:{' '}
                {p.qty !== undefined && p.qty.startsWith('-') ? <span className="rt-flag">{showQty(p.qty)} (negative)</span> : showQty(p.qty ?? '0')}{' '}
                {p.unit}
              </span>
              <button type="button" onClick={() => onAdd(p)} aria-label={`Add ${p.description}`}>
                Add
              </button>
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}
