import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { retail, type Product, type Usage, type UsageRequest } from '../../../api/retail';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { parseQty, qtyString, showQty } from './maths';
import { BranchRequired, CategoryLabel, Gate, Problem, useStockingBranch } from './ui';
import { canUse, needsOf } from './permissions';
import { BusyButton } from '../../../app/ui/BusyButton';

// Usage and damage (FR-RET-07): items taken out of stock for use in the shop or because they are
// damaged, with a reason. Valued at cost by the server; no cost is shown here.

interface Line { product: Product; qty: string }

export function buildUsage(input: { branchId: string; kind: 'used' | 'damaged'; reason: string; lines: Line[] }): UsageRequest {
  return {
    branch_id: input.branchId,
    kind: input.kind,
    reason: input.reason.trim(),
    lines: input.lines.map((l) => ({ product_id: l.product.id ?? '', qty: qtyString(parseQty(l.qty) ?? 0) })),
  };
}

export function UsageForm({ branchId, onSaved }: { branchId: string; onSaved?: (u: Usage) => void }) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<{ kind: 'used' | 'damaged'; reason: string; lines: Line[] }>(
    `usage:${me.user_id ?? ''}:${branchId}`,
    { kind: 'used', reason: '', lines: [] },
  );
  const { kind, reason, lines } = draft;
  const setKind = (k: 'used' | 'damaged') => setDraft((d) => ({ ...d, kind: k }));
  const setReason = (r: string) => setDraft((d) => ({ ...d, reason: r }));
  const setLines = (f: (ls: Line[]) => Line[]) => setDraft((d) => ({ ...d, lines: f(d.lines) }));
  const search = useLiveSearch();
  const products = useQuery({
    queryKey: ['retail', 'products', branchId, search.term],
    queryFn: ({ signal }) => retail.listProducts({ query: search.term, branchId, signal }),
    placeholderData: keepPreviousData,
  });
  const finding = products.isPlaceholderData || products.isFetching;
  const save = useMutation({
    mutationFn: () => retail.createUsage(buildUsage({ branchId, kind, reason, lines }), key),
    onSuccess: (u) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved?.(u);
    },
  });
  const valid = lines.length > 0 && reason.trim() !== '' && lines.every((l) => parseQty(l.qty) !== null);

  return (
    <form onSubmit={(e) => { e.preventDefault(); if (valid && !save.isPending) save.mutate(); }}>
      <fieldset data-tour="usage-kind">
        <legend>What happened?</legend>
        {(['used', 'damaged'] as const).map((k) => (
          <label key={k} style={{ fontWeight: 400 }}>
            <input type="radio" name="usage-kind" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={kind === k} onChange={() => setKind(k)} />
            {k === 'used' ? 'Used in the shop' : 'Damaged or lost'}
          </label>
        ))}
      </fieldset>
      <div data-tour="usage-reason">
      <label htmlFor="usage-reason">Reason</label>
      <input id="usage-reason" value={reason} onChange={(e) => setReason(e.target.value)} />
      </div>

      <div data-tour="usage-search">
      <SearchBox id="usage-search" label="Find an item by name, code or category" search={search} fetching={finding} count={products.data ? Math.min(products.data.length, 20) : undefined} more={(products.data?.length ?? 0) > 20} />
      </div>
      {products.data && products.data.length === 0 && search.term && <NoResults term={search.term} onClear={search.clear} noun="items" />}
      <SearchResults fetching={finding}>
      <ul style={{ listStyle: 'none', padding: 0, maxHeight: 180, overflowY: 'auto' }}>
        {(products.data ?? []).slice(0, 20).map((p) => (
          <li key={p.id} className="rt-card rt-row">
            <span><strong>{p.description}</strong> ({p.code}), in stock {showQty(p.qty ?? '0')} {p.unit}<br /><CategoryLabel category={p.category} /></span>
            <button type="button" aria-label={`Add ${p.description}`} onClick={() => setLines((ls) => (ls.some((l) => l.product.id === p.id) ? ls : [...ls, { product: p, qty: '1' }]))}>Add</button>
          </li>
        ))}
      </ul>
      </SearchResults>

      <h2>Items</h2>
      {lines.length === 0 && <p className="empty-state">No items yet.</p>}
      {lines.map((l, i) => (
        <div key={l.product.id} className="rt-card">
          <strong>{l.product.description}</strong>
          <label htmlFor={`uq-${i}`}>Quantity ({l.product.unit})</label>
          <input id={`uq-${i}`} inputMode="decimal" value={l.qty} aria-invalid={parseQty(l.qty) === null}
            onChange={(e) => setLines((ls) => ls.map((x, n) => (n === i ? { ...x, qty: e.target.value } : x)))} />
          {parseQty(l.qty) === null && <p className="rt-flag">Enter a quantity above zero.</p>}
          <button type="button" className="btn-ghost" aria-label={`Remove ${l.product.description}`} onClick={() => setLines((ls) => ls.filter((_, n) => n !== i))}>Remove</button>
        </div>
      ))}
      <Problem error={save.error} />
      <BusyButton type="submit" className="rt-primary" data-tour="usage-save" busy={save.isPending} disabled={!valid}>Save</BusyButton>

      {(lines.length > 0 || reason !== '') && !save.isPending && (
        <button type="button" onClick={() => { discard(); save.reset(); }}>Clear this form</button>
      )}
    </form>
  );
}

export function UsagePage() {
  const { me } = useStaff();
  const { branchId, branchName } = useStockingBranch();
  const [done, setDone] = useState<Usage | null>(null);
  const [round, setRound] = useState(0);
  return (
    <Gate screen="usage" title="Usage and damage">
      {branchId === null ? (
        <BranchRequired permissions={needsOf('usage')} stocking />
      ) : done ? (
        <section aria-label="Saved">
          <h2>Saved</h2>
          <p>{done.kind === 'used' ? 'Usage' : 'Damage'} recorded for {(done.lines ?? []).length} items. Stock is reduced. {canUse(me, 'stock') && <Link to="/staff/retail/stock">See the stock</Link>}</p>
          <button type="button" className="rt-primary" onClick={() => { setDone(null); setRound((n) => n + 1); }}>Record another</button>
        </section>
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{branchName}</strong></p>
          <UsageForm key={`${branchId}-${round}`} branchId={branchId} onSaved={setDone} />
        </>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/usage')({ component: UsagePage });
