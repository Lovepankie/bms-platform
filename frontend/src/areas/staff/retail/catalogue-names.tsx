import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { RetailError, type SimilarProduct } from '../../../api/retail';

// An item's name is kept once per unit (issue #352). Three things can happen on the Items form: the server refuses a name that
// exists (422 duplicate_product_name), an administrator may add it anyway with a reason, and a name that is only close to another
// is a warning the person decides on. The texts are the owner's: "This product already exists: <name> (code). Open it?".

/** The minimum length of the reason for adding a duplicate anyway, as the server's. */
export const MIN_DUPLICATE_REASON = 5;

/** The existing item a refusal names, or null when the error is something else. */
export interface ExistingItem { id: string; code: string; name: string; mayAddAnyway: boolean }

export function existingOf(error: unknown): ExistingItem | null {
  if (!(error instanceof RetailError) || error.code !== 'duplicate_product_name') return null;
  const p = error.problem ?? {};
  return { id: String(p['existing_id'] ?? ''), code: String(p['existing_code'] ?? ''), name: String(p['existing_name'] ?? ''), mayAddAnyway: p['may_add_anyway'] === true };
}

/** The sentence the owner asked for. */
export function existsText(item: { name: string; code: string }): string {
  return `This product already exists: ${item.name} (${item.code}).`;
}

/** The reason typed for an anyway-add, or null when it is too short. */
export function duplicateReasonOf(text: string): string | null {
  const reason = text.trim();
  return reason.length >= MIN_DUPLICATE_REASON ? reason : null;
}

/** The name already exists in this unit: open the item, change the name, or (an administrator) add it anyway with a reason. */
export function ExistsPanel({ item, canAddAnyway, busy, onOpen, onAddAnyway, onChangeName }: {
  item: ExistingItem;
  canAddAnyway: boolean;
  busy?: boolean;
  onOpen: (id: string) => void;
  onAddAnyway: (reason: string) => void;
  onChangeName: () => void;
}) {
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState('');
  const ready = duplicateReasonOf(reason);
  return (
    <section className="alert alert-warning" role="alert" aria-label="This product already exists" data-testid="name-exists">
      <p><strong>{existsText(item)}</strong> Open it?</p>
      <p className="cluster">
        <button type="button" className="rt-primary" onClick={() => onOpen(item.id)}>Open it</button>
        <button type="button" className="btn-ghost" onClick={onChangeName}>Change the name</button>
        {canAddAnyway && !asking && <button type="button" className="btn-sm" onClick={() => setAsking(true)}>Add anyway</button>}
      </p>
      {canAddAnyway && asking && (
        <div className="stack">
          <label htmlFor="duplicate-reason">Why is this a different item? (at least {MIN_DUPLICATE_REASON} characters)</label>
          <input id="duplicate-reason" value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} autoComplete="off" />
          <p className="hint">The reason is kept with the item.</p>
          <BusyButton busy={busy ?? false} type="button" disabled={ready === null} onClick={() => ready && onAddAnyway(ready)}>Add anyway</BusyButton>
        </div>
      )}
    </section>
  );
}

/** What a close item looks like in the warning: the same name in another unit, or a few letters apart. */
export function closeText(s: SimilarProduct): string {
  const kept = `${s.description} (${s.code}), per ${s.unit}`;
  return s.distance === 0 ? `${kept}: the same name in another unit.` : `${kept}${s.active === false ? ', switched off' : ''}.`;
}

/** Items whose name is close to the one typed: check it is not the same item twice, then save or change the name. */
export function SimilarPanel({ items, busy, onOpen, onSaveAnyway, onChangeName }: {
  items: SimilarProduct[];
  busy?: boolean;
  onOpen: (id: string) => void;
  onSaveAnyway: () => void;
  onChangeName: () => void;
}) {
  return (
    <section className="alert alert-warning" role="note" aria-label="Similar items" data-testid="name-similar">
      <p><strong>{items.length === 1 ? 'An item with a similar name exists.' : 'Items with similar names exist.'}</strong> Check you are not adding the same item twice.</p>
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {items.map((s) => (
          <li key={s.id} className="rt-row">
            <span>{closeText(s)}</span>
            <button type="button" className="btn-sm" onClick={() => onOpen(s.id ?? '')} aria-label={`Open ${s.description}`}>Open</button>
          </li>
        ))}
      </ul>
      <p className="cluster">
        <BusyButton busy={busy ?? false} type="button" className="rt-primary" onClick={onSaveAnyway}>This is a different item, save it</BusyButton>
        <button type="button" className="btn-ghost" onClick={onChangeName}>Change the name</button>
      </p>
    </section>
  );
}
