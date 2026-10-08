import { useMutation, useQuery } from '@tanstack/react-query';
import { createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { investments, type InvestmentProduct, type MemberListItem } from '../../../api/investments';
import { Problem } from '../retail/ui';
import { INVESTMENTS_OPEN, instructionWords, may, payoutWords, percent } from './investment-state';
import { InvestmentsGate } from './investments';
import { money, parseAmount } from './loan-state';
import { useStaff } from '../context';

// The subscribe form (FR-INV-02): choose the member, the product, the amount and the term, see the
// return schedule the server works out for them (R-INV-1 to R-INV-4), record the member's maturity
// choice, and open the investment. It opens pending funding: the cashier records the money next.

function MemberPicker({ onPick }: { onPick: (m: MemberListItem) => void }) {
  const [typed, setTyped] = useState('');
  const [q, setQ] = useState('');
  const found = useQuery({ queryKey: ['lending', 'member-search', q], queryFn: () => investments.findMembers(q), enabled: q.length >= 2 });
  return (
    <section aria-label="Member">
      <form role="search" className="ln-search" onSubmit={(e) => { e.preventDefault(); setQ(typed.trim()); }}>
        <label htmlFor="iv-member-q">Member number or name</label>
        <div className="rt-row">
          <input id="iv-member-q" type="search" value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
          <button type="submit">Find</button>
        </div>
      </form>
      <Problem error={found.error} />
      {found.data && found.data.length === 0 && <p className="empty-state">No member matches.</p>}
      {found.data && found.data.length > 0 && (
        <ul className="ln-list">
          {found.data.map((m) => (
            <li key={m.id}>
              <button type="button" className="ln-item" onClick={() => onPick(m)}>
                <strong>{m.full_name}</strong>
                <span className="ln-muted">{m.member_no}</span>
              </button>
            </li>
          ))}
        </ul>
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
      <p>For <strong>{member.label}</strong></p>
      <label htmlFor="iv-product">Product</label>
      <select id="iv-product" value={productId} onChange={(e) => {
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
      <input id="iv-amount" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)}
        aria-invalid={amount !== '' && amountMinor === null} aria-describedby="iv-amount-hint" />
      <p id="iv-amount-hint" className="field-hint">
        From {money(product?.min_amount_minor, product?.currency)}
        {product?.max_amount_minor ? ` to ${money(product.max_amount_minor, product.currency)}` : ''}.
      </p>
      <fieldset>
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
      <button type="submit" className="rt-primary btn-block" disabled={amountMinor === null || !termOk || save.isPending}>
        {save.isPending ? 'Opening' : 'Open the investment'}
      </button>
      <p className="field-hint">It opens pending funding; record the money received on the next screen.</p>
    </form>
  );
}

function NewInvestment({ memberId }: { memberId?: string }) {
  const { me } = useStaff();
  const [picked, setPicked] = useState<{ id: string; label: string } | null>(memberId ? { id: memberId, label: 'the member' } : null);
  const products = useQuery({ queryKey: ['lending', 'investment-products'], queryFn: () => investments.products() });
  if (!may(me, INVESTMENTS_OPEN)) return <p className="empty-state">You may not open investments.</p>;
  return (
    <>
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
    </>
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
