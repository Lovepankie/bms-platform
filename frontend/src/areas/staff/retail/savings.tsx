import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState, type ReactNode } from 'react';
import { businessToday, retail, type Savings } from '../../../api/retail';
import { useStaff } from '../context';
import { monthLabel } from './cash-state';
import { DateField, RecordsByShop, Saved, WindowFields, useEntryDate, useReadScope, useSavedFor, useWindow } from './cash-ui';
import { usePersistedDraft } from './idempotency';
import { CASHBOOK_READ, SALE_CREATE, SAVINGS_OVERWRITE, SAVINGS_RECORD, holds, savesAsSalesStaff } from './permissions';
import { SAVINGS_REASONS, afterSavingsConflict, nowAboveWords, planSavings, reasonText, seesAmounts } from './savings-state';
import { ScreenLink } from './screen-link';
import { useToast } from './toast';
import { BranchRequired, Gate, Note, Problem, money, useBranchName, useSingleBranch } from './ui';
import { showDate } from './transfers';
import { BusyButton } from '../../../app/ui/BusyButton';
import { ActionBar } from '../../../app/ui/ActionBar';
import { KeyFigure } from '../../../app/ui/KeyFigure';

// Daily savings (FR-RET-18 to FR-RET-20): per shop per day. The server suggests half the day's profit; a
// caller without retail.profit.read never sees the profit, the suggestion or any amount, and sends none:
// the standard amount is recorded for them. When the owner turns on "Salespeople can see and change the savings amount"
// (issue #768, ADR-115) that caller sees the suggestion, may change the amount, and sees what was saved; never the profit, the
// expenses or the most the day allows (an amount above it is refused in words that do not state it). With that switch on, a seller
// without the till (issue #799) uses the sales staff routes and sees every month's savings by shop, year, month and person with
// the amount saved at each level (issue #827, as the owner's old app did), with no void. Every save echoes the suggestion token the form was shown; if
// sales changed since, the server answers 409 suggestion_changed with the new token and writes nothing.
// The day may save at most its profit less its expenses (ADR-080): the screen shows the three figures and
// says so under the amount before anything is sent. The reason for a changed amount is optional (#538).

interface SavingsDraft { date: string; dateReason?: string; amount: string; reason: string; other: string }

const EMPTY = { amount: '', reason: '', other: '' };

/** The last part of a query key read through the sales staff routes (issue #799). */
export const SALES_STAFF_KEY = 'sales-staff';

/**
 * Put a new record at the top of the savings lists already loaded that would show it: their key is
 * ['retail', 'savings', scope, from, to] (SavingsRecords), the scope lists branch ids or is empty for all, and the
 * window holds the day. Anything else under the prefix is left alone; the refetch that follows settles every list.
 */
export function showInRecords(client: QueryClient, saved: Savings) {
  const day = saved.business_date ?? '';
  // Only the records list itself: five parts, the branch ids (undefined for all) and a from and to date. A seller's grouped
  // view (issue #827, key ['retail', 'savings-groups', ...]) is refetched by the save's invalidation of the ['retail'] prefix.
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
  const { key, draft, setDraft, finish, discard } = usePersistedDraft<SavingsDraft>(`savings:${me.user_id ?? ''}:${branchId}`, { date: businessToday(), ...EMPTY });
  // A draft restored with an amount shows its problem at once; a fresh form waits for blur (rule 10).
  const [touched, setTouched] = useState(() => draft.amount.trim() !== '');
  const [reasonOpen, setReasonOpen] = useState(false);
  // ADR-120: today for everyone but the owner, who alone may open an earlier day.
  const entry = useEntryDate(branchId, draft.date, draft.dateReason);
  // Issue #799: a seller without the till goes through the sales staff routes while the owner's switch is on.
  const salesStaff = savesAsSalesStaff(me);
  // The sales staff route has its own cache entry, so a till answer (or a 403) cached under one is never served for the other.
  const suggestionKey = ['retail', 'savings-suggestion', branchId, entry.day, ...(salesStaff ? [SALES_STAFF_KEY] : [])];
  const suggestion = useQuery({ queryKey: suggestionKey, queryFn: () => retail.savingsSuggestion({ branchId, date: entry.day, salesStaff }) });
  const s = suggestion.data;
  const profitView = seesAmounts(s);
  // ADR-115: the server says whether this caller may change the amount (the owner's setting before the day is saved, or the
  // overwrite permission); an older server without the flag leaves it to the permission.
  const canOverwrite = s?.may_change_amount ?? holds(me, SAVINGS_OVERWRITE);
  const reason = reasonText(draft.reason, draft.other ?? '');
  const plan = s ? planSavings({ branchId, date: entry.day, sent: entry.sent, suggestion: s, amount: draft.amount, reason, canOverwrite, today: businessToday() }) : null;

  // success-screen: the Saved panel replaces the form
  const save = useMutation({
    mutationFn: () => {
      if (!plan) throw new Error('The suggestion has not loaded');
      return retail.createSavings(plan.request, key, { salesStaff });
    },
    meta: { success: (saved: Savings) => (saved?.amount_minor != null ? `Savings of ${money(saved.amount_minor)} saved` : 'Savings for the day saved') },
    onSuccess: (saved) => {
      finish();
      showInRecords(queryClient, saved);
      // The day is saved: its suggestion goes and only the saved amount stays (issue #799), before the refetch says the same.
      queryClient.setQueryData(suggestionKey, (old: typeof s) =>
        (old ? { ...old, existing_id: saved.id, existing_amount_minor: saved.amount_minor, suggested_minor: undefined, may_change_amount: false } : old));
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved?.(saved);
    },
    onError: (error) => {
      // A 409 means nothing was written: take what the server says now (a new token, or the record that won)
      // and, when the figures changed, start a fresh key and ask the person to look again before saving.
      if (afterSavingsConflict(queryClient, suggestionKey, error) === 'look_again') {
        discard();
        setDraft({ date: draft.date, dateReason: draft.dateReason, ...EMPTY });
      }
    },
  });

  const blocked = !s || !plan || plan.problem !== null || s.existing_id != null || entry.reasonMissing || save.isPending;
  const update = (patch: Partial<SavingsDraft>) => setDraft((d) => ({ ...d, ...patch }));
  // Validation shows on blur (docs/ui-rules.md rule 10) or when Save is pressed; the cap never waits for the server.
  const showProblem = touched && plan?.problem != null && draft.amount.trim() !== '' && !(plan.overwrite && !canOverwrite);

  return (
    <form onSubmit={(e) => { e.preventDefault(); setTouched(true); if (!blocked) save.mutate(); }}>
      {/* Issue #799: sales staff open days of this month only; the server refuses an earlier one too. */}
      <DateField id="savings-date" label="Date" entry={entry} value={entry.day} reason={draft.dateReason} min={salesStaff ? `${businessToday().slice(0, 8)}01` : undefined} onChange={(date) => { update({ date, ...EMPTY }); setTouched(false); }} onReason={(dateReason) => update({ dateReason })} />
      {suggestion.isError && <Problem error={suggestion.error} />}
      {suggestion.isPending && <p className="loading">Loading</p>}
      {s && (
        <>
          <p>
            Sold on {showDate(s.business_date ?? entry.day)}: <strong>{money(s.total_sold_minor ?? 0)}</strong>
          </p>
          {/* Issue #799 (the owner's request): a saved day shows only what was saved, never its suggestion. */}
          {s.existing_id != null ? (
            <>
              <Note>
                Savings are already recorded for this shop on this day. If that record is wrong,{' '}
                {salesStaff ? 'ask an administrator to void it.' : 'void it in the records below, then save again.'}
              </Note>
              {s.existing_amount_minor !== undefined && <KeyFigure label="Saved" value={money(s.existing_amount_minor)} />}
              {nowAboveWords(s) && <Note>{nowAboveWords(s)}</Note>}
            </>
          ) : profitView ? (
            <>
              {s.daily_profit_minor === undefined ? null : s.available_minor !== undefined ? (
                <dl className="rt-figures" aria-label="The day's figures">
                  <div><dt>Profit for the day</dt><dd>{money(s.daily_profit_minor ?? 0)}</dd></div>
                  <div><dt>Expenses for the day</dt><dd>{money(s.day_expenses_minor ?? 0)}</dd></div>
                  <div><dt>Available to save</dt><dd><strong>{money(s.available_minor)}</strong></dd></div>
                </dl>
              ) : (
                <p>Profit for the day: <strong>{money(s.daily_profit_minor)}</strong></p>
              )}
              <div aria-live="polite"><KeyFigure label="Suggested savings" value={money(s.suggested_minor ?? 0)} /></div>
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
      <ActionBar>
        <BusyButton type="submit" className="rt-primary" busy={save.isPending} notReady={blocked}>
          Save savings
        </BusyButton>
      </ActionBar>

    </form>
  );
}

export function SavingsRecords({ onVoided }: { onVoided?: (message: string) => void }) {
  const scope = useReadScope();
  const window = useWindow();
  const { from, to } = window;
  const rows = useQuery({
    queryKey: ['retail', 'savings', scope, from, to],
    queryFn: () => retail.listSavings({ branchIds: scope, from, to, includeVoided: true }),
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
          list="savings"
          figures={{ amount: (r) => r.amount_minor }}
          totalWord="Saved"
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

// JUSTIFICATION-A3: issue #827 asks for the old app's grouped view with a total at four levels; no screen shows it yet.
/** One line of the grouped view: what it is and the amount saved, the amount at the end of the line. */
function Total({ label, minor }: { label: ReactNode; minor: number | undefined }) {
  return <span className="rt-savings-total"><span>{label}</span> <strong>{money(minor ?? 0)}</strong></span>;
}

/**
 * Issue #827, as the owner's old app did: a seller's savings of every month, by shop, year, month and the person who saved,
 * with the amount saved at each level. Amounts saved only, never the profit or a suggestion. The newest year is open.
 */
export function SavingsByShop() {
  const scope = useReadScope();
  const shopName = useBranchName();
  const groups = useQuery({ queryKey: ['retail', 'savings-groups', scope, SALES_STAFF_KEY], queryFn: () => retail.savingsGroups({ branchIds: scope }) });
  const g = groups.data;
  return (
    <section aria-label="Savings records" data-tour="savings-records">
      <h2>Savings records</h2>
      {groups.isError && <Problem error={groups.error} />}
      {groups.isPending && <p className="loading">Loading</p>}
      {g && (g.shops?.length ?? 0) === 0 && <p className="empty-state">No savings recorded yet.</p>}
      {g && (g.shops?.length ?? 0) > 0 && (
        <>
          <p><Total label="Saved in all shops" minor={g.total_minor} /></p>
          {g.shops?.map((shop) => (
            <section key={shop.branch_id} aria-label={shopName(shop.branch_id)}>
              <h3><Total label={shopName(shop.branch_id)} minor={shop.total_minor} /></h3>
              {shop.years?.map((year, i) => (
                <details key={year.year} className="rt-groupby" open={i === 0}>
                  <summary><Total label={year.year} minor={year.total_minor} /></summary>
                  {year.months?.map((month) => (
                    <div key={month.month} className="rt-savings-month">
                      <h4><Total label={monthLabel(`${year.year}-${String(month.month).padStart(2, '0')}`)} minor={month.total_minor} /></h4>
                      <ul className="rt-savings-people">
                        {month.people?.map((person) => (
                          <li key={person.user_id ?? 'none'} className="rt-card">
                            <Total label={person.name ?? 'Not recorded'} minor={person.total_minor} />
                            <ul className="rt-savings-days">
                              {person.records?.map((r) => (
                                <li key={r.id}><Total label={showDate(r.business_date)} minor={r.amount_minor} /></li>
                              ))}
                            </ul>
                          </li>
                        ))}
                      </ul>
                    </div>
                  ))}
                </details>
              ))}
            </section>
          ))}
        </>
      )}
    </section>
  );
}

export function SavingsScreen() {
  const { me } = useStaff();
  const { branchId, branchName } = useSingleBranch();
  const [saved, setSaved] = useSavedFor<Savings>(branchId);
  const salesStaff = savesAsSalesStaff(me);
  const [round, setRound] = useState(0);
  const { show, toast } = useToast();
  return (
    <Gate screen="savings" title="Savings">
      <p><ScreenLink screen="cashSummary" to="/staff/retail/cash-summary">Cash summary</ScreenLink></p>
      <div data-tour="savings-form">
      {branchId === null ? (
        <BranchRequired permissions={[salesStaff ? SALE_CREATE : SAVINGS_RECORD]} />
      ) : saved ? (
        <Saved title="Savings recorded" again="Record another day" onAgain={() => { setSaved(null); setRound((n) => n + 1); }}>
          <p>
            {branchName}, {showDate(saved.business_date)}. Sold that day: {money(saved.total_sold_minor ?? 0)}.
          </p>
          {saved.amount_minor !== undefined ? <p>Amount saved: <strong>{money(saved.amount_minor)}</strong></p> : <p>The savings were recorded using the standard amount.</p>}
        </Saved>
      ) : (
        <>
          <SavingsForm key={`${branchId}-${round}`} branchId={branchId} onSaved={setSaved} />
        </>
      )}
      </div>
      {toast}
      {salesStaff ? <SavingsByShop /> : holds(me, CASHBOOK_READ) && <SavingsRecords onVoided={(text) => show(text, 'success')} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/savings')({ component: SavingsScreen });
