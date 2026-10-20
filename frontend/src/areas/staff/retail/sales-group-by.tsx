import { LEVEL_NAMES, addLevel, dropLevel, droppedLevels, moveLevel, type ChainLevel } from './sales-chain-state';

// The "Group by" control of All sales and Credit sales (issue #341): the levels the list is grouped by, in order. A
// person moves a level up or down, takes it out, or puts it back; the choice is kept for them by the screen. Buttons
// rather than dragging, so it works with a thumb on a phone and with a keyboard.

export function GroupBy<L extends string = ChainLevel>({ chain, levels, onChange, names = LEVEL_NAMES as Record<string, string>, tour = 'sales-group-by' }: {
  chain: L[];
  /** The levels this person may use, in the usual order. */
  levels: readonly L[];
  onChange: (next: L[]) => void;
  /** The words for each level; the sales levels unless the screen has its own (the Controls page). */
  names?: Record<string, string>;
  tour?: string;
}) {
  const out = droppedLevels(chain, levels);
  const usual = chain.length === levels.length && chain.every((l, i) => l === levels[i]);
  return (
    <details className="rt-groupby" data-tour={tour}>
      <summary>
        <strong>Group by</strong>{' '}
        <span className="rt-groupby-now">{chain.map((l) => names[l]).join(', ')}</span>
      </summary>
      <ol className="rt-groupby-list">
        {chain.map((level, i) => (
          <li key={level}>
            <span className="rt-groupby-name">{names[level]}</span>
            <button type="button" aria-label={`Move ${names[level]} up`} disabled={i === 0} onClick={() => onChange(moveLevel(chain, level, -1))}>Up</button>
            <button type="button" aria-label={`Move ${names[level]} down`} disabled={i === chain.length - 1} onClick={() => onChange(moveLevel(chain, level, 1))}>Down</button>
            <button type="button" aria-label={`Remove ${names[level]}`} disabled={chain.length === 1} onClick={() => onChange(dropLevel(chain, level))}>Remove</button>
          </li>
        ))}
      </ol>
      {out.length > 0 && (
        <p className="rt-groupby-add">
          Not used:{' '}
          {out.map((level) => (
            <button key={level} type="button" aria-label={`Add ${names[level]}`} onClick={() => onChange(addLevel(chain, level, levels))}>{names[level]}</button>
          ))}
        </p>
      )}
      {!usual && <button type="button" onClick={() => onChange([...levels])}>Use the usual order</button>}
    </details>
  );
}
