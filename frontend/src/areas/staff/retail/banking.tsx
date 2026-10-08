import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, retail, type Banking, type BankingDay, type BankingExpected } from '../../../api/retail';
import { useStaff } from '../context';
import { alreadyBankedLabel, bankingPrefill } from './banking-state';
import { amountOrNull, clockTime, flagWords, groupByShopMonth, warningWords, type BankingFlag } from './cash-state';
import { DateField, ImportedBadge, RecordsByShop, Saved, Warnings, WindowFields, useReadScope, useSavedFor, useWindow } from './cash-ui';
import { usePersistedDraft } from './idempotency';
import { CASHBOOK_READ, holds, needsOf } from './permissions';
import { useToast } from './toast';
import { BranchRequired, Gate, Problem, money, useBranchName, useSingleBranch } from './ui';
import { showDate } from './transfers';

// Cash banked (FR-RET-21 to FR-RET-23). The server works out what to expect: every caller sees "Cash
// expected" (takings less what was paid out of the till). Only a caller with retail.profit.read also gets
// the cash restock cost, the savings and the net amount to bank, and only then the flag, the difference and
// the warning after a save. Several deposits on one day are allowed.

interface BankingDraft { date: string; amount: string | null; reference: string }

export function ExpectedFigures({ expected, date }: { expected: BankingExpected; date?: string }) {
  return (
    <div className="rt-card" aria-label="What to expect">
      <p className="rt-row">
        <span>Cash expected</span>
        <strong>{money(expected.cash_expected_minor ?? 0)}</strong>
      </p>
      <p className="hint">Takings less what was paid out of the till for expenses and advances, plus advance repayments received.</p>
      {expected.cash_purchases_minor !== undefined && <p className="rt-row"><span>Cash restocks, at cost</span><strong>{money(expected.cash_purchases_minor)}</strong></p>}
      {expected.savings_minor !== undefined && <p className="rt-row"><span>Savings set aside</span><strong>{money(expected.savings_minor)}</strong></p>}
      {expected.expected_minor !== undefined && <p className="rt-row"><span>Expected to bank</span><strong>{money(expected.expected_minor)}</strong></p>}
      <p className="rt-row"><span>{alreadyBankedLabel(date ?? expected.business_date, businessToday())}</span><strong>{money(expected.banked_so_far_minor ?? 0)}</strong></p>
    </div>
  );
}

export function BankingForm({ branchId, onSaved }: { branchId: string; onSaved?: (saved: Banking) => void }) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const { key, draft, setDraft, finish } = usePersistedDraft<BankingDraft>(`banking:${me.user_id ?? ''}:${branchId}`, { date: businessToday(), amount: null, reference: '' });
  const expected = useQuery({ queryKey: ['retail', 'banking-expected', branchId, draft.date], queryFn: () => retail.bankingExpected({ branchId, date: draft.date }) });
  const shown = draft.amount ?? bankingPrefill(expected.data);
  const minor = amountOrNull(shown);
  const save = useMutation({
    mutationFn: () => retail.createBanking({ branch_id: branchId, business_date: draft.date, amount_minor: minor ?? 0, ...(draft.reference.trim() ? { reference: draft.reference.trim() } : {}) }, key),
    onSuccess: (saved) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved?.(saved);
    },
  });
  const blocked = minor === null || expected.isPending || save.isPending;
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!blocked) save.mutate(); }}>
      <DateField id="banking-date" label="Date" value={draft.date} onChange={(date) => setDraft((d) => ({ ...d, date, amount: null }))} />
      {expected.isError && <Problem error={expected.error} />}
      {expected.isPending && <p className="loading">Loading</p>}
      {expected.data && <ExpectedFigures expected={expected.data} date={draft.date} />}
      <label htmlFor="banking-amount">Amount banked</label>
      <input id="banking-amount" inputMode="numeric" value={shown} onChange={(e) => setDraft((d) => ({ ...d, amount: e.target.value }))} aria-invalid={shown.trim() !== '' && minor === null} />
      {shown.trim() !== '' && minor === null && <p role="alert" className="rt-flag">Enter the amount in whole shillings.</p>}
      <label htmlFor="banking-reference">Bank reference (optional)</label>
      <input id="banking-reference" maxLength={100} value={draft.reference} onChange={(e) => setDraft((d) => ({ ...d, reference: e.target.value }))} />
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={blocked}>
        {save.isPending ? 'Saving' : 'Save banking'}
      </button>
    </form>
  );
}

/** What a save came to: the flag, the difference and the warnings are shown only when the server sent them. */
export function BankingResult({ saved }: { saved: Banking }) {
  const flag = flagWords(saved.flag as BankingFlag | undefined, saved.difference_minor, money);
  return (
    <>
      <p>Banked <strong>{money(saved.amount_minor ?? 0)}</strong> on {showDate(saved.business_date)}.</p>
      {saved.expected_minor !== undefined && <p>Expected to bank: {money(saved.expected_minor)}.</p>}
      {flag && <p className={saved.flag === 'ok' ? '' : 'rt-flag'}>{flag}</p>}
    </>
  );
}

/** The warnings of a banking save in words (none unless the server sent some). */
export const bankingWarnings = (saved: Banking): string[] => (saved.warnings ?? []).map(warningWords).filter((w): w is string => w !== null);

export function BankingRecords({ onVoided }: { onVoided?: (message: string) => void }) {
  const scope = useReadScope();
  const window = useWindow();
  const rows = useQuery({
    queryKey: ['retail', 'bankings', scope, window.from, window.to],
    queryFn: () => retail.listBankings({ branchIds: scope, ...window.query, includeVoided: true }),
  });
  return (
    <section aria-label="Banking records">
      <h2>Banked cash records</h2>
      <WindowFields id="banking-list" window={window} />
      {rows.isError && <Problem error={rows.error} />}
      {rows.isPending && <p className="loading">Loading</p>}
      {rows.data && (
        <RecordsByShop
          rows={rows.data}
          empty="Nothing banked in this period."
          what="banking record"
          voidWith={(row, reason, key) => retail.voidBanking(row.id ?? '', reason, key)}
          onVoided={() => onVoided?.('Banking record voided.')}
          renderRow={(row) => (
            <>
              <strong>{showDate(row.business_date)}</strong> <span className="muted">{clockTime(row.banked_at)}</span>
              <br />
              Banked {money(row.amount_minor ?? 0)}
              {row.reference ? `, reference ${row.reference}` : ''}
              {row.flag && <><br />{flagWords(row.flag as BankingFlag, row.difference_minor, money)}</>}
            </>
          )}
        />
      )}
    </section>
  );
}

function BankingScreen() {
  const { me } = useStaff();
  const { branchId, branchName } = useSingleBranch();
  const [saved, setSaved] = useSavedFor<Banking>(branchId);
  const [round, setRound] = useState(0);
  const { show, toast } = useToast();
  return (
    <Gate screen="banking" title="Banking">
      {branchId === null ? (
        <BranchRequired permissions={needsOf('banking')} />
      ) : saved ? (
        <Saved title="Banking recorded" notes={<Warnings words={bankingWarnings(saved)} />} again="Record another deposit" onAgain={() => { setSaved(null); setRound((n) => n + 1); }}>
          <p>{branchName}</p>
          <BankingResult saved={saved} />
        </Saved>
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{branchName}</strong></p>
          <BankingForm key={`${branchId}-${round}`} branchId={branchId} onSaved={(b) => { setSaved(b); show('Banking recorded.'); }} />
        </>
      )}
      {toast}
      {holds(me, CASHBOOK_READ) && <BankingRecords onVoided={show} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/banking')({ component: BankingScreen });

// The banking report (FR-RET-23): per shop per day what was expected and what was banked. Expected,
// difference, flag and the running unbanked total are all net of savings, so they are present only for a
// caller with retail.profit.read; everyone else sees the cash expected and the cash banked. Days imported
// from the pilot app are listed apart and are not part of the running total.

export function BankingDayCard({ day }: { day: BankingDay }) {
  return (
    <li className="rt-card">
      <strong>{showDate(day.business_date)}</strong>
      <p className="rt-row"><span>Cash expected</span><strong>{money(day.cash_expected_minor ?? 0)}</strong></p>
      <p className="rt-row"><span>Banked</span><strong>{money(day.banked_minor ?? 0)}</strong></p>
      {day.expected_minor !== undefined && <p className="rt-row"><span>Expected to bank</span><strong>{money(day.expected_minor)}</strong></p>}
      {day.difference_minor !== undefined && <p className="rt-row"><span>Difference</span><strong>{money(day.difference_minor)}</strong></p>}
      {day.flag && <p className={day.flag === 'ok' ? '' : 'rt-flag'}>{flagWords(day.flag as BankingFlag, day.difference_minor, money)}</p>}
      {day.unbanked_running_minor !== undefined && <p className="rt-row"><span>Unbanked so far</span><strong>{money(day.unbanked_running_minor)}</strong></p>}
      {(day.entries ?? []).length > 0 && (
        <ul style={{ listStyle: 'none', padding: 0 }}>
          {(day.entries ?? []).map((e) => (
            <li key={e.id} className="hint">
              {money(e.amount_minor ?? 0)} at {clockTime(e.banked_at)}{e.by_name ? ` by ${e.by_name}` : ''}{e.voided ? ' (voided)' : ''}
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

export function BankingDays({ days }: { days: BankingDay[] }) {
  const shopName = useBranchName();
  const groups = groupByShopMonth(days, shopName);
  return (
    <>
      {groups.map((shop) => (
        <section key={shop.branchId} aria-label={shop.shop}>
          <h3>{shop.shop}</h3>
          <ul style={{ listStyle: 'none', padding: 0 }}>
            {shop.months.flatMap((m) => m.rows).map((day) => <BankingDayCard key={`${day.branch_id}-${day.business_date}`} day={day} />)}
          </ul>
        </section>
      ))}
    </>
  );
}

export function BankingReportView({ days }: { days: BankingDay[] }) {
  const live = days.filter((d) => !d.historical);
  const imported = days.filter((d) => d.historical);
  return (
    <>
      {days.length === 0 && <p className="empty-state">No cash to bank or banked in this period.</p>}
      {live.length > 0 && <BankingDays days={live} />}
      {imported.length > 0 && (
        <section aria-label="Imported days">
          <h2>Days imported from the old app <ImportedBadge /></h2>
          <p className="hint">These days are shown for reference. They are not part of the unbanked total above.</p>
          <BankingDays days={imported} />
        </section>
      )}
    </>
  );
}

function BankingReportScreen() {
  const scope = useReadScope();
  const window = useWindow();
  const report = useQuery({
    queryKey: ['retail', 'banking-report', scope, window.from, window.to],
    queryFn: () => retail.bankingReport({ branchIds: scope, ...window.query }),
  });
  return (
    <Gate screen="bankingReport" title="Banking report">
      <WindowFields id="banking-report" window={window} />
      {report.isError && <Problem error={report.error} />}
      {report.isPending && <p className="loading">Loading</p>}
      {report.data && <BankingReportView days={report.data.items ?? []} />}
    </Gate>
  );
}

export const ReportRoute = createLazyRoute('/staff/retail/banking-report')({ component: BankingReportScreen });
