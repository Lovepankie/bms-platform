import { Link } from '@tanstack/react-router';
import type { Me } from '../../../api/client';
import { icons } from '../../../components/icons';
import { articleForScreen } from '../../../help/corpus';

// The small How do I? link of a screen (issue #598): drawn by the staff layout under the breadcrumbs when an article this person
// may read names the page among its screens, and opening that article. Nothing shows on a page with no article.

export function HowDoILink({ pathname, me }: { pathname: string; me: Me }) {
  const article = pathname.startsWith('/staff/help') ? undefined : articleForScreen(pathname, me);
  if (!article) return null;
  return (
    <p className="how-do-i">
      <Link to="/staff/help/$articleId" params={{ articleId: article.id }} aria-label={`How do I? ${article.title}`} data-tour="how-do-i">
        {icons.help}
        How do I?
      </Link>
    </p>
  );
}
