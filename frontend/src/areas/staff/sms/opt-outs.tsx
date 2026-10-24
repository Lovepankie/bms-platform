import { keepPreviousData, useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { SmsError, sms, type SmsImportResult, type SmsOptOut } from '../../../api/sms';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Highlight, NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { useStaff } from '../context';
import { LiftConfirm } from './lift';
import { mayManageSettings, mayReadNotifications } from './state';
import { NoAccess } from '../../../app/ui/EmptyState';

// The SMS opt-out list (extras slice B3, issue #218; ADR-036 decision 8): who is not to be texted. A row is about a member
// (it follows the member to any number; staff make these on the member's own page) or about a number alone (a person who
// asked to stop, or a pasted list of numbers). Numbers are shown masked and are never stored. The list searches as you type,
// pages by cursor, and an administrator can add a number, paste a list (checked first, then added) and lift any row.

export const SCOPE_WORDS: Record<string, string> = {
  reminders: 'Reminders and arrears notices',
  all: 'Every text message, receipts too',
};

export const KIND_WORDS: Record<string, string> = { member: 'Member', number: 'Number' };

export const OUTCOME_WORDS: Record<string, string> = {
  added: 'Added',
  changed: 'Made stricter',
  unchanged: 'Already on the list',
  duplicate_in_list: 'Repeated in this list',
  invalid_phone: 'Not a number we can text',
};

/** The result of a pasted list in words, never with the numbers in it. */
export function importWords(r: Pick<SmsImportResult, 'dry_run' | 'rows_read' | 'added' | 'changed' | 'unchanged' | 'invalid'>): string {
  const parts = [`${r.rows_read} read`, `${r.added} ${r.dry_run ? 'would be added' : 'added'}`];
  if (r.changed > 0) parts.push(`${r.changed} ${r.dry_run ? 'would be made stricter' : 'made stricter'}`);
  if (r.unchanged > 0) parts.push(`${r.unchanged} already there`);
  if (r.invalid > 0) parts.push(`${r.invalid} not a number we can text`);
  return parts.join(', ');
}

export function OptOutRows({ items, term, canManage, busy, onRemove }: {
  items: SmsOptOut[];
  term: string;
  canManage: boolean;
  busy: boolean;
  onRemove: (item: SmsOptOut) => void;
}) {
  if (items.length === 0) return <p className="empty-state">Nobody is on the list.</p>;
  return (
    <div className="table-wrap">
      <table>
        <caption className="visually-hidden">People and numbers that are not to be texted</caption>
        <thead>
          <tr>
            <th scope="col">Who</th>
            <th scope="col">Number</th>
            <th scope="col">Stops</th>
            <th scope="col">Since</th>
            {canManage && <th scope="col"><span className="visually-hidden">Actions</span></th>}
          </tr>
        </thead>
        <tbody>
          {items.map((i) => (
            <tr key={i.id}>
              <td>
                {KIND_WORDS[i.kind] ?? i.kind}
                {i.member_id && (
                  <>
                    {' '}
                    <Link to="/staff/lending/members/$memberId/savings" params={{ memberId: i.member_id }}>Open the member</Link>
                  </>
                )}
              </td>
              <td className="num">{i.recipient_masked ? <Highlight text={i.recipient_masked} term={term} /> : 'Any number'}</td>
              <td>{SCOPE_WORDS[i.scope] ?? i.scope}</td>
              <td>{new Date(i.created_at).toLocaleDateString(undefined, { dateStyle: 'medium' })}</td>
              {canManage && (
                <td>
                  <button type="button" /* busy-exempt: opens the confirmation */ className="btn-ghost btn-sm" disabled={busy} onClick={() => onRemove(i)} aria-label={`Lift the opt-out for ${i.recipient_masked ?? 'this member'}`}>
                    Lift
                  </button>
                </td>
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AddNumber({ onAdded }: { onAdded: () => void }) {
  const [phone, setPhone] = useState('');
  const [scope, setScope] = useState('all');
  const [reason, setReason] = useState('');
  const add = useMutation({
    mutationFn: () => sms.addOptOut(phone, scope, reason.trim() === '' ? undefined : reason.trim()),
    onSuccess: () => { setPhone(''); setReason(''); onAdded(); },
  });
  const error = add.error instanceof SmsError ? add.error : null;
  return (
    <form
      aria-label="Add a number"
      data-tour="sms-optouts-add"
      className="rt-card form-stack"
      onSubmit={(e) => { e.preventDefault(); if (phone.trim() !== '' && !add.isPending) add.mutate(); }}
    >
      <h2>Add a number</h2>
      <label htmlFor="sms-oo-phone">Phone number</label>
      <input id="sms-oo-phone" inputMode="tel" autoComplete="off" value={phone} onChange={(e) => { setPhone(e.target.value); add.reset(); }} aria-invalid={error !== null} />
      {error && !error.fields.reason && <p role="alert" className="field-error">{error.fields.phone ?? error.message}</p>}
      <label htmlFor="sms-oo-scope">Stop</label>
      <select id="sms-oo-scope" value={scope} onChange={(e) => setScope(e.target.value)}>
        <option value="all">{SCOPE_WORDS.all}</option>
        <option value="reminders">{SCOPE_WORDS.reminders}</option>
      </select>
      <label htmlFor="sms-oo-reason">Why (only to make a number's block narrower)</label>
      <input id="sms-oo-reason" autoComplete="off" maxLength={200} value={reason} onChange={(e) => { setReason(e.target.value); add.reset(); }} aria-invalid={error !== null && error.fields.reason !== undefined} />
      <p className="field-hint">Needed only when the number is already stopped for every message and you choose reminders only. Words about why, not names or numbers.</p>
      {error?.fields.reason && <p role="alert" className="field-error">{error.fields.reason}</p>}
      {add.isSuccess && <p role="status" className="alert alert-success">Added.</p>}
      <p>
        <BusyButton type="submit" className="rt-primary" busy={add.isPending} notReady={phone.trim() === ''}>
          Add to the list
        </BusyButton>
      </p>
    </form>
  );
}

function PasteList({ onAdded }: { onAdded: () => void }) {
  const [text, setText] = useState('');
  const [scope, setScope] = useState('all');
  const [checked, setChecked] = useState<SmsImportResult | null>(null);
  const run = useMutation({
    mutationFn: (dryRun: boolean) => sms.importOptOuts(text, scope, dryRun),
    onSuccess: (result) => {
      setChecked(result);
      if (!result.dry_run) {
        setText('');
        onAdded();
      }
    },
  });
  const error = run.error instanceof SmsError ? run.error : null;
  const invalid = checked?.rows.filter((r) => r.outcome === 'invalid_phone') ?? [];
  return (
    <form
      aria-label="Paste a list of numbers"
      data-tour="sms-optouts-paste"
      className="rt-card form-stack"
      onSubmit={(e) => { e.preventDefault(); if (text.trim() !== '' && !run.isPending) run.mutate(true); }}
    >
      <h2>Paste a list of numbers</h2>
      <label htmlFor="sms-oo-paste">Numbers, one on each line (or separated by commas)</label>
      <textarea id="sms-oo-paste" rows={5} value={text} onChange={(e) => { setText(e.target.value); setChecked(null); run.reset(); }} />
      <p className="field-hint">Up to 2000 at a time. Each is read as a Uganda mobile number; a number we cannot read without guessing is left out and listed by its place in the list. A number already on the list is never made less strict.</p>
      <label htmlFor="sms-oo-paste-scope">Stop</label>
      <select id="sms-oo-paste-scope" value={scope} onChange={(e) => { setScope(e.target.value); setChecked(null); }}>
        <option value="all">{SCOPE_WORDS.all}</option>
        <option value="reminders">{SCOPE_WORDS.reminders}</option>
      </select>
      {error && <p role="alert" className="field-error">{error.fields.numbers ?? error.message}</p>}
      {checked && (
        <div role="status" className="alert alert-info stack">
          <p>{checked.dry_run ? 'Checked, nothing added yet: ' : 'Done: '}{importWords(checked)}.</p>
          {invalid.length > 0 && <p>Not read: {invalid.map((r) => `number ${r.line}`).join(', ')}.</p>}
        </div>
      )}
      <p className="cluster">
        <BusyButton type="submit" className="btn-ghost" busy={run.isPending && run.variables === true} disabled={text.trim() === '' || run.isPending}>
          Check the list
        </BusyButton>
        {checked?.dry_run && checked.added + checked.changed > 0 && (
          <BusyButton type="button" className="rt-primary" busy={run.isPending && run.variables === false} disabled={run.isPending} onClick={() => run.mutate(false)}>
            {`Add ${checked.added + checked.changed} to the list`}
          </BusyButton>
        )}
      </p>
    </form>
  );
}

export function OptOutsScreen() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const search = useLiveSearch();
  const [scope, setScope] = useState('');
  const [kind, setKind] = useState('');
  const allowed = mayReadNotifications(me);
  const pages = useInfiniteQuery({
    queryKey: ['sms', 'opt-outs', search.term, scope, kind],
    queryFn: ({ pageParam, signal }) =>
      sms.optOuts({ q: search.term || undefined, scope: scope || undefined, kind: kind || undefined, cursor: pageParam }, signal),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor || undefined,
    placeholderData: keepPreviousData,
    enabled: allowed,
    retry: false,
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['sms', 'opt-outs'] });
  // Lifting is two steps: "Lift" opens the confirmation with the reason; only its button sends anything.
  const [lifting, setLifting] = useState<SmsOptOut | null>(null);
  const lift = useMutation({
    mutationFn: (v: { item: SmsOptOut; reason: string }) => sms.removeOptOut(v.item.id, v.reason),
    onSuccess: () => {
      setLifting(null);
      return refresh();
    },
    onError: refresh,
  });
  if (!allowed) return <NoAccess what="the opt-out list" />;
  const canManage = mayManageSettings(me);
  const items = (pages.data?.pages ?? []).flatMap((p) => p.items);
  const fetching = pages.isPlaceholderData || (pages.isFetching && !pages.isFetchingNextPage);

  return (
    <div className="stack">
      <p>
        These people and numbers get no text message of the kind chosen. Staff can also stop messages to one member on the member's own page. Numbers are shown masked and are not kept.
      </p>
      <form role="search" className="ln-search" data-tour="sms-optouts-search" onSubmit={(e) => { e.preventDefault(); search.flush(); }}>
        <SearchBox id="sms-oo-q" label="Find a number by its last digits" search={search} fetching={fetching} count={pages.data ? items.length : undefined} more={pages.hasNextPage} hint="For example 0601" />
        <label htmlFor="sms-oo-kind">Who</label>
        <select id="sms-oo-kind" value={kind} onChange={(e) => setKind(e.target.value)}>
          <option value="">Members and numbers</option>
          <option value="member">Members</option>
          <option value="number">Numbers</option>
        </select>
        <label htmlFor="sms-oo-filter-scope">Stops</label>
        <select id="sms-oo-filter-scope" value={scope} onChange={(e) => setScope(e.target.value)}>
          <option value="">Anything</option>
          <option value="reminders">{SCOPE_WORDS.reminders}</option>
          <option value="all">{SCOPE_WORDS.all}</option>
        </select>
      </form>
      {pages.isPending && <p className="loading">Loading the list</p>}
      {pages.error instanceof Error && <p role="alert" className="alert alert-danger">{pages.error.message}</p>}
      {lifting && (
        <LiftConfirm
          what={lifting.recipient_masked ?? 'this member'}
          busy={lift.isPending}
          error={lift.error instanceof Error ? lift.error.message : null}
          onConfirm={(reason) => lift.mutate({ item: lifting, reason })}
          onCancel={() => { setLifting(null); lift.reset(); }}
        />
      )}
      {pages.data && (
        <SearchResults fetching={fetching}>
          {items.length === 0 && search.term ? (
            <NoResults term={search.term} onClear={search.clear} noun="numbers" />
          ) : (
            <OptOutRows items={items} term={search.term} canManage={canManage} busy={lift.isPending} onRemove={(i) => { lift.reset(); setLifting(i); }} />
          )}
        </SearchResults>
      )}
      {pages.hasNextPage && (
        <button type="button" className="btn-block" disabled={pages.isFetchingNextPage || pages.isPlaceholderData} onClick={() => void pages.fetchNextPage()}>
          {pages.isFetchingNextPage ? 'Loading' : 'Load more'}
        </button>
      )}
      {canManage && (
        <>
          <AddNumber onAdded={() => void refresh()} />
          <PasteList onAdded={() => void refresh()} />
        </>
      )}
    </div>
  );
}

export const Route = createLazyRoute('/staff/sms/opt-outs')({ component: OptOutsScreen });
