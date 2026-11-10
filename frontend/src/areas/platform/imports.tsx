import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { killSwitch, platformRuns, setKillSwitch, type PlatformRun } from '../../api/imports';
import { platformMe } from '../../api/onboarding';
import { Breadcrumbs } from '../../components/breadcrumbs';
import { currentStage, mayAct, mayOpen, stateWords, STEPS } from '../staff/imports/pipeline';
import { ConfirmPanel, TourButton } from './operator-parts';
import { formatMoment } from './operator-state';

// Imports (issue #141, spec section 11, ADR-024 decision 8): every business's import runs, grouped by their state in the order of
// the pipeline, each with the stage of spec section 2 it stands at. A run opens its own page with the stop, resume, start, trading
// override and dump record. The platform kill switch stops every import before its next file. Each business is named by its
// slug only, as the platform report gives it.

/** The order the states are shown in: what needs an operator first, then the rest of the pipeline. */
export const STATE_ORDER = ['committing', 'ready', 'in_review', 'uploaded', 'draft', 'committed', 'signed_off', 'purged', 'cancelled'];

/** The runs grouped by state, in STATE_ORDER, empty states left out. */
export function byState(runs: PlatformRun[]): { status: string; runs: PlatformRun[] }[] {
  return STATE_ORDER.map((status) => ({ status, runs: runs.filter((r) => r.metrics?.status === status) })).filter((g) => g.runs.length > 0);
}

export const QUEUE_KEY = ['platform-import-runs'] as const;

export function ImportQueue() {
  const me = useQuery({ queryKey: ['platform-me-imports'], queryFn: platformMe });
  const allowed = me.data ? mayOpen(me.data, '/platform/imports') : false;
  const runs = useQuery({ queryKey: QUEUE_KEY, queryFn: platformRuns, refetchInterval: 15000, enabled: allowed });
  const groups = byState(runs.data ?? []);
  const head = (
    <>
      <Breadcrumbs items={[{ label: 'Operator portal', to: '/platform' }, { label: 'Imports' }]} />
      <div className="cluster">
        <h1>Imports</h1>
        <TourButton />
      </div>
    </>
  );
  if (!me.data) {
    return (
      <main className="stack">
        {head}
        {me.isError ? (
          <p role="alert" className="alert alert-danger">
            Could not load your operator account.
          </p>
        ) : (
          <p role="status" className="loading">
            Loading
          </p>
        )}
      </main>
    );
  }
  if (!allowed) {
    return (
      <main className="stack">
        {head}
        <p role="alert" className="alert alert-danger">
          Your operator account cannot see the imports.
        </p>
      </main>
    );
  }
  return (
    <main className="stack">
      {head}
      <KillSwitchCard canAct={mayAct(me.data)} />
      {runs.isError && (
        <p role="alert" className="alert alert-danger">
          Could not load the imports.
        </p>
      )}
      {runs.isPending && (
        <p role="status" className="loading">
          Loading
        </p>
      )}
      {runs.data && groups.length === 0 && <p>No business has an import yet.</p>}
      <div className="stack" data-tour="imports-queue">
        {groups.map((g) => (
          <section key={g.status} className="card stack" aria-labelledby={`queue-${g.status}`}>
            <h2 id={`queue-${g.status}`}>
              {stateWords(g.status)} ({g.runs.length})
            </h2>
            <ul className="stack">
              {g.runs.map((r) => (
                <QueueRow key={r.metrics?.run_id} run={r} />
              ))}
            </ul>
          </section>
        ))}
      </div>
    </main>
  );
}

function QueueRow({ run }: { run: PlatformRun }) {
  const stage = currentStage(run.metrics);
  const step = STEPS.findIndex((s) => (s.states as readonly string[]).includes(run.metrics?.status ?? ''));
  return (
    <li className="ln-item">
      <p className="ln-item-head">
        <Link to="/platform/imports/$slug/$runId" params={{ slug: run.tenant_slug ?? '', runId: run.metrics?.run_id ?? '' }} data-tour="imports-open">
          <strong>{run.tenant_slug}</strong>
        </Link>
        {run.metrics?.fabricated ? <span className="badge">Test data</span> : null}
      </p>
      <p>
        {stage ? `Stage ${stage.number} of 10: ${stage.words}` : 'Not started'}
        {step >= 0 ? `. Step ${step + 1} of ${STEPS.length} for the business.` : '.'}
      </p>
      <p>Started {formatMoment(run.metrics?.stamps?.created_at)}</p>
    </li>
  );
}

function KillSwitchCard({ canAct }: { canAct: boolean }) {
  const queryClient = useQueryClient();
  const enabled = useQuery({ queryKey: ['platform-import-kill-switch'], queryFn: killSwitch });
  const [open, setOpen] = useState(false);
  // toast-exempt: success note: the card's heading turns to say whether imports may run
  const change = useMutation({
    mutationFn: (reason: string) => setKillSwitch(!on, reason),
    onSuccess: (now) => {
      setOpen(false);
      queryClient.setQueryData(['platform-import-kill-switch'], now);
    },
  });
  // Never assumed: while the switch is not known the card says so and offers nothing to press.
  if (enabled.data === undefined) {
    return (
      <section className="card stack" aria-labelledby="kill-switch-title" data-tour="imports-kill-switch">
        <h2 id="kill-switch-title">Imports on the platform</h2>
        {enabled.isError ? (
          <p role="alert" className="alert alert-danger">
            Could not load whether imports may run.
          </p>
        ) : (
          <p role="status" className="loading">
            Loading
          </p>
        )}
      </section>
    );
  }
  const on = enabled.data;
  return (
    <section className="card stack" aria-labelledby="kill-switch-title" data-tour="imports-kill-switch">
      <h2 id="kill-switch-title">{on ? 'Imports may run' : 'Every import is stopped'}</h2>
      <p>
        {on
          ? 'Stopping every import halts each one before its next file. Runs being imported stay where they are and can be resumed later.'
          : 'No import brings data in until this is allowed again.'}
      </p>
      {!canAct && <p>Only an operator who may change businesses can switch this.</p>}
      {canAct && !open && (
        <p>
          <button type="button" /* busy-exempt: opens the confirm, sends nothing */ className={on ? 'btn-danger' : 'btn-primary'} onClick={() => setOpen(true)}>
            {on ? 'Stop every import' : 'Allow imports again'}
          </button>
        </p>
      )}
      {open && (
        <ConfirmPanel
          id="kill-switch"
          sentence={on ? 'Stop every import on the platform before its next file?' : 'Allow imports to run again?'}
          confirm={on ? 'Yes, stop every import' : 'Yes, allow them'}
          danger={on}
          busy={change.isPending}
          error={change.error instanceof Error ? change.error.message : null}
          onConfirm={(reason) => change.mutate(reason)}
          onCancel={() => setOpen(false)}
        />
      )}
    </section>
  );
}

export const Route = createLazyRoute('/platform/imports')({ component: ImportQueue });
