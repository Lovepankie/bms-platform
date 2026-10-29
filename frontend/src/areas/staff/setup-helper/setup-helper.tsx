import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { setupHelper, type DraftReason, type Given, type SetupHelperApi } from '../../../api/setup-helper';
import { BusyButton } from '../../../app/ui/BusyButton';
import { Field } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { Card } from '../../../app/ui/ListRow';
import { Segmented } from '../../../app/ui/Segmented';
import { browserCodec, PrepareError, prepareFile, type Codec } from '../lending/image-prep';
import { Note, Problem } from '../retail/ui';
import { FALLBACK, namesDiffer, reasonText, type ItemEdit, type LoanEdit } from './setup-helper-state';

// The setup helper's box on the two import screens (issue #626, ADR-102): the owner types the list, takes a photo of the stock
// book or register, or chooses a CSV, Excel or PDF file, and the app reads it into rows the owner can change or remove. The
// rows become the import's own file, so the import's check shows what would be added and its button adds it: nothing is
// saved here. The box is drawn only while the platform has switched the helper on for the business; when the app cannot
// read the list it says so in plain words and the owner types it or imports a file as before.

/** Whether the helper is on for the business; off while it is unknown, so the import screens draw as before. */
export function useSetupHelper(api: SetupHelperApi = setupHelper) {
  const status = useQuery({ queryKey: ['help', 'setup'], queryFn: () => api.status(), staleTime: 5 * 60_000 });
  return status.data ?? { enabled: false, photos: false };
}

type Mode = 'type' | 'photo' | 'file';

const FILE_ACCEPT = '.csv,.txt,.xlsx,.pdf,text/csv,text/plain,application/pdf,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const MAX_FILE_BYTES = 5 * 1024 * 1024;

/** The box: how the owner gives the list, and Read my list. `read` sends it; `drafted` takes the rows found (0 for none). */
export function ListReader<T extends { reason: DraftReason | null }>({
  photos,
  noun,
  read,
  drafted,
  count,
  tour,
  codec = browserCodec,
}: {
  photos: boolean;
  noun: 'items' | 'loans';
  read: (given: Given) => Promise<T>;
  drafted: (draft: T) => void;
  count: (draft: T) => number;
  /** The page tour's target for the box. */
  tour: string;
  codec?: Codec;
}) {
  const [mode, setMode] = useState<Mode>('type');
  const [text, setText] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);
  const options = [
    { value: 'type' as const, label: 'Type it' },
    ...(photos ? [{ value: 'photo' as const, label: 'Photo' }] : []),
    { value: 'file' as const, label: 'File' },
  ];
  const ready = mode === 'type' ? text.trim() !== '' : file !== null;
  const go = async () => {
    if (!ready || busy) return;
    // toast-exempt: only reads the list into the rows below, nothing is saved until the import's own Add
    setBusy(true);
    setMessage(null);
    setError(null);
    try {
      let given: Given;
      if (mode === 'type') given = { text };
      else if (mode === 'photo' && file) {
        const prepared = await prepareFile(file, codec);
        given = { file: prepared.blob, name: prepared.fileName };
      } else {
        if (!file) return;
        if (file.size > MAX_FILE_BYTES) throw new PrepareError('This file is bigger than 5 MB. Choose a smaller one, or take a photo of the page.');
        given = { file, name: file.name };
      }
      const draft = await read(given);
      const n = count(draft);
      if (draft.reason || n === 0) setMessage(reasonText(draft.reason, noun));
      drafted(draft);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };
  const title = noun === 'items' ? 'Let the app read your list' : 'Let the app read your register';
  return (
    <div data-tour={tour}>
    <Card title={title}>
      <Hint full>
        {noun === 'items'
          ? 'Type your items, take a photo of your stock book or a supplier invoice, or choose a file. The app fills in the rows below. Nothing is added until you check them and tap Add.'
          : 'Type the loans, take a photo of the register, or choose a file. The app fills in the loans below. Nothing is recorded until you check them and tap Record.'}
      </Hint>
      <Segmented label="How you give the list" options={options} value={mode} onChange={(m) => { setMode(m); setFile(null); setMessage(null); setError(null); }} />
      {mode === 'type' && (
        <>
          <Field
            id="helper-text"
            label={noun === 'items' ? 'Your items, one on each line' : 'The loans, one on each line'}
            control={(props) => <textarea {...props} rows={6} value={text} onChange={(e) => setText(e.target.value)} />}
          />
        </>
      )}
      {mode === 'photo' && (
        <>
          <label htmlFor="helper-photo">Take or choose a photo</label>
          <input id="helper-photo" type="file" accept="image/jpeg,image/png,image/webp" capture="environment" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </>
      )}
      {mode === 'file' && (
        <>
          <label htmlFor="helper-file">Choose a CSV, Excel or PDF file</label>
          <input id="helper-file" type="file" accept={FILE_ACCEPT} onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
        </>
      )}
      <BusyButton busy={busy} type="button" notReady={!ready} onClick={() => void go()}>
        Read my list
      </BusyButton>
      {message && <Note>{message}</Note>}
      {error != null && (
        <>
          <Problem error={error} />
          <Hint>{FALLBACK}</Hint>
        </>
      )}
    </Card>
    </div>
  );
}

const slug = (i: number, field: string) => `draft-${i}-${field}`;

/** The drafted items, each with its fields to change and Remove. */
export function ItemDraftList({ rows, withCost, onChange }: { rows: ItemEdit[]; withCost: boolean; onChange: (rows: ItemEdit[]) => void }) {
  const set = (i: number, change: Partial<ItemEdit>) => onChange(rows.map((r, j) => (j === i ? { ...r, ...change } : r)));
  return (
    <Card title={`${rows.length} ${rows.length === 1 ? 'item' : 'items'} from your list`}>
      <Hint>Change what is wrong and remove what you do not sell, then tap Check these items.</Hint>
      {rows.map((r, i) => (
        <div key={i} className="stack" data-testid={`draft-item-${i}`}>
          <Field id={slug(i, 'name')} label="Name" value={r.description} onChange={(e) => set(i, { description: e.target.value })} error={r.description.trim() === '' ? 'Add the name.' : null} />
          <Field id={slug(i, 'category')} label="Category" value={r.category} onChange={(e) => set(i, { category: e.target.value })} />
          <Field id={slug(i, 'unit')} label="Counted in" value={r.unit} onChange={(e) => set(i, { unit: e.target.value })} />
          <Field id={slug(i, 'sell')} label="Selling price" inputMode="decimal" value={r.sell} onChange={(e) => set(i, { sell: e.target.value })} error={r.sell.trim() === '' ? 'Add the selling price.' : null} />
          {withCost && <Field id={slug(i, 'cost')} label="Buying price" optional inputMode="decimal" value={r.cost} onChange={(e) => set(i, { cost: e.target.value })} />}
          {(r.packUnit !== '' || r.packFactor !== '') && (
            <>
              <Field id={slug(i, 'pack')} label="Comes in a pack of" value={r.packUnit} onChange={(e) => set(i, { packUnit: e.target.value })} />
              <Field id={slug(i, 'factor')} label={`How many in one ${r.packUnit || 'pack'}`} inputMode="decimal" value={r.packFactor} onChange={(e) => set(i, { packFactor: e.target.value })} />
            </>
          )}
          <button type="button" onClick={() => onChange(rows.filter((_, j) => j !== i))}>{`Remove ${r.description || 'this item'}`}</button>
        </div>
      ))}
    </Card>
  );
}

/** The drafted loans, each with its member, amounts, dates and payments to change, and Remove. */
export function LoanDraftList({ loans, onChange }: { loans: LoanEdit[]; onChange: (loans: LoanEdit[]) => void }) {
  const set = (i: number, change: Partial<LoanEdit>) => onChange(loans.map((l, j) => (j === i ? { ...l, ...change } : l)));
  const setPayment = (i: number, p: number, change: Partial<LoanEdit['payments'][number]>) =>
    set(i, { payments: (loans[i]?.payments ?? []).map((x, k) => (k === p ? { ...x, ...change } : x)) });
  return (
    <Card title={`${loans.length} ${loans.length === 1 ? 'loan' : 'loans'} from your register`}>
      <Hint>Change what is wrong and remove what should not come in, then tap Check these loans.</Hint>
      {loans.map((l, i) => (
        <div key={i} className="stack" data-testid={`draft-loan-${i}`}>
          <Field id={slug(i, 'ref')} label="Number in the register" value={l.ref} onChange={(e) => set(i, { ref: e.target.value })} />
          {l.memberNo ? (
            namesDiffer(l) ? (
              <>
                <Note>{`Member ${l.memberNo}, ${l.memberFullName}, has this phone already. The register says ${l.memberName}. If this is the same person, tick This is the same person; if not, remove this loan.`}</Note>
                <label htmlFor={slug(i, 'same')}>
                  <input
                    id={slug(i, 'same')}
                    type="checkbox"
                    checked={l.sameConfirmed}
                    {...(l.sameConfirmed ? {} : { 'data-needed': '' })}
                    onChange={(e) => set(i, { sameConfirmed: e.target.checked })}
                  />{' '}
                  This is the same person
                </label>
              </>
            ) : (
              <Hint>{`Member ${l.memberNo}, ${l.memberFullName}, has this phone already.`}</Hint>
            )
          ) : (
            <>
              <Field id={slug(i, 'member')} label="Member name" value={l.memberName} onChange={(e) => set(i, { memberName: e.target.value })} error={l.memberName.trim() === '' ? 'Add the member\'s name.' : null} hint="A new member, added with the loan." />
              <Field id={slug(i, 'phone')} label="Member phone" inputMode="tel" value={l.memberPhone} onChange={(e) => set(i, { memberPhone: e.target.value })} error={l.memberPhone.trim() === '' ? 'Add the member\'s phone.' : null} />
            </>
          )}
          <Field id={slug(i, 'principal')} label="Amount lent" inputMode="decimal" value={l.principal} onChange={(e) => set(i, { principal: e.target.value })} error={l.principal.trim() === '' ? 'Add the amount lent.' : null} />
          <Field id={slug(i, 'date')} label="Date given out" type="date" value={l.date} onChange={(e) => set(i, { date: e.target.value })} error={l.date.trim() === '' ? 'Add the date the money was given out.' : null} />
          <Field id={slug(i, 'term')} label="Months" optional inputMode="numeric" value={l.term} onChange={(e) => set(i, { term: e.target.value })} />
          <Field
            id={slug(i, 'total')}
            label="Total to repay"
            optional
            inputMode="decimal"
            value={l.agreedTotal}
            onChange={(e) => set(i, { agreedTotal: e.target.value, totalWorkedOut: false })}
            hint={l.totalWorkedOut ? `Worked out from ${l.ratePercent}% a month for ${l.term} months. Check it against the register.` : l.ratePercent ? `The register says ${l.ratePercent}% a month.` : undefined}
          />
          {l.payments.map((p, k) => (
            <div key={k} className="cluster">
              <Field id={slug(i, `paid-date-${k}`)} label={`Payment ${k + 1} date`} type="date" value={p.date} onChange={(e) => setPayment(i, k, { date: e.target.value })} error={p.date.trim() === '' ? 'Add the date it was paid.' : null} />
              <Field id={slug(i, `paid-${k}`)} label={`Payment ${k + 1} amount`} inputMode="decimal" value={p.amount} onChange={(e) => setPayment(i, k, { amount: e.target.value })} />
              <button type="button" onClick={() => set(i, { payments: l.payments.filter((_, x) => x !== k) })}>{`Remove payment ${k + 1}`}</button>
            </div>
          ))}
          <button type="button" onClick={() => onChange(loans.filter((_, j) => j !== i))}>{`Remove loan ${l.ref}`}</button>
        </div>
      ))}
    </Card>
  );
}
