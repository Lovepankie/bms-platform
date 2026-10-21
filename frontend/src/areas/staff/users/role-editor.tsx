import { useRef, useState } from 'react';
import type { RoleAssignmentRequest, StaffBranch, StaffUser } from '../../../api/users';
import { RolePick } from './role-pick';
import { TENANT_ADMIN, assignmentsFor, branchUnavailable, needsBranches, roleRows, type RoleChoice, type RoleRow } from './state';

// Change what a person may do: their roles, each with the shops and stores it applies in unless it is the administrator (PUT
// /users/{id}/roles replaces the whole list, one assignment per branch, ADR-055). The last active administrator cannot be changed away;
// the server refuses it and the words arrive here.

type Row = RoleRow;

export function RoleEditor({ user, self = false, roles, branches, onSave, onCancel, retail = true }: {
  user: StaffUser;
  /** The person changing the roles is the person whose roles they are. */
  self?: boolean;
  roles: RoleChoice[];
  branches: StaffBranch[];
  onSave: (roles: RoleAssignmentRequest[]) => Promise<string | null>;
  onCancel: () => void;
  /** The business has the retail module: its branches are shops and stores. */
  retail?: boolean;
}) {
  const [rows, setRows] = useState<Row[]>(() => roleRows(user));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const saving = useRef(false);
  const [confirming, setConfirming] = useState(false);
  const ready = rows.length > 0 && rows.every((r) => r.roleKey !== '') && !rows.some((r) => r.branchIds.some((b) => branchUnavailable(b, branches)) || needsBranches(r.roleKey, r.branchIds));
  // Taking the administrator role off yourself ends your right to this page's actions the moment it is saved.
  const droppingOwnAdmin = self && (user.roles ?? []).some((r) => r.role_key === TENANT_ADMIN) && !rows.some((r) => r.roleKey === TENANT_ADMIN);

  async function save() {
    if (!ready || saving.current) return;
    if (droppingOwnAdmin && !confirming) {
      setConfirming(true);
      return;
    }
    saving.current = true;
    setBusy(true);
    setError(null);
    const failure = await onSave(rows.flatMap((r) => assignmentsFor(r.roleKey, r.branchIds)));
    saving.current = false;
    setBusy(false);
    if (failure) {
      setError(failure);
      setConfirming(false);
    }
  }

  return (
    <form
      className="rt-card form-stack"
      aria-label={`Change the roles of ${user.full_name ?? 'this person'}`}
      onSubmit={(e) => {
        e.preventDefault();
        void save();
      }}
    >
      <h3>Roles of {user.full_name}</h3>
      {rows.map((row, i) => (
        <div key={i} className="stack">
          <RolePick
            roles={roles}
            branches={branches}
            roleKey={row.roleKey}
            branchIds={row.branchIds}
            retail={retail}
            showMissing
            label={rows.length > 1 ? `Role ${i + 1}` : 'Role'}
            disabled={busy}
            onChange={(next) => { setConfirming(false); setRows((all) => all.map((r, j) => (j === i ? next : r))); }}
          />
          {rows.length > 1 && (
            <button type="button" className="btn-ghost btn-sm" disabled={busy} onClick={() => setRows((all) => all.filter((_, j) => j !== i))}>
              Remove this role
            </button>
          )}
        </div>
      ))}
      {!rows.some((r) => r.roleKey === TENANT_ADMIN) && (
        <p>
          <button type="button" className="btn-ghost btn-sm" disabled={busy} onClick={() => setRows((all) => [...all, { roleKey: '', branchIds: [] }])}>
            Add another role
          </button>
        </p>
      )}
      {error && <p role="alert" className="alert alert-danger">{error}</p>}
      {confirming && (
        <p role="alert" className="alert alert-warning">
          You are taking the administrator role off <strong>your own</strong> account. You will no longer be able to invite people, change roles or
          deactivate anyone, and only another administrator can give the role back. Tap the button again to go ahead.
        </p>
      )}
      <p className="cluster">
        <button type="submit" className={confirming ? 'btn-danger' : 'rt-primary'} disabled={!ready || busy}>
          {busy ? 'Saving' : confirming ? 'Yes, remove my administrator role' : 'Save the roles'}
        </button>
        <button type="button" className="btn-ghost" disabled={busy} onClick={onCancel}>Cancel</button>
      </p>
    </form>
  );
}
