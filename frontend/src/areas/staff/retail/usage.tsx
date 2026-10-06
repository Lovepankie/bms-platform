import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type Product, type Usage, type UsageRequest } from '../../../api/retail';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { parseQty, qtyString, showQty } from './maths';
import { BranchRequired, CategoryLabel, Gate, Problem, useSingleBranch } from './ui';

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
  const [search, setSearch] = useState('');
  const products = useQuery({ queryKey: ['retail', 'products', branchId, search], queryFn: () => retail.listProducts({ query: search, branchId }) });
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
      <fieldset>
        <legend>What happened?</legend>
        {(['used', 'damaged'] as const).map((k) => (
          <label key={k} style={{ fontWeight: 400 }}>
            <input type="radio" name="usage-kind" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={kind === k} onChange={() => setKind(k)} />
            {k === 'used' ? 'Used in the shop' : 'Damaged or lost'}
          </label>
        ))}
      </fieldset>
      <label htmlFor="usage-reason">Reason</label>
      <input id="usage-reason" value={reason} onChange={(e) => setReason(e.target.value)} />

      <label htmlFor="usage-search">Find an item by name, code or category</label>
      <input id="usage-search" type="search" value={search} onChange={(e) => setSearch(e.target.value)} autoComplete="off" />
      <ul style={{ listStyle: 'none', padding: 0, maxHeight: 180, overflowY: 'auto' }}>
        {(products.data ?? []).slice(0, 20).map((p) => (
          <li key={p.id} className="rt-card rt-row">
            <span><strong>{p.description}</strong> ({p.code}), in stock {showQty(p.qty ?? '0')} {p.unit}<br /><CategoryLabel category={p.category} /></span>
            <button type="button" aria-label={`Add ${p.description}`} onClick={() => setLines((ls) => (ls.some((l) => l.product.id === p.id) ? ls : [...ls, { product: p, qty: '1' }]))}>Add</button>
          </li>
        ))}
      </ul>

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
      <button type="submit" className="rt-primary" disabled={!valid || save.isPending}>{save.isPending ? 'Saving' : 'Save'}</button>
      {(lines.length > 0 || reason !== '') && !save.isPending && (
        <button type="button" onClick={() => { discard(); save.reset(); }}>Clear this form</button>
      )}
    </form>
  );
}

function UsagePage() {
  const { branchId, branchName } = useSingleBranch();
  const [done, setDone] = useState<Usage | null>(null);
  const [round, setRound] = useState(0);
  return (
    <Gate screen="usage" title="Usage and damage">
      {branchId === null ? (
        <BranchRequired />
      ) : done ? (
        <section aria-label="Saved">
          <h2>Saved</h2>
          <p>{done.kind === 'used' ? 'Usage' : 'Damage'} recorded for {(done.lines ?? []).length} items. Stock is reduced.</p>
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
