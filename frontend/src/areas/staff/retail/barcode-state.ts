import type { Product } from '../../../api/retail';
import { parseCount, qtyString, showQty } from './maths';

// Barcodes (ADR-070, issue #345; FR-RET-68): the formats an item accepts, the same rules as the server's
// Barcodes.java so the item form can say what is wrong before saving, how a keyboard-wedge scanner is told
// apart from a person typing, and the Code 128 bars a printed label draws.

export type BarcodeFormat = 'ean13' | 'ean8' | 'upca' | 'code128';

export type BarcodeCheck = { ok: true; code: string; format: BarcodeFormat } | { ok: false; problem: string; spreadsheet?: true };

export const MAX_BARCODE = 48;

const NAMES: Record<BarcodeFormat, string> = { ean13: 'EAN-13', ean8: 'EAN-8', upca: 'UPC-A', code128: 'Code 128' };
const DIGITS: Record<Exclude<BarcodeFormat, 'code128'>, number> = { ean13: 13, ean8: 8, upca: 12 };

/** The GS1 check digit rule shared by EAN-13, EAN-8 and UPC-A: weights 3 and 1 from the right, to a multiple of ten. */
export function checkDigitOk(digits: string): boolean {
  let sum = 0;
  for (let i = digits.length - 2, w = 3; i >= 0; i--, w = 4 - w) sum += Number(digits[i]) * w;
  return (10 - (sum % 10)) % 10 === Number(digits[digits.length - 1]);
}

export const SPREADSHEET_DAMAGE = 'This does not look like a barcode: the spreadsheet may have dropped a leading zero or turned the code into a number. Type the code exactly as printed under the bars, or set the format to code128 if it really is this.';

/** All digits of a length no barcode format has (6, 7, 9, 10, 11 or 14), or a number in scientific notation (4.00638E+12). */
export function spreadsheetDamaged(code: string): boolean {
  return /^[+-]?\d+(?:[.,]\d+)?[eE][+-]?\d+$/.test(code) || (/^\d+$/.test(code) && [6, 7, 9, 10, 11, 14].includes(code.length));
}

export function inferredFormat(code: string): BarcodeFormat {
  if (/^\d+$/.test(code)) {
    if (code.length === 13) return 'ean13';
    if (code.length === 12) return 'upca';
    if (code.length === 8) return 'ean8';
  }
  return 'code128';
}

/** The code as the server will read it, or the reason it is refused, in the server's words. */
export function checkBarcode(raw: string, asked?: BarcodeFormat): BarcodeCheck {
  const code = raw.trim();
  if (code === '') return { ok: false, problem: 'The barcode is empty.' };
  if (code.length > MAX_BARCODE) return { ok: false, problem: `A barcode has at most ${MAX_BARCODE} characters.` };
  if (!/^[!-~]+$/.test(code)) return { ok: false, problem: 'A barcode holds only letters, digits and signs, with no spaces.' };
  if (/[%;?#/\\]/.test(code)) return { ok: false, problem: 'A barcode here cannot hold % ; ? # / or \\.' };
  if (/^\.+$/.test(code)) return { ok: false, problem: 'A barcode cannot be made only of dots.' };
  if (asked === undefined && spreadsheetDamaged(code)) return { ok: false, problem: SPREADSHEET_DAMAGE, spreadsheet: true };
  const format = asked ?? inferredFormat(code);
  if (format === 'code128') return { ok: true, code, format };
  if (code.length !== DIGITS[format] || !/^\d+$/.test(code)) return { ok: false, problem: `A barcode in ${NAMES[format]} has exactly ${DIGITS[format]} digits.` };
  if (!checkDigitOk(code)) return { ok: false, problem: `The last digit of this ${NAMES[format]} barcode does not match the others. Check it was typed or scanned correctly.` };
  return { ok: true, code, format };
}

export function formatName(format: string | undefined): string {
  return NAMES[(format ?? 'code128') as BarcodeFormat] ?? format ?? '';
}

/** The key a stored code is unique under (V90 lookup_key): only a UPC-A is the thirteen digit EAN-13 a scanner may report. */
export function barcodeKey(code: string, format: string | undefined): string {
  const c = code.trim();
  return format === 'upca' && /^\d{12}$/.test(c) ? `0${c}` : c;
}

/** The keys a scanned code may be held under, most likely first: twelve digits are a UPC-A, then a Code 128. */
export function scanKeys(code: string): string[] {
  const c = code.trim();
  return /^\d{12}$/.test(c) ? [`0${c}`, c] : [c];
}

// ---- Keyboard-wedge scanners ------------------------------------------------------------------------

/**
 * A USB or Bluetooth scanner types the code far faster than a person and ends with Enter. Keys closer together than
 * this are a scanner; a person rarely types two keys within 35 ms, a scanner sends one every 5 to 20. A slow Bluetooth
 * scanner may need more, so each device may raise it up to WEDGE_GAP_MAX_MS in Notifications and app.
 */
export const WEDGE_GAP_MS = 35;
export const WEDGE_GAP_MAX_MS = 80;
export const WEDGE_GAP_CHOICES = [35, 50, 65, 80] as const;
const WEDGE_GAP_KEY = 'bms.scanner.gap-ms';
const SCAN_FOCUS_KEY = 'bms.scanner.sale-focus';

/** This device's scanner gap: the saved one between 35 and 80 ms, else 35. Storage can be blocked; then the default. */
export function wedgeGap(): number {
  try {
    const n = Number(window.localStorage.getItem(WEDGE_GAP_KEY));
    return Number.isInteger(n) && n >= WEDGE_GAP_MS && n <= WEDGE_GAP_MAX_MS ? n : WEDGE_GAP_MS;
  } catch {
    return WEDGE_GAP_MS;
  }
}

export function saveWedgeGap(ms: number): void {
  try {
    if (ms === WEDGE_GAP_MS) window.localStorage.removeItem(WEDGE_GAP_KEY);
    else window.localStorage.setItem(WEDGE_GAP_KEY, String(Math.min(Math.max(Math.round(ms), WEDGE_GAP_MS), WEDGE_GAP_MAX_MS)));
  } catch {
    // Blocked storage keeps the default.
  }
}

/** Whether the sale screen keeps the cursor in its scan box (on unless this device turned it off). */
export function saleScanFocus(): boolean {
  try {
    return window.localStorage.getItem(SCAN_FOCUS_KEY) !== 'off';
  } catch {
    return true;
  }
}

export function saveSaleScanFocus(on: boolean): void {
  try {
    if (on) window.localStorage.removeItem(SCAN_FOCUS_KEY);
    else window.localStorage.setItem(SCAN_FOCUS_KEY, 'off');
  } catch {
    // Blocked storage keeps the default.
  }
}
/** The shortest code a wedge burst is taken for (shorter bursts are a fast typist's double key). */
export const WEDGE_MIN = 4;

/**
 * How long a field must go without a key before the next key starts something new. A scanner can stall for longer
 * than the gap in the middle of a code; until the field has been this quiet, the keys belong to the same burst.
 */
export const WEDGE_IDLE_MS = 500;

/**
 * Collects the keys of one scanner burst: `key` with a printable character and its time, `enter` at the end. Returns
 * the code when Enter closes a burst of at least WEDGE_MIN characters that each came within WEDGE_GAP_MS of the last.
 */
export function createWedge(gap = WEDGE_GAP_MS, min = WEDGE_MIN) {
  let buffer = '';
  let last = 0;
  let started = false;
  let burst = false;
  return {
    /** True when this key starts something new: the first key, or one after the field was idle for WEDGE_IDLE_MS. */
    key(ch: string, at: number): boolean {
      const idle = !started || at - last >= WEDGE_IDLE_MS;
      if (idle) burst = false;
      buffer = buffer === '' || at - last > gap ? ch : buffer + ch;
      if (buffer.length >= min) burst = true;
      started = true;
      last = at;
      return idle;
    },
    /**
     * True when Enter at `at` closes a scanner burst that stalled part way: a run of scanner keys came since the field
     * was last idle and Enter follows at scanner speed, so the keys before Enter are a scanner's, not a person's.
     */
    scanner(at: number): boolean {
      return burst && at - last <= gap * 3;
    },
    enter(at: number): string | null {
      const code = at - last <= gap * 3 && buffer.length >= min ? buffer : null;
      buffer = '';
      started = false;
      burst = false;
      return code;
    },
    reset() {
      buffer = '';
    },
  };
}

/**
 * One more of a quantity typed on a form ("2" is "3", "1.5" is "2.5"), or `stepMilli` thousandths more (a counted
 * carton adds its pack factor); an empty or unreadable quantity starts again from the step.
 */
export function plusOne(qty: string | undefined, stepMilli = 1000): string {
  const milli = parseCount(qty ?? '');
  return showQty(qtyString((milli ?? 0) + stepMilli));
}

/**
 * The lines of a form after a scan: the line of this item in this unit (the base unit when `unitId` is absent) is
 * bumped, or a new line made by `make` is added at the end. Every form keeps its own line shape.
 */
export function scanInto<L extends { product: Product; unitId?: string }>(lines: L[], product: Product, unitId: string | undefined, bump: (line: L) => L, make: () => L): L[] {
  const at = lines.findIndex((l) => l.product.id === product.id && (l.unitId ?? '') === (unitId ?? ''));
  return at < 0 ? [...lines, make()] : lines.map((l, i) => (i === at ? bump(l) : l));
}

/** What a form says when a scanned barcode is on a pack it cannot use; it never adds one base unit in its place. */
export const PACK_UNUSABLE = 'This barcode is on a pack size that is switched off or not offered here. Scan the item\'s own barcode, or find the item by name.';

/** The item among `products` with this barcode, for a scan while the server cannot be reached. */
export function findByBarcodeIn(products: Product[], code: string): { product: Product; unitId?: string } | null {
  for (const key of scanKeys(code)) {
    for (const p of products) {
      const b = (p.barcodes ?? []).find((x) => barcodeKey(x.code ?? '', x.format) === key);
      if (b && p.active !== false) return { product: p, ...(b.unit_id ? { unitId: b.unit_id } : {}) };
    }
  }
  return null;
}

// ---- Code 128 (labels) --------------------------------------------------------------------------------

/** The bar and space widths of the 107 Code 128 symbols, in modules; 103 to 105 start A, B, C, 106 stop. */
export const CODE128 = [
  '212222', '222122', '222221', '121223', '121322', '131222', '122213', '122312', '132212', '221213',
  '221312', '231212', '112232', '122132', '122231', '113222', '123122', '123221', '223211', '221132',
  '221231', '213212', '223112', '312131', '311222', '321122', '321221', '312212', '322112', '322211',
  '212123', '212321', '232121', '111323', '131123', '131321', '112313', '132113', '132311', '211313',
  '231113', '231311', '112133', '112331', '132131', '113123', '113321', '133121', '313121', '211331',
  '231131', '213113', '213311', '213131', '311123', '311321', '331121', '312113', '312311', '332111',
  '314111', '221411', '431111', '111224', '111422', '121124', '121421', '141122', '141221', '112214',
  '112412', '122114', '122411', '142112', '142211', '241211', '221114', '413111', '241112', '134111',
  '111242', '121142', '121241', '114212', '124112', '124211', '411212', '421112', '421211', '212141',
  '214121', '412121', '111143', '111341', '131141', '114113', '114311', '411113', '411311', '113141',
  '114131', '311141', '411131', '211412', '211214', '211232', '2331112',
];

/** The symbol values of `text` in Code 128 set B (every printable ASCII character), with its start and check symbols. */
export function code128Values(text: string): number[] {
  const values = [104];
  for (const ch of text) {
    const c = ch.charCodeAt(0);
    if (c < 32 || c > 126) throw new Error('Code 128 B carries printable ASCII only');
    values.push(c - 32);
  }
  const check = values.reduce((sum, v, i) => sum + v * (i === 0 ? 1 : i), 0) % 103;
  return [...values, check, 106];
}

/** The widths of the bars and spaces of `text`, starting with a bar, in modules, without the quiet zones. */
export function code128Widths(text: string): number[] {
  return code128Values(text).flatMap((v) => [...(CODE128[v] ?? '')].map(Number));
}
