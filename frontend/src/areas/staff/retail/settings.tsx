import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { retail, type CapabilitiesRead } from '../../../api/retail';
import { SkeletonList } from '../../../components/states';
import {
  CAPABILITIES, PROFILE_NAMES, changeOf, chooseProfile, draftOf, isChange, requestOf, sentencesOf, toggle,
  type CapabilityKey, type Change, type Draft, type Profile,
} from './capabilities-state';
import { CreditRecognitionSetting } from './credit-recognition';
import { PurchasingSettings } from './purchasing-settings';
import { SlowMoverSetting } from './slow-movers';
import { Gate, Problem, Success, changeFailureText, useToast } from './ui';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Hint } from '../../../app/ui/Hint';

// Retail settings (ADR-033, trade slice 2, issue #192; issue #424, ADR-092): the administrator says what kind of business this
// is, named by what the owner has, and switches each feature on or off. The kind is a preset of the switches, not a mode: after
// choosing it every switch can still be changed. A change is read back to the administrator before it is saved, and the server
// audits the old and new values. /me is read again afterwards, so the menus follow at once. Switching a feature off keeps what
// was recorded with it.

const PROFILES: { value: Profile; hint: string }[] = [
  { value: 'shops', hint: 'One or more shops that sell over the counter.' },
  { value: 'stores_and_shops', hint: 'Stores that receive stock and send it to your shops. Any number of each.' },
  {
    value: 'wholesale',
    hint: 'You sell in bulk to other shops: price lists and customer prices, quotations, invoices and delivery notes, credit to shops, packs and purchase orders.',
  },
  { value: 'wholesale_and_shops', hint: 'You sell in bulk to other shops, and over the counter in your own shops.' },
];

export function ProfileChoice({ draft, disabled, onChoose }: { draft: Draft; disabled?: boolean; onChoose: (profile: Profile) => void }) {
  return (
    <fieldset data-tour="retail-profile">
      <legend>What kind of business is this?</legend>
      {PROFILES.map((p) => (
        <label key={p.value} className="rt-choice">
          <input type="radio" name="retail-profile" value={p.value} checked={draft.profile === p.value} disabled={disabled} onChange={() => onChoose(p.value)} />
          <span>
            <strong>{PROFILE_NAMES[p.value]}</strong>
            <span className="hint"> {p.hint}</span>
          </span>
        </label>
      ))}
      <p data-tour="retail-profile-branches">
        Each branch is set as a store or a shop under <Link to="/staff/setup/branches">Branches</Link>. A store keeps stock and does not sell.
      </p>
      <Hint>Choosing one switches its features below on or off. You can still change each one.</Hint>
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
          {!c.ready && <Hint full>The pages for this feature are not released yet. Switching it on changes nothing you can see today.</Hint>}
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
      <Hint full>The change takes effect on the next page everyone opens. It is recorded with your name.</Hint>
      <p className="cluster">
        <BusyButton type="button" className="rt-primary" busy={busy === true} onClick={onConfirm}>Confirm</BusyButton>
        <button type="button" /* busy-exempt: back waits for the save */ className="btn-ghost" disabled={busy} onClick={onBack}>Back</button>

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
 * the whole of a sale with no second person. That is the default on purpose (the system invents no amount), so it is said here, as
 * one neutral Hint line (issue #575: no status colour on a normal screen); the full explanation is behind the Hint's info button.
 * No screen sets the limit yet (the approval limits screen is #343), so the Hint offers no link: a link that leads to no field
 * would promise an action it cannot keep. Add the link when #343 ships.
 */
export function ReturnsThresholdWarning({ capabilities }: { capabilities: CapabilitiesRead['capabilities'] }) {
  if (capabilities.returns !== true || (capabilities.returns_approval_threshold_minor ?? 0) > 0) return null;
  return (
    <div role="note" data-testid="returns-no-threshold">
      <Hint>
        Returns need no second administrator yet. With no approval limit, any administrator can refund up to the whole of a sale, and void
        a return, alone. The limit counts the returns of one sale together, not a person&apos;s returns across sales, so many small returns
        on different sales still need no second administrator.
      </Hint>
    </div>
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
      <p>Say what kind of business this is, and which features to use. Every feature starts off.</p>
      {read.isPending && <SkeletonList label="Loading settings" />}
      <Problem error={read.error} />
      {read.data && <ReturnsThresholdWarning capabilities={read.data.capabilities} />}
      {/* Keyed by the version, so a fresh read after someone else's change starts from what is saved. */}
      {read.data && <Editor key={read.data.version} read={read.data} onSaved={() => { setSaved(true); show('Saved', 'success'); }} onEdit={() => setSaved(false)} />}
      {read.data && <CreditRecognitionSetting key={`credit-${read.data.version}`} read={read.data} onSaved={() => show('Saved', 'success')} />}
      {read.data && <SlowMoverSetting key={`slow-${read.data.version}`} read={read.data} onSaved={() => show('Saved', 'success')} />}
      <PurchasingSettings />
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/settings')({ component: RetailSettings });
