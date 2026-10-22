import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { FormError, type PaymentStatementDetail, reconciliation, statements } from '../../../api/payments';
import { money } from '../lending/loan-state';
import { useStaff } from '../context';
import { FEE_WORDS, MATCH_WORDS, canManageStatements, fileProblem, findingWords, showReconciliation } from './state';

// "Payment reconciliation" (extras slice A5 part 2, issue #224, task #317; FR-PAY-05, FR-PAY-11): the differences between what the payment provider
// says, what this system holds and what people entered by hand, and the upload of the provider's statement. Nothing here changes a loan or a
// balance; the only money it moves is the provider's fee on a line that matches, recorded once.

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong.');

function when(iso: string | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' });
}

export function UploadStatement({ onUploaded }: { onUploaded: (s: PaymentStatementDetail, replayed: boolean) => void }) {
  const [file, setFile] = useState<File | null>(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const coveredProblem = (from === '') !== (to === '') ? 'Give both days, or neither.' : from !== '' && to < from ? 'The last day is before the first.' : null;
  const upload = useMutation({
    mutationFn: async () => {
      if (!file) throw new Error('Choose the statement file.');
      return statements.upload(file, file.name, from && to ? { from, to } : undefined);
    },
    onSuccess: (detail) => onUploaded(detail, false),
  });
  const problem = file ? fileProblem(file) : null;
  const lines = upload.error instanceof FormError ? Object.entries(upload.error.fields ?? {}) : [];
  return (
    <section className="rt-card stack" aria-labelledby="stmt-upload">
      <h2 id="stmt-upload">Upload the provider's statement</h2>
      <p className="hint">
        Export the merchant statement from your payment provider's dashboard as a CSV file and upload it here. Each line is matched to your own payments, and the provider's fee on a
        matching line is recorded once. A file with a line the system cannot read is refused whole, and the lines are named.
      </p>
      <form
        className="stack"
        aria-label="Upload the statement"
        onSubmit={(e) => {
          e.preventDefault();
          if (file && !problem && !coveredProblem && !upload.isPending) upload.mutate();
        }}
      >
        <label htmlFor="stmt-file">Statement file (CSV)</label>
        <input id="stmt-file" type="file" accept=".csv,text/csv,text/plain" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        {problem && <p role="alert" className="field-error">{problem}</p>}
        <p className="hint">
          If you know the export is complete for some days, give the first and last of them: a payment recorded here on those days but missing from the export is then reported. Leave both empty and only the days strictly between the first and the last line count.
        </p>
        <label htmlFor="stmt-from">Complete from (optional)</label>
        <input id="stmt-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
        <label htmlFor="stmt-to">Complete to (optional)</label>
        <input id="stmt-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        {coveredProblem && <p role="alert" className="field-error">{coveredProblem}</p>}
        {upload.error && (
          <div role="alert" className="alert alert-danger">
            <p>{messageOf(upload.error)}</p>
            {lines.length > 0 && (
              <ul>
                {lines.slice(0, 20).map(([where, what]) => (
                  <li key={where}>{where}: {String(what)}</li>
                ))}
              </ul>
            )}
          </div>
        )}
        <p>
          <button type="submit" className="rt-primary" disabled={!file || problem !== null || coveredProblem !== null || upload.isPending}>{upload.isPending ? 'Uploading' : 'Upload'}</button>
        </p>
      </form>
    </section>
  );
}

export function StatementView({ detail, canMark }: { detail: PaymentStatementDetail; canMark: boolean }) {
  const s = detail.statement;
  const queryClient = useQueryClient();
  const [marking, setMarking] = useState<number | null>(null);
  const [note, setNote] = useState('');
  const [marked, setMarked] = useState<Set<number>>(new Set());
  const mark = useMutation({
    mutationFn: ({ rowNo, text }: { rowNo: number; text: string }) => statements.markOutside(s?.id ?? '', rowNo, text),
    onSuccess: (_row, v) => {
      setMarked((m) => new Set(m).add(v.rowNo));
      setMarking(null);
      setNote('');
      void queryClient.invalidateQueries({ queryKey: ['payments', 'reconciliation'] });
    },
  });
  return (
    <section className="rt-card stack" aria-labelledby="stmt-result">
      <h2 id="stmt-result">Statement {s?.file_name}</h2>
      <p>
        {s?.row_count} lines from {s?.period_from} to {s?.period_to}: {s?.matched_count} match, {s?.different_count} differ, {s?.unmatched_count} are not ours. Fees recorded now:{' '}
        <strong>{money(s?.fees_posted_minor, s?.currency)}</strong>.
      </p>
      <table>
        <thead>
          <tr>
            <th>Line</th>
            <th>Payment code</th>
            <th>Amount</th>
            <th>Fee</th>
            <th>Result</th>
          </tr>
        </thead>
        <tbody>
          {(detail.rows ?? []).map((r) => (
            <tr key={r.row_no}>
              <td>{r.row_no}</td>
              <td>{r.confirmation_code ?? r.order_tracking_id}</td>
              <td>{money(r.amount_minor, s?.currency)}</td>
              <td>{money(r.fee_minor, s?.currency)} ({FEE_WORDS[r.fee_status ?? ''] ?? r.fee_status})</td>
              <td>
                {MATCH_WORDS[r.match_status ?? ''] ?? r.match_status}
                {r.reviewed_outside && <span className="badge"> Outside this system (confirmed)</span>}
                {!r.reviewed_outside && (r.outside_awaiting_confirmation || marked.has(r.row_no ?? -1)) && (
                  <span className="badge"> Marked outside this system: waiting for a second person to confirm</span>
                )}
                {canMark && r.match_status === 'unmatched' && !r.reviewed_outside && !r.outside_awaiting_confirmation && !marked.has(r.row_no ?? -1) && (
                  marking === r.row_no ? (
                    <form
                      className="stack"
                      aria-label={`Mark line ${r.row_no} as outside this system`}
                      onSubmit={(e) => {
                        e.preventDefault();
                        if (note.trim().length >= 3 && !mark.isPending) mark.mutate({ rowNo: r.row_no ?? 0, text: note.trim() });
                      }}
                    >
                      <label htmlFor={`why-${r.row_no}`}>What is it</label>
                      <input id={`why-${r.row_no}`} value={note} maxLength={300} onChange={(e) => setNote(e.target.value)} />
                      {mark.error && <p role="alert" className="field-error">{messageOf(mark.error)}</p>}
                      <button type="submit" className="rt-primary" disabled={note.trim().length < 3 || mark.isPending}>Save</button>
                    </form>
                  ) : (
                    <button type="button" className="btn-ghost" onClick={() => { setMarking(r.row_no ?? null); setNote(''); }}>Not ours</button>
                  )
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {detail.rows_truncated && <p className="hint">Only the first 1,000 lines are shown.</p>}
    </section>
  );
}

export function ReconciliationPage() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const allowed = showReconciliation(me);
  const manage = canManageStatements(me);
  const [uploaded, setUploaded] = useState<{ detail: PaymentStatementDetail; replayed: boolean } | null>(null);
  const report = useQuery({ queryKey: ['payments', 'reconciliation'], queryFn: reconciliation.latest, enabled: allowed });
  const run = useMutation({
    mutationFn: reconciliation.run,
    onSuccess: (r) => queryClient.setQueryData(['payments', 'reconciliation'], r),
  });
  if (!allowed) {
    return (
      <main className="rt ln">
        <h1>Payment reconciliation</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  const data = run.data ?? report.data;
  const findings = data?.findings ?? [];
  return (
    <main className="rt ln stack">
      <h1>Payment reconciliation</h1>
      <p>
        Every night the system compares what your payment provider says with what is recorded here and what was entered by hand, and lists what differs. It changes nothing by
        itself. A difference goes away on the next run once its cause is put right.
      </p>
      {manage && (
        <UploadStatement
          onUploaded={(detail, replayed) => {
            setUploaded({ detail, replayed });
            void queryClient.invalidateQueries({ queryKey: ['payments', 'reconciliation'] });
          }}
        />
      )}
      {uploaded && <StatementView detail={uploaded.detail} canMark={manage} />}
      <section className="rt-card stack" aria-labelledby="recon-report">
        <h2 id="recon-report">Differences</h2>
        {report.error && <p role="alert" className="alert alert-danger">{messageOf(report.error)}</p>}
        {!data && !report.error && <p className="loading">Loading</p>}
        {data && !data.run && <p className="empty-state">No reconciliation has run yet.</p>}
        {data?.run && (
          <p className="hint">
            Last run {when(data.run.created_at)} ({data.run.trigger === 'manual' ? 'on request' : 'nightly'})
            {data.run.intents_examined != null && <>; {data.run.intents_examined} payments and {data.run.statements_used} statements looked at</>}.
          </p>
        )}
        {data?.run?.truncated && (
          <p role="alert" className="alert alert-warning">
            This run could not read everything, so the list below is partial and leaves out what needs the whole picture. Tell the person who looks after the system.
          </p>
        )}
        {data?.run && findings.length === 0 && <p role="status" className="alert alert-success">Nothing differs.</p>}
        <ul className="stack list-plain">
          {findings.map((f) => {
            const words = findingWords(f.kind);
            return (
              <li key={f.id} className="rt-card stack" data-testid="finding">
                <h3>{words.title}</h3>
                <p className="hint">{words.help}</p>
                <p>
                  {f.confirmation_code ?? f.order_tracking_id}
                  {f.bms_amount_minor !== undefined && f.bms_amount_minor !== null && <> | here {money(f.bms_amount_minor, f.currency)}</>}
                  {f.provider_amount_minor !== undefined && f.provider_amount_minor !== null && <> | provider {money(f.provider_amount_minor, f.currency)}</>}
                </p>
              </li>
            );
          })}
        </ul>
        {data?.findings_truncated && <p className="hint">Only the first 500 are shown.</p>}
        {run.error && <p role="alert" className="alert alert-danger">{messageOf(run.error)}</p>}
        {manage && (
          <p>
            <button type="button" className="rt-primary" disabled={run.isPending} onClick={() => run.mutate()}>{run.isPending ? 'Running' : 'Run it now'}</button>
          </p>
        )}
      </section>
    </main>
  );
}

export const Route = createLazyRoute('/staff/payments/reconciliation')({ component: ReconciliationPage });
