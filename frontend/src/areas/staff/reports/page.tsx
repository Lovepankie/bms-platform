import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, useRouter } from '@tanstack/react-router';
import { useEffect, useRef, useState, type FormEvent } from 'react';
import { businessZone } from '../../../api/retail';
import {
  OPEN_STATUSES,
  PACK_FORMAT,
  ReportsError,
  reports,
  runErrorText,
  type ReportCatalogueEntry,
  type ReportRun,
  type ReportRunFile,
} from '../../../api/reports';
import { branchFilter } from '../../../auth/branch';
import { BusyButton } from '../../../app/ui/BusyButton';
import { EmptyState, NoAccess } from '../../../app/ui/EmptyState';
import { Segmented } from '../../../app/ui/Segmented';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { holdsReports, maySwitchReportsOn, showReports } from './permissions';

// The report centre (extras slices D1 and D5, issues #214 and #229; ADR-038): the monthly pack first, then every report this
// user may run, grouped by what it is about, each with its own form; then the user's own runs with their status, a filter, a
// download for each file (a pack has a PDF and an Excel workbook) and Run again. A run is queued and made by a job, so the list
// refreshes every 3 seconds while any run is open and says when one is ready. Files are fetched through a signed link and
// deleted after 30 days. Off until a business administrator switches reports on.

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

/** The groups of the catalogue, by the permission a report runs under (chapter 14). */
export const GROUPS: { permission: string; title: string }[] = [
  { permission: 'lending.reports.portfolio', title: 'Loan book' },
  { permission: 'lending.reports.collections', title: 'Collections and officers' },
  { permission: 'lending.reports.members', title: 'Members and collateral' },
  { permission: 'lending.reports.compliance', title: 'For the regulator' },
];

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** A date as the reports print it: "8 Oct 2026" (chapter 14 section 14.1). */
export function reportDate(iso: string | null | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso ?? '');
  if (!m) return '';
  return `${Number(m[3])} ${MONTHS[Number(m[2]) - 1]} ${m[1]}`;
}

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

/** A file type in the words people use: "Excel" for xlsx, "PDF and Excel" for a pack. */
export function formatName(format: string): string {
  if (format === PACK_FORMAT) return 'PDF and Excel';
  return format === 'xlsx' ? 'Excel' : format.toUpperCase();
}

/** The file type to offer first: the PDF to read and print, else the Excel workbook, else whatever comes first. */
export function defaultFormat(formats: string[]): string {
  return ['pdf', 'xlsx', 'csv', 'json'].find((f) => formats.includes(f)) ?? formats[0] ?? 'json';
}

/** Whether a catalogue entry is a pack (offered only as a PDF and a workbook together). */
export function isPack(entry: Pick<ReportCatalogueEntry, 'formats'>): boolean {
  return entry.formats.includes(PACK_FORMAT);
}

/** The files a run offers: what the server lists, or the one file of a run from before files were listed. */
export function filesOf(run: ReportRun): ReportRunFile[] {
  if (run.status !== 'ready' || run.expired) return [];
  if (run.files) return run.files;
  return run.document_id ? [{ document_id: run.document_id, format: run.format }] : [];
}

/** The catalogue in its groups, in order; a report under no known permission goes last, under "Other reports". */
export function grouped(entries: ReportCatalogueEntry[]): { title: string; entries: ReportCatalogueEntry[] }[] {
  const out = GROUPS.map((g) => ({ title: g.title, entries: entries.filter((e) => !isPack(e) && e.permission === g.permission) })).filter(
    (g) => g.entries.length > 0,
  );
  const known = new Set(GROUPS.map((g) => g.permission));
  const other = entries.filter((e) => !isPack(e) && !known.has(e.permission));
  if (other.length > 0) out.push({ title: 'Other reports', entries: other });
  return out;
}

/** No report permission: no access. A holder while runs are off: the switched off note. Otherwise the report centre. */
export function Reports() {
  const { me } = useStaff();
  if (!holdsReports(me)) {
    return (
      <main className="rt ln">
        <h1>Reports</h1>
        <NoAccess />
      </main>
    );
  }
  if (!showReports(me)) return <ReportsOff canSwitch={maySwitchReportsOn(me)} />;
  return <ReportsPage />;
}

/**
 * Report runs switched off for the business (issue #764): never "no access", since Staff users cannot fix it. Someone who may
 * change the business settings gets a link to the switch in Business set-up; anyone else is told who can switch it on.
 */
export function ReportsOff({ canSwitch }: { canSwitch: boolean }) {
  const router = useRouter({ warn: false });
  const label = 'Switch them on in Business set-up';
  return (
    <main className="rt ln">
      <h1>Reports</h1>
      <p className="alert alert-info" role="status">Reports are switched off for this business.</p>
      {canSwitch ? (
        router ? (
          <Link className="btn" to="/staff/setup" hash="reports">
            {label}
          </Link>
        ) : (
          <a className="btn" href="/staff/setup#reports">
            {label}
          </a>
        )
      ) : (
        <p className="ln-muted">Ask the owner to switch them on in Business set-up.</p>
      )}
    </main>
  );
}

function ReportsPage() {
  const { branch, me } = useStaff();
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
  if (off) return <ReportsOff canSwitch={maySwitchReportsOn(me)} />;
  if (catalogue.isError || !catalogue.data) {
    return (
      <main className="rt ln">
        <h1>Reports</h1>
        <Problem error={catalogue.error} />
      </main>
    );
  }
  const entries = catalogue.data;
  const runnable = new Set(entries.map((e) => e.key));
  return (
    <main className="rt ln rp">
      <h1>Reports</h1>
      <p className="ln-muted">
        Each report is prepared in the background from one consistent reading of your books, then kept for 30 days.
        Branch: the one chosen at the top of the page.
      </p>
      <p role="status" aria-live="polite" className={notice ? 'alert alert-success' : 'sr-only'}>{notice}</p>
      {entries.filter(isPack).map((p) => (
        <PackCard key={p.key} entry={p} branchIds={branchFilter(branch)} onQueued={refresh} />
      ))}
      <CatalogueList entries={entries} branchIds={branchFilter(branch)} onQueued={refresh} />
      <RunsTable runs={runs.data ?? []} loading={runs.isLoading} error={runs.error} onChange={refresh} canRun={(key) => runnable.has(key)} />
    </main>
  );
}

/** The monthly pack: a period end and one button, the whole board pack as one PDF and one workbook (issue #229). */
export function PackCard({ entry, branchIds, onQueued }: { entry: ReportCatalogueEntry; branchIds: string[] | undefined; onQueued: () => void }) {
  const [periodEnd, setPeriodEnd] = useState('');
  const queue = useMutation({
    mutationFn: () => {
      const params: Record<string, unknown> = {};
      if (periodEnd) params.period_end = periodEnd;
      if (branchIds && entry.params.some((p) => p.name === 'branch_id')) params.branch_id = branchIds;
      return reports.request(entry.key, params, PACK_FORMAT);
    },
    onSuccess: onQueued,
    meta: { success: 'Report pack requested' },
  });
  function submit(e: FormEvent) {
    e.preventDefault();
    queue.mutate();
  }
  return (
    <form className="card rp-pack" onSubmit={submit} aria-label={entry.title} data-tour="reports-pack">
      <h2 className="card-title">{entry.title}</h2>
      <p className="ln-muted">{entry.description}</p>
      <label className="rp-field">
        Period end
        <input type="date" value={periodEnd} onChange={(ev) => setPeriodEnd(ev.target.value)} />
      </label>
      <p className="ln-muted rp-hint">Leave it empty for today. For a closed month, choose its last day.</p>
      <BusyButton type="submit" className="btn-primary" busy={queue.isPending}>Run the pack</BusyButton>
      <Problem error={queue.error} />
    </form>
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
  const groups = grouped(entries);
  if (groups.length === 0) {
    return <p className="empty-state" id="reports-catalogue" data-tour="reports-catalogue">No report is available to you yet.</p>;
  }
  return (
    <section aria-label="Reports you can run" id="reports-catalogue" data-tour="reports-catalogue">
      {groups.map((g) => (
        <div key={g.title} className="rp-group">
          <h2>{g.title}</h2>
          {g.entries.map((e) => (
            <details key={e.key} className="card rp-report">
              <summary>
                <span className="rp-report-title">{e.title}</span>
                <span className="ln-muted rp-report-text">{e.description}</span>
              </summary>
              <RunForm entry={e} branchIds={branchIds} onQueued={onQueued} />
            </details>
          ))}
        </div>
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
    meta: { success: 'Report requested' },
  });
  function submit(e: FormEvent) {
    e.preventDefault();
    queue.mutate();
  }
  return (
    <form className="iv-filters" onSubmit={submit} aria-label={entry.title}>
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

/** A choice in words: "none" reads "No grouping", an arrears bucket such as "31_60" reads "31 to 60 days late". */
export function words(value: string): string {
  if (value === 'none') return 'No grouping';
  if (value === 'over_90') return 'Over 90 days late';
  const bucket = /^(\d+)_(\d+)$/.exec(value);
  if (bucket) return `${bucket[1]} to ${bucket[2]} days late`;
  return paramLabel(value);
}

type Show = 'all' | 'open' | 'ready' | 'failed';

const SHOW: { value: Show; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'open', label: 'In progress' },
  { value: 'ready', label: 'Ready' },
  { value: 'failed', label: 'Failed' },
];

/** Whether a run is shown under a filter; a cancelled run shows under All only. */
export function shows(run: Pick<ReportRun, 'status'>, show: Show): boolean {
  if (show === 'all') return true;
  if (show === 'open') return OPEN_STATUSES.includes(run.status);
  return run.status === show;
}

/** The label of a file's download button: one file is "Download", a pack's are "Download PDF" and "Download Excel". */
export function downloadLabel(files: ReportRunFile[], file: ReportRunFile): string {
  return files.length === 1 ? 'Download' : `Download ${formatName(file.format)}`;
}

export function RunsTable({
  runs,
  loading,
  error,
  onChange,
  canRun = () => false,
}: {
  runs: ReportRun[];
  loading?: boolean;
  error?: unknown;
  onChange: () => void;
  /** Whether the user may still run a report, so Run again is offered. */
  canRun?: (key: string) => boolean;
}) {
  const [problem, setProblem] = useState<unknown>(null);
  const [show, setShow] = useState<Show>('all');
  const cancel = useMutation({ mutationFn: (id: string) => reports.cancel(id), onSuccess: onChange, onError: setProblem, meta: { success: 'Report cancelled' } });
  const again = useMutation({
    mutationFn: (r: ReportRun) => reports.request(r.report_key, r.params, r.format),
    onSuccess: onChange,
    onError: setProblem,
    meta: { success: 'Report requested' },
  });
  async function download(file: ReportRunFile) {
    setProblem(null);
    try {
      const url = await reports.downloadUrl(file.document_id);
      window.location.assign(url);
    } catch (e) {
      setProblem(e);
      onChange();
    }
  }
  const visible = runs.filter((r) => shows(r, show));
  return (
    <section aria-label="Your reports" data-tour="reports-runs">
      <h2>Your reports</h2>
      {loading && <p className="loading">Loading your reports</p>}
      <Problem error={error ?? problem} />
      {!loading && runs.length === 0 ? (
        <EmptyState
          text="You have not run a report yet."
          action={{ label: 'Choose a report', onClick: () => document.getElementById('reports-catalogue')?.scrollIntoView() }}
        />
      ) : (
        <>
          <Segmented label="Show" options={SHOW} value={show} onChange={setShow} />
          {visible.length === 0 ? (
            <EmptyState text="No report has this status." action={{ label: 'Show all', onClick: () => setShow('all') }} />
          ) : (
            <div className="table-wrap table-cards rp-runs" role="region" aria-label="Your reports" tabIndex={0}>
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
                  {visible.map((r) => {
                    const files = filesOf(r);
                    const finished = !OPEN_STATUSES.includes(r.status);
                    return (
                      <tr key={r.id}>
                        {/* One value for the phone card's flex row, so the title and its format wrap together inside the card. */}
                        <td data-label="Report"><span>{r.title} <span className="ln-muted">({formatName(r.format)})</span></span></td>
                        <td data-label="As at">{reportDate(r.as_of)}</td>
                        <td data-label="Asked">{when(r.requested_at)}</td>
                        <td data-label="Status">
                          <span className={STATUS_BADGE[r.status] ?? 'badge'}>{STATUS_LABEL[r.status] ?? r.status}</span>
                          {r.cancel_requested && <span className="ln-muted"> stopping</span>}
                          {r.status === 'failed' && <div className="ln-muted">{runErrorText(r.error)}</div>}
                        </td>
                        <td data-label="File">
                          {r.status === 'ready' && (r.expired ? 'Deleted after 30 days' : sizeText(r))}
                        </td>
                        <td data-label="Actions" className="rp-actions">
                          {files.map((f) => (
                            <button key={f.document_id} type="button" className="btn-ghost btn-sm" onClick={() => void download(f)}>
                              {downloadLabel(files, f)}
                            </button>
                          ))}
                          {OPEN_STATUSES.includes(r.status) && !r.cancel_requested && (
                            <BusyButton type="button" className="btn-ghost btn-sm" busy={cancel.isPending && cancel.variables === r.id} disabled={cancel.isPending} onClick={() => cancel.mutate(r.id)}>
                              Cancel
                            </BusyButton>
                          )}
                          {finished && canRun(r.report_key) && (
                            <BusyButton type="button" className="btn-ghost btn-sm" busy={again.isPending && again.variables?.id === r.id} disabled={again.isPending} onClick={() => again.mutate(r)}>
                              Run again
                            </BusyButton>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </section>
  );
}

export const Route = createLazyRoute('/staff/reports')({ component: Reports });
