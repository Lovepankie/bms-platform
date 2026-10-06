import { useQuery } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { RETAIL_CURRENCY, retail, type Product } from '../../../api/retail';
import { formatMinor } from '../../../components/money';
import { useStaff } from '../context';
import { showQty } from './maths';
import { canSeeProfit, canUse, type RetailScreen } from './permissions';

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

export function Problem({ error }: { error: unknown }) {
  if (!error) return null;
  return <p role="alert" className="alert alert-danger">{error instanceof Error ? error.message : 'Something went wrong.'}</p>;
}

/** Shows its children only when the session may use the screen; otherwise nothing of the screen. */
export function Gate({ screen, title, children }: { screen: RetailScreen; title: string; children: ReactNode }) {
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
    <main className="rt">
      <h1>{title}</h1>
      {children}
    </main>
  );
}

/** The active branch as one concrete branch, or a prompt to choose one. */
export function useSingleBranch(): { branchId: string | null; branchName: string } {
  const { me, branch } = useStaff();
  const found = (me.branches ?? []).find((b) => b.id === branch);
  return { branchId: found?.id ?? null, branchName: found ? `${found.code ?? ''} ${found.name ?? ''}`.trim() : '' };
}

export function BranchRequired() {
  return <Note>Choose one branch in the Branch box at the top of the page first.</Note>;
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
      <label htmlFor={id}>Find an item by name or code</label>
      <input id={id} type="search" value={search} onChange={(e) => setSearch(e.target.value)} autoComplete="off" />
      {products.isError && <Problem error={products.error} />}
      <ul style={{ listStyle: 'none', padding: 0, maxHeight: 220, overflowY: 'auto' }}>
        {(products.data ?? []).slice(0, 20).map((p) => (
          <li key={p.id} className="rt-card">
            <div className="rt-row">
              <span>
                <strong>{p.description}</strong> ({p.code})
                <br />
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
