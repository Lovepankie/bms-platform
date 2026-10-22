import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { UsersError, users } from '../../../api/users';
import { useShellBrand } from '../../../app/branding';
import { useStaff } from '../context';
import { LinkBox, type ShownLink } from './link-box';
import { RolePick } from './role-pick';
import { WhoCanDoWhat } from './who-can-do-what';
import { ADD_ONS, TILL, canManageUsers, emptyInvite, hasProblems, inviteBody, inviteProblems, offeredChoices, purchasingOffered, roleChoices, type InviteDraft, type InviteProblems } from './state';

// Invite a staff user (issue #319; FR-IAM-01). Phone first: most staff are reached on a phone, so the phone box comes before the
// email and either one is enough. After the invitation the one-time link is shown once, in a box with Copy and WhatsApp buttons.

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');

export function InviteScreen() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const brand = useShellBrand();
  const allowed = canManageUsers(me);
  const roles = useQuery({ queryKey: ['staff-roles'], queryFn: users.roles, enabled: allowed });
  const branches = useQuery({ queryKey: ['staff-branches'], queryFn: users.branches, enabled: allowed });
  // Only the Owner invites an administrator (ADR-062): the staff list says whether the business has an Owner.
  const people = useQuery({ queryKey: ['staff-users'], queryFn: users.list, enabled: allowed });
  const [draft, setDraft] = useState<InviteDraft>(emptyInvite);
  const [touched, setTouched] = useState(false);
  const [busy, setBusy] = useState(false);
  // A second tap before the first answer would invite twice; a ref closes that gap before React re-renders.
  const sending = useRef(false);
  const [error, setError] = useState<string | null>(null);
  // The person may already be on the staff (a lost answer, or a second tap): point at the list, where Send a new link is.
  const [lookInList, setLookInList] = useState(false);
  const [link, setLink] = useState<ShownLink | null>(null);

  if (!allowed) {
    return (
      <main className="rt ln">
        <h1>Invite someone</h1>
        <p className="empty-state">Only an administrator can invite staff. <Link to="/staff/users">Back to the staff list</Link></p>
      </main>
    );
  }

  const problems = inviteProblems(draft);
  // A business with the retail module calls its branches shops and stores.
  const retail = !me.modules || me.modules.includes('retail');
  const set = (patch: Partial<InviteDraft>) => setDraft((d) => ({ ...d, ...patch }));

  async function submit() {
    setTouched(true);
    if (hasProblems(problems) || sending.current) return;
    sending.current = true;
    setBusy(true);
    setError(null);
    setLookInList(false);
    try {
      const invited = await users.invite(inviteBody(draft));
      void queryClient.invalidateQueries({ queryKey: ['staff-users'] });
      void queryClient.invalidateQueries({ queryKey: ['setup-users'] });
      setLink({
        name: invited.user?.full_name ?? draft.fullName.trim(),
        phone: invited.user?.phone_e164 ?? null,
        url: invited.invitation?.url ?? '',
        expiresAt: invited.invitation?.expires_at,
        kind: 'invited',
      });
      setDraft(emptyInvite());
      setTouched(false);
    } catch (e) {
      setError(messageOf(e));
      const code = e instanceof UsersError ? e.code : undefined;
      if (code === 'network' || code === 'duplicate_phone' || code === 'duplicate_email') {
        setLookInList(true);
        // The invitation may have been made before the answer was lost: have the list ready and current.
        void queryClient.invalidateQueries({ queryKey: ['staff-users'] });
      }
    } finally {
      sending.current = false;
      setBusy(false);
    }
  }

  if (link) {
    return (
      <main className="rt ln stack">
        <h1>Invite someone</h1>
        <LinkBox link={link} business={brand.name} onDone={() => setLink(null)} doneLabel="I have sent it, invite another person" />
        <p><Link to="/staff/users">Back to the staff list</Link></p>
      </main>
    );
  }

  const shown = (key: keyof InviteProblems) => (touched ? problems[key] : undefined);
  return (
    <main className="rt ln stack">
      <h1>Invite someone</h1>
      <p>Add a person to your staff. They get a link to choose their own password; what they can see and do follows the role you give.</p>
      {roles.isError && <p role="alert" className="alert alert-danger">{messageOf(roles.error)}</p>}
      {branches.isError && <p role="alert" className="alert alert-warning">The branches could not be loaded, so you can only choose every branch.</p>}
      <form
        className="rt-card form-stack"
        aria-label="Invite a staff user"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void submit();
        }}
      >
        <label htmlFor="invite-name">Full name</label>
        <input id="invite-name" data-tour="invite-name" value={draft.fullName} maxLength={120} autoComplete="off" aria-invalid={Boolean(shown('fullName'))} onChange={(e) => set({ fullName: e.target.value })} />
        {shown('fullName') && <p role="alert" className="field-error">{shown('fullName')}</p>}

        <label htmlFor="invite-phone">Phone number</label>
        <input id="invite-phone" data-tour="invite-phone" type="tel" inputMode="tel" autoComplete="off" placeholder="0712 345 678" value={draft.phone} aria-invalid={Boolean(problems.phone)} onChange={(e) => set({ phone: e.target.value })} />
        {draft.phone.trim() !== '' && problems.phone && <p role="alert" className="field-error">{problems.phone}</p>}
        <p className="field-hint">A Uganda mobile number. Either a phone or an email is enough.</p>

        <label htmlFor="invite-email">Email address (if they have one)</label>
        <input id="invite-email" type="email" inputMode="email" autoComplete="off" value={draft.email} aria-invalid={Boolean(problems.email)} onChange={(e) => set({ email: e.target.value })} />
        {draft.email.trim() !== '' && problems.email && <p role="alert" className="field-error">{problems.email}</p>}
        {shown('contact') && <p role="alert" className="field-error">{shown('contact')}</p>}

        <div data-tour="invite-role">
        <RolePick
          roles={offeredChoices(roleChoices(roles.data ?? [], me.modules), me, people.data ?? null).filter((r) => !(ADD_ONS as readonly string[]).includes(r.key))}
          branches={branches.data ?? []}
          roleKey={draft.roleKey}
          branchIds={draft.branchIds}
          onChange={(next) => set(next)}
          problem={shown('roleKey')}
          retail={retail}
          showMissing={touched}
        />
        </div>
        {purchasingOffered(draft.roleKey, roleChoices(roles.data ?? [], me.modules)) && (
          <label className="check">
            <input type="checkbox" checked={draft.purchasing} onChange={(e) => set({ purchasing: e.target.checked })} />
            Can also record purchases and restocks (with the buying prices), in the same shops and stores
          </label>
        )}
        {purchasingOffered(draft.roleKey, roleChoices(roles.data ?? [], me.modules), TILL) && (
          <label className="check">
            <input type="checkbox" checked={draft.till === true} onChange={(e) => set({ till: e.target.checked })} />
            Can close the till and do the banking, in the same shops and stores
          </label>
        )}
        <WhoCanDoWhat roles={roles.data ?? []} modules={me.modules} />

        {error && <p role="alert" className="alert alert-danger">{error}</p>}
        {error && lookInList && (
          <p className="hint">
            Did you just invite this person? Open <Link to="/staff/users">the staff list</Link>: if they are there, tap Send a new link next to their name.
          </p>
        )}
        <p className="cluster">
          <button type="submit" className="rt-primary" data-tour="invite-send" disabled={busy || roles.isPending}>{busy ? 'Inviting' : 'Invite'}</button>
          <Link to="/staff/users" className="btn-ghost btn">Cancel</Link>
        </p>
      </form>
    </main>
  );
}

export const Route = createLazyRoute('/staff/users/new')({ component: InviteScreen });
