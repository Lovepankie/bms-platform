import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { retail, type BranchRole } from '../../../api/retail';
import type { ReactNode } from 'react';
import { SkeletonList } from '../../../components/states';
import { ScreenLink } from './screen-link';
import { Gate, Problem, changeFailureText } from './ui';

// Branch roles (ADR-033, spec section 2): which branches are shops and which are both.
// Only a shop or a branch that is both sells to customers; every branch can be restocked and can send
// or receive stock. A store is the branch's Store mark on Branches (ADR-048, ADR-104): it is shown here
// and not chosen. The screen exists only while the branch_roles feature is on (see SCREEN_CAPABILITY).

type Role = 'shop' | 'both';

const ROLES: { value: Role; label: string }[] = [
  { value: 'shop', label: 'Shop: sells to customers' },
  { value: 'both', label: 'Both: keeps stock and sells' },
];

export function RoleRow({ row, busy, onChange, extra }: { row: BranchRole; busy?: boolean; onChange: (role: Role) => void; extra?: ReactNode }) {
  const id = `role-${row.branch_id}`;
  if (row.role === 'store') {
    return (
      <li className="rt-card">
        <span>{row.name ?? row.code}</span>
        {extra}
        <p>Store: keeps stock, does not sell. <Link to="/staff/setup/branches">Change it on Branches</Link></p>
      </li>
    );
  }
  return (
    <li className="rt-card">
      <label htmlFor={id}>{row.name ?? row.code}</label>
      {extra}
      <select id={id} value={row.role === 'shop' ? 'shop' : 'both'} disabled={busy} onChange={(e) => onChange(e.target.value as Role)}>
        {ROLES.map((r) => (
          <option key={r.value} value={r.value}>{r.label}</option>
        ))}
      </select>
    </li>
  );
}

export function BranchRoles() {
  const queryClient = useQueryClient();
  const roles = useQuery({ queryKey: ['retail', 'branch-roles'], queryFn: () => retail.branchRoles() });
  const set = useMutation({
    mutationFn: (v: { branchId: string; role: Role }) => retail.setBranchRole(v.branchId, v.role),
    onSettled: () => void queryClient.invalidateQueries({ queryKey: ['retail', 'branch-roles'] }),
  });
  return (
    <Gate screen="branchRoles" title="Branch roles">
      <p data-tour="branch-roles-help">A store keeps stock and feeds the shop. Only a shop sells to customers. Mark a store on Branches.</p>
      {roles.isPending && <SkeletonList label="Loading branches" />}
      <Problem error={roles.error ?? (set.error && new Error(changeFailureText(set.error, queryClient)))} />
      <ul className="stack" data-tour="retail-roles">
        {(roles.data ?? []).map((r) => (
          <RoleRow key={r.branch_id} row={r} busy={set.isPending} extra={<p><ScreenLink screen="stockByLocation" to="/staff/retail/stock-by-location" label={`See the stock of ${r.name ?? r.code}`}>Stock</ScreenLink></p>} onChange={(role) => set.mutate({ branchId: r.branch_id ?? '', role })} />
        ))}
      </ul>
    </Gate>
  );
}

export const Route = createLazyRoute('/staff/retail/branch-roles')({ component: BranchRoles });
