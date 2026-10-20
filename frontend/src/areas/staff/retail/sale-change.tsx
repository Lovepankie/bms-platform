import { RETAIL_CURRENCY, type Sale, type SalePayment } from '../../../api/retail';
import { formatMinor } from '../../../components/money';
import { NOTES, changeDue, readReceived, receivedProblem, takesCash } from './sale-change-state';

// The amount received and the change to give (issue #346). Drawn by the sale form for a cash sale only;
// it keeps nothing itself: the text lives in the form's own state, so it goes with the form after each sale.

const grouped = (n: number) => formatMinor(n, RETAIL_CURRENCY).slice(RETAIL_CURRENCY.length + 1);

/** The field, the quick notes and the change in large plain words. Nothing for mobile money, bank or credit. */
export function ChangePanel({ method, total, text, onText }: { method: SalePayment; total: number; text: string; onText: (v: string) => void }) {
  if (!takesCash(method)) return null;
  const r = readReceived(text);
  const problem = receivedProblem(method, total, text);
  return (
    <fieldset data-tour="sale-change" data-testid="sale-change">
      <legend>Cash from the customer</legend>
      <label htmlFor="amount-received">Amount received (UGX)</label>
      <input
        id="amount-received"
        inputMode="numeric"
        autoComplete="off"
        value={text}
        placeholder={total > 0 ? grouped(total) : '0'}
        onChange={(e) => onText(e.target.value)}
        aria-invalid={problem !== null}
        aria-describedby="amount-received-hint"
      />
      <p id="amount-received-hint" className="hint">Leave it empty if you do not need the change.</p>
      <div className="rt-row">
        {NOTES.map((n) => (
          <button key={n} type="button" className="btn-sm" onClick={() => onText(String(n))}>{grouped(n)}</button>
        ))}
        <button type="button" className="btn-sm" disabled={total <= 0} onClick={() => onText(String(total))}>Exact</button>
      </div>
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      {r.kind === 'ok' && problem === null && total > 0 && (
        <p className="rt-total" aria-live="polite" data-testid="change-due">
          <span>Change to give</span>
          <span>{formatMinor(changeDue(total, r.amount), RETAIL_CURRENCY)}</span>
        </p>
      )}
    </fieldset>
  );
}

/** The receipt line of a cash sale: what the customer gave and the change. Display only; the sale is recorded at its total. */
export function ReceiptChange({ sale, received }: { sale: Sale; received?: number }) {
  if (received === undefined || sale.payment_method !== 'cash') return null;
  return (
    <p data-testid="receipt-change">
      Received {formatMinor(received, RETAIL_CURRENCY)}. Change given: <strong>{formatMinor(changeDue(sale.total_minor ?? 0, received), RETAIL_CURRENCY)}</strong>
    </p>
  );
}
