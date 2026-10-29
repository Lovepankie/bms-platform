import { QueryClientContext, keepPreviousData, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useContext, useRef, useState, type ReactNode } from 'react';
import { retail, type Supplier } from '../../../api/retail';
import { AddByName, EmptyChoice } from '../../../app/ui/EmptyChoice';
import { PickerWithAdd } from '../../../app/ui/PickerWithAdd';
import { useOptionalStaff } from '../context';
import { canUse } from './permissions';
import { branchLabel } from '../../../auth/branch';
import { MIN_REASON, reasonOf, reversalKeys } from './account-state';
import { newIdempotencyKey } from './idempotency';
import { Problem } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';

// Small pieces the purchasing screens share (ADR-033, issue #198): a cursor-paged list, the "why" panel every void, cancel and
// reversal asks for, and the supplier and branch fields. They hold no purchasing rules; those are in purchasing-state.ts and
// payables-state.ts.

/** A cursor-paged list that keeps the old answer in place while a new search answers, as the other retail lists do. */
export function useCursorList<T>(
  queryKey: readonly unknown[],
  fetchPage: (cursor: string | undefined, signal: AbortSignal) => Promise<{ items?: T[]; next_cursor?: string }>,
  enabled = true,
) {
  const query = useInfiniteQuery({
    queryKey: [...queryKey],
    queryFn: ({ pageParam, signal }) => fetchPage(pageParam || undefined, signal),
    initialPageParam: '',
    getNextPageParam: (last) => last.next_cursor || undefined,
    placeholderData: keepPreviousData,
    enabled,
  });
  const finding = query.isPlaceholderData || (query.isFetching && !query.isFetchingNextPage);
  const rows = (query.data?.pages ?? []).flatMap((p) => p.items ?? []);
  return { query, rows, finding };
}

/** The "show more" button under a cursor-paged list. */
export function MoreButton({ list, noun }: { list: { query: { hasNextPage: boolean; isFetchingNextPage: boolean; isPlaceholderData: boolean; fetchNextPage: () => unknown } }; noun: string }) {
  if (!list.query.hasNextPage) return null;
  return (
    <button type="button" onClick={() => void list.query.fetchNextPage()} disabled={list.query.isFetchingNextPage || list.query.isPlaceholderData}>
      {list.query.isFetchingNextPage ? 'Loading' : `Show more ${noun}`}
    </button>
  );
}

/** One Idempotency-Key per attempt of each action: a retry of the same action with the same reason repeats its key. */
export function useActionKeys() {
  return useRef(reversalKeys(newIdempotencyKey)).current;
}

/**
 * A button that opens a small form asking why: a reason of at least five characters, then one button. Used for cancelling an
 * order and for voiding goods received, an invoice or a payment.
 */
export function ReasonPanel({ openLabel, question, hint, confirmLabel, busy, error, onSubmit, fieldId = 'reason-text' }: {
  /** The id of the text box; two panels on one page need two ids. */
  fieldId?: string;
  openLabel: string;
  question: string;
  hint?: string;
  confirmLabel: string;
  busy?: boolean;
  error?: unknown;
  onSubmit: (reason: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState('');
  if (!open) return <p><button type="button" className="btn-sm" onClick={() => setOpen(true)}>{openLabel}</button></p>;
  return (
    <form
      className="stack"
      aria-label={openLabel}
      onSubmit={(e) => {
        e.preventDefault();
        const why = reasonOf(reason);
        if (why && !busy) onSubmit(why);
      }}
    >
      <label htmlFor={fieldId}>{question} (at least {MIN_REASON} characters)</label>
      <input id={fieldId} value={reason} maxLength={300} onChange={(e) => setReason(e.target.value)} />
      {hint && <p className="hint">{hint}</p>}
      <Problem error={error} />
      <p className="cluster">
        <BusyButton type="submit" busy={busy === true} disabled={reasonOf(reason) === null}>{confirmLabel}</BusyButton>

        <button type="button" className="btn-ghost" onClick={() => setOpen(false)}>Back</button>
      </p>
    </form>
  );
}

export interface SupplierOption { id?: string; name?: string; active?: boolean }

/** The suppliers a form may choose: the active ones, and one already chosen even if it was switched off since. */
export function useSuppliers(chosenId = ''): SupplierOption[] {
  const suppliers = useQuery({ queryKey: ['retail', 'suppliers'], queryFn: () => retail.listSuppliers() });
  return (suppliers.data ?? []).filter((s) => s.active !== false || s.id === chosenId);
}

/** The red asterisk after the label of a required field, hidden from a screen reader (which hears aria-required). */
export const Star = () => <span className="field-star" aria-hidden="true"> *</span>;

export function SupplierField({ id, label = 'Supplier', value, suppliers, disabled, required = false, onChange }: {
  id: string;
  label?: string;
  value: string;
  suppliers: SupplierOption[];
  disabled?: boolean;
  required?: boolean;
  onChange: (supplierId: string) => void;
}) {
  const me = useOptionalStaff()?.me;
  // The form's own read of the suppliers, looked up in the cache without a second request: the empty line waits until it has
  // answered. A form drawn without a query client (a test) never shows it.
  const queryClient = useContext(QueryClientContext);
  const read = queryClient?.getQueryState(['retail', 'suppliers']);
  const failed = read?.status === 'error';
  // Issue #688: the list ends with "+ Add a supplier", added in a sheet over the order or invoice and chosen.
  return (
    <PickerWithAdd
      id={id}
      label={label}
      thing="supplier"
      placeholder="Choose a supplier"
      options={suppliers.map((s) => ({ value: s.id ?? '', label: s.active === false ? `${s.name ?? ''} (switched off)` : s.name ?? '' }))}
      value={value}
      onChange={onChange}
      disabled={disabled}
      canAdd={!disabled && !!me && canUse(me, 'suppliers')}
      required={required}
      loading={!failed && read?.data === undefined}
      load={{ isError: failed, refetch: () => queryClient?.refetchQueries({ queryKey: ['retail', 'suppliers'] }) }}
      empty="No suppliers yet."
      ask="Ask the owner to add your suppliers first."
      form={(done, cancel) => (
        <AddByName id={`${id}-add`} onCancel={cancel} add={{
          label: 'Add a supplier', name: 'Name of the new supplier', send: 'Add supplier', maxLength: 120,
          create: (name) => retail.createSupplier({ name }),
          onAdded: (supplier: Supplier) => {
            queryClient?.setQueryData<Supplier[]>(['retail', 'suppliers'], (was) => [...(was ?? []).filter((s) => s.id !== supplier.id), supplier]);
            void queryClient?.invalidateQueries({ queryKey: ['retail', 'suppliers'] });
            done({ value: supplier.id ?? '', label: supplier.name ?? '' });
          },
        }} />
      )}
    />
  );
}

/**
 * A supplier's name on a detail page. Where the person may pay suppliers it opens the payment form for that supplier, which lists
 * what the supplier is owed (the open items); otherwise it is plain text.
 */
export function SupplierOwed({ id, name, payable }: { id?: string; name?: string; payable?: boolean }) {
  if (!payable || !id) return <>{name ?? ''}</>;
  return <Link to="/staff/retail/supplier-payments/new" search={{ supplier: id } as never} aria-label={`See what is owed to ${name ?? 'the supplier'}`}>{name}</Link>;
}

/** Which branch or store a form is for. With only one to choose from it is shown, not asked. */
export function BranchField({ id, label, value, branches, required = false, onChange }: {
  id: string;
  label: string;
  value: string;
  branches: { id?: string; name?: string; code?: string }[];
  required?: boolean;
  onChange: (branchId: string) => void;
}) {
  if (branches.length === 1) return <p><strong>{label}:</strong> {branchLabel(branches[0])}</p>;
  if (branches.length === 0) return <EmptyChoice id={id} label={label} required={required} text="No branch or store you may use here." ask="Ask the owner to give you one first." />;
  return (
    <>
      <label htmlFor={id}>{label}{required && <Star />}</label>
      <select id={id} value={value} aria-required={required || undefined} onChange={(e) => onChange(e.target.value)}>
        <option value="">Choose a branch or store</option>
        {branches.map((b) => <option key={b.id} value={b.id}>{branchLabel(b)}</option>)}
      </select>
    </>
  );
}

/** A status in words, with the colour of a warning for the ones that are not standing. */
export function StatusBadge({ words, warn }: { words: string; warn?: boolean }) {
  return <span className={warn ? 'badge badge-warning' : 'badge'}>{words}</span>;
}

/** The facts of a document as two columns: what it is, and its value. */
export function Facts({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <div className="table-wrap">
      <table>
        <tbody>
          {rows.map(([k, v]) => <tr key={k}><td>{k}</td><td className="num">{v}</td></tr>)}
        </tbody>
      </table>
    </div>
  );
}
