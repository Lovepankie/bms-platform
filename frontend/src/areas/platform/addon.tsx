import { useQuery, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { BusyButton } from '../../app/ui/BusyButton';
import { addAiAddonPrice, aiAddon, type BusinessDetail, OperatorError, setBusinessAiAddon } from '../../api/operator';
import { formatMinor, parseMinor } from '../../components/money';
import { ConfirmPanel } from './operator-parts';
import { addonLine, formatDay, reasonProblem } from './operator-state';

// The AI assistant add-on (issue #423, ADR-112). On the Business page: whether the business may use the assistant, charged or not,
// switched by the operator with a reason. On Plans and prices: the add-on's own monthly price over time, typed by the operator and
// never seeded. Every business starts with it off. Nothing is charged in the free month, so a business can try it free then.

type Change = 'on' | 'free' | 'off';

const SENTENCES: Record<Change, (name: string, price: string) => string> = {
  on: (name, price) => `Switch the AI assistant on for ${name}. From the end of its free month, ${price} is added to what it owes each month.`,
  free: (name) => `Switch the AI assistant on for ${name} with no charge, as for a demo or a pilot.`,
  off: (name) => `Switch the AI assistant off for ${name}. It stops at once and nothing more is charged for it.`,
};

const errorText = (err: unknown) => (err instanceof OperatorError || err instanceof Error ? err.message : 'Something went wrong.');

export function AddonSection({ tenantId, detail }: { tenantId: string; detail: BusinessDetail }) {
  const client = useQueryClient();
  const [open, setOpen] = useState<Change | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const addon = detail.ai_addon;
  const currency = detail.tenant?.currency ?? 'UGX';
  const name = detail.tenant?.name ?? 'the business';
  const price = addon?.monthly_minor == null ? 'the add-on price' : formatMinor(addon.monthly_minor, currency);

  async function confirm(change: Change, reason: string) {
    setBusy(true);
    setError(null);
    try {
      await setBusinessAiAddon(tenantId, change !== 'off', change === 'free', reason);
      setDone(change === 'off' ? 'The AI assistant is off for this business.' : 'The AI assistant is on for this business.');
      setOpen(null);
      await client.invalidateQueries({ queryKey: ['business', tenantId] });
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="biz-addon-heading" data-tour="biz-addon">
      <h2 id="biz-addon-heading">AI assistant add-on</h2>
      <p>{addonLine(addon, currency)}</p>
      {done && <p role="status" className="alert alert-success">{done}</p>}
      <div className="ln-actions">
        {!addon?.on && (
          <>
            <button type="button" className="btn-primary" onClick={() => setOpen('on')}>Switch on</button>
            <button type="button" onClick={() => setOpen('free')}>Switch on with no charge</button>
          </>
        )}
        {addon?.on && (
          <button type="button" onClick={() => setOpen('off')}>Switch off</button>
        )}
      </div>
      {open && (
        <ConfirmPanel
          id="addon-confirm"
          sentence={SENTENCES[open](name, price)}
          confirm={open === 'off' ? 'Yes, switch it off' : 'Yes, switch it on'}
          busy={busy}
          error={error}
          onConfirm={(reason) => void confirm(open, reason)}
          onCancel={() => {
            setOpen(null);
            setError(null);
          }}
        />
      )}
    </section>
  );
}

export function AddonPriceSection() {
  const client = useQueryClient();
  const query = useQuery({ queryKey: ['addon', 'ai_assistant'], queryFn: aiAddon });
  const [amount, setAmount] = useState('');
  const [from, setFrom] = useState('');
  const [reason, setReason] = useState('');
  const [confirmLarge, setConfirmLarge] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [unusual, setUnusual] = useState(false);
  const detail = query.data;

  async function save(e: FormEvent) {
    e.preventDefault();
    const minor = parseMinor(amount, 'UGX');
    const why = reasonProblem(reason);
    if (minor === null || minor < 0) {
      setError('Type the monthly price, in shillings.');
      return;
    }
    if (why) {
      setError(why);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const saved = await addAiAddonPrice({
        monthly_minor: minor,
        effective_from: from || undefined,
        reason: reason.trim(),
        confirm_large_change: confirmLarge || undefined,
      });
      client.setQueryData(['addon', 'ai_assistant'], saved);
      setAmount('');
      setReason('');
      setConfirmLarge(false);
      setUnusual(false);
    } catch (err) {
      setUnusual(err instanceof OperatorError && err.code === 'price_change_unusual');
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section aria-labelledby="addon-price-heading" data-tour="plans-addon">
      <h2 id="addon-price-heading">AI assistant add-on</h2>
      <p className="hint">Added each month to what a business owes while the add-on is on.</p>
      {query.error && <p role="alert" className="alert alert-danger">{errorText(query.error)}</p>}
      {detail && (
        <p>
          <strong>
            {detail.price_today?.monthly_minor != null
              ? `${formatMinor(detail.price_today.monthly_minor, detail.price_today.currency ?? 'UGX')} a month`
              : 'No price entered yet'}
          </strong>
          <span className="hint"> On for {detail.tenants_on ?? 0} {detail.tenants_on === 1 ? 'business' : 'businesses'}.</span>
        </p>
      )}
      <form className="form-stack" aria-label="Set the add-on price" onSubmit={(e) => void save(e)}>
        <label htmlFor="addon-price">Monthly price (UGX)</label>
        <input id="addon-price" inputMode="numeric" className="num" value={amount} onChange={(e) => setAmount(e.target.value)} />
        <label htmlFor="addon-from">Starts on (empty is today)</label>
        <input id="addon-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        <label htmlFor="addon-reason">Why (kept with the price)</label>
        <textarea id="addon-reason" rows={2} maxLength={300} value={reason} onChange={(e) => setReason(e.target.value)} />
        {unusual && (
          <label>
            <input type="checkbox" checked={confirmLarge} onChange={(e) => setConfirmLarge(e.target.checked)} /> I checked this price
          </label>
        )}
        {error && <p role="alert" className="alert alert-danger">{error}</p>}
        <p><BusyButton type="submit" className="btn-primary" busy={busy}>Save the price</BusyButton></p>
      </form>
      {detail && (detail.prices ?? []).length > 0 && (
        <div className="table-wrap table-cards">
        <table className="table">
          <caption>Prices of the add-on, newest first</caption>
          <thead>
            <tr><th scope="col">From</th><th scope="col" className="num">A month</th><th scope="col">Why</th></tr>
          </thead>
          <tbody>
            {(detail.prices ?? []).map((p) => (
              <tr key={p.id}>
                <td data-label="From">{p.effective_from ? formatDay(p.effective_from) : ''}</td>
                <td data-label="A month" className="num">{formatMinor(p.monthly_minor ?? 0, p.currency ?? 'UGX')}</td>
                <td data-label="Why" className="cell-stack">{p.reason}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
    </section>
  );
}
