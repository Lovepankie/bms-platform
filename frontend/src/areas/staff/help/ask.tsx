import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { askBms, type AskAnswer } from '../../../api/help';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field } from '../../../app/ui/Field';
import { Card, ListRow, RowList } from '../../../app/ui/ListRow';

// Ask BMS (issue #20, ADR-096): a question in the person's own words, answered from the How do I...? articles only, with the
// article it comes from linked. Shown only while the server says it is on for the business; offline or off, the page is as
// before. The server scrubs the question and never reads the business's records; when no answer is written the matching
// articles are listed instead. "Not answered? Ask us" stays under it on the page.

export const ASK_STATUS_KEY = ['help', 'ask', 'status'] as const;

export function useAskEnabled(): boolean {
  const status = useQuery({ queryKey: ASK_STATUS_KEY, queryFn: askBms.status, staleTime: 5 * 60_000, retry: false });
  return status.data?.enabled === true;
}

const row = (a: { id: string; title: string }) => <ListRow key={a.id} title={a.title} to={`/staff/help/${a.id}`} />;

function Answer({ result }: { result: AskAnswer }) {
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
  if (result.articles.length > 0) {
    return (
      <Card title="These articles may answer it">
        <RowList label="These articles may answer it">{result.articles.map(row)}</RowList>
      </Card>
    );
  }
  return <p className="empty-state">No article answers that yet.</p>;
}

/** The Ask BMS box; `onAsked` hands the question to "Not answered? Ask us". */
export function AskBms({ onAsked }: { onAsked?: (question: string) => void }) {
  const [question, setQuestion] = useState('');
  const [tried, setTried] = useState(false);
  const ask = useMutation({ mutationFn: (q: string) => askBms.ask(q) });
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
          ask.mutate(text);
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
          onChange={(e) => setQuestion(e.target.value)}
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
        {ask.data && <Answer result={ask.data} />}
      </div>
    </section>
  );
}
