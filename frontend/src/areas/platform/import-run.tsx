import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import {
  ImportError,
  askTradingOverride,
  checkDump,
  platformAction,
  platformRun,
  recordDump,
  type ImportRun,
  type PlatformRunState,
  type RunAction,
} from '../../api/imports';
import { BusyButton } from '../../app/ui/BusyButton';
import { Field, FieldsNeededNote } from '../../app/ui/Field';
import { Breadcrumbs } from '../../components/breadcrumbs';
import { platformMe } from '../../api/onboarding';
import { mayAct, mayOpen, stateWords } from '../staff/imports/pipeline';
import { StageProgress } from '../staff/imports/stage-progress';
import { ConfirmPanel, TourButton } from './operator-parts';

// One import run of a business in the operator portal (issue #141, spec sections 3.7, 3.11 and 11): where it stands, the stop
// button while it is being brought in and the resume after it, the start once a second person approved it and the dump is
// checked, the trading override (asked by one operator, approved by another) and the record of the pre-import dump and its
// check. The commit approval itself is given in the business's Approvals by a person who may approve imports; this page says
// whether it is given.

const runKey = (slug: string, runId: string) => ['platform-import-run', slug, runId] as const;

/** The one main action an operator has on the run now, or null. */
export function operatorAction(run: ImportRun, commitEnabled: boolean): { action: RunAction; label: string; danger: boolean; sentence: string } | null {
  if (run.status === 'committing' && !run.halt_requested) {
    return { action: 'stop', label: 'Stop the import', danger: true, sentence: 'Stop this import before its next file, if it is still running? What is already in stays in; it can be resumed.' };
  }
  if (run.status === 'committing' && run.halt_requested) {
    return commitEnabled
      ? { action: 'resume', label: 'Resume the import', danger: false, sentence: 'Resume from the first file not yet brought in?' }
      : null;
  }
  if (run.status === 'ready' && run.commit_approved && run.pre_import_dump_verified && commitEnabled) {
    return { action: 'start', label: 'Start the import', danger: false, sentence: 'Bring this business’s data in now, file by file?' };
  }
  return null;
}

/** How long a run may be committing before it may have stopped by itself: one file's time limit (10 minutes, issue #897). */
export const STALE_AFTER_MS = 10 * 60 * 1000;

/**
 * Whether to offer Resume beside Stop: a run being brought in whose stop was not pressed may have stopped by itself (a failed
 * file, a restart), with no loop alive. A healthy run is not offered it (issue #897): only once it has been committing longer
 * than STALE_AFTER_MS, or once the server answered an action on it with the 409 IMPORT_IN_PROGRESS (`refused`), so the
 * operator can try Resume. A run with no committing_at is not stale. The server still answers IMPORT_IN_PROGRESS while a
 * loop lives, so offering it is safe.
 */
export function stoppedBySelf(run: ImportRun, commitEnabled: boolean, now: number, refused = false): boolean {
  if (run.status !== 'committing' || run.halt_requested || !commitEnabled) return false;
  const since = run.committing_at ? Date.parse(run.committing_at) : Number.NaN;
  return refused || (!Number.isNaN(since) && now - since > STALE_AFTER_MS);
}

/** The facts an operator checks before the start, each in plain words. */
export function runFacts(state: PlatformRunState): { label: string; value: string }[] {
  const r = state.run ?? {};
  return [
    { label: 'State', value: stateWords(r.status) },
    { label: 'Approval', value: r.commit_approved ? 'Approved by a second person' : 'Not approved yet; it is given in the business’s Approvals' },
    {
      label: 'Backup before the import',
      value: r.pre_import_dump_file ? `${r.pre_import_dump_file}, ${r.pre_import_dump_verified ? 'checked' : 'not checked yet'}` : 'None recorded',
    },
    {
      label: 'Business already trading',
      value: r.trading_override_approved ? 'Override approved' : r.trading_override_set ? 'Override asked, waiting for a second operator' : 'No override',
    },
    { label: 'Imports on the platform', value: state.commit_enabled ? 'Allowed' : 'Stopped for every business' },
    { label: 'Stop pressed', value: r.halt_requested ? 'Yes' : 'No' },
  ];
}

export function OperatorImportRun({ slug, runId }: { slug: string; runId: string }) {
  const queryClient = useQueryClient();
  const me = useQuery({ queryKey: ['platform-me-imports'], queryFn: platformMe });
  const allowed = me.data ? mayOpen(me.data, '/platform/imports/$slug/$runId') : false;
  const canAct = mayAct(me.data);
  const state = useQuery({
    queryKey: runKey(slug, runId),
    queryFn: () => platformRun(slug, runId),
    enabled: allowed,
    refetchInterval: (q) => (q.state.data?.run?.status === 'committing' ? 5000 : false),
  });
  const [confirming, setConfirming] = useState(false);
  const [done, setDone] = useState<string | null>(null);
  const settle = (next: PlatformRunState, words: string) => {
    queryClient.setQueryData(runKey(slug, runId), next);
    void queryClient.invalidateQueries({ queryKey: ['platform-import-runs'] });
    setDone(words);
  };
  // toast-exempt: success note: the line under the heading says what was done (Stop pressed, Started, Resumed)
  const act = useMutation({
    mutationFn: (action: RunAction) => platformAction(slug, runId, action),
    onSuccess: (next, action) => {
      setConfirming(false);
      settle(next, ACTION_DONE[action]);
    },
  });

  const s = state.data;
  const r = s?.run;
  // Only an operator who may change businesses is offered an action; one who may only read sees the facts.
  const main = r && s && canAct ? operatorAction(r, s.commit_enabled ?? false) : null;
  const head = (
    <>
      <Breadcrumbs items={[{ label: 'Operator portal', to: '/platform' }, { label: 'Imports', to: '/platform/imports' }, { label: slug }]} />
      <div className="page-header">
        <h1>Import for {slug}</h1>
        <TourButton />
      </div>
    </>
  );
  if (!me.data || !allowed) {
    return (
      <main className="stack">
        {head}
        {me.isPending ? (
          <p role="status" className="loading">
            Loading
          </p>
        ) : (
          <p role="alert" className="alert alert-danger">
            {me.isError ? 'Could not load your operator account.' : 'Your operator account cannot see the imports.'}
          </p>
        )}
      </main>
    );
  }
  return (
    <main className="stack">
      {head}
      {state.isPending && (
        <p role="status" className="loading">
          Loading
        </p>
      )}
      {state.isError && (
        <p role="alert" className="alert alert-danger">
          Could not load this import.
        </p>
      )}
      {done && <p role="status">{done}</p>}
      {s && r && (
        <>
          <StageProgress status={r.status} />
          <section className="card stack" aria-labelledby="run-facts" data-tour="import-run-facts">
            <h2 id="run-facts">Before it starts</h2>
            <dl className="import-figures">
              {runFacts(s).map((f) => (
                <Fact key={f.label} label={f.label} value={f.value} />
              ))}
            </dl>
          </section>
          {main && !confirming && (
            <p>
              <button
                type="button"
                /* busy-exempt: opens the confirm, sends nothing */
                className={main.danger ? 'btn-danger' : 'btn-primary'}
                onClick={() => setConfirming(true)}
                data-tour="import-run-action"
              >
                {main.label}
              </button>
            </p>
          )}
          {main && confirming && (
            <ConfirmPanel
              id="import-action"
              sentence={main.sentence}
              confirm={`Yes, ${main.label.toLowerCase()}`}
              needsReason={false}
              danger={main.danger}
              busy={act.isPending}
              error={act.error instanceof Error ? act.error.message : null}
              onConfirm={() => act.mutate(main.action)}
              onCancel={() => setConfirming(false)}
            />
          )}
          {/* JUSTIFICATION-A3: the review of e3f95b2 asks Resume for a run that stopped by itself; one card beside Stop. */}
          {canAct && stoppedBySelf(r, s.commit_enabled ?? false, Date.now(), act.error instanceof ImportError && act.error.code === 'IMPORT_IN_PROGRESS') && !confirming && (
            <section className="card stack" aria-labelledby="resume-title">
              <h2 id="resume-title">Stopped by itself?</h2>
              <p>
                After a failed file or a restart the import waits here with nothing running. Resume goes on from the first file
                not yet brought in; while it is still running, nothing changes and you are told so.
              </p>
              <p>
                <BusyButton type="button" className="btn" busy={act.isPending && act.variables === 'resume'} onClick={() => act.mutate('resume')}>
                  Resume the import
                </BusyButton>
              </p>
              {act.isError && act.variables === 'resume' && (
                <p role="alert" className="alert alert-danger">
                  {act.error instanceof Error ? act.error.message : 'Could not resume it.'}
                </p>
              )}
            </section>
          )}
          {canAct && (r.status === 'draft' || r.status === 'uploaded' || r.status === 'in_review' || r.status === 'ready') && (
            <>
              <OverrideCard slug={slug} runId={runId} run={r} onDone={settle} onApprove={() => act.mutate('approve-override')} approving={act.isPending} />
              <DumpCard slug={slug} runId={runId} run={r} onDone={settle} />
            </>
          )}
        </>
      )}
    </main>
  );
}

const ACTION_DONE: Record<RunAction, string> = {
  stop: 'Stop pressed. The import stops before its next file.',
  start: 'Started. The import runs file by file; this page follows it.',
  resume: 'Resumed. It goes on from the first file not yet brought in.',
  'approve-override': 'Override approved.',
};

function OverrideCard({ slug, runId, run, onDone, onApprove, approving }: {
  slug: string;
  runId: string;
  run: ImportRun;
  onDone: (next: PlatformRunState, words: string) => void;
  onApprove: () => void;
  approving: boolean;
}) {
  const [open, setOpen] = useState(false);
  // toast-exempt: success note: the line under the heading says the override was asked
  const ask = useMutation({
    mutationFn: (reason: string) => askTradingOverride(slug, runId, reason),
    onSuccess: (next) => {
      setOpen(false);
      onDone(next, 'Override asked. A second operator approves it.');
    },
  });
  return (
    <section className="card stack" aria-labelledby="override-title" data-tour="import-run-override">
      <h2 id="override-title">Business already trading</h2>
      <p>An import into a business that already has sales or loans needs an override, asked by one operator and approved by another.</p>
      {run.trading_override_set && !run.trading_override_approved && (
        <p>
          <BusyButton type="button" className="btn" busy={approving} onClick={onApprove}>
            Approve the override
          </BusyButton>
        </p>
      )}
      {!run.trading_override_set && !open && (
        <p>
          <button type="button" /* busy-exempt: opens the confirm, sends nothing */ className="btn" onClick={() => setOpen(true)}>
            Ask for the override
          </button>
        </p>
      )}
      {open && (
        <ConfirmPanel
          id="override"
          sentence="Allow this import into a business that already trades?"
          confirm="Yes, ask for it"
          busy={ask.isPending}
          error={ask.error instanceof Error ? ask.error.message : null}
          onConfirm={(reason) => ask.mutate(reason)}
          onCancel={() => setOpen(false)}
        />
      )}
    </section>
  );
}

const SHA256 = /^[0-9a-f]{64}$/;

function DumpCard({ slug, runId, run, onDone }: { slug: string; runId: string; run: ImportRun; onDone: (next: PlatformRunState, words: string) => void }) {
  const [file, setFile] = useState('');
  const [takenAt, setTakenAt] = useState('');
  const [sha, setSha] = useState('');
  const [entries, setEntries] = useState('');
  const [tried, setTried] = useState(false);
  const checking = Boolean(run.pre_import_dump_file) && !run.pre_import_dump_verified;
  // toast-exempt: success note: the line under the heading says the dump or its check was recorded
  const save = useMutation({
    mutationFn: () =>
      checking
        ? checkDump(slug, runId, sha.trim(), Number(entries))
        : recordDump(slug, runId, file.trim(), new Date(takenAt).toISOString(), sha.trim()),
    onSuccess: (next) => {
      setTried(false);
      setSha('');
      onDone(next, checking ? 'Backup check recorded.' : 'Backup recorded. Count its entries with the restore list next.');
    },
  });
  const shaProblem = SHA256.test(sha.trim()) ? null : 'Paste the 64 letters and digits of the fingerprint, in lower case.';
  const fileProblem = checking || file.trim() !== '' ? null : 'Type the file name of the backup.';
  const timeProblem = checking || takenAt !== '' ? null : 'Pick when the backup was taken.';
  const entriesProblem = !checking || Number(entries) > 0 ? null : 'Type how many entries the restore list counted.';

  function submit(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (!shaProblem && !fileProblem && !timeProblem && !entriesProblem) save.mutate();
  }

  if (run.pre_import_dump_verified) {
    return (
      <section className="card stack" aria-labelledby="dump-title" data-tour="import-run-dump">
        <h2 id="dump-title">Backup before the import</h2>
        <p>{run.pre_import_dump_file} is recorded and checked. Recording another backup needs a new check and a new approval.</p>
      </section>
    );
  }
  return (
    <section className="card stack" aria-labelledby="dump-title" data-tour="import-run-dump">
      <h2 id="dump-title">{checking ? 'Check the backup before the import' : 'Record the backup before the import'}</h2>
      <form className="form-stack" onSubmit={submit} noValidate>
        <FieldsNeededNote />
        {!checking && (
          <>
            <Field label="File name" required value={file} onChange={(e) => setFile(e.target.value)} error={fileProblem} showError={tried} />
            <Field label="Taken at" required type="datetime-local" value={takenAt} onChange={(e) => setTakenAt(e.target.value)} error={timeProblem} showError={tried} />
          </>
        )}
        <Field label="Fingerprint (SHA256)" required value={sha} onChange={(e) => setSha(e.target.value)} error={shaProblem} showError={tried} autoComplete="off" />
        {checking && (
          <Field
            label="Entries listed"
            required
            inputMode="numeric"
            value={entries}
            onChange={(e) => setEntries(e.target.value.replace(/\D/g, ''))}
            error={entriesProblem}
            showError={tried}
          />
        )}
        {save.isError && (
          <p role="alert" className="alert alert-danger">
            {save.error instanceof Error ? save.error.message : 'Could not record it.'}
          </p>
        )}
        <BusyButton type="submit" className="btn-primary" busy={save.isPending}>
          {checking ? 'Record the check' : 'Record the backup'}
        </BusyButton>
      </form>
    </section>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </>
  );
}

const route = getRouteApi('/platform/imports/$slug/$runId');

function OperatorImportRunPage() {
  const { slug, runId } = route.useParams();
  return <OperatorImportRun key={`${slug}-${runId}`} slug={slug} runId={runId} />;
}

export const Route = createLazyRoute('/platform/imports/$slug/$runId')({ component: OperatorImportRunPage });
