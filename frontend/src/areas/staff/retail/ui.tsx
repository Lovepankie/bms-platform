import { useQuery, type QueryClient } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useState, type FocusEvent, type KeyboardEvent, type ReactNode } from 'react';
import { TOAST_MS, useToast as useToastOf } from './toast';
import { Highlight } from '../../../app/ui/SearchBox';
import { ItemCombobox } from '../../../app/ui/ItemCombobox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import type { Me } from '../../../api/client';
import { RETAIL_CURRENCY, RetailError, retail, type BranchRole, type Product } from '../../../api/retail';
import { ALL_BRANCHES, branchLabel } from '../../../auth/branch';
import { formatMinor } from '../../../components/money';
import { BranchOptions, useStockedBranches } from '../branch-picker';
import { useStaff } from '../context';
import { ItemPhoto, shownPhoto } from './item-photo';
import { parseCount, qtyString, showQty } from './maths';
import { packsAndPieces, packsAndPiecesMilli, packsAndPiecesProblem } from './units-state';
import { plural } from '../../../app/glossary';
import { BRANCH_ROLES, branchesWhere, canSeeProfit, canUse, capabilityOn, sellingBranches, type RetailScreen } from './permissions';
import { keptWords } from './orders-state';
import { NoAccess } from '../../../app/ui/EmptyState';
import { icons } from '../../../components/icons';

// Shared pieces of the retail screens. Phone first: one column, 44px tap targets, labels on every
// input, a visible focus ring, and state shown in words as well as colour.

export const money = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY);

/**
 * An amount on the screen, always in tabular figures (docs/ui-rules.md rule 8), so amounts in a list or a statement line up digit
 * by digit. Use it wherever an amount is drawn; money() stays for words built as text (a label, a toast, an aria-label).
 */
export function Money({ minor }: { minor: number }) {
  return <span className="num">{money(minor)}</span>;
}

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
 * What the person who asked sees while a void, a stock correction or an expense waits for an administrator (issue #343, ADR-091
 * decision 9): nothing changed yet, an administrator was told, and the request is on Approvals, where they can see or cancel it.
 * A neutral note, not a success: the action has not happened.
 */
export function WaitingForApproval({ what }: { what: string }) {
  return (
    <div role="status" className="alert alert-info stack" data-testid="approval-waiting">
      <p>
        <strong>Sent for approval.</strong> {what} Nothing changes until an administrator approves it.
      </p>
      <Link to="/staff/approvals" className="link-row">See it, or cancel it, on Approvals</Link>
    </div>
  );
}

/**
 * A short toast above the bottom bar (`.toast`), gone after four seconds. It repeats the on-screen
 * success message and is hidden from assistive technology, which already has that message (#146).
 * Render `toast` anywhere in the screen and call `show(text, 'success')` when a save succeeds, or `'info'` when it only waits
 * for an approval.
 */
export function useToast() {
  return useToastOf(TOAST_MS, true);
}

/** The debounce of the shared search hook (#241), kept here for the screens that import it from this file. */
export { createDebouncer } from '../../../app/ui/useLiveSearch';

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
        <NoAccess />
      </main>
    );
  }
  return (
    <main className={wide ? 'rt rt-wide' : 'rt'}>
      <h1>{title}</h1>
      <ShopHere />
      {children}
    </main>
  );
}

/**
 * The shop the page works in, under its title (issue #680): "Bulb Store", or "All branches". With more than one to choose, a
 * native select laid over it opens the phone's own picker in one tap, as the top bar's title does. JUSTIFICATION-A3: a new
 * component the issue asks for, its options shared with the bar and the menu through BranchOptions.
 */
export function ShopHere() {
  const { me, branch, chooseBranch } = useStaff();
  const active = (me.branches ?? []).find((b) => b.id === branch);
  const choices = chooseBranch ? (me.branches ?? []).length + (me.all_branches ? 1 : 0) : 0;
  const name = active ? branchLabel(active) : branch === ALL_BRANCHES ? 'All branches' : choices > 1 ? 'Choose a shop' : '';
  if (!name) return null;
  return (
    <p className="shop-here" data-testid="shop-here">
      <span className="shop-here-icon" aria-hidden="true">{icons.shop}</span>
      <span className="shop-here-name">{name}</span>
      {choices > 1 && <span className="shop-here-chevron" aria-hidden="true">{icons.chevron}</span>}
      {choices > 1 && (
        <select className="shop-here-select" aria-label={`Shop: ${name}. Switch shop`} value={branch ?? ''} onChange={(e) => chooseBranch?.(e.target.value)}>
          {!active && branch !== ALL_BRANCHES && <option value="">Choose a shop</option>}
          <BranchOptions me={me} />
        </select>
      )}
    </p>
  );
}

/**
 * The roles of the branches (ADR-033): undefined while the capability is off, the session cannot read
 * stock, or the read is on its way, and then every branch behaves as before roles existed.
 */
export function useBranchRoles(me: Pick<Me, 'permissions' | 'module_capabilities'>, wanted = true): BranchRole[] | undefined {
  return useBranchRolesRead(me, wanted).roles;
}

/** As {@link useBranchRoles}, with `loading` true while roles it wants are still being read (a failed read counts as read). */
export function useBranchRolesRead(me: Pick<Me, 'permissions' | 'module_capabilities'>, wanted = true): { roles: BranchRole[] | undefined; loading: boolean } {
  const enabled = wanted && capabilityOn(me, 'retail', BRANCH_ROLES) && (me.permissions ?? []).includes('retail.stock.read');
  const roles = useQuery({ queryKey: ['retail', 'branch-roles'], queryFn: () => retail.branchRoles(), enabled, staleTime: 60_000, retry: 1 });
  return { roles: enabled ? roles.data : undefined, loading: enabled && roles.isPending };
}

/** True unless branch roles are on and every branch the session can sell at is a store. `wanted` false skips the branch roles read. */
export function useCanSellAnywhere(me: Me, wanted = true): boolean {
  const roles = useBranchRoles(me, wanted);
  return sellingBranches(branchesWhere(me, 'retail.sale.create'), roles).length > 0;
}

/** The active branch as one concrete branch, or a prompt to choose one. */
export function useSingleBranch(): { branchId: string | null; branchName: string } {
  const { me, branch } = useStaff();
  const found = (me.branches ?? []).find((b) => b.id === branch);
  return { branchId: found?.id ?? null, branchName: branchLabel(found) };
}

/**
 * As {@link useSingleBranch} for a screen that sells, moves or counts goods: a branch that keeps no goods (a head office, #329) is
 * not a branch to work in, so `branchId` is null there and {@link BranchRequired} says so and offers the branches that keep goods.
 */
export function useStockingBranch(): { branchId: string | null; branchName: string } {
  const { me } = useStaff();
  const one = useSingleBranch();
  const found = (me.branches ?? []).find((b) => b.id === one.branchId);
  return found?.stocks_goods === false ? { branchId: null, branchName: one.branchName } : one;
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

/** True below 720px, where a wide table gives way to one card per row. Shared with the phone side menu (issue #455). */
export { useIsPhone } from '../../../components/side-menu';

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
export function BranchRequired({ permissions, stocking = false }: { permissions: string[]; stocking?: boolean }) {
  const { me, chooseBranch, branch } = useStaff();
  const branches = (me.branches ?? []).filter(
    (b) => b.id && (!stocking || b.stocks_goods !== false) && permissions.every((p) => branchesWhere(me, p).some((x) => x.id === b.id)),
  );
  // The chosen branch keeps no goods (#329): say so before the list.
  const chosen = stocking ? (me.branches ?? []).find((b) => b.id === branch && b.stocks_goods === false) : undefined;
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
        {chosen && <p><strong>{branchLabel(chosen)}</strong> keeps no goods, so nothing is sold, bought in, moved or counted there.</p>}
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
 * sale screen shows the price too; the stock move screen shows the source branch's stock. The matches
 * show only while something is typed and close once one is picked (ItemCombobox, issue #534): there is
 * no browsable list here, the Stock page has the whole list.
 */
export function ProductPicker({ id, branchId, onAdd, showPrice = false, showStock = true, addLabel = 'Add', label = 'Find an item by name, code or category', notFound }: {
  id: string;
  /**
   * Drawn under the box once a search has found nothing, with what was typed (Sell's Add it here, issue #615). `hold` keeps it
   * drawn while a form it opened is open, so a later search that finds something never wipes what was typed (review of #616).
   */
  notFound?: (term: string, clear: () => void, hold: (open: boolean) => void) => ReactNode;
  /** The box's label; a second picker on a screen has its own, so the two are told apart. */
  label?: string;
  /** Absent for a screen that is about an item and not a branch's stock (the price list editor), which also sets showStock false. */
  branchId?: string;
  onAdd: (p: Product) => void;
  showPrice?: boolean;
  showStock?: boolean;
  addLabel?: string;
}) {
  const search = useLiveSearch();
  const products = useQuery({
    queryKey: ['retail', 'products', branchId, search.term],
    queryFn: ({ signal }) => retail.listProducts({ query: search.term, branchId, signal }),
    enabled: search.term !== '',
  });
  const nothing = notFound && search.term !== '' && !search.pending && products.data !== undefined && products.data.length === 0;
  const [held, setHeld] = useState(false);
  return (
    <>
    <ItemCombobox
      id={id}
      label={label}
      search={search}
      // Only the answer to the term in the box: an earlier term's matches are never shown, so never picked.
      results={search.term === '' ? undefined : products.data}
      // Waiting only for a term with no answer yet: a background refresh of a cached answer keeps its options in view.
      fetching={products.isPending}
      error={products.isError ? <Problem error={products.error} /> : undefined}
      optionKey={(p) => p.id ?? p.code ?? ''}
      onPick={onAdd}
      renderOption={(p) => {
        const facts = [p.category, showPrice && p.sell_minor !== undefined ? `${money(p.sell_minor)} each` : null].filter(Boolean).join(', ');
        return (
          <>
            <ItemPhoto photoId={shownPhoto(p)} label={p.description ?? ''} size={40} />
            <span className="cb-text">
              <strong><Highlight text={p.description ?? ''} term={search.term} /></strong> (<Highlight text={p.code ?? ''} term={search.term} />)
              <span className="cb-meta">
                {facts}
                {showStock && (
                  <>
                    {facts && ', '}
                    in stock here:{' '}
                    {p.qty !== undefined && p.qty.startsWith('-') ? <span className="rt-flag">{showQty(p.qty)} (negative)</span> : showQty(p.qty ?? '0')}{' '}
                    {p.unit}
                    {keptWords(p.qty, p.reserved_qty) && <> ({keptWords(p.qty, p.reserved_qty)})</>}
                  </>
                )}
              </span>
            </span>
            <span className="cb-verb" aria-hidden="true">{addLabel}</span>
          </>
        );
      }}
    />
    {notFound && (nothing || held) && notFound(search.term, search.clear, setHeld)}
    </>
  );
}

/**
 * Issue #650: the quantity of an item with a standard pack on Count stock and Move stock, typed as whole packs and the pieces
 * left, or as pieces alone, with the total in pieces under it before anything is sent. Each box has its own label and is
 * named with the item (`nameId`); the pieces box takes `id`, so a scan, a warning and the cursor find it as before.
 */
export function PacksAndPiecesFields({ id, nameId, unit, pack, packs, pieces, onPacks, onPieces, onBlur, onPiecesKeyDown, totalWord, after, invalid = false }: {
  id: string;
  nameId: string;
  unit: string;
  pack: { unit: string; factor?: string };
  packs: string;
  pieces: string;
  onPacks: (text: string) => void;
  onPieces: (text: string) => void;
  onBlur?: () => void;
  onPiecesKeyDown?: (e: KeyboardEvent<HTMLInputElement>) => void;
  /** "Counted" or "Moving", before the total. */
  totalWord: string;
  /** Shown at the end of the row, such as the 0 button of a count. */
  after?: ReactNode;
  /** The quantity is refused for a reason of its own, such as more than the branch holds: both boxes are marked. */
  invalid?: boolean;
}) {
  const problem = packsAndPiecesProblem(packs, pieces, unit, pack.unit);
  const total = problem === null ? packsAndPiecesMilli(packs, pieces, pack) : null;
  // Going from one box to the other is not leaving the quantity: a count line moves to Counted items only once both are done.
  const leave = (other: string) => (e: FocusEvent<HTMLInputElement>) => { if (e.relatedTarget?.id !== other) onBlur?.(); };
  return (
    <>
      <div className="count-entry count-packs">
        <span className="count-part">
          <label id={`${id}-packs-label`} htmlFor={`${id}-packs`}>{plural(pack.unit)}</label>
          <input id={`${id}-packs`} inputMode="numeric" value={packs} aria-labelledby={`${nameId} ${id}-packs-label`}
            aria-invalid={invalid || (packs.trim() !== '' && !/^\d+$/.test(packs.trim()))} onChange={(e) => onPacks(e.target.value)} onBlur={leave(id)}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); document.getElementById(id)?.focus(); } }} />
        </span>
        <span className="count-part">
          <label id={`${id}-label`} htmlFor={id}>{plural(unit)}</label>
          <input id={id} inputMode="decimal" value={pieces} aria-labelledby={`${nameId} ${id}-label`} aria-describedby={`${id}-total`}
            aria-invalid={invalid || (pieces.trim() !== '' && parseCount(pieces) === null)} onChange={(e) => onPieces(e.target.value)} onBlur={leave(`${id}-packs`)}
            onKeyDown={onPiecesKeyDown} />
        </span>
        {after}
      </div>
      <p id={`${id}-total`} className="hint count-total" aria-live="polite">
        {total !== null ? `${totalWord} ${packsAndPieces(qtyString(total), unit, pack)}` : `Type the ${plural(pack.unit)} and the ${plural(unit)} left, or all of it in ${plural(unit)}.`}
      </p>
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
    </>
  );
}
