// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, createEvent, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { LabelsPage } from './labels';
import { SaleForm } from './sale';
import { TransferForm } from './transfer';

// Scanning on the forms and the label sheet (ADR-070, issue #345; FR-RET-68): a keyboard-wedge scan adds the item at 1
// and the next scan of it adds one more, an unknown code says so (with Add this barcode to a product for an
// administrator only), the camera button shows only where the browser can open a camera, and the label sheet draws a
// barcode per label. The retail adapter is the fabricated mock; codes and items are made up.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const BRANCH = '00000000-0000-4000-8000-0000000000a1';
const LAMP = '00000000-0000-4000-8000-0000000b0003';
const EAN = '4006381333931';

beforeEach(() => {
  Object.assign(retail, createMockRetail());
});
afterEach(() => {
  cleanup();
  sessionStorage.clear();
  Reflect.deleteProperty(navigator, 'mediaDevices');
});

function screenOf(node: ReactElement, role = 'admin') {
  const me = mockMe(role);
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: BRANCH }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

/** A scanner: the code's keys in a burst on whatever has focus, then Enter. */
function wedge(code: string) {
  const target = document.activeElement ?? document.body;
  for (const ch of code) fireEvent.keyDown(target, { key: ch });
  fireEvent.keyDown(target, { key: 'Enter' });
}

/**
 * A key as a browser handles it, at time `at`: keydown, then, unless prevented, the character typed into the focused field
 * over its selection with a real input event, so React's onChange runs as it would for a scanner.
 */
function press(key: string, at: number) {
  const target = document.activeElement ?? document.body;
  const down = createEvent.keyDown(target, { key });
  Object.defineProperty(down, 'timeStamp', { value: at });
  fireEvent(target, down);
  if (down.defaultPrevented || key.length !== 1 || !(target instanceof HTMLInputElement)) return;
  const start = target.selectionStart ?? target.value.length;
  const end = target.selectionEnd ?? start;
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(target, target.value.slice(0, start) + key + target.value.slice(end));
  target.setSelectionRange(start + 1, start + 1);
  target.dispatchEvent(new Event('input', { bubbles: true }));
}

/** A burst of `code` with `gapMs` between keys, then Enter, typed with `press`. */
function typed(code: string, gapMs: number, from = 1000) {
  let at = from;
  for (const ch of code) press(ch, (at += gapMs));
  press('Enter', at + gapMs);
}

const BULB15 = '00000000-0000-4000-8000-0000000b0004';

const qtyOf = (i: number) => (screen.getByLabelText(/^Quantity/, { selector: `#qty-${i}` }) as HTMLInputElement).value;

describe('FR-RET-68 scanning on the sale form', () => {
  it('adds a scanned item at 1, and one more on the next scan', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge(EAN);
    await waitFor(() => expect(screen.getByText('Added LED bulb 9W screw.')).toBeTruthy());
    expect(qtyOf(0)).toBe('1');
    wedge(EAN);
    await waitFor(() => expect(qtyOf(0)).toBe('2'));
    expect(document.querySelectorAll('[id^="qty-"]')).toHaveLength(1);
  });

  it('leaves a focused quantity as it was when a scan lands in it with the cursor in the middle', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    await retail.addBarcode(BULB15, { code: 'TEST-BULB15' });
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge(EAN);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    const qty = document.getElementById('qty-0') as HTMLInputElement;
    fireEvent.change(qty, { target: { value: '12' } });
    qty.focus();
    qty.setSelectionRange(1, 1);
    typed('TEST-BULB15', 8);
    await waitFor(() => expect(qtyOf(1)).toBe('1'));
    expect(qtyOf(0)).toBe('12');
    expect([qty.selectionStart, qty.selectionEnd]).toEqual([1, 1]);
  });

  it('leaves a focused quantity as it was when a scan lands in it with the text selected', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    await retail.addBarcode(BULB15, { code: 'TEST-BULB15' });
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge(EAN);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    const qty = document.getElementById('qty-0') as HTMLInputElement;
    fireEvent.change(qty, { target: { value: '12' } });
    qty.focus();
    qty.select();
    typed('TEST-BULB15', 8);
    await waitFor(() => expect(qtyOf(1)).toBe('1'));
    expect(qtyOf(0)).toBe('12');
    expect([qty.selectionStart, qty.selectionEnd]).toEqual([0, 2]);
  });

  it('takes keys slower than a scanner for a person typing, not a scan', async () => {
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP' });
    screenOf(<SaleForm branchId={BRANCH} />);
    (document.activeElement as HTMLElement | null)?.blur();
    typed('TEST-LAMP', 150);
    await new Promise((r) => setTimeout(r, 400));
    expect(document.querySelectorAll('[id^="qty-"]')).toHaveLength(0);
    expect(screen.queryByText(/^Added /)).toBeNull();
  });

  it('adds both items when a second scan comes while the first is still being looked up', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    await retail.addBarcode(BULB15, { code: 'TEST-BULB15' });
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge(EAN);
    wedge('TEST-BULB15');
    wedge(EAN);
    await waitFor(() => expect(document.querySelectorAll('[id^="qty-"]')).toHaveLength(2));
    await waitFor(() => expect(qtyOf(0)).toBe('2'));
    expect(qtyOf(1)).toBe('1');
  });

  it('takes a code typed in the scan box when Enter is pressed', async () => {
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP' });
    screenOf(<SaleForm branchId={BRANCH} />);
    const box = screen.getByLabelText('Scan or type a barcode, then press Enter');
    fireEvent.change(box, { target: { value: 'TEST-LAMP' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
  });

  it('says plainly that a code is unknown, and offers an administrator to add it to an item', async () => {
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge('TEST-UNKNOWN');
    await waitFor(() => expect(screen.getByTestId('scan-unknown').textContent).toContain('No item has the barcode TEST-UNKNOWN.'));
    fireEvent.click(screen.getByRole('button', { name: 'Add this barcode to a product' }));
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Choose LED bulb 9W screw' }).length).toBeGreaterThan(0));
    fireEvent.click(screen.getAllByRole('button', { name: 'Choose LED bulb 9W screw' })[0] as HTMLElement);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    expect((await retail.findByBarcode('TEST-UNKNOWN'))?.product?.id).toBe(LAMP);
  });

  it('gives a seller the unknown code message without the button', async () => {
    screenOf(<SaleForm branchId={BRANCH} />, 'sales');
    wedge('TEST-UNKNOWN');
    await waitFor(() => expect(screen.getByTestId('scan-unknown').textContent).toContain('Ask an administrator'));
    expect(screen.queryByRole('button', { name: 'Add this barcode to a product' })).toBeNull();
  });

  it('hides the camera Scan button where the browser cannot open a camera, and shows it where it can', () => {
    const first = screenOf(<SaleForm branchId={BRANCH} />);
    expect(screen.queryByRole('button', { name: 'Scan' })).toBeNull();
    first.unmount();
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: vi.fn() } });
    screenOf(<SaleForm branchId={BRANCH} />);
    expect(screen.getByRole('button', { name: 'Scan' })).toBeTruthy();
  });
});

describe('FR-RET-68 scanning on the move stock form', () => {
  it('adds the scanned item and bumps it on the next scan', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<TransferForm fromBranchId={BRANCH} />);
    wedge(EAN);
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('1'));
    wedge(EAN);
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('2'));
  });
});

describe('FR-RET-68 scanning a pack on the move stock form', () => {
  it('adds the pack factor in the base unit for a barcode on a pack', async () => {
    const ROLL = '00000000-0000-4000-8000-0000000d0001';
    await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<TransferForm fromBranchId={BRANCH} />);
    wedge('TEST-LAMP-BOX');
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('10'));
    wedge(EAN);
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('11'));
    wedge('TEST-LAMP-BOX');
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('21'));
    expect(document.querySelectorAll('[id^="tq-"]')).toHaveLength(1);
  });
});

describe('FR-RET-68 the label sheet', () => {
  it('makes a code for an item without one and draws a barcode on each label', async () => {
    screenOf(<LabelsPage />);
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Add LED bulb 9W screw' }).length).toBeGreaterThan(0));
    fireEvent.click(screen.getAllByRole('button', { name: 'Add LED bulb 9W screw' })[0] as HTMLElement);
    fireEvent.change(screen.getByLabelText('Labels'), { target: { value: '3' } });
    fireEvent.click(screen.getByRole('button', { name: 'Make the labels' }));
    await waitFor(() => expect(screen.getByTestId('label-sheet')).toBeTruthy());
    const labels = screen.getByTestId('label-sheet').querySelectorAll('.label');
    expect(labels).toHaveLength(3);
    expect(labels[0]?.textContent).toContain('LED bulb 9W screw');
    expect(labels[0]?.querySelector('svg rect')).not.toBeNull();
    expect(screen.getAllByRole('img', { name: /^Barcode IN\d{8}$/ })).toHaveLength(3);
    expect((screen.getByRole('button', { name: 'Print' }) as HTMLButtonElement).disabled).toBe(false);
  });
});
