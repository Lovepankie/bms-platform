import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Highlight, NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { useMembersApi } from '../../../api/lending-context';
import type { MemberListItem } from '../../../api/members';
import { branchFilter } from '../../../auth/branch';
import { useStaff } from '../context';

// Choose a member by searching as you type (number, name, phone or national ID; never in the address). Used where a record
// belongs to a member, such as new collateral. A chosen member shows as a line with a way to choose another.

export function MemberPicker({ id, chosen, onChoose, error, errorId }: {
  id: string;
  chosen: { id: string; name: string; no: string } | null;
  onChoose: (m: { id: string; name: string; no: string } | null) => void;
  error?: string;
  errorId?: string;
}) {
  const { branch } = useStaff();
  const api = useMembersApi();
  const search = useLiveSearch();
  const found = useQuery({
    queryKey: ['members', 'picker', branch, search.term],
    queryFn: ({ signal }) => api.list({ q: search.term, status: 'active', branchIds: branchFilter(branch), signal }),
    enabled: chosen === null && search.term !== '',
    placeholderData: keepPreviousData,
  });
  if (chosen) {
    return (
      <div className="mf-field" data-tour="member-picker">
        <p className="ph-label">Member</p>
        <p className="cluster">
          <strong>{chosen.name}</strong> ({chosen.no})
          <button type="button" className="btn-sm btn-ghost" onClick={() => onChoose(null)}>Choose another member</button>
        </p>
      </div>
    );
  }
  const items: MemberListItem[] = found.data?.items ?? [];
  const fetching = found.isFetching;
  return (
    <div className="mf-field" data-tour="member-picker">
      <SearchBox id={id} label="Find the member (number, name, phone or national ID)" search={search} fetching={fetching} count={search.term && found.data ? items.length : undefined} />
      {error && <p id={errorId} className="field-error" role="alert">{error}</p>}
      {search.term !== '' && found.data && (
        <SearchResults fetching={fetching}>
          {items.length === 0 ? (
            <NoResults term={search.term} onClear={search.clear} noun="members" />
          ) : (
            <ul className="ln-list">
              {items.map((m) => (
                <li key={m.id}>
                  <button type="button" className="ln-item" onClick={() => onChoose({ id: m.id ?? '', name: m.full_name ?? '', no: m.member_no ?? '' })}>
                    <strong><Highlight text={m.full_name ?? ''} term={search.term} /></strong>
                    <span>{m.member_no}, phone {m.phone_e164_masked}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}
        </SearchResults>
      )}
    </div>
  );
}
