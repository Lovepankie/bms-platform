import { useMutation, useQuery } from '@tanstack/react-query';
import { Link } from '@tanstack/react-router';
import { useEffect, useRef, useState } from 'react';
import { askBms, type AskAnswer } from '../../../api/help';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field } from '../../../app/ui/Field';
import { Card, ListRow, RowList } from '../../../app/ui/ListRow';
import { relatedArticles } from '../../../help/corpus';

// Ask BMS (issue #20, ADR-096): a question in the person's own words, answered from the How do I...? articles only, with the
// article it comes from linked. Shown only while the server says it is on for the business; offline or off, the page is as
// before. The server scrubs the question and never reads the business's records. When the help has no answer (issue #609:
// the model found none in the articles, every model failed, or no article matched) it says so in one line with Ask us right
// under it, then lists only the articles that share a real word with the question; the page then drops its own "Not
// answered? Ask us", so there is one. With no model to ask, or the day's questions used up, the matching articles are listed.
// Typing a new question clears that line, and so does the box leaving the page (offline, or Ask BMS turned off), so the page's
// own Ask us comes back (issue #611).

export const NOT_FOUND = 'I could not find this in the help yet. Tap Ask us and it comes to the team.';

export const ASK_STATUS_KEY = ['help', 'ask', 'status'] as const;

/**
 * Whether Ask BMS is on for the business, and whether that is settled (`known`): the server has answered, the read failed, or it
 * waits for the network (offline, the query is paused), when Ask BMS counts as off.
 */
export function useAskStatus(): { enabled: boolean; known: boolean } {
  const status = useQuery({ queryKey: ASK_STATUS_KEY, queryFn: askBms.status, staleTime: 5 * 60_000, retry: false });
  return { enabled: status.data?.enabled === true, known: !status.isPending || status.fetchStatus === 'paused' };
}

export function useAskEnabled(): boolean {
  return useAskStatus().enabled;
}

const row = (a: { id: string; title: string }) => <ListRow key={a.id} title={a.title} to={`/staff/help/${a.id}`} />;

/** Whether the help has no answer to the question, rather than no model to write one (issue #609). */
export function notFound(result: AskAnswer): boolean {
  return result.source === 'no_match' || (result.source === 'articles' && (result.reason === 'not_in_articles' || result.reason === 'all_failed'));
}

function Answer({ result, question }: { result: AskAnswer; question: string }) {
  if ((result.source === 'model' || result.source === 'cache') && result.answer && result.cited) {
    return (
      <Card title="Answer">
        <p className="help-answer" data-testid="ask-answer">
          {result.answer}
        </p>
        <p className="hint">Written from the article below, which has the pictures.</p>
        <RowList label="From the article">{row(result.cited)}</RowList>
      </Card>
    );
  }
  if (result.source === 'other_role' && result.answer) {
    return (
      <>
        <p role="note" className="alert alert-info">
          {result.answer}
        </p>
        {result.articles.length > 0 && (
          <Card title="Articles for what you can do">
            <RowList label="Articles for what you can do">{result.articles.map(row)}</RowList>
          </Card>
        )}
      </>
    );
  }
  if (!notFound(result)) {
    // No model was asked (none set, or the day's questions used up): the matching articles, as before.
    return result.articles.length > 0 ? (
      <Card title="These articles may answer it">
        <RowList label="These articles may answer it">{result.articles.map(row)}</RowList>
      </Card>
    ) : (
      <p className="empty-state">No article answers that yet.</p>
    );
  }
  const related = relatedArticles(question, result.articles);
  return (
    <div className="stack" data-testid="ask-not-found">
      <p role="note" className="alert alert-info">
        {NOT_FOUND}
      </p>
      <p>
        <Link to="/staff/feedback" search={{ ask: question }} className="btn">
          Ask us
        </Link>
      </p>
      {related.length > 0 && (
        <Card title="These articles may answer it">
          <RowList label="These articles may answer it">{related.map(row)}</RowList>
        </Card>
      )}
    </div>
  );
}

/**
 * The Ask BMS box; `onAsked` hands the question to "Not answered? Ask us", and `onNotFound` tells the page whether the answer
 * shown says the help has none, with its own Ask us. It is told `false` when the box leaves the page, and an answer that arrives
 * after that is not passed on, so the page never hides its own Ask us for a box that is gone (issue #611).
 */
export function AskBms({ onAsked, onNotFound }: { onAsked?: (question: string) => void; onNotFound?: (shown: boolean) => void }) {
  const [question, setQuestion] = useState('');
  const [tried, setTried] = useState(false);
  // toast-exempt: asking a question saves nothing, the answer shows in the box
  const ask = useMutation({ mutationFn: (q: string) => askBms.ask(q) });
  const tellNotFound = useRef(onNotFound);
  useEffect(() => {
    tellNotFound.current = onNotFound;
  });
  useEffect(() => () => tellNotFound.current?.(false), []);
  const text = question.trim();
  const tooShort = text.length < 3 ? 'Type your question, in your own words.' : null;
  return (
    <section className="stack help-ask-bms" aria-labelledby="ask-bms-title">
      <h2 id="ask-bms-title">Ask BMS</h2>
      <form
        className="stack"
        aria-label="Ask BMS"
        onSubmit={(e) => {
          e.preventDefault();
          setTried(true);
          if (tooShort || ask.isPending) return;
          onAsked?.(text);
          onNotFound?.(false);
          // Called with the mutate, not on the hook: TanStack drops these once the box has unmounted.
          ask.mutate(text, { onSuccess: (result) => tellNotFound.current?.(notFound(result)) });
        }}
      >
        <Field
          label="Your question"
          hint="For example: how do I invite a cashier?"
          value={question}
          maxLength={300}
          autoComplete="off"
          error={tooShort}
          showError={tried}
          onChange={(e) => {
            setQuestion(e.target.value);
            if (ask.data && notFound(ask.data) && e.target.value.trim() !== ask.variables) {
              ask.reset();
              onNotFound?.(false);
            }
          }}
        />
        <p>
          <BusyButton type="submit" className="rt-primary" busy={ask.isPending}>
            Ask
          </BusyButton>
        </p>
      </form>
      <div aria-live="polite">
        {ask.isError && (
          <p role="alert" className="alert alert-danger">
            {ask.error instanceof Error ? ask.error.message : 'Could not ask now. The articles below still answer.'}
          </p>
        )}
        {ask.data && <Answer result={ask.data} question={ask.variables ?? text} />}
      </div>
    </section>
  );
}
