import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useEffect, useRef, useState } from 'react';
import { RetailError, retail, type StockRow } from '../../../api/retail';
import { useStaff } from '../context';
import { showWhen } from './catalogue-products';
import { usePersistedDraft } from './idempotency';
import { showQty } from './maths';
import { holds, holdsAt } from './permissions';
import { REASONS, correctionRequest, correctionWords, differenceWords, movementTitle, quantityProblem, signedQty, type ReasonCode } from './stock-correct-state';
import { Problem, Success } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Segmented } from '../../../app/ui/Segmented';
import { WORDS } from '../../../app/glossary';
import { Hint } from '../../../app/ui/Hint';

// "Correct" on a row of the stock list (issue #351, ADR-044): the owner fixes a mistake where he sees it, with a trail. Four small
// choices, each opened on its own: the right quantity (a reason, posted as a correction that is not shrinkage), the category, a wrong
// unit, name or price (where to go), and the history of the item at this branch. Nothing is edited or deleted underneath: the
// restock the mistake came from stays.

export const STOCK_COUNT = 'retail.stocktake.commit';
export const CATALOGUE_MANAGE = 'retail.catalogue.manage';

type Choice = 'quantity' | 'category' | 'other' | 'history';

/** The right quantity and a reason: the server posts the difference as a correction. */
function QuantityForm({ row, branchId, onDone }: { row: StockRow; branchId: string; onDone: (message: string) => void }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const current = row.qty ?? '0';
  // The draft carries the quantity the form showed when it was opened: that is what the server compares with the shelf.
  const { key, draft, setDraft, discard } = usePersistedDraft<{ qty: string; reason: ReasonCode | ''; note: string; expected: string }>(
    `stock-correct:${me.user_id ?? ''}:${branchId}:${row.product_id ?? ''}`,
    { qty: showQty(current), reason: '', note: '', expected: current },
  );
  // A draft typed against another quantity (kept from an earlier opening, or the shelf moved and the list was read again) starts
  // again from the quantity on the list now, with a new key: the quantity sent as expected is always the one on the list.
  const [restarted, setRestarted] = useState<string | null>(null);
  // A 409 that did not name the quantity now: the list is being read again, and the notice waits for it (issue #370).
  const [rereading, setRereading] = useState(false);
  // Set when the list has been read again after such a 409: the form then starts from the list as it is.
  const [reread, setReread] = useState(false);
  // Starts the form again from a quantity (the list's, or the one a 409 named): a new key, nothing typed, expected is that quantity.
  const restart = (touched: boolean, from: string = current) => {
    discard();
    setDraft({ qty: showQty(from), reason: '', note: '', expected: from });
    setRestarted(touched ? from : null);
    setRereading(false);
  };
  useEffect(() => {
    if (draft.expected === current) return;
    // A notice already showing, or waiting for the list, stays and names the list's quantity now (issue #370).
    restart(rereading || restarted !== null || draft.reason !== '' || draft.note !== '' || draft.qty !== showQty(draft.expected));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current]);
  useEffect(() => {
    if (!reread) return;
    setReread(false);
    // The list came back with the same quantity (a new one restarted the form already): start from it, fresh from the server.
    if (rereading) restart(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reread]);
  // The quantity the server will compare with: the list's, or the one a 409 named before the list was read again.
  const shown = draft.expected;
  const problem = quantityProblem(draft.qty, shown);
  const ready = !rereading && problem === null && draft.reason !== '';
  // A second press before the first request has started must not send another one: the ref answers at once.
  const sending = useRef(false);
  const send = useMutation({
    mutationFn: () => retail.correctStock(correctionRequest(branchId, row.product_id ?? '', draft.qty, draft.reason as ReasonCode, draft.note, draft.expected), key),
    onSuccess: (done) => {
      discard();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onDone(`${row.description} is now ${showQty(done.new_qty ?? '0')} ${row.unit ?? ''} (it was ${showQty(done.old_qty ?? '0')}). The correction is in its history. It does not count as lost or damaged stock.`);
    },
    onError: (error) => {
      // The shelf moved while the form was open: read the list again, and type against the new quantity with a new key.
      if (error instanceof RetailError && ['stock_moved', 'stocktake_in_progress'].includes(error.code ?? '')) {
        void queryClient.invalidateQueries({ queryKey: ['retail'] }).then(() => setReread(true));
        // The refusal names the quantity now (current_qty): show it at once, never the one the form was opened with.
        const now = error.problem?.current_qty;
        if (error.code === 'stock_moved' && typeof now === 'string') restart(true, now);
        else if (error.code === 'stock_moved') {
          // No number: show none and offer none until the list comes back, so nothing is typed against the old one.
          restart(false);
          setDraft((d) => ({ ...d, qty: '' }));
          setRereading(true);
        }
      }
    },
    onSettled: () => { sending.current = false; },
  });
  const difference = differenceWords(draft.qty, shown, row.unit);
  const press = () => {
    if (!ready || sending.current) return;
    sending.current = true;
    send.mutate();
  };
  return (
    <form className="stack" aria-label="Correct the quantity" onSubmit={(e) => { e.preventDefault(); press(); }}>
      {!rereading && <p>In stock now: <strong>{showQty(shown)} {row.unit}</strong>.</p>}
      {restarted !== null && <p role="status" className="hint">The quantity on the list changed to {showQty(restarted)}; type it again.</p>}
      {rereading && <p role="status" className="hint">The quantity changed while you were typing. Reading the list again.</p>}
      <label htmlFor="correct-qty">Right quantity ({row.unit})</label>
      <input id="correct-qty" inputMode="decimal" disabled={rereading} value={draft.qty} onChange={(e) => setDraft((d) => ({ ...d, qty: e.target.value }))} aria-invalid={problem !== null && draft.qty !== showQty(shown)} autoComplete="off" />
      {difference && <p className="hint" aria-live="polite">{difference}</p>}
      {!rereading && problem && draft.qty !== showQty(shown) && <p role="alert" className="rt-flag">{problem}</p>}
      <label htmlFor="correct-reason">Why is it being corrected?</label>
      <select id="correct-reason" value={draft.reason} onChange={(e) => setDraft((d) => ({ ...d, reason: e.target.value as ReasonCode | '' }))}>
        <option value="">Choose a reason</option>
        {REASONS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
      </select>
      <label htmlFor="correct-note">A note (if you like)</label>
      <input id="correct-note" value={draft.note} maxLength={280} onChange={(e) => setDraft((d) => ({ ...d, note: e.target.value }))} autoComplete="off" />
      <Hint full>
        The old figure stays in the history, with who changed it, when and why. A correction is not counted as lost or damaged stock, or as usage.
        If the money was entered wrongly too, use Past purchases instead.
      </Hint>
      <Problem error={send.error} />
      <BusyButton type="submit" className="rt-primary" busy={send.isPending} notReady={!ready}>Save the right quantity</BusyButton>
    </form>
  );
}

/** Move the item to the right category; the change is audited with the old and the new category. */
function CategoryForm({ row, onDone }: { row: StockRow; onDone: (message: string) => void }) {
  const queryClient = useQueryClient();
  const id = row.product_id ?? '';
  const product = useQuery({ queryKey: ['retail', 'product', id], queryFn: () => retail.getProduct(id), gcTime: 0 });
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const [chosen, setChosen] = useState('');
  const sending = useRef(false);
  const own = product.data?.category_id ?? '';
  const target = chosen === '' ? own : chosen;
  const send = useMutation({
    mutationFn: () => retail.updateProduct(id, product.data?.version ?? 1, { category_id: target }),
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onDone(`${saved.description} is now in ${saved.category}. It was in ${row.category}.`);
    },
    onSettled: () => { sending.current = false; },
  });
  if (product.isPending || categories.isPending) return <p className="loading">Loading</p>;
  if (!product.data) return <Problem error={product.error} />;
  const choices = (categories.data ?? []).filter((c) => c.active !== false || c.id === own);
  const ready = target !== '' && target !== own;
  return (
    <form
      className="stack"
      aria-label="Change the category"
      onSubmit={(e) => {
        e.preventDefault();
        if (!ready || sending.current) return;
        sending.current = true;
        send.mutate();
      }}
    >
      <p>Now in <strong>{row.category}</strong>.</p>
      <label htmlFor="correct-category">The right category</label>
      <select id="correct-category" value={target} onChange={(e) => setChosen(e.target.value)}>
        {choices.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
      </select>
      <p className="hint">The change is recorded with the old and the new category.</p>
      <Problem error={send.error} />
      <BusyButton type="submit" className="rt-primary" busy={send.isPending} notReady={!ready}>Move to this category</BusyButton>

    </form>
  );
}

/** The history of the item at this branch, newest first: restocks, sales, counts and corrections with who and why. */
function History({ row, branchId }: { row: StockRow; branchId: string }) {
  const { me } = useStaff();
  const history = useQuery({
    queryKey: ['retail', 'stock-history', branchId, row.product_id],
    queryFn: ({ signal }) => retail.stockHistory({ productId: row.product_id ?? '', branchId, limit: 30, signal }),
  });
  if (history.isPending) return <p className="loading">Loading</p>;
  if (!history.data) return <Problem error={history.error} />;
  const items = history.data.items;
  if (items.length === 0) return <p className="empty-state">Nothing has moved this item here yet.</p>;
  return (
    <ul style={{ listStyle: 'none', padding: 0 }} aria-label="Stock history">
      {items.map((m) => (
        <li key={m.id} className="rt-card" data-testid="history-line">
          <p>
            <strong>{showWhen(m.at)}</strong> {movementTitle(m)}: <strong>{signedQty(m)} {row.unit}</strong>
          </p>
          {correctionWords(m) && <p>{correctionWords(m)}</p>}
          {m.note && <p className="hint">{m.note}</p>}
          <p className="hint">By {m.by === me.user_id ? 'you' : 'a colleague'}</p>
        </li>
      ))}
      {history.data.next_cursor && <li className="hint">Older lines are not shown here.</li>}
    </ul>
  );
}

/**
 * The panel under a stock row. `branchId` is the branch being viewed: a correction is for one branch. The quantity form needs the
 * stock-take permission at that branch and the category form the catalogue permission; the other choices need neither.
 */
export function CorrectItem({ row, branchId, onClose }: { row: StockRow; branchId: string; onClose: () => void }) {
  const { me } = useStaff();
  const [choice, setChoice] = useState<Choice | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const mayQuantity = holdsAt(me, STOCK_COUNT, branchId);
  const mayCategory = holds(me, CATALOGUE_MANAGE);
  const done = (text: string) => { setMessage(text); setChoice(null); };
  const choices: { id: Choice; label: string; show: boolean }[] = [
    { id: 'quantity', label: 'The quantity is wrong', show: mayQuantity },
    { id: 'category', label: 'The category is wrong', show: mayCategory },
    { id: 'other', label: 'The unit, name or price is wrong', show: true },
    { id: 'history', label: 'See its history', show: true },
  ];
  return (
    <section className="rt-card" aria-label={`Correct ${row.description}`} data-testid="correct-item">
      <h3>Correct {row.description}</h3>
      {message && <Success>{message}</Success>}
      <div className="cluster">
        <Segmented
          label="What is wrong"
          options={choices.filter((c) => c.show).map((c) => ({ value: c.id, label: c.label }))}
          value={choice}
          clearable
          onChange={(id) => { setMessage(null); setChoice(id); }}
        />
        <button type="button" className="btn-ghost btn-sm" onClick={onClose}>Close</button>
      </div>
      {choice === 'quantity' && <QuantityForm row={row} branchId={branchId} onDone={done} />}
      {choice === 'category' && <CategoryForm row={row} onDone={done} />}
      {choice === 'history' && <History row={row} branchId={branchId} />}
      {choice === 'other' && (
        <div className="stack" data-testid="correct-other">
          <p>
            <strong>A purchase was entered in the wrong pack unit, or at the wrong price:</strong> open <Link to="/staff/retail/restocks">Past purchases</Link>, choose Correct this purchase, and enter it again.
          </p>
          <p>
            <strong>The name or the price of the item itself is wrong:</strong> change it in <Link to="/staff/retail/catalogue/products">Items</Link>.
          </p>
          <p>
            <strong>The item is kept in the wrong unit:</strong> a unit is never converted once the item has stock, because 10 rolls would become 10 metres. Add a new item with the right unit, correct this one to none here, and buy stock of the new one.
          </p>
          <p>
            <strong>The shelf holds a different number than the list:</strong> choose The quantity is wrong above, or count it in <Link to="/staff/retail/stocktake">{WORDS.countStock}</Link>.
          </p>
        </div>
      )}
    </section>
  );
}

/** True when the Correct button is worth showing: some correction is allowed at the branch. */
export function mayCorrect(me: Parameters<typeof holds>[0] & Parameters<typeof holdsAt>[0], branchId: string | null): boolean {
  return branchId !== null && (holdsAt(me, STOCK_COUNT, branchId) || holds(me, CATALOGUE_MANAGE));
}
