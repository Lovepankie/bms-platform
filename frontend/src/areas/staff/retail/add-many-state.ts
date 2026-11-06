import { RETAIL_CURRENCY, type Category, type Unit } from '../../../api/retail';
import { exponentOf, parseMinor } from '../../../components/money';
import { csvCell } from '../setup-helper/setup-helper-state';

/** A number checked by `readNumber`, as the import file carries it: its thousands commas taken out, nothing else changed. */
const plainNumber = (text: string): string => {
  const n = readNumber(text);
  return 'value' in n ? n.value : text.trim();
};

// Add many items (issue #870): a shop owner stopped using the app after adding about 20 items one at a time on Quick add. This
// file is the screen's pure part, shared with its tests: the rows, reading a list pasted from Excel, Google Sheets or WhatsApp,
// the checks on each row before saving, and the request of the rows that are good. A number is read exactly as typed or
// refused, never rounded or rewritten; a comma is taken only between thousands (12,500), so 1,5 is refused, as the import's
// rule of #867.

export interface ListRow {
  /** Stable for React and for the ids of the row's fields. */
  key: number;
  name: string;
  /** The group chosen on this row; '' with `categoryChosen` false follows the group of the row above (see `groupsOf`). */
  categoryId: string;
  categoryChosen: boolean;
  /** '' is the shop's piece unit, as on Quick add. */
  unitId: string;
  qty: string;
  cost: string;
  sell: string;
  /** A group or unit pasted that the shop does not have: the row asks for one to be chosen, it is never guessed. */
  pastedCategory?: string;
  pastedUnit?: string;
  /** Pasted with more columns than the list takes. */
  extraColumns?: number;
  /** The item already in the list this row adds stock to, chosen instead of a new item of the same name. */
  addTo?: { id: string; code: string; name: string };
  /** The server refused the list on this row: its words, on the field it names. */
  refused?: { field: string; message: string };
}

export const START_ROWS = 5;

export function emptyRow(key: number): ListRow {
  return { key, name: '', categoryId: '', categoryChosen: false, unitId: '', qty: '', cost: '', sell: '' };
}

export function startRows(from = 1): ListRow[] {
  return Array.from({ length: START_ROWS }, (_, i) => emptyRow(from + i));
}

/** The next free row key. */
export const nextKey = (rows: ListRow[]): number => rows.reduce((m, r) => Math.max(m, r.key), 0) + 1;

/** Nothing typed on the row: it is left out of the save and never counted. */
export function isBlank(r: ListRow): boolean {
  return !r.addTo && [r.name, r.qty, r.cost, r.sell].every((v) => v.trim() === '') && !r.pastedCategory && !r.pastedUnit;
}

/**
 * The group of each row: the one chosen on it, or else the last one chosen on a row above it, or else the one this person last
 * used at this shop (`last`, as Quick add remembers it). Optional: none sends no group and the item goes under General.
 */
export function groupsOf(rows: ListRow[], last: string): string[] {
  let current = last;
  return rows.map((r) => {
    if (r.categoryChosen) current = r.categoryId;
    return r.categoryChosen ? r.categoryId : current;
  });
}

// ---- Numbers ----------------------------------------------------------------------------------------------------------

/** Commas only between groups of three digits: 12,500 or 1,250,000.5 (the import's rule of #867). */
const GROUPED = /^[1-9]\d{0,2}(,\d{3})+(\.\d+)?$/;
const PLAIN = /^\d+(\.\d+)?$/;

export type NumberProblem = 'comma_decimal' | 'not_number';

/** The number typed with its thousands commas taken out, or why it is not one. Nothing is rounded. */
export function readNumber(text: string): { value: string } | { problem: NumberProblem } {
  const t = text.trim();
  if (t.includes(',')) {
    if (GROUPED.test(t)) return { value: t.replace(/,/g, '') };
    // 1,5 or 12,50: a comma used as a decimal point is refused, never read as 15 or 1250.
    return { problem: /^\d+,\d+$/.test(t) ? 'comma_decimal' : 'not_number' };
  }
  return PLAIN.test(t) ? { value: t } : { problem: 'not_number' };
}

export const COMMA_DECIMAL = 'Use a point for decimals, like 2.5. A comma only goes between thousands, like 12,500.';
export const NOT_A_NUMBER = 'Type a number, like 12 or 2.5.';
export const NAME_NEEDED = "Type the item's name.";
export const QTY_NEEDED = 'Type how many you have now, or 0.';
export const QTY_TO_ADD = 'Type how many you are adding, more than 0.';
export const QTY_PLACES = 'Use at most 3 places after the point.';
export const SELL_NEEDED = 'Type the selling price.';
export const SELL_BELOW = 'The selling price is below the buying price. Check it, or save it like this.';

const whole = () => {
  const places = exponentOf(RETAIL_CURRENCY) ?? 0;
  return places === 0 ? `Type the price in whole ${RETAIL_CURRENCY}, like 1500.` : `Use at most ${places} places after the point.`;
};

/** The quantity as sent ("12.5"), or the words of its problem. Empty is a problem: 0 is typed, never assumed. */
export function readQty(text: string, adding: boolean): { qty: string } | { error: string } {
  if (text.trim() === '') return { error: adding ? QTY_TO_ADD : QTY_NEEDED };
  const n = readNumber(text);
  if ('problem' in n) return { error: n.problem === 'comma_decimal' ? COMMA_DECIMAL : NOT_A_NUMBER };
  if ((n.value.split('.')[1] ?? '').length > 3) return { error: QTY_PLACES };
  if (adding && Number(n.value) <= 0) return { error: QTY_TO_ADD };
  return { qty: n.value };
}

/** A price in minor units, null when left empty, or the words of its problem. */
export function readPrice(text: string): { minor: number | null } | { error: string } {
  if (text.trim() === '') return { minor: null };
  const n = readNumber(text);
  if ('problem' in n) return { error: n.problem === 'comma_decimal' ? COMMA_DECIMAL : NOT_A_NUMBER };
  const minor = parseMinor(n.value, RETAIL_CURRENCY);
  return minor === null ? { error: whole() } : { minor };
}

// ---- Checks -----------------------------------------------------------------------------------------------------------

/** The server's name key (issue #352): lower case, every run of spaces and punctuation one space. */
export const nameKey = (text: string): string => text.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

export interface RowCheck {
  /** Field to the words under it: name, category, unit, qty, cost, sell. */
  errors: Partial<Record<'name' | 'category' | 'unit' | 'qty' | 'cost' | 'sell' | 'row', string>>;
  /** Shown, never a block. */
  warning?: string;
  /** The row as Import items takes it (`listCsv`); only when there is no error. */
  send?: CsvRow;
}

/** One line of the import file a list becomes (issue #870): the import's own columns, the numbers as typed without commas. */
export interface CsvRow {
  description: string;
  category: string;
  unit: string;
  qty?: string;
  cost?: string;
  sell?: string;
  /** Add the quantity to the item of this name and unit already in the list (the import's stock_only). */
  stockOnly?: boolean;
}

export interface CheckContext {
  /** The group of each row (`groupsOf`). */
  groups: string[];
  /** The unit an empty unit stands for (the shop's piece unit), when the shop has one. */
  pieceId?: string;
  /** The name of a group or unit by its id, as the import file names them. */
  categoryName?: (id: string) => string | undefined;
  unitName?: (id: string) => string | undefined;
  /** May enter the buying price (the profit permission, as on Quick add). */
  profit: boolean;
  /** May add stock at the chosen shop; without it rows are sent with no quantity. */
  mayCount: boolean;
}

/** The group a row goes under when none is chosen and none was used before: the import files it under General. */
export const GENERAL = 'General';

/** The import file of the rows: a header, then a line each; the buying price only for whoever may see it. */
export function listCsv(rows: CsvRow[], profit: boolean, withQty: boolean): string {
  const header = ['description', 'category', 'unit', ...(withQty ? ['qty'] : []), ...(profit ? ['cost_price'] : []), 'sell_price', 'stock_only'];
  const body = rows.map((r) => [r.description, r.category, r.unit, ...(withQty ? [r.qty ?? ''] : []), ...(profit ? [r.cost ?? ''] : []), r.sell ?? '', r.stockOnly ? 'yes' : '']);
  return [header, ...body].map((cells) => cells.map(csvCell).join(',')).join('\n') + '\n';
}

/** Every row checked, in order; a blank row has no errors and nothing to send. */
export function checkRows(rows: ListRow[], ctx: CheckContext): RowCheck[] {
  const seen = new Map<string, number>();
  return rows.map((r, i) => {
    if (isBlank(r)) return { errors: {} };
    const errors: RowCheck['errors'] = {};
    if (r.extraColumns) errors.row = `This line had ${r.extraColumns} columns; the list takes 6. Check what went where.`;
    const qty = ctx.mayCount ? readQty(r.qty, r.addTo !== undefined) : { qty: undefined };
    if ('error' in qty) errors.qty = qty.error;
    const unit = r.unitId || ctx.pieceId || '';
    // As the import file names them: a group or unit typed or pasted that the shop does not have yet is made by the import,
    // and says so on the row; none chosen is General, which the picker shows.
    const unitText = r.pastedUnit ?? ((unit && ctx.unitName?.(unit)) || 'Piece');
    const group = ctx.groups[i] ?? '';
    const categoryText = r.pastedCategory ?? ((group && ctx.categoryName?.(group)) || GENERAL);
    if (r.addTo) {
      // Only stock is added to an item already in the list, so the quantity is needed.
      const adds = 'qty' in qty ? qty.qty : undefined;
      if (adds === undefined && !errors.qty) errors.qty = QTY_TO_ADD;
      if (r.refused) errors[fieldOf(r.refused.field)] = r.refused.message;
      if (Object.keys(errors).length > 0 || adds === undefined) return { errors };
      return { errors, send: { description: r.name.trim(), category: categoryText, unit: unitText, qty: adds, stockOnly: true } };
    }
    const key = `${nameKey(r.name)}|${unit || nameKey(unitText)}`;
    if (r.name.trim() === '') errors.name = NAME_NEEDED;
    else if (seen.has(key)) errors.name = `This name is on row ${(seen.get(key) ?? 0) + 1} too. Change one of them.`;
    else seen.set(key, i);
    const made = [r.pastedCategory ? `the group ${r.pastedCategory}` : '', r.pastedUnit ? `the unit ${r.pastedUnit}` : ''].filter(Boolean);
    const sell = readPrice(r.sell);
    if ('error' in sell) errors.sell = sell.error;
    else if (sell.minor === null) errors.sell = SELL_NEEDED;
    const cost = ctx.profit ? readPrice(r.cost) : { minor: null };
    if ('error' in cost) errors.cost = cost.error;
    if (r.refused) errors[fieldOf(r.refused.field)] = r.refused.message;
    const sellMinor = 'minor' in sell ? sell.minor : null;
    const costMinor = 'minor' in cost ? cost.minor : null;
    const below = sellMinor !== null && costMinor !== null && costMinor > 0 && sellMinor < costMinor ? SELL_BELOW : undefined;
    const fresh = made.length > 0 ? `This adds ${made.join(' and ')}, which your shop does not have yet.` : undefined;
    const warning = [below, fresh].filter(Boolean).join(' ') || undefined;
    if (Object.keys(errors).length > 0) return { errors, warning };
    return {
      errors,
      warning,
      send: {
        description: r.name.trim(),
        category: categoryText,
        unit: unitText,
        sell: plainNumber(r.sell),
        ...(costMinor ? { cost: plainNumber(r.cost) } : {}),
        ...('qty' in qty && qty.qty !== undefined ? { qty: qty.qty } : {}),
      },
    };
  });
}

/** The row field a server refusal names (`rows[3].sell_minor` gives sell). */
export function fieldOf(field: string): keyof RowCheck['errors'] {
  const f = field.replace(/^rows\[\d+\]\.?/, '');
  if (f.startsWith('description')) return 'name';
  if (f.startsWith('category')) return 'category';
  if (f.startsWith('unit')) return 'unit';
  if (f.startsWith('qty') || f.startsWith('branch')) return 'qty';
  if (f.startsWith('cost')) return 'cost';
  if (f.startsWith('sell')) return 'sell';
  return 'row';
}

/** "18 items added, 2 need a look." and its parts, plainly. */
export function resultLine(added: number, needLook: number): string {
  const done = `${added} ${added === 1 ? 'item' : 'items'} added`;
  if (needLook === 0) return `${done}.`;
  return `${done}, ${needLook} ${needLook === 1 ? 'needs' : 'need'} a look.`;
}

// ---- Paste ------------------------------------------------------------------------------------------------------------

export type Separator = 'tab' | 'spaces' | 'comma';

/**
 * The cells of each line pasted. One separator for the whole paste, so every line is read the same way: a tab (Excel and
 * Google Sheets copy with tabs), else two or more spaces (a list typed in WhatsApp), else a comma. Empty lines are dropped.
 */
export function splitPaste(text: string): { lines: string[][]; by: Separator } {
  const lines = text.split(/\r?\n/).map((l) => l.replace(/\s+$/, '')).filter((l) => l.trim() !== '');
  const by: Separator = lines.some((l) => l.includes('\t')) ? 'tab' : lines.some((l) => /\S {2,}\S/.test(l)) ? 'spaces' : 'comma';
  const cells = lines.map((l) => {
    if (by === 'tab') return l.split('\t');
    if (by === 'spaces') return l.trim().split(/ {2,}/);
    return splitComma(l);
  });
  return { lines: cells.map((c) => c.map((x) => x.trim())), by };
}

/** A comma separated line, a cell in double quotes keeping its commas ("Rice, 5kg"). */
function splitComma(line: string): string[] {
  const out: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (c === '"') {
      if (quoted && line[i + 1] === '"') {
        cell += '"';
        i++;
      } else quoted = !quoted;
    } else if (c === ',' && !quoted) {
      out.push(cell);
      cell = '';
    } else cell += c;
  }
  out.push(cell);
  return out;
}

/**
 * The columns by how many a line has. Six are the screen's own order; with fewer, the group and the unit go first, so a
 * short list of a name and prices still fills the right cells. Shown under the paste box.
 */
export const COLUMNS: Record<number, (keyof PastedCells)[]> = {
  1: ['name'],
  2: ['name', 'sell'],
  3: ['name', 'qty', 'sell'],
  4: ['name', 'qty', 'cost', 'sell'],
  5: ['name', 'unit', 'qty', 'cost', 'sell'],
  6: ['name', 'category', 'unit', 'qty', 'cost', 'sell'],
};

interface PastedCells { name: string; category: string; unit: string; qty: string; cost: string; sell: string }

const HEADER = new Set(['name', 'item', 'items', 'item name', 'description', 'product']);

/**
 * The rows a paste fills, in order, each for the person to check: a group or unit that matches one of the shop's by name is
 * chosen, one that does not is kept on the row to be chosen; a first line of column names (Name, Item) is left out. Without
 * the profit permission the buying price column is left out, as the screen has no buying price for them (`droppedCost`).
 */
export function rowsFromPaste(
  text: string,
  ctx: { categories: Category[]; units: Unit[]; profit: boolean; firstKey: number },
): { rows: ListRow[]; droppedCost: boolean; by: Separator } {
  const { lines, by } = splitPaste(text);
  const body = lines.length > 0 && HEADER.has((lines[0]?.[0] ?? '').toLowerCase()) ? lines.slice(1) : lines;
  let droppedCost = false;
  const rows = body.map((cells, i): ListRow => {
    const extra = cells.length > 6 ? cells.length : undefined;
    const order = COLUMNS[Math.min(cells.length, 6)] ?? COLUMNS[1] ?? [];
    const got: PastedCells = { name: '', category: '', unit: '', qty: '', cost: '', sell: '' };
    order.forEach((col, j) => { got[col] = cells[j] ?? ''; });
    const row = emptyRow(ctx.firstKey + i);
    row.name = got.name;
    row.qty = got.qty;
    row.sell = got.sell;
    if (ctx.profit) row.cost = got.cost;
    else if (got.cost !== '') droppedCost = true;
    if (got.category !== '') {
      const c = ctx.categories.find((x) => x.active !== false && (x.name ?? '').trim().toLowerCase() === got.category.toLowerCase());
      if (c?.id) {
        row.categoryId = c.id;
        row.categoryChosen = true;
      } else row.pastedCategory = got.category;
    }
    if (got.unit !== '') {
      const u = ctx.units.find((x) => x.active !== false && x.is_package !== true && (x.name ?? '').trim().toLowerCase() === got.unit.toLowerCase());
      if (u?.id) row.unitId = u.id;
      else row.pastedUnit = got.unit;
    }
    if (extra) row.extraColumns = extra;
    return row;
  });
  return { rows, droppedCost, by };
}

/** The paste put into the list: the blank rows first, then new rows after the last one. */
export function fillRows(rows: ListRow[], pasted: ListRow[]): ListRow[] {
  const out = [...rows];
  let next = 0;
  for (let i = 0; i < out.length && next < pasted.length; i++) {
    const r = out[i];
    if (r && isBlank(r)) out[i] = { ...(pasted[next++] as ListRow), key: r.key };
  }
  let key = nextKey(out);
  while (next < pasted.length) out.push({ ...(pasted[next++] as ListRow), key: key++ });
  return out;
}
