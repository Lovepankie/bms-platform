import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import {
  askCustomer,
  downloadQuestions,
  openIssues,
  rawRow,
  resolveAll,
  resolveIssue,
  type ImportIssue,
} from '../../../api/imports';
import { BusyButton } from '../../../app/ui/BusyButton';
import { EmptyState, NoAccess } from '../../../app/ui/EmptyState';
import { Field, FieldsNeededNote } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { Segmented } from '../../../app/ui/Segmented';
import { Sheet } from '../../../app/ui/Sheet';
import { Skeleton } from '../../../app/ui/Skeleton';
import { useStaff } from '../context';
import { VIEW_RAW, bulkWays, codeTitle, groupByCode, issuePlace, mayOpen, needsNote, severityWords, type IssueGroup } from './pipeline';

// The problems of one import (issue #141, spec sections 3.4 and 3.5, FR-IMP-05): every open problem grouped by its kind, with the
// plain message the check wrote, never a value of a row. A problem is fixed in place (a new value, the line left out with a
// reason, a warning accepted with a note, or a question for the owner), or every problem of one kind the same way at once. The
// questions for the owner download as a numbered list. The row as uploaded is shown only to a holder of core.imports.view_raw,
// and the server records every opening.

const issuesKey = (id: string) => ['import-issues', id] as const;

export function ImportProblems({ runId }: { runId: string }) {
  const { me } = useStaff();
  const allowed = mayOpen(me, '/staff/setup/imports/runs/$runId/problems');
  const seesRaw = (me.permissions ?? []).includes(VIEW_RAW);
  const issues = useQuery({ queryKey: issuesKey(runId), queryFn: () => openIssues(runId), enabled: allowed });
  const [fixing, setFixing] = useState<ImportIssue | null>(null);
  const [bulk, setBulk] = useState<IssueGroup | null>(null);
  // busy-exempt: the download button says Downloading itself and is disabled meanwhile
  // toast-exempt: saves nothing: a download changes nothing, and the device shows the saved file
  const questions = useMutation({ mutationFn: () => downloadQuestions(runId) });

  if (!allowed) {
    return (
      <main className="stack">
        <h1>Problems to fix</h1>
        <NoAccess what="the problems of this import" />
      </main>
    );
  }
  const groups = groupByCode(issues.data ?? []);
  const asked = (issues.data ?? []).filter((i) => i.ask_customer).length;
  return (
    <main className="stack">
      <h1>Problems to fix</h1>
      <Hint tour="import-problems-intro">Problems of the same kind are together. Fix one line at a time, or all of one kind the same way.</Hint>
      {issues.isError && (
        <p role="alert" className="alert alert-danger">
          Could not load the problems.
        </p>
      )}
      <Skeleton loading={issues.isPending} of="import-problems" rows={3}>
        {issues.data && groups.length === 0 && <EmptyState text="No problem is left. Go back to the import and try it." />}
        <div className="stack" data-tour="import-problems-groups">
          {groups.map((g) => (
            <GroupCard key={g.code} group={g} onFix={setFixing} onBulk={() => setBulk(g)} />
          ))}
        </div>
      </Skeleton>
      <section className="rt-card stack" aria-labelledby="import-questions-title" data-tour="import-problems-questions">
        <h2 id="import-questions-title">Questions for the owner</h2>
        <p>
          {asked === 0 ? 'No question yet.' : `${asked} ${asked === 1 ? 'question' : 'questions'}.`} The list has the file and line of each,
          and never a name, phone or ID number.
        </p>
        <button type="button" /* busy-exempt: a download, shown as busy by its own text */ className="btn" disabled={questions.isPending} onClick={() => questions.mutate()}>
          {questions.isPending ? 'Downloading the questions' : 'Download the questions'}
        </button>
        {questions.isError && <p role="alert" className="alert alert-danger">{questions.error instanceof Error ? questions.error.message : 'Could not download the questions.'}</p>}
      </section>
      <Sheet open={fixing !== null} onClose={() => setFixing(null)} title="Fix this line">
        {fixing && <FixForm runId={runId} issue={fixing} seesRaw={seesRaw} onDone={() => setFixing(null)} />}
      </Sheet>
      <Sheet open={bulk !== null} onClose={() => setBulk(null)} title="Fix them all the same way">
        {bulk && <BulkForm runId={runId} group={bulk} onDone={() => setBulk(null)} />}
      </Sheet>
    </main>
  );
}

function GroupCard({ group, onFix, onBulk }: { group: IssueGroup; onFix: (issue: ImportIssue) => void; onBulk: () => void }) {
  const first = group.issues[0];
  const id = `import-group-${group.code}`;
  return (
    <section className="rt-card stack" aria-labelledby={id}>
      <h2 id={id}>
        {codeTitle(group.code)} ({group.issues.length})
      </h2>
      <p>
        <strong>{severityWords(group.severity)}.</strong> {first?.message}
      </p>
      {group.issues.length > 1 && (
        <button type="button" /* busy-exempt: opens the sheet, sends nothing */ className="btn" onClick={onBulk}>
          Fix all {group.issues.length} the same way
        </button>
      )}
      <details className="recon-items">
        <summary>See each line</summary>
        <ul className="stack">
          {group.issues.map((i) => (
            <li key={i.id}>
              <p>
                {issuePlace(i)}
                {i.ask_customer ? ', asked of the owner' : ''}
              </p>
              <button type="button" /* busy-exempt: opens the sheet, sends nothing */ className="btn btn-sm" onClick={() => onFix(i)}>
                Fix line {i.line}
              </button>
            </li>
          ))}
        </ul>
      </details>
    </section>
  );
}

type Way = 'edit_value' | 'exclude_row' | 'override' | 'ask_customer';

function FixForm({ runId, issue, seesRaw, onDone }: { runId: string; issue: ImportIssue; seesRaw: boolean; onDone: () => void }) {
  const queryClient = useQueryClient();
  const ways: { value: Way; label: string }[] = [
    { value: 'edit_value', label: 'New value' },
    { value: 'exclude_row', label: 'Leave out' },
    ...(issue.severity === 'warning' ? [{ value: 'override' as Way, label: 'Accept' }] : []),
    { value: 'ask_customer', label: 'Ask the owner' },
  ];
  const [way, setWay] = useState<Way>('edit_value');
  const [value, setValue] = useState('');
  const [note, setNote] = useState('');
  const [tried, setTried] = useState(false);
  const [rawShown, setRawShown] = useState(false);
  const raw = useQuery({ queryKey: ['import-raw', issue.id], queryFn: () => rawRow(issue.id ?? ''), enabled: rawShown, staleTime: 0, gcTime: 0 });
  // Never kept: each opening asks the server again, which records it (spec section 6.3), and the cells leave memory with the sheet.
  const save = useMutation({
    meta: { success: `Line ${issue.line} fixed` },
    mutationFn: () =>
      way === 'ask_customer'
        ? askCustomer(issue.id ?? '', issue.version ?? 1, true)
        : resolveIssue(issue.id ?? '', {
            resolution: way,
            version: issue.version ?? 1,
            value: way === 'edit_value' ? value : undefined,
            note: needsNote(way) ? note.trim() : undefined,
          }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: issuesKey(runId) });
      await queryClient.invalidateQueries({ queryKey: ['import-metrics', runId] });
      onDone();
    },
  });
  const noteMissing = needsNote(way) && note.trim().length < 3;

  function submit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (!noteMissing) save.mutate();
  }

  return (
    <form className="stack" onSubmit={submit} noValidate>
      <p>
        <strong>{issuePlace(issue)}.</strong> {issue.message}
      </p>
      {seesRaw && !rawShown && (
        <button type="button" /* busy-exempt: shows a read; the server records the opening */ className="btn" onClick={() => setRawShown(true)}>
          See the line as uploaded
        </button>
      )}
      {rawShown && raw.isPending && <Skeleton loading rows={2} />}
      {raw.isError && <p role="alert" className="alert alert-danger">{raw.error instanceof Error ? raw.error.message : 'Could not show the line.'}</p>}
      {raw.data && (
        <dl className="import-figures" data-testid="import-raw">
          {(raw.data.cells ?? []).map((c) => (
            <Cell key={c.column} label={c.column ?? ''} value={c.value ?? ''} />
          ))}
        </dl>
      )}
      <Segmented label="How to fix it" options={ways} value={way} onChange={setWay} />
      {needsNote(way) && <FieldsNeededNote />}
      {way === 'edit_value' && (
        <Field label="New value" value={value} onChange={(e) => setValue(e.target.value)} hint="Leave it empty to keep the field empty" />
      )}
      {needsNote(way) && (
        <Field
          label="Why"
          required
          value={note}
          maxLength={500}
          onChange={(e) => setNote(e.target.value)}
          error={noteMissing ? 'Say why, in a few words.' : null}
          showError={tried}
        />
      )}
      {save.isError && (
        <p role="alert" className="alert alert-danger">
          {save.error instanceof Error ? save.error.message : 'Could not save the fix.'}
        </p>
      )}
      <BusyButton type="submit" className="btn-primary btn-block" busy={save.isPending}>
        Save the fix
      </BusyButton>
    </form>
  );
}

function BulkForm({ runId, group, onDone }: { runId: string; group: IssueGroup; onDone: () => void }) {
  const queryClient = useQueryClient();
  const ways = bulkWays(group);
  const [way, setWay] = useState<string>(ways[0]?.value ?? 'exclude_row');
  const [note, setNote] = useState('');
  const [tried, setTried] = useState(false);
  const save = useMutation({
    meta: { success: (n: number) => `${n} lines fixed the same way` },
    mutationFn: () => resolveAll(runId, { code: group.code, resolution: way, note: needsNote(way) ? note.trim() : undefined }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: issuesKey(runId) });
      await queryClient.invalidateQueries({ queryKey: ['import-metrics', runId] });
      onDone();
    },
  });
  const noteMissing = needsNote(way) && note.trim().length < 3;

  function submit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (!noteMissing) save.mutate();
  }

  return (
    <form className="stack" onSubmit={submit} noValidate>
      <p>
        {group.issues.length} lines: {codeTitle(group.code).toLowerCase()}.
      </p>
      <Segmented label="How to fix them" options={ways} value={way} onChange={setWay} />
      {needsNote(way) && <FieldsNeededNote />}
      {needsNote(way) && (
        <Field
          label="Why"
          required
          value={note}
          maxLength={500}
          onChange={(e) => setNote(e.target.value)}
          error={noteMissing ? 'Say why, in a few words.' : null}
          showError={tried}
        />
      )}
      {save.isError && (
        <p role="alert" className="alert alert-danger">
          {save.error instanceof Error ? save.error.message : 'Could not fix them.'}
        </p>
      )}
      <BusyButton type="submit" className="btn-primary btn-block" busy={save.isPending}>
        Fix all {group.issues.length}
      </BusyButton>
    </form>
  );
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </>
  );
}

const route = getRouteApi('/staff/setup/imports/runs/$runId/problems');

function ImportProblemsRoutePage() {
  const { runId } = route.useParams();
  return <ImportProblems key={runId} runId={runId} />;
}

export const Route = createLazyRoute('/staff/setup/imports/runs/$runId/problems')({ component: ImportProblemsRoutePage });
