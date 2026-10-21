import { useId } from 'react';
import type { StaffBranch } from '../../../api/users';
import { EVERY_BRANCH, TENANT_ADMIN, branchUnavailable, needsBranches, type RoleChoice } from './state';

// One role and, unless it is the administrator, the branches it applies in (FR-IAM-01, ADR-017, ADR-055). The administrator works
// in every branch, so no branch is asked for; any other role is ticked for the branches the person works in, or for every one. The
// server scopes everything the role does to the ticked branches. Taking off the last branch leaves nothing chosen, which cannot be
// saved, so it never turns into every branch. A business with the retail module calls its branches shops and stores.

export function RolePick({ roles, branches, roleKey, branchIds, onChange, disabled, label = 'What will this person do?', problem, retail = true, showMissing = false }: {
  roles: RoleChoice[];
  branches: StaffBranch[];
  roleKey: string;
  /** [EVERY_BRANCH] for every branch; empty means none chosen yet. */
  branchIds: string[];
  onChange: (next: { roleKey: string; branchIds: string[] }) => void;
  disabled?: boolean;
  label?: string;
  problem?: string;
  /** The business has the retail module: say shops and stores. */
  retail?: boolean;
  /** Say that a branch is still to be chosen (after a first try to send). */
  showMissing?: boolean;
}) {
  const id = useId();
  const chosen = roles.find((r) => r.key === roleKey);
  const open = branches.filter((b) => b.id && (b.status ?? 'active') === 'active');
  const every = branchIds.includes(EVERY_BRANCH);
  // A branch the person has that is closed now (or gone from the list): shown ticked, so it can be taken off before saving.
  const gone = branchIds.filter((b) => branchUnavailable(b, branches));
  const toggle = (branch: string, on: boolean) => {
    const rest = branchIds.filter((b) => b !== branch && b !== EVERY_BRANCH);
    onChange({ roleKey, branchIds: on ? [...rest, branch] : rest });
  };
  const all = retail ? 'Every shop and store' : 'Every branch';
  const name = (b: StaffBranch) => `${b.name ?? b.code}${b.code && b.name ? ` (${b.code})` : ''}${retail && b.sells_goods === false ? ', a store' : ''}`;
  return (
    <div className="stack">
      <label htmlFor={`${id}-role`}>{label}</label>
      <select id={`${id}-role`} value={roleKey} disabled={disabled} aria-invalid={Boolean(problem)} onChange={(e) => onChange({ roleKey: e.target.value, branchIds: e.target.value === TENANT_ADMIN ? [] : branchIds })}>
        <option value="">Choose a role</option>
        {roles.map((r) => <option key={r.key} value={r.key}>{r.label}</option>)}
      </select>
      {problem && <p role="alert" className="field-error">{problem}</p>}
      {chosen && <p className="hint">{chosen.about}</p>}
      {chosen && chosen.key !== TENANT_ADMIN && (
        <fieldset className="stack">
          <legend>Where does this person work?</legend>
          <p className="hint">{retail ? 'Tick the shops and stores.' : 'Tick the branches.'} They see and do this only there.</p>
          <label className="check">
            <input type="checkbox" checked={every} disabled={disabled} onChange={(e) => onChange({ roleKey, branchIds: e.target.checked ? [EVERY_BRANCH] : [] })} />
            {all}
          </label>
          {open.map((b) => (
            <label key={b.id} className="check">
              <input type="checkbox" checked={!every && branchIds.includes(b.id ?? '')} disabled={disabled} onChange={(e) => toggle(b.id ?? '', e.target.checked)} />
              {name(b)}
            </label>
          ))}
          {gone.map((g) => {
            const closed = branches.find((b) => b.id === g);
            return (
              <label key={g} className="check">
                <input type="checkbox" checked disabled={disabled} onChange={() => toggle(g, false)} />
                {closed ? `${closed.name ?? closed.code} (closed)` : 'A branch that is closed'}
              </label>
            );
          })}
          {gone.length > 0 && <p role="alert" className="field-error">This branch is closed. Take it off, and tick another one or every one, before you save.</p>}
          {gone.length === 0 && showMissing && needsBranches(roleKey, branchIds) && <p role="alert" className="field-error">Tick where this person works, or every one.</p>}
        </fieldset>
      )}
      {chosen?.key === TENANT_ADMIN && <p className="hint">An administrator works everywhere and sees everything, profit and costs included.</p>}
    </div>
  );
}
