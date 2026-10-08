import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessToday, retail, type Withdrawal } from '../../../api/retail';
import { useStaff } from '../context';
import { amountOrNull, warningWords } from './cash-state';
import { DateField, RecordsByShop, Saved, Warnings, WindowFields, useReadScope, useSavedFor, useWindow } from './cash-ui';
import { usePersistedDraft } from './idempotency';
import { CASHBOOK_READ, holds, needsOf } from './permissions';
import { useToast } from './toast';
import { BranchRequired, Gate, Problem, money, useSingleBranch } from './ui';
import { showDate } from './transfers';

// Cash withdrawn from the bank into a shop's till (FR-RET-25). A withdrawal above the bank balance on
// record is saved and comes back with a warning, never a refusal.

interface WithdrawalDraft { date: string; amount: string; purpose: string }

export function WithdrawalForm({ branchId, onSaved }: { branchId: string; onSaved?: (saved: Withdrawal) => void }) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const { key, draft, setDraft, finish } = usePersistedDraft<WithdrawalDraft>(`withdrawal:${me.user_id ?? ''}:${branchId}`, { date: businessToday(), amount: '', purpose: '' });
  const minor = amountOrNull(draft.amount);
  const save = useMutation({
    mutationFn: () => retail.createWithdrawal({ branch_id: branchId, business_date: draft.date, amount_minor: minor ?? 0, ...(draft.purpose.trim() ? { purpose: draft.purpose.trim() } : {}) }, key),
    onSuccess: (saved) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onSaved?.(saved);
    },
  });
  const blocked = minor === null || save.isPending;
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!blocked) save.mutate(); }}>
      <DateField id="withdrawal-date" label="Date" value={draft.date} onChange={(date) => setDraft((d) => ({ ...d, date }))} />
      <label htmlFor="withdrawal-amount">Amount withdrawn</label>
      <input id="withdrawal-amount" inputMode="numeric" value={draft.amount} onChange={(e) => setDraft((d) => ({ ...d, amount: e.target.value }))} aria-invalid={draft.amount.trim() !== '' && minor === null} />
      {draft.amount.trim() !== '' && minor === null && <p role="alert" className="rt-flag">Enter the amount in whole shillings.</p>}
      <label htmlFor="withdrawal-purpose">What is it for? (optional)</label>
      <input id="withdrawal-purpose" maxLength={200} value={draft.purpose} onChange={(e) => setDraft((d) => ({ ...d, purpose: e.target.value }))} />
      <p className="hint">The cash goes into this shop's till.</p>
      <Problem error={save.error} />
      <button type="submit" className="rt-primary" disabled={blocked}>
        {save.isPending ? 'Saving' : 'Save withdrawal'}
      </button>
    </form>
  );
}

export function WithdrawalRecords({ onVoided }: { onVoided?: (message: string) => void }) {
  const scope = useReadScope();
  const window = useWindow();
  const rows = useQuery({
    queryKey: ['retail', 'withdrawals', scope, window.from, window.to],
    queryFn: () => retail.listWithdrawals({ branchIds: scope, ...window.query, includeVoided: true }),
  });
  return (
    <section aria-label="Withdrawal records">
      <h2>Cash withdrawals</h2>
      <WindowFields id="withdrawal-list" window={window} />
      {rows.isError && <Problem error={rows.error} />}
      {rows.isPending && <p className="loading">Loading</p>}
      {rows.data && (
        <RecordsByShop
          rows={rows.data}
          empty="No cash withdrawn in this period."
          what="withdrawal"
          voidWith={(row, reason, key) => retail.voidWithdrawal(row.id ?? '', reason, key)}
          onVoided={() => onVoided?.('Withdrawal voided.')}
          renderRow={(row) => (
            <>
              <strong>{showDate(row.business_date)}</strong> <span className="muted">{money(row.amount_minor ?? 0)}</span>
              {row.purpose && <><br />{row.purpose}</>}
            </>
          )}
        />
      )}
    </section>
  );
}

function WithdrawalsScreen() {
  const { me } = useStaff();
  const { branchId, branchName } = useSingleBranch();
  const [saved, setSaved] = useSavedFor<Withdrawal>(branchId);
  const [round, setRound] = useState(0);
  const { show, toast } = useToast();
  return (
    <Gate screen="withdrawals" title="Cash withdrawals">
      {branchId === null ? (
        <BranchRequired permissions={needsOf('withdrawals')} />
      ) : saved ? (
        <Saved title="Withdrawal recorded" notes={<Warnings words={(saved.warnings ?? []).map(warningWords).filter((w): w is string => w !== null)} />} again="Record another withdrawal" onAgain={() => { setSaved(null); setRound((n) => n + 1); }}>
          <p>{branchName}, {showDate(saved.business_date)}: <strong>{money(saved.amount_minor ?? 0)}</strong> taken out of the bank.</p>
        </Saved>
      ) : (
        <>
          <p className="branch-line">Branch: <strong>{branchName}</strong></p>
          <WithdrawalForm key={`${branchId}-${round}`} branchId={branchId} onSaved={(w) => { setSaved(w); show('Withdrawal recorded.'); }} />
        </>
      )}
      {toast}
      {holds(me, CASHBOOK_READ) && <WithdrawalRecords onVoided={show} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/withdrawals')({ component: WithdrawalsScreen });
