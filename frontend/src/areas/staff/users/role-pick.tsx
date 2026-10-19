import { useId } from 'react';
import type { StaffBranch } from '../../../api/users';
import { TENANT_ADMIN, branchUnavailable, type RoleChoice } from './state';

// One role and, unless it is the administrator, the branch it applies in (FR-IAM-01, ADR-017). The administrator covers every
// branch, so no branch is asked for; any other role may be limited to one branch, or cover every branch.

export function RolePick({ roles, branches, roleKey, branchId, onChange, disabled, label = 'What will this person do?', problem }: {
  roles: RoleChoice[];
  branches: StaffBranch[];
  roleKey: string;
  branchId: string;
  onChange: (next: { roleKey: string; branchId: string }) => void;
  disabled?: boolean;
  label?: string;
  problem?: string;
}) {
  const id = useId();
  const chosen = roles.find((r) => r.key === roleKey);
  const gone = branchUnavailable(branchId, branches);
  const closed = branches.find((b) => b.id === branchId);
  const open = branches.filter((b) => b.id && (b.status ?? 'active') === 'active');
  return (
    <div className="stack">
      <label htmlFor={`${id}-role`}>{label}</label>
      <select id={`${id}-role`} value={roleKey} disabled={disabled} aria-invalid={Boolean(problem)} onChange={(e) => onChange({ roleKey: e.target.value, branchId: e.target.value === TENANT_ADMIN ? '' : branchId })}>
        <option value="">Choose a role</option>
        {roles.map((r) => <option key={r.key} value={r.key}>{r.label}</option>)}
      </select>
      {problem && <p role="alert" className="field-error">{problem}</p>}
      {chosen && <p className="hint">{chosen.about}</p>}
      {chosen && chosen.key !== TENANT_ADMIN && (
        <>
          <label htmlFor={`${id}-branch`}>Where?</label>
          <select id={`${id}-branch`} value={branchId} disabled={disabled} onChange={(e) => onChange({ roleKey, branchId: e.target.value })}>
            <option value="">Every branch</option>
            {gone && <option value={branchId}>{closed ? `${closed.name ?? closed.code} (closed)` : 'A branch that is closed'}</option>}
            {open.map((b) => <option key={b.id} value={b.id}>{b.name ?? b.code}{b.code && b.name ? ` (${b.code})` : ''}</option>)}
          </select>
          {gone && <p role="alert" className="field-error">This branch is closed. Pick another branch, or every branch, before you save.</p>}
        </>
      )}
      {chosen?.key === TENANT_ADMIN && <p className="hint">An administrator works in every branch.</p>}
    </div>
  );
}
