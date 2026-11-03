// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Product } from '../api/retail';
import { SALE_ANSWER_MS } from './connectivity';
import type { OutboxItem } from './outbox';

/** A button that may be pressed: not disabled, and not a primary button whose form is still not ready (issue #526). */
const ready = (b: unknown) => !(b as HTMLButtonElement).disabled && (b as HTMLElement).getAttribute('data-not-ready') !== 'true';

// Record a sale with the network off (issue #458, ADR-067; NFR-OFF-07): the items come from the phone's copy, a credit sale
// is not offered, the sale goes to the outbox with every price as charged, the provisional receipt shows, and once the
// phone is online the server's number replaces it. Issue #769: online, a sale lost on the network or with no answer within
// 8 seconds goes to the outbox at once under the same key, recorded offline, and the late answer makes no second sale. A sale whose
// answer was lost before a reload locks the form and is kept with the same client sale id and a new key, so it is recorded
// once. Every request fails as with no network unless a test says otherwise. Fabricated people, items and amounts.

vi.setConfig({ testTimeout: 20000 });
const noNetwork = () => Promise.reject(new TypeError('Failed to fetch'));
vi.stubGlobal('fetch', vi.fn(noNetwork));
// The real client builds a Request from a relative address, which Node cannot; give it a base (as members-real.test.ts does).
const BaseRequest = globalThis.Request;
globalThis.Request = class extends BaseRequest {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    super(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init);
  }
} as typeof Request;

const createSale = vi.fn();
const listProducts = vi.fn();
const resolvePrices = vi.fn();

vi.mock('../api/retail', async (original) => {
  const real = await original<typeof import('../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      createSale: (...a: unknown[]) => createSale(...a),
      listProducts: (...a: unknown[]) => listProducts(...a),
      resolvePrices: (...a: unknown[]) => resolvePrices(...a),
      listCustomers: () => Promise.reject(new TypeError('Failed to fetch')),
      branchRoles: () => Promise.reject(new TypeError('Failed to fetch')),
      stockedBranches: () => Promise.reject(new TypeError('Failed to fetch')),
    },
  };
});

const { mockMe } = await import('../api/retail-mock');
const { StaffContext } = await import('../areas/staff/context');
const { RecordSale } = await import('../areas/staff/retail/sale');
const { OfflineProvider } = await import('./offline-context');
const { testOffline } = await import('./test-kit');
const { setAccessToken } = await import('../auth/session');
const { RetailError } = await import('../api/retail');

const seller = mockMe('sales');
const branch = seller.branches?.[0]?.id ?? '';
const bulb = { id: 'p2', code: 'BLB-9', description: 'Test Bulb 9W', category: 'Test Lighting', unit: 'pc', sell_minor: 5000, qty: '1.000', active: true } as Product;
const catalogue = { pricesAsOf: '2026-10-22T09:14:00.000Z', products: { [branch]: [bulb] }, customers: [{ id: 'c1', name: 'Test Customer 05' }] };

function screenWith(offline: ReturnType<typeof testOffline>, me = seller) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>
        <OfflineProvider value={offline}>
          <RecordSale />
        </OfflineProvider>
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

/** Online: finds the bulb, adds it and presses Record sale. */
async function addBulbAndRecord() {
  fireEvent.change(screen.getByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'bulb' } });
  fireEvent.click(await screen.findByRole('option', { name: /^Test Bulb 9W/ }));
  const save = screen.getByRole('button', { name: /^Record sale/ }) as HTMLButtonElement;
  await waitFor(() => expect(ready(save)).toBe(true));
  fireEvent.click(save);
}

beforeEach(() => {
  sessionStorage.clear();
  setAccessToken('test-access-token');
});

afterEach(() => {
  cleanup();
  createSale.mockReset();
  listProducts.mockReset();
  resolvePrices.mockReset();
});

describe('Record a sale offline', () => {
  it('says what the phone holds when no item on it matches, and when nothing is saved on it yet', async () => {
    const withCopy = testOffline(seller, { online: false, catalogue });
    if (!withCopy) throw new Error('a seller sells offline');
    screenWith(withCopy);
    fireEvent.change(await screen.findByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'fridge' } });
    expect(await screen.findByText('No item on this phone matches "fridge". Items are saved while the phone is online.', { selector: '.cb-empty' })).toBeTruthy();
    cleanup();
    const empty = testOffline(seller, { online: false, catalogue: null });
    if (!empty) throw new Error('a seller sells offline');
    screenWith(empty);
    fireEvent.change(await screen.findByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'bulb' } });
    expect(await screen.findByText('No items are saved on this phone yet. Items are saved while the phone is online.', { selector: '.cb-empty' })).toBeTruthy();
  });

  it('saves a sale on the phone from the copy and shows a provisional receipt, then the server number', async () => {
    const send = vi.fn(async () => ({ status: 201, body: { id: 'sale-1', sale_no: 'RS00000042' } }));
    const offline = testOffline(seller, { online: false, catalogue, deps: { send } });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);

    expect(await screen.findByTestId('offline-note')).toBeTruthy();
    expect(screen.getByText(/Prices as of/)).toBeTruthy();
    expect((screen.getByLabelText('Credit (pay later)') as HTMLInputElement).disabled).toBe(true);

    fireEvent.change(screen.getByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'bulb' } });
    fireEvent.click(await screen.findByRole('option', { name: /^Test Bulb 9W/ }));
    // Two on a shelf the copy says holds one: the server flags it, the phone does not stop the sale.
    const qty = screen.getAllByRole('textbox').find((i) => (i as HTMLInputElement).value === '1') as HTMLInputElement;
    fireEvent.change(qty, { target: { value: '2' } });
    // The buyer's name typed offline goes with the sale.
    fireEvent.change(screen.getByLabelText("Or the buyer's name"), { target: { value: 'Test Buyer 21' } });
    const save = screen.getByRole('button', { name: 'Save on this phone' }) as HTMLButtonElement;
    await waitFor(() => expect(ready(save)).toBe(true));
    fireEvent.click(save);

    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    expect(screen.getByTestId('receipt-number').textContent).toMatch(/OFF-[0-9A-F]{8} \(provisional\)/);
    expect(screen.getByText('Saved on this phone, it will send when the connection is better.')).toBeTruthy();
    const items = offline.engine.snapshot().items;
    expect(items).toHaveLength(1);
    expect(items[0]?.request).toMatchObject({
      branch_id: branch,
      payment_method: 'cash',
      recorded_offline: true,
      prices_as_of: '2026-10-22T09:14:00.000Z',
      buyer_name: 'Test Buyer 21',
      lines: [{ product_id: 'p2', qty: '2.000', unit_price_minor: 5000 }],
    });
    expect(items[0]?.request.client_sale_id).toBe(items[0]?.id);
    expect(createSale).not.toHaveBeenCalled();
    expect(send).not.toHaveBeenCalled();

    // Back online: the engine sends it and the receipt takes the server's number.
    act(() => offline.connectivity.set(true));
    await act(() => offline.engine.syncNow());
    expect(send).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('Sale sent')).toBeTruthy();
    expect(screen.getByTestId('receipt-number').textContent).toBe('Receipt RS00000042');
  });

  it('does not let a seller without retail.price.override type a price offline', async () => {
    const plain = { ...seller, permissions: (seller.permissions ?? []).filter((p) => p !== 'retail.price.override') };
    const offline = testOffline(plain, { online: false, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me: plain, branch }}>
          <OfflineProvider value={offline}>
            <RecordSale />
          </OfflineProvider>
        </StaffContext.Provider>
      </QueryClientProvider>,
    );
    fireEvent.change(await screen.findByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'bulb' } });
    fireEvent.click(await screen.findByRole('option', { name: /^Test Bulb 9W/ }));
    const price = screen.getByLabelText('Selling price') as HTMLInputElement;
    expect(price.readOnly).toBe(true);
    expect(screen.getByText('Offline, the price saved on this phone is used. You do not have permission to change it.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save on this phone' }));
    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    expect(offline.engine.snapshot().items[0]?.request.lines).toEqual([{ product_id: 'p2', qty: '1.000', unit_price_minor: 5000 }]);
  });

  it('says plainly when the items of the branch are not on the phone yet (issue #769)', async () => {
    const empty = testOffline(seller, { online: false, catalogue: null });
    if (!empty) throw new Error('a seller sells offline');
    screenWith(empty);
    expect((await screen.findByTestId('no-items-on-phone')).textContent).toBe(
      'The items are not on this phone yet, so you cannot sell offline here. Open Sell once with the internet on, and the items are saved on this phone for next time.',
    );
    cleanup();
    const other = testOffline(seller, { online: false, catalogue: { ...catalogue, products: { elsewhere: [bulb] } } });
    if (!other) throw new Error('a seller sells offline');
    screenWith(other);
    expect(await screen.findByTestId('no-items-on-phone')).toBeTruthy();
    cleanup();
    const withCopy = testOffline(seller, { online: false, catalogue });
    if (!withCopy) throw new Error('a seller sells offline');
    screenWith(withCopy);
    expect(await screen.findByText(/Prices as of/)).toBeTruthy();
    expect(screen.queryByTestId('no-items-on-phone')).toBeNull();
  });

  it('waits for the copy on the phone before saying the items are not on it, and sells offline only in retail (issue #769)', async () => {
    const loading = testOffline(seller, { online: false, catalogue: null });
    if (!loading) throw new Error('a seller sells offline');
    screenWith({ ...loading, catalogueLoading: true });
    expect(await screen.findByTestId('offline-note')).toBeTruthy();
    expect(screen.queryByTestId('no-items-on-phone')).toBeNull();
    // A profile without its modules counts as retail, as the menus count it; a business without retail never sells offline.
    expect(testOffline({ ...seller, modules: undefined })).not.toBeNull();
    expect(testOffline({ ...seller, modules: [] })).toBeNull();
    expect(testOffline({ ...seller, modules: ['lending'] })).toBeNull();
  });

  it('puts a sale lost on the network in the outbox at once under the same key, recorded offline (issue #769)', async () => {
    createSale.mockRejectedValue(new TypeError('Failed to fetch'));
    listProducts.mockResolvedValue([bulb]);
    const offline = testOffline(seller, { online: true, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);

    await addBulbAndRecord();
    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    expect(screen.getByText('Saved on this phone, it will send when the connection is better.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Keep it on this phone' })).toBeNull();
    expect(createSale).toHaveBeenCalledTimes(1);
    const [body, key] = createSale.mock.calls[0] as [{ client_sale_id?: string }, string];
    expect(body.client_sale_id).toBe(key);
    const [item] = offline.engine.snapshot().items;
    expect(item).toMatchObject({ id: key, key, state: 'queued' });
    // Priced online from the items as they were read, never from the copy on the phone (the next test pins the time).
    expect(item?.request).toMatchObject({ ...body, occurred_at: item?.occurredAt, recorded_offline: true });
    expect(item?.request.prices_as_of).not.toBe(catalogue.pricesAsOf);
    expect(String(item?.request.prices_as_of) <= String(item?.occurredAt)).toBe(true);
  });

  // JUSTIFICATION-A3: a new test the review asks for (#769), not a mechanism.
  it("dates an item picked from the phone's copy as of the copy, and leaves the time out when it is not known (issue #769)", async () => {
    createSale.mockRejectedValue(new TypeError('Failed to fetch'));
    listProducts.mockResolvedValue([bulb]);
    // Picked offline from the copy saved at 09:14 on 22 Oct, then saved online once the weak link came back, and lost.
    const offline = testOffline(seller, { online: false, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);
    fireEvent.change(screen.getByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'bulb' } });
    fireEvent.click(await screen.findByRole('option', { name: /^Test Bulb 9W/ }));
    act(() => offline.connectivity.set(true));
    const save = (await screen.findByRole('button', { name: /^Record sale/ })) as HTMLButtonElement;
    await waitFor(() => expect(ready(save)).toBe(true));
    fireEvent.click(save);
    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    expect(offline.engine.snapshot().items[0]?.request.prices_as_of).toBe(catalogue.pricesAsOf);
    cleanup();

    // A draft from before the read times were kept (no readAt on its line): the time is not known, so none is sent, never
    // Save's time; the server then holds each price to today's.
    const key = 'c0ffee00-0000-4000-8000-000000000770';
    const draft = { branchId: branch, method: 'cash', customerId: '', newBuyerName: '', newBuyerContact: '', dueDate: '', lines: [{ product: bulb, qty: '1', price: '5000' }] };
    sessionStorage.setItem(`retail-draft:sale:${seller.user_id ?? ''}:${branch}`, JSON.stringify({ key, draft }));
    const again = testOffline(seller, { online: true, catalogue });
    if (!again) throw new Error('a seller sells offline');
    screenWith(again);
    const record = (await screen.findByRole('button', { name: /^Record sale/ })) as HTMLButtonElement;
    await waitFor(() => expect(ready(record)).toBe(true));
    fireEvent.click(record);
    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    const [kept] = again.engine.snapshot().items;
    expect(kept?.request.recorded_offline).toBe(true);
    expect(kept?.request).not.toHaveProperty('prices_as_of');
  });

  // JUSTIFICATION-A3: a new test the review asks for (#769), not a mechanism.
  it("dates an item scanned from the phone's copy as of the copy (issue #769)", async () => {
    createSale.mockRejectedValue(new TypeError('Failed to fetch'));
    const labelled = { ...bulb, barcodes: [{ id: 'b-bulb', product_id: 'p2', code: 'IN00007690', format: 'code128', generated: true }] } as Product;
    const offline = testOffline(seller, { online: false, catalogue: { ...catalogue, products: { [branch]: [labelled] } } });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);
    // The lookup fails with no network, so the scan finds the bulb in the copy saved at 09:14 on 22 Oct.
    const box = await screen.findByLabelText('Scan here');
    fireEvent.change(box, { target: { value: 'IN00007690' } });
    fireEvent.keyDown(box, { key: 'Enter' });
    await waitFor(() => expect(screen.getAllByRole('textbox').some((i) => (i as HTMLInputElement).value === '1')).toBe(true));
    act(() => offline.connectivity.set(true));
    const save = (await screen.findByRole('button', { name: /^Record sale/ })) as HTMLButtonElement;
    await waitFor(() => expect(ready(save)).toBe(true));
    fireEvent.click(save);
    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    expect(offline.engine.snapshot().items[0]?.request.prices_as_of).toBe(catalogue.pricesAsOf);
  });

  // JUSTIFICATION-A3: a new test the review asks for (#769), not a mechanism.
  it("dates the kept copy's prices when the items were read, not when Save was pressed (issue #769)", async () => {
    createSale.mockRejectedValue(new TypeError('Failed to fetch'));
    listProducts.mockResolvedValue([bulb]);
    const offline = testOffline(seller, { online: true, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      // 09:00: the cashier finds the bulb, read from the server with its price.
      vi.setSystemTime(new Date('2026-11-02T09:00:00.000Z'));
      screenWith(offline);
      fireEvent.change(screen.getByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: 'bulb' } });
      fireEvent.click(await screen.findByRole('option', { name: /^Test Bulb 9W/ }));
      // 09:10: Save, and the request is lost on the network.
      vi.setSystemTime(new Date('2026-11-02T09:10:00.000Z'));
      const save = screen.getByRole('button', { name: /^Record sale/ }) as HTMLButtonElement;
      await waitFor(() => expect(ready(save)).toBe(true));
      fireEvent.click(save);
      expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
      const [item] = offline.engine.snapshot().items;
      expect(item?.request.prices_as_of).toBe('2026-11-02T09:00:00.000Z');
      expect(item?.request.occurred_at).toBe('2026-11-02T09:10:00.000Z');
    } finally {
      vi.useRealTimers();
    }
  });

  // JUSTIFICATION-A3: the tests the #776 follow-up asks for (the oldest of several read times), not a mechanism.
  it('dates the kept copy as of the oldest read: two lines, a cached answer picked later, a price list read later (issue #776)', async () => {
    const cable = { id: 'p3', code: 'CBL-2', description: 'Test Cable 2.5mm', category: 'Test Wiring', unit: 'm', sell_minor: 3000, qty: '50.000', active: true } as Product;
    createSale.mockRejectedValue(new TypeError('Failed to fetch'));
    listProducts.mockImplementation(async ({ query }: { query: string }) => (query === 'cable' ? [cable] : [bulb]));
    const pick = async (term: string, name: RegExp) => {
      fireEvent.change(screen.getByRole('combobox', { name: 'Find an item by name, code or category' }), { target: { value: term } });
      return screen.findByRole('option', { name });
    };
    const record = async () => {
      const save = screen.getByRole('button', { name: /^Record sale/ }) as HTMLButtonElement;
      await waitFor(() => expect(ready(save)).toBe(true));
      fireEvent.click(save);
      expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    };
    const at = (time: string) => vi.setSystemTime(new Date(`2026-11-02T${time}:00.000Z`));
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      // Two lines: the bulb read at 09:00, the cable at 09:05; the copy is priced as of 09:00.
      const two = testOffline(seller, { online: true, catalogue });
      if (!two) throw new Error('a seller sells offline');
      at('09:00');
      screenWith(two);
      fireEvent.click(await pick('bulb', /^Test Bulb 9W/));
      at('09:05');
      fireEvent.click(await pick('cable', /^Test Cable 2.5mm/));
      at('09:10');
      await record();
      expect(two.engine.snapshot().items[0]?.request.prices_as_of).toBe('2026-11-02T09:00:00.000Z');
      cleanup();
      sessionStorage.clear();

      // The answer read at 09:00 and picked at 09:07: the copy is priced as of the read, not the pick.
      const cached = testOffline(seller, { online: true, catalogue });
      if (!cached) throw new Error('a seller sells offline');
      at('09:00');
      screenWith(cached);
      const option = await pick('bulb', /^Test Bulb 9W/);
      at('09:07');
      fireEvent.click(option);
      await record();
      expect(cached.engine.snapshot().items[0]?.request.prices_as_of).toBe('2026-11-02T09:00:00.000Z');
      cleanup();
      sessionStorage.clear();

      // Price lists on: the item read at 09:00, its list price resolved at 09:05; the copy is priced as of 09:00.
      const lister = { ...seller, module_capabilities: { ...seller.module_capabilities, retail: [...(seller.module_capabilities?.retail ?? []), 'price_lists'] } };
      const listed = testOffline(lister, { online: true, catalogue });
      if (!listed) throw new Error('a seller sells offline');
      resolvePrices.mockImplementation(async () => {
        at('09:05');
        return { price_list: 'Test List A', origin: 'sale', lines: [{ product_id: 'p2', qty: '1', unit_price_minor: 4800, line_total_minor: 4800, price_source: 'price_list' }] };
      });
      at('09:00');
      screenWith(listed, lister);
      fireEvent.click(await pick('bulb', /^Test Bulb 9W/));
      expect(await screen.findByText(/Priced from the list Test List A/)).toBeTruthy();
      expect(resolvePrices).toHaveBeenCalled();
      at('09:10');
      await record();
      expect(listed.engine.snapshot().items[0]?.request.prices_as_of).toBe('2026-11-02T09:00:00.000Z');
    } finally {
      vi.useRealTimers();
    }
  });

  // JUSTIFICATION-A3: a new test the review asks for (#769), not a mechanism.
  it.each([
    ['slow', () => new Promise<Response>(() => {})],
    ['failed', noNetwork],
  ])('through the real client, a 401 with a %s refresh is never kept and says to sign in (issue #769)', async (_kind, refresh) => {
    // The real retail.createSale and api client: a write answered 401 is shown as it is and never refreshed and sent again
    // inside the 8 s race, so neither a slow nor a failed refresh can put the sale in the outbox.
    const real = await vi.importActual<typeof import('../api/retail')>('../api/retail');
    createSale.mockImplementation((body: never, key: string) => real.retail.createSale(body, key));
    listProducts.mockResolvedValue([bulb]);
    const urls: string[] = [];
    vi.mocked(fetch).mockImplementation(async (input) => {
      const url = input instanceof Request ? input.url : String(input);
      urls.push(url);
      if (url.includes('/auth/refresh')) return refresh();
      if (url.includes('/api/v1/retail/sales')) {
        return new Response(JSON.stringify({ code: 'unauthenticated', detail: 'Sign in again.' }), { status: 401, headers: { 'content-type': 'application/problem+json' } });
      }
      return noNetwork();
    });
    const offline = testOffline(seller, { online: true, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    try {
      screenWith(offline);
      await addBulbAndRecord();
      expect(await screen.findByText(/Your session has ended. Sign in again./)).toBeTruthy();
      expect(screen.queryByText('Sale saved on this phone')).toBeNull();
      expect(offline.engine.snapshot().items).toHaveLength(0);
      expect(urls.filter((u) => u.includes('/api/v1/retail/sales'))).toHaveLength(1);
      expect(urls.some((u) => u.includes('/auth/refresh'))).toBe(false);
    } finally {
      vi.mocked(fetch).mockImplementation(noNetwork);
    }
  });

  // JUSTIFICATION-A3: a new test the review asks for (#769), not a mechanism.
  it('never keeps a sale on the phone when the session has ended, and says to sign in (issue #769)', async () => {
    listProducts.mockResolvedValue([bulb]);
    const offline = testOffline(seller, { online: true, catalogue });
    if (!offline) throw new Error('a seller sells offline');

    // The server answers 401: a clear answer, shown as it is.
    createSale.mockRejectedValue(new RetailError('Your session has ended. Sign in again.', 401, 'unauthenticated'));
    screenWith(offline);
    await addBulbAndRecord();
    expect(await screen.findByText(/Your session has ended. Sign in again./)).toBeTruthy();
    expect(screen.queryByText('Sale saved on this phone')).toBeNull();
    expect(offline.engine.snapshot().items).toHaveLength(0);
    cleanup();
    sessionStorage.clear();

    // A refused refresh left no session, then the network fails: still never kept as saved.
    setAccessToken(null);
    createSale.mockReset();
    createSale.mockRejectedValue(new TypeError('Failed to fetch'));
    screenWith(offline);
    await addBulbAndRecord();
    expect(await screen.findByText(/Your session has ended. Sign in again. Then tap Try again/)).toBeTruthy();
    expect(screen.queryByText('Sale saved on this phone')).toBeNull();
    expect(offline.engine.snapshot().items).toHaveLength(0);
    // The request may have been recorded: the form stays locked, so only the same sale can go again, and with no session
    // it is never offered to keep on the phone.
    expect(screen.getByTestId('lost-answer')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Keep it on this phone' })).toBeNull();
    const qty = screen.getAllByRole('textbox').find((i) => (i as HTMLInputElement).value === '1') as HTMLInputElement;
    expect(qty.matches(':disabled')).toBe(true);
  });

  it('keeps a sale with no answer within 8 seconds on the phone, and the late answer makes no second sale (issue #769)', async () => {
    // A fake server as the real one behaves: one sale per Idempotency-Key, whose hash leaves the delivery fields out; while
    // the first request still holds the key a second is 409 idempotency_in_progress; once it is done, the same sale again.
    const delivery = ['occurred_at', 'recorded_offline', 'prices_as_of'];
    const sale = (body: unknown) => Object.fromEntries(Object.entries(body as object).filter(([k]) => !delivery.includes(k)));
    const recorded = new Map<string, { body: unknown; sale: { id: string; sale_no: string } }>();
    const inFlight = new Set<string>();
    const arrive = (body: unknown, key: string) => {
      const before = recorded.get(key);
      if (before) {
        expect(before.body).toEqual(sale(body));
        return before.sale;
      }
      const done = { id: `sale-${recorded.size + 1}`, sale_no: `RS0000007${recorded.size + 1}` };
      recorded.set(key, { body: sale(body), sale: done });
      return done;
    };
    let answerLate = () => {};
    createSale.mockImplementation((body: unknown, key: string) => {
      inFlight.add(key);
      return new Promise((resolve) => {
        answerLate = () => {
          inFlight.delete(key);
          resolve(arrive(body, key));
        };
      });
    });
    listProducts.mockResolvedValue([bulb]);
    const send = vi.fn(async (item: OutboxItem) =>
      inFlight.has(item.key) ? { status: 409, body: { code: 'idempotency_in_progress' } } : { status: 201, body: arrive(item.request, item.key) },
    );
    const report = vi.fn();
    const offline = testOffline(seller, { online: true, catalogue, deps: { send, report } });
    if (!offline) throw new Error('a seller sells offline');
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      screenWith(offline);
      await addBulbAndRecord();
      await act(() => vi.advanceTimersByTimeAsync(SALE_ANSWER_MS - 100));
      expect(screen.queryByText('Sale saved on this phone')).toBeNull();
      await act(() => vi.advanceTimersByTimeAsync(100));
      // The provisional receipt shows while the first request still holds the key: the outbox's send is told to wait.
      expect(await screen.findByRole('heading', { name: 'Sale saved on this phone' })).toBeTruthy();
      expect(screen.getByText('Saved on this phone, it will send when the connection is better.')).toBeTruthy();
      await waitFor(() => expect(send).toHaveBeenCalled());
      expect(offline.engine.snapshot().items[0]).toMatchObject({ state: 'queued' });
      expect(screen.getByRole('heading', { name: 'Sale saved on this phone' })).toBeTruthy();

      const [body, key] = createSale.mock.calls[0] as [{ client_sale_id?: string }, string];
      expect(offline.engine.snapshot().items[0]).toMatchObject({ id: key, key });
      expect(offline.engine.snapshot().items[0]?.request).toMatchObject({ ...body, recorded_offline: true });

      // The server's answer to the first request comes at last: the screen keeps the receipt it shows, and the sale is one.
      await act(async () => answerLate());
      expect(screen.getByRole('heading', { name: 'Sale saved on this phone' })).toBeTruthy();
      await act(() => offline.engine.syncNow());
      expect(await screen.findByText('Sale sent')).toBeTruthy();
      expect(screen.getByTestId('receipt-number').textContent).toBe('Receipt RS00000071');
      expect(recorded.size).toBe(1);
      expect(createSale).toHaveBeenCalledTimes(1);
      expect(report).not.toHaveBeenCalled();
      expect(send.mock.calls.every(([i]) => i.key === key)).toBe(true);
    } finally {
      vi.useRealTimers();
    }
  });

  it('keeps a sale whose answer was lost before a reload with the same client sale id and a new key', async () => {
    const key = 'c0ffee00-0000-4000-8000-000000000769';
    const body = { branch_id: branch, payment_method: 'cash', lines: [{ product_id: 'p2', qty: '1.000', unit_price_minor: 5000 }], client_sale_id: key };
    const draft = { branchId: branch, method: 'cash', customerId: '', newBuyerName: '', newBuyerContact: '', dueDate: '', lines: [{ product: bulb, qty: '1', price: '5000' }], sent: { body, at: '2026-10-22T09:30:00.000Z' } };
    sessionStorage.setItem(`retail-draft:sale:${seller.user_id ?? ''}:${branch}`, JSON.stringify({ key, draft }));
    const offline = testOffline(seller, { online: true, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);

    // The answer is lost: the form is locked, so the cashier cannot change the lines and send them under the same id.
    fireEvent.click(await screen.findByRole('button', { name: 'Keep it on this phone' }));
    expect(await screen.findByText('Sale saved on this phone')).toBeTruthy();
    const [item] = offline.engine.snapshot().items;
    expect(item?.id).toBe(key);
    expect(item?.request).toMatchObject({ ...body, recorded_offline: true, occurred_at: '2026-10-22T09:30:00.000Z' });
    expect(item?.key).not.toBe(key);
  });
  it('offers only Try again for a credit sale whose answer was lost, never Keep it on this phone', async () => {
    createSale.mockRejectedValue(new TypeError('Failed to fetch'));
    const key = 'c0ffee00-0000-4000-8000-000000000458';
    const body = { branch_id: branch, payment_method: 'credit', buyer_name: 'Test Buyer 07', lines: [{ product_id: 'p2', qty: '1' }], client_sale_id: key };
    const draft = { branchId: branch, method: 'credit', customerId: '', newBuyerName: 'Test Buyer 07', newBuyerContact: '', dueDate: '', lines: [{ product: bulb, qty: '1', price: '5000' }], sent: { body, at: '2026-10-22T09:30:00.000Z' } };
    sessionStorage.setItem(`retail-draft:sale:${seller.user_id ?? ''}:${branch}`, JSON.stringify({ key, draft }));
    const offline = testOffline(seller, { online: true, catalogue });
    if (!offline) throw new Error('a seller sells offline');
    screenWith(offline);

    expect((await screen.findByTestId('lost-answer')).textContent).toContain('A credit sale needs the internet');
    expect(screen.queryByRole('button', { name: 'Keep it on this phone' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await waitFor(() => expect(createSale).toHaveBeenCalledTimes(1));
    expect(createSale.mock.calls[0]?.[0]).toEqual(body);
    await screen.findByTestId('lost-answer');
    expect(screen.queryByRole('button', { name: 'Keep it on this phone' })).toBeNull();
    expect(offline.engine.snapshot().items).toHaveLength(0);

    // Clearing it is asked first: it may already be recorded.
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    expect((await screen.findByTestId('confirm-clear')).textContent).toContain('may already be saved');
    fireEvent.click(screen.getByRole('button', { name: 'Keep it' }));
    expect(screen.queryByTestId('confirm-clear')).toBeNull();
    expect(screen.getByTestId('lost-answer')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Clear this sale' }));
    fireEvent.click(await screen.findByRole('button', { name: 'Clear it' }));
    await waitFor(() => expect(screen.queryByTestId('lost-answer')).toBeNull());
  });
});
