import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute, useNavigate } from '@tanstack/react-router';
import { importRuns, startImport } from '../../../api/imports';
import { ActionBar } from '../../../app/ui/ActionBar';
import { BusyButton } from '../../../app/ui/BusyButton';
import { EmptyState, NoAccess } from '../../../app/ui/EmptyState';
import { Hint } from '../../../app/ui/Hint';
import { ListRow, RowList } from '../../../app/ui/ListRow';
import { Skeleton } from '../../../app/ui/Skeleton';
import { useStaff } from '../context';
import { mayOpen, stateWords, stepLine } from './pipeline';

// Business set-up > Bring in your data (issue #141, spec sections 2 and 11): the business's imports, newest first, each with the
// step it stands on. The one main action starts a new import. Run by the person Rincol Tech signs in with, holder of
// core.imports.manage; later by the business's own admin (#142).

export const RUNS_KEY = ['import-runs'] as const;

export function ImportRuns() {
  const { me } = useStaff();
  const allowed = mayOpen(me, '/staff/setup/imports/runs');
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const runs = useQuery({ queryKey: RUNS_KEY, queryFn: importRuns, enabled: allowed });
  const start = useMutation({
    meta: { success: 'New import started' },
    mutationFn: startImport,
    onSuccess: async (run) => {
      await queryClient.invalidateQueries({ queryKey: RUNS_KEY });
      await navigate({ to: '/staff/setup/imports/runs/$runId', params: { runId: run.id ?? '' } });
    },
  });

  if (!allowed) {
    return (
      <main className="stack">
        <h1>Bring in your data</h1>
        <NoAccess what="the imports" />
      </main>
    );
  }
  return (
    <main className="stack">
      <h1>Bring in your data</h1>
      <Hint tour="import-runs-intro">Your sheets are checked here before anything enters your books. Nothing changes until a second person approves.</Hint>
      {runs.isError && (
        <p role="alert" className="alert alert-danger">
          Could not load the imports.
        </p>
      )}
      <Skeleton loading={runs.isPending} of="import-runs" rows={2}>
        {runs.data && runs.data.length === 0 && <EmptyState text="No import yet." />}
        {runs.data && runs.data.length > 0 && (
          <div data-tour="import-runs-list">
            <RowList label="Imports">
              {runs.data.map((r) => (
                <ListRow
                  key={r.id}
                  title={stateWords(r.status)}
                  meta={stepLine(r.status)}
                  to={`/staff/setup/imports/runs/${r.id ?? ''}`}
                />
              ))}
            </RowList>
          </div>
        )}
      </Skeleton>
      {start.isError && (
        <p role="alert" className="alert alert-danger">
          {start.error instanceof Error ? start.error.message : 'Could not start an import.'}
        </p>
      )}
      <ActionBar>
        <BusyButton type="button" className="btn-primary btn-block" busy={start.isPending} onClick={() => start.mutate()} data-tour="import-runs-start">
          Start a new import
        </BusyButton>
      </ActionBar>
    </main>
  );
}

export const Route = createLazyRoute('/staff/setup/imports/runs')({ component: ImportRuns });
