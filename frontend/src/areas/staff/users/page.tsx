import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { useRef, useState } from 'react';
import { type AdminCredential, resetUserSignIn } from '../../../api/sign-in-reset';
import { type RoleAssignmentRequest, type StaffUser, users } from '../../../api/users';
import { useShellBrand } from '../../../app/branding';
import { Highlight, NoResults, SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { useStaff } from '../context';
import { LinkBox, type ShownLink } from './link-box';
import { RoleEditor } from './role-editor';
import { ResetPanel } from './reset-panel';
import { canManageUsers, canResetSignIn, contactLine, matchesSearch, roleChoices, roleLines, showUsers, statusWords } from './state';

// Staff users (issue #319; FR-IAM-01, FR-IAM-02, FR-IAM-08): who works in the business, what each person may do, and who has not
// joined yet. Anyone who may read the list sees it; an administrator also invites, sends a new link, changes roles and deactivates.
// The one-time link of a resend is shown once, in a box, and is never kept. Reset sign-in (issue #339) makes such a link for an active
// person who forgot their password, after the administrator types a code from their own authenticator app (or their own password).

const messageOf = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');

const BADGE: Record<string, string> = { invited: 'badge badge-warning', active: 'badge badge-success', deactivated: 'badge' };

type Mode = { kind: 'roles' | 'deactivate' | 'reset'; id: string } | null;

export function UsersScreen() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const brand = useShellBrand();
  const allowed = showUsers(me);
  const canManage = canManageUsers(me);
  const search = useLiveSearch();
  const list = useQuery({ queryKey: ['staff-users'], queryFn: users.list, enabled: allowed });
  const roles = useQuery({ queryKey: ['staff-roles'], queryFn: users.roles, enabled: allowed });
  const branches = useQuery({ queryKey: ['staff-branches'], queryFn: users.branches, enabled: allowed });
  const [mode, setMode] = useState<Mode>(null);
  const [link, setLink] = useState<ShownLink | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  // One action at a time: a second tap before the first answer must not send a second request.
  const working = useRef(false);

  if (!allowed) {
    return (
      <main className="rt ln">
        <h1>Staff users</h1>
        <p className="empty-state">You do not have access to this page.</p>
      </main>
    );
  }

  async function act(user: StaffUser, run: () => Promise<string | null>) {
    if (working.current) return;
    working.current = true;
    setBusyId(user.id ?? null);
    setError(null);
    setNotice(null);
    try {
      setNotice(await run());
      await queryClient.invalidateQueries({ queryKey: ['staff-users'] });
      void queryClient.invalidateQueries({ queryKey: ['setup-users'] });
    } catch (e) {
      setError(messageOf(e));
    } finally {
      working.current = false;
      setBusyId(null);
    }
  }

  const resend = (user: StaffUser) =>
    act(user, async () => {
      const made = await users.reissue(user.id ?? '');
      setLink({ userId: user.id, name: user.full_name ?? 'this person', phone: user.phone_e164 ?? null, url: made.url ?? '', expiresAt: made.expires_at, kind: 'again' });
      return null;
    });

  // The link of a reset is held in this screen's state only, like the invitation link, and the proof the administrator typed is not kept.
  async function resetSignIn(user: StaffUser, credential: AdminCredential): Promise<string | null> {
    if (working.current) return null;
    working.current = true;
    setNotice(null);
    try {
      const made = await resetUserSignIn(user.id ?? '', credential);
      setLink({ userId: user.id, name: user.full_name ?? 'this person', phone: user.phone_e164 ?? null, url: made.url, expiresAt: made.expiresAt, kind: 'reset' });
      setMode(null);
      return null;
    } catch (e) {
      return messageOf(e);
    } finally {
      working.current = false;
    }
  }

  const deactivate = (user: StaffUser) =>
    act(user, async () => {
      await users.deactivate(user.id ?? '');
      setMode(null);
      // A link made for this person is of no use now.
      setLink((shownLink) => (shownLink?.userId === user.id ? null : shownLink));
      return `${user.full_name} is deactivated and signed out.`;
    });

  async function saveRoles(user: StaffUser, next: RoleAssignmentRequest[]): Promise<string | null> {
    try {
      await users.setRoles(user.id ?? '', next);
      setMode(null);
      setNotice(`The roles of ${user.full_name} are saved.`);
      // One's own roles decide what this page offers, and they come from /me: read them again.
      if (user.id === me.user_id) await queryClient.invalidateQueries({ queryKey: ['me'] });
      await queryClient.invalidateQueries({ queryKey: ['staff-users'] });
      return null;
    } catch (e) {
      return messageOf(e);
    }
  }

  const all = list.data ?? [];
  const shown = search.term === '' ? all : all.filter((u) => matchesSearch(u, search.term));
  const roleList = roles.data ?? [];
  const branchList = branches.data ?? [];
  const choices = roleChoices(roleList);

  return (
    <main className="rt ln stack">
      <h1>Staff users</h1>
      <p>The people who work in your business, and what each may do. A person still marked Invited has not opened their link yet.</p>
      {canManage ? (
        <p>
          <Link to="/staff/users/new" className="btn rt-primary" data-tour="staff-invite">Invite someone</Link>
        </p>
      ) : (
        <p className="hint">Only an administrator can invite people or change roles.</p>
      )}
      {link && <LinkBox link={link} business={brand.name} onDone={() => setLink(null)} doneLabel="I have sent it" />}
      {notice && <p role="status" className="alert alert-success">{notice}</p>}
      {error && mode?.kind !== 'deactivate' && <p role="alert" className="alert alert-danger">{error}</p>}
      {list.isPending && <p className="loading">Loading your staff</p>}
      {list.isError && <p role="alert" className="alert alert-danger">{messageOf(list.error)}</p>}
      {roles.isError && <p role="alert" className="alert alert-warning">The role names could not be loaded, so roles are shown by their key.</p>}
      {list.data && (
        <>
          <form role="search" className="ln-search" data-tour="staff-search" onSubmit={(e) => { e.preventDefault(); search.flush(); }}>
            <SearchBox id="staff-q" label="Find a person" search={search} fetching={false} count={shown.length} hint="A name, a phone number or an email" />
          </form>
          <SearchResults fetching={search.pending}>
            {all.length === 0 ? (
              <div className="empty-state">
                <p>No staff yet.</p>
                {canManage && <p>Tap Invite someone to add the first person.</p>}
              </div>
            ) : shown.length === 0 ? (
              <NoResults term={search.term} onClear={search.clear} noun="people" />
            ) : (
              <ul className="ln-list stack" data-tour="staff-list">
                {shown.map((u) => (
                  <li key={u.id} className="rt-card stack">
                    <p>
                      <strong><Highlight text={u.full_name ?? ''} term={search.term} /></strong>{' '}
                      <span className={BADGE[u.status ?? ''] ?? 'badge'}>{statusWords(u.status)}</span>
                      {u.id === me.user_id && <span className="hint"> (you)</span>}
                    </p>
                    <p className="hint"><Highlight text={contactLine(u)} term={search.term} /></p>
                    <ul>
                      {roleLines(u, branchList, roleList).map((line) => <li key={line}>{line}</li>)}
                    </ul>
                    {canManage && u.status !== 'deactivated' && mode?.id !== u.id && (
                      <p className="cluster">
                        {u.status === 'invited' && (
                          <button type="button" className="btn-sm" disabled={busyId !== null} onClick={() => void resend(u)}>
                            {busyId === u.id ? 'Making a link' : 'Send a new link'}
                          </button>
                        )}
                        <button type="button" className="btn-sm" disabled={busyId !== null} onClick={() => { setMode({ kind: 'roles', id: u.id ?? '' }); setError(null); }}>
                          Change roles
                        </button>
                        {canResetSignIn(me, u) && (
                          <button type="button" className="btn-sm" disabled={busyId !== null} onClick={() => { setMode({ kind: 'reset', id: u.id ?? '' }); setError(null); }}>
                            Reset sign-in
                          </button>
                        )}
                        {u.id !== me.user_id && (
                          <button type="button" className="btn-ghost btn-sm" disabled={busyId !== null} onClick={() => { setMode({ kind: 'deactivate', id: u.id ?? '' }); setError(null); }}>
                            Deactivate
                          </button>
                        )}
                      </p>
                    )}
                    {mode?.id === u.id && mode?.kind === 'roles' && (
                      <RoleEditor user={u} self={u.id === me.user_id} roles={choices} branches={branchList} onSave={(next) => saveRoles(u, next)} onCancel={() => setMode(null)} />
                    )}
                    {mode?.id === u.id && mode?.kind === 'reset' && (
                      <ResetPanel user={u} mfaEnabled={me.mfa_enabled === true} onSend={(credential) => resetSignIn(u, credential)} onCancel={() => setMode(null)} />
                    )}
                    {mode?.id === u.id && mode?.kind === 'deactivate' && (
                      <div className="alert alert-warning stack" role="group" aria-label={`Deactivate ${u.full_name}`}>
                        {error && <p role="alert" className="alert alert-danger">{error}</p>}
                        <p>Deactivate <strong>{u.full_name}</strong>? They are signed out at once and cannot sign in again. Everything they recorded stays on the books.</p>
                        <p className="cluster">
                          <button type="button" className="btn-danger" disabled={busyId !== null} onClick={() => void deactivate(u)}>
                            {busyId === u.id ? 'Deactivating' : 'Yes, deactivate'}
                          </button>
                          <button type="button" className="btn-ghost" disabled={busyId !== null} onClick={() => setMode(null)}>Keep them</button>
                        </p>
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}
          </SearchResults>
        </>
      )}
    </main>
  );
}

export const Route = createLazyRoute('/staff/users')({ component: UsersScreen });
