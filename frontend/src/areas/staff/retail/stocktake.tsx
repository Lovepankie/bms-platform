import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type Stocktake, type StocktakeLine } from '../../../api/retail';
import { milliOf, parseCount, showQty } from './maths';
import { BranchRequired, CategoryLabel, Gate, Note, Problem, useSingleBranch } from './ui';
import { needsOf } from './permissions';

// Stock-take (FR-RET-08): count the products of a branch, review the variance the server works
// out, then commit to write the adjustments. Only counted rows are sent.

/** The variance written at commit once committed, otherwise the draft's (counted less expected). */
const variance = (l: StocktakeLine): string => l.committed_variance_qty ?? l.variance_qty ?? '0';

export function StocktakeReview({ stocktake, onCommit, committing }: { stocktake: Stocktake; onCommit?: () => void; committing?: boolean }) {
  const changed = (stocktake.lines ?? []).filter((l) => milliOf(variance(l)) !== 0);
  return (
    <section aria-label="Stock-take review">
      <h2>{stocktake.status === 'committed' ? 'Stock-take committed' : 'Review the variance'}</h2>
      {changed.length === 0 ? (
        <p>Every counted item matches the system.</p>
      ) : (
        <div className="table-wrap" tabIndex={0}><table>
          <thead>
            <tr>
              <th>Item</th>
              <th className="num">System</th>
              <th className="num">Counted</th>
              <th className="num">Difference</th>
            </tr>
          </thead>
          <tbody>
            {changed.map((l) => {
              const v = milliOf(variance(l));
              return (
                <tr key={l.product_id}>
                  <td>{l.description ?? l.product_id}</td>
                  <td className="num">{showQty(l.expected_qty ?? '0')}</td>
                  <td className="num">{showQty(l.counted_qty ?? '0')}</td>
                  <td className="num">
                    <strong>{v > 0 ? '+' : ''}{showQty(variance(l))}</strong> {v > 0 ? '(more)' : '(less)'}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table></div>
      )}
      {stocktake.status === 'draft' && onCommit && (
        <button type="button" className="rt-primary" disabled={committing} onClick={onCommit}>
          {committing ? 'Saving' : 'Commit the count'}
        </button>
      )}
    </section>
  );
}

function CountSheet({ branchId }: { branchId: string }) {
  const queryClient = useQueryClient();
  const [query, setQuery] = useState('');
  const [counts, setCounts] = useState<Record<string, string>>({});
  const [draft, setDraft] = useState<Stocktake | null>(null);
  const stock = useQuery({ queryKey: ['retail', 'stock', branchId, '', false], queryFn: () => retail.listStock({ branchId }) });

  const entered = Object.entries(counts).filter(([, v]) => v.trim() !== '');
  const bad = entered.some(([, v]) => parseCount(v) === null);
  const review = useMutation({
    mutationFn: () => retail.createStocktake({ branch_id: branchId, lines: entered.map(([productId, countedQty]) => ({ product_id: productId, counted_qty: countedQty.trim() })) }),
    onSuccess: setDraft,
  });
  const commit = useMutation({
    mutationFn: () => retail.commitStocktake(draft?.id ?? ''),
    onSuccess: (st) => {
      setDraft(st);
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
  });

  if (draft) {
    return (
      <>
        <StocktakeReview stocktake={draft} onCommit={() => commit.mutate()} committing={commit.isPending} />
        <Problem error={commit.error} />
        {draft.status === 'draft' && <button type="button" onClick={() => setDraft(null)}>Back to the count</button>}
        {draft.status === 'committed' && <button type="button" onClick={() => { setDraft(null); setCounts({}); }}>Start another count</button>}
      </>
    );
  }
  const rows = (stock.data ?? []).filter((r) => !query.trim() || `${r.description ?? ''} ${r.code ?? ''} ${r.category ?? ''}`.toLowerCase().includes(query.trim().toLowerCase()));
  return (
    <>
      <Note>Type the counted quantity for each item you counted. Leave an item blank to skip it.</Note>
      <label htmlFor="count-search">Search by name, code or category</label>
      <input id="count-search" type="search" value={query} onChange={(e) => setQuery(e.target.value)} autoComplete="off" />
      {stock.isPending && <p className="loading">Loading</p>}
      <Problem error={stock.error} />
      {rows.map((r) => {
        const id = r.product_id ?? '';
        return (
          <div key={id} className="rt-card">
            <label htmlFor={`count-${id}`}>{r.description} ({r.unit})</label>
            <CategoryLabel category={r.category} />
            <input id={`count-${id}`} inputMode="decimal" value={counts[id] ?? ''} placeholder="Counted"
              aria-invalid={(counts[id] ?? '').trim() !== '' && parseCount(counts[id] ?? '') === null}
              onChange={(e) => setCounts({ ...counts, [id]: e.target.value })} />
          </div>
        );
      })}
      {bad && <p role="alert" className="rt-flag">Some counts are not valid quantities.</p>}
      <Problem error={review.error} />
      <button type="button" className="rt-primary" disabled={entered.length === 0 || bad || review.isPending} onClick={() => review.mutate()}>
        {review.isPending ? 'Working out' : `Review variance (${entered.length} counted)`}
      </button>
    </>
  );
}

function StocktakePage() {
  const { branchId, branchName } = useSingleBranch();
  return (
    <Gate screen="stocktake" title="Stock-take">
      {branchId === null ? (
        <BranchRequired permissions={needsOf('stocktake')} />
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{branchName}</strong></p>
          <CountSheet key={branchId} branchId={branchId} />
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stocktake')({ component: StocktakePage });
