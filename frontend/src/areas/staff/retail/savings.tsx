import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, retail, type Savings } from '../../../api/retail';
import { useStaff } from '../context';
import { DateField, RecordsByShop, Saved, WindowFields, useReadScope, useSavedFor, useWindow } from './cash-ui';
import { usePersistedDraft } from './idempotency';
import { CASHBOOK_READ, SAVINGS_OVERWRITE, SAVINGS_RECORD, holds } from './permissions';
import { SAVINGS_REASONS, afterSavingsConflict, nowAboveWords, planSavings, reasonText, savedWords, seesAmounts } from './savings-state';
import { ScreenLink } from './screen-link';
import { useToast } from './toast';
import { BranchRequired, Gate, Note, Problem, money, useSingleBranch } from './ui';
import { showDate } from './transfers';
import { BusyButton } from '../../../app/ui/BusyButton';

// Daily savings (FR-RET-18 to FR-RET-20): per shop per day. The server suggests half the day's profit; a
// caller without retail.profit.read never sees the profit, the suggestion or any amount, and sends none:
// the standard amount is recorded for them. Every save echoes the suggestion token the form was shown; if
// sales changed since, the server answers 409 suggestion_changed with the new token and writes nothing.
// The day may save at most its profit less its expenses (ADR-080): the screen shows the three figures and
// says so under the amount before anything is sent. The reason for a changed amount is optional (#538).

interface SavingsDraft { date: string; amount: string; reason: string; other: string }

const EMPTY = { amount: '', reason: '', other: '' };

/**
 * Put a new record at the top of the savings lists already loaded that would show it: their key is
 * ['retail', 'savings', scope, from, to] (SavingsRecords), the scope lists branch ids or is empty for all, and the
 * window holds the day. Anything else under the prefix is left alone; the refetch that follows settles every list.
 */
export function showInRecords(client: QueryClient, saved: Savings) {
  const day = saved.business_date ?? '';
  // Only the records list itself: exactly five parts, the branch ids (undefined for all) and a from and to date.
  const holds = (key: readonly unknown[]): boolean => {
    if (key.length !== 5) return false;
    const [, , scope, from, to] = key;
    if ((scope !== undefined && !Array.isArray(scope)) || typeof from !== 'string' || typeof to !== 'string') return false;
    const inScope = scope === undefined || scope.length === 0 || scope.includes(saved.branch_id);
    return inScope && (from === '' || day >= from) && (to === '' || day <= to);
  };
  for (const [key, rows] of client.getQueriesData<unknown>({ queryKey: ['retail', 'savings'] })) {
    if (!Array.isArray(rows) || !holds(key) || rows.some((r: Savings) => r.id === saved.id)) continue;
    client.setQueryData(key, [saved, ...(rows as Savings[])]);
  }
}

export function SavingsForm({ branchId, onSaved }: { branchId: string; onSaved?: (saved: Savings) => void }) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const canOverwrite = holds(me, SAVINGS_OVERWRITE);
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<SavingsDraft>(`savings:${me.user_id ?? ''}:${branchId}`, { date: businessToday(), ...EMPTY });
  // A draft restored with an amount shows its problem at once; a fresh form waits for blur (rule 10).
  const [touched, setTouched] = useState(() => draft.amount.trim() !== '');
  const [reasonOpen, setReasonOpen] = useState(false);
  const suggestionKey = ['retail', 'savings-suggestion', branchId, draft.date];
  const suggestion = useQuery({ queryKey: suggestionKey, queryFn: () => retail.savingsSuggestion({ branchId, date: draft.date }) });
  const s = suggestion.data;
  const profitView = seesAmounts(s);
  const reason = reasonText(draft.reason, draft.other ?? '');
  const plan = s ? planSavings({ branchId, date: draft.date, suggestion: s, amount: draft.amount, reason, canOverwrite, today: businessToday() }) : null;

  const save = useMutation({
    mutationFn: () => {
      if (!plan) throw new Error('The suggestion has not loaded');
      return retail.createSavings(plan.request, key);
    },
    onSuccess: (saved) => {
      finish();
      showInRecords(queryClient, saved);
      queryClient.setQueryData(suggestionKey, (old: typeof s) => (old ? { ...old, existing_id: saved.id } : old));
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved?.(saved);
    },
    onError: (error) => {
      // A 409 means nothing was written: take what the server says now (a new token, or the record that won)
      // and, when the figures changed, start a fresh key and ask the person to look again before saving.
      if (afterSavingsConflict(queryClient, suggestionKey, error) === 'look_again') {
        discard();
        setDraft({ date: draft.date, ...EMPTY });
      }
    },
  });

  const blocked = !s || !plan || plan.problem !== null || s.existing_id != null || save.isPending;
  const update = (patch: Partial<SavingsDraft>) => setDraft((d) => ({ ...d, ...patch }));
  // Validation shows on blur (docs/ui-rules.md rule 10) or when Save is pressed; the cap never waits for the server.
  const showProblem = touched && plan?.problem != null && draft.amount.trim() !== '' && !(plan.overwrite && !canOverwrite);

  return (
    <form onSubmit={(e) => { e.preventDefault(); setTouched(true); if (!blocked) save.mutate(); }}>
      <DateField id="savings-date" label="Date" value={draft.date} onChange={(date) => { update({ date, ...EMPTY }); setTouched(false); }} />
      {suggestion.isError && <Problem error={suggestion.error} />}
      {suggestion.isPending && <p className="loading">Loading</p>}
      {s && (
        <>
          <p>
            Sold on {showDate(s.business_date ?? draft.date)}: <strong>{money(s.total_sold_minor ?? 0)}</strong>
          </p>
          {s.existing_id != null && <Note>Savings are already recorded for this shop on this day. If that record is wrong, void it in the records below, then save again.</Note>}
          {nowAboveWords(s) && <Note>{nowAboveWords(s)}</Note>}
          {profitView ? (
            <>
              {s.available_minor !== undefined ? (
                <dl className="rt-figures" aria-label="The day's figures">
                  <div><dt>Profit for the day</dt><dd>{money(s.daily_profit_minor ?? 0)}</dd></div>
                  <div><dt>Expenses for the day</dt><dd>{money(s.day_expenses_minor ?? 0)}</dd></div>
                  <div><dt>Available to save</dt><dd><strong>{money(s.available_minor)}</strong></dd></div>
                </dl>
              ) : (
                s.daily_profit_minor !== undefined && <p>Profit for the day: <strong>{money(s.daily_profit_minor)}</strong></p>
              )}
              <p className="rt-total" aria-live="polite">Suggested savings {money(s.suggested_minor ?? 0)}</p>
              <label htmlFor="savings-amount">Amount to save (optional, leave blank to save the suggestion)</label>
              <input
                id="savings-amount"
                inputMode="numeric"
                value={draft.amount}
                onChange={(e) => update({ amount: e.target.value })}
                onBlur={() => setTouched(true)}
                aria-invalid={showProblem}
                aria-describedby={showProblem ? 'savings-amount-problem' : undefined}
              />
              {showProblem && <p id="savings-amount-problem" role="alert" className="rt-flag">{plan?.problem}</p>}
              {plan?.overwrite && canOverwrite && (
                reasonOpen || draft.reason !== '' ? (
                  <>
                    <label htmlFor="savings-reason">Reason (optional)</label>
                    <select id="savings-reason" value={draft.reason} onChange={(e) => update({ reason: e.target.value })}>
                      <option value="">No reason</option>
                      {SAVINGS_REASONS.map((r) => <option key={r} value={r}>{r}</option>)}
                    </select>
                    {draft.reason === 'Other' && (
                      <>
                        <label htmlFor="savings-reason-other">Say it in a few words (optional)</label>
                        <input id="savings-reason-other" maxLength={300} value={draft.other ?? ''} onChange={(e) => update({ other: e.target.value })} />
                      </>
                    )}
                  </>
                ) : (
                  <p><button type="button" className="btn-ghost" onClick={() => setReasonOpen(true)}>Add a reason (optional)</button></p>
                )
              )}
              {plan?.overwrite && !canOverwrite && <Note>Only an administrator can change the amount. Clear the amount to save the suggested one.</Note>}
            </>
          ) : (
            <Note>Savings will be recorded using the standard amount.</Note>
          )}
        </>
      )}
      <Problem error={save.error} />
      <BusyButton type="submit" className="rt-primary" busy={save.isPending} disabled={blocked}>
        Save savings
      </BusyButton>

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
    <section aria-label="Savings records" data-tour="savings-records">
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

export function SavingsScreen() {
  const { me } = useStaff();
  const { branchId, branchName } = useSingleBranch();
  const [saved, setSaved] = useSavedFor<Savings>(branchId);
  const [round, setRound] = useState(0);
  const { show, toast } = useToast();
  return (
    <Gate screen="savings" title="Savings">
      <p><ScreenLink screen="cashSummary" to="/staff/retail/cash-summary">Cash summary</ScreenLink></p>
      <div data-tour="savings-form">
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
          <SavingsForm key={`${branchId}-${round}`} branchId={branchId} onSaved={(s) => { setSaved(s); show(savedWords(s), 'success'); }} />
        </>
      )}
      </div>
      {toast}
      {holds(me, CASHBOOK_READ) && <SavingsRecords onVoided={(text) => show(text, 'success')} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/savings')({ component: SavingsScreen });
