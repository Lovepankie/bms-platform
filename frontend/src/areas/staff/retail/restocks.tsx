import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, useNavigate } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { businessToday, daysBefore, retail, type Purchase } from '../../../api/retail';
import { useStaff } from '../context';
import { usePersistedDraft } from './idempotency';
import { showDate } from './maths';
import { holdsAt, PURCHASE_CREATE } from './permissions';
import { alreadyVoided, listIsStale, quantityWords, stateOf, unitHint, voidReasonOf, MIN_VOID_REASON } from './restock-correct-state';
import { Gate, Problem, Success, money } from './ui';

// Past restocks (issue #351, ADR-044): every restock, newest first, a voided one marked and never deleted. A restock entered with a
// wrong quantity, price, unit or supplier is corrected here: "Correct this restock" voids it (the stock, the books and the prices go
// back) and opens the restock form again with the same lines to enter them right. The server refuses a void when the stock has
// moved, in plain words; the screens only show the refusal.

const PAYMENT: Record<string, string> = { cash: 'Cash', bank: 'Bank', credit: 'On credit' };

/** The branches a restock added stock to. */
function branchesOf(purchase: Purchase): string[] {
  return [...new Set((purchase.lines ?? []).flatMap((l) => (l.qty_by_branch ?? []).map((b) => b.branch_id ?? '')))];
}

/** The void form: why, what it will do, and the two ways to go on. */
export function CorrectPanel({ purchase, onDone, onCancel }: { purchase: Purchase; onDone: (message: string) => void; onCancel: () => void }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const id = purchase.id ?? '';
  const { key, draft, setDraft, discard } = usePersistedDraft<{ reason: string }>(`restock-void:${me.user_id ?? ''}:${id}`, { reason: '' });
  const ready = voidReasonOf(draft.reason);
  // A second press before the first request has started must not send another one: the ref answers at once.
  const sending = useRef(false);
  const send = useMutation({
    mutationFn: (again: boolean) => retail.voidPurchase(id, voidReasonOf(draft.reason) ?? '', key).then(() => again),
    onSuccess: (again) => {
      discard();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      if (again) void navigate({ to: '/staff/retail/restock', search: { again: id } as never });
      else onDone(`Restock ${purchase.purchase_no ?? ''} is voided. Its stock, its entries and its prices are back as they were.`);
    },
    onError: (error) => {
      if (listIsStale(error)) void queryClient.invalidateQueries({ queryKey: ['retail'] });
      if (alreadyVoided(error)) onDone(`Restock ${purchase.purchase_no ?? ''} was voided already.`);
    },
    onSettled: () => { sending.current = false; },
  });
  const press = (again: boolean) => {
    if (!ready || sending.current) return;
    sending.current = true;
    send.mutate(again);
  };
  return (
    <form className="stack" aria-label="Correct this restock" onSubmit={(e) => { e.preventDefault(); press(true); }}>
      <p className="hint" data-testid="void-warning">
        Voiding takes the stock this restock added back out, reverses what it posted and puts the prices back if nothing has changed them since.
        The restock stays in this list, marked Voided. If fewer than its quantity are left at a branch it cannot be voided: use Correct the quantity on the Stock page instead.
      </p>
      <label htmlFor={`void-${id}`}>Why is it being corrected? (at least {MIN_VOID_REASON} characters)</label>
      <input id={`void-${id}`} value={draft.reason} maxLength={300} onChange={(e) => setDraft({ reason: e.target.value })} autoComplete="off" />
      <Problem error={send.error} />
      <p className="cluster">
        <button type="submit" className="rt-primary" disabled={ready === null || send.isPending}>{send.isPending ? 'Voiding' : 'Void and enter it again'}</button>
        <button type="button" disabled={ready === null || send.isPending} onClick={() => press(false)}>Void only</button>
        <button type="button" className="btn-ghost" disabled={send.isPending} onClick={onCancel}>Keep the restock</button>
      </p>
    </form>
  );
}

export function RestockCard({ purchase, supplier, onDone }: { purchase: Purchase; supplier?: string; onDone: (message: string) => void }) {
  const { me } = useStaff();
  const [open, setOpen] = useState(false);
  const voided = purchase.status === 'voided';
  // A scoped caller's copy hides the other branches the restock touched (`partial`): the server would refuse the void with 403.
  const mayVoid = purchase.partial !== true && branchesOf(purchase).every((b) => holdsAt(me, PURCHASE_CREATE, b));
  return (
    <li className="rt-card" data-testid="restock-card">
      <p>
        <strong>{purchase.purchase_no}</strong> <span className={voided ? 'badge badge-warning' : 'badge badge-info'}>{stateOf(purchase)}</span>
      </p>
      <p className="hint">
        Bought {showDate(purchase.purchased_on)} from {supplier || 'no supplier'}, {PAYMENT[purchase.payment_method ?? ''] ?? purchase.payment_method}. Total {money(purchase.total_minor ?? 0)}.
      </p>
      <ul>
        {(purchase.lines ?? []).map((l) => (
          <li key={l.id}>
            {l.description}: {quantityWords(l)}
            {unitHint(l) && <span className="hint" data-testid="unit-hint"> {unitHint(l)}</span>}
          </li>
        ))}
      </ul>
      {voided && (
        <p className="hint" data-testid="void-note">
          Voided {showDate(purchase.voided_on)}. Reason: {purchase.void_reason}
        </p>
      )}
      {!voided && !open && mayVoid && <p><button type="button" className="btn-sm" onClick={() => setOpen(true)} aria-label={`Correct restock ${purchase.purchase_no}`}>Correct this restock</button></p>}
      {!voided && !mayVoid && <p className="hint">Only someone who manages every branch this restock touched can correct it.</p>}
      {!voided && open && <CorrectPanel purchase={purchase} onDone={(m) => { setOpen(false); onDone(m); }} onCancel={() => setOpen(false)} />}
    </li>
  );
}

export function RestocksPage() {
  const today = businessToday();
  const [from, setFrom] = useState(daysBefore(today, 60));
  const [to, setTo] = useState(today);
  const [status, setStatus] = useState<'all' | 'posted' | 'voided'>('all');
  const [message, setMessage] = useState<string | null>(null);
  const suppliers = useQuery({ queryKey: ['retail', 'suppliers'], queryFn: () => retail.listSuppliers() });
  const page = useInfiniteQuery({
    queryKey: ['retail', 'restocks', from, to, status],
    queryFn: ({ pageParam, signal }) => retail.listPurchases({ from, to, ...(status === 'all' ? {} : { status }), ...(pageParam ? { cursor: pageParam } : {}), signal }),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
  });
  const names = new Map((suppliers.data ?? []).map((s) => [s.id, s.name]));
  const items = (page.data?.pages ?? []).flatMap((p) => p.items ?? []);
  return (
    <Gate screen="restock" title="Past restocks">
      <p><Link to="/staff/retail/restock">Back to Restock</Link></p>
      <p className="hint">Every restock is kept. If you entered one wrongly, open Correct this restock, then enter it again with the right quantity.</p>
      {message && <Success>{message}</Success>}
      <div className="rt-row" data-tour="restocks-filters">
        <div>
          <label htmlFor="restocks-from">From</label>
          <input id="restocks-from" type="date" value={from} max={to} onChange={(e) => setFrom(e.target.value)} />
        </div>
        <div>
          <label htmlFor="restocks-to">To</label>
          <input id="restocks-to" type="date" value={to} min={from} max={today} onChange={(e) => setTo(e.target.value)} />
        </div>
        <div>
          <label htmlFor="restocks-status">Show</label>
          <select id="restocks-status" value={status} onChange={(e) => setStatus(e.target.value as 'all' | 'posted' | 'voided')}>
            <option value="all">All restocks</option>
            <option value="posted">Not voided</option>
            <option value="voided">Voided</option>
          </select>
        </div>
      </div>
      {page.isPending && <p className="loading">Loading</p>}
      <Problem error={page.error} />
      {page.data && items.length === 0 && <p className="empty-state">No restocks in these dates.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="restocks-list">
        {items.map((p) => <RestockCard key={p.id} purchase={p} supplier={names.get(p.supplier_id)} onDone={setMessage} />)}
      </ul>
      {page.hasNextPage && <button type="button" onClick={() => void page.fetchNextPage()} disabled={page.isFetchingNextPage}>{page.isFetchingNextPage ? 'Loading' : 'Show older restocks'}</button>}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/restocks')({ component: RestocksPage });
