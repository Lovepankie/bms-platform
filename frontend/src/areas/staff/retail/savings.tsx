import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, retail, type Savings } from '../../../api/retail';
import { useStaff } from '../context';
import { DateField, RecordsByShop, Saved, WindowFields, useReadScope, useSavedFor, useWindow } from './cash-ui';
import { usePersistedDraft } from './idempotency';
import { CASHBOOK_READ, SAVINGS_OVERWRITE, SAVINGS_RECORD, holds } from './permissions';
import { afterSavingsConflict, planSavings, seesAmounts } from './savings-state';
import { useToast } from './toast';
import { BranchRequired, Gate, Note, Problem, money, useSingleBranch } from './ui';
import { showDate } from './transfers';

// Daily savings (FR-RET-18 to FR-RET-20): per shop per day. The server suggests half the day's profit; a
// caller without retail.profit.read never sees the profit, the suggestion or any amount, and sends none:
// the standard amount is recorded for them. Every save echoes the suggestion token the form was shown; if
// sales changed since, the server answers 409 suggestion_changed with the new token and writes nothing.

interface SavingsDraft { date: string; amount: string; reason: string }

export function SavingsForm({ branchId, onSaved }: { branchId: string; onSaved?: (saved: Savings) => void }) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const canOverwrite = holds(me, SAVINGS_OVERWRITE);
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<SavingsDraft>(`savings:${me.user_id ?? ''}:${branchId}`, { date: businessToday(), amount: '', reason: '' });
  const suggestionKey = ['retail', 'savings-suggestion', branchId, draft.date];
  const suggestion = useQuery({ queryKey: suggestionKey, queryFn: () => retail.savingsSuggestion({ branchId, date: draft.date }) });
  const s = suggestion.data;
  const profitView = seesAmounts(s);
  const plan = s ? planSavings({ branchId, date: draft.date, suggestion: s, amount: draft.amount, reason: draft.reason, canOverwrite }) : null;

  const save = useMutation({
    mutationFn: () => {
      if (!plan) throw new Error('The suggestion has not loaded');
      return retail.createSavings(plan.request, key);
    },
    onSuccess: (saved) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved?.(saved);
    },
    onError: (error) => {
      // A 409 means nothing was written: take what the server says now (a new token, or the record that won)
      // and, when the figures changed, start a fresh key and ask the person to look again before saving.
      if (afterSavingsConflict(queryClient, suggestionKey, error) === 'look_again') {
        discard();
        setDraft({ date: draft.date, amount: '', reason: '' });
      }
    },
  });

  const blocked = !s || !plan || plan.problem !== null || s.existing_id !== undefined || save.isPending;
  const update = (patch: Partial<SavingsDraft>) => setDraft((d) => ({ ...d, ...patch }));

  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!blocked) save.mutate(); }}>
      <DateField id="savings-date" label="Date" value={draft.date} onChange={(date) => update({ date, amount: '', reason: '' })} />
      {suggestion.isError && <Problem error={suggestion.error} />}
      {suggestion.isPending && <p className="loading">Loading</p>}
      {s && (
        <>
          <p>
            Sold on {showDate(s.business_date ?? draft.date)}: <strong>{money(s.total_sold_minor ?? 0)}</strong>
          </p>
          {s.existing_id !== undefined && <Note>Savings are already recorded for this shop on this day. If that record is wrong, void it in the records below, then save again.</Note>}
          {profitView ? (
            <>
              {s.daily_profit_minor !== undefined && <p>Profit for the day: <strong>{money(s.daily_profit_minor)}</strong></p>}
              <p className="rt-total" aria-live="polite">Suggested savings {money(s.suggested_minor ?? 0)}</p>
              <label htmlFor="savings-amount">Amount to save (leave blank to save the suggestion)</label>
              <input id="savings-amount" inputMode="numeric" value={draft.amount} onChange={(e) => update({ amount: e.target.value })} aria-invalid={plan?.problem != null && draft.amount.trim() !== ''} />
              {plan?.overwrite && canOverwrite && (
                <>
                  <label htmlFor="savings-reason">Why are you changing the amount? (at least 5 characters)</label>
                  <input id="savings-reason" maxLength={300} value={draft.reason} onChange={(e) => update({ reason: e.target.value })} />
                </>
              )}
              {plan?.overwrite && !canOverwrite && <Note>Only an administrator can change the amount. Clear the amount to save the suggested one.</Note>}
              {plan?.problem && draft.amount.trim() !== '' && !(plan.overwrite && !canOverwrite) && <p role="alert" className="rt-flag">{plan.problem}</p>}
            </>
          ) : (
            <Note>Savings will be recorded using the standard amount.</Note>
          )}
        </>
      )}
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={blocked}>
        {save.isPending ? 'Saving' : 'Save savings'}
      </button>
    </form>
  );
}

export function SavingsRecords({ onVoided }: { onVoided?: (message: string) => void }) {
  const scope = useReadScope();
  const window = useWindow();
  const rows = useQuery({
    queryKey: ['retail', 'savings', scope, window.from, window.to],
    queryFn: () => retail.listSavings({ branchIds: scope, ...window.query, includeVoided: true }),
  });
  return (
    <section aria-label="Savings records">
      <h2>Savings records</h2>
      <WindowFields id="savings-list" window={window} />
      {rows.isError && <Problem error={rows.error} />}
      {rows.isPending && <p className="loading">Loading</p>}
      {rows.data && (
        <RecordsByShop
          rows={rows.data}
          empty="No savings recorded in this period."
          what="savings record"
          voidWith={(row, reason, key) => retail.voidSavings(row.id ?? '', reason, key)}
          onVoided={() => onVoided?.('Savings record voided.')}
          renderRow={(row) => (
            <>
              <strong>{showDate(row.business_date)}</strong>
              <span className="muted"> Sold {money(row.total_sold_minor ?? 0)}</span>
              <br />
              {row.amount_minor !== undefined ? <>Saved {money(row.amount_minor)}</> : <>Savings recorded</>}
              {row.overwritten === true && <> (amount changed from the suggestion)</>}
            </>
          )}
        />
      )}
    </section>
  );
}

function SavingsScreen() {
  const { me } = useStaff();
  const { branchId, branchName } = useSingleBranch();
  const [saved, setSaved] = useSavedFor<Savings>(branchId);
  const [round, setRound] = useState(0);
  const { show, toast } = useToast();
  return (
    <Gate screen="savings" title="Savings">
      {branchId === null ? (
        <BranchRequired permissions={[SAVINGS_RECORD]} />
      ) : saved ? (
        <Saved title="Savings recorded" again="Record another day" onAgain={() => { setSaved(null); setRound((n) => n + 1); }}>
          <p>
            {branchName}, {showDate(saved.business_date)}. Sold that day: {money(saved.total_sold_minor ?? 0)}.
          </p>
          {saved.amount_minor !== undefined ? <p>Amount saved: <strong>{money(saved.amount_minor)}</strong></p> : <p>The savings were recorded using the standard amount.</p>}
        </Saved>
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{branchName}</strong></p>
          <SavingsForm key={`${branchId}-${round}`} branchId={branchId} onSaved={(s) => { setSaved(s); show('Savings recorded.'); }} />
        </>
      )}
      {toast}
      {holds(me, CASHBOOK_READ) && <SavingsRecords onVoided={show} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/savings')({ component: SavingsScreen });
