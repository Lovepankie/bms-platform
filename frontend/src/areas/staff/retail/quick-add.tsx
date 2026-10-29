import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, useNavigate, useSearch } from '@tanstack/react-router';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { RETAIL_CURRENCY, RetailError, retail, type QuickAdd, type QuickAddRequest, type Unit } from '../../../api/retail';
import { IDEMPOTENCY_UNSURE_CODES } from '../../../api/retail-errors';
import { branchLabel } from '../../../auth/branch';
import { WORDS } from '../../../app/glossary';
import { BusyButton } from '../../../app/ui/BusyButton';
import { EmptyChoice } from '../../../app/ui/EmptyChoice';
import { Field, FieldsNeededNote, focusFirstMissing } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { Segmented } from '../../../app/ui/Segmented';
import { parseMinor } from '../../../components/money';
import { useOnline } from '../../../offline/offline-context';
import { useStaff } from '../context';
import { CategoryPicker } from './category-picker';
import { ExistsPanel, existingOf, type ExistingItem } from './catalogue-names';
import { newIdempotencyKey } from './idempotency';
import { PhotoChoice, sendNewPhoto } from './item-photo';
import { parseCount, qtyString, showQty } from './maths';
import { branchesWhere, canUse, holdsAt } from './permissions';
import { UnitPicker } from './unit-picker';
import { Gate, Problem, money, useProfitAccess, useStockingBranch } from './ui';

// Quick add (issue #632): a shop owner needed nine steps on four screens to add one item and its stock, and said AppSheet was
// still easier. One form and one tap: the name, the category right under it, the selling price, the buying price (optional),
// how many there are here now, where (the branch in use, or a store), the unit (the shop's piece unit), and a photo if wanted.
// The category is needed (issue #688): an item is never filed under General unasked. The last one this person chose at this
// shop is chosen for them next time, and "+ Add a group" adds one in a sheet over the form, which keeps what was typed. Add makes the item and adds the quantity to its stock at that branch as opening stock, in one
// request (POST /retail/quick-add, ADR-101), so no other item's stock is touched and it is never profit. A name already in the
// list offers to use that item and just add the quantity to its stock. Nothing is kept for later: offline, the form says it needs the internet, as Add it here does.
// Goods just bought with shop money (issue #646): a tick, off by default, books them as a purchase paid from the branch's cash or
// mobile money, as Buy stock does, for whoever may buy stock there and see the buying price; the total that comes out shows before Add.

export const QUICK_ADD = 'Quick add';
/** Offline at the press; `button` is the one that sends again (issue #641): Add, or the name panel's button that was tapped. */
export const quickAddOffline = (button = 'Add') => `You are offline. Quick add needs the internet. Connect, then tap ${button} again.`;
export const NO_PLACE = 'You have no branch where you can add stock.';
export const NO_COUNT_HERE = 'The item is added with no stock. Ask your manager to add its stock.';

const COUNT = 'retail.stocktake.commit';
const BUY = 'retail.purchase.create';
export const PAID_TICK = 'I just bought these, take the money from cash';
type PaidFrom = 'cash' | 'mobile_money';
const PAID_FROM: { value: PaidFrom; label: string }[] = [{ value: 'cash', label: 'Cash' }, { value: 'mobile_money', label: 'Mobile money' }];
/** The piece unit a shop may already have, lower case, in the order the server prefers them. */
const PIECE_NAMES = ['piece', 'pc', 'pcs'];

/** The shop's piece unit, which the form starts with; undefined when the shop has none yet (the server makes Piece). */
export function pieceUnit(units: Unit[]): Unit | undefined {
  const active = units.filter((u) => u.active !== false);
  return PIECE_NAMES.map((n) => active.find((u) => (u.name ?? '').trim().toLowerCase() === n)).find((u) => u !== undefined);
}

/** The line said once Add has worked: "Test bulb added: 600 pc at Bulb Store", or for an item already in the list what was added and what it has now. */
export function addedLine(done: QuickAdd, where: string): string {
  const name = done.description ?? 'The item';
  if (done.qty === undefined) return `${name} added.`;
  const of = (qty: string | undefined) => `${qty ?? ''} ${done.unit ?? ''}`.trim();
  return done.item_made ? `${name} added: ${of(done.qty)} at ${where}` : `${name}: ${of(done.qty)} added at ${where}, ${of(done.total_qty)} now`;
}

// The last group chosen on Quick add, per business (the host, or the dev server's tenant), person and shop (issue #688), kept
// the way range-state.ts keeps the Home range; blocked storage only means the group is asked for again.
// JUSTIFICATION-A3: the issue asks to remember the last group per person and shop; no existing store carries it.
const groupKey = (userId: string, branchId: string) =>
  `bms.quickAddGroup.${import.meta.env.DEV && import.meta.env.VITE_DEV_TENANT ? import.meta.env.VITE_DEV_TENANT : window.location.hostname}.${userId}.${branchId}`;

export function readLastGroup(userId: string, branchId: string): string | null {
  try { return window.localStorage.getItem(groupKey(userId, branchId)); } catch { return null; }
}

export function writeLastGroup(userId: string, branchId: string, categoryId: string): void {
  try { window.localStorage.setItem(groupKey(userId, branchId), categoryId); } catch { /* asked for again next time */ }
}

/** The fetch never reached the server: no status and no problem body. */
function unreachable(error: unknown): boolean {
  return error instanceof TypeError || (error instanceof RetailError && error.status === 0);
}

/**
 * The server answered for certain that nothing was done: a 4xx refusal other than the key's own answers. A 5xx, a gateway timeout,
 * a lost answer, a key still being worked on or a key sent with another body may hide a request that went through.
 */
export function refusedForCertain(error: unknown): boolean {
  return error instanceof RetailError && error.status >= 400 && error.status < 500
    && !IDEMPOTENCY_UNSURE_CODES.includes(error.code ?? '');
}

export const NO_COST_HINT = 'Add a buying price so profit is right. If you do not know it yet, buying stock sets it.';
/** The answer was lost; `button` is the one that sends again, since Add is off while the name panel shows (issue #641). */
export const notSureLine = (button = 'Add') => `We could not tell whether this was saved. Tap ${button} again: it sends exactly the same, and it is never added twice.`;

export function QuickAddForm({ initialName = '', onSell }: {
  /** What the name starts with: on Sell's not-found, what was typed in the search. */
  initialName?: string;
  /** Opens Sell with the item on the sale, at the branch its stock was added at. */
  onSell?: (productId: string, branchId: string) => void;
}) {
  const { me } = useStaff();
  const online = useOnline();
  const queryClient = useQueryClient();
  const profit = useProfitAccess();
  const here = useStockingBranch();
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  // Where the stock is: every branch that keeps goods where this person may add stock, stores too; the branch in use first.
  const places = branchesWhere(me, COUNT).filter((b) => b.id && b.stocks_goods !== false);
  const mayCount = places.length > 0;
  const [chosenWhere, setWhere] = useState<string | null>(null);
  // Until the person picks one, Where follows the branch in use, which may be known only after the first render.
  const where = chosenWhere ?? places.find((b) => b.id === here.branchId)?.id ?? places[0]?.id ?? '';
  const [name, setName] = useState(initialName.trim());
  const [sell, setSell] = useState('');
  const [cost, setCost] = useState('');
  const [qty, setQty] = useState('');
  // The group last chosen here, from the first render on; checked against the list once it is read (below).
  const [categoryId, setCategoryId] = useState(() => readLastGroup(me.user_id ?? '', where || here.branchId || '') ?? '');
  const [unitId, setUnitId] = useState('');
  const [photo, setPhoto] = useState<File | null>(null);
  const [paid, setPaid] = useState(false);
  const [paidFrom, setPaidFrom] = useState<PaidFrom>('cash');
  const [showErrors, setShowErrors] = useState(false);
  const [offline, setOffline] = useState(false);
  const [exists, setExists] = useState<ExistingItem | null>(null);
  const [done, setDone] = useState<{ added: QuickAdd; note: string | null } | null>(null);
  // One key per attempt, and the request sent with it. While the answer is not certain (lost, a 5xx, a timeout) both are kept and
  // Add sends exactly that request again, whatever was typed since, so a retry can never add the stock twice; a new key and a new
  // request only once the server has answered for certain (success, or a 4xx refusal).
  const key = useRef(newIdempotencyKey());
  const unsure = useRef<QuickAddRequest | null>(null);
  const [notSure, setNotSure] = useState(false);
  // True while a request may have gone through: every field is locked, so what is shown is what is being sent again.
  const [held, setHeld] = useState(false);
  // True after the server refused for certain (nothing was saved): Start again is offered then, and only then.
  const [refused, setRefused] = useState(false);
  // What the duplicate panel says when the quantity is not yet above 0.
  const [panelNote, setPanelNote] = useState<string | null>(null);
  const form = useRef<HTMLFormElement>(null);
  const piece = pieceUnit(units.data ?? []);
  // A Piece or a General the owner switched off is never switched back on by the server: the person chooses one instead.
  const pieceOff = piece === undefined && (units.data ?? []).some((u) => u.active === false && (u.name ?? '').trim().toLowerCase() === 'piece');
  // The group: the one this person last chose at this shop, until they choose one in this form (issue #688).
  const shop = where || here.branchId || '';
  const [groupChosen, setGroupChosen] = useState(false);
  useEffect(() => {
    if (groupChosen || categories.data === undefined) return;
    const last = readLastGroup(me.user_id ?? '', shop);
    setCategoryId(categories.data.find((c) => c.id === last && c.active !== false)?.id ?? '');
  }, [categories.data, groupChosen, me.user_id, shop]);
  const chooseGroup = (next: string) => {
    setCategoryId(next);
    setGroupChosen(true);
    writeLastGroup(me.user_id ?? '', shop, next);
  };
  // What was said when the person kept an item already in the list instead of a duplicate (someone who may not add stock).
  const [kept, setKept] = useState<string | null>(null);
  // The piece unit is chosen once the units are read, unless the person has chosen another.
  useEffect(() => {
    if (unitId === '' && piece?.id) setUnitId(piece.id);
  }, [piece?.id, unitId]);
  // No focus on Name at opening (issue #642): a tour that starts over the page takes the focus, which is a blur of Name, and its
  // error would show before the person did anything. Add another, Start again and Change the name focus it: the person asked.

  const sellMinor = parseMinor(sell, RETAIL_CURRENCY);
  const costMinor = cost.trim() === '' ? 0 : parseMinor(cost, RETAIL_CURRENCY);
  const counted = mayCount ? parseCount(qty) : null;
  // Paid for now from cash: only where this person may buy stock, and with the buying price, which the purchase needs.
  const paying = paid && profit && mayCount && holdsAt(me, BUY, where);
  // Ticked, then Where moved to a place where this person may not buy stock: Add is held until they untick or move back, so
  // goods meant as paid for never go in as opening stock unnoticed.
  const cannotPayHere = paid && profit && mayCount && !holdsAt(me, BUY, where);
  // The place named on the paid lines; never empty, even before Where is known.
  const whereName = branchLabel(places.find((b) => b.id === where)) || 'this place';
  const paidMinor = paying && counted !== null && costMinor !== null ? Math.round((counted * costMinor) / 1000) : null;
  const complete = name.trim() !== '' && sellMinor !== null && costMinor !== null && (!mayCount || (counted !== null && where !== ''))
    && (!pieceOff || unitId !== '') && categoryId !== ''
    && (!paying || (costMinor > 0 && counted !== null && counted > 0)) && !cannotPayHere;
  const canAddAnyway = (me.permissions ?? []).includes('core.settings.manage');
  const openLabel = mayCount ? 'Use the existing one and just add stock' : 'Keep the existing one';
  // The button that sends the held request again: Add is off while the name panel shows, so then the panel button that sent it.
  const resend = exists === null ? 'Add' : unsure.current?.duplicate_reason ? 'Add anyway' : openLabel;

  // toast-exempt: success screen, the form turns into its added screen (qa-done) with Sell it and Add another
  const add = useMutation({
    mutationFn: async (extra: { productId?: string; duplicateReason?: string }) => {
      const body: QuickAddRequest = unsure.current ?? {
          ...(extra.productId
            ? { product_id: extra.productId }
            : {
                description: name.trim(),
                sell_minor: sellMinor ?? 0,
                ...(profit && costMinor ? { cost_minor: costMinor } : {}),
                ...(categoryId ? { category_id: categoryId } : {}),
                ...(unitId ? { unit_id: unitId } : {}),
                ...(extra.duplicateReason ? { duplicate_reason: extra.duplicateReason } : {}),
              }),
          ...(mayCount && counted !== null ? { qty: qtyString(counted), branch_id: where } : {}),
          ...(paying && costMinor ? { paid_from: paidFrom, cost_minor: costMinor } : {}),
        };
      unsure.current = body;
      const added = await retail.quickAdd(body, key.current);
      // The photo after the item (#617): a failed upload leaves the item and says so under the line.
      const note = added.item_made ? await sendNewPhoto(added.product_id ?? '', photo) : null;
      return { added, note };
    },
    onSuccess: ({ added, note }) => {
      key.current = newIdempotencyKey();
      unsure.current = null;
      setNotSure(false);
      setHeld(false);
      setRefused(false);
      setExists(null);
      setDone({ added, note });
      // The first-run card, the item search, the stock lists and Home all read again with the new item.
      for (const k of [['retail', 'catalogue'], ['retail', 'products'], ['retail', 'stock'], ['retail', 'stocked-branches'], ['retail', 'home']]) {
        void queryClient.invalidateQueries({ queryKey: k });
      }
    },
    onError: (error) => {
      if (!refusedForCertain(error)) {
        // The request may have gone through: the key and the request are kept for the next tap.
        if (unreachable(error)) setOffline(true);
        else setNotSure(true);
        setHeld(true);
        return;
      }
      // The server refused for certain, so the next attempt is a new request.
      key.current = newIdempotencyKey();
      unsure.current = null;
      setNotSure(false);
      setHeld(false);
      const found = existingOf(error);
      setRefused(!found);
      if (found) setExists({ ...found, mayAddAnyway: found.mayAddAnyway && canAddAnyway });
    },
  });

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    if (add.isPending || exists !== null) return;
    setKept(null);
    setRefused(false);
    if (!complete && unsure.current === null) {
      setShowErrors(true);
      if (form.current) focusFirstMissing(form.current);
      return;
    }
    // Read at the press: the browser's flag does not render the form again when the connection drops.
    if (!online || (typeof navigator !== 'undefined' && navigator.onLine === false)) return setOffline(true);
    setOffline(false);
    add.mutate({});
  };
  /** Start again: everything typed is cleared, after the server said for certain that nothing was saved. */
  const startAgain = () => {
    setName('');
    setSell('');
    setCost('');
    setQty('');
    setGroupChosen(false);
    setUnitId(piece?.id ?? '');
    setWhere(null);
    setPhoto(null);
    setPaid(false);
    setShowErrors(false);
    setRefused(false);
    add.reset();
    setTimeout(() => document.getElementById('qa-name')?.focus());
  };
  /** Add another: a clear form, keeping the category and where, so a run of items from one shelf is quick. */
  const another = () => {
    setDone(null);
    setName('');
    setSell('');
    setCost('');
    setQty('');
    setPhoto(null);
    setPaid(false);
    setShowErrors(false);
    add.reset();
    setTimeout(() => document.getElementById('qa-name')?.focus());
  };

  if (done) {
    // The branch the server added the stock at, from its answer: never the select, which a retry may have left elsewhere.
    const at = done.added.branch_id ?? '';
    const atName = branchLabel((me.branches ?? []).find((b) => b.id === at));
    const sellsThere = (me.branches ?? []).some((b) => b.id === at && b.sells_goods !== false);
    // Sell it only where this person may sell: the sale waits for that branch to be the one in use.
    const stocked = done.added.qty !== undefined && Number(done.added.qty) > 0;
    const canSell = onSell && canUse(me, 'sale') && holdsAt(me, 'retail.sale.create', at) && sellsThere && stocked;
    // Someone who sells, but not at this branch (or at a store), is told why there is no Sell it, rather than left waiting.
    const notHere = onSell && canUse(me, 'sale') && stocked && !canSell;
    return (
      <section className="rt-card stack" aria-labelledby="qa-done">
        <p id="qa-done" role="status" className="alert alert-success" data-testid="qa-done">{addedLine(done.added, atName)}</p>
        {done.added.paid_from && done.added.paid_minor !== undefined && (
          <p className="hint" data-testid="qa-paid"><span className="num">{money(done.added.paid_minor)}</span> taken from {done.added.paid_from === 'cash' ? 'Cash' : 'Mobile money'} at {atName}, recorded as Buy stock {done.added.purchase_no}.</p>
        )}
        {done.note && <p className="hint" data-testid="qa-photo-note">{done.note}</p>}
        {notHere && <p className="hint" data-testid="qa-not-here">{sellsThere ? `You do not sell at ${atName}, so Sell it is not offered here.` : `${atName} is a store, so the item is sold from a shop.`}</p>}
        <p className="cluster">
          <button type="button" className="rt-primary" onClick={another}>Add another</button>
          {canSell && <button type="button" onClick={() => onSell(done.added.product_id ?? '', at)}>Sell it</button>}
        </p>
      </section>
    );
  }

  return (
    <form ref={form} className="stack" onSubmit={submit} noValidate aria-label={QUICK_ADD}>
      <FieldsNeededNote />
      {/* While a send is not confirmed every field is locked: Add sends again exactly what is shown (review round 4). */}
      <fieldset disabled={held} className="form-lock stack" data-testid="qa-fields">
      <div className="stack" data-tour="quick-add-fields">
      <Field
        id="qa-name"
        label="Name"
        required
        error={name.trim() === '' ? 'Type what the item is.' : null}
        showError={showErrors}
        value={name}
        maxLength={300}
        onChange={(e) => { setName(e.target.value); setExists(null); }}
        autoComplete="off"
      />
      <CategoryPicker
        id="qa-category"
        label="Category"
        thing="group"
        placeholder="Choose a group"
        categories={(categories.data ?? []).filter((c) => c.active !== false)}
        value={categoryId}
        onChange={chooseGroup}
        required
        error={categoryId === '' ? 'Choose a group.' : null}
        showError={showErrors}
      />
      <Field
        id="qa-sell"
        label={`${WORDS.sellingPrice} (${RETAIL_CURRENCY})`}
        required
        error={sellMinor === null ? `Type the price you sell one at, in ${RETAIL_CURRENCY}.` : null}
        showError={showErrors}
        inputMode="numeric"
        value={sell}
        onChange={(e) => setSell(e.target.value)}
        autoComplete="off"
      />
      {profit && (
        <Field
          id="qa-cost"
          label={`${WORDS.buyingPrice} (${RETAIL_CURRENCY})`}
          optional={!paying}
          required={paying}
          error={paying && !costMinor ? `Type what you paid for one, in ${RETAIL_CURRENCY}.` : costMinor === null ? `Type what you pay for one, in ${RETAIL_CURRENCY}, or leave it empty.` : null}
          showError={showErrors}
          hint={sellMinor !== null && costMinor !== null && costMinor > 0 && sellMinor <= costMinor ? `This is not below the selling price, ${money(sellMinor)}.` : costMinor === 0 ? NO_COST_HINT : undefined}
          inputMode="numeric"
          value={cost}
          onChange={(e) => setCost(e.target.value)}
          autoComplete="off"
        />
      )}
      {mayCount ? (
        <>
          <Field
            id="qa-qty"
            label="How many do you have here now?"
            required
            error={counted === null ? 'Type how many you have, or 0.' : paying && counted === 0 ? 'Type how many you bought, more than 0.' : null}
            showError={showErrors}
            inputMode="decimal"
            value={qty}
            onChange={(e) => setQty(e.target.value)}
            autoComplete="off"
          />
          <Field
            id="qa-where"
            label="Where"
            required
            error={where === '' ? 'Choose where the stock is.' : null}
            showError={showErrors}
            control={(props) => (
              // empty: drawn only while places has a branch; with none, the EmptyChoice below stands in its place.
              <select {...props} value={where} onChange={(e) => setWhere(e.target.value)}>
                {places.map((b) => (
                  <option key={b.id} value={b.id}>{branchLabel(b)}{b.sells_goods === false ? ' (store)' : ''}</option>
                ))}
              </select>
            )}
          />
          {profit && (holdsAt(me, BUY, where) || paid) && (
            <div className="stack" data-testid="qa-paid-from">
              <label htmlFor="qa-paid">
                <input
                  id="qa-paid"
                  type="checkbox"
                  checked={paid}
                  onChange={(e) => setPaid(e.target.checked)}
                  aria-invalid={cannotPayHere || undefined}
                  aria-describedby={cannotPayHere ? 'qa-paid-here' : undefined}
                /> {PAID_TICK}
              </label>
              {cannotPayHere && (
                <p id="qa-paid-here" role="alert" className="alert alert-warning" data-testid="qa-paid-here">
                  {`You may not buy stock at ${whereName}, so the money cannot be taken there. Choose another place, or untick this.`}
                </p>
              )}
              {paying && (
                <>
                  <Segmented label="Paid from" options={PAID_FROM} value={paidFrom} onChange={setPaidFrom} />
                  <Hint full>
                    {paidMinor !== null && paidMinor > 0
                      ? <><span className="num" data-testid="qa-paid-total">{money(paidMinor)}</span>{` comes out of ${paidFrom === 'cash' ? 'Cash' : 'Mobile money'} at ${whereName}.`}</>
                      : 'Type the buying price and how many, to see what comes out.'}
                  </Hint>
                </>
              )}
            </div>
          )}
        </>
      ) : (
        // No branch where this person may add stock (rule 25): the field says so and who to ask, and the item is added with no stock.
        <EmptyChoice id="qa-where" label="Where" text={NO_PLACE} ask={NO_COUNT_HERE} />
      )}
      </div>
      <div className="stack" data-tour="quick-add-more">
      <UnitPicker
        id="qa-unit"
        units={(units.data ?? []).filter((u) => u.active !== false)}
        loading={units.data === undefined}
        load={units}
        value={unitId}
        onChange={(next) => { setUnitId(next); setExists(null); }}
        required={pieceOff}
        error={pieceOff && unitId === '' ? 'The Piece unit is switched off. Choose a unit.' : null}
        showError={showErrors}
      />
      {units.data !== undefined && piece === undefined && !pieceOff && unitId === '' && (
        <p className="hint" data-testid="qa-piece-made">Left empty, the item is counted in Piece, made for you.</p>
      )}
      <PhotoChoice id="qa-photo" file={photo} onChange={setPhoto} />
      </div>
      </fieldset>
      {exists && mayCount && counted !== null && counted > 0 && (
        <p className="hint" data-testid="qa-adds-to">The {showQty(qtyString(counted))} you typed is added to the stock it already has there.</p>
      )}
      {exists && panelNote && <p role="alert" className="alert alert-warning" data-testid="qa-panel-note">{panelNote}</p>}
      {exists && (
        <ExistsPanel
          item={exists}
          canAddAnyway={exists.mayAddAnyway}
          busy={add.isPending}
          openLabel={openLabel}
          onOpen={(id) => {
            if (mayCount && counted !== null && counted > 0) {
              setPanelNote(null);
              return add.mutate({ productId: id });
            }
            if (mayCount) {
              // Nothing to add yet: say so in the panel, never a silent refusal.
              setPanelNote('Type how many you are adding, more than 0, then tap Use the existing one and just add stock again.');
              setTimeout(() => document.getElementById('qa-qty')?.focus());
              return;
            }
            // Nothing to add for someone who may not add stock: the existing item stays as it is and the form is cleared, so Add
            // never sends the same name again.
            setKept(`${exists.name} is already in your items. Nothing new was added. To add stock to it, ask your manager.`);
            setExists(null);
            setName('');
            setSell('');
            setCost('');
            setPhoto(null);
            setShowErrors(false);
            setTimeout(() => document.getElementById('qa-name')?.focus());
          }}
          onAddAnyway={(reason) => add.mutate({ duplicateReason: reason })}
          onChangeName={() => { setExists(null); setPanelNote(null); setTimeout(() => document.getElementById('qa-name')?.focus()); }}
        />
      )}
      {kept && <p role="status" className="hint" data-testid="qa-kept">{kept}</p>}
      {offline ? (
        <p role="alert" className="alert alert-warning" data-testid="qa-offline">{quickAddOffline(resend)}</p>
      ) : notSure ? (
        <p role="alert" className="alert alert-warning" data-testid="qa-not-sure">{notSureLine(resend)}</p>
      ) : (
        !exists && <Problem error={add.error} />
      )}
      <p className="cluster" data-tour="quick-add-go">
        <BusyButton busy={add.isPending} type="submit" className="rt-primary" disabled={exists !== null}>Add</BusyButton>
        {refused && <button type="button" className="btn-ghost" onClick={startAgain}>Start again</button>}
      </p>
    </form>
  );
}

function QuickAddScreen() {
  const { chooseBranch } = useStaff();
  const navigate = useNavigate();
  const { name } = useSearch({ strict: false }) as { name?: string };
  return (
    <Gate screen="quickAdd" title={QUICK_ADD}>
      <p>An item, its prices and how many you have, in one tap.</p>
      <QuickAddForm
        initialName={name}
        onSell={(productId, branchId) => {
          chooseBranch?.(branchId);
          void navigate({ to: '/staff/retail/sale', search: { item: productId, branch: branchId } as never });
        }}
      />
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/quick-add')({ component: QuickAddScreen });
