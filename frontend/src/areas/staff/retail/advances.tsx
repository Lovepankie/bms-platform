import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, retail, type Advance, type AdvanceReport, type Repayment } from '../../../api/retail';
import { useStaff } from '../context';
import { amountOrNull, enteredBy, voidOffered } from './cash-state';
import { DateField, ImportedBadge, RecordsByShop, Saved, VoidAction, useMayVoid, useReadScope, useSavedFor } from './cash-ui';
import { usePersistedDraft } from './idempotency';
import { PartyPicker, useCashParties } from './party-picker';
import { CASHBOOK_READ, holds } from './permissions';
import { useToast } from './toast';
import { BranchRequired, Gate, Problem, money, useBranchName, useSingleBranch } from './ui';
import { showDate } from './transfers';

// Advances to the owner or the company, and their repayments (FR-RET-26). Not the lending module: no
// schedule, interest or collateral. Recording an advance needs retail.advance.create, a repayment
// retail.advance.repay; the list, a single advance and the outstanding report need retail.cashbook.read.

export const ADVANCE_CREATE = 'retail.advance.create';
export const ADVANCE_REPAY = 'retail.advance.repay';
const ADVANCE_KINDS = ['owner', 'staff', 'related_entity'] as const;

const METHODS: { value: 'cash' | 'mobile_money' | 'bank'; label: string }[] = [
  { value: 'cash', label: 'Cash' },
  { value: 'mobile_money', label: 'Mobile money' },
  { value: 'bank', label: 'Bank' },
];

/** "ADV-000001, Test Owner 01, owes UGX 50,000": how an advance reads in a list. */
export const advanceLabel = (a: Advance): string => `${a.advance_no ?? 'Advance'}, ${a.party_name ?? 'unknown'}, owes ${money(a.balance_minor ?? 0)}`;

/** What stops a repayment, in plain words, or null. The server has the last word (it checks the balance itself). */
export function repaymentProblem(advance: Advance | undefined, amountText: string): string | null {
  if (!advance) return 'Choose the advance being repaid.';
  const minor = amountOrNull(amountText);
  if (minor === null) return 'Enter the amount in whole shillings.';
  if (minor > (advance.balance_minor ?? 0)) return `That is more than the ${money(advance.balance_minor ?? 0)} still owed on this advance.`;
  return null;
}

interface AdvanceDraft { date: string; partyId: string; takenById: string; principal: string; purpose: string }

export function AdvanceForm({ branchId, onSaved }: { branchId: string; onSaved?: (saved: Advance) => void }) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const { key, draft, setDraft, finish } = usePersistedDraft<AdvanceDraft>(`advance:${me.user_id ?? ''}:${branchId}`, { date: businessToday(), partyId: '', takenById: '', principal: '', purpose: '' });
  const parties = useCashParties();
  const minor = amountOrNull(draft.principal);
  const save = useMutation({
    mutationFn: () =>
      retail.createAdvance({
        branch_id: branchId, business_date: draft.date, party_id: draft.partyId, principal_minor: minor ?? 0,
        ...(draft.takenById ? { taken_by_party_id: draft.takenById } : {}), ...(draft.purpose.trim() ? { purpose: draft.purpose.trim() } : {}),
      }, key),
    onSuccess: (saved) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved?.(saved);
    },
  });
  const blocked = draft.partyId === '' || minor === null || save.isPending;
  const update = (patch: Partial<AdvanceDraft>) => setDraft((d) => ({ ...d, ...patch }));
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!blocked) save.mutate(); }}>
      <DateField id="advance-date" label="Date" value={draft.date} onChange={(date) => update({ date })} />
      <PartyPicker
        id="advance-party" label="Who is the advance for?" parties={parties.data ?? []} kinds={[...ADVANCE_KINDS]} listed={[...ADVANCE_KINDS]} value={draft.partyId}
        onChange={(partyId) => update({ partyId })} noneLabel="Choose who it is for" addLabel="Add a new person or company"
      />
      <PartyPicker
        id="advance-taken-by" label="Who took the money? (optional)" parties={parties.data ?? []} kinds={[...ADVANCE_KINDS]} listed={[...ADVANCE_KINDS]} value={draft.takenById}
        onChange={(takenById) => update({ takenById })} noneLabel="Not recorded" addLabel="Add a new person" optional
      />
      <label htmlFor="advance-principal">Amount advanced</label>
      <input id="advance-principal" inputMode="numeric" value={draft.principal} onChange={(e) => update({ principal: e.target.value })} aria-invalid={draft.principal.trim() !== '' && minor === null} />
      {draft.principal.trim() !== '' && minor === null && <p role="alert" className="rt-flag">Enter the amount in whole shillings.</p>}
      <label htmlFor="advance-purpose">What is it for? (optional)</label>
      <input id="advance-purpose" maxLength={300} value={draft.purpose} onChange={(e) => update({ purpose: e.target.value })} />
      <p className="hint">The money is taken out of this shop's till.</p>
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={blocked}>
        {save.isPending ? 'Saving' : 'Save advance'}
      </button>
    </form>
  );
}

interface RepaymentDraft { date: string; advanceId: string; method: 'cash' | 'mobile_money' | 'bank'; amount: string }

export function RepaymentForm({ branchId, onSaved }: { branchId: string; onSaved?: (saved: Repayment, advance: Advance) => void }) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const { key, draft, setDraft, finish } = usePersistedDraft<RepaymentDraft>(`repayment:${me.user_id ?? ''}:${branchId}`, { date: businessToday(), advanceId: '', method: 'cash', amount: '' });
  const canList = holds(me, CASHBOOK_READ);
  // Only advances that still have a balance can be repaid.
  const open = useQuery({ queryKey: ['retail', 'advances', 'open'], queryFn: () => retail.listAdvances({ openOnly: true }), enabled: canList });
  const advance = (open.data ?? []).find((a) => a.id === draft.advanceId);
  const problem = repaymentProblem(advance, draft.amount);
  const save = useMutation({
    mutationFn: () => retail.createRepayment(draft.advanceId, { branch_id: branchId, amount_minor: amountOrNull(draft.amount) ?? 0, method: draft.method, paid_on: draft.date }, key),
    onSuccess: (saved) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      if (advance) onSaved?.(saved, advance);
    },
  });
  const blocked = problem !== null || save.isPending;
  const update = (patch: Partial<RepaymentDraft>) => setDraft((d) => ({ ...d, ...patch }));
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!blocked) save.mutate(); }}>
      {!canList && <p role="note" className="alert alert-info">You need access to the advances list to choose an advance to repay.</p>}
      {open.isError && <Problem error={open.error} />}
      <label htmlFor="repay-advance">Advance being repaid</label>
      <select id="repay-advance" value={draft.advanceId} onChange={(e) => update({ advanceId: e.target.value })}>
        <option value="">{open.data && open.data.length === 0 ? 'No advance has a balance' : 'Choose an advance'}</option>
        {(open.data ?? []).map((a) => (
          <option key={a.id} value={a.id}>{advanceLabel(a)}</option>
        ))}
      </select>
      <fieldset>
        <legend>How was it repaid?</legend>
        {METHODS.map((m) => (
          <label key={m.value} style={{ fontWeight: 400 }}>
            <input type="radio" name="repay-method" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={draft.method === m.value} onChange={() => update({ method: m.value })} />
            {m.label}
          </label>
        ))}
      </fieldset>
      <label htmlFor="repay-amount">Amount repaid</label>
      <input id="repay-amount" inputMode="numeric" value={draft.amount} onChange={(e) => update({ amount: e.target.value })} aria-invalid={draft.amount.trim() !== '' && problem !== null} />
      {advance && <p className="hint">Still owed on this advance: {money(advance.balance_minor ?? 0)}.</p>}
      {draft.amount.trim() !== '' && problem && <p role="alert" className="rt-flag">{problem}</p>}
      <DateField id="repay-date" label="Date" value={draft.date} onChange={(date) => update({ date })} />
      <p className="hint">The money goes into this shop's till or account.</p>
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={blocked}>
        {save.isPending ? 'Saving' : 'Save repayment'}
      </button>
    </form>
  );
}

export function AdvanceList({ onOpen }: { onOpen: (id: string) => void }) {
  const scope = useReadScope();
  const [openOnly, setOpenOnly] = useState(false);
  const rows = useQuery({ queryKey: ['retail', 'advances', scope, openOnly], queryFn: () => retail.listAdvances({ branchIds: scope, openOnly }) });
  return (
    <section aria-label="Advances">
      <div className="cluster filters" role="group" aria-label="Show">
        <button type="button" className="btn-sm" aria-pressed={!openOnly} onClick={() => setOpenOnly(false)}>All advances</button>
        <button type="button" className="btn-sm" aria-pressed={openOnly} onClick={() => setOpenOnly(true)}>Still owing</button>
      </div>
      {rows.isError && <Problem error={rows.error} />}
      {rows.isPending && <p className="loading">Loading</p>}
      {rows.data && (
        <RecordsByShop
          rows={rows.data}
          empty={openOnly ? 'No advance has a balance.' : 'No advances recorded yet.'}
          what="advance"
          renderRow={(a) => (
            <>
              <strong>{a.advance_no}</strong> <span className="muted">{showDate(a.business_date)}</span>
              <br />
              {a.party_name}{a.purpose ? `, ${a.purpose}` : ''}
              <br />
              Advanced {money(a.principal_minor ?? 0)}, repaid {money(a.repaid_minor ?? 0)}, <strong>{(a.balance_minor ?? 0) > 0 ? `owes ${money(a.balance_minor ?? 0)}` : 'fully repaid'}</strong>
              <br />
              <button type="button" className="btn-sm" onClick={() => onOpen(a.id ?? '')} aria-label={`Open ${a.advance_no}`}>Open</button>
            </>
          )}
        />
      )}
    </section>
  );
}

export function AdvanceDetailView({ advance, onVoided }: { advance: Advance; onVoided?: (message: string) => void }) {
  const mayVoid = useMayVoid()(advance.branch_id);
  const shopName = useBranchName();
  const repayments = advance.repayments ?? [];
  return (
    <section aria-label="Advance">
      <h2>
        {advance.advance_no} {advance.voided && <span className="badge badge-warning">Voided</span>} {advance.historical && <ImportedBadge />}
      </h2>
      <div className="table-wrap"><table>
        <tbody>
          <tr><td>For</td><td className="num">{advance.party_name}</td></tr>
          {advance.taken_by_name && <tr><td>Taken by</td><td className="num">{advance.taken_by_name}</td></tr>}
          <tr><td>Date</td><td className="num">{showDate(advance.business_date)}</td></tr>
          <tr><td>Shop</td><td className="num">{shopName(advance.branch_id)}</td></tr>
          {advance.purpose && <tr><td>Purpose</td><td className="num">{advance.purpose}</td></tr>}
          <tr><td>Advanced</td><td className="num">{money(advance.principal_minor ?? 0)}</td></tr>
          <tr><td>Repaid</td><td className="num">{money(advance.repaid_minor ?? 0)}</td></tr>
          <tr><td>Still owed</td><td className="num"><strong>{money(advance.balance_minor ?? 0)}</strong></td></tr>
        </tbody>
      </table></div>
      <p className="hint">{enteredBy(advance.by_name, advance.at)}{advance.voided && advance.void_reason ? `. Voided because: ${advance.void_reason}` : ''}</p>
      {voidOffered(advance, mayVoid) && (
        <>
          {repayments.some((r) => !r.voided) && <p className="hint">This advance has repayments. Void them first, then the advance.</p>}
          <VoidAction what="advance" run={(reason, key) => retail.voidAdvance(advance.id ?? '', reason, key)} onVoided={() => onVoided?.('Advance voided.')} />
        </>
      )}
      <h3>Repayments</h3>
      {repayments.length === 0 && <p className="empty-state">Nothing repaid yet.</p>}
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {repayments.map((r) => (
          <li key={r.id} className="rt-card">
            <strong>{showDate(r.paid_on)}</strong> <span className="muted">{money(r.amount_minor ?? 0)} by {METHODS.find((m) => m.value === r.method)?.label ?? r.method}</span>
            <p className="hint">
              {enteredBy(r.by_name, r.at)}
              {r.historical && <> <ImportedBadge /></>}
              {r.voided && <> <span className="badge badge-warning">Voided</span>{r.void_reason ? ` because: ${r.void_reason}` : ''}</>}
            </p>
            {voidOffered(r, mayVoid) && <VoidAction what="repayment" run={(reason, key) => retail.voidRepayment(advance.id ?? '', r.id ?? '', reason, key)} onVoided={() => onVoided?.('Repayment voided.')} />}
          </li>
        ))}
      </ul>
    </section>
  );
}

function AdvanceDetail({ id, onClose, onVoided }: { id: string; onClose: () => void; onVoided: (message: string) => void }) {
  const advance = useQuery({ queryKey: ['retail', 'advance', id], queryFn: () => retail.getAdvance(id) });
  return (
    <>
      <button type="button" onClick={onClose}>Back to the list</button>
      {advance.isError && <Problem error={advance.error} />}
      {advance.isPending && <p className="loading">Loading</p>}
      {advance.data && <AdvanceDetailView advance={advance.data} onVoided={onVoided} />}
    </>
  );
}

export function OutstandingView({ report }: { report: AdvanceReport }) {
  const parties = report.items ?? [];
  if (parties.length === 0) return <p className="empty-state">No advance has a balance.</p>;
  return (
    <>
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {parties.map((p) => (
          <li key={p.party_id} className="rt-card">
            <strong>{p.party_name}</strong>
            <p className="rt-row"><span>Advanced</span><span>{money(p.principal_minor ?? 0)}</span></p>
            <p className="rt-row"><span>Repaid</span><span>{money(p.repaid_minor ?? 0)}</span></p>
            <p className="rt-row"><span>Owes</span><strong>{money(p.balance_minor ?? 0)}</strong></p>
            <p className="hint">{p.count} {p.count === 1 ? 'advance' : 'advances'}, the oldest from {showDate(p.oldest_advance_date)}</p>
          </li>
        ))}
      </ul>
      <p className="rt-total">Total owed {money(report.balance_minor ?? 0)}</p>
    </>
  );
}

function Outstanding() {
  const scope = useReadScope();
  const report = useQuery({ queryKey: ['retail', 'advances-report', scope], queryFn: () => retail.advancesReport({ branchIds: scope }) });
  return (
    <section aria-label="Outstanding advances">
      {report.isError && <Problem error={report.error} />}
      {report.isPending && <p className="loading">Loading</p>}
      {report.data && <OutstandingView report={report.data} />}
    </section>
  );
}

type Mode = 'list' | 'new' | 'repay' | 'outstanding';

function AdvancesScreen() {
  const { me } = useStaff();
  const { branchId, branchName } = useSingleBranch();
  const canRead = holds(me, CASHBOOK_READ);
  const modes: { mode: Mode; label: string }[] = [
    ...(canRead ? [{ mode: 'list' as const, label: 'Advances' }] : []),
    ...(holds(me, ADVANCE_CREATE) ? [{ mode: 'new' as const, label: 'New advance' }] : []),
    ...(holds(me, ADVANCE_REPAY) ? [{ mode: 'repay' as const, label: 'Repay' }] : []),
    ...(canRead ? [{ mode: 'outstanding' as const, label: 'Who owes' }] : []),
  ];
  const [mode, setMode] = useState<Mode>(modes[0]?.mode ?? 'list');
  const [open, setOpen] = useState<string | null>(null);
  const [savedAdvance, setSavedAdvance] = useSavedFor<Advance>(branchId);
  const [savedRepayment, setSavedRepayment] = useSavedFor<{ repayment: Repayment; advance: Advance }>(branchId);
  const [round, setRound] = useState(0);
  const { show, toast } = useToast();
  const choose = (next: Mode) => { setMode(next); setOpen(null); setSavedAdvance(null); setSavedRepayment(null); };
  return (
    <Gate screen="advances" title="Advances">
      <div className="cluster filters" role="group" aria-label="Advances">
        {modes.map((m) => (
          <button key={m.mode} type="button" className="btn-sm" aria-pressed={mode === m.mode} onClick={() => choose(m.mode)}>{m.label}</button>
        ))}
      </div>

      {mode === 'list' && canRead && (open ? <AdvanceDetail id={open} onClose={() => setOpen(null)} onVoided={show} /> : <AdvanceList onOpen={setOpen} />)}
      {mode === 'outstanding' && canRead && <Outstanding />}

      {mode === 'new' && (branchId === null ? <BranchRequired permissions={[ADVANCE_CREATE]} /> : savedAdvance ? (
        <Saved title="Advance recorded" again="Record another advance" onAgain={() => { setSavedAdvance(null); setRound((n) => n + 1); }}>
          <p>{savedAdvance.advance_no}: <strong>{money(savedAdvance.principal_minor ?? 0)}</strong> for {savedAdvance.party_name}, from {branchName}'s till.</p>
        </Saved>
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{branchName}</strong></p>
          <AdvanceForm key={`${branchId}-${round}`} branchId={branchId} onSaved={(a) => { setSavedAdvance(a); show('Advance recorded.'); }} />
        </>
      ))}

      {mode === 'repay' && (branchId === null ? <BranchRequired permissions={[ADVANCE_REPAY]} /> : savedRepayment ? (
        <Saved title="Repayment recorded" again="Record another repayment" onAgain={() => { setSavedRepayment(null); setRound((n) => n + 1); }}>
          <p>
            {money(savedRepayment.repayment.amount_minor ?? 0)} repaid on {savedRepayment.advance.advance_no} ({savedRepayment.advance.party_name}).
            {savedRepayment.repayment.balance_minor !== undefined && <> Still owed: <strong>{money(savedRepayment.repayment.balance_minor)}</strong>.</>}
          </p>
        </Saved>
      ) : (
        <>
          <p className="branch-line">Receiving branch: <strong>{branchName}</strong></p>
          <RepaymentForm key={`${branchId}-${round}`} branchId={branchId} onSaved={(repayment, advance) => { setSavedRepayment({ repayment, advance }); show('Repayment recorded.'); }} />
        </>
      ))}
      {toast}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/advances')({ component: AdvancesScreen });
