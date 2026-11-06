import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { RETAIL_CURRENCY, RetailError, retail, type ImportResult } from '../../../api/retail';
import { WORDS } from '../../../app/glossary';
import { ActionBar } from '../../../app/ui/ActionBar';
import { BusyButton } from '../../../app/ui/BusyButton';
import { EmptyChoice } from '../../../app/ui/EmptyChoice';
import { Field } from '../../../app/ui/Field';
import { Hint } from '../../../app/ui/Hint';
import { EntryState } from '../../../offline/entry-state';
import { OfflineFormNote, isKept, useEntrySave } from '../../../offline/keep-entry';
import type { OutboxItem } from '../../../offline/outbox';
import { AddsTo } from '../branch-picker';
import { useStaff } from '../context';
import {
  COLUMNS, checkRows, emptyRow, fillRows, groupsOf, isBlank, listCsv, nameKey, nextKey, resultLine, rowsFromPaste, startRows,
  type ListRow, type RowCheck,
} from './add-many-state';
import { CategoryPicker } from './category-picker';
import { existsText, type ExistingItem } from './catalogue-names';
import { newIdempotencyKey, usePersistedDraft } from './idempotency';
import { branchesWhere } from './permissions';
import { NO_COUNT_HERE, NO_PLACE, notSureLine, pieceUnit, readLastGroup, refusedForCertain, writeLastGroup } from './quick-add';
import { Problem, useProfitAccess, useStockingBranch } from './ui';
import { UnitPicker } from './unit-picker';

// Add many items on Import items (issue #870, folded into #146 on the Lead's review): a pilot shop owner stopped using the app
// after adding about 20 items one at a time. Rows of the item's name, group, unit, how many now at the shop chosen at the top,
// buying price (only for whoever may see it, as on Quick add) and selling price; five to start and Add another row; Tab or
// Enter moves to the next cell; on a phone each row is a small card. Paste a list fills the rows from lines copied out of
// Excel, Google Sheets or WhatsApp, for the owner to check. Each row is checked where it is (a missing name, the same name twice,
// a number that is not one, a comma used as a decimal point; selling below buying is a warning). Save the list turns the good
// rows into the import's own file and runs the import's check, which also finds a name already in the items (Add to its stock
// instead); then one apply adds every row the check passed, each with its opening stock at the chosen shop, under the list's
// one key, so a resend adds nothing twice. The rows that need a look stay. Offline the apply is kept on the phone as one entry
// and sent once (ADR-067 decision 13); the import checks it when it arrives.

export const ADD_MANY = 'Add many items';
const COUNT = 'retail.stocktake.commit';
const SAVE = 'Save the list';

interface ListDraft {
  rows: ListRow[];
  /** The shop chosen at the top; null follows the branch in use. */
  where: string | null;
  /** The apply sent, with its key, while its answer is not certain: Save sends exactly it again, under that key. */
  pending: Plan | null;
  /**
   * The list's one Idempotency-Key once a list was answered for certain on this screen: a new one after each answer, so the
   * next list is a new request. Absent, the draft's own key (a fresh one when Fix put the draft back).
   */
  listKey?: string;
  /** In a draft kept with an outbox entry: the row keys in the order its lines were sent, so skipped rows come back. */
  keys?: number[];
}

/** One apply: the import file of the rows it carries (by row key), the shop, and the key it goes under. */
interface Plan { csv: string; branchId?: string; keys: number[]; key: string }

/** The item already in the list under a row's name, for the name it was found for (from the import's check). */
type Found = Record<number, { forName: string; item: ExistingItem }>;

/** The import's answer for one line of a file: line 2 is the first row after the header. */
const rowOf = (line: number | undefined) => (line ?? 0) - 2;

export function AddManyForm() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const profit = useProfitAccess();
  const here = useStockingBranch();
  const categories = useQuery({ queryKey: ['retail', 'catalogue', 'categories'], queryFn: () => retail.listCategories() });
  const units = useQuery({ queryKey: ['retail', 'catalogue', 'units'], queryFn: () => retail.listUnits() });
  const places = branchesWhere(me, COUNT).filter((b) => b.id && b.stocks_goods !== false);
  const mayCount = places.length > 0;
  const { key: draftKey, draft, setDraft, discard, name: draftName } = usePersistedDraft<ListDraft>(
    `add-many:${me.user_id ?? ''}`,
    { rows: startRows(), where: null, pending: null },
  );
  const entries = useEntrySave();
  const where = draft.where ?? places.find((b) => b.id === here.branchId)?.id ?? places[0]?.id ?? '';
  const whereName = places.find((b) => b.id === where)?.name ?? 'this shop';
  const active = (categories.data ?? []).filter((c) => c.active !== false);
  const switchedOn = (units.data ?? []).filter((u) => u.active !== false);
  const piece = pieceUnit(units.data ?? []);
  const lastSaved = readLastGroup(me.user_id ?? '', where || here.branchId || '') ?? '';
  const last = active.some((c) => c.id === lastSaved) ? lastSaved : '';
  const groups = groupsOf(draft.rows, last);
  const [found, setFound] = useState<Found>({});
  const [showErrors, setShowErrors] = useState(false);
  const [result, setResult] = useState<string | null>(null);
  const [keptItem, setKeptItem] = useState<OutboxItem | null>(null);
  const [notSure, setNotSure] = useState(false);
  const [refusal, setRefusal] = useState<unknown>(null);
  const [checking, setChecking] = useState(false);
  const [paste, setPaste] = useState('');
  const [pasteNote, setPasteNote] = useState<string | null>(null);
  const list = useRef<HTMLOListElement>(null);
  const held = draft.pending !== null;
  const context = {
    groups,
    pieceId: piece?.id,
    categoryName: (id: string) => (categories.data ?? []).find((c) => c.id === id)?.name,
    unitName: (id: string) => (units.data ?? []).find((u) => u.id === id)?.name,
    profit,
    mayCount,
  };

  const checksWith = (rows: ListRow[], map: Found): RowCheck[] => checkRows(rows, context).map((c, i) => {
    const r = rows[i];
    const hit = r && !r.addTo ? map[r.key] : undefined;
    // A name already in the items is not saved twice: the row asks to add to its stock, or a new name.
    if (r && hit && hit.forName === nameKey(r.name) && !c.errors.name) return { ...c, errors: { ...c.errors, name: existsText(hit.item) }, send: undefined };
    return c;
  });
  const checks = checksWith(draft.rows, found);
  const filled = draft.rows.filter((r) => !isBlank(r)).length;
  const ready = checks.filter((c) => c.send).length;

  const change = (rowKey: number, patch: Partial<ListRow>) => {
    setResult(null);
    setDraft((d) => ({ ...d, rows: d.rows.map((r) => (r.key === rowKey ? { ...r, ...patch, refused: undefined } : r)) }));
  };

  const addRow = () => {
    const k = nextKey(draft.rows);
    setDraft((d) => ({ ...d, rows: [...d.rows, emptyRow(k)] }));
    setTimeout(() => document.getElementById(`am-name-${k}`)?.focus());
  };
  const removeRow = (rowKey: number) => setDraft((d) => {
    const rows = d.rows.filter((r) => r.key !== rowKey);
    return { ...d, rows: rows.length > 0 ? rows : startRows(nextKey(d.rows)) };
  });

  /** Enter moves to the next cell, as Tab does; on the last cell it adds a row. */
  const onKey = (e: KeyboardEvent<HTMLElement>) => {
    const t = e.target;
    if (e.key !== 'Enter' || e.shiftKey || !(t instanceof HTMLInputElement) || t.dataset.cell === undefined) return;
    e.preventDefault();
    const cells = [...(list.current?.querySelectorAll<HTMLInputElement>('input[data-cell]') ?? [])];
    const next = cells[cells.indexOf(t) + 1];
    if (next) next.focus();
    else addRow();
  };

  const fill = () => {
    const pasted = rowsFromPaste(paste, { categories: categories.data ?? [], units: units.data ?? [], profit, firstKey: nextKey(draft.rows) });
    if (pasted.rows.length === 0) {
      setPasteNote('Nothing to fill. Paste one item on each line.');
      return;
    }
    setDraft((d) => ({ ...d, rows: fillRows(d.rows, pasted.rows) }));
    setPaste('');
    setShowErrors(true);
    setPasteNote(`${pasted.rows.length} ${pasted.rows.length === 1 ? 'line' : 'lines'} filled in. Check each row, then tap ${SAVE}.`
      + (pasted.droppedCost ? ' The buying prices were left out: only someone who sees buying prices enters them.' : ''));
  };

  const save = useMutation({
    // An entry kept on the phone is said by the toast itself (doneToasts); a saved list says how many were added.
    meta: { success: (done: ImportResult) => resultLine((done?.added ?? 0) + (done?.stock_added ?? 0), 0).replace(/\.$/, '') },
    mutationFn: async (plan: Plan) => {
      setDraft((d) => ({ ...d, pending: plan }));
      const count = plan.keys.length;
      return entries.save(
        {
          kind: 'items', body: { csv: plan.csv, ...(plan.branchId ? { branch_id: plan.branchId } : {}) }, key: plan.key, branchId: where,
          detail: `${count} ${count === 1 ? 'item' : 'items'}`,
          // Fixed later, the list comes back without its spent key, so it is a new request.
          fix: { to: '/staff/retail/catalogue/import', draftName, draft: { rows: draft.rows, where: draft.where, pending: null, keys: plan.keys } },
        },
        () => retail.importProducts(plan.csv, false, false, { branchId: plan.branchId, key: plan.key }),
      );
    },
    onSuccess: (answer, plan) => {
      const kept = isKept(answer);
      // The Lead on the review of #871: every row of the answer maps back. A row the apply did not add stays on the screen,
      // counted under need a look; one the answer names as already held offers Add to its stock at once.
      const missed = kept ? [] : (answer.rows ?? []).filter((row) => row.outcome !== 'added' && row.outcome !== 'stock');
      const notAdded = new Set(missed.map((row) => plan.keys[rowOf(row.line)]));
      const named = missed.flatMap((row) => {
        const r = draft.rows.find((x) => x.key === plan.keys[rowOf(row.line)]);
        return r && row.existing_code ? [[r.key, { forName: nameKey(r.name), item: { id: row.existing_code, code: row.existing_code, name: r.name.trim(), mayAddAnyway: false } }] as const] : [];
      });
      if (named.length > 0) setFound((m) => ({ ...m, ...Object.fromEntries(named) }));
      const sent = new Set(plan.keys.filter((k) => !notAdded.has(k)));
      const left = draft.rows.filter((r) => !sent.has(r.key) && !isBlank(r));
      const needLook = left.length;
      // The group is remembered as on Quick add, once an item is added with it at that shop.
      const lastGroup = [...draft.rows].reverse().map((r) => (sent.has(r.key) ? groups[draft.rows.indexOf(r)] : '')).find(Boolean);
      if (lastGroup) writeLastGroup(me.user_id ?? '', where || here.branchId || '', lastGroup);
      // A new key for the next list: all saved, a fresh blank list; some left, those rows under a new key.
      if (left.length === 0) {
        discard();
        if (draft.where) setDraft((d) => ({ ...d, where: draft.where }));
      } else setDraft({ rows: left, where: draft.where, pending: null, listKey: newIdempotencyKey() });
      setNotSure(false);
      setRefusal(null);
      setShowErrors(needLook > 0);
      if (kept) {
        setKeptItem(answer.kept);
        setResult(`${plan.keys.length} ${plan.keys.length === 1 ? 'item' : 'items'} saved on this phone. They are sent when the phone is back online.`
          + (needLook > 0 ? ` ${needLook} ${needLook === 1 ? 'needs' : 'need'} a look.` : ''));
        return;
      }
      setKeptItem(null);
      setResult(resultLine((answer.added ?? 0) + (answer.stock_added ?? 0), needLook));
      void queryClient.invalidateQueries({ queryKey: ['retail'] });
    },
    onError: (error) => {
      if (!refusedForCertain(error)) {
        // It may have gone through: the key and the request are kept, every field is locked, Save sends exactly it again.
        setNotSure(true);
        return;
      }
      // Nothing was saved: a new key for the next try, and the import's words say why.
      setNotSure(false);
      setDraft({ rows: draft.rows, where: draft.where, pending: null, listKey: newIdempotencyKey() });
      setResult(null);
      setRefusal(error);
    },
  });

  /**
   * The Lead on the review of #871: Add to its stock is one tap. The row becomes the item already in the list and, when its
   * quantity is good and nothing else is on its way, its quantity is posted to that item at once, alone; otherwise the row
   * says what to fix and Save the list sends it with the others.
   */
  const addToStockNow = (r: ListRow, index: number, item: ExistingItem) => {
    const next: ListRow = { ...r, addTo: { id: item.id, code: item.code, name: item.name }, refused: undefined };
    setResult(null);
    setDraft((d) => ({ ...d, rows: d.rows.map((x) => (x.key === r.key ? next : x)) }));
    const [c] = checkRows([next], { ...context, groups: [groups[index] ?? ''] });
    if (!c?.send || save.isPending || checking || draft.pending) return;
    save.mutate({ csv: listCsv([c.send], profit, mayCount), branchId: mayCount ? where : undefined, keys: [r.key], key: draft.listKey ?? draftKey });
  };

  /** The import's check of the rows sent: the rows it would add, and the others marked on the screen. */
  const checkOnServer = async (plan: Plan): Promise<number[]> => {
    const answer = await retail.importProducts(plan.csv, true, false, { branchId: plan.branchId });
    const ok: number[] = [];
    const nextFound: Found = { ...found };
    const marks = new Map<number, { field: string; message: string }>();
    for (const row of answer.rows ?? []) {
      const rowKey = plan.keys[rowOf(row.line)];
      if (rowKey === undefined) continue;
      const r = draft.rows.find((x) => x.key === rowKey);
      if (row.outcome === 'added' || row.outcome === 'stock') ok.push(rowKey);
      else if (row.outcome === 'skipped' && row.existing_code && r) {
        nextFound[rowKey] = { forName: nameKey(r.name), item: { id: row.existing_code, code: row.existing_code, name: r.name.trim(), mayAddAnyway: false } };
      } else marks.set(rowKey, { field: 'row', message: row.message ?? 'This row has a problem.' });
    }
    setFound(nextFound);
    if (marks.size > 0) setDraft((d) => ({ ...d, rows: d.rows.map((r) => (marks.has(r.key) ? { ...r, refused: marks.get(r.key) } : r)) }));
    return ok;
  };

  const submit = async (e?: FormEvent) => {
    e?.preventDefault();
    if (save.isPending || checking) return;
    setRefusal(null);
    setResult(null);
    if (draft.pending) return save.mutate(draft.pending);
    const now = checksWith(draft.rows, found);
    setShowErrors(true);
    const good = draft.rows.flatMap((r, i) => (now[i]?.send ? [{ key: r.key, send: now[i].send }] : []));
    if (good.length === 0) {
      setResult(filled === 0 ? 'Type or paste at least one item.' : resultLine(0, filled));
      const first = draft.rows.find((r, i) => !isBlank(r) && Object.keys(now[i]?.errors ?? {}).length > 0);
      if (first) setTimeout(() => document.getElementById(`am-name-${first.key}`)?.focus());
      return;
    }
    const key = draft.listKey ?? draftKey;
    const branchId = mayCount ? where : undefined;
    const planOf = (rows: typeof good): Plan => ({ csv: listCsv(rows.map((g) => g.send), profit, mayCount), branchId, keys: rows.map((g) => g.key), key });
    // Offline nothing can be checked now: the apply goes to the phone's outbox, and the import checks it when it arrives.
    if (entries.offlineMode) return save.mutate(planOf(good));
    setChecking(true);
    try {
      const ok = new Set(await checkOnServer(planOf(good)));
      const passed = good.filter((g) => ok.has(g.key));
      if (passed.length === 0) {
        setResult(resultLine(0, filled));
        return;
      }
      save.mutate(planOf(passed));
    } catch (error) {
      setRefusal(error);
    } finally {
      setChecking(false);
    }
  };

  return (
    <form className="stack am-form" onSubmit={(e) => void submit(e)} noValidate aria-label={ADD_MANY} onKeyDown={onKey}>
      {entries.offlineMode && <OfflineFormNote what="list" />}
      {mayCount ? (
        <AddsTo id="am-where" lead="Stock goes to" value={where} places={places} onChoose={held ? undefined : (id) => setDraft((d) => ({ ...d, where: id }))} />
      ) : (
        <EmptyChoice id="am-where" label="Where" text={NO_PLACE} ask={NO_COUNT_HERE.replace('The item is', 'The items are').replace('its stock', 'their stock')} />
      )}
      <fieldset disabled={held} className="form-lock stack">
        <details className="am-paste" data-tour="add-many-paste">
          <summary className="btn">Paste a list</summary>
          <div className="stack">
            <label htmlFor="am-paste">Paste lines from Excel, Google Sheets or WhatsApp, one item on each line</label>
            <textarea id="am-paste" rows={5} value={paste} onChange={(e) => { setPaste(e.target.value); setPasteNote(null); }} />
            <Hint full>{`Put a tab, a comma or two spaces between the columns, in this order: name, group, unit, how many, ${WORDS.buyingPrice.toLowerCase()}, ${WORDS.sellingPrice.toLowerCase()}. With fewer columns: ${columnsHint()}. With commas between columns, write numbers without commas.`}</Hint>
            <p className="cluster">
              <button type="button" className="btn" onClick={fill} disabled={paste.trim() === ''}>Fill the rows</button>
            </p>
          </div>
        </details>
        {pasteNote && <p role="status" className="hint" data-testid="am-paste-note">{pasteNote}</p>}
        <ol ref={list} className="am-rows" data-tour="add-many-rows" aria-label="Items">
          {draft.rows.map((r, i) => (
            <RowCard
              key={r.key}
              row={r}
              index={i}
              check={checks[i] ?? { errors: {} }}
              group={groups[i] ?? ''}
              categories={active}
              units={switchedOn.filter((u) => u.is_package !== true)}
              unitsLoad={units}
              pieceId={piece?.id ?? ''}
              profit={profit}
              mayCount={mayCount}
              whereName={whereName}
              showErrors={showErrors}
              found={found[r.key]?.forName === nameKey(r.name) ? found[r.key]?.item : undefined}
              onChange={(patch) => change(r.key, patch)}
              onRemove={() => removeRow(r.key)}
              onAddToStock={(item) => addToStockNow(r, i, item)}
            />
          ))}
        </ol>
        <button type="button" className="btn am-another" onClick={addRow}>Add another row</button>
      </fieldset>
      {result && <p role="status" className={`alert ${result.startsWith('0 ') ? 'alert-warning' : 'alert-success'}`} data-testid="am-result">{result}</p>}
      {keptItem && <EntryState item={keptItem} />}
      {notSure && <p role="alert" className="alert alert-warning" data-testid="am-not-sure">{notSureLine(SAVE)}</p>}
      <Problem error={refusal instanceof RetailError || refusal instanceof Error ? refusal : null} />
      <ActionBar testId="am-save-bar">
        <p className="hint am-count" data-tour="add-many-save" data-testid="am-count">
          <span className="num">{ready}</span> ready{filled > ready ? <>, <span className="num">{filled - ready}</span> {filled - ready === 1 ? 'needs' : 'need'} a look</> : ''}
        </p>
        <BusyButton busy={save.isPending || checking} type="submit" className="rt-primary">{SAVE}</BusyButton>
      </ActionBar>
    </form>
  );
}

/** "name, selling price" and the others, from COLUMNS. */
function columnsHint(): string {
  const words: Record<string, string> = { name: 'name', category: 'group', unit: 'unit', qty: 'how many', cost: 'buying', sell: 'selling' };
  return [2, 3, 4, 5].map((n) => (COLUMNS[n] ?? []).map((c) => words[c]).join(', ')).join('; or ');
}

function RowCard({ row: r, index, check, group, categories, units, unitsLoad, pieceId, profit, mayCount, whereName, showErrors, found, onChange, onRemove, onAddToStock }: {
  row: ListRow;
  index: number;
  check: RowCheck;
  group: string;
  categories: Parameters<typeof CategoryPicker>[0]['categories'];
  units: Parameters<typeof UnitPicker>[0]['units'];
  unitsLoad: { isError: boolean; refetch: () => unknown; data?: unknown };
  pieceId: string;
  profit: boolean;
  mayCount: boolean;
  whereName: string;
  showErrors: boolean;
  found?: ExistingItem;
  onChange: (patch: Partial<ListRow>) => void;
  onRemove: () => void;
  /** One tap: the row adds its quantity to the stock of the item already in the list (posted at once when it can be). */
  onAddToStock: (item: ExistingItem) => void;
}) {
  const id = (f: string) => `am-${f}-${r.key}`;
  const e = check.errors;
  const blank = isBlank(r);
  const shown = showErrors && !blank;
  const label = `Row ${index + 1}`;
  return (
    <li className="am-row" aria-label={label} data-testid={`am-row-${index + 1}`}>
      <div className="am-row-head">
        <span className="am-row-no">{label}</span>
        <button type="button" className="btn-ghost" onClick={onRemove} aria-label={`Remove row ${index + 1}`}>Remove</button>
      </div>
      {e.row && shown && <p role="alert" className="field-error" data-testid={`am-problem-${index + 1}`}>{e.row}</p>}
      {r.addTo && (
        <div className="stack am-adds-to">
          <p data-testid={`am-adds-to-${index + 1}`}>Adds to the stock of <strong>{r.addTo.name}</strong> ({r.addTo.code}) at {whereName}.</p>
          <p className="cluster">
            <button type="button" className="btn-ghost" onClick={() => onChange({ addTo: undefined })}>Make it a new item instead</button>
          </p>
        </div>
      )}
      <div className="am-cells">
        {!r.addTo && (
          <Field
            id={id('name')}
            className="am-wide"
            label="Name"
            required
            value={r.name}
            maxLength={300}
            autoComplete="off"
            data-cell=""
            error={blank ? null : e.name ?? null}
            showError={shown || found !== undefined}
            onChange={(ev) => onChange({ name: ev.target.value })}
          />
        )}
        {found && !r.addTo && (
          <div className="am-full stack" data-testid={`am-exists-${index + 1}`}>
            {mayCount ? (
              <p className="cluster">
                <button type="button" className="btn" onClick={() => onAddToStock(found)}>Add to its stock instead</button>
              </p>
            ) : <p className="hint">Change the name, or remove this row: it is already in your items.</p>}
          </div>
        )}
        {!r.addTo && (
          <div className="am-wide">
            <CategoryPicker
              id={id('category')}
              label="Group"
              thing="group"
              placeholder={r.pastedCategory ?? 'General'}
              noneLabel="General"
              categories={categories}
              value={r.pastedCategory ? '' : group}
              onChange={(next) => onChange({ categoryId: next, categoryChosen: true, pastedCategory: undefined })}
              error={e.category ?? null}
              showError={shown}
              hint={r.pastedCategory ? `New group: ${r.pastedCategory}.` : !r.categoryChosen && group ? (index === 0 ? 'The group you used last time.' : 'The same as the row above.') : undefined}
            />
          </div>
        )}
        {!r.addTo && (
          <div>
            <UnitPicker
              id={id('unit')}
              units={units}
              loading={unitsLoad.data === undefined}
              load={unitsLoad}
              value={r.pastedUnit ? '' : r.unitId || pieceId}
              onChange={(next) => onChange({ unitId: next === pieceId ? '' : next, pastedUnit: undefined })}
              required={false}
              error={e.unit ?? null}
              showError={shown}
              hint={r.pastedUnit ? `New unit: ${r.pastedUnit}.` : undefined}
            />
          </div>
        )}
        {mayCount && (
          <Field
            id={id('qty')}
            className="am-num"
            label={r.addTo ? 'How many to add' : 'How many now'}
            required
            inputMode="decimal"
            autoComplete="off"
            data-cell=""
            value={r.qty}
            error={blank ? null : e.qty ?? null}
            showError={shown}
            onChange={(ev) => onChange({ qty: ev.target.value })}
          />
        )}
        {profit && !r.addTo && (
          <Field
            id={id('cost')}
            className="am-num"
            label={`${WORDS.buyingPrice} (${RETAIL_CURRENCY})`}
            optional
            inputMode="decimal"
            autoComplete="off"
            data-cell=""
            value={r.cost}
            error={blank ? null : e.cost ?? null}
            showError={shown}
            onChange={(ev) => onChange({ cost: ev.target.value })}
          />
        )}
        {!r.addTo && (
          <Field
            id={id('sell')}
            className="am-num"
            label={`${WORDS.sellingPrice} (${RETAIL_CURRENCY})`}
            required
            inputMode="decimal"
            autoComplete="off"
            data-cell=""
            value={r.sell}
            error={blank ? null : e.sell ?? null}
            showError={shown}
            hint={check.warning}
            onChange={(ev) => onChange({ sell: ev.target.value })}
          />
        )}
      </div>
    </li>
  );
}
