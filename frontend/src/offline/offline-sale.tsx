import type { Product } from '../api/retail';
import { SearchBox } from '../app/ui/SearchBox';
import { useLiveSearch } from '../app/ui/useLiveSearch';
import { showQty } from '../areas/staff/retail/maths';
import { money } from '../areas/staff/retail/ui';
import { pricesAsOfWords, searchCatalogue } from './catalogue';
import { useOffline, useOutbox } from './offline-context';
import type { OutboxItem } from './outbox';

// The sale screen with no network (issue #458, ADR-067 decision 3): items found in the copy on the phone, a note that the
// sale stays on the phone, and the provisional receipt that the server's number replaces once the sale is sent.

/** The banner at the top of the sale form while offline. */
export function OfflineNote() {
  const offline = useOffline();
  return (
    <div role="status" className="alert alert-warning" data-testid="offline-note">
      <p>
        <strong>You are offline.</strong> This sale is kept on this phone and sent when the phone is back online.
      </p>
      <p className="muted">{pricesAsOfWords(offline?.catalogue?.pricesAsOf)}</p>
    </div>
  );
}

/** Search the items saved on this phone for the branch, with the price and the stock when the copy was made. */
export function OfflineProductPicker({ branchId, onAdd }: { branchId: string; onAdd: (p: Product) => void }) {
  const offline = useOffline();
  const search = useLiveSearch();
  const found = searchCatalogue(offline?.catalogue ?? null, branchId, search.term);
  return (
    <>
      <SearchBox id="sale-search" label="Find an item by name, code or category" search={search} fetching={false} count={found.length} />
      {found.length === 0 && <p className="muted">No item on this phone matches. Items are saved while the phone is online.</p>}
      <ul style={{ listStyle: 'none', padding: 0, maxHeight: 220, overflowY: 'auto' }}>
        {found.slice(0, 20).map((p) => (
          <li key={p.id} className="rt-card">
            <div className="rt-row">
              <span>
                <strong>{p.description}</strong> ({p.code})
                <br />
                {p.sell_minor !== undefined && `${money(p.sell_minor)} each, `}
                in stock when saved: {showQty(p.qty ?? '0')} {p.unit}
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

/** The receipt of a sale saved on the phone: provisional until sent, then with the server's number. */
export function ProvisionalReceipt({ item, onNew }: { item: OutboxItem; onNew: () => void }) {
  const outbox = useOutbox();
  const now = outbox.items.find((i) => i.id === item.id) ?? item;
  const sent = now.state === 'sent' && now.sale;
  return (
    <section aria-label="Sale receipt">
      <h2>{sent ? 'Sale sent' : 'Sale saved on this phone'}</h2>
      <p data-testid="receipt-number">
        {sent ? (
          <>
            Receipt <strong>{now.sale?.saleNo}</strong>
          </>
        ) : (
          <>
            Receipt <strong>{now.provisionalNo}</strong> (provisional)
          </>
        )}
      </p>
      <div className="table-wrap" tabIndex={0}>
        <table>
          <thead>
            <tr>
              <th>Item</th>
              <th className="num">Qty</th>
              <th className="num">Price</th>
              <th className="num">Total</th>
            </tr>
          </thead>
          <tbody>
            {now.lines.map((l, i) => (
              <tr key={i}>
                <td>{l.description}</td>
                <td className="num">{showQty(l.qty)}</td>
                <td className="num">{money(l.unitPriceMinor)}</td>
                <td className="num">{money(l.totalMinor)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="rt-total">Total {money(now.totalMinor)}</p>
      {now.state === 'failed' ? (
        <p role="alert" className="alert alert-danger">
          The server did not record this sale: {now.error?.message} A manager can have it sent again or discard it.
        </p>
      ) : (
        !sent && <p className="muted">Not yet sent. It goes to the server when the phone is back online.</p>
      )}
      <button type="button" className="rt-primary" onClick={onNew}>
        New sale
      </button>
    </section>
  );
}
