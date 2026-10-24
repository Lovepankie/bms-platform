import { Link } from '@tanstack/react-router';

// The one empty list (#520, docs/ui-rules.md rule 5): one plain line saying what is missing, and at most one action that
// fills it, centred. No illustration and no second paragraph: the screen around it already says where the person is. The
// look is .empty-line in src/app/ui/kit.css.

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
