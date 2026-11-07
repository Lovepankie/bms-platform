import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, useRouterState } from '@tanstack/react-router';
import { type FormEvent, useState } from 'react';
import { businessToday } from '../../../api/retail';
import {
  downloadReconciliation,
  ImportError,
  reconcileAgain,
  reconciliationOf,
  signOff,
  signOffRuns,
  type Reconciliation,
  type ReconciliationSection,
} from '../../../api/imports';
import { ActionBar } from '../../../app/ui/ActionBar';
import { BusyButton } from '../../../app/ui/BusyButton';
import { EmptyState, NoAccess } from '../../../app/ui/EmptyState';
import { Field } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { ListRow, RowList } from '../../../app/ui/ListRow';
import { showDate } from '../../../app/ui/PeriodPicker';
import { Skeleton } from '../../../app/ui/Skeleton';
import { useStaff } from '../context';
import { figure, itemPlace, notTied, reasonWords, runState, runTitle, signOffProblem } from './state';

// Business set-up > Imports to sign off (issue #134, spec sections 3.9 and 3.10, FR-IMP-08). After Rincol Tech imports a
// business's own sheets, the tenant admin reads the reconciliation: per check what their sheet says, what BMS now holds and
// the difference, every difference explained by a row left out, a warning accepted or one of their decisions. They tick each
// section, type their name and today's date, and sign. A difference that is not explained cannot be signed. Only the holder of
// core.imports.sign_off reaches the page.

const RUNS = ['imports-sign-off'] as const;
const reconciliationKey = (run: string) => ['imports-reconciliation', run] as const;

/** The sign-off was refused because the figures changed since they were read. */
const changed = (error: unknown) => error instanceof ImportError && error.code === 'reconciliation_changed';

export function ImportsSignOff() {
  const { me } = useStaff();
  const allowed = (me.permissions ?? []).includes('core.imports.sign_off');
  const linked: unknown = useRouterState({ select: (s) => (s.location.search as Record<string, unknown>).run });
  const [chosen, setChosen] = useState<string | null>(typeof linked === 'string' ? linked : null);
  const runs = useQuery({ queryKey: RUNS, queryFn: signOffRuns, enabled: allowed });

  if (!allowed) {
    return (
      <main className="stack">
        <h1>Imports to sign off</h1>
        <NoAccess what="the imports to sign off" />
      </main>
    );
  }
  if (chosen) {
    return <ReconciliationScreen runId={chosen} onBack={() => setChosen(null)} />;
  }
  return (
    <main className="stack">
      <h1>Imports to sign off</h1>
      <Hint tour="imports-intro">Check what was imported against your own figures, then sign it for the business.</Hint>
      {runs.isError && <p role="alert">Could not load the imports to sign off.</p>}
      <Skeleton loading={runs.isPending} of="imports-sign-off" rows={2}>
        {runs.data && runs.data.length === 0 && <EmptyState text="No import is waiting for your sign-off." />}
        {runs.data && runs.data.length > 0 && (
          <div data-tour="imports-list">
            <RowList label="Imports">
              {runs.data.map((r) => (
                <ListRow key={r.run_id} title={runTitle(r)} meta={runState(r)} onClick={() => setChosen(r.run_id ?? null)} />
              ))}
            </RowList>
          </div>
        )}
      </Skeleton>
    </main>
  );
}

function ReconciliationScreen({ runId, onBack }: { runId: string; onBack: () => void }) {
  const { me } = useStaff();
  const currency = me.tenant_currency ?? 'UGX';
  const today = businessToday(new Date(), me.tenant_timezone ?? 'Africa/Kampala');
  const queryClient = useQueryClient();
  const reconciliation = useQuery({ queryKey: reconciliationKey(runId), queryFn: () => reconciliationOf(runId) });
  const [ticked, setTicked] = useState<Set<string>>(new Set());
  const [name, setName] = useState('');
  const [date, setDate] = useState('');
  const [tried, setTried] = useState(false);
  const [refused, setRefused] = useState<string | null>(null);

  // success-screen: the signed reconciliation replaces the form, with who signed and when
  const sign = useMutation({
    meta: { success: 'Import signed off' },
    mutationFn: (r: Reconciliation) =>
      signOff(runId, { reconciliation_id: r.id ?? '', sha256: r.sha256 ?? '', sections: [...ticked], name: name.trim(), date }),
    onSuccess: async (signed) => {
      queryClient.setQueryData(reconciliationKey(runId), signed);
      await queryClient.invalidateQueries({ queryKey: RUNS });
    },
    // The figures changed since they were read: the API stores what it measured as the next version, so show the newest,
    // to be checked and ticked again (Lead on the review of 391e1dc, point 5). The one refused is remembered, so a refetch
    // that brings it back is never called the newest (review of 1ea73e5).
    onError: (error, refusedOne) => {
      if (!changed(error)) return;
      setRefused(refusedOne.id ?? null);
      setTicked(new Set());
      // Not awaited: the refusal shows at once and hides the form while the newest figures are on their way.
      void queryClient.invalidateQueries({ queryKey: reconciliationKey(runId) });
    },
  });
  // busy-exempt: the download button says Downloading the report itself and is disabled meanwhile
  // toast-exempt: saves nothing: a download changes nothing, and the device shows the saved file
  const download = useMutation({ mutationFn: (r: Reconciliation) => downloadReconciliation(runId, r.version ?? 1) });

  // Made again from the source and the books, by whoever runs imports (core.imports.manage): when the commit could not make
  // it, or after a correction (review of #873). The new version replaces the one on screen; nothing is ticked any more.
  const canRemake = (me.permissions ?? []).includes('core.imports.manage');
  const remake = useMutation({
    meta: { success: 'Reconciliation made again' },
    mutationFn: () => reconcileAgain(runId),
    onSuccess: async (made) => {
      queryClient.setQueryData(reconciliationKey(runId), made);
      setTicked(new Set());
      sign.reset();
      await queryClient.invalidateQueries({ queryKey: RUNS });
    },
  });
  const remakeButton = canRemake && (
    <>
      <BusyButton type="button" className="btn" busy={remake.isPending} onClick={() => remake.mutate()} data-tour="imports-remake">
        Make the reconciliation again
      </BusyButton>
      {remake.isError && <p role="alert">{remake.error instanceof Error ? remake.error.message : 'Could not make the reconciliation.'}</p>}
    </>
  );

  const back = (
    <button type="button" /* busy-exempt: goes back to the list, sends nothing */ className="btn" onClick={onBack}>
      All imports to sign off
    </button>
  );
  if (reconciliation.isError) {
    return (
      <main className="stack">
        <h1>Sign off the import</h1>
        <p role="alert">This import has no reconciliation to show yet.</p>
        {remakeButton}
        {back}
      </main>
    );
  }
  const r = reconciliation.data;
  if (!r) {
    return (
      <main className="stack">
        <h1>Sign off the import</h1>
        <Skeleton loading rows={4} />
      </main>
    );
  }
  const problem = signOffProblem(r, ticked, name, date, today);
  const signed = r.signed;
  // Refused because the figures changed: the API's own words, which name the new version. Should the one on screen still be
  // the one refused, offer to make it again rather than sign it once more.
  const moved = !signed && changed(sign.error);
  // The refused version is never offered for signing again: no ticks and no form while it is on screen, the refetch done
  // or not (Lead on the review of 5b32682).
  const stale = moved && refused !== null && refused === r.id;
  const signing = !signed && r.signable === true && !stale;
  // The ways out wait for the refetch, so the normal path never flashes them (review of 4105c2a).
  const settled = stale && !reconciliation.isFetching;

  function onSign(e: FormEvent) {
    e.preventDefault();
    setTried(true);
    if (r && problem === null) sign.mutate(r);
  }

  function toggle(key: string, on: boolean) {
    const next = new Set(ticked);
    if (on) next.add(key);
    else next.delete(key);
    setTicked(next);
  }

  return (
    <main className="stack">
      <h1>Sign off the import</h1>
      <p data-tour="imports-about">
        Version {r.version}, as at {showDate(r.as_at)}. Each check compares your sheet with what BMS now holds.
      </p>
      {signed ? (
        <section className="rt-card stack" aria-labelledby="imports-signed" data-tour="imports-signed">
          <h2 id="imports-signed">Signed</h2>
          <p>
            Signed by {signed.signed_name} on {showDate(signed.signed_on)}. The signed report is kept as it is and cannot be changed.
          </p>
        </section>
      ) : (
        !r.signable && (
          <p role="alert" className="alert alert-danger">
            {notTied(r).length === 1 ? 'One difference is' : `${notTied(r).length} differences are`} not explained, so this cannot be
            signed yet. Ask Rincol Tech to find and correct it.
          </p>
        )
      )}
      {moved && sign.error instanceof Error && (
        <p role="alert" className="alert alert-danger">
          {sign.error.message}
        </p>
      )}
      {!signed && (stale ? settled : !r.signable) && remakeButton}
      {settled && !canRemake && (
        <BusyButton type="button" className="btn" busy={reconciliation.isFetching} onClick={() => void reconciliation.refetch()}>
          Show the new figures
        </BusyButton>
      )}
      {(r.sections ?? []).map((s) => (
        <SectionCard
          key={s.key}
          section={s}
          currency={currency}
          ticked={ticked.has(s.key ?? '')}
          canTick={signing}
          onTick={(on) => toggle(s.key ?? '', on)}
        />
      ))}
      <button
        type="button"
        className="btn"
        /* busy-exempt: a download, shown as busy by its own text */
        disabled={download.isPending}
        onClick={() => download.mutate(r)}
        data-tour="imports-download"
      >
        {download.isPending ? 'Downloading the report' : 'Download the report'}
      </button>
      {download.isError && <p role="alert">{download.error instanceof Error ? download.error.message : 'Could not download the report.'}</p>}
      {signing && (
        <form className="stack" onSubmit={onSign} noValidate data-tour="imports-sign">
          <h2>Sign for the business</h2>
          <Field
            label="Your name"
            required
            autoComplete="name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            error={name.trim() === '' ? 'Type your name.' : null}
            showError={tried}
          />
          <Field
            label="Today's date"
            required
            type="date"
            value={date}
            onChange={(e) => setDate(e.target.value)}
            error={date !== today ? `Type today's date, ${showDate(today)}.` : null}
            showError={tried}
          />
          {tried && problem && <p role="alert" className="field-error">{problem}</p>}
          {sign.isError && !moved && (
            <p role="alert" className="alert alert-danger">
              {sign.error instanceof Error ? sign.error.message : 'Could not sign. Nothing was signed.'}
            </p>
          )}
          <ActionBar>
            <BusyButton type="submit" className="btn-primary btn-block" busy={sign.isPending}>
              Sign the reconciliation
            </BusyButton>
          </ActionBar>
        </form>
      )}
      {back}
    </main>
  );
}

function SectionCard({ section, currency, ticked, canTick, onTick }: {
  section: ReconciliationSection;
  currency: string;
  ticked: boolean;
  canTick: boolean;
  onTick: (on: boolean) => void;
}) {
  const checks = section.checks ?? [];
  const items = section.items ?? [];
  const id = `imports-section-${section.key}`;
  return (
    <section className="rt-card stack" aria-labelledby={id} data-tour={`imports-section-${section.key}`}>
      <h2 id={id}>{section.title}</h2>
      {(section.facts ?? []).length > 0 && (
        <dl className="recon-figures">
          {(section.facts ?? []).map((f) => (
            <FigureLine key={f.label} label={f.label ?? ''} value={figure(f.value, 'count', currency)} />
          ))}
        </dl>
      )}
      {checks.length === 0 && <p>Nothing to compare here.</p>}
      {checks.map((c) => (
        <div className="recon-check" key={c.key}>
          <p>
            {c.label} {c.ties ? '' : <strong>(not explained)</strong>}
          </p>
          <dl className="recon-figures">
            <FigureLine label="Your sheet" value={figure(c.source, c.unit, currency)} />
            <FigureLine label="In BMS" value={figure(c.system, c.unit, currency)} />
            <FigureLine label="Difference" value={figure(c.difference, c.unit, currency)} />
            <FigureLine label="Not explained" value={figure(c.unitemised, c.unit, currency)} />
          </dl>
        </div>
      ))}
      {items.length > 0 && (
        <details className="recon-items">
          <summary>Explained differences ({items.length})</summary>
          <ul className="stack">
            {items.map((i, n) => {
              const unit = checks.find((c) => c.key === i.check)?.unit;
              return (
                <li key={`${i.check}-${i.file}-${i.line}-${n}`}>
                  {reasonWords(i.reason)}: {itemPlace(i)}, {i.detail}
                  {i.amount && i.amount !== '0' ? `, ${figure(i.amount, unit, currency)}` : ''}
                </li>
              );
            })}
          </ul>
        </details>
      )}
      {canTick && (
        <label>
          <input type="checkbox" checked={ticked} onChange={(e) => onTick(e.target.checked)} />
          I have checked {section.title?.toLowerCase()}
        </label>
      )}
    </section>
  );
}

function FigureLine({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </>
  );
}

export const Route = createLazyRoute('/staff/setup/imports')({ component: ImportsSignOff });
