import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, useNavigate, useSearch } from '@tanstack/react-router';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { businessToday, retail, type GoodsReceipt, type LandedCostPreview } from '../../../api/retail';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { newIdempotencyKey, usePersistedDraft } from './idempotency';
import { AllocationTable } from './landed-costs';
import {
  CHARGE_KINDS, SPREAD_METHODS, addReceipt, blankCharge, blankLanded, buildLandedRequest, chargeProblem, chargesTotal, landedProblem, lineProblem, methodWords, previewKey, removeReceipt,
  sharesLeft, tickedLines, typedTotal, type ChargeForm, type GoodsLineForm, type LandedForm,
} from './landed-state';
import { showQty } from './maths';
import { SupplierField, useSuppliers, type SupplierOption } from './purchasing-ui';
import { showDate } from './transfers';
import { Gate, Problem, money } from './ui';
import { Hint } from '../../../app/ui/Hint';

// The landed cost form (the purchasing capability, issue #199). Choose who is owed (the forwarder or agent, one of your supplier
// rows) and their invoice, type the charges and the total printed on the invoice, choose how to share them over the goods, find the
// deliveries they belong to and tick the lines. A Review step asks the server to work out each line's share (and, for a person who may
// see profit, what it does to stock value and cost) before anything is saved. The draft survives a reload.

/** Find posted deliveries and add their lines to the form. */
export function GoodsPicker({ onAdd, chosen }: { onAdd: (receipt: GoodsReceipt) => void; chosen: string[] }) {
  const search = useLiveSearch();
  const term = search.term;
  const found = useQuery({ queryKey: ['retail', 'goods-receipts', 'pick', term], queryFn: ({ signal }) => retail.listGoodsReceipts({ query: term, status: 'posted', signal }) });
  const add = useMutation({ mutationFn: (id: string) => retail.getGoodsReceipt(id), onSuccess: onAdd });
  const rows = (found.data?.items ?? []).filter((r) => !chosen.includes(r.id ?? ''));
  return (
    <div className="stack" data-tour="landed-goods">
      <SearchBox id="landed-goods-search" label="Find a delivery these charges belong to" hint="Type the delivery number, the order number, the delivery note or the supplier." search={search} fetching={found.isFetching} count={found.data ? rows.length : undefined} />
      <Problem error={found.error ?? add.error} />
      <SearchResults fetching={found.isFetching}>
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {rows.map((r) => (
            <li key={r.id} className="rt-card" data-testid={`pick-${r.receipt_no}`}>
              <p>
                <strong>{r.receipt_no}</strong> from {r.supplier_name}, {showDate(r.received_on)}, {money(r.total_minor ?? 0)}
                {r.held_for_costing ? <span className="hint"> Held until the landed cost is in.</span> : null}
              </p>
              <BusyButton busy={add.isPending && add.variables === (r.id ?? '')} type="button" className="btn-sm" disabled={add.isPending} onClick={() => add.mutate(r.id ?? '')} aria-label={`Add ${r.receipt_no}`}>Add this delivery</BusyButton>
            </li>
          ))}
        </ul>
      </SearchResults>
      {found.data && rows.length === 0 && <p className="empty-state">{term ? 'No other delivery matches.' : 'There is no other delivery to add.'}</p>}
    </div>
  );
}

function ChargeRow({ charge, index, onChange, onRemove }: { charge: ChargeForm; index: number; onChange: (c: ChargeForm) => void; onRemove?: () => void }) {
  const problem = charge.amount.trim() === '' ? null : chargeProblem(charge);
  return (
    <li className="rt-card" data-testid={`charge-${index}`}>
      <div className="rt-row">
        <div>
          <label htmlFor={`charge-kind-${index}`}>Kind of charge {index + 1}</label>
          <select id={`charge-kind-${index}`} value={charge.kind} onChange={(e) => onChange({ ...charge, kind: e.target.value as ChargeForm['kind'] })}>
            {CHARGE_KINDS.map((k) => <option key={k.value} value={k.value}>{k.label}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor={`charge-amount-${index}`}>Amount of charge {index + 1}</label>
          <input id={`charge-amount-${index}`} inputMode="numeric" value={charge.amount} onChange={(e) => onChange({ ...charge, amount: e.target.value })} aria-invalid={problem !== null} />
        </div>
      </div>
      <label htmlFor={`charge-what-${index}`}>What charge {index + 1} is for (optional)</label>
      <input id={`charge-what-${index}`} value={charge.description} maxLength={200} onChange={(e) => onChange({ ...charge, description: e.target.value })} />
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      {onRemove && <button type="button" className="btn-ghost" onClick={onRemove} aria-label={`Remove charge ${index + 1}`}>Remove</button>}
    </li>
  );
}

function GoodsLineRow({ line, method, onChange }: { line: GoodsLineForm; method: LandedForm['method']; onChange: (l: GoodsLineForm) => void }) {
  const problem = line.ticked ? lineProblem(line, method) : null;
  const touched = (method === 'weight' && line.weight.trim() !== '') || (method === 'manual' && line.amount.trim() !== '');
  return (
    <li className="rt-card" data-testid={`landed-line-${line.receiptLineId}`}>
      <label>
        <input type="checkbox" checked={line.ticked} onChange={(e) => onChange({ ...line, ticked: e.target.checked })} />
        <span>
          <strong>Share over {line.description}</strong>
          <span className="hint"> {showQty(line.qty)} {line.unit} received, worth {money(line.valueMinor)}</span>
        </span>
      </label>
      {line.ticked && method === 'weight' && (
        <>
          <label htmlFor={`lw-${line.receiptLineId}`}>Weight of {line.description}</label>
          <input id={`lw-${line.receiptLineId}`} inputMode="decimal" value={line.weight} onChange={(e) => onChange({ ...line, weight: e.target.value })} aria-invalid={problem !== null && touched} />
        </>
      )}
      {line.ticked && method === 'manual' && (
        <>
          <label htmlFor={`la-${line.receiptLineId}`}>Share of {line.description}</label>
          <input id={`la-${line.receiptLineId}`} inputMode="numeric" value={line.amount} onChange={(e) => onChange({ ...line, amount: e.target.value })} aria-invalid={problem !== null && touched} />
        </>
      )}
      {problem && touched && <p role="alert" className="rt-flag">{problem}</p>}
    </li>
  );
}

/** The whole form, controlled by the page so a draft survives a reload. `picker` finds the deliveries. */
export function LandedFormView({ form, suppliers, picker, onChange, onReview }: {
  form: LandedForm;
  suppliers: SupplierOption[];
  picker: ReactNode;
  onChange: (form: LandedForm) => void;
  onReview: () => void;
}) {
  const problem = landedProblem(form);
  const typed = typedTotal(form);
  const charges = chargesTotal(form);
  const setCharge = (i: number, c: ChargeForm) => onChange({ ...form, charges: form.charges.map((x, n) => (n === i ? c : x)) });
  const setLine = (id: string, l: GoodsLineForm) => onChange({ ...form, lines: form.lines.map((x) => (x.receiptLineId === id ? l : x)) });
  return (
    <form className="stack" onSubmit={(e) => { e.preventDefault(); if (!problem) onReview(); }} data-tour="landed-form">
      <div className="stack" data-tour="landed-who">
        <h2>Who is owed</h2>
        <SupplierField id="landed-supplier" label="Who is owed (the forwarder, carrier or agent)" value={form.supplierId} suppliers={suppliers} onChange={(supplierId) => onChange({ ...form, supplierId })} />
        <Hint>They must be in your list of suppliers. Add them in the Catalogue first if they are not.</Hint>
        <label htmlFor="landed-ref">Their invoice number</label>
        <input id="landed-ref" value={form.supplierRef} maxLength={60} onChange={(e) => onChange({ ...form, supplierRef: e.target.value })} />
        <label htmlFor="landed-date">Date of the charges</label>
        <input id="landed-date" type="date" value={form.costDate} onChange={(e) => onChange({ ...form, costDate: e.target.value })} />
        <label htmlFor="landed-due">Due on</label>
        <input id="landed-due" type="date" value={form.dueDate} onChange={(e) => onChange({ ...form, dueDate: e.target.value })} />
      </div>

      <h2>The charges</h2>
      <ul style={{ listStyle: 'none', padding: 0 }} data-tour="landed-charges">
        {form.charges.map((c, i) => (
          <ChargeRow key={c.key} charge={c} index={i} onChange={(x) => setCharge(i, x)} onRemove={form.charges.length > 1 ? () => onChange({ ...form, charges: form.charges.filter((_, n) => n !== i) }) : undefined} />
        ))}
      </ul>
      <p><button type="button" className="btn-sm" onClick={() => onChange({ ...form, charges: [...form.charges, blankCharge(newIdempotencyKey())] })}>Add a charge</button></p>
      <p className="rt-total" aria-live="polite" data-testid="landed-charges-total">The charges come to {money(charges)}.</p>
      <label htmlFor="landed-total">Invoice total, as printed</label>
      <input id="landed-total" inputMode="numeric" value={form.total} onChange={(e) => onChange({ ...form, total: e.target.value })} />
      {typed !== null && typed !== charges && (
        <p role="alert" className="rt-flag" data-testid="landed-total-mismatch">The total you typed is not the sum of the charges. Check both against the invoice.</p>
      )}

      <fieldset data-tour="landed-method">
        <legend>How to spread the charges over the goods</legend>
        {SPREAD_METHODS.map((m) => (
          <p key={m.value}>
            <label>
              <input type="radio" name="landed-method" checked={form.method === m.value} onChange={() => onChange({ ...form, method: m.value })} />
              <span><strong>{m.label}.</strong> {m.sentence}</span>
            </label>
          </p>
        ))}
      </fieldset>

      <h2>The goods</h2>
      {picker}
      {form.receipts.length === 0 && <p className="empty-state">No delivery chosen yet. Find one above and add it.</p>}
      {form.receipts.map((r) => (
        <section key={r.id} aria-label={`Delivery ${r.receiptNo}`} className="stack">
          <h3>{r.receiptNo} from {r.supplierName}, {showDate(r.receivedOn)}</h3>
          <ul style={{ listStyle: 'none', padding: 0 }}>
            {form.lines.filter((l) => l.receiptId === r.id).map((l) => <GoodsLineRow key={l.receiptLineId} line={l} method={form.method} onChange={(x) => setLine(l.receiptLineId, x)} />)}
          </ul>
          <button type="button" className="btn-ghost" onClick={() => onChange(removeReceipt(form, r.id))} aria-label={`Take ${r.receiptNo} off`}>Take this delivery off</button>
        </section>
      ))}
      {form.method === 'manual' && tickedLines(form).length > 0 && (
        <p data-testid="landed-shares-left" className={sharesLeft(form) === 0 ? 'hint' : 'rt-flag'}>
          {sharesLeft(form) === 0 ? 'The shares add up to the total.' : sharesLeft(form) > 0 ? `${money(sharesLeft(form))} of the total is not given to a line yet.` : `${money(-sharesLeft(form))} more is given to lines than the total.`}
        </p>
      )}

      <label htmlFor="landed-note">Note (optional)</label>
      <input id="landed-note" value={form.note} maxLength={300} onChange={(e) => onChange({ ...form, note: e.target.value })} />
      {problem && <p role="alert" className="rt-flag" data-testid="landed-problem">{problem}</p>}
      <button type="submit" className="rt-primary" disabled={problem !== null} data-tour="landed-review">Review</button>
    </form>
  );
}

/** What the server worked out, for a last look before saving. */
export function ReviewPanel({ form, payee, preview, busy, error, onBack, onSave }: {
  form: LandedForm;
  /** The name of who is owed. */
  payee: string;
  preview: LandedCostPreview | undefined;
  busy?: boolean;
  error?: unknown;
  onBack: () => void;
  onSave: () => void;
}) {
  const lines = preview?.lines ?? [];
  return (
    <section className="stack" aria-label="Review the landed cost" data-testid="landed-review">
      <h2>Review</h2>
      <p>
        {money(chargesTotal(form))} owed to {payee}, shared <strong>{methodWords(form.method).toLowerCase()}</strong> over {tickedLines(form).length} {tickedLines(form).length === 1 ? 'line' : 'lines'}.
      </p>
      {!preview && !error && <SkeletonList label="Working out the shares" />}
      {preview?.total_mismatch && <p role="alert" className="alert alert-danger">The total you typed is not the sum of the charges. Go back and check both against the invoice.</p>}
      {preview && <AllocationTable lines={lines} total={preview.total_minor} />}
      <Problem error={error} />
      <p className="cluster">
        <BusyButton busy={busy ?? false} type="button" className="rt-primary" disabled={!preview || preview.total_mismatch === true} onClick={onSave}>Save landed cost</BusyButton>
        <button type="button" /* busy-exempt: going back waits for the save */ className="btn-ghost" onClick={onBack} disabled={busy}>Change something</button>
      </p>
    </section>
  );
}

export function LandedCostNewPage() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [reviewing, setReviewing] = useState(false);
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<LandedForm>(`landed-cost:${me.user_id ?? ''}`, blankLanded(businessToday()));
  // "Add landed cost" on a delivery arrives with that delivery already chosen.
  const params = useSearch({ strict: false }) as { receipt?: string };
  const wanted = typeof params.receipt === 'string' ? params.receipt : '';
  const applied = useRef('');
  useEffect(() => {
    if (wanted && applied.current !== wanted) {
      applied.current = wanted;
      void retail.getGoodsReceipt(wanted).then((r) => setDraft((d) => addReceipt(d, r)), () => undefined);
    }
  }, [wanted, setDraft]);
  const suppliers = useSuppliers(draft.supplierId);
  const preview = useQuery({
    queryKey: ['retail', 'landed-preview', previewKey(draft)],
    queryFn: () => retail.previewLandedCost(buildLandedRequest(draft)),
    enabled: reviewing && landedProblem(draft) === null,
    retry: false,
  });
  const save = useMutation({
    mutationFn: () => retail.createLandedCost(buildLandedRequest(draft), key),
    onSuccess: (made) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      void navigate({ to: '/staff/retail/landed-costs/$landedCostId', params: { landedCostId: made.id ?? '' } });
    },
  });
  const picker = <GoodsPicker chosen={draft.receipts.map((r) => r.id)} onAdd={(receipt) => setDraft((d) => addReceipt(d, receipt))} />;
  return (
    <Gate screen="landedCosts" title="New landed cost">
      <Hint>Record freight, duty, clearing or insurance for goods you have received. You see the share of every line before anything is saved.</Hint>
      {!reviewing ? (
        <LandedFormView form={draft} suppliers={suppliers} picker={picker} onChange={(f) => setDraft(() => f)} onReview={() => { save.reset(); setReviewing(true); }} />
      ) : (
        <ReviewPanel form={draft} payee={suppliers.find((s) => s.id === draft.supplierId)?.name ?? 'the payee'} preview={preview.data} busy={save.isPending} error={preview.error ?? save.error} onBack={() => setReviewing(false)} onSave={() => save.mutate()} />
      )}
      {!reviewing && draft.receipts.length > 0 && <button type="button" className="btn-ghost" onClick={() => { discard(); save.reset(); }}>Clear this form</button>}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/landed-costs/new')({ component: LandedCostNewPage });
