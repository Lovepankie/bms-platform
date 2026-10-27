import { onlineManager } from '@tanstack/react-query';
import { Link, createLazyRoute, useRouterState } from '@tanstack/react-router';
import { useEffect, useMemo, useRef, useState } from 'react';
import { Card, ListRow, RowList } from '../../../app/ui/ListRow';
import { markFeatureSeen } from '../../../app/ui/NewBadge';
import { SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { JOBS, articleById, articleVisible, articlesFor, searchArticles, type Article, type Inline } from '../../../help/corpus';
import { useStaff } from '../context';
import { HELP_FEATURE } from '../menu';
import { AskBms, useAskEnabled, useAskStatus } from './ask';

// How do I...? (issue #598, ADR-095): short articles with pictures for the jobs a person does in BMS, under Help and feedback.
// The articles ship with the app (src/help), so the page and its pictures work offline once the app is installed, and nothing is
// sent anywhere while a person searches. A person sees only the articles their permissions allow. "Not answered? Ask us" opens
// Feedback with what they searched for already typed. While the platform has Ask BMS on for the business (issue #20, ADR-096),
// an Ask BMS box above the search, and at the top of each article (issue #603), answers a question from these articles and
// links the one it comes from.

function Words({ runs }: { runs: Inline }) {
  return <>{runs.map((r, i) => (r.strong ? <strong key={i}>{r.text}</strong> : <span key={i}>{r.text}</span>))}</>;
}

/** Opens Feedback as a question, with `text` typed in the message. */
export function AskUs({ text }: { text: string }) {
  const ask = text.trim();
  return (
    <p className="help-ask" data-tour="help-ask">
      <span>Not answered?</span>{' '}
      <Link to="/staff/feedback" search={{ ask: ask === '' ? undefined : ask }} className="btn">
        Ask us
      </Link>
    </p>
  );
}

const row = (a: Article) => <ListRow key={a.id} title={a.title} to={`/staff/help/${a.id}`} />;

export function HowDoIPage() {
  const { me } = useStaff();
  // Opening the page once retires its New badge on this device (issue #603).
  useEffect(() => markFeatureSeen(HELP_FEATURE), []);
  const mine = useMemo(() => articlesFor(me), [me]);
  const search = useLiveSearch({ urlParam: 'q' });
  const found = useMemo(() => searchArticles(mine, search.term), [mine, search.term]);
  const { enabled: askEnabled, known } = useAskStatus();
  const [asked, setAsked] = useState('');
  // Ask BMS said the help has no answer, with its own Ask us right under it (issue #609): one Ask us, not two.
  const [askNotFound, setAskNotFound] = useState(false);
  // The top bar's Ask button opens /staff/help#ask (issue #603): once the server has said whether Ask BMS is on, the cursor goes
  // to the question box, or to the article search while it is off or the phone is offline (a remembered "on" cannot be asked
  // then). Only once per arrival.
  const hash = useRouterState({ select: (state) => state.location.hash });
  const focused = useRef(false);
  useEffect(() => {
    if (hash !== 'ask' || !known || focused.current) return;
    focused.current = true;
    document.querySelector<HTMLElement>(askEnabled && onlineManager.isOnline() ? '.help-ask-bms input' : '#help-q')?.focus();
  }, [hash, known, askEnabled]);
  return (
    <main className="rt ln stack help-page">
      <h1>How do I...?</h1>
      <p>Short answers with pictures, for the jobs you do in BMS. They work without the internet too.</p>
      {askEnabled && <AskBms onAsked={setAsked} onNotFound={setAskNotFound} />}
      <form role="search" data-tour="help-search" onSubmit={(e) => { e.preventDefault(); search.flush(); }}>
        <SearchBox id="help-q" label="What do you want to do?" search={search} fetching={false} count={search.term ? found.length : undefined} hint="For example: move stock, invite, count" />
      </form>
      <SearchResults fetching={search.pending}>
        {search.term ? (
          found.length > 0 ? (
            <Card>
              <RowList label="Answers">{found.map(row)}</RowList>
            </Card>
          ) : (
            <p className="empty-state">No article answers that yet.</p>
          )
        ) : (
          <div className="stack" data-tour="help-groups">
            {JOBS.map((job) => {
              const here = mine.filter((a) => a.job === job.id);
              return here.length === 0 ? null : (
                <Card key={job.id} title={job.label}>
                  <RowList label={job.label}>{here.map(row)}</RowList>
                </Card>
              );
            })}
            {mine.length === 0 && <p className="empty-state">There is no article for your role yet.</p>}
          </div>
        )}
      </SearchResults>
      {(search.text.trim() || !askNotFound) && <AskUs text={search.text.trim() ? search.text : asked} />}
    </main>
  );
}

/** One article with its steps and pictures, or why this person cannot read it. */
export function ArticleView({ articleId }: { articleId: string }) {
  const { me } = useStaff();
  const article = articleById(articleId);
  const allowed = article !== undefined && articleVisible(article, me);
  const askEnabled = useAskEnabled();
  const [askNotFound, setAskNotFound] = useState(false);
  // An article is part of /staff/help: reading one retires the New badge too (issue #603); one not found or for another role is not read.
  useEffect(() => {
    if (allowed) markFeatureSeen(HELP_FEATURE);
  }, [allowed]);
  useCrumbLabel(allowed ? article.title : undefined);
  if (!article || !allowed) {
    return (
      <main className="rt ln stack help-page">
        <h1>How do I...?</h1>
        <p className="empty-state">{article ? 'This article is for another role. Ask your administrator if you need to do this.' : 'This article could not be found.'}</p>
        <p>
          <Link to="/staff/help" className="btn">All the answers</Link>
        </p>
      </main>
    );
  }
  return (
    <main className="rt ln stack help-page">
      <h1>{article.title}</h1>
      {/* A person the article did not answer asks straight away (issue #603). */}
      {askEnabled && <AskBms onNotFound={setAskNotFound} />}
      {article.intro.map((runs, i) => (
        <p key={i} data-tour={i === 0 ? 'help-article-intro' : undefined}>
          <Words runs={runs} />
        </p>
      ))}
      <ol className="help-steps" data-tour="help-article-steps">
        {article.steps.map((step, i) => (
          <li key={i} className="help-step">
            <p>
              <Words runs={step.text} />
            </p>
            {step.picture && <img className="help-shot" src={`/help/${step.picture.file}`} alt={step.picture.caption} width={412} height={900} loading="lazy" />}
          </li>
        ))}
      </ol>
      {article.notes.map((runs, i) => (
        <p key={i} role="note" className="alert alert-info">
          <Words runs={runs} />
        </p>
      ))}
      {!askNotFound && <AskUs text={article.title} />}
      <p>
        <Link to="/staff/help" className="btn btn-ghost">All the answers</Link>
      </p>
    </main>
  );
}

export const Route = createLazyRoute('/staff/help')({ component: HowDoIPage });
function ArticlePage() {
  const { articleId } = ArticleRoute.useParams();
  return <ArticleView key={articleId} articleId={articleId} />;
}

export const ArticleRoute = createLazyRoute('/staff/help/$articleId')({ component: ArticlePage });
