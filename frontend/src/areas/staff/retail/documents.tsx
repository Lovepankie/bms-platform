import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type DocumentType } from '../../../api/retail';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { useStaff } from '../context';
import { DocumentCards, SeriesPanel } from './documents-ui';
import { TYPE_LABEL } from './documents-state';
import { holds } from './permissions';
import { Gate, Problem, useBranchName, useBranchView } from './ui';

// Documents (documents capability, ADR-033, issue #197): every invoice, delivery note and quotation issued in the branches the
// session may read, newest first, with the stored PDF one tap away, and the numbered series with their prefixes. A document is
// made from a sale (its Documents panel), a stock move or a quotation; this page finds it again. Nothing here is needed to sell.

const TYPES: DocumentType[] = ['invoice', 'delivery_note', 'quotation'];

export function DocumentsPage() {
  const { me } = useStaff();
  const { all, branchId, branchName } = useBranchView();
  const nameOf = useBranchName();
  const queryClient = useQueryClient();
  const search = useLiveSearch();
  const [type, setType] = useState<DocumentType | ''>('');
  const list = useInfiniteQuery({
    queryKey: ['retail', 'documents', all ? 'all' : branchId, type, search.term],
    queryFn: ({ pageParam, signal }) => retail.listDocuments({ branchId: all ? undefined : (branchId ?? undefined), type: type || undefined, query: search.term, cursor: pageParam, signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next_cursor,
    enabled: all || branchId !== null,
    placeholderData: keepPreviousData,
  });
  const items = (list.data?.pages ?? []).flatMap((p) => p.items ?? []);
  const fetching = list.isPlaceholderData || (list.isFetching && !list.isFetchingNextPage && list.fetchStatus !== 'idle');
  const series = useQuery({ queryKey: ['retail', 'document-series'], queryFn: () => retail.documentSeries() });
  const save = useMutation({
    mutationFn: ({ kind, prefix }: { kind: DocumentType; prefix: string }) => retail.setDocumentPrefix(kind, prefix),
    onSuccess: (rows) => queryClient.setQueryData(['retail', 'document-series'], rows),
  });
  return (
    <Gate screen="documents" title="Documents" wide>
      <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
      <p className="hint" data-tour="documents-help">
        Open a sale to make its invoice or delivery note, or <Link to="/staff/retail/quotations">make a quotation</Link>. The till receipt stays the default: a sale never needs a document.
      </p>
      <div data-tour="documents-list">
        <SearchBox id="search-documents" label="Number or buyer" search={search} fetching={fetching} count={list.data ? items.length : undefined} more={list.hasNextPage} />
        <label htmlFor="documents-type">Show</label>
        <select id="documents-type" value={type} onChange={(e) => setType(e.target.value as DocumentType | '')}>
          <option value="">All documents</option>
          {TYPES.map((t) => <option key={t} value={t}>{TYPE_LABEL[t]}s</option>)}
        </select>
        {list.isPending && list.fetchStatus !== 'idle' && <p className="loading">Loading</p>}
        <Problem error={list.error} />
        {list.data && (
          <SearchResults fetching={fetching}>
            {items.length === 0 && search.term ? <NoResults term={search.term} onClear={search.clear} noun="documents" /> : <DocumentCards items={items} branchOf={all ? nameOf : undefined} term={search.term} />}
          </SearchResults>
        )}
        {list.hasNextPage && (
          <button type="button" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>
            {list.isFetchingNextPage ? 'Loading' : 'Show more'}
          </button>
        )}
      </div>
      <Problem error={series.error} />
      {series.data && <SeriesPanel series={series.data} canEdit={holds(me, 'core.settings.manage')} saving={save.isPending} onSave={(kind, prefix) => save.mutate({ kind, prefix })} />}
      <Problem error={save.error} />
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/documents')({ component: DocumentsPage });
