import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { BusyButton } from '../../app/ui/BusyButton';
import { type OutboxStatus, outboxStatus, retryOutbox } from '../../api/onboarding';

// Messages the senders could not deliver (FR-NTF-09): three failed attempts each. Recipients are
// masked by the API; no message text is shown. "Send again" puts a row back in the queue.

const TEMPLATE_LABEL: Record<string, string> = {
  'onboarding.verify_email': 'Email confirmation link',
  'onboarding.needs_info': 'Question to an applicant',
  'onboarding.rejected': 'Application not accepted',
  'onboarding.activation': 'Activation link',
  'onboarding.operator_alert': 'Operator alert',
  'feedback.new': 'New feedback',
};

// A suppressed email was never sent: the address bounced or complained before, so Resend blocks it.
const BOUNCE_WORDS: Record<string, string> = {
  complained: 'Marked as unwanted',
  suppressed: 'Not sent, address blocked after an earlier bounce',
};

export function OutboxView({ status, busyId, onRetry }: { status: OutboxStatus; busyId: string | null; onRetry: (id: string) => void }) {
  const channels = status.enabled_channels ?? [];
  const counts = (status.counts ?? {}) as Record<string, number>;
  return (
    <>
      <p>
        Waiting: {counts.pending ?? 0}. Sent: {counts.sent ?? 0}. Failed: {counts.failed ?? 0}.
      </p>
      {(['email', 'telegram'] as const)
        .filter((c) => !channels.includes(c))
        .map((c) => (
          <p key={c} className="alert alert-warning">
            The {c === 'email' ? 'email' : 'Telegram'} sender is not configured on this server: its messages wait until it is.
          </p>
        ))}
      {(status.failures ?? []).length === 0 ? (
        <p>Nothing has failed.</p>
      ) : (
        <div className="table-wrap">
        <table aria-label="Failed messages">
          <thead>
            <tr>
              <th scope="col">Message</th>
              <th scope="col">Channel</th>
              <th scope="col">To</th>
              <th scope="col">Error</th>
              <th scope="col">Failed</th>
              <th scope="col">Action</th>
            </tr>
          </thead>
          <tbody>
            {(status.failures ?? []).map((f) => (
              <tr key={f.id}>
                <td>{TEMPLATE_LABEL[f.template_key ?? ''] ?? f.template_key}</td>
                <td>{f.channel}</td>
                <td>{f.recipient}</td>
                <td>{f.last_error}</td>
                <td>{f.failed_at?.slice(0, 16).replace('T', ' ')}</td>
                <td>
                  <BusyButton type="button" busy={busyId !== null && busyId === f.id} disabled={busyId !== null} onClick={() => onRetry(f.id ?? '')}>
                    Send again
                  </BusyButton>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
      {/* JUSTIFICATION-A3: the bounces and complaints list the #759 lead asked for on this page (issue #333). */}
      <h2>Bounced and complained</h2>
      {(status.bounces ?? []).length === 0 ? (
        <p>No email has bounced or been marked as unwanted.</p>
      ) : (
        <div className="table-wrap">
        <table aria-label="Bounced and complained emails">
          <thead>
            <tr>
              <th scope="col">What happened</th>
              <th scope="col">Message</th>
              <th scope="col">To domain</th>
              <th scope="col">When</th>
            </tr>
          </thead>
          <tbody>
            {(status.bounces ?? []).map((b, i) => (
              <tr key={`${b.outbox_id ?? 'none'}-${b.occurred_at ?? i}-${b.kind}`}>
                <td>{BOUNCE_WORDS[b.kind ?? ''] ?? (b.bounce_type === 'Permanent' ? 'Bounced, address does not work' : 'Bounced')}</td>
                <td>{TEMPLATE_LABEL[(b.template_key ?? '').replace(/_/g, '.')] ?? b.template_key ?? 'Email'}</td>
                <td>{b.recipient_domain}</td>
                <td>{b.occurred_at?.slice(0, 16).replace('T', ' ')}</td>
              </tr>
            ))}
          </tbody>
        </table>
        </div>
      )}
    </>
  );
}

function Outbox() {
  const client = useQueryClient();
  const query = useQuery({ queryKey: ['outbox'], queryFn: outboxStatus });
  const retry = useMutation({
    mutationFn: retryOutbox,
    onSuccess: () => client.invalidateQueries({ queryKey: ['outbox'] }),
  });
  return (
    <main>
      <h1>Messages not sent</h1>
      {query.isPending && <p role="status" className="loading">Loading</p>}
      {(query.isError || retry.isError) && (
        <p role="alert" className="alert alert-danger">
          {(query.error ?? retry.error)?.message}
        </p>
      )}
      {query.data && <OutboxView status={query.data} busyId={retry.isPending ? (retry.variables ?? null) : null} onRetry={(id) => retry.mutate(id)} />}
    </main>
  );
}

export const Route = createLazyRoute('/platform/outbox')({ component: Outbox });
