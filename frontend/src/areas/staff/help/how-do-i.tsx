import { Link, useRouterState } from '@tanstack/react-router';
import type { Me } from '../../../api/client';
import { NewDot, useIsNew } from '../../../app/ui/NewBadge';
import { icons } from '../../../components/icons';
import { articleForScreen } from '../../../help/corpus';
import { HELP_FEATURE } from '../menu';
import { useAskEnabled } from './ask';

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

/**
 * The Ask button of the staff top bar (issue #603, round 2), next to Search on every staff screen but /staff/help itself: Ask
 * (named Ask BMS) while the business has Ask BMS on, else How do I?, never Help, which names the top menu's tours sheet (WCAG
 * 3.2.4). It opens /staff/help#ask, where the cursor lands in the Ask BMS question box (the article search while Ask BMS is off),
 * and carries the help page's New dot, said as ", New" in its name, until the person has opened it once. Below 430px only the
 * icon shows (issue #605), so the branch name beside it stays readable; its name stays.
 */
export function AskButton() {
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const ask = useAskEnabled();
  const fresh = useIsNew(HELP_FEATURE);
  if (pathname.replace(/\/$/, '') === '/staff/help') return null;
  const name = ask ? 'Ask BMS' : 'How do I?';
  return (
    <Link to="/staff/help" hash="ask" className="btn btn-ghost btn-icon ask-open" aria-label={fresh ? `${name}, New` : name}>
      {icons.ask}
      <span className="ask-open-word">{ask ? 'Ask' : 'How do I?'}</span>
      <NewDot feature={HELP_FEATURE} />
    </Link>
  );
}
