import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type Stocktake } from '../../../api/retail';
import { milliOf, parseCount, showQty } from './maths';
import { BranchRequired, Gate, Note, Problem, useSingleBranch } from './ui';

// Stock-take (FR-RET-08): count the products of a branch, review the variance the server works
// out, then commit to write the adjustments. Only counted rows are sent.

export function StocktakeReview({ stocktake, onCommit, committing }: { stocktake: Stocktake; onCommit?: () => void; committing?: boolean }) {
  const changed = stocktake.lines.filter((l) => milliOf(l.varianceQty) !== 0);
  return (
    <section aria-label="Stock-take review">
      <h2>{stocktake.status === 'committed' ? 'Stock-take committed' : 'Review the variance'}</h2>
      {changed.length === 0 ? (
        <p>Every counted item matches the system.</p>
      ) : (
        <table>
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
              const v = milliOf(l.varianceQty);
              return (
                <tr key={l.productId}>
                  <td>{l.description ?? l.productId}</td>
                  <td className="num">{showQty(l.expectedQty)}</td>
                  <td className="num">{showQty(l.countedQty)}</td>
                  <td className="num">
                    <strong>{v > 0 ? '+' : ''}{showQty(l.varianceQty)}</strong> {v > 0 ? '(more)' : '(less)'}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
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
    mutationFn: () => retail.createStocktake({ branchId, lines: entered.map(([productId, countedQty]) => ({ productId, countedQty: countedQty.trim() })) }),
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
  const rows = (stock.data ?? []).filter((r) => !query.trim() || r.description.toLowerCase().includes(query.trim().toLowerCase()));
  return (
    <>
      <Note>Type the counted quantity for each item you counted. Leave an item blank to skip it.</Note>
      <label htmlFor="count-search">Search by name</label>
      <input id="count-search" type="search" value={query} onChange={(e) => setQuery(e.target.value)} autoComplete="off" />
      {stock.isPending && <p>Loading</p>}
      <Problem error={stock.error} />
      {rows.map((r) => (
        <div key={r.productId} className="rt-card">
          <label htmlFor={`count-${r.productId}`}>{r.description} ({r.unit})</label>
          <input id={`count-${r.productId}`} inputMode="decimal" value={counts[r.productId] ?? ''} placeholder="Counted"
            aria-invalid={(counts[r.productId] ?? '').trim() !== '' && parseCount(counts[r.productId] ?? '') === null}
            onChange={(e) => setCounts({ ...counts, [r.productId]: e.target.value })} />
        </div>
      ))}
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
        <BranchRequired />
      ) : (
        <>
          <p>Branch: {branchName}</p>
          <CountSheet key={branchId} branchId={branchId} />
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/stocktake')({ component: StocktakePage });
