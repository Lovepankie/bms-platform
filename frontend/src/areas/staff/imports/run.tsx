import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import {
  askApproval,
  certificateOf,
  checkFiles,
  dryRunOf,
  importFiles,
  importRun,
  mappingChoices,
  markReady,
  previewOf,
  removeFile,
  runMetrics,
  saveFile,
  tryImport,
  uploadFile,
  type Balances,
  type Certificate,
  type DryRun,
  type ImportFile,
  type ImportRun,
  type MappingChoice,
  type RunMetrics,
} from '../../../api/imports';
import { formatMinor } from '../../../components/money';
import { ActionBar } from '../../../app/ui/ActionBar';
import { BusyButton } from '../../../app/ui/BusyButton';
import { NoAccess } from '../../../app/ui/EmptyState';
import { Field, FieldsNeededNote } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { showDate } from '../../../app/ui/PeriodPicker';
import { Sheet } from '../../../app/ui/Sheet';
import { Skeleton } from '../../../app/ui/Skeleton';
import { useStaff } from '../context';
import { mayOpen, nextStep, quantity, stateWords, type NextStep } from './pipeline';
import { RUNS_KEY } from './runs';
import { StageProgress } from './stage-progress';

// One import (issue #141, spec sections 2, 3 and 6.4): the steps it has been through, its files with the sheet layout each was
// read with, what the check found, what will come in and the trial run, then the approval, the sign-off and the certificate of
// deletion. The one main action is always the next step. Nothing here holds a value of a row: counts, codes, files and lines.

const runKey = (id: string) => ['import-run', id] as const;
const filesKey = (id: string) => ['import-files', id] as const;
const metricsKey = (id: string) => ['import-metrics', id] as const;
const dryRunKey = (id: string) => ['import-dry-run', id] as const;

/** Open problems that hold the run, from its metrics: every code that is not a note. */
export function openProblems(metrics: RunMetrics | undefined): number {
  return (metrics?.issues ?? []).filter((i) => i.severity !== 'info').reduce((n, i) => n + (i.open ?? 0), 0);
}

export function ImportRunScreen({ runId }: { runId: string }) {
  const { me } = useStaff();
  const allowed = mayOpen(me, '/staff/setup/imports/runs/$runId');
  const queryClient = useQueryClient();
  const run = useQuery({
    queryKey: runKey(runId),
    queryFn: () => importRun(runId),
    enabled: allowed,
    // While it is being brought in, the page follows it by itself.
    refetchInterval: (q) => (q.state.data?.status === 'committing' ? 5000 : false),
  });
  const status = run.data?.status;
  const files = useQuery({ queryKey: filesKey(runId), queryFn: () => importFiles(runId), enabled: allowed });
  const metrics = useQuery({ queryKey: metricsKey(runId), queryFn: () => runMetrics(runId), enabled: allowed });
  const dryRun = useQuery({
    queryKey: dryRunKey(runId),
    queryFn: () => dryRunOf(runId),
    enabled: allowed && (status === 'in_review' || status === 'ready'),
  });
  const certificate = useQuery({ queryKey: ['import-certificate', runId], queryFn: () => certificateOf(runId), enabled: allowed && status === 'purged' });
  const [adding, setAdding] = useState(false);

  const refresh = async () => {
    await Promise.all(
      [runKey(runId), filesKey(runId), metricsKey(runId), dryRunKey(runId), RUNS_KEY].map((queryKey) => queryClient.invalidateQueries({ queryKey })),
    );
  };
  const check = useMutation({
    meta: { success: (r: { blocking?: number; warning?: number }) => `Files checked: ${(r.blocking ?? 0) + (r.warning ?? 0)} problems to look at` },
    mutationFn: () => checkFiles(runId),
    onSuccess: refresh,
  });
  const trial = useMutation({
    meta: { success: 'Trial run finished, nothing was written' },
    mutationFn: () => tryImport(runId),
    onSuccess: refresh,
  });
  const ready = useMutation({ meta: { success: 'Import marked ready for approval' }, mutationFn: () => markReady(runId), onSuccess: refresh });
  // toast-exempt: waits for approval: the request goes to Approvals, and the screen says a second person approves it
  const approval = useMutation({ mutationFn: () => askApproval(runId), onSuccess: refresh });

  if (!allowed) {
    return (
      <main className="stack">
        <h1>Import</h1>
        <NoAccess what="this import" />
      </main>
    );
  }
  if (run.isError) {
    return (
      <main className="stack">
        <h1>Import</h1>
        <p role="alert" className="alert alert-danger">
          Could not load this import.
        </p>
      </main>
    );
  }
  const r = run.data;
  if (!r) {
    return (
      <main className="stack">
        <h1>Import</h1>
        <Skeleton loading rows={4} />
      </main>
    );
  }
  const fileList = (files.data ?? []).filter((f) => f.status !== 'cancelled');
  const open = openProblems(metrics.data);
  const next = nextStep(r, fileList.length, open, dryRun.data ?? null);
  const editable = r.status === 'draft' || r.status === 'uploaded' || r.status === 'in_review';
  const busy = check.isPending || trial.isPending || ready.isPending || approval.isPending;
  const failed = [check, trial, ready, approval].find((m) => m.isError)?.error;

  return (
    <main className="stack">
      <h1>{stateWords(r.status)}</h1>
      <Hint tour="import-run-intro">Each step must be finished before the next. Nothing enters your books until a second person approves.</Hint>
      <StageProgress status={r.status} />
      {approval.isSuccess && r.status === 'ready' && !r.commit_approved && (
        <p role="status" className="alert alert-info">
          Sent to Approvals. A second person who may approve imports approves it there.
        </p>
      )}
      {failed instanceof Error && (
        <p role="alert" className="alert alert-danger">
          {failed.message}
        </p>
      )}
      <FilesCard runId={runId} files={fileList} editable={editable} onAdd={() => setAdding(true)} onChanged={refresh} />
      {(r.status === 'in_review' || r.status === 'ready') && <ProblemsCard runId={runId} open={open} metrics={metrics.data} />}
      {(r.status === 'in_review' || r.status === 'ready') && <PreviewCard runId={runId} currency={me.tenant_currency ?? 'UGX'} />}
      {dryRun.data && <DryRunCard dryRun={dryRun.data} currency={me.tenant_currency ?? 'UGX'} />}
      {(r.status === 'committed' || r.status === 'signed_off' || r.status === 'purged') && (
        <SignOffProgress run={r} metrics={metrics.data} canSign={(me.permissions ?? []).includes('core.imports.sign_off')} />
      )}
      {certificate.data && <CertificateCard certificate={certificate.data} />}
      <Sheet open={adding} onClose={() => setAdding(false)} title="Add a file">
        {adding && (
          <UploadForm
            runId={runId}
            onDone={async () => {
              setAdding(false);
              await refresh();
            }}
          />
        )}
      </Sheet>
      <NextAction
        next={next}
        runId={runId}
        busy={busy}
        certificate={certificate.data ?? null}
        onUpload={() => setAdding(true)}
        onCheck={() => check.mutate()}
        onTry={() => trial.mutate()}
        onReady={() => ready.mutate()}
        onApproval={() => approval.mutate()}
      />
    </main>
  );
}

/** The one main action of the screen: the next step, or a plain sentence while it waits on someone else. */
function NextAction({ next, runId, busy, certificate, onUpload, onCheck, onTry, onReady, onApproval }: {
  next: NextStep;
  runId: string;
  busy: boolean;
  certificate: Certificate | null;
  onUpload: () => void;
  onCheck: () => void;
  onTry: () => void;
  onReady: () => void;
  onApproval: () => void;
}) {
  if (next.kind === 'wait') {
    return (
      <p role="status" className="alert alert-info" data-tour="import-run-next">
        {next.words}
      </p>
    );
  }
  const button = (label: string, onClick: () => void) => (
    <ActionBar>
      <BusyButton type="button" className="btn-primary btn-block" busy={busy} onClick={onClick} data-tour="import-run-next">
        {label}
      </BusyButton>
    </ActionBar>
  );
  switch (next.kind) {
    case 'upload':
      return button('Add a file', onUpload);
    case 'check':
      return button('Check the files', onCheck);
    case 'fix':
      return (
        <ActionBar>
          <Link className="btn btn-primary btn-block" to="/staff/setup/imports/runs/$runId/problems" params={{ runId }} data-tour="import-run-next">
            Fix {next.open} {next.open === 1 ? 'problem' : 'problems'}
          </Link>
        </ActionBar>
      );
    case 'try':
      return button('Try the import', onTry);
    case 'ready':
      return button('Mark it ready', onReady);
    case 'approval':
      return button('Ask for approval', onApproval);
    case 'sign':
      return (
        <ActionBar>
          <Link className="btn btn-primary btn-block" to="/staff/setup/imports" search={{ run: runId }} data-tour="import-run-next">
            Compare and sign
          </Link>
        </ActionBar>
      );
    case 'certificate':
      // Offered once the certificate has loaded, so the button never does nothing.
      if (!certificate) {
        return (
          <p role="status" className="loading" data-tour="import-run-next">
            Loading the certificate
          </p>
        );
      }
      return button('Download the certificate', () =>
        saveFile(new Blob([certificate.document ?? ''], { type: 'text/plain' }), `certificate-of-deletion-${certificate.version ?? 1}.txt`),
      );
  }
}

function FilesCard({ runId, files, editable, onAdd, onChanged }: {
  runId: string;
  files: ImportFile[];
  editable: boolean;
  onAdd: () => void;
  onChanged: () => Promise<void>;
}) {
  const remove = useMutation({
    meta: { success: (f: ImportFile) => `File ${f.file_name ?? ''} taken out` },
    mutationFn: (batchId: string) => removeFile(batchId),
    onSuccess: onChanged,
  });
  return (
    <section className="rt-card stack" aria-labelledby={`import-files-${runId}`} data-tour="import-run-files">
      <h2 id={`import-files-${runId}`}>Your files</h2>
      {files.length === 0 && <p>No file yet. Add each sheet or tab you keep your records in.</p>}
      <ul className="stack">
        {files.map((f) => (
          <li key={f.id}>
            <p>
              <strong>{f.file_name}</strong>
            </p>
            <p>{fileLine(f)}</p>
            {editable && f.status !== 'committed' && (
              <BusyButton type="button" className="btn btn-sm" busy={remove.isPending && remove.variables === f.id} onClick={() => remove.mutate(f.id ?? '')}>
                Take this file out
              </BusyButton>
            )}
          </li>
        ))}
      </ul>
      {remove.isError && (
        <p role="alert" className="alert alert-danger">
          {remove.error instanceof Error ? remove.error.message : 'Could not take the file out.'}
        </p>
      )}
      {editable && files.length > 0 && (
        <button type="button" /* busy-exempt: opens the sheet, sends nothing */ className="btn" onClick={onAdd}>
          Add another file
        </button>
      )}
    </section>
  );
}

/** What became of a file, in plain words. */
export function fileLine(f: ImportFile): string {
  if (f.status === 'failed') return `Could not be read: ${f.failure_reason ?? f.failure_code ?? 'unknown reason'}`;
  if (f.status === 'uploaded') return 'Uploaded, being read';
  const rows = quantity(f.row_count ?? 0);
  if (f.status === 'committed') return `${rows} lines, brought in`;
  return `${rows} lines read`;
}

/** One file or tab of one sheet layout, as the person picks it. */
export interface LayoutChoice {
  mappingId: string;
  source: string;
  label: string;
  hint: string;
}

/** Every file or tab of every sheet layout, each a choice, narrowed by the words typed. */
export function layoutChoices(mappings: MappingChoice[], term: string): LayoutChoice[] {
  const all = mappings.flatMap((m) =>
    (m.sources ?? []).map((s) => ({
      mappingId: m.id ?? '',
      source: s.key ?? '',
      label: `${m.name ?? ''}: ${s.file ?? s.key ?? ''}`,
      hint: `Version ${m.version ?? 1}, ${(s.entity_set ?? '').split('_').join(' ')}`,
    })),
  );
  const words = term.trim().toLowerCase();
  return words === '' ? all : all.filter((c) => `${c.label} ${c.hint}`.toLowerCase().includes(words));
}

function UploadForm({ runId, onDone }: { runId: string; onDone: () => Promise<void> }) {
  const mappings = useQuery({ queryKey: ['import-mappings'], queryFn: mappingChoices });
  const [term, setTerm] = useState('');
  const [chosen, setChosen] = useState<LayoutChoice | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [tried, setTried] = useState(false);
  const upload = useMutation({
    meta: { success: (f: ImportFile) => `File ${f.file_name ?? ''} uploaded` },
    mutationFn: () => uploadFile(runId, file as File, chosen?.mappingId ?? '', chosen?.source ?? ''),
    onSuccess: onDone,
  });
  const choices = layoutChoices(mappings.data ?? [], term);

  function submit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (chosen && file) upload.mutate();
  }

  return (
    <form className="stack" onSubmit={submit} noValidate>
      <FieldsNeededNote />
      <Field
        label="Sheet layout"
        required
        type="search"
        value={term}
        onChange={(e) => setTerm(e.target.value)}
        hint="Type part of the name to find it"
        error={chosen ? null : 'Pick the layout of this file.'}
        showError={tried}
      />
      {mappings.isError && (
        <p role="alert" className="alert alert-danger">
          Could not load the sheet layouts.
        </p>
      )}
      {mappings.data && mappings.data.length === 0 && <p>No sheet layout yet. Rincol Tech makes one from the columns of your sheet first.</p>}
      <fieldset className="stack import-choices" aria-label="Sheet layouts">
        {choices.map((c) => (
          <label key={`${c.mappingId}-${c.source}`} className="import-choice">
            <input
              type="radio"
              name="import-layout"
              checked={chosen?.mappingId === c.mappingId && chosen.source === c.source}
              onChange={() => setChosen(c)}
            />
            <span>
              {c.label}
              <br />
              <small>{c.hint}</small>
            </span>
          </label>
        ))}
        {mappings.data && mappings.data.length > 0 && choices.length === 0 && <p>No layout matches &quot;{term}&quot;.</p>}
      </fieldset>
      <Field
        label="File"
        required
        type="file"
        accept=".csv,.xlsx,.jsonl"
        onChange={(e) => setFile(e.target.files?.[0] ?? null)}
        hint="CSV, Excel or JSON Lines, up to 25 MB"
        error={file ? null : 'Pick the file.'}
        showError={tried}
      />
      {upload.isError && (
        <p role="alert" className="alert alert-danger">
          {upload.error instanceof Error ? upload.error.message : 'Could not upload the file.'}
        </p>
      )}
      <BusyButton type="submit" className="btn-primary btn-block" busy={upload.isPending}>
        Upload the file
      </BusyButton>
    </form>
  );
}

function ProblemsCard({ runId, open, metrics }: { runId: string; open: number; metrics: RunMetrics | undefined }) {
  const codes = (metrics?.issues ?? []).filter((i) => (i.open ?? 0) > 0 && i.severity !== 'info');
  return (
    <section className="rt-card stack" aria-labelledby="import-problems-title" data-tour="import-run-problems">
      <h2 id="import-problems-title">What the check found</h2>
      {open === 0 ? (
        <p>No problem is left open.</p>
      ) : (
        <p>
          {open} {open === 1 ? 'problem is' : 'problems are'} left in {codes.length} {codes.length === 1 ? 'kind' : 'kinds'}.{' '}
          <Link to="/staff/setup/imports/runs/$runId/problems" params={{ runId }}>
            See them by kind
          </Link>
        </p>
      )}
    </section>
  );
}

function PreviewCard({ runId, currency }: { runId: string; currency: string }) {
  const [shown, setShown] = useState(false);
  const preview = useQuery({ queryKey: ['import-preview', runId], queryFn: () => previewOf(runId), enabled: shown });
  return (
    <section className="rt-card stack" aria-labelledby="import-preview-title" data-tour="import-run-preview">
      <h2 id="import-preview-title">What will come in</h2>
      {!shown && (
        <button type="button" /* busy-exempt: shows a read, writes nothing */ className="btn" onClick={() => setShown(true)}>
          Show what will come in
        </button>
      )}
      {preview.isError && (
        <p role="alert" className="alert alert-danger">
          Could not show what will come in.
        </p>
      )}
      {shown && <Skeleton loading={preview.isPending} rows={3} />}
      {preview.data && (
        <>
          <dl className="import-figures">
            {(preview.data.records ?? []).map((c) => (
              <Figure key={c.type} label={(c.type ?? '').split('_').join(' ').replace(/^./, (l) => l.toUpperCase())} value={quantity(c.count)} />
            ))}
          </dl>
          <BalancesList balances={preview.data.balances} currency={currency} />
        </>
      )}
    </section>
  );
}

function DryRunCard({ dryRun, currency }: { dryRun: DryRun; currency: string }) {
  return (
    <section className="rt-card stack" aria-labelledby="import-dry-run-title" data-tour="import-run-dry-run">
      <h2 id="import-dry-run-title">Trial run</h2>
      <p>{dryRunLine(dryRun)}</p>
      <dl className="import-figures">
        {(dryRun.sources ?? []).map((s) => (
          <Figure key={s.name} label={s.name ?? ''} value={`${quantity(s.written)} in, ${quantity(s.existing)} already there, ${quantity(s.skipped)} left out`} />
        ))}
      </dl>
      {(dryRun.anomalies ?? []).length > 0 && (
        <details className="recon-items">
          <summary>Notes from the trial ({(dryRun.anomalies ?? []).length})</summary>
          <ul className="stack">
            {(dryRun.anomalies ?? []).map((a, n) => (
              <li key={`${a.file}-${a.line}-${n}`}>
                {a.file}, line {a.line}: {a.message}
              </li>
            ))}
          </ul>
        </details>
      )}
      {dryRun.balances && <BalancesList balances={dryRun.balances} currency={currency} />}
    </section>
  );
}

/** The stored trial run in one sentence: when, whether it still counts and whether it matched what was shown. */
export function dryRunLine(d: DryRun): string {
  const when = d.at ? `Run on ${showDate(d.at.slice(0, 10))}.` : 'Run.';
  if (d.failure) return `${when} It stopped: ${d.failure}. Fix it and try again.`;
  if (!d.current) return `${when} Something changed since, so try it again.`;
  if (!d.preview_matches) return `${when} Its figures differ from what will come in. Ask Rincol Tech to look.`;
  return `${when} It matched what will come in. Nothing was written.`;
}

/** Stock, money owed and opening entries per branch, money through the one formatter. */
function BalancesList({ balances, currency }: { balances: Balances | undefined; currency: string }) {
  if (!balances) return null;
  return (
    <dl className="import-figures">
      {(balances.stock ?? []).map((s) => (
        <Figure key={`stock-${s.branch}`} label={`Stock at ${s.branch}`} value={`${quantity(s.qty)}, ${formatMinor(s.value_minor ?? 0, currency)}`} />
      ))}
      {(balances.receivables ?? []).map((a) => (
        <Figure key={`owed-${a.branch}`} label={`Owed by customers at ${a.branch}`} value={formatMinor(a.amount_minor ?? 0, currency)} />
      ))}
      {(balances.loan_principal ?? []).map((a) => (
        <Figure key={`loans-${a.branch}`} label={`Loans at ${a.branch}`} value={formatMinor(a.amount_minor ?? 0, currency)} />
      ))}
      {(balances.journals ?? []).map((j) => (
        <Figure
          key={`journal-${j.branch}-${j.kind}`}
          label={`Opening ${(j.kind ?? '').split('_').join(' ')} at ${j.branch}`}
          value={`Debit ${formatMinor(j.debit_minor ?? 0, currency)}, credit ${formatMinor(j.credit_minor ?? 0, currency)}`}
        />
      ))}
    </dl>
  );
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </>
  );
}

/** The sign-off's steps after the data is in: compared, signed, copies deleted. */
export function signOffSteps(run: Pick<ImportRun, 'status' | 'committed_at'>, metrics: RunMetrics | undefined): { label: string; done: string | null }[] {
  const stamps = metrics?.stamps;
  const day = (at: string | null | undefined) => (at ? showDate(at.slice(0, 10)) : null);
  return [
    { label: 'Brought in', done: day(run.committed_at ?? stamps?.committed_at) },
    { label: 'Figures compared', done: day(stamps?.reconciled_at) },
    { label: 'Signed by the owner', done: day(stamps?.signed_off_at) ?? (run.status === 'signed_off' || run.status === 'purged' ? 'Done' : null) },
    { label: 'Copies deleted', done: day(stamps?.purged_at) ?? (run.status === 'purged' ? 'Done' : null) },
  ];
}

function SignOffProgress({ run, metrics, canSign }: { run: ImportRun; metrics: RunMetrics | undefined; canSign: boolean }) {
  return (
    <section className="rt-card stack" aria-labelledby="import-sign-title" data-tour="import-run-sign">
      <h2 id="import-sign-title">Sign off</h2>
      <dl className="import-figures">
        {signOffSteps(run, metrics).map((s) => (
          <Figure key={s.label} label={s.label} value={s.done ?? 'Not yet'} />
        ))}
      </dl>
      {run.status === 'committed' && !canSign && <p>The owner of the business compares the figures and signs. They see it under Imports to sign off.</p>}
    </section>
  );
}

function CertificateCard({ certificate }: { certificate: Certificate }) {
  return (
    <section className="rt-card stack" aria-labelledby="import-certificate-title" data-tour="import-run-certificate">
      <h2 id="import-certificate-title">Certificate of deletion</h2>
      <p>
        Issued on {showDate((certificate.issued_at ?? '').slice(0, 10))}.{' '}
        {certificate.dump_confirmed ? 'Every copy is confirmed deleted.' : 'The last backup copy is not yet confirmed deleted.'}
      </p>
      <pre className="import-certificate">{certificate.document}</pre>
    </section>
  );
}

const route = getRouteApi('/staff/setup/imports/runs/$runId');

function ImportRunRoutePage() {
  const { runId } = route.useParams();
  return <ImportRunScreen key={runId} runId={runId} />;
}

export const Route = createLazyRoute('/staff/setup/imports/runs/$runId')({ component: ImportRunRoutePage });
