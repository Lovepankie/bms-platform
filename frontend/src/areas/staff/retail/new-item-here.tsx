import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { RETAIL_CURRENCY, RetailError, retail, type Category, type Product, type SimilarProduct } from '../../../api/retail';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field, FieldsNeededNote, focusFirstMissing } from '../../../app/ui/Field';
import { WORDS } from '../../../app/glossary';
import { parseMinor } from '../../../components/money';
import { useOnline } from '../../../offline/offline-context';
import { useStaff } from '../context';
import { ExistsPanel, SimilarPanel, existingOf, type ExistingItem } from './catalogue-names';
import { parseCount, parseQty } from './maths';
import { CategoryPicker } from './category-picker';
import { Problem, money } from './ui';

// Add an item while buying or counting stock (issue #505): under the item search, "Not in the list? Add it here" opens a
// short form in the order of the owner's AppSheet form (Hatibu, 23 Oct 2026): Item code (shown, made by the app from the
// category, never typed: issue #562), Category (the first field the person fills), Description, Counted in, Buying price,
// Selling price, the quantity, one Save. Saving creates the item through the same create call as Items, so the server makes the code
// (ADR-081) and keeps the name rules (issue #352); the screen then puts it on the purchase or the count without leaving.
// Only retail.catalogue.manage may add items; anyone else is told to ask their manager. The form is a group, not a <form>:
// it sits inside the purchase form, and Enter in it saves the item, never the purchase.

export const ASK_MANAGER = 'Ask your manager to add this item.';
export const OFFLINE_TEXT = 'You are offline. Adding a new item needs the internet. Connect, then tap Save the item again.';

/** The new item and what was typed with it, for the screen to put on its line. */
export interface AddedItem {
  product: Product;
  /** The quantity typed, as typed; empty when the screen asked for none. */
  qty: string;
  cost: string;
  /** Absent for an item already in the list the person chose instead: it keeps its own selling price. */
  sell?: string;
}

/** The fetch never reached the server: no status and no problem body. */
function unreachable(error: unknown): boolean {
  return error instanceof TypeError || (error instanceof RetailError && error.status === 0);
}

/**
 * The item code line of a new item (issue #562, owner decision of 25 Oct 2026): the app always makes the code from the
 * category, so it is shown as text, never asked. The number is the category's next; another add may take it first.
 */
export function ItemCodeLine({ id, category }: { id: string; category: Category | undefined }) {
  return (
    <p id={id} data-testid={id}>
      {WORDS.itemCode}:{' '}
      {category
        ? <><strong>{category.next_code ?? `${category.code_prefix ?? ''}-0001`}</strong>, made by the app</>
        : 'made by the app once you choose the category'}
    </p>
  );
}

export function NewItemHere({ id, qtyLabel, counting = false, askCost, onAdded }: {
  id: string;
  /** The quantity's label; absent when the screen takes the quantity on its own line. */
  qtyLabel?: string;
  /** A count, where 0 is a quantity; a purchase needs more than 0. */
  counting?: boolean;
  /** The buying price is asked for: always on Buy stock; on Count stock only for someone who may see it. */
  askCost: boolean;
  /** Puts the item on the screen's line; a returned text says why it could not, and shows under the button. */
  onAdded: (added: AddedItem) => string | void | Promise<string | void>;
}) {
  const { me } = useStaff();
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  if (!(me.permissions ?? []).includes('retail.catalogue.manage')) {
    return <p className="hint" data-testid={`${id}-ask`}>Not in the list? {ASK_MANAGER}</p>;
  }
  if (!open) {
    return (
      <>
        <button type="button" id={`${id}-open`} className="btn-ghost" aria-expanded={false} onClick={() => { setNote(null); setOpen(true); }}>
          Not in the list? Add it here
        </button>
        {note && <p role="alert" className="alert alert-warning" data-testid={`${id}-note`}>{note}</p>}
      </>
    );
  }
  // Focus goes back to the button that opened the form, so it never falls to the page.
  const close = (refused?: string | void) => {
    setNote(refused || null);
    setOpen(false);
    setTimeout(() => document.getElementById(`${id}-open`)?.focus());
  };
  return <NewItemForm id={id} qtyLabel={qtyLabel} counting={counting} askCost={askCost} onAdded={onAdded} onClose={close} />;
}

function NewItemForm({ id, qtyLabel, counting, askCost, onAdded, onClose }: {
  id: string;
  qtyLabel?: string;
  counting: boolean;
  askCost: boolean;
  onAdded: (added: AddedItem) => string | void | Promise<string | void>;
  onClose: (refused?: string | void) => void;
}) {
  const { me } = useStaff();
  const online = useOnline();
  const queryClient = useQueryClient();
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  const [categoryId, setCategoryId] = useState('');
  const [description, setDescription] = useState('');
  const [unitId, setUnitId] = useState('');
  const [cost, setCost] = useState(counting ? '0' : '');
  const [sell, setSell] = useState('');
  const [qty, setQty] = useState('');
  const [showErrors, setShowErrors] = useState(false);
  const [offline, setOffline] = useState(false);
  const [similar, setSimilar] = useState<SimilarProduct[] | null>(null);
  const [exists, setExists] = useState<ExistingItem | null>(null);
  const [acceptedKey, setAcceptedKey] = useState('');
  const sending = useRef(false);
  const group = useRef<HTMLDivElement>(null);
  // The category is the first field the person fills (#493): it takes the cursor when the form opens.
  useEffect(() => { document.getElementById(`${id}-category`)?.focus(); }, [id]);

  const chosen = (categories.data ?? []).find((c) => c.id === categoryId);
  const costMinor = askCost ? parseMinor(cost, RETAIL_CURRENCY) : 0;
  const sellMinor = parseMinor(sell, RETAIL_CURRENCY);
  const qtyMilli = qtyLabel === undefined ? 0 : counting ? parseCount(qty) : parseQty(qty);
  const complete = categoryId !== '' && description.trim() !== '' && unitId !== '' && costMinor !== null && sellMinor !== null && qtyMilli !== null;
  const nameKey = `${description.trim()}|${unitId}`;
  const canAddAnyway = (me.permissions ?? []).includes('core.settings.manage');

  const added = async (product: Product, own: boolean) => {
    onClose(await onAdded({ product, qty: qtyLabel === undefined ? '' : qty.trim(), cost: askCost ? cost.trim() : '', ...(own ? { sell: sell.trim() } : {}) }));
  };
  const save = useMutation({
    mutationFn: async (duplicateReason?: string) => {
      const product = await retail.createProduct({
        description: description.trim(),
        category_id: categoryId,
        unit_id: unitId,
        sell_minor: sellMinor ?? 0,
        cost_minor: costMinor ?? 0,
        ...(duplicateReason ? { duplicate_reason: duplicateReason } : {}),
      });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'catalogue'] });
      await added(product, true);
    },
    onError: (error) => {
      const found = existingOf(error);
      if (found) setExists({ ...found, mayAddAnyway: found.mayAddAnyway && canAddAnyway });
      if (unreachable(error)) setOffline(true);
    },
    onSettled: () => { sending.current = false; },
  });
  const check = useMutation({
    mutationFn: () => retail.similarProducts({ description: description.trim(), unitId }),
    onSuccess: (found) => {
      const same = (found.exact ?? [])[0];
      if (same) setExists({ id: same.id ?? '', code: same.code ?? '', name: same.description ?? '', mayAddAnyway: canAddAnyway });
      else if ((found.similar ?? []).length > 0) setSimilar(found.similar ?? []);
      else {
        save.mutate(undefined);
        return;
      }
      sending.current = false;
    },
    onError: (error) => {
      sending.current = false;
      if (unreachable(error)) setOffline(true);
    },
  });
  // An item already in the list, chosen from the warning: it goes on the line instead, with the quantity typed.
  const pickExisting = useMutation({
    mutationFn: async (productId: string) => added(await retail.getProduct(productId), false),
    onSettled: () => { sending.current = false; },
  });
  // A second tap on Use before the first has answered would put the item on the line twice.
  const chooseExisting = (productId: string) => {
    if (sending.current) return;
    sending.current = true;
    pickExisting.mutate(productId);
  };
  const busy = save.isPending || check.isPending || pickExisting.isPending;

  const submit = () => {
    if (!complete) {
      setShowErrors(true);
      if (group.current) focusFirstMissing(group.current);
      return;
    }
    // While a name warning is open its own buttons decide; Enter in a field (or the reason box) does not check the name again.
    if (busy || sending.current || exists !== null || similar !== null) return;
    // Nothing is kept for later: a new item is made on the server, so without the internet it is refused here, plainly.
    // Read at the press: the browser's flag does not render the form again when the connection drops.
    if (!online || (typeof navigator !== 'undefined' && navigator.onLine === false)) return setOffline(true);
    setOffline(false);
    sending.current = true;
    if (acceptedKey !== nameKey) check.mutate();
    else save.mutate(undefined);
  };
  const forget = () => { setExists(null); setSimilar(null); save.reset(); };
  const enter = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== 'Enter' || (e.target as HTMLElement).tagName === 'BUTTON') return;
    e.preventDefault();
    submit();
  };
  const fail = save.error ?? check.error ?? pickExisting.error;

  return (
    <div ref={group} id={`${id}-form`} role="group" aria-labelledby={`${id}-title`} className="rt-card" onKeyDown={enter}>
      <h3 id={`${id}-title`}>Add a new item</h3>
      <FieldsNeededNote />
      <ItemCodeLine id={`${id}-code`} category={chosen} />
      <CategoryPicker
        id={`${id}-category`}
        categories={(categories.data ?? []).filter((c) => c.active !== false)}
        value={categoryId}
        onChange={setCategoryId}
        required
        error={categoryId === '' ? 'Choose a category.' : null}
        showError={showErrors}
      />
      <Field
        id={`${id}-description`}
        label="Description"
        required
        error={description.trim() === '' ? 'Type what the item is.' : null}
        showError={showErrors}
        value={description}
        maxLength={300}
        onChange={(e) => { setDescription(e.target.value); forget(); }}
        autoComplete="off"
      />
      <Field
        id={`${id}-unit`}
        label={WORDS.countedIn}
        required
        error={unitId === '' ? 'Choose a unit.' : null}
        showError={showErrors}
        control={(props) => (
          <select {...props} value={unitId} onChange={(e) => { setUnitId(e.target.value); forget(); }}>
            <option value="">Choose a unit</option>
            {(units.data ?? []).filter((u) => u.active !== false).map((u) => <option key={u.id} value={u.id}>{u.name}</option>)}
          </select>
        )}
      />
      {askCost && (
        <Field
          id={`${id}-cost`}
          label={`${WORDS.buyingPrice} (${RETAIL_CURRENCY})`}
          required
          error={costMinor === null ? `Type what you pay for one, in ${RETAIL_CURRENCY}.` : null}
          showError={showErrors}
          hint={counting ? 'What you pay the supplier for one. Leave 0 if you do not know it yet; buying stock sets it.' : undefined}
          inputMode="numeric"
          value={cost}
          onChange={(e) => setCost(e.target.value)}
          autoComplete="off"
        />
      )}
      <Field
        id={`${id}-sell`}
        label={`${WORDS.sellingPrice} (${RETAIL_CURRENCY})`}
        required
        error={sellMinor === null ? `Type the price you sell one at, in ${RETAIL_CURRENCY}.` : null}
        showError={showErrors}
        hint={sellMinor !== null && costMinor !== null && costMinor > 0 && sellMinor <= costMinor ? `This is not above the buying price, ${money(costMinor)}.` : undefined}
        inputMode="numeric"
        value={sell}
        onChange={(e) => setSell(e.target.value)}
        autoComplete="off"
      />
      {qtyLabel !== undefined && (
        <Field
          id={`${id}-qty`}
          label={qtyLabel}
          required
          error={qtyMilli === null ? (counting ? 'Type how many you counted, or 0.' : 'Type how many you bought.') : null}
          showError={showErrors}
          inputMode="decimal"
          value={qty}
          onChange={(e) => setQty(e.target.value)}
          autoComplete="off"
        />
      )}
      {exists && (
        <ExistsPanel
          item={exists}
          canAddAnyway={exists.mayAddAnyway}
          busy={busy}
          openLabel="Use this item"
          onOpen={chooseExisting}
          onAddAnyway={(reason) => { if (sending.current) return; sending.current = true; save.mutate(reason); }}
          onChangeName={forget}
        />
      )}
      {similar && !exists && (
        <SimilarPanel
          items={similar}
          busy={busy}
          openLabel="Use"
          onOpen={chooseExisting}
          onSaveAnyway={() => { if (sending.current) return; sending.current = true; setAcceptedKey(nameKey); setSimilar(null); save.mutate(undefined); }}
          onChangeName={forget}
        />
      )}
      {offline ? (
        <p role="alert" className="alert alert-warning" data-testid={`${id}-offline`}>{OFFLINE_TEXT}</p>
      ) : (
        !exists && <Problem error={fail} />
      )}
      <p className="cluster">
        <BusyButton busy={busy} type="button" disabled={exists !== null || similar !== null} onClick={submit}>Save the item</BusyButton>
        <button type="button" className="btn-ghost" onClick={() => onClose()}>Cancel</button>
      </p>
    </div>
  );
}
