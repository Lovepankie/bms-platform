import { STEPS, stepLine, stepStates } from './pipeline';

// The steps of an import (issue #141) as a list a person reads top to bottom: done, the step it is on, then what is left. Shared by
// the business's run screen and the operator portal's, so both say the same. The state is in the words as well as the mark, so it
// does not rest on colour.

const MARK = { done: 'Done', now: 'Now', later: 'Later' } as const;

export function StageProgress({ status }: { status: string | undefined }) {
  const states = stepStates(status);
  return (
    <section className="import-stages" aria-labelledby="import-stages-title" data-tour="import-stages">
      <h2 id="import-stages-title">{stepLine(status)}</h2>
      <ol>
        {STEPS.map((step, i) => {
          const state = states[i] ?? 'later';
          return (
            <li key={step.key} className={`import-stage is-${state}`} aria-current={state === 'now' ? 'step' : undefined}>
              <span className="import-stage-mark">{MARK[state]}</span>
              <span>{step.label}</span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
