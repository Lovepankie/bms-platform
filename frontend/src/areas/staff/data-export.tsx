import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { dataExport, OPEN_STATUSES, reports, runErrorText, type DataExportStatus } from '../../api/reports';
import { BusyButton } from '../../app/ui/BusyButton';

// "Download my data" in Business set-up (issue #334 step 1, ADR-074): the whole business as one Excel workbook, a sheet per
// table, made in the background and kept for 30 days; and the weekly copy emailed to the Owner, which can be switched off.
// Read only: it changes none of the business's data, so it works while the subscription has ended.

const QUERY = ['data-export'];

function when(at: string | null): string {
  if (!at) return 'never';
  return new Date(at).toLocaleString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** The tables the workbook holds, for the modules the business has on (all of them when not known). */
export function exportWording(modules: string[] | null | undefined): string {
  const retail = !modules || modules.includes('retail');
  const lending = !modules || modules.includes('lending');
  const tables = [
    ...(retail ? ['sales, stock, restocks, credit buyers, the cash book, customers and products'] : []),
    ...(lending ? ['members, loans, repayments and arrears'] : []),
  ];
  return tables.length === 0 ? 'your branches' : tables.join('; ');
}

export function DataExportCard({ modules }: { modules?: string[] | null }) {
  const queryClient = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const status = useQuery({
    queryKey: QUERY,
    queryFn: dataExport.status,
    refetchInterval: (query) => (OPEN_STATUSES.includes(query.state.data?.latest?.status ?? '') ? 3000 : false),
  });
  const set = (next: DataExportStatus) => queryClient.setQueryData(QUERY, next);
  const fail = (e: unknown) => setMessage(e instanceof Error ? e.message : 'Something went wrong. Try again.');
  const ask = useMutation({
    mutationFn: dataExport.request,
    onMutate: () => setMessage(null),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: QUERY }),
    onError: fail,
  });
  // busy-exempt: a checkbox, disabled while the change saves
  const weekly = useMutation({
    mutationFn: dataExport.setWeeklyEmail,
    onMutate: () => setMessage(null),
    onSuccess: (next) => {
      set(next);
      setMessage(next.weekly_email ? 'The Owner will get a copy by email every Monday.' : 'The weekly email is off.');
    },
    onError: fail,
  });
  const download = useMutation({
    mutationFn: (documentId: string) => reports.downloadUrl(documentId),
    onMutate: () => setMessage(null),
    onSuccess: (url) => window.location.assign(url),
    onError: fail,
  });

  const data = status.data;
  const latest = data?.latest ?? null;
  const open = latest !== null && OPEN_STATUSES.includes(latest.status);
  const ready = latest?.status === 'ready' && !latest.expired && latest.document_id;

  return (
    <fieldset data-tour="setup-data-export">
      <legend>Download my data</legend>
      <p>
        Every table of the business in one Excel workbook, a sheet each: {exportWording(modules)}. It changes nothing and is kept
        for 30 days.
      </p>
      {status.isError && <p role="alert">Could not load the data export.</p>}
      {data && (
        <>
          <p>
            Last export: <strong>{when(data.last_export_at)}</strong>. Last weekly email: <strong>{when(data.last_emailed_at)}</strong>.
          </p>
          <BusyButton busy={ask.isPending || open} onClick={() => ask.mutate()}>
            Download my data
          </BusyButton>
          {open && <p role="status">Preparing your workbook. It takes a minute or two; you can leave this page.</p>}
          {ready && (
            <p>
              Your workbook is ready{latest.expires_at ? ` until ${when(latest.expires_at)}` : ''}.{' '}
              <BusyButton busy={download.isPending} onClick={() => download.mutate(latest.document_id as string)}>
                Save the workbook
              </BusyButton>
            </p>
          )}
          {latest && (latest.status === 'failed' || latest.status === 'cancelled') && (
            <p role="alert">{runErrorText(latest.error) || 'The workbook was not made. Try again.'}</p>
          )}
          <label style={{ display: 'block', marginTop: 8 }}>
            <input
              type="checkbox"
              checked={data.weekly_email}
              disabled={weekly.isPending}
              onChange={(e) => weekly.mutate(e.target.checked)}
            />{' '}
            Email the Owner a copy every week (attached, or a link when it is too large for email)
          </label>
        </>
      )}
      {message && <p role="status">{message}</p>}
    </fieldset>
  );
}
