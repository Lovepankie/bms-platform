import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type CapabilitiesRead } from '../../../api/retail';
import { SkeletonList } from '../../../components/states';
import {
  CAPABILITIES, changeOf, chooseProfile, draftOf, isChange, requestOf, sentencesOf, toggle,
  type CapabilityKey, type Change, type Draft, type Profile,
} from './capabilities-state';
import { Gate, Problem, Success, changeFailureText, useToast } from './ui';

// Retail settings (ADR-033, trade slice 2, issue #192): the administrator chooses the shop or the
// trade profile and switches each feature on or off. The profile is a preset of the switches, not a
// mode: after choosing it every switch can still be changed. A change is read back to the administrator
// before it is saved, and the server audits the old and new values. /me is read again afterwards, so the
// menus follow at once. Switching a feature off keeps what was recorded with it.

const PROFILES: { value: Profile; label: string; hint: string }[] = [
  { value: 'shop', label: 'Shop', hint: 'One or more shops that sell to customers over the counter. Nothing extra is switched on.' },
  { value: 'trade', label: 'Trade', hint: 'Stores that feed a shop, packs, price lists, customer accounts, returns and received transfers.' },
];

export function ProfileChoice({ draft, disabled, onChoose }: { draft: Draft; disabled?: boolean; onChoose: (profile: Profile) => void }) {
  return (
    <fieldset data-tour="retail-profile">
      <legend>What kind of business is this?</legend>
      {PROFILES.map((p) => (
        <label key={p.value}>
          <input type="radio" name="retail-profile" checked={draft.profile === p.value} disabled={disabled} onChange={() => onChoose(p.value)} />
          <span>
            <strong>{p.label}</strong>
            <span className="hint"> {p.hint}</span>
          </span>
        </label>
      ))}
      <p className="hint">Choosing one switches on its features below. You can still change each one.</p>
    </fieldset>
  );
}

export function FlagList({ draft, saved, disabled, onToggle }: { draft: Draft; saved: Draft; disabled?: boolean; onToggle: (key: CapabilityKey, on: boolean) => void }) {
  return (
    <ul className="stack" aria-label="Features" data-tour="retail-capabilities">
      {CAPABILITIES.map((c) => (
        <li key={c.key} className="rt-card" data-tour={`retail-cap-${c.key}`}>
          <label>
            <input type="checkbox" checked={draft.flags[c.key]} disabled={disabled} onChange={(e) => onToggle(c.key, e.target.checked)} />
            <span>
              <strong>{c.label}</strong>
              <span className="hint"> {c.hint}</span>
            </span>
          </label>
          {!c.ready && <p className="hint">The pages for this feature are not released yet. Switching it on changes nothing you can see today.</p>}
          {draft.flags[c.key] !== saved.flags[c.key] && <p className="badge badge-warning">{draft.flags[c.key] ? 'Will be switched on' : 'Will be switched off'}</p>}
        </li>
      ))}
    </ul>
  );
}

/** What saving will change, in words, with the two buttons that confirm or go back. */
export function ChangeReview({ change, busy, onConfirm, onBack }: { change: Change; busy?: boolean; onConfirm: () => void; onBack: () => void }) {
  return (
    <section className="rt-card" aria-label="Check your changes">
      <h2>Check your changes</h2>
      <ul>
        {sentencesOf(change).map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>
      <p className="hint">The change takes effect on the next page everyone opens. It is recorded with your name.</p>
      <p className="cluster">
        <button type="button" className="rt-primary" disabled={busy} onClick={onConfirm}>{busy ? 'Saving' : 'Confirm'}</button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={onBack}>Back</button>
      </p>
    </section>
  );
}

function Editor({ read, onSaved, onEdit }: { read: CapabilitiesRead; onSaved: () => void; onEdit: () => void }) {
  const queryClient = useQueryClient();
  const saved = draftOf(read.capabilities);
  const [draft, setDraft] = useState<Draft>(saved);
  const [reviewing, setReviewing] = useState(false);
  const change = changeOf(saved, draft);

  const save = useMutation({
    mutationFn: () => retail.changeCapabilities(requestOf(change), read.version),
    onSuccess: (next) => {
      queryClient.setQueryData(['retail', 'capabilities'], next);
      // The menus and tours read /me, so it is read again.
      void queryClient.invalidateQueries({ queryKey: ['me'] });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'branch-roles'] });
      setDraft(draftOf(next.capabilities));
      setReviewing(false);
      onSaved();
    },
    onError: () => setReviewing(false),
  });

  return (
    <div className="stack">
      <Problem error={save.error && new Error(changeFailureText(save.error, queryClient))} />
      {reviewing ? (
        <ChangeReview change={change} busy={save.isPending} onConfirm={() => save.mutate()} onBack={() => setReviewing(false)} />
      ) : (
        <>
          <ProfileChoice draft={draft} onChoose={(p) => { onEdit(); setDraft(chooseProfile(draft, p)); }} />
          <FlagList draft={draft} saved={saved} onToggle={(k, on) => { onEdit(); setDraft(toggle(draft, k, on)); }} />
          <p className="cluster">
            <button type="button" className="rt-primary" disabled={!isChange(change)} onClick={() => setReviewing(true)} data-tour="retail-settings-save">
              Review changes
            </button>
            {isChange(change) && <button type="button" className="btn-ghost" onClick={() => setDraft(saved)}>Undo</button>}
          </p>
        </>
      )}
    </div>
  );
}

/**
 * While customer returns are on and the business has set no approval threshold for them, one administrator can refund cash up to
 * the whole of a sale with no second person. That is the default on purpose (the system invents no amount), so it is said here.
 */
export function ReturnsThresholdWarning({ capabilities }: { capabilities: CapabilitiesRead['capabilities'] }) {
  if (capabilities.returns !== true || (capabilities.returns_approval_threshold_minor ?? 0) > 0) return null;
  return (
    <p role="note" className="alert alert-warning" data-testid="returns-no-threshold">
      Customer returns have no approval limit. Any administrator can refund up to the whole of a sale, and void a return, with no second person. To ask for a second
      administrator above an amount, set the approval limit for customer returns in the business settings.
    </p>
  );
}

export function RetailSettings() {
  const read = useQuery({ queryKey: ['retail', 'capabilities'], queryFn: () => retail.capabilities() });
  const [saved, setSaved] = useState(false);
  const { show, toast } = useToast();
  return (
    <Gate screen="settings" title="Retail settings">
      {toast}
      {saved && <Success>Saved. The menus now follow your choice.</Success>}
      <p>Choose what kind of business this is, and which features to use. Every feature starts off.</p>
      {read.isPending && <SkeletonList label="Loading settings" />}
      <Problem error={read.error} />
      {read.data && <ReturnsThresholdWarning capabilities={read.data.capabilities} />}
      {/* Keyed by the version, so a fresh read after someone else's change starts from what is saved. */}
      {read.data && <Editor key={read.data.version} read={read.data} onSaved={() => { setSaved(true); show('Saved'); }} onEdit={() => setSaved(false)} />}
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/settings')({ component: RetailSettings });
