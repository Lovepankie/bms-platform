import type { StaffRole } from '../../../api/users';
import { whoCanDoWhat } from './state';

// "Who can do what" on the Users screen and the invitation (issue #399, ADR-055): one plain line per role the business can give,
// taken from the same words as the role pick-list, so the card cannot say something the roles do not.

export function WhoCanDoWhat({ roles, modules }: { roles: StaffRole[]; modules?: string[] | null }) {
  const lines = whoCanDoWhat(roles, modules);
  return (
    <details className="rt-card" data-tour="who-can-do-what">
      <summary>Who can do what</summary>
      {lines.length === 0 ? (
        <p className="hint">The roles are loading.</p>
      ) : (
        <ul>
          {lines.map((l) => (
            <li key={l.title}><strong>{l.title}</strong>: {l.about}</li>
          ))}
        </ul>
      )}
    </details>
  );
}
