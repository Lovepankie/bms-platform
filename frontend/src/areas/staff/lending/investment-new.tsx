import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { Highlight, NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { investments, type InvestmentProduct, type MemberListItem } from '../../../api/investments';
import { Problem } from '../retail/ui';
import { INVESTMENTS_OPEN, instructionWords, may, payoutWords, percent } from './investment-state';
import { InvestmentsGate } from './investments';
import { money, parseAmount } from './loan-state';
import { useStaff } from '../context';
import { MemberLink } from './member-ui';
import { BusyButton } from '../../../app/ui/BusyButton';

// The subscribe form (FR-INV-02): choose the member, the product, the amount and the term, see the
// return schedule the server works out for them (R-INV-1 to R-INV-4), record the member's maturity
// choice, and open the investment. It opens pending funding: the cashier records the money next.

function MemberPicker({ onPick }: { onPick: (m: MemberListItem) => void }) {
  const search = useLiveSearch({ minLength: 2 });
  const found = useQuery({
    queryKey: ['lending', 'member-search', search.term],
    queryFn: ({ signal }) => investments.findMembers(search.term, signal),
    enabled: search.term !== '',
    placeholderData: keepPreviousData,
  });
  const fetching = found.isPlaceholderData || found.isFetching;
  return (
    <section aria-label="Member" data-tour="investment-member">
      <form role="search" className="ln-search" onSubmit={(e) => { e.preventDefault(); search.flush(); }}>
        <SearchBox
          id="iv-member-q"
          label="Member number, name or phone"
          search={search}
          fetching={fetching}
          count={search.term !== '' && found.data ? found.data.length : undefined}
          minLength={2}
          hint="Type at least 2 characters."
        />
      </form>
      <Problem error={found.error} />
      {search.term !== '' && found.data && found.data.length === 0 && <NoResults term={search.term} onClear={search.clear} noun="members" />}
      {search.term !== '' && found.data && found.data.length > 0 && (
        <SearchResults fetching={fetching}>
          <ul className="ln-list">
            {found.data.map((m) => (
              <li key={m.id}>
                <button type="button" className="ln-item" onClick={() => onPick(m)}>
                  <strong><Highlight text={m.full_name ?? ''} term={search.term} /></strong>
                  <span className="ln-muted"><Highlight text={m.member_no ?? ''} term={search.term} /></span>
                </button>
              </li>
            ))}
          </ul>
        </SearchResults>
      )}
    </section>
  );
}

export function SubscribeForm({ member, products }: { member: { id: string; label: string }; products: InvestmentProduct[] }) {
  const navigate = useNavigate();
  const active = products.filter((p) => p.status === 'active');
  const [productId, setProductId] = useState(active[0]?.id ?? '');
  const product = active.find((p) => p.id === productId);
  const [amount, setAmount] = useState('');
  const [term, setTerm] = useState<number>(product?.allowed_terms_months?.[0] ?? 0);
  const [instruction, setInstruction] = useState('');
  const amountMinor = parseAmount(amount, product?.currency);
  const terms = product?.allowed_terms_months ?? [];
  const termOk = terms.includes(term);
  const preview = useQuery({
    queryKey: ['lending', 'investment-preview', productId, amountMinor, term],
    queryFn: () => investments.preview({ product_id: productId, amount_minor: amountMinor ?? 0, term_months: term }),
    enabled: !!product && amountMinor !== null && termOk,
  });
  const save = useMutation({
    mutationFn: () =>
      investments.open({
        member_id: member.id,
        product_id: productId,
        amount_minor: amountMinor ?? 0,
        term_months: term,
        maturity_instruction: instruction || undefined,
      }),
    onSuccess: (inv) => void navigate({ to: '/staff/lending/investments/$investmentId', params: { investmentId: inv.id ?? '' } }),
  });
  if (active.length === 0) return <p className="empty-state">No investment product is set up yet.</p>;
  const p = preview.data;
  return (
    <form aria-label="New investment" onSubmit={(e) => { e.preventDefault(); if (amountMinor !== null && termOk && !save.isPending) save.mutate(); }}>
      <p>For <MemberLink memberId={member.id}><strong>{member.label}</strong></MemberLink></p>
      <label htmlFor="iv-product">Product</label>
      <select id="iv-product" data-tour="investment-product" value={productId} onChange={(e) => {
        setProductId(e.target.value);
        setTerm(active.find((x) => x.id === e.target.value)?.allowed_terms_months?.[0] ?? 0);
      }}>
        {active.map((x) => (
          <option key={x.id} value={x.id}>
            {x.name}: {percent(x.return_rate_bp)} a year, paid {payoutWords(x.payout_frequency).toLowerCase()}
          </option>
        ))}
      </select>
      <label htmlFor="iv-amount">Amount ({product?.currency ?? 'UGX'})</label>
      <input id="iv-amount" data-tour="investment-amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)}
        aria-invalid={amount !== '' && amountMinor === null} aria-describedby="iv-amount-hint" />
      <p id="iv-amount-hint" className="field-hint">
        From {money(product?.min_amount_minor, product?.currency)}
        {product?.max_amount_minor ? ` to ${money(product.max_amount_minor, product.currency)}` : ''}.
      </p>
      <fieldset data-tour="investment-term">
        <legend>Term</legend>
        {terms.map((t) => (
          <label key={t}>
            <input type="radio" name="iv-term" checked={term === t} onChange={() => setTerm(t)} />
            {t} months
          </label>
        ))}
      </fieldset>
      <label htmlFor="iv-instruction">At maturity</label>
      <select id="iv-instruction" value={instruction} onChange={(e) => setInstruction(e.target.value)}>
        <option value="">Decide later</option>
        {['payout', 'rollover_principal', 'rollover_all'].map((i) => (
          <option key={i} value={i}>{instructionWords(i)}</option>
        ))}
      </select>
      {preview.isFetching && <p className="loading">Working out the return</p>}
      <Problem error={preview.error} />
      {p && (
        <dl className="ln-facts" aria-label="Return if funded today">
          <dt>Matures</dt>
          <dd>{p.maturity_date}</dd>
          <dt>Agreed return</dt>
          <dd>{money(p.agreed_return_minor, p.currency)}</dd>
          <dt className="ln-strong">Value at maturity</dt>
          <dd className="ln-strong">{money(p.maturity_value_minor, p.currency)}</dd>
        </dl>
      )}
      <Problem error={save.error} />
      <BusyButton busy={save.isPending} type="submit" className="rt-primary btn-block" data-tour="investment-open" disabled={amountMinor === null || !termOk}>
        Open the investment
      </BusyButton>
      <p className="field-hint">It opens pending funding; record the money received on the next screen.</p>
    </form>
  );
}

export function NewInvestment({ memberId }: { memberId?: string }) {
  const { me } = useStaff();
  const [picked, setPicked] = useState<{ id: string; label: string } | null>(memberId ? { id: memberId, label: 'the member' } : null);
  const products = useQuery({ queryKey: ['lending', 'investment-products'], queryFn: () => investments.products() });
  if (!may(me, INVESTMENTS_OPEN)) return <p className="empty-state">You may not open investments.</p>;
  return (
    <div data-tour="investment-new">
      {!picked && <MemberPicker onPick={(m) => setPicked({ id: m.id ?? '', label: `${m.full_name ?? ''} (${m.member_no ?? ''})` })} />}
      {picked && (
        <>
          {!memberId && (
            <button type="button" className="btn-ghost" onClick={() => setPicked(null)}>Choose another member</button>
          )}
          {products.isPending && <p className="loading">Loading products</p>}
          <Problem error={products.error} />
          {products.data && <SubscribeForm member={picked} products={products.data} />}
        </>
      )}
    </div>
  );
}

const memberRoute = getRouteApi('/staff/lending/investments/member/$memberId/new');

export const Route = createLazyRoute('/staff/lending/investments/new')({
  component: () => (
    <InvestmentsGate title="New investment">
      <NewInvestment />
    </InvestmentsGate>
  ),
});

function ForMember() {
  const { memberId } = memberRoute.useParams();
  return (
    <InvestmentsGate title="New investment">
      <NewInvestment memberId={memberId} />
    </InvestmentsGate>
  );
}

export const MemberRoute = createLazyRoute('/staff/lending/investments/member/$memberId/new')({ component: ForMember });
