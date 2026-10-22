import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { type MerchantAccount, payments } from '../../../api/payments';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useStaff } from '../context';
import { STATUS_WORDS, canChangePaymentAccount, codeProblem, credentialProblem, failureWords, nextStep, showReceivePayments } from './state';

// "Receive payments" (extras slice A7, issue #233; ADR-035 amendment of 2026-10-18): money owed to the business lands only in
// the business's own Pesapal account. A business administrator enters the consumer key and secret (the secret is write-only: it
// is never shown again), tests the connection, and sees whether it works. Everyone who can read settings sees the status.

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong.');

function When({ iso }: { iso: string | null }) {
  if (!iso) return null;
  const d = new Date(iso);
  return <>{Number.isNaN(d.getTime()) ? '' : d.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short' })}</>;
}

export function AccountStatus({ account }: { account: MerchantAccount }) {
  const badge = account.status === 'verified' ? 'badge badge-success' : account.status === 'failed' ? 'badge badge-danger' : 'badge';
  return (
    <section className="rt-card stack" aria-labelledby="receive-status" data-tour="payments-status">
      <h2 id="receive-status">Your payment account</h2>
      <p>
        <span className={badge}>{STATUS_WORDS[account.status]}</span>
        {account.key_hint && <span className="hint"> Consumer key ending {account.key_hint}</span>}
      </p>
      <p role={account.status === 'failed' ? 'alert' : 'note'} className={account.status === 'failed' ? 'alert alert-danger' : 'hint'}>
        {nextStep(account)}
      </p>
      {account.last_checked_at && (
        <p className="hint">
          Last tested <When iso={account.last_checked_at} />.
        </p>
      )}
      {account.status === 'failed' && account.failure_reason && <p className="hint">{failureWords(account.failure_reason)}</p>}
    </section>
  );
}

/** A box for the six digits of the authenticator app: never remembered by the browser, cleared after each use. */
export function CodeField({ id, value, onChange, problem }: { id: string; value: string; onChange: (v: string) => void; problem: string | null }) {
  return (
    <>
      <label htmlFor={id}>Code from your authenticator app</label>
      <input
        id={id}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        inputMode="numeric"
        autoComplete="one-time-code"
        maxLength={6}
        spellCheck={false}
        aria-invalid={problem !== null}
      />
      {problem && <p role="alert" className="field-error">{problem}</p>}
    </>
  );
}

export function CredentialsForm({ account, busy, error, onSave }: {
  account: MerchantAccount;
  busy: boolean;
  error: string | null;
  onSave: (key: string, secret: string, code: string) => void;
}) {
  const [key, setKey] = useState('');
  const [secret, setSecret] = useState('');
  const [code, setCode] = useState('');
  const keyProblem = key === '' ? null : credentialProblem(key);
  const secretProblem = secret === '' ? null : credentialProblem(secret);
  const codeBad = code === '' ? null : codeProblem(code);
  const ready = key !== '' && secret !== '' && !credentialProblem(key) && !credentialProblem(secret) && !codeProblem(code);
  return (
    <section className="rt-card stack" aria-labelledby="receive-form" data-tour="payments-credentials">
      <h2 id="receive-form">{account.credentials_saved ? 'Change the key and secret' : 'Enter the key and secret'}</h2>
      <p className="hint">
        You find both in your own Pesapal account, under the API keys of your business. The secret cannot be shown again after you save it.
        Because this changes where your members' payments go, you are asked for a code from your authenticator app.
      </p>
      <form
        className="stack"
        aria-label="Consumer key and secret"
        autoComplete="off"
        onSubmit={(e) => {
          e.preventDefault();
          if (ready && !busy) {
            onSave(key.trim(), secret.trim(), code.trim());
            // Nothing typed is kept on the page once it has been sent.
            setSecret('');
            setCode('');
          }
        }}
      >
        <label htmlFor="receive-key">Consumer key</label>
        <input id="receive-key" value={key} onChange={(e) => setKey(e.target.value)} autoComplete="off" spellCheck={false} aria-invalid={keyProblem !== null} />
        {keyProblem && <p role="alert" className="field-error">{keyProblem}</p>}
        <label htmlFor="receive-secret">Consumer secret</label>
        <input
          id="receive-secret"
          type="password"
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          autoComplete="new-password"
          spellCheck={false}
          aria-invalid={secretProblem !== null}
        />
        {secretProblem && <p role="alert" className="field-error">{secretProblem}</p>}
        <CodeField id="receive-code" value={code} onChange={setCode} problem={codeBad} />
        {error && <p role="alert" className="alert alert-danger">{error}</p>}
        <p>
          <BusyButton type="submit" className="rt-primary" busy={busy} disabled={!ready}>Save the key and secret</BusyButton>
        </p>
      </form>
    </section>
  );
}

export function RemoveAccount({ busy, error, onRemove }: { busy: boolean; error: string | null; onRemove: (code: string) => void }) {
  const [confirming, setConfirming] = useState(false);
  const [code, setCode] = useState('');
  return (
    <section className="rt-card stack" aria-labelledby="receive-remove">
      <h2 id="receive-remove">Remove the account</h2>
      {error && <p role="alert" className="alert alert-danger">{error}</p>}
      {!confirming ? (
        <p>
          <button type="button" /* busy-exempt: opens the confirmation */ className="btn-ghost" disabled={busy} onClick={() => setConfirming(true)}>Remove the saved key and secret</button>
        </p>
      ) : (
        <form
          className="stack"
          aria-label="Confirm removing the account"
          autoComplete="off"
          onSubmit={(e) => {
            e.preventDefault();
            if (!codeProblem(code) && !busy) {
              onRemove(code.trim());
              setCode('');
              setConfirming(false);
            }
          }}
        >
          <p className="alert alert-warning">Collecting stops until you connect an account again. Payments already made are not affected.</p>
          <CodeField id="remove-code" value={code} onChange={setCode} problem={code === '' ? null : codeProblem(code)} />
          <p className="cluster">
            <BusyButton type="submit" className="rt-primary" busy={busy} disabled={codeProblem(code) !== null}>Yes, remove it</BusyButton>
            <button type="button" /* busy-exempt: cancel waits for the request */ className="btn-ghost" disabled={busy} onClick={() => { setConfirming(false); setCode(''); }}>Keep it</button>
          </p>
        </form>
      )}
    </section>
  );
}

export function ReceivePaymentsPage() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  // The key, the secret and the code go through local handlers and are never given to a mutation or a query: the query
  // client keeps the variables of a mutation in its cache, and a secret must not sit there. Only the answer (which
  // carries no secret) is kept.
  const [saving, setSaving] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [removeError, setRemoveError] = useState<string | null>(null);
  const query = useQuery({ queryKey: ['payments', 'account'], queryFn: payments.account, enabled: showReceivePayments(me) });
  const keep = (saved: MerchantAccount) => queryClient.setQueryData(['payments', 'account'], saved);
  const test = useMutation({
    mutationFn: payments.test,
    onSuccess: keep,
    onError: () => void queryClient.invalidateQueries({ queryKey: ['payments', 'account'] }),
  });
  async function save(key: string, secret: string, code: string) {
    setSaving(true);
    setSaveError(null);
    try {
      keep(await payments.save(key, secret, code));
    } catch (e) {
      setSaveError(messageOf(e));
    } finally {
      setSaving(false);
    }
  }
  async function remove(code: string) {
    setRemoving(true);
    setRemoveError(null);
    try {
      keep(await payments.remove(code));
    } catch (e) {
      setRemoveError(messageOf(e));
    } finally {
      setRemoving(false);
    }
  }
  if (!showReceivePayments(me)) {
    return (
      <main className="rt ln">
        <h1>Receive payments</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }
  const account = query.data;
  const canChange = canChangePaymentAccount(me);
  const busy = saving || test.isPending || removing;
  return (
    <main className="rt ln stack">
      <h1>Receive payments</h1>
      <p data-tour="payments-intro">
        Members pay by mobile money or card into <strong>your own</strong> Pesapal account. This system never holds your money.
      </p>
      {query.error && <p role="alert" className="alert alert-danger">{messageOf(query.error)}</p>}
      {!account && !query.error && <p className="loading">Loading</p>}
      {account && <AccountStatus account={account} />}
      {account && canChange && account.credentials_saved && account.status !== 'verified' && (
        <section className="rt-card stack" aria-labelledby="receive-test" data-tour="payments-test">
          <h2 id="receive-test">Test the connection</h2>
          <p className="hint">We ask Pesapal for access with your key and secret and tell it where to report payments. Nothing is charged.</p>
          {test.error && <p role="alert" className="alert alert-danger">{messageOf(test.error)}</p>}
          <p>
            <BusyButton type="button" className="rt-primary" busy={test.isPending} disabled={busy || !account.host_ready} onClick={() => test.mutate()}>
              Test connection
            </BusyButton>
          </p>
        </section>
      )}
      {account && canChange && account.status === 'verified' && (
        <div className="stack">
          {test.error && <p role="alert" className="alert alert-danger">{messageOf(test.error)}</p>}
          <p>
            <BusyButton type="button" className="btn-ghost" busy={test.isPending} disabled={busy || !account.host_ready} onClick={() => test.mutate()}>
              Test again
            </BusyButton>
          </p>
        </div>
      )}
      {account && canChange && (
        <CredentialsForm key={account.key_hint ?? 'new'} account={account} busy={saving} error={saveError} onSave={(key, secret, code) => void save(key, secret, code)} />
      )}
      {account && canChange && account.credentials_saved && <RemoveAccount busy={busy} error={removeError} onRemove={(code) => void remove(code)} />}
      {account && !canChange && <p className="hint">Only the business administrator can change the key and secret.</p>}
    </main>
  );
}

export const Route = createLazyRoute('/staff/payments')({ component: ReceivePaymentsPage });
