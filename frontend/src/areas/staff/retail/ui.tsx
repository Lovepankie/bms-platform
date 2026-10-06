import { useQuery } from '@tanstack/react-query';
import { useState, type CSSProperties, type ReactNode } from 'react';
import { RETAIL_CURRENCY, retail, type Product } from '../../../api/retail';
import { formatMinor } from '../../../components/money';
import { useStaff } from '../context';
import { showQty } from './maths';
import { canSeeProfit, canUse, type RetailScreen } from './permissions';

// Shared pieces of the retail screens. Phone first: one column, 44px tap targets, labels on every
// input, a visible focus ring, and state shown in words as well as colour.

export const money = (minor: number): string => formatMinor(minor, RETAIL_CURRENCY);

const CSS = `
.rt { max-width: 640px; margin: 0 auto; }
.rt label, .rt legend { display: block; font-weight: 600; margin: 12px 0 4px; }
.rt input, .rt select, .rt textarea { width: 100%; box-sizing: border-box; min-height: 44px; font-size: 16px; padding: 8px; }
.rt button, .rt a.rt-tile { min-height: 44px; font-size: 16px; padding: 8px 14px; cursor: pointer; }
.rt button:disabled { cursor: not-allowed; opacity: .6; }
.rt button:focus-visible, .rt input:focus-visible, .rt select:focus-visible, .rt textarea:focus-visible, .rt a:focus-visible {
  outline: 3px solid #0d5c75; outline-offset: 2px; }
.rt fieldset { border: 1px solid #999; border-radius: 6px; margin: 12px 0; padding: 8px; }
.rt .rt-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: end; }
.rt .rt-row > * { flex: 1 1 120px; }
.rt .rt-card { border: 1px solid #bbb; border-radius: 6px; padding: 8px; margin: 8px 0; }
.rt .rt-total { font-size: 20px; font-weight: 700; margin: 12px 0; }
.rt .rt-flag { font-weight: 700; color: #a40000; }
.rt .rt-flag::before { content: "\\26A0 "; }
.rt .rt-primary { background: #0d5c75; color: #fff; border: 0; border-radius: 6px; width: 100%; }
.rt table { width: 100%; border-collapse: collapse; }
.rt th, .rt td { text-align: left; padding: 6px 4px; border-bottom: 1px solid #ddd; }
.rt td.num, .rt th.num { text-align: right; }
`;

export function RetailStyles() {
  return <style>{CSS}</style>;
}

const noteStyle: CSSProperties = { border: '1px solid #0d5c75', borderRadius: 6, padding: 8, margin: '8px 0' };

export function Note({ children }: { children: ReactNode }) {
  return (
    <p role="note" style={noteStyle}>
      {children}
    </p>
  );
}

export function Problem({ error }: { error: unknown }) {
  if (!error) return null;
  return <p role="alert" className="rt-flag">{error instanceof Error ? error.message : 'Something went wrong.'}</p>;
}

/** Shows its children only when the session may use the screen; otherwise nothing of the screen. */
export function Gate({ screen, title, children }: { screen: RetailScreen; title: string; children: ReactNode }) {
  const { me } = useStaff();
  if (!canUse(me, screen)) {
    return (
      <main className="rt">
        <RetailStyles />
        <h1>{title}</h1>
        <p>You do not have access to this page.</p>
      </main>
    );
  }
  return (
    <main className="rt">
      <RetailStyles />
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
