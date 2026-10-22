import { describe, expect, it } from 'vitest';
import type { Product } from '../../../api/retail';
import { CODE128, barcodeKey, checkBarcode, code128Values, code128Widths, createWedge, findByBarcodeIn, plusOne, scanInto } from './barcode-state';

// Barcodes (ADR-070, issue #345; FR-RET-68): the same format rules as the server, the scanner burst, the line a scan
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

describe('a keyboard-wedge scanner', () => {
  it('is a fast burst closed by Enter', () => {
    const wedge = createWedge();
    [...'4006381333931'].forEach((ch, i) => wedge.key(ch, 1000 + i * 8));
    expect(wedge.enter(1000 + 13 * 8)).toBe('4006381333931');
  });

  it('is not a person typing, nor a burst too short to be a code', () => {
    const wedge = createWedge();
    [...'1234'].forEach((ch, i) => wedge.key(ch, 1000 + i * 150));
    expect(wedge.enter(1700)).toBeNull();
    [...'12'].forEach((ch, i) => wedge.key(ch, 2000 + i * 5));
    expect(wedge.enter(2010)).toBeNull();
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
