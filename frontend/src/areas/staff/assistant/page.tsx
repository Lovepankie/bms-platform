import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { businessZone } from '../../../api/retail';
import { advisor, type AdvisorSettings } from '../../../api/advisor';
import { BusyButton } from '../../../app/ui/BusyButton';
import { useStaff } from '../context';
import { SETTINGS_MANAGE, canConfigureAdvisor } from '../insights/permissions';
import { NoAccess } from '../../../app/ui/EmptyState';

// The AI assistant's settings (extras slice C3, issue #226; ADR-037; spec 5.2): the switch with the acknowledgement of what is
// sent and where, the two daily caps (a cap of 0 stops every call), and what was used today. Off until a business administrator
// switches it on, and only possible when the platform has named the business. Saved under the version that was read.

function When({ iso }: { iso: string | null }) {
  if (!iso) return null;
  const d = new Date(iso);
  return <>{Number.isNaN(d.getTime()) ? '' : d.toLocaleString('en-GB', { dateStyle: 'medium', timeStyle: 'short', timeZone: businessZone() })}</>;
}

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong.');

export function AssistantSwitch({ saved, canManage, busy, error, onChange }: {
  saved: AdvisorSettings;
  canManage: boolean;
  busy: boolean;
  error: string | null;
  onChange: (change: { enabled: boolean; acknowledged_text_version?: string }) => void;
}) {
  const [ticked, setTicked] = useState(false);
  const [opening, setOpening] = useState(false);
  return (
    <section className="rt-card stack" aria-labelledby="assistant-switch">
      <h2 id="assistant-switch">AI assistant (advisory)</h2>
      <p>
        {saved.enabled ? <span className="badge badge-success">On</span> : <span className="badge">Off</span>}{' '}
        {saved.enabled
          ? 'A summary of your portfolio figures is made each morning. It only advises and changes nothing.'
          : 'Off. No figure leaves your business, and the Insights page shows the automatic summary.'}
      </p>
      {!saved.allowed && <p role="note" className="alert alert-info">The platform has not opened the assistant for your business yet. Ask the platform operator.</p>}
      {saved.allowed && !saved.provider_configured && <p role="note" className="alert alert-info">No provider is set up on the platform, so the automatic summary is shown.</p>}
      {saved.enabled && saved.acknowledged_at && (
        <p className="hint">Switched on after the notice was accepted on <When iso={saved.acknowledged_at} />.</p>
      )}
      {error && <p role="alert" className="alert alert-danger">{error}</p>}
      {canManage && saved.enabled && (
        <p>
          <BusyButton type="button" className="btn-ghost" busy={busy} onClick={() => onChange({ enabled: false })}>Switch the assistant off</BusyButton>
        </p>
      )}
      {canManage && !saved.enabled && saved.allowed && !opening && (
        <p><button type="button" className="rt-primary" onClick={() => setOpening(true)}>Switch the assistant on</button></p>
      )}
      {canManage && !saved.enabled && saved.allowed && opening && (
        <form
          aria-label="Switch the assistant on"
          className="stack"
          onSubmit={(e) => {
            e.preventDefault();
            if (ticked && !busy) onChange({ enabled: true, acknowledged_text_version: saved.acknowledgement.text_version });
          }}
        >
          <p role="note" className="alert alert-warning pre-line" data-testid="assistant-notice">{saved.acknowledgement.text}</p>
          <label>
            <input type="checkbox" checked={ticked} onChange={(e) => setTicked(e.target.checked)} /> I have read this and accept it for my business
          </label>
          <p className="cluster">
            <BusyButton type="submit" className="rt-primary" busy={busy} disabled={!ticked}>Switch the assistant on</BusyButton>
            <button type="button" /* busy-exempt: not now waits for the request */ className="btn-ghost" disabled={busy} onClick={() => { setOpening(false); setTicked(false); }}>Not now</button>
          </p>
        </form>
      )}
      {!canManage && <p className="hint">Only a business administrator can switch the assistant on or off.</p>}
    </section>
  );
}

export function AssistantCaps({ saved, busy, error, onSave }: { saved: AdvisorSettings; busy: boolean; error: string | null; onSave: (tokens: number) => void }) {
  const [tokens, setTokens] = useState(String(saved.daily_token_cap));
  const valid = /^\d{1,9}$/.test(tokens.trim());
  return (
    <section className="rt-card stack" aria-labelledby="assistant-caps">
      <h2 id="assistant-caps">Daily limit</h2>
      <p className="hint">
        Used today: {saved.tokens_used_today.toLocaleString('en-GB')} tokens. When the limit is reached the automatic summary is
        shown. 0 stops every call.
      </p>
      <form
        className="stack"
        aria-label="Daily limit"
        onSubmit={(e) => {
          e.preventDefault();
          if (valid && !busy) onSave(Number(tokens.trim()));
        }}
      >
        <label htmlFor="assistant-token-cap">Tokens a day</label>
        <input id="assistant-token-cap" inputMode="numeric" value={tokens} onChange={(e) => setTokens(e.target.value)} aria-invalid={!valid} />
        {!valid && <p role="alert" className="field-error">Use a whole number, 0 or more.</p>}
        {error && <p role="alert" className="alert alert-danger">{error}</p>}
        <p><BusyButton type="submit" className="rt-primary" busy={busy} notReady={!valid}>Save the limit</BusyButton></p>
      </form>
    </section>
  );
}

export function AssistantSettingsPage() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const query = useQuery({ queryKey: ['advisor', 'settings'], queryFn: advisor.settings, enabled: canConfigureAdvisor(me) });
  const save = useMutation({
    mutationFn: (change: Parameters<typeof advisor.changeSettings>[1]) => advisor.changeSettings(query.data?.version ?? 0, change),
    onSuccess: (saved) => {
      queryClient.setQueryData(['advisor', 'settings'], saved);
      void queryClient.invalidateQueries({ queryKey: ['advisor', 'brief'] });
    },
    onError: () => void queryClient.invalidateQueries({ queryKey: ['advisor', 'settings'] }),
    meta: { success: 'Settings saved' },
  });
  if (!canConfigureAdvisor(me)) {
    return (
      <main className="rt ln">
        <h1>AI assistant</h1>
        <NoAccess />
      </main>
    );
  }
  const saved = query.data;
  return (
    <main className="rt ln stack">
      <h1>AI assistant</h1>
      {query.error && <p role="alert" className="alert alert-danger">{messageOf(query.error)}</p>}
      {!saved && !query.error && <p className="loading">Loading</p>}
      <div data-tour="assistant-switch">
        {saved && (
          <AssistantSwitch
            saved={saved}
            canManage={(me.permissions ?? []).includes(SETTINGS_MANAGE)}
            busy={save.isPending}
            error={save.error ? messageOf(save.error) : null}
            onChange={(change) => save.mutate(change)}
          />
        )}
      </div>
      <div data-tour="assistant-caps">
        {saved && (
          <AssistantCaps
            key={saved.version}
            saved={saved}
            busy={save.isPending}
            error={save.error ? messageOf(save.error) : null}
            onSave={(tokens) => save.mutate({ daily_token_cap: tokens })}
          />
        )}
      </div>
    </main>
  );
}

export const Route = createLazyRoute('/staff/assistant')({ component: AssistantSettingsPage });
