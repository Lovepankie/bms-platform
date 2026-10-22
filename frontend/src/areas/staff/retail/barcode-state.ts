import type { Product } from '../../../api/retail';
import { parseCount, qtyString, showQty } from './maths';

// Barcodes (ADR-070, issue #345; FR-RET-68): the formats an item accepts, the same rules as the server's
// Barcodes.java so the item form can say what is wrong before saving, how a keyboard-wedge scanner is told
// apart from a person typing, and the Code 128 bars a printed label draws.

export type BarcodeFormat = 'ean13' | 'ean8' | 'upca' | 'code128';

export type BarcodeCheck = { ok: true; code: string; format: BarcodeFormat } | { ok: false; problem: string };

export const MAX_BARCODE = 48;

const NAMES: Record<BarcodeFormat, string> = { ean13: 'EAN-13', ean8: 'EAN-8', upca: 'UPC-A', code128: 'Code 128' };
const DIGITS: Record<Exclude<BarcodeFormat, 'code128'>, number> = { ean13: 13, ean8: 8, upca: 12 };

/** The GS1 check digit rule shared by EAN-13, EAN-8 and UPC-A: weights 3 and 1 from the right, to a multiple of ten. */
export function checkDigitOk(digits: string): boolean {
  let sum = 0;
  for (let i = digits.length - 2, w = 3; i >= 0; i--, w = 4 - w) sum += Number(digits[i]) * w;
  return (10 - (sum % 10)) % 10 === Number(digits[digits.length - 1]);
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
  if (/[/\\]/.test(code)) return { ok: false, problem: 'A barcode here cannot hold a slash or a backslash.' };
  const format = asked ?? inferredFormat(code);
  if (format === 'code128') return { ok: true, code, format };
  if (code.length !== DIGITS[format] || !/^\d+$/.test(code)) return { ok: false, problem: `A barcode in ${NAMES[format]} has exactly ${DIGITS[format]} digits.` };
  if (!checkDigitOk(code)) return { ok: false, problem: `The last digit of this ${NAMES[format]} barcode does not match the others. Check it was typed or scanned correctly.` };
  return { ok: true, code, format };
}

export function formatName(format: string | undefined): string {
  return NAMES[(format ?? 'code128') as BarcodeFormat] ?? format ?? '';
}

/** The key a code is looked up under: a twelve digit UPC-A is the thirteen digit EAN-13 a scanner may report. */
export function barcodeKey(code: string): string {
  const c = code.trim();
  return /^\d{12}$/.test(c) ? `0${c}` : c;
}

// ---- Keyboard-wedge scanners ------------------------------------------------------------------------

/**
 * A USB or Bluetooth scanner types the code far faster than a person and ends with Enter. Keys closer together than
 * this are a scanner; a person rarely types two keys within 35 ms, a scanner sends one every 5 to 20.
 */
export const WEDGE_GAP_MS = 35;
/** The shortest code a wedge burst is taken for (shorter bursts are a fast typist's double key). */
export const WEDGE_MIN = 4;

/**
 * Collects the keys of one scanner burst: `key` with a printable character and its time, `enter` at the end. Returns
 * the code when Enter closes a burst of at least WEDGE_MIN characters that each came within WEDGE_GAP_MS of the last.
 */
export function createWedge(gap = WEDGE_GAP_MS, min = WEDGE_MIN) {
  let buffer = '';
  let last = 0;
  return {
    key(ch: string, at: number) {
      buffer = at - last <= gap ? buffer + ch : ch;
      last = at;
    },
    enter(at: number): string | null {
      const code = at - last <= gap * 3 && buffer.length >= min ? buffer : null;
      buffer = '';
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

/** The item among `products` with this barcode, for a scan while the server cannot be reached. */
export function findByBarcodeIn(products: Product[], code: string): { product: Product; unitId?: string } | null {
  const key = barcodeKey(code);
  for (const p of products) {
    const b = (p.barcodes ?? []).find((x) => barcodeKey(x.code ?? '') === key);
    if (b && p.active !== false) return { product: p, ...(b.unit_id ? { unitId: b.unit_id } : {}) };
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
