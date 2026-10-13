import { useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retailAnalytics, type Ageing, type AgeingCustomer, type CustomerAgeing } from '../../../api/retail-analytics';
import { NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { foldText } from '../../../app/ui/fold';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { SkeletonList } from '../../../components/states';
import { useStaff } from '../context';
import { Bars, Choice, Empty, Section } from './analytics-ui';
import { termsWords } from './account-state';
import { canOpenAccount } from './permissions';
import { Gate, Problem, money, useBranchView, useIsPhone } from './ui';

// Customer ageing (customer_accounts capability, ADR-033, issue #196): what each credit buyer with a record owes, by days past
// the due date, with their limit and terms. It reads the same sales and buckets as Credit control. A buyer is marked over the
// limit only when the server says so, and it says so only for a report over every branch, because the limit is measured across
// all of them. The search narrows the rows already loaded (the Top number sets how many); it is the shared live search box.

const TOPS = [10, 20, 50, 100].map((n) => ({ value: n, label: `Top ${n}` }));

const BUCKETS: { key: keyof Ageing; label: string }[] = [
  { key: 'not_due_minor', label: 'Not yet due' },
  { key: 'days1_to30_minor', label: '1 to 30 days late' },
  { key: 'days31_to60_minor', label: '31 to 60 days late' },
  { key: 'days61_to90_minor', label: '61 to 90 days late' },
  { key: 'over90_minor', label: 'Over 90 days late' },
];

/** The rows whose name holds every word of `term`, ignoring case and accents; all of them for an empty term. */
export function matchingBuyers(rows: readonly AgeingCustomer[], term: string): AgeingCustomer[] {
  const words = foldText(term).split(' ').filter((w) => w !== '');
  return rows.filter((r) => {
    const name = foldText(r.name ?? '');
    return words.every((w) => name.includes(w));
  });
}

/** The report as a table: one row a buyer, the most owed first. `accounts` adds the link to each buyer's account. */
export function CustomerAgeingView({ data, rows, accounts = false }: { data: CustomerAgeing; rows?: readonly AgeingCustomer[]; accounts?: boolean }) {
  const phone = useIsPhone();
  const totals = data.totals ?? { owed_minor: 0 };
  const shown = rows ?? data.customers ?? [];
  return (
    <>
      <p className="rt-total">Owed by credit buyers with a record {money(totals.owed_minor ?? 0)}</p>
      <p className="hint">{data.customer_count ?? 0} {data.customer_count === 1 ? 'buyer owes' : 'buyers owe'} this, as of {data.as_of}. Days are counted from the due date; a sale with no due date counts as not yet due.</p>
      <Section title="Ageing">
        <Bars label="Owed by age" rows={BUCKETS.map((b) => ({ key: b.key, name: b.label, value: totals[b.key] ?? 0, text: money(totals[b.key] ?? 0) }))} />
      </Section>
      <Section title="By buyer" hint="The buyers owing the most first.">
        {shown.length === 0 ? <Empty>Nobody owes anything.</Empty> : (
          <>
            <div className="table-wrap" tabIndex={0}><table>
              <thead>
                <tr>
                  <th>Buyer</th>
                  <th className="num">Owes</th>
                  {!phone && <th className="num">Limit</th>}
                  {!phone && BUCKETS.map((b) => <th key={b.key} className="num">{b.label}</th>)}
                  {phone && <th className="num">Oldest late</th>}
                </tr>
              </thead>
              <tbody>
                {shown.map((b) => (
                  <tr key={b.customer_id} data-testid={`ageing-${b.customer_id}`}>
                    <td>
                      {accounts && b.customer_id ? <Link to="/staff/retail/accounts/$customerId" params={{ customerId: b.customer_id }} aria-label={`Open the account of ${b.name}`}>{b.name}</Link> : b.name}
                      <br /><span className="hint">{b.sale_count} {b.sale_count === 1 ? 'sale' : 'sales'}. {termsWords(b.terms_days)}</span>
                      {b.over_limit === true && <><br /><span className="rt-flag">Above the credit limit</span></>}
                    </td>
                    <td className="num">{money(b.ageing?.owed_minor ?? 0)}</td>
                    {!phone && <td className="num">{b.credit_limit_minor !== undefined ? money(b.credit_limit_minor) : ''}</td>}
                    {!phone && BUCKETS.map((x) => <td key={x.key} className="num">{b.ageing?.[x.key] ? money(b.ageing[x.key] ?? 0) : ''}</td>)}
                    {phone && <td className="num">{b.oldest_overdue_days !== undefined ? `${b.oldest_overdue_days} days` : ''}</td>}
                  </tr>
                ))}
              </tbody>
            </table></div>
            {(data.customer_count ?? 0) > (data.customers ?? []).length && <p className="hint">Showing {(data.customers ?? []).length} of {data.customer_count}. Raise the Top number to see more.</p>}
          </>
        )}
      </Section>
    </>
  );
}

function CustomerAgeingPage() {
  const { me } = useStaff();
  const { all, branchId, branchName } = useBranchView();
  const [top, setTop] = useState(20);
  const search = useLiveSearch();
  const report = useQuery({
    queryKey: ['retail', 'customer-ageing', all ? 'all' : branchId, top],
    queryFn: () => retailAnalytics.customerAgeing({ branchId: all ? undefined : (branchId ?? ''), top }),
    enabled: all || branchId !== null,
  });
  const rows = report.data ? matchingBuyers(report.data.customers ?? [], search.term) : [];
  return (
    <Gate screen="customerAgeing" title="Customer ageing" wide>
      <p className="branch-line">Branch: <strong>{all ? 'All branches' : branchName}</strong></p>
      <p className="hint">What each credit buyer owes, as of today, by how late it is.</p>
      <div className="rt-row">
        <Choice id="ca-top" label="Rows to show" value={top} options={TOPS} onChange={setTop} />
      </div>
      <SearchBox id="search-ageing" label="Find a buyer in this list" search={search} fetching={false} count={report.data ? rows.length : undefined} />
      {report.isPending && <SkeletonList label="Loading customer ageing" />}
      <Problem error={report.error} />
      {report.data && search.term !== '' && rows.length === 0 ? (
        <NoResults term={search.term} onClear={search.clear} noun="credit buyers" />
      ) : (
        report.data && <SearchResults fetching={false}><CustomerAgeingView data={report.data} rows={rows} accounts={canOpenAccount(me)} /></SearchResults>
      )}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/customer-ageing')({ component: CustomerAgeingPage });
