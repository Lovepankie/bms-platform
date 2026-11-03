// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { retail, type Product } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { OfflineProvider, type Offline } from '../../../offline/offline-context';
import { StaffContext } from '../context';
import { ScanBar } from './scan';

// A scan with no network finds the item in a copy and dates it as of that copy (issue #769). The time is found by the
// item's id, so an item the search hands back as a copy of the row still carries its time (review of #771, issue #776).
// Fabricated items, codes and times.

vi.mock('./barcode-state', async (original) => {
  const real = await original<typeof import('./barcode-state')>();
  // The search hands back a copy of the row, as one that applied a pack unit would.
  return { ...real, findByBarcodeIn: (products: Product[], code: string) => {
    const hit = real.findByBarcodeIn(products, code);
    return hit ? { ...hit, product: { ...hit.product } } : null;
  } };
});

const BRANCH = '00000000-0000-4000-8000-0000000007a1';
const PHONE_AT = '2026-11-02T08:15:00.000Z';
const SCREEN_AT = Date.parse('2026-11-02T09:40:00.000Z');
const labelled = (id: string, code: string): Product =>
  ({ id, code: `T-${id}`, description: `Test Item ${id}`, unit: 'pc', sell_minor: 1000, active: true, barcodes: [{ id: `b-${id}`, product_id: id, code, format: 'code128', generated: true }] }) as Product;

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('a scan found in a copy (issue #776)', () => {
  it.each([
    ['the phone copy', 'IN00007761', Date.parse(PHONE_AT)],
    ["a screen's answer", 'IN00007762', SCREEN_AT],
    // The phone's copy holds the item from before its new barcode; the screen's answer holds it with it (review of #784).
    ["a screen's answer, the phone copy holding the same item without the barcode", 'IN00007763', SCREEN_AT],
  ])('dates an item found in %s as of the copy that matched', async (_copy, code, at) => {
    vi.spyOn(retail, 'findByBarcode').mockRejectedValue(new TypeError('Failed to fetch'));
    const client = new QueryClient();
    client.setQueryData(['retail', 'products', BRANCH, 'item'], [labelled('s1', 'IN00007762'), labelled('p1', 'IN00007763')], { updatedAt: SCREEN_AT });
    const offline = { catalogue: { pricesAsOf: PHONE_AT, products: { [BRANCH]: [labelled('p1', 'IN00007761')] }, customers: [] } } as unknown as Offline;
    const onFound = vi.fn(() => undefined);
    render(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: mockMe('sales'), branch: BRANCH } as never}>
          <OfflineProvider value={offline}>
            <ScanBar id="scan-test" branchId={BRANCH} onFound={onFound} />
          </OfflineProvider>
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    const box = screen.getByLabelText('Scan here');
    fireEvent.change(box, { target: { value: code } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(onFound).toHaveBeenCalledTimes(1));
    expect(onFound).toHaveBeenCalledWith(expect.objectContaining({ code: expect.stringMatching(/^T-/) }), undefined, at);
  });
});
