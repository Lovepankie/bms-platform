// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, createEvent, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { LabelsPage } from './labels';
import { ReceiveBlindPage } from './goods-receive';
import { RestockForm } from './restock';
import { SaleForm } from './sale';
import { StocktakePage } from './stocktake';
import { ScannerSettings } from './scan';
import { TransferForm } from './transfer';
import { wedgeGap } from './barcode-state';

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
  localStorage.clear();
  Reflect.deleteProperty(navigator, 'mediaDevices');
});

function screenOf(node: ReactElement, role = 'admin', branch = BRANCH, capabilities?: string[]) {
  const me = capabilities ? { ...mockMe(role), module_capabilities: { retail: capabilities } } : mockMe(role);
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}


/**
 * A key as a browser handles it, at time `at`: keydown, then, unless prevented, the character typed into the focused field
 * over its selection with a real input event, so React's onChange runs as it would for a scanner.
 */
function press(key: string, at: number): Event {
  const target = document.activeElement ?? document.body;
  const down = createEvent.keyDown(target, { key });
  Object.defineProperty(down, 'timeStamp', { value: at });
  fireEvent(target, down);
  if (down.defaultPrevented || key.length !== 1 || !(target instanceof HTMLInputElement)) return down;
  const start = target.selectionStart ?? target.value.length;
  const end = target.selectionEnd ?? start;
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set?.call(target, target.value.slice(0, start) + key + target.value.slice(end));
  target.setSelectionRange(start + 1, start + 1);
  target.dispatchEvent(new Event('input', { bubbles: true }));
  return down;
}

let clock = 0;

/** A burst of `code` with `gapMs` between keys, then Enter, typed with `press` into whatever has focus. */
function typed(code: string, gapMs: number) {
  let at = (clock += 10000);
  for (const ch of code) press(ch, (at += gapMs));
  press('Enter', (clock = at + gapMs));
}

/**
 * A scanner that stalls once: the first `split` keys of `code` 8 ms apart, a pause of `stallMs`, the rest, then Enter.
 * Returns the Enter key event, so a test can see whether the form would have been submitted.
 */
function stalled(code: string, split: number, stallMs: number): Event {
  let at = (clock += 10000);
  [...code].forEach((ch, i) => press(ch, (at += i === split ? stallMs : 8)));
  return press('Enter', (clock = at + 8));
}

/** A scanner: the code's keys in a fast burst on whatever has focus (the sale's scan box, or a field), then Enter. */
const wedge = (code: string) => typed(code, 8);

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

  it('leaves a focused price as it was when a scan lands in it', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    await retail.addBarcode(BULB15, { code: 'TEST-BULB15' });
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge(EAN);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    const price = document.getElementById('price-0') as HTMLInputElement;
    fireEvent.change(price, { target: { value: '5500' } });
    price.focus();
    price.setSelectionRange(2, 2);
    typed('TEST-BULB15', 8);
    await waitFor(() => expect(qtyOf(1)).toBe('1'));
    expect(price.value).toBe('5500');
    expect(price.selectionStart).toBe(2);
  });

  it('looks a scan that stalls part way up as the whole code, keeps the quantity it had, and never lets it submit the sale', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge(EAN);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    const qty = document.getElementById('qty-0') as HTMLInputElement;
    const submitted = vi.fn((e: Event) => e.preventDefault());
    qty.form?.addEventListener('submit', submitted);
    fireEvent.change(qty, { target: { value: '12' } });
    qty.focus();
    qty.setSelectionRange(2, 2);
    // A pause longer than the gap in the middle of the code: the keys after it join the code, so the full EAN is found
    // and its line goes from the 12 typed to 13, with nothing of the code left in the quantity.
    expect(stalled(EAN, 6, 120).defaultPrevented).toBe(true);
    expect(qtyOf(0)).toBe('12');
    await waitFor(() => expect(qtyOf(0)).toBe('13'));
    expect(screen.queryByTestId('scan-unknown')).toBeNull();
    // A pause just before the end, leaving a trailing fragment too short to be a code: still the full EAN, no submit.
    qty.setSelectionRange(2, 2);
    expect(stalled(EAN, 11, 120).defaultPrevented).toBe(true);
    expect(qtyOf(0)).toBe('13');
    expect(qty.selectionStart).toBe(2);
    await waitFor(() => expect(qtyOf(0)).toBe('14'));
    expect(submitted).not.toHaveBeenCalled();
  });

  it('keeps a quantity typed just before a scan, even within the idle time', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    await retail.addBarcode(BULB15, { code: 'TEST-BULB15' });
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge(EAN);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    const qty = document.getElementById('qty-0') as HTMLInputElement;
    qty.focus();
    qty.select();
    let at = (clock += 10000);
    press('5', at);
    expect(qtyOf(0)).toBe('5');
    // The scan starts 200 ms after the 5, inside the 500 ms idle time.
    at += 200;
    for (const ch of 'TEST-BULB15') press(ch, (at += 8));
    press('Enter', (clock = at + 8));
    await waitFor(() => expect(qtyOf(1)).toBe('1'));
    expect(qtyOf(0)).toBe('5');
  });

  it('waits in the scan box, so a scanner too slow to be told from typing still adds the item', async () => {
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP' });
    screenOf(<SaleForm branchId={BRANCH} />);
    expect(document.activeElement?.id).toBe('sale-scan');
    typed('TEST-LAMP', 150);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    expect(document.activeElement?.id).toBe('sale-scan');
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

  it('lets an administrator add a GTIN-14 as printed (Code 128), as the item form does', async () => {
    const GTIN = '10012345678902';
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge(GTIN);
    await waitFor(() => expect(screen.getByTestId('scan-unknown').textContent).toContain(`No item has the barcode ${GTIN}.`));
    fireEvent.click(screen.getByRole('button', { name: 'Add this barcode to a product' }));
    fireEvent.click(await screen.findByRole('checkbox', { name: /This code is exactly as printed \(Code 128\)/ }));
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Choose LED bulb 9W screw' }).length).toBeGreaterThan(0));
    fireEvent.click(screen.getAllByRole('button', { name: 'Choose LED bulb 9W screw' })[0] as HTMLElement);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    expect((await retail.findByBarcode(GTIN))?.barcode?.format).toBe('code128');
  });

  it('offers no as-printed choice for a code that cannot be spreadsheet damage', async () => {
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge('TEST-UNKNOWN');
    fireEvent.click(await screen.findByRole('button', { name: 'Add this barcode to a product' }));
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Choose LED bulb 9W screw' }).length).toBeGreaterThan(0));
    expect(screen.queryByRole('checkbox', { name: /exactly as printed/ })).toBeNull();
  });

  it('gives a seller the unknown code message without the button', async () => {
    screenOf(<SaleForm branchId={BRANCH} />, 'sales');
    wedge('TEST-UNKNOWN');
    await waitFor(() => expect(screen.getByTestId('scan-unknown').textContent).toContain('Ask an administrator'));
    expect(screen.queryByRole('button', { name: 'Add this barcode to a product' })).toBeNull();
  });

  it('says the camera needs the secure address on a plain http page', () => {
    Object.defineProperty(window, 'isSecureContext', { configurable: true, value: false });
    try {
      screenOf(<SaleForm branchId={BRANCH} />);
      expect(screen.getByText('The camera works only on the secure (https) address.')).toBeTruthy();
      expect(screen.queryByRole('button', { name: 'Scan' })).toBeNull();
    } finally {
      Reflect.deleteProperty(window, 'isSecureContext');
    }
  });

  it('hides the camera Scan button where the browser cannot open a camera, and shows it where it can', () => {
    Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
    const first = screenOf(<SaleForm branchId={BRANCH} />);
    expect(screen.queryByRole('button', { name: 'Scan' })).toBeNull();
    first.unmount();
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: vi.fn() } });
    screenOf(<SaleForm branchId={BRANCH} />);
    expect(screen.getByRole('button', { name: 'Scan' })).toBeTruthy();
    expect(screen.queryByText('The camera works only on the secure (https) address.')).toBeNull();
    Reflect.deleteProperty(window, 'isSecureContext');
  });
});

describe('FR-RET-68 scanning on the stock count', () => {
  it('clears the search so the counted item shows, and counts one more on each scan', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<StocktakePage />);
    const search = await screen.findByLabelText('Search by name, code or category');
    await waitFor(() => expect(document.getElementById(`count-${LAMP}`)).not.toBeNull());
    fireEvent.change(search, { target: { value: 'solar' } });
    await waitFor(() => expect(document.getElementById(`count-${LAMP}`)).toBeNull());
    (document.activeElement as HTMLElement | null)?.blur();
    wedge(EAN);
    await waitFor(() => expect(document.querySelector('.scan-bar')?.textContent).toContain('Added'));
    await waitFor(() => expect((document.getElementById(`count-${LAMP}`) as HTMLInputElement | null)?.value).toBe('1'));
    expect((search as HTMLInputElement).value).toBe('');
  });

  it('refuses an item that is not on the count sheet of the branch', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    const all = await retail.listStock({ branchId: BRANCH });
    retail.listStock = async () => all.filter((r) => r.product_id !== LAMP);
    screenOf(<StocktakePage />);
    await screen.findByLabelText('Search by name, code or category');
    await waitFor(() => expect(document.querySelectorAll('[id^="count-"]').length).toBeGreaterThan(1));
    (document.activeElement as HTMLElement | null)?.blur();
    wedge(EAN);
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain('is not on the count sheet of this branch'));
    expect(document.getElementById(`count-${LAMP}`)).toBeNull();
  });

  it('refuses a pack barcode while pack sizes are off, and counts no single piece in its place', async () => {
    const ROLL = '00000000-0000-4000-8000-0000000d0001';
    await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    screenOf(<StocktakePage />);
    await screen.findByLabelText('Search by name, code or category');
    await waitFor(() => expect(document.getElementById(`count-${LAMP}`)).not.toBeNull());
    (document.activeElement as HTMLElement | null)?.blur();
    wedge('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain('pack size'));
    expect((document.getElementById(`count-${LAMP}`) as HTMLInputElement).value).toBe('');
  });
});

describe('FR-RET-68 scanning on the restock form', () => {
  it('asks for a branch first when none is chosen at the top, and adds the item there once one is', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    const first = screenOf(<RestockForm />, 'admin', '');
    (document.activeElement as HTMLElement | null)?.blur();
    wedge(EAN);
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain('Choose the branch at the top of the page first'));
    first.unmount();
    screenOf(<RestockForm />);
    (document.activeElement as HTMLElement | null)?.blur();
    wedge(EAN);
    await waitFor(() => expect((document.getElementById(`q-0-${BRANCH}`) as HTMLInputElement | null)?.value).toBe('1'));
  });
});

describe('FR-RET-68 the scanner settings of this device', () => {
  it('lets a slow scanner be chosen, kept on this device, and then takes its bursts as scans', async () => {
    render(<ScannerSettings />);
    expect(wedgeGap()).toBe(35);
    fireEvent.change(screen.getByLabelText('Scanner speed'), { target: { value: '80' } });
    expect(wedgeGap()).toBe(80);
    cleanup();
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP' });
    screenOf(<TransferForm fromBranchId={BRANCH} />);
    typed('TEST-LAMP', 60);
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('1'));
  });

  it('keeps 35 ms for a saved value out of range, and can stop the sale holding the cursor in the scan box', () => {
    localStorage.setItem('bms.scanner.gap-ms', '500');
    expect(wedgeGap()).toBe(35);
    render(<ScannerSettings />);
    fireEvent.click(screen.getByRole('checkbox', { name: /Keep the cursor in the scan box/ }));
    cleanup();
    screenOf(<SaleForm branchId={BRANCH} />);
    expect(document.activeElement?.id).not.toBe('sale-scan');
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

describe('FR-RET-68 a pack barcode the sale cannot use', () => {
  const ROLL = '00000000-0000-4000-8000-0000000d0001';

  it('refuses a pack barcode while pack sizes are off, and adds no single piece in its place', async () => {
    await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain('pack size'));
    expect(document.querySelectorAll('[id^="qty-"]')).toHaveLength(0);
  });

  it('says so when the pack the barcode is on was switched off', async () => {
    const pack = await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    await retail.updatePack(LAMP, ROLL, pack.version ?? 1, { active: false });
    screenOf(<SaleForm branchId={BRANCH} />);
    wedge('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('switched off'));
    expect(document.querySelectorAll('[id^="qty-"]')).toHaveLength(0);
  });
});

describe('FR-RET-68 a pack barcode the restock and goods received cannot use', () => {
  const ROLL = '00000000-0000-4000-8000-0000000d0001';

  it('refuses a pack barcode on the restock while pack sizes are off, and adds no single piece in its place', async () => {
    await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    screenOf(<RestockForm />);
    (document.activeElement as HTMLElement | null)?.blur();
    wedge('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain('pack size'));
    expect(document.getElementById(`q-0-${BRANCH}`)).toBeNull();
  });

  it('refuses a pack barcode on goods received while pack sizes are off, and receives no single piece in its place', async () => {
    const read = await retail.capabilities();
    await retail.changeCapabilities({ purchasing: true }, read.version);
    await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<ReceiveBlindPage />, 'admin', BRANCH, ['purchasing']);
    await screen.findByLabelText('Scan or type a barcode, then press Enter');
    (document.activeElement as HTMLElement | null)?.blur();
    wedge('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain('pack size'));
    expect(screen.queryByText(/^Added /)).toBeNull();
    // The item's own barcode is still received, at 1.
    wedge(EAN);
    await waitFor(() => expect(screen.getByText('Added LED bulb 9W screw.')).toBeTruthy());
  });
});

describe('FR-RET-68 scanning a pack on the move stock form', () => {
  it('adds the pack factor in the base unit for a barcode on a pack', async () => {
    const ROLL = '00000000-0000-4000-8000-0000000d0001';
    await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<TransferForm fromBranchId={BRANCH} />, 'admin', BRANCH, ['units']);
    wedge('TEST-LAMP-BOX');
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('10'));
    wedge(EAN);
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('11'));
    wedge('TEST-LAMP-BOX');
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('21'));
    expect(document.querySelectorAll('[id^="tq-"]')).toHaveLength(1);
  });

  it('refuses a barcode on a pack that was switched off, and moves no single piece in its place', async () => {
    const ROLL = '00000000-0000-4000-8000-0000000d0001';
    const pack = await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    await retail.updatePack(LAMP, ROLL, pack.version ?? 1, { active: false });
    screenOf(<TransferForm fromBranchId={BRANCH} />, 'admin', BRANCH, ['units']);
    wedge('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('switched off'));
    expect(document.querySelectorAll('[id^="tq-"]')).toHaveLength(0);
  });

  it('refuses a pack barcode while pack sizes are off', async () => {
    const ROLL = '00000000-0000-4000-8000-0000000d0001';
    await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    screenOf(<TransferForm fromBranchId={BRANCH} />);
    wedge('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain('pack size'));
    expect(document.querySelectorAll('[id^="tq-"]')).toHaveLength(0);
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
    expect(screen.getByTestId('label-stock').textContent).toContain('70 by 37 mm');
    expect(screen.getByTestId('label-stock').textContent).toContain('zero margin');
    fireEvent.click(screen.getByRole('checkbox', { name: /keep 4 mm clear inside each label/ }));
    expect(screen.getByTestId('label-sheet').classList.contains('label-safe')).toBe(true);
  });
});
