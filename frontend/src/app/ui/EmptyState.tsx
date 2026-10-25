import { Link, useRouter } from '@tanstack/react-router';

// The one empty list (#520, docs/ui-rules.md rule 5): one plain line saying what is missing, and at most one action that
// fills it, centred. No illustration, no border and no second paragraph (issue #526: the dashed box read as an unfinished
// wireframe): the screen around it already says where the person is. The look is .empty-line in src/app/ui/kit.css.

export type EmptyAction = { label: string; to: string } | { label: string; onClick: () => void };

export function EmptyState({ text, action }: { text: string; action?: EmptyAction }) {
  return (
    <div className="empty-line">
      <p>{text}</p>
      {action &&
        ('to' in action ? (
          <Link className="btn" to={action.to as never}>
            {action.label}
          </Link>
        ) : (
          <button type="button" onClick={action.onClick}>
            {action.label}
          </button>
        ))}
    </div>
  );
}

/**
 * A page or part the session may not open (issue #526): what it cannot open, who can give access, and a way Home. The words
 * name the owner and the administrators, who hold Staff users; the link is a plain one outside a router (a screen test).
 */
export function NoAccess({ what = 'this page' }: { what?: string }) {
  const router = useRouter({ warn: false });
  return (
    <div className="empty-line" role="status">
      <p>
        You do not have access to {what}. The owner or an administrator of the business can give it to you in Staff users.
      </p>
      {router ? (
        <Link className="btn" to="/staff">
          Go to Home
        </Link>
      ) : (
        <a className="btn" href="/staff">
          Go to Home
        </a>
      )}
    </div>
  );
}
