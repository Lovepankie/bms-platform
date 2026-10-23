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
import { ScanBar } from './scan';
import { OfflineProvider, type Offline } from '../../../offline/offline-context';
import type { Product } from '../../../api/retail';
import { TransferForm } from './transfer';
import { PACK_UNUSABLE } from './barcode-state';

// Scanning on the forms and the label sheet (ADR-070, issue #345; FR-RET-68): a scan into the Scan box adds the item at 1
// and the next scan of it adds one more, an unknown code says so (with Add this barcode to a product for an
// administrator only), the camera button shows only where the browser can open a camera, and the label sheet draws a
// barcode per label. The retail adapter is the fabricated mock; codes and items are made up.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

// The camera view, stood in for by a button that reports one read, as the real one does after two frames agree.
vi.mock('./camera-scan', () => ({
  default: ({ onCode }: { onCode: (code: string) => void }) => (
    <button type="button" onClick={() => onCode('4006381333931')}>
      Fake camera read
    </button>
  ),
}));

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

/** The Scan box of the form on screen. */
const scanBox = () => document.querySelector<HTMLInputElement>('input[data-scan-box]') as HTMLInputElement;

/** A scanner: the cursor in the Scan box, the code's keys in a fast burst, then Enter, as a USB or Bluetooth scanner types. */
function scan(code: string) {
  scanBox().focus();
  let at = (clock += 10000);
  for (const ch of code) press(ch, (at += 8));
  press('Enter', (clock = at + 8));
}

const BULB15 = '00000000-0000-4000-8000-0000000b0004';

const qtyOf = (i: number) => (screen.getByLabelText(/^Quantity/, { selector: `#qty-${i}` }) as HTMLInputElement).value;

describe('FR-RET-68 scanning on the sale form', () => {
  it('adds a scanned item at 1, and one more on the next scan', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<SaleForm branchId={BRANCH} />);
    scan(EAN);
    await waitFor(() => expect(screen.getByText('Added LED bulb 9W screw.')).toBeTruthy());
    expect(qtyOf(0)).toBe('1');
    expect(document.querySelector('.toast')?.className).toBe('toast toast-success');
    const badge = document.querySelector('.count-badge');
    scan(EAN);
    await waitFor(() => expect(qtyOf(0)).toBe('2'));
    expect(document.querySelectorAll('[id^="qty-"]')).toHaveLength(1);
    // A top-up pops the count again (#508), though the number of lines is the same.
    expect(document.querySelector('.count-badge')).not.toBe(badge);
  });

  it('takes a slow Bluetooth scanner in the Scan box like a fast one: its speed no longer matters', async () => {
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP' });
    screenOf(<SaleForm branchId={BRANCH} />);
    expect(document.activeElement?.id).toBe('sale-scan');
    let at = (clock += 10000);
    for (const ch of 'TEST-LAMP') press(ch, (at += 150));
    press('Enter', (clock = at + 150));
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    expect(document.activeElement?.id).toBe('sale-scan');
  });

  it('adds both items when a second scan comes while the first is still being looked up', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    await retail.addBarcode(BULB15, { code: 'TEST-BULB15' });
    screenOf(<SaleForm branchId={BRANCH} />);
    scan(EAN);
    scan('TEST-BULB15');
    scan(EAN);
    await waitFor(() => expect(document.querySelectorAll('[id^="qty-"]')).toHaveLength(2));
    await waitFor(() => expect(qtyOf(0)).toBe('2'));
    expect(qtyOf(1)).toBe('1');
  });

  it('takes a code typed in the scan box when Enter is pressed', async () => {
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP' });
    screenOf(<SaleForm branchId={BRANCH} />);
    const box = screen.getByLabelText('Scan here');
    fireEvent.change(box, { target: { value: 'TEST-LAMP' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
  });

  it('says plainly that a code is unknown, and offers an administrator to add it to an item', async () => {
    screenOf(<SaleForm branchId={BRANCH} />);
    scan('TEST-UNKNOWN');
    await waitFor(() => expect(screen.getByTestId('scan-unknown').textContent).toContain('No item has the barcode TEST-UNKNOWN.'));
    fireEvent.click(screen.getByRole('button', { name: 'Add this barcode to a product' }));
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Choose LED bulb 9W screw' }).length).toBeGreaterThan(0));
    fireEvent.click(screen.getAllByRole('button', { name: 'Choose LED bulb 9W screw' })[0] as HTMLElement);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    expect((await retail.findByBarcode('TEST-UNKNOWN'))?.product?.id).toBe(LAMP);
  });

  it('says a scanned code with a sign no barcode here may hold is unknown, without looking it up', async () => {
    const lookup = vi.spyOn(retail, 'findByBarcode');
    screenOf(<SaleForm branchId={BRANCH} />);
    scan('TEST/7%3B?#');
    await waitFor(() => expect(screen.getByTestId('scan-unknown').textContent).toContain('No item has the barcode TEST/7%3B?#.'));
    expect(lookup).not.toHaveBeenCalled();
    expect(screen.queryByRole('button', { name: 'Add this barcode to a product' })).toBeNull();
  });

  it('lets an administrator add a GTIN-14 as printed (Code 128), as the item form does', async () => {
    const GTIN = '10012345678902';
    screenOf(<SaleForm branchId={BRANCH} />);
    scan(GTIN);
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
    scan('TEST-UNKNOWN');
    fireEvent.click(await screen.findByRole('button', { name: 'Add this barcode to a product' }));
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Choose LED bulb 9W screw' }).length).toBeGreaterThan(0));
    expect(screen.queryByRole('checkbox', { name: /exactly as printed/ })).toBeNull();
  });

  it('gives a seller the unknown code message without the button', async () => {
    screenOf(<SaleForm branchId={BRANCH} />, 'sales');
    scan('TEST-UNKNOWN');
    await waitFor(() => expect(screen.getByTestId('scan-unknown').textContent).toContain('Ask an administrator'));
    expect(screen.queryByRole('button', { name: 'Add this barcode to a product' })).toBeNull();
  });

  it('says the camera needs the secure address on a plain http page', () => {
    Object.defineProperty(window, 'isSecureContext', { configurable: true, value: false });
    try {
      screenOf(<SaleForm branchId={BRANCH} />);
      expect(screen.getByText('The camera works only on the secure (https) address.')).toBeTruthy();
      expect(screen.queryByRole('button', { name: 'Scan with the camera' })).toBeNull();
    } finally {
      Reflect.deleteProperty(window, 'isSecureContext');
    }
  });

  it('hides the camera Scan button where the browser cannot open a camera, and shows it where it can', () => {
    Object.defineProperty(window, 'isSecureContext', { configurable: true, value: true });
    const first = screenOf(<SaleForm branchId={BRANCH} />);
    expect(screen.queryByRole('button', { name: 'Scan with the camera' })).toBeNull();
    first.unmount();
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: vi.fn() } });
    screenOf(<SaleForm branchId={BRANCH} />);
    expect(screen.getByRole('button', { name: 'Scan with the camera' })).toBeTruthy();
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
    scan(EAN);
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
    scan(EAN);
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
    scan('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain('pack size'));
    expect((document.getElementById(`count-${LAMP}`) as HTMLInputElement).value).toBe('');
  });
});

describe('FR-RET-68 the Scan box', () => {
  it('adds the scanned item, clears the box and keeps the cursor there for the next scan', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<SaleForm branchId={BRANCH} />);
    expect(document.activeElement).toBe(scanBox());
    scan(EAN);
    expect(scanBox().value).toBe('');
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    expect(document.activeElement).toBe(scanBox());
    scan(EAN);
    scan(EAN);
    await waitFor(() => expect(qtyOf(0)).toBe('3'));
    expect(document.querySelectorAll('[id^="qty-"]')).toHaveLength(1);
    expect(document.activeElement).toBe(scanBox());
  });

  it('never submits the form on the Enter that ends a scan', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<SaleForm branchId={BRANCH} />);
    const submitted = vi.fn((e: Event) => e.preventDefault());
    scanBox().form?.addEventListener('submit', submitted);
    const createSale = vi.spyOn(retail, 'createSale');
    scanBox().focus();
    for (const ch of EAN) press(ch, (clock += 8));
    expect(press('Enter', (clock += 8)).defaultPrevented).toBe(true);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    expect(submitted).not.toHaveBeenCalled();
    expect(createSale).not.toHaveBeenCalled();
  });

  it('says an unknown code plainly, adds nothing, and keeps the cursor in the box', async () => {
    screenOf(<SaleForm branchId={BRANCH} />);
    scan('TEST-NOT-HERE');
    await waitFor(() => expect(screen.getByTestId('scan-unknown').textContent).toContain('No item has the barcode TEST-NOT-HERE.'));
    expect(document.querySelectorAll('[id^="qty-"]')).toHaveLength(0);
    expect(scanBox().value).toBe('');
    expect(document.activeElement).toBe(scanBox());
  });

  it('sends a camera read through the same lookup and adds the item the same way', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia: vi.fn() } });
    const lookUp = vi.spyOn(retail, 'findByBarcode');
    screenOf(<SaleForm branchId={BRANCH} />);
    scan(EAN);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    fireEvent.click(screen.getByRole('button', { name: 'Scan with the camera' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Fake camera read' }));
    await waitFor(() => expect(qtyOf(0)).toBe('2'));
    expect(lookUp).toHaveBeenCalledTimes(2);
    expect(lookUp).toHaveBeenLastCalledWith(EAN, BRANCH);
    expect(screen.queryByRole('button', { name: 'Fake camera read' })).toBeNull();
    expect(screen.getByText('Added LED bulb 9W screw.')).toBeTruthy();
  });

  it.each([
    ['the restock', () => <RestockForm />, 'restock-scan'],
    ['the stock count', () => <StocktakePage />, 'count-scan'],
    ['the move stock form', () => <TransferForm fromBranchId={BRANCH} />, 'transfer-scan'],
  ] as const)('starts with the cursor in the Scan box on %s', async (_where, form, id) => {
    screenOf(form());
    await waitFor(() => expect(document.activeElement?.id).toBe(id));
    expect(screen.getByLabelText('Scan here').id).toBe(id);
  });

  it('marks the count line a scan counted on the stock count', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<StocktakePage />);
    await waitFor(() => expect(document.getElementById(`count-${LAMP}`)).not.toBeNull());
    scan(EAN);
    await waitFor(() => expect(screen.getByTestId('scan-hit').querySelector('input')?.id).toBe(`count-${LAMP}`));
    expect(document.activeElement?.id).toBe('count-scan');
  });
});

describe('FR-RET-68 typing in a quantity, price or count field is never a scan', () => {
  /** The sale's first quantity and price, after one scan made the line. */
  async function saleFields(): Promise<[HTMLInputElement, HTMLInputElement]> {
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<SaleForm branchId={BRANCH} />);
    scan(EAN);
    await waitFor(() => expect(qtyOf(0)).toBe('1'));
    return [document.getElementById('qty-0') as HTMLInputElement, document.getElementById('price-0') as HTMLInputElement];
  }

  /** The first count box of the count sheet. */
  async function countField(): Promise<HTMLInputElement> {
    screenOf(<StocktakePage />);
    await waitFor(() => expect(document.querySelector('.rt-card input[id^="count-"]')).not.toBeNull());
    return document.querySelector('.rt-card input[id^="count-"]') as HTMLInputElement;
  }

  /** 1256 at scanner speed with two key rollovers (the keys a wedge guess once took for a scan), then Enter. */
  function fast1256(field: HTMLInputElement): Event {
    field.focus();
    field.select();
    let at = (clock += 10000);
    press('1', at);
    press('2', (at += 8));
    press('5', (at += 8));
    press('6', (at += 8));
    return press('Enter', (clock = at + 8));
  }

  const FIELDS = {
    'the sale quantity': async () => (await saleFields())[0],
    'the sale price': async () => (await saleFields())[1],
    'a stock count box': countField,
  } as const;

  it.each(Object.keys(FIELDS) as (keyof typeof FIELDS)[])('keeps 1256 typed fast into %s with Enter, and looks nothing up', async (where) => {
    const field = await FIELDS[where]();
    const lookUp = vi.spyOn(retail, 'findByBarcode');
    const seen = vi.fn();
    (field.form ?? field.parentElement)?.addEventListener('keydown', (e) => {
      if ((e as KeyboardEvent).key === 'Enter') seen();
    });
    expect(fast1256(field).defaultPrevented).toBe(false);
    expect(field.value).toBe('1256');
    expect(seen).toHaveBeenCalledTimes(1);
    await new Promise((r) => setTimeout(r, 50));
    expect(lookUp).not.toHaveBeenCalled();
    expect(screen.queryByTestId('scan-unknown')).toBeNull();
  });

  it('submits the sale normally with the 1256 typed into the quantity when Enter is pressed there', async () => {
    // The branch holds enough lamps for 1256 to be a sale the form sends, not one it stops for stock.
    const real = retail.findByBarcode;
    const lookUp = vi.spyOn(retail, 'findByBarcode').mockImplementationOnce(async (code, branchId) => {
      const match = await real(code, branchId);
      return match && { ...match, product: { ...match.product, qty: '5000' } };
    });
    const [qty] = await saleFields();
    lookUp.mockClear();
    const createSale = vi.spyOn(retail, 'createSale');
    const enter = fast1256(qty);
    // A browser submits a form on Enter in its field unless the key was stopped; jsdom does not, so the test does it.
    expect(enter.defaultPrevented).toBe(false);
    qty.form?.requestSubmit();
    await waitFor(() => expect(createSale).toHaveBeenCalledTimes(1));
    expect(createSale.mock.calls[0]?.[0].lines?.[0]).toMatchObject({ product_id: LAMP, qty: '1256.000' });
    expect(lookUp).not.toHaveBeenCalled();
  });
});

describe('FR-RET-68 scanning on the restock form', () => {
  it('asks for a branch first when none is chosen at the top, and adds the item there once one is', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    const first = screenOf(<RestockForm />, 'admin', '');
    scan(EAN);
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain('Choose the branch at the top of the page first'));
    first.unmount();
    screenOf(<RestockForm />);
    scan(EAN);
    await waitFor(() => expect((document.getElementById(`q-0-${BRANCH}`) as HTMLInputElement | null)?.value).toBe('1'));
  });
});

describe('FR-RET-68 scanning on the move stock form', () => {
  it('adds the scanned item and bumps it on the next scan', async () => {
    await retail.addBarcode(LAMP, { code: EAN });
    screenOf(<TransferForm fromBranchId={BRANCH} />);
    scan(EAN);
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('1'));
    scan(EAN);
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('2'));
  });
});

describe('FR-RET-68 a pack barcode the sale cannot use', () => {
  const ROLL = '00000000-0000-4000-8000-0000000d0001';

  it('refuses a pack barcode while pack sizes are off, and adds no single piece in its place', async () => {
    await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    screenOf(<SaleForm branchId={BRANCH} />);
    scan('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain('pack size'));
    expect(document.querySelectorAll('[id^="qty-"]')).toHaveLength(0);
    // A refusal is a warning on the form, never the green check toast of an add (#508, docs/ui-rules.md rule 16).
    expect(document.querySelector('.toast-success')).toBeNull();
  });

  it('says so when the pack the barcode is on was switched off', async () => {
    const pack = await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    await retail.updatePack(LAMP, ROLL, pack.version ?? 1, { active: false });
    screenOf(<SaleForm branchId={BRANCH} />);
    scan('TEST-LAMP-BOX');
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
    scan('TEST-LAMP-BOX');
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
    await screen.findByLabelText('Scan here');
    scan('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain('pack size'));
    expect(screen.queryByText(/^Added /)).toBeNull();
    // The item's own barcode is still received, at 1.
    scan(EAN);
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
    scan('TEST-LAMP-BOX');
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('10'));
    scan(EAN);
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('11'));
    scan('TEST-LAMP-BOX');
    await waitFor(() => expect((document.getElementById('tq-0') as HTMLInputElement).value).toBe('21'));
    expect(document.querySelectorAll('[id^="tq-"]')).toHaveLength(1);
  });

  it('refuses a barcode on a pack that was switched off, and moves no single piece in its place', async () => {
    const ROLL = '00000000-0000-4000-8000-0000000d0001';
    const pack = await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    await retail.updatePack(LAMP, ROLL, pack.version ?? 1, { active: false });
    screenOf(<TransferForm fromBranchId={BRANCH} />, 'admin', BRANCH, ['units']);
    scan('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('switched off'));
    expect(document.querySelectorAll('[id^="tq-"]')).toHaveLength(0);
  });

  it('refuses a match on a pack that is switched off with the pack message, and moves no single piece in its place', async () => {
    const ROLL = '00000000-0000-4000-8000-0000000d0001';
    const lamp = await retail.getProduct(LAMP);
    // A lookup that still finds the barcode on a pack now inactive, as an offline hit on a stale item list would.
    retail.findByBarcode = async () => ({ product: { ...lamp, packs: [{ unit_id: ROLL, factor: '10.000', active: false }] }, unit_id: ROLL });
    screenOf(<TransferForm fromBranchId={BRANCH} />, 'admin', BRANCH, ['units']);
    scan('TEST-LAMP-BOX');
    await waitFor(() => expect(screen.getByTestId('scan-refused').textContent).toContain(PACK_UNUSABLE));
    expect(document.querySelectorAll('[id^="tq-"]')).toHaveLength(0);
  });

  it('refuses a pack barcode while pack sizes are off', async () => {
    const ROLL = '00000000-0000-4000-8000-0000000d0001';
    await retail.addPack(LAMP, { unit_id: ROLL, factor: '10.000' });
    await retail.addBarcode(LAMP, { code: 'TEST-LAMP-BOX', unit_id: ROLL });
    screenOf(<TransferForm fromBranchId={BRANCH} />);
    scan('TEST-LAMP-BOX');
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

describe('FR-RET-68 scanning with no network', () => {
  it('finds a scanned item in the catalogue kept on the phone for offline selling', async () => {
    const onPhone: Product = { id: LAMP, code: 'P003', description: 'Test offline lamp', unit: 'piece', active: true, barcodes: [{ id: 'b1', product_id: LAMP, code: EAN, format: 'ean13', generated: false }] };
    vi.spyOn(retail, 'findByBarcode').mockRejectedValue(new TypeError('Failed to fetch'));
    const offline = { catalogue: { pricesAsOf: '2026-10-22T08:00:00Z', products: { [BRANCH]: [onPhone] }, customers: [] } } as unknown as Offline;
    const onFound = vi.fn();
    screenOf(
      <OfflineProvider value={offline}>
        <ScanBar id="offline-scan" branchId={BRANCH} onFound={onFound} />
      </OfflineProvider>,
    );
    scan(EAN);
    await waitFor(() => expect(onFound).toHaveBeenCalled());
    // Without the phone's catalogue nothing on this screen holds the item, and the scan fails.
    expect(onFound.mock.calls[0]?.[0]).toMatchObject({ id: LAMP, description: 'Test offline lamp' });
  });
});
