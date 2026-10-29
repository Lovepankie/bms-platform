import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { businessZone } from '../../../api/retail';
import {
  OPEN_STATUSES,
  ReportsError,
  reports,
  runErrorText,
  type ReportCatalogueEntry,
  type ReportRun,
} from '../../../api/reports';
import { branchFilter } from '../../../auth/branch';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { showReports } from './permissions';
import { NoAccess } from '../../../app/ui/EmptyState';

// Reports (extras slice D1, issue #214; ADR-038): the catalogue of the reports this user may run, a run per click,
// and the user's own runs with their status. A run is queued and executed by a job, so the list refreshes every
// 3 seconds while any run is open and says when one is ready. A file is fetched through a signed link and deleted
// after 30 days. Off until a business administrator switches it on. The full report centre is slice D5.

export const POLL_MS = 3_000;

const STATUS_LABEL: Record<string, string> = {
  queued: 'Waiting',
  running: 'Running',
  ready: 'Ready',
  failed: 'Failed',
  cancelled: 'Cancelled',
};

const STATUS_BADGE: Record<string, string> = {
  queued: 'badge',
  running: 'badge badge-info',
  ready: 'badge badge-success',
  failed: 'badge badge-danger',
  cancelled: 'badge',
};

/** A run's date and time in the reader's language, down to the minute. */
export function when(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: businessZone() });
}

/** Rows and size in words: "3 rows, 1 KB". */
export function sizeText(run: Pick<ReportRun, 'row_count' | 'size_bytes'>): string {
  if (run.row_count == null) return '';
  const rows = run.row_count === 1 ? '1 row' : `${run.row_count} rows`;
  const bytes = run.size_bytes ?? 0;
  const size = bytes < 1024 ? `${bytes} B` : bytes < 1024 * 1024 ? `${Math.round(bytes / 1024)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${rows}, ${size}`;
}

/** A file type in the words people use: "Excel" for xlsx. */
export function formatName(format: string): string {
  return format === 'xlsx' ? 'Excel' : format.toUpperCase();
}

/** The file type to offer first: the PDF to read and print, else the Excel workbook, else whatever comes first. */
export function defaultFormat(formats: string[]): string {
  return ['pdf', 'xlsx', 'csv', 'json'].find((f) => formats.includes(f)) ?? formats[0] ?? 'json';
}

export function Reports() {
  const { me } = useStaff();
  if (!showReports(me)) {
    return (
      <main className="rt ln">
        <h1>Reports</h1>
        <NoAccess />
      </main>
    );
  }
  return <ReportsPage />;
}

function ReportsPage() {
  const { branch } = useStaff();
  const queryClient = useQueryClient();
  const catalogue = useQuery({ queryKey: ['reports', 'catalogue'], queryFn: reports.catalogue, retry: false });
  // Off is a 404 from the server, like every report route: the tab is hidden by /me, and a stale page says so.
  const off = catalogue.error instanceof ReportsError && catalogue.error.code === 'reports_not_enabled';
  const enabled = catalogue.data !== undefined;
  const runs = useQuery({
    queryKey: ['reports', 'runs'],
    queryFn: reports.runs,
    enabled,
    refetchInterval: (q) => (q.state.data?.some((r) => OPEN_STATUSES.includes(r.status)) ? POLL_MS : false),
  });

  // Say so when a run finishes while the page is open (an in-app notice, FR-RPT-04).
  const seen = useRef<Map<string, string>>(new Map());
  const [notice, setNotice] = useState('');
  useEffect(() => {
    for (const r of runs.data ?? []) {
      const before = seen.current.get(r.id);
      if (before && before !== r.status && r.status === 'ready') setNotice(`${r.title} is ready to download.`);
      if (before && before !== r.status && r.status === 'failed') setNotice(`${r.title} could not be produced.`);
      seen.current.set(r.id, r.status);
    }
  }, [runs.data]);

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['reports'] });

  if (catalogue.isLoading) {
    return (
      <main className="rt ln">
        <h1>Reports</h1>
        <p className="loading">Loading the reports</p>
      </main>
    );
  }
  if (catalogue.isError || !catalogue.data) {
    return (
      <main className="rt ln">
        <h1>Reports</h1>
        <Problem error={catalogue.error} />
      </main>
    );
  }
  if (off) {
    return (
      <main className="rt ln">
        <h1>Reports</h1>
        <p className="alert alert-info">Reports are switched off for this business.</p>
        <p className="ln-muted">A business administrator can switch them on in Business set-up.</p>
      </main>
    );
  }
  return (
    <main className="rt ln">
      <h1>Reports</h1>
      <p className="ln-muted">
        Each report is prepared in the background from one consistent reading of your books, then kept for 30 days.
        Branch: the one chosen at the top of the page.
      </p>
      <p role="status" aria-live="polite" className={notice ? 'alert alert-success' : 'sr-only'}>{notice}</p>
      <CatalogueList entries={catalogue.data ?? []} branchIds={branchFilter(branch)} onQueued={refresh} />
      <RunsTable runs={runs.data ?? []} loading={runs.isLoading} error={runs.error} onChange={refresh} />
    </main>
  );
}

export function CatalogueList({
  entries,
  branchIds,
  onQueued,
}: {
  entries: ReportCatalogueEntry[];
  branchIds: string[] | undefined;
  onQueued: () => void;
}) {
  if (entries.length === 0) {
    return <p className="empty-state" data-tour="reports-catalogue">No report is available to you yet.</p>;
  }
  return (
    <section aria-label="Reports you can run" data-tour="reports-catalogue">
      {entries.map((e) => (
        <RunForm key={e.key} entry={e} branchIds={branchIds} onQueued={onQueued} />
      ))}
    </section>
  );
}

function RunForm({ entry, branchIds, onQueued }: { entry: ReportCatalogueEntry; branchIds: string[] | undefined; onQueued: () => void }) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [format, setFormat] = useState(defaultFormat(entry.formats));
  const queue = useMutation({
    mutationFn: () => {
      const params: Record<string, unknown> = {};
      for (const p of entry.params) {
        if (p.type === 'uuid_list') {
          if (branchIds && p.name === 'branch_id') params[p.name] = branchIds;
        } else if (values[p.name]) {
          params[p.name] = p.type === 'integer' ? Number(values[p.name]) : values[p.name];
        }
      }
      return reports.request(entry.key, params, format);
    },
    onSuccess: onQueued,
  });
  function submit(e: FormEvent) {
    e.preventDefault();
    queue.mutate();
  }
  return (
    <form className="iv-filters" onSubmit={submit} aria-label={entry.title}>
      <h2>{entry.title}</h2>
      <p className="ln-muted">{entry.description}</p>
      {entry.params
        .filter((p) => p.type !== 'uuid_list')
        .map((p) => (
          <label key={p.name}>
            {paramLabel(p.name)}
            {p.type === 'enum' ? (
              // empty: Default is always there; the values come with the report.
              <select value={values[p.name] ?? ''} onChange={(ev) => setValues({ ...values, [p.name]: ev.target.value })}>
                <option value="">Default</option>
                {p.values.map((v) => (
                  <option key={v} value={v}>{words(v)}</option>
                ))}
              </select>
            ) : (
              <input
                type={p.type === 'date' ? 'date' : p.type === 'integer' ? 'number' : 'text'}
                value={values[p.name] ?? ''}
                onChange={(ev) => setValues({ ...values, [p.name]: ev.target.value })}
              />
            )}
          </label>
        ))}
      <label>
        File type
        {/* empty: every report the server lists has at least one file type. */}
        <select value={format} onChange={(ev) => setFormat(ev.target.value)}>
          {entry.formats.map((f) => (
            <option key={f} value={f}>{FORMAT_LABEL[f] ?? f.toUpperCase()}</option>
          ))}
        </select>
      </label>
      <BusyButton type="submit" className="btn-primary" busy={queue.isPending}>Run report</BusyButton>
      <Problem error={queue.error} />
    </form>
  );
}

const FORMAT_LABEL: Record<string, string> = {
  pdf: 'PDF (to read and print)',
  xlsx: 'Excel (workbook)',
  csv: 'CSV (plain spreadsheet)',
  json: 'JSON (data)',
};

/** "as_at" reads "As at", "group_by" reads "Group by". */
export function paramLabel(name: string): string {
  const text = name.replaceAll('_', ' ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

function words(value: string): string {
  return value === 'none' ? 'No grouping' : paramLabel(value);
}

export function RunsTable({
  runs,
  loading,
  error,
  onChange,
}: {
  runs: ReportRun[];
  loading?: boolean;
  error?: unknown;
  onChange: () => void;
}) {
  const [problem, setProblem] = useState<unknown>(null);
  const cancel = useMutation({ mutationFn: (id: string) => reports.cancel(id), onSuccess: onChange, onError: setProblem });
  async function download(run: ReportRun) {
    setProblem(null);
    try {
      const url = await reports.downloadUrl(run.document_id as string);
      window.location.assign(url);
    } catch (e) {
      setProblem(e);
      onChange();
    }
  }
  return (
    <section aria-label="Your reports" data-tour="reports-runs">
      <h2>Your reports</h2>
      {loading && <p className="loading">Loading your reports</p>}
      <Problem error={error ?? problem} />
      {!loading && runs.length === 0 ? (
        <p className="ln-muted">You have not run a report yet.</p>
      ) : (
        <div className="table-wrap table-cards" role="region" aria-label="Your reports" tabIndex={0}>
          <table>
            <thead>
              <tr>
                <th scope="col">Report</th>
                <th scope="col">As at</th>
                <th scope="col">Asked</th>
                <th scope="col">Status</th>
                <th scope="col">File</th>
                <th scope="col"><span className="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {runs.map((r) => (
                <tr key={r.id}>
                  <td data-label="Report">{r.title} <span className="ln-muted">({formatName(r.format)})</span></td>
                  <td data-label="As at">{r.as_of}</td>
                  <td data-label="Asked">{when(r.requested_at)}</td>
                  <td data-label="Status">
                    <span className={STATUS_BADGE[r.status] ?? 'badge'}>{STATUS_LABEL[r.status] ?? r.status}</span>
                    {r.cancel_requested && <span className="ln-muted"> stopping</span>}
                    {r.status === 'failed' && <div className="ln-muted">{runErrorText(r.error)}</div>}
                  </td>
                  <td data-label="File">
                    {r.status === 'ready' && (r.expired ? 'Deleted after 30 days' : sizeText(r))}
                  </td>
                  <td data-label="Actions">
                    {r.status === 'ready' && !r.expired && r.document_id && (
                      <button type="button" className="btn-ghost btn-sm" onClick={() => void download(r)}>Download</button>
                    )}
                    {OPEN_STATUSES.includes(r.status) && !r.cancel_requested && (
                      <BusyButton type="button" className="btn-ghost btn-sm" busy={cancel.isPending && cancel.variables === r.id} disabled={cancel.isPending} onClick={() => cancel.mutate(r.id)}>
                        Cancel
                      </BusyButton>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

export const Route = createLazyRoute('/staff/reports')({ component: Reports });
