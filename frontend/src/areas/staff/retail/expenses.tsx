import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Segmented } from '../../../app/ui/Segmented';
import { businessToday, isWaiting, retail, type ApprovalWaiting, type Expense, type ExpenseCategory, type ExpenseGroup, type ExpenseItem } from '../../../api/retail';
import { useStaff } from '../context';
import { DateField, RecordsByShop, Saved, WindowFields, useEntryDate, useReadScope, useSavedFor, useWindow } from './cash-ui';
import type { CashFigures } from './cash-state';
import { EXPENSE_ORDER } from './list-chain-state';
import {
  MIN_EXPLANATION, activeCategories, deleteExpenseReason, draftOfExpense, itemsOf, needsExplanation, planExpense, withItem,
} from './expenses-state';
import { newIdempotencyKey, usePersistedDraft } from './idempotency';
import { PartyPicker, useCashParties } from './party-picker';
import { CASHBOOK_READ, EXPENSE_RECORD, holds, holdsAt, needsOf } from './permissions';
import { ScreenLink } from './screen-link';
import { sayDone, useToast } from './toast';
import { BranchRequired, Gate, Problem, WaitingForApproval, money, useBranchName, useSingleBranch } from './ui';
import { showDate } from './transfers';
import { ActionBar } from '../../../app/ui/ActionBar';
import { AddByName, EmptyChoice, showsEmptyChoice, type AddInline, type ChoiceAdd } from '../../../app/ui/EmptyChoice';
import { addOn } from './screen-link';
import type { Kept } from '../../../offline/entries';
import { EditNeedsInternet, KeptOnPhone, OfflineFormNote, isKept, useEntrySave } from '../../../offline/keep-entry';
import { RECEIPT_KEPT, RECEIPT_LATER_NOTE, ReceiptPhoto, ReceiptPhotoChoice, bounded, withPhotoNote } from './receipt-photo';

// Company expenses (FR-RET-24): shop, date, category, then an item of that category, an optional
// beneficiary, the amount, and an explanation when the item needs one. An optional receipt photo (issue #819, ADR-124) is
// sent first and named by the expense's receipt_document_id; offline the expense is kept without it.

interface ExpenseDraft { date: string; dateReason?: string; categoryId: string; itemId: string; partyId: string; amount: string; explanation: string }

export function ExpenseForm({ branchId, onSaved, onWaiting, replaces, onCancel }: {
  branchId: string;
  onSaved?: (saved: Expense) => void;
  onWaiting?: (amountMinor: number) => void;
  /** Edit (issue #830): the form opens filled in with this expense; saving voids it and saves the form as a new one naming it. */
  replaces?: Expense;
  onCancel?: () => void;
}) {
  const queryClient = useQueryClient();
  const { me } = useStaff();
  const { key, draft, setDraft, finish, discard, name } = usePersistedDraft<ExpenseDraft>(replaces ? `expense-edit:${me.user_id ?? ''}:${replaces.id ?? ''}` : `expense:${me.user_id ?? ''}:${branchId}`, replaces ? draftOfExpense(replaces) : {
    date: businessToday(), categoryId: '', itemId: '', partyId: '', amount: '', explanation: '',
  });
  const categories = useQuery({ queryKey: ['retail', 'expense-categories', true], queryFn: () => retail.listExpenseCategories({ activeOnly: true }) });
  const parties = useCashParties();
  const list = categories.data ?? [];
  const items = itemsOf(list, draft.categoryId);
  const explain = needsExplanation(list, draft.categoryId, draft.itemId);
  // Issue #812: offline, or when the network drops, a new expense is kept on the phone; an edit stays online only. Offline the
  // phone dates it and no date box shows, so a draft's earlier day never holds the save (second review of #839).
  const entries = useEntrySave();
  const entry = useEntryDate(branchId, entries.offlineMode ? businessToday() : draft.date, draft.dateReason);
  const plan = planExpense({ branchId, ...draft, entry }, list);
  const detail = [list.find((c) => c.id === draft.categoryId)?.name, items.find((i) => i.id === draft.itemId)?.name].filter(Boolean).join(', ');
  // Issue #819: the photo is sent once per file chosen, so a retry under the same key names the same document; an edit keeps
  // the photo the expense had unless another is chosen. A photo that fails, or takes longer than RECEIPT_SEND_MS, is left out,
  // the expense is saved without it and the saved message says so. Every save, kept on the phone or sent, clears the photo.
  const [receipt, setReceipt] = useState<File | null>(null);
  const sentReceipt = useRef<{ file: File; id?: string } | null>(null);
  const photoNote = useRef<string | null>(null);
  const keptPhoto = useRef(false);
  const withReceipt = async () => {
    photoNote.current = null;
    let id = replaces?.receipt_document_id;
    if (receipt && !entries.offlineMode) {
      if (sentReceipt.current?.file !== receipt) {
        sentReceipt.current = await bounded((signal) => retail.sendExpenseReceiptPhoto(receipt, signal)).then((r) => ({ file: receipt, id: r.photo_id }), () => null);
      }
      if (sentReceipt.current?.id) id = sentReceipt.current.id;
      else photoNote.current = RECEIPT_LATER_NOTE;
    }
    return id ? { ...plan.request, receipt_document_id: id } : plan.request;
  };
  // success-screen: the Saved panel replaces the form; one that waits for approval says so on the form instead; an edited one (issue #830) closes its form
  const save = useMutation({
    // Edit (issue #830): one call voids the old expense and saves this one naming it, in one transaction on the server, so any
    // refusal (an amount that would wait for an administrator, an item switched off) leaves the old expense as it was.
    mutationFn: async (): Promise<Expense | ApprovalWaiting | Kept> => {
      const request = await withReceipt();
      // The lead's decision on the second review of #846: an expense kept on the phone never carries its photo (the server's
      // key covers the expense without it, so the copy sent later is the same expense); the photo is added from the record.
      const { receipt_document_id: photoId, ...kept } = request;
      keptPhoto.current = photoId != null;
      return replaces?.id ? retail.replaceExpense(replaces.id, request, key) : entries.save(
        { kind: 'expense', body: kept, key, branchId, detail, amountMinor: request.amount_minor, fix: { to: '/staff/retail/expenses', draftName: name, draft } },
        () => retail.createExpense(request, key),
      );
    },
    meta: { success: (saved: Expense) => (isWaiting(saved) ? null : withPhotoNote(`Expense${saved.amount_minor != null ? ` of ${money(saved.amount_minor)}` : ''} ${replaces ? 'updated' : 'saved'}`, photoNote.current)) },
    onSuccess: (saved) => {
      setReceipt(null);
      sentReceipt.current = null;
      if (isKept(saved)) {
        // After the kept words of the app's toast: the photo stayed behind and is added from the record.
        if (keptPhoto.current) sayDone(RECEIPT_KEPT);
        discard();
        return;
      }
      finish();
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      // ADR-091: an expense worth the business's amount waits for an administrator and is not in the cash book until then.
      if (isWaiting(saved)) onWaiting?.(saved.amount_minor ?? 0);
      else onSaved?.(saved);
    },
  });
  const update = (patch: Partial<ExpenseDraft>) => setDraft((d) => ({ ...d, ...patch }));
  // ADR-118 (issue #802): whoever records expenses adds a missing item in place, the same way as a new beneficiary.
  // A name the category has already picks that item; the new one goes into the list at once and is chosen.
  const [addingItem, setAddingItem] = useState(false);
  const addItem: AddInline<ExpenseItem> | null = draft.categoryId !== '' && holdsAt(me, EXPENSE_RECORD, branchId) ? {
    label: 'Add a new item', name: 'Name of the new item', send: 'Add to the list', maxLength: 100,
    // The category is the one chosen when the name is sent; the item is picked only if it is still the chosen one.
    create: async (name) => {
      const categoryId = draft.categoryId;
      const row = await retail.createExpenseItem(categoryId, { name, reuse_existing: true });
      queryClient.setQueryData<ExpenseCategory[]>(['retail', 'expense-categories', true], (old) => withItem(old, categoryId, row));
      void queryClient.invalidateQueries({ queryKey: ['retail', 'expense-categories'] });
      setDraft((d) => (d.categoryId === categoryId ? { ...d, itemId: row.id ?? '', explanation: '' } : d));
      return row;
    },
    onAdded: () => undefined,
  } : null;
  const editOffline = replaces !== undefined && entries.offlineMode;
  const blocked = plan.problem !== null || save.isPending || editOffline;
  if (entries.kept) return <KeptOnPhone item={entries.kept} onNew={entries.another} />;
  return (
    <form onSubmit={(e) => { e.preventDefault(); if (!blocked) save.mutate(); }}>
      {editOffline ? <EditNeedsInternet /> : entries.offlineMode && <OfflineFormNote what="expense" />}
      {!entries.offlineMode && <DateField id="expense-date" label="Date" entry={entry} value={entry.day} reason={draft.dateReason} onChange={(date) => update({ date })} onReason={(dateReason) => update({ dateReason })} />}

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

      {addingItem && addItem ? (
        <AddByName id="expense-item-add" title={addItem.label} add={{ ...addItem, onAdded: (row) => { setAddingItem(false); addItem.onAdded(row); } }} onCancel={() => setAddingItem(false)} />
      ) : draft.categoryId !== '' && items.length === 0 ? (
        <EmptyChoice id="expense-item" label="Item" text="This category has no items yet." ask="Ask the owner to add its items first."
          add={addItem ?? (addOn(me, 'expenseSetup', 'Add an item to it', '/staff/retail/expense-lists') as ChoiceAdd<ExpenseItem> | null)} />
      ) : (
      <>
      <label htmlFor="expense-item">Item</label>
      <select id="expense-item" value={draft.itemId} disabled={draft.categoryId === ''} onChange={(e) => update({ itemId: e.target.value, explanation: '' })}>
        <option value="">{draft.categoryId === '' ? 'Choose a category first' : 'Choose an item'}</option>
        {items.map((i) => (
          <option key={i.id} value={i.id}>{i.name}</option>
        ))}
      </select>
      {addItem && <button type="button" className="btn-sm" onClick={() => setAddingItem(true)}>{addItem.label}</button>}
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

      <ReceiptPhotoChoice id="expense-receipt" file={receipt} onChange={setReceipt} />

      {plan.problem && draft.categoryId !== '' && draft.amount.trim() !== '' && <p role="alert" className="rt-flag">{plan.problem}</p>}
      <Problem error={save.error} />
      <ActionBar>
        <BusyButton busy={save.isPending} type="submit" className="rt-primary" notReady={blocked}>
          {replaces ? 'Save changes' : 'Save expense'}
        </BusyButton>
        {onCancel && <button type="button" /* busy-exempt: cancel waits for the save */ className="btn-ghost" disabled={save.isPending} onClick={onCancel}>Cancel</button>}
      </ActionBar>
    </form>
  );
}

/**
 * Edit and Delete on an expense, the old app's buttons (issue #830), offered where the expense may be voided (retail.cashbook.void,
 * the owner's). Delete asks first and voids it; Edit opens the expense form filled in, and saving voids the old one and saves the new
 * one naming it (needs retail.expense.record there too). Both keep the old expense, marked Voided.
 * JUSTIFICATION-A3: #830 asks for Edit and Delete on each expense row; the void and the form are reused, this only joins them.
 */
export function ExpenseRowActions({ row, onDeleted }: { row: Expense; onDeleted?: () => void }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<'edit' | 'delete' | null>(null);
  const [reason, setReason] = useState('');
  const key = useRef(newIdempotencyKey());
  // toast-exempt: the Expenses screen says it through onDeleted (show with success)
  const remove = useMutation({
    mutationFn: () => retail.voidExpense(row.id ?? '', deleteExpenseReason(reason), key.current),
    onSuccess: () => {
      setMode(null);
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
      onDeleted?.();
    },
  });
  const branchId = row.branch_id ?? '';
  if (mode === 'edit') {
    return <ExpenseForm branchId={branchId} replaces={row} onSaved={() => setMode(null)} onWaiting={() => setMode(null)} onCancel={() => setMode(null)} />;
  }
  if (mode === 'delete') {
    return (
      <form className="stack" aria-label="Delete this expense" onSubmit={(e) => { e.preventDefault(); if (!remove.isPending) remove.mutate(); }}>
        <p className="alert alert-warning" data-testid="delete-expense-question">
          <strong>Delete this expense?</strong> It stops counting in the cash book and the reports. It stays in this list marked Voided.
        </p>
        <label htmlFor={`delete-expense-${row.id ?? ''}`}>Why? (optional)</label>
        <input id={`delete-expense-${row.id ?? ''}`} maxLength={300} value={reason} onChange={(e) => { key.current = newIdempotencyKey(); setReason(e.target.value); }} />
        <Problem error={remove.error} />
        <p className="cluster">
          <BusyButton busy={remove.isPending} type="submit" className="btn-danger">Yes, delete it</BusyButton>
          <button type="button" /* busy-exempt: keep waits for the delete */ className="btn-ghost" disabled={remove.isPending} onClick={() => { setMode(null); setReason(''); remove.reset(); }}>Keep it</button>
        </p>
      </form>
    );
  }
  return (
    <p className="cluster">
      {holdsAt(me, EXPENSE_RECORD, branchId) && <button type="button" className="btn-sm" onClick={() => setMode('edit')}>Edit</button>}
      <button type="button" className="btn-sm btn-danger" onClick={() => setMode('delete')}>Delete</button>
    </p>
  );
}

/** Issue #816: each group of the expense records totals what was spent, by category as the old app did. */
const EXPENSE_FIGURES: CashFigures<Expense> = { amount: (r) => r.amount_minor, category: (r) => ({ id: r.category_id, name: r.category_name }) };

export function ExpenseRecords({ onVoided }: { onVoided?: (message: string) => void }) {
  const { me } = useStaff();
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
          list="expenses"
          levels={EXPENSE_ORDER}
          figures={EXPENSE_FIGURES}
          totalWord="Spent"
          actions={(row, mayVoid) => (mayVoid ? <ExpenseRowActions row={row} onDeleted={() => onVoided?.('Expense deleted.')} /> : null)}
          renderRow={(row) => (
            <>
              <strong>{showDate(row.business_date)}</strong> <span className="muted">{money(row.amount_minor ?? 0)}</span>
              <br />
              {row.category_name} / {row.item_name}
              {row.party_name ? `, for ${row.party_name}` : ''}
              {row.explanation ? <><br />{row.explanation}</> : null}
              <div><ReceiptPhoto kind="expense" recordId={row.id} has={row.receipt_document_id != null} canAdd={!row.voided && holdsAt(me, EXPENSE_RECORD, row.branch_id ?? '')} /></div>
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
          <ExpenseForm key={`${branchId}-${round}`} branchId={branchId} onSaved={setSaved} onWaiting={(amount) => { setWaiting(amount); show('Sent for approval.', 'info'); }} />
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
