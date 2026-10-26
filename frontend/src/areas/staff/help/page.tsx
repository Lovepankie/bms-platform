import { Link, createLazyRoute } from '@tanstack/react-router';
import { useMemo } from 'react';
import { Card, ListRow, RowList } from '../../../app/ui/ListRow';
import { SearchBox, SearchResults } from '../../../app/ui/SearchBox';
import { useLiveSearch } from '../../../app/ui/useLiveSearch';
import { useCrumbLabel } from '../../../components/breadcrumbs';
import { JOBS, articleById, articleVisible, articlesFor, searchArticles, type Article, type Inline } from '../../../help/corpus';
import { useStaff } from '../context';

// How do I...? (issue #598, ADR-095): short articles with pictures for the jobs a person does in BMS, under Help and feedback.
// The articles ship with the app (src/help), so the page and its pictures work offline once the app is installed, and nothing is
// sent anywhere while a person searches. A person sees only the articles their permissions allow. "Not answered? Ask us" opens
// Feedback with what they searched for already typed.

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
  const mine = useMemo(() => articlesFor(me), [me]);
  const search = useLiveSearch({ urlParam: 'q' });
  const found = useMemo(() => searchArticles(mine, search.term), [mine, search.term]);
  return (
    <main className="rt ln stack help-page">
      <h1>How do I...?</h1>
      <p>Short answers with pictures, for the jobs you do in BMS. They work without the internet too.</p>
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
      <AskUs text={search.text} />
    </main>
  );
}

/** One article with its steps and pictures, or why this person cannot read it. */
export function ArticleView({ articleId }: { articleId: string }) {
  const { me } = useStaff();
  const article = articleById(articleId);
  const allowed = article !== undefined && articleVisible(article, me);
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
      <AskUs text={article.title} />
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
