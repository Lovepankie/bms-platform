import { describe, expect, it } from 'vitest';
import type { Product } from '../../../api/retail';
import { CODE128, barcodeKey, checkBarcode, checkDigitOk, code128Values, code128Widths, ean13Modules, findByBarcodeIn, isEan13, plusOne, scanInto, shopEan, touchedLine } from './barcode-state';

// Barcodes (ADR-070, issue #345; FR-RET-68): the same format rules as the server, the line a scan
// adds or bumps, and the Code 128 bars of a label. Fabricated codes and items.

describe('FR-RET-68 barcode formats', () => {
  it('accepts an EAN-13, an EAN-8 and a UPC-A whose check digit is right, and refuses one that is wrong', () => {
    expect(checkBarcode('4006381333931')).toEqual({ ok: true, code: '4006381333931', format: 'ean13' });
    expect(checkBarcode('96385074')).toMatchObject({ ok: true, format: 'ean8' });
    expect(checkBarcode(' 036000291452 ')).toMatchObject({ ok: true, code: '036000291452', format: 'upca' });
    expect(checkBarcode('4006381333932')).toMatchObject({ ok: false, problem: expect.stringContaining('last digit') });
    expect(checkBarcode('96385075').ok).toBe(false);
    expect(checkBarcode('036000291453').ok).toBe(false);
  });

  it('takes any other printable code as Code 128, and refuses spaces, slashes and long codes', () => {
    expect(checkBarcode('TEST-ITEM-01')).toMatchObject({ ok: true, format: 'code128' });
    expect(checkBarcode('4006381333932', 'code128').ok).toBe(true);
    expect(checkBarcode('with space').ok).toBe(false);
    for (const dots of ['.', '..', '...']) expect(checkBarcode(dots, 'code128')).toMatchObject({ ok: false, problem: expect.stringContaining('only of dots') });
    expect(checkBarcode('A.B').ok).toBe(true);
    for (const sign of ['/', '\\', '%', ';', '?', '#']) expect(checkBarcode(`A${sign}B`, 'code128')).toMatchObject({ ok: false, problem: expect.stringContaining('cannot hold') });
    expect(checkBarcode('x'.repeat(49)).ok).toBe(false);
    expect(checkBarcode('').ok).toBe(false);
  });

  it('refuses a code a spreadsheet may have damaged unless Code 128 is asked for', () => {
    for (const code of ['123456', '1234567', '123456789', '1234567890', '36000291452', '12345678901231', '4.00638E+12', '4,00638e12']) {
      expect(checkBarcode(code)).toMatchObject({ ok: false, spreadsheet: true, problem: expect.stringContaining('the spreadsheet may have dropped a leading zero or turned the code into a number') });
      expect(checkBarcode(code, 'code128').ok).toBe(true);
    }
    expect(checkBarcode('12345').ok).toBe(true);
    expect(checkBarcode('TEST-E12').ok).toBe(true);
  });

  it('keys a UPC-A as the EAN-13 a scanner may report for it, and twelve digits in Code 128 as themselves', () => {
    expect(barcodeKey('036000291452', 'upca')).toBe(barcodeKey('0036000291452', 'ean13'));
    expect(barcodeKey('036000291452', 'code128')).toBe('036000291452');
    expect(barcodeKey('TEST-1', 'code128')).toBe('TEST-1');
  });
});

const lamp: Product = { id: 'p1', code: 'L-1', description: 'Test Lamp', unit: 'piece', barcodes: [{ id: 'b1', code: '036000291452', format: 'upca', generated: false }] };
const carton: Product = { id: 'p2', code: 'C-1', description: 'Test Carton', unit: 'piece', barcodes: [{ id: 'b2', code: 'TEST-CTN', format: 'code128', unit_id: 'u-ctn', generated: false }] };

describe('a scan on a form', () => {
  type Line = { product: Product; qty: string; unitId?: string };
  const bump = (l: Line) => ({ ...l, qty: plusOne(l.qty) });

  it('adds the item at 1, then adds one more to its line', () => {
    const once = scanInto<Line>([], lamp, undefined, bump, () => ({ product: lamp, qty: '1' }));
    expect(once).toEqual([{ product: lamp, qty: '1' }]);
    const twice = scanInto(once, lamp, undefined, bump, () => ({ product: lamp, qty: '1' }));
    expect(twice).toEqual([{ product: lamp, qty: '2' }]);
  });

  it('keeps a pack line apart from a line of the item\'s own unit', () => {
    const lines: Line[] = [{ product: carton, qty: '3' }];
    const next = scanInto(lines, carton, 'u-ctn', bump, () => ({ product: carton, qty: '1', unitId: 'u-ctn' }));
    expect(next).toHaveLength(2);
  });

  it('adds one to a typed quantity, or a pack factor when counting', () => {
    expect(plusOne('1.5')).toBe('2.5');
    expect(plusOne('')).toBe('1');
    expect(plusOne('2', 12000)).toBe('14');
  });

  it('finds an item by any of its barcodes in what the screen has already read, for a scan offline', () => {
    expect(findByBarcodeIn([lamp, carton], '0036000291452')?.product.id).toBe('p1');
    expect(findByBarcodeIn([lamp, carton], 'TEST-CTN')).toEqual({ product: carton, unitId: 'u-ctn' });
    expect(findByBarcodeIn([lamp, { ...carton, active: false }], 'TEST-CTN')).toBeNull();
    expect(findByBarcodeIn([lamp], '036000291452')?.product.id).toBe('p1');
    const printed: Product = { id: 'p3', code: 'P-1', description: 'Test Printed', unit: 'piece', barcodes: [{ id: 'b3', code: '036000291452', format: 'code128', generated: false }] };
    expect(findByBarcodeIn([printed], '036000291452')?.product.id).toBe('p3');
    expect(findByBarcodeIn([printed], '0036000291452')).toBeNull();
  });
});

describe('Code 128 labels', () => {
  it('has 106 symbols of 11 modules and a stop of 13', () => {
    expect(CODE128).toHaveLength(107);
    CODE128.slice(0, 106).forEach((p) => expect([...p].reduce((a, b) => a + Number(b), 0)).toBe(11));
    expect([...(CODE128[106] ?? '')].reduce((a, b) => a + Number(b), 0)).toBe(13);
  });

  it('starts in set B, ends with the weighted check symbol and the stop', () => {
    // Start B (104) + 'A' (33) at 1 + 'B' (34) at 2: 104 + 33 + 68 = 205, and 205 mod 103 = 102.
    expect(code128Values('AB')).toEqual([104, 33, 34, 102, 106]);
    expect(code128Widths('IN00000001').reduce((a, b) => a + b, 0)).toBe(13 * 11 + 2);
  });
});

describe('EAN-13 shop labels (#524)', () => {
  // The space, bar, space, bar widths of each digit in the L set, from the GS1 table; G is the same read backwards and
  // R the same starting with a bar. Written as widths here so the test does not share the code's bit table.
  const WIDTHS = ['3211', '2221', '2122', '1411', '1132', '1231', '1114', '1312', '1213', '3112'];
  const PARITY = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG', 'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL'];
  const runs = (bits: string) => (bits.match(/0+|1+/g) ?? []).map((r) => r.length).join('');

  /** Reads the digits back out of the modules, by bar widths and the left half's L and G pattern. */
  function decode(modules: string): string {
    let parity = '';
    let left = '';
    for (let i = 0; i < 6; i++) {
      const group = modules.slice(3 + i * 7, 10 + i * 7);
      const w = runs(group);
      const odd = isL(group);
      parity += odd ? 'L' : 'G';
      left += String(odd ? WIDTHS.indexOf(w) : WIDTHS.indexOf([...w].reverse().join('')));
    }
    let right = '';
    for (let i = 0; i < 6; i++) right += String(WIDTHS.indexOf(runs(modules.slice(50 + i * 7, 57 + i * 7))));
    return `${PARITY.indexOf(parity)}${left}${right}`;
  }
  // An L digit has an odd number of bar modules, a G digit an even number.
  const isL = (group: string) => [...group].filter((b) => b === '1').length % 2 === 1;

  it('makes the shop code 210, nine digits and the GS1 check digit', () => {
    expect(shopEan(42)).toBe('2100000000425');
    expect(shopEan(1)).toBe('2100000000012');
    for (const n of [1, 7, 42, 123456789, 999999999]) {
      const code = shopEan(n);
      expect(code).toMatch(/^210\d{10}$/);
      expect(checkDigitOk(code)).toBe(true);
      expect(checkBarcode(code)).toMatchObject({ ok: true, format: 'ean13' });
    }
  });

  it('draws 95 modules with the start, centre and end guards', () => {
    const m = ean13Modules('2100000000425');
    expect(m).toHaveLength(95);
    expect(m.slice(0, 3)).toBe('101');
    expect(m.slice(45, 50)).toBe('01010');
    expect(m.slice(92)).toBe('101');
  });

  it('encodes every digit so it reads back, the first by the left half pattern', () => {
    for (const code of ['2100000000425', '4006381333931', '5901234123457', shopEan(987654321)]) expect(decode(ean13Modules(code))).toBe(code);
  });

  it('refuses a code that is not an EAN-13', () => {
    expect(isEan13('2100000000426')).toBe(false);
    expect(isEan13('IN00000001')).toBe(false);
    expect(() => ean13Modules('2100000000426')).toThrow();
  });
});

describe('the line an add touched (#504)', () => {
  const a = { id: 'a' }, b = { id: 'b' }, b2 = { id: 'b', qty: 2 };
  it('is the new last line when one was added', () => {
    expect(touchedLine([a], [a, b])).toEqual({ index: 1, created: true });
  });
  it('is the line that changed when one was topped up, not the last line of that item', () => {
    expect(touchedLine([b, a], [b2, a])).toEqual({ index: 0, created: false });
  });
  it('is none when nothing changed', () => {
    expect(touchedLine([a], [a])).toEqual({ index: -1, created: false });
  });
});
