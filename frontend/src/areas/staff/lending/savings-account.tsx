import { useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { Link, createLazyRoute, getRouteApi } from '@tanstack/react-router';
import { useState } from 'react';
import { savings, type SavingsAccount, type SavingsTransaction } from '../../../api/savings';
import { useStaff } from '../context';
import { Problem } from '../retail/ui';
import { methodWords, money, words } from './loan-state';
import { CloseForm, MoneyForm, ReverseSavingsForm, StatusForm } from './savings-forms';
import { SavingsGate, savingsBadge } from './savings';
import { canReverseSavings, savingsActionsFor, type SavingsAction } from './savings-permissions';

// One savings account (FR-SAV-02 to FR-SAV-07): its status, balance, what can be paid out now and
// the interest earned since the last posting; the actions the session may take in this status,
// each opening its form in place; and the movements with their running balances, newest first.
// The server checks every action again.

export const SAVINGS_ACTION_LABELS: Record<SavingsAction, string> = {
  deposit: 'Deposit',
  withdraw: 'Withdraw',
  close: 'Close account',
  freeze: 'Freeze',
  unfreeze: 'Unfreeze',
  reactivate: 'Reactivate',
};

const TXN_WORDS: Record<string, string> = {
  deposit: 'Deposit',
  withdrawal: 'Withdrawal',
  interest: 'Interest',
  fee: 'Withdrawal fee',
  reversal: 'Reversal',
};

const txnWords = (t: string | undefined): string => (t ? TXN_WORDS[t] ?? words(t) : '');

export function AccountCard({ account }: { account: SavingsAccount }) {
  const a = account;
  const c = a.currency;
  return (
    <section aria-label="Savings account" className="rt-card ln-head">
      <p className="ln-item-head">
        <strong>{a.account_no}</strong>
        <span className={savingsBadge(a.status)}>{words(a.status)}</span>
      </p>
      <p>
        <Link to="/staff/lending/members/$memberId/savings" params={{ memberId: a.member_id ?? '' }}>
          {a.member_name} ({a.member_no})
        </Link>
      </p>
      <p className="ln-muted">
        {a.product_name} ({a.product_code}), {a.interest_calc === 'none' ? 'no interest' : `${(a.interest_rate_bp ?? 0) / 100}% a year, ${words(a.interest_calc)}, posted ${a.interest_posting}`}
      </p>
      {a.status_reason && a.status !== 'active' && <p className="ln-muted">Reason: {a.status_reason}</p>}
      <dl className="ln-facts">
        <dt className="ln-strong">Balance</dt>
        <dd className="ln-strong">{money(a.balance_minor, c)}</dd>
        <dt>Can be paid out now</dt>
        <dd>{money(a.available_minor ?? 0, c)}</dd>
        <dt>Minimum balance</dt>
        <dd>{money(a.min_balance_minor ?? 0, c)}</dd>
        <dt>Withdrawal fee</dt>
        <dd>{money(a.withdrawal_fee_minor ?? 0, c)}</dd>
        {(a.hold_minor ?? 0) > 0 && (
          <>
            <dt>On hold</dt>
            <dd>{money(a.hold_minor, c)}</dd>
          </>
        )}
        <dt>Interest earned, not yet posted</dt>
        <dd>{money(a.accrued_interest_minor ?? 0, c)}</dd>
        <dt>Last posted to</dt>
        <dd>{a.last_interest_posted_to ?? 'None yet'}</dd>
        <dt>Opened</dt>
        <dd>{a.opened_on}</dd>
        <dt>Last member deposit or withdrawal</dt>
        <dd>{a.last_member_txn_on ?? 'None yet'}</dd>
        {a.closed_on && (
          <>
            <dt>Closed</dt>
            <dd>{a.closed_on}</dd>
          </>
        )}
      </dl>
    </section>
  );
}

export function SavingsTransactions({ account, items }: { account: SavingsAccount; items: SavingsTransaction[] }) {
  const { me } = useStaff();
  const [reversing, setReversing] = useState<string | null>(null);
  if (items.length === 0) return <p className="empty-state">No money has moved on this account yet.</p>;
  return (
    <ul className="ln-list">
      {items.map((t) => (
        <li key={t.id} className="rt-card">
          <p className="ln-item-head">
            <strong>{txnWords(t.txn_type)}</strong>
            <span className="num">
              {t.credit ? '+' : '-'}
              {money(t.amount_minor, t.currency ?? account.currency)}
            </span>
          </p>
          <p className="ln-muted">
            {t.value_date}
            {t.receipt_no && `, ${t.receipt_no}`}
            {t.payment_method_key && `, ${methodWords(t.payment_method_key)}`}
            {t.external_reference && `, ref ${t.external_reference}`}
          </p>
          <p className="ln-muted">Balance after {money(t.balance_after_minor, account.currency)}</p>
          {t.reversed_by_txn_id && <p><span className="badge badge-danger">Reversed</span></p>}
          {t.reason && <p className="ln-muted">{t.txn_type === 'interest' ? t.reason : `Reason: ${t.reason}`}</p>}
          {canReverseSavings(me, account.status, t) &&
            (reversing === t.id ? (
              <ReverseSavingsForm account={account} txn={t} />
            ) : (
              <button type="button" onClick={() => setReversing(t.id ?? null)} aria-label={`Reverse ${t.receipt_no ?? 'this transaction'}`}>
                Reverse
              </button>
            ))}
        </li>
      ))}
    </ul>
  );
}

/** The account page body, given the account; the movements load beside it. */
export function AccountView({ account }: { account: SavingsAccount }) {
  const { me } = useStaff();
  const accountId = account.id ?? '';
  const [open, setOpen] = useState<SavingsAction | null>(null);
  const txns = useInfiniteQuery({
    queryKey: ['savings', 'account', accountId, 'transactions'],
    queryFn: ({ pageParam }) => savings.listTransactions(accountId, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.next,
  });
  const actions = savingsActionsFor(me, account.status);
  const items = (txns.data?.pages ?? []).flatMap((p) => p.items);
  return (
    <>
      <AccountCard account={account} />
      {actions.length > 0 && (
        <div className="ln-actions" role="group" aria-label="Actions">
          {actions.map((a) => (
            <button key={a} type="button" aria-pressed={open === a} onClick={() => setOpen(open === a ? null : a)}>
              {SAVINGS_ACTION_LABELS[a]}
            </button>
          ))}
        </div>
      )}
      {open && (
        <section aria-label={SAVINGS_ACTION_LABELS[open]} className="rt-card">
          <h2>{SAVINGS_ACTION_LABELS[open]}</h2>
          {open === 'deposit' && <MoneyForm account={account} kind="deposit" />}
          {open === 'withdraw' && <MoneyForm account={account} kind="withdraw" />}
          {open === 'close' && <CloseForm account={account} />}
          {(open === 'freeze' || open === 'unfreeze' || open === 'reactivate') && <StatusForm account={account} change={open} />}
        </section>
      )}
      <p>
        <Link to="/staff/lending/savings/accounts/$accountId/statement" params={{ accountId }} className="btn btn-ghost">
          Statement
        </Link>
      </p>
      <h2>Transactions</h2>
      {txns.isPending && <p className="loading">Loading transactions</p>}
      <Problem error={txns.error} />
      {txns.data && <SavingsTransactions account={account} items={items} />}
      {txns.hasNextPage && (
        <button type="button" className="btn-block" disabled={txns.isFetchingNextPage} onClick={() => void txns.fetchNextPage()}>
          {txns.isFetchingNextPage ? 'Loading' : 'Load more'}
        </button>
      )}
    </>
  );
}

const route = getRouteApi('/staff/lending/savings/accounts/$accountId');

function AccountPage() {
  const { accountId } = route.useParams();
  const account = useQuery({ queryKey: ['savings', 'account', accountId], queryFn: () => savings.getAccount(accountId) });
  return (
    <SavingsGate title="Savings account">
      <Link to="/staff/lending/savings" className="btn btn-ghost">Back to savings</Link>
      {account.isPending && <p className="loading">Loading the account</p>}
      <Problem error={account.error} />
      {account.data && <AccountView account={account.data} />}
    </SavingsGate>
  );
}

export const Route = createLazyRoute('/staff/lending/savings/accounts/$accountId')({ component: AccountPage });
