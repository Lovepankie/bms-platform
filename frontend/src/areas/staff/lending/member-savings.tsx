import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi, useNavigate } from '@tanstack/react-router';
import { useState } from 'react';
import { savings } from '../../../api/savings';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { money } from './loan-state';
import { AccountRows, SavingsGate } from './savings';
import { mayOpenAccounts } from './savings-permissions';

// A member's savings tab (FR-SAV-02): every account the member holds (a member may hold several),
// the total, and opening another one on an active product. Opening moves no money: the first
// deposit is recorded on the new account, which opens straight after.

function OpenAccount({ memberId }: { memberId: string }) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const products = useQuery({ queryKey: ['savings', 'products'], queryFn: () => savings.listProducts() });
  const active = (products.data ?? []).filter((p) => p.status === 'active');
  const [productId, setProductId] = useState('');
  const chosen = active.find((p) => p.id === productId);
  const open = useMutation({
    mutationFn: () => savings.open(memberId, productId),
    onSuccess: (a) => {
      void queryClient.invalidateQueries({ queryKey: ['savings', 'accounts'] });
      void navigate({ to: '/staff/lending/savings/accounts/$accountId', params: { accountId: a.id ?? '' } });
    },
  });
  if (products.data && active.length === 0) {
    return <p className="empty-state">There is no active savings product yet. Ask an admin to set one up.</p>;
  }
  return (
    <form aria-label="Open a savings account" onSubmit={(e) => { e.preventDefault(); if (productId && !open.isPending) open.mutate(); }}>
      <label htmlFor="sv-open-product">Product</label>
      <select id="sv-open-product" value={productId} onChange={(e) => setProductId(e.target.value)}>
        <option value="">Choose a product</option>
        {active.map((p) => (
          <option key={p.id} value={p.id}>
            {p.code}: {p.name}
          </option>
        ))}
      </select>
      {chosen && (chosen.min_opening_balance_minor ?? 0) > 0 && (
        <p className="field-hint">The first deposit must be at least {money(chosen.min_opening_balance_minor, chosen.currency)}.</p>
      )}
      <Problem error={open.error} />
      <button type="submit" className="rt-primary btn-block" disabled={!productId || open.isPending}>
        {open.isPending ? 'Opening' : 'Open account'}
      </button>
    </form>
  );
}

const route = getRouteApi('/staff/lending/members/$memberId/savings');

function MemberSavings() {
  const { memberId } = route.useParams();
  const { me } = useStaff();
  const [opening, setOpening] = useState(false);
  const accounts = useQuery({
    queryKey: ['savings', 'accounts', 'member', memberId],
    queryFn: () => savings.listAccounts({ memberId }),
  });
  const items = accounts.data?.items ?? [];
  const first = items[0];
  const total = items.reduce((sum, a) => sum + (a.balance_minor ?? 0), 0);
  return (
    <SavingsGate title="Member savings">
      <Link to="/staff/lending/savings" className="btn btn-ghost">Back to savings</Link>
      {first && (
        <section className="rt-card ln-head" aria-label="Member">
          <p>
            <strong>{first.member_name}</strong> ({first.member_no})
          </p>
          <p>
            {items.length} {items.length === 1 ? 'account' : 'accounts'}, total {money(total, first.currency)}
          </p>
        </section>
      )}
      {accounts.isPending && <p className="loading">Loading the member's accounts</p>}
      <Problem error={accounts.error} />
      {accounts.data && <AccountRows items={items} />}
      {mayOpenAccounts(me) &&
        (opening ? (
          <section className="rt-card" aria-label="Open a savings account">
            <h2>Open a savings account</h2>
            <OpenAccount memberId={memberId} />
          </section>
        ) : (
          <button type="button" className="rt-primary btn-block" onClick={() => setOpening(true)}>Open a savings account</button>
        ))}
    </SavingsGate>
  );
}

export const Route = createLazyRoute('/staff/lending/members/$memberId/savings')({ component: MemberSavings });
