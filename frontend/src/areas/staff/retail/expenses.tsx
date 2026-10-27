import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Segmented } from '../../../app/ui/Segmented';
import { businessToday, isWaiting, retail, type Expense, type ExpenseGroup } from '../../../api/retail';
import { useStaff } from '../context';
import { DateField, RecordsByShop, Saved, WindowFields, useReadScope, useSavedFor, useWindow } from './cash-ui';
import { MIN_EXPLANATION, activeCategories, itemsOf, needsExplanation, planExpense } from './expenses-state';
import { usePersistedDraft } from './idempotency';
import { PartyPicker, useCashParties } from './party-picker';
import { CASHBOOK_READ, holds, needsOf } from './permissions';
import { ScreenLink } from './screen-link';
import { useToast } from './toast';
import { BranchRequired, Gate, Problem, WaitingForApproval, money, useBranchName, useSingleBranch } from './ui';
import { showDate } from './transfers';
import { ActionBar } from '../../../app/ui/ActionBar';
import { EmptyChoice, showsEmptyChoice } from '../../../app/ui/EmptyChoice';
import { addOn } from './screen-link';

// Company expenses (FR-RET-24): shop, date, category, then an item of that category, an optional
// beneficiary, the amount, and an explanation when the item needs one. A receipt photo is not offered yet:
// the frontend has no documents upload client to send a receipt_document_id with (skipped on purpose).

interface ExpenseDraft { date: string; categoryId: string; itemId: string; partyId: string; amount: string; explanation: string }

export function ExpenseForm({ branchId, onSaved, onWaiting }: { branchId: string; onSaved?: (saved: Expense) => void; onWaiting?: (amountMinor: number) => void }) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const { key, draft, setDraft, finish } = usePersistedDraft<ExpenseDraft>(`expense:${me.user_id ?? ''}:${branchId}`, {
    date: businessToday(), categoryId: '', itemId: '', partyId: '', amount: '', explanation: '',
  });
  const categories = useQuery({ queryKey: ['retail', 'expense-categories', true], queryFn: () => retail.listExpenseCategories({ activeOnly: true }) });
  const parties = useCashParties();
  const list = categories.data ?? [];
  const items = itemsOf(list, draft.categoryId);
  const explain = needsExplanation(list, draft.categoryId, draft.itemId);
  const plan = planExpense({ branchId, ...draft }, list);
  const save = useMutation({
    mutationFn: () => retail.createExpense(plan.request, key),
    onSuccess: (saved) => {
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      // ADR-091: an expense worth the business's amount waits for an administrator and is not in the cash book until then.
      if (isWaiting(saved)) onWaiting?.(saved.amount_minor ?? 0);
      else onSaved?.(saved);
    },
  });
  const update = (patch: Partial<ExpenseDraft>) => setDraft((d) => ({ ...d, ...patch }));
  const blocked = plan.problem !== null || save.isPending;
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!blocked) save.mutate(); }}>
      <DateField id="expense-date" label="Date" value={draft.date} onChange={(date) => update({ date })} />

      {showsEmptyChoice(categories, categories.data && activeCategories(list)) ? (
        <EmptyChoice id="expense-category" label="Category" load={categories} text="No expense categories yet." ask="Ask the owner to add expense categories first."
          add={addOn(me, 'expenseSetup', 'Add an expense category', '/staff/retail/expense-lists')} />
      ) : (
      <>
      <label htmlFor="expense-category">Category</label>
      <select id="expense-category" value={draft.categoryId} onChange={(e) => update({ categoryId: e.target.value, itemId: '', explanation: '' })}>
        <option value="">Choose a category</option>
        {activeCategories(list).map((c) => (
          <option key={c.id} value={c.id}>{c.name}</option>
        ))}
      </select>

      </>
      )}

      {draft.categoryId !== '' && items.length === 0 ? (
        <EmptyChoice id="expense-item" label="Item" text="This category has no items yet." ask="Ask the owner to add its items first."
          add={addOn(me, 'expenseSetup', 'Add an item to it', '/staff/retail/expense-lists')} />
      ) : (
      <>
      <label htmlFor="expense-item">Item</label>
      <select id="expense-item" value={draft.itemId} disabled={draft.categoryId === ''} onChange={(e) => update({ itemId: e.target.value, explanation: '' })}>
        <option value="">{draft.categoryId === '' ? 'Choose a category first' : 'Choose an item'}</option>
        {items.map((i) => (
          <option key={i.id} value={i.id}>{i.name}</option>
        ))}
      </select>
      </>
      )}

      {explain && (
        <>
          <label htmlFor="expense-explanation">Explanation (required, at least {MIN_EXPLANATION} characters)</label>
          <input id="expense-explanation" maxLength={300} value={draft.explanation} onChange={(e) => update({ explanation: e.target.value })} aria-required="true" />
        </>
      )}

      <PartyPicker
        id="expense-party" label="Beneficiary (optional)" parties={parties.data ?? []} loading={parties.data === undefined} load={parties} kinds={['other']} value={draft.partyId}
        onChange={(partyId) => update({ partyId })} noneLabel="No beneficiary" addLabel="Add a new beneficiary" optional
      />

      <label htmlFor="expense-amount">Amount</label>
      <input id="expense-amount" inputMode="numeric" value={draft.amount} onChange={(e) => update({ amount: e.target.value })} />

      {plan.problem && draft.categoryId !== '' && draft.amount.trim() !== '' && <p role="alert" className="rt-flag">{plan.problem}</p>}
      <Problem error={save.error} />
      <ActionBar>
        <BusyButton busy={save.isPending} type="submit" className="rt-primary" notReady={blocked}>
          Save expense
        </BusyButton>
      </ActionBar>
    </form>
  );
}

export function ExpenseRecords({ onVoided }: { onVoided?: (message: string) => void }) {
  const scope = useReadScope();
  const window = useWindow();
  const rows = useQuery({
    queryKey: ['retail', 'expenses', scope, window.from, window.to],
    queryFn: () => retail.listExpenses({ branchIds: scope, ...window.query, includeVoided: true }),
  });
  return (
    <section aria-label="Expense records" data-tour="expense-records">
      <h2>Expense records</h2>
      <WindowFields id="expense-list" window={window} />
      {rows.isError && <Problem error={rows.error} />}
      {rows.isPending && <p className="loading">Loading</p>}
      {rows.data && (
        <RecordsByShop
          rows={rows.data}
          empty="No expenses recorded in this period."
          what="expense"
          voidWith={(row, reason, key) => retail.voidExpense(row.id ?? '', reason, key)}
          onVoided={() => onVoided?.('Expense voided.')}
          renderRow={(row) => (
            <>
              <strong>{showDate(row.business_date)}</strong> <span className="muted">{money(row.amount_minor ?? 0)}</span>
              <br />
              {row.category_name} / {row.item_name}
              {row.party_name ? `, for ${row.party_name}` : ''}
              {row.explanation ? <><br />{row.explanation}</> : null}
            </>
          )}
        />
      )}
    </section>
  );
}

export function ExpensesScreen() {
  const { me } = useStaff();
  const { branchId, branchName } = useSingleBranch();
  const [saved, setSaved] = useSavedFor<Expense>(branchId);
  const [round, setRound] = useState(0);
  const [waiting, setWaiting] = useState<number | null>(null);
  const { show, toast } = useToast();
  return (
    <Gate screen="expenses" title="Expenses">
      <p className="cluster">
        <ScreenLink screen="expensesReport" to="/staff/retail/expenses-report" tour="expenses-report-link">Expenses report</ScreenLink>
        <ScreenLink screen="expenseSetup" to="/staff/retail/expense-lists" tour="expenses-lists-link">Expense lists</ScreenLink>
      </p>
      <div data-tour="expense-form">
      {branchId === null ? (
        <BranchRequired permissions={needsOf('expenses')} />
      ) : waiting !== null ? (
        <section aria-label="Expense sent for approval" className="stack">
          <WaitingForApproval what={`The expense of ${money(waiting)} is not in the cash book yet.`} />
          <button type="button" className="rt-primary" onClick={() => { setWaiting(null); setRound((n) => n + 1); }}>Record another expense</button>
        </section>
      ) : saved ? (
        <Saved title="Expense recorded" again="Record another expense" onAgain={() => { setSaved(null); setRound((n) => n + 1); }}>
          <p>
            {branchName}, {showDate(saved.business_date)}: {saved.category_name} / {saved.item_name}, <strong>{money(saved.amount_minor ?? 0)}</strong>
            {saved.party_name ? `, for ${saved.party_name}` : ''}.
          </p>
        </Saved>
      ) : (
        <>
          <ExpenseForm key={`${branchId}-${round}`} branchId={branchId} onSaved={(x) => { setSaved(x); show('Expense recorded.', 'success'); }} onWaiting={(amount) => { setWaiting(amount); show('Sent for approval.', 'info'); }} />
        </>
      )}
      </div>
      {toast}
      {holds(me, CASHBOOK_READ) && <ExpenseRecords onVoided={(text) => show(text, 'success')} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/expenses')({ component: ExpensesScreen });

// The expenses report (FR-RET-29): totals by category, item, shop or month for a period, voided rows left out.

export type ReportGroup = 'category' | 'item' | 'branch' | 'month';
const GROUPS: { value: ReportGroup; label: string }[] = [
  { value: 'category', label: 'By category' },
  { value: 'item', label: 'By item' },
  { value: 'branch', label: 'By shop' },
  { value: 'month', label: 'By month' },
];

export function ExpenseGroups({ groups, total, count, by }: { groups: ExpenseGroup[]; total: number; count: number; by: ReportGroup }) {
  const shopName = useBranchName();
  if (groups.length === 0) return <p className="empty-state">No expenses in this period.</p>;
  return (
    <>
      <ul style={{ listStyle: 'none', padding: 0 }}>
        {groups.map((g) => (
          <li key={g.key} className="rt-card">
            <p className="rt-row">
              <strong>{by === 'branch' ? shopName(g.branch_id ?? g.key) : g.label}</strong>
              <strong>{money(g.total_minor ?? 0)}</strong>
            </p>
            <p className="hint">{g.count ?? 0} {g.count === 1 ? 'expense' : 'expenses'}</p>
          </li>
        ))}
      </ul>
      <p className="rt-total">Total {money(total)}<span className="hint">{count} {count === 1 ? 'expense' : 'expenses'}</span></p>
    </>
  );
}

export function ExpensesReportScreen() {
  const scope = useReadScope();
  const window = useWindow();
  const [by, setBy] = useState<ReportGroup>('category');
  const report = useQuery({
    queryKey: ['retail', 'expenses-report', scope, window.from, window.to, by],
    queryFn: () => retail.expensesReport({ branchIds: scope, ...window.query, groupBy: by }),
  });
  return (
    <Gate screen="expensesReport" title="Expenses report">
      <p><ScreenLink screen="expenses" to="/staff/retail/expenses" tour="expenses-back-link" action>Record an expense</ScreenLink></p>
      <div data-tour="expenses-report-window"><WindowFields id="expenses-report" window={window} /></div>
      <Segmented label="Group the expenses" tour="expenses-report-group" options={GROUPS} value={by} onChange={setBy} />
      {report.isError && <Problem error={report.error} />}
      {report.isPending && <p className="loading">Loading</p>}
      <div data-tour="expenses-report-rows">
        {report.data && <ExpenseGroups groups={report.data.items ?? []} total={report.data.total_minor ?? 0} count={report.data.count ?? 0} by={by} />}
      </div>
    </Gate>
  );
}

export const ReportRoute = createLazyRoute('/staff/retail/expenses-report')({ component: ExpensesReportScreen });
