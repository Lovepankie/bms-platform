// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Sale, StockMovement } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Where each sale and usage entry is made (issue #826, ADR-122): the phone asks once with a plain reason and never blocks;
// the position goes with the entry, also offline; only the owner sees the distance from the shop, in words; a seller sees
// nothing and the app never asks the server for it. Fabricated places and amounts.

vi.setConfig({ testTimeout: 20000 });

const entryDistances = vi.fn();
const usageHistory = vi.fn();
const shopLocations = vi.fn();
const setShopLocation = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      entryDistances: (...a: unknown[]) => entryDistances(...a),
      usageHistory: (...a: unknown[]) => usageHistory(...a),
      shopLocations: (...a: unknown[]) => shopLocations(...a),
      setShopLocation: (...a: unknown[]) => setShopLocation(...a),
      listProducts: () => Promise.resolve([]),
    },
  };
});

const { LocationAsk, ShopLocationsCard, usable, useWhereNow, whereWords, ASK_REASON } = await import('./entry-location');
const { SaleDetail } = await import('./sales');
const { UsageHistoryPage } = await import('./usage-history');
const { offlineSale } = await import('../../../offline/catalogue');

const owner = mockMe('admin');
const seller = mockMe('sales');
const branch = owner.branches?.[0]?.id ?? '';

function wrap(ui: ReactElement, me = owner) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch } as never}>{ui}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const position = (latitude: number, longitude: number, accuracy: number) => ({ coords: { latitude, longitude, accuracy } });
let getCurrentPosition: ReturnType<typeof vi.fn>;

beforeEach(() => {
  localStorage.clear();
  getCurrentPosition = vi.fn((ok: (p: unknown) => void) => ok(position(0.31396, 32.5811, 12)));
  Object.defineProperty(navigator, 'geolocation', { value: { getCurrentPosition }, configurable: true });
});

afterEach(() => {
  cleanup();
  entryDistances.mockReset();
  usageHistory.mockReset();
  shopLocations.mockReset();
  setShopLocation.mockReset();
});

const sale: Sale = { id: 's1', sale_no: 'S-0001', branch_id: branch, sale_date: '2026-11-03', payment_method: 'cash', status: 'completed', total_minor: 1500, lines: [] } as Sale;

function Probe({ onLocation }: { onLocation: (l: unknown) => void }) {
  const where = useWhereNow();
  return (
    <>
      <LocationAsk where={where} />
      <button type="button" onClick={() => onLocation(where.now().location)}>Save</button>
    </>
  );
}

describe('the phone asks once', () => {
  it('gives the plain reason, and after Allow the position goes with the next entry', async () => {
    const seen = vi.fn();
    wrap(<Probe onLocation={seen} />);
    expect(screen.getByTestId('location-ask').textContent).toContain(ASK_REASON);
    expect(getCurrentPosition).not.toHaveBeenCalled();
    fireEvent.click(screen.getByText('Allow'));
    await waitFor(() => expect(screen.queryByTestId('location-ask')).toBeNull());
    fireEvent.click(screen.getByText('Save'));
    expect(seen).toHaveBeenLastCalledWith({ latitude: 0.31396, longitude: 32.5811, accuracy_m: 12 });
    cleanup();
    wrap(<Probe onLocation={seen} />);
    expect(screen.queryByTestId('location-ask')).toBeNull();
  });

  it('after No thanks it never asks again and the entry goes without a position', () => {
    const seen = vi.fn();
    wrap(<Probe onLocation={seen} />);
    fireEvent.click(screen.getByText('No thanks'));
    expect(screen.queryByTestId('location-ask')).toBeNull();
    fireEvent.click(screen.getByText('Save'));
    expect(seen).toHaveBeenLastCalledWith(undefined);
    expect(getCurrentPosition).not.toHaveBeenCalled();
    cleanup();
    wrap(<Probe onLocation={seen} />);
    expect(screen.queryByTestId('location-ask')).toBeNull();
  });

  it('a phone that gives no position never blocks the entry', () => {
    getCurrentPosition.mockImplementation((_ok: unknown, fail: (e: unknown) => void) => fail({ code: 3, PERMISSION_DENIED: 1 }));
    localStorage.setItem('bms.entry-location', 'yes');
    const seen = vi.fn();
    wrap(<Probe onLocation={seen} />);
    fireEvent.click(screen.getByText('Save'));
    expect(seen).toHaveBeenLastCalledWith(undefined);
  });

  it('drops a position out of range before it is sent', () => {
    expect(usable({ latitude: 91, longitude: 0, accuracy: 5 })).toBeUndefined();
    expect(usable({ latitude: 0, longitude: 0, accuracy: -1 })).toBeUndefined();
    expect(usable({ latitude: Number.NaN, longitude: 0, accuracy: 5 })).toBeUndefined();
    expect(usable({ latitude: 0.3136, longitude: 32.5811, accuracy: 7.25 })).toEqual({ latitude: 0.3136, longitude: 32.5811, accuracy_m: 7.3 });
  });
});

describe('a sale kept on the phone', () => {
  it('keeps its position in the outbox request', () => {
    const item = offlineSale({
      draft: { branchId: branch, method: 'cash', customerId: '', newBuyerName: '', newBuyerContact: '', dueDate: '', lines: [] } as never,
      clientSaleId: 'c1',
      key: 'k1',
      scope: 'scope',
      branchName: 'Test Branch A',
      location: { latitude: 0.3136, longitude: 32.6, accuracy_m: 20 },
      now: new Date('2026-11-03T08:00:00Z'),
    });
    expect(item.request.location).toEqual({ latitude: 0.3136, longitude: 32.6, accuracy_m: 20 });
    expect(item.request.recorded_offline).toBe(true);
  });
});

describe('only the owner sees where', () => {
  it('shows the owner the distance from the shop on the sale', async () => {
    entryDistances.mockResolvedValue([{ id: 's1', state: 'located', distance_m: 40, accuracy_m: 12, words: '40 m from the shop' }]);
    wrap(<SaleDetail sale={sale} today="2026-11-03" />);
    expect((await screen.findByTestId('sale-where')).textContent).toBe('Where: 40 m from the shop, the phone sure to about 12 m');
    expect(entryDistances).toHaveBeenCalledWith('sale', ['s1']);
  });

  it('never asks for it and never shows it to a seller', async () => {
    entryDistances.mockResolvedValue([{ id: 's1', state: 'located', words: '40 m from the shop' }]);
    wrap(<SaleDetail sale={sale} today="2026-11-03" />, seller);
    await new Promise((r) => setTimeout(r, 20));
    expect(entryDistances).not.toHaveBeenCalled();
    expect(document.body.textContent).not.toContain('from the shop');
    expect(document.body.textContent).not.toContain('Where');
  });

  it('shows the owner where each usage entry was made, and a seller nothing', async () => {
    const m: StockMovement = { id: 'm1', at: '2026-10-20T08:00:00Z', branch_id: branch, product_id: 'p1', description: 'Test bulb', unit: 'piece', kind: 'damage', qty: '-1.000', note: 'Broken', source_type: 'retail.usage', source_id: 'u1' };
    usageHistory.mockResolvedValue({ items: [m] });
    entryDistances.mockResolvedValue([{ id: 'u1', state: 'no_location', words: 'no location' }]);
    wrap(<UsageHistoryPage />);
    expect((await screen.findByTestId('usage-where')).textContent).toBe('Where: no location');
    expect(entryDistances).toHaveBeenCalledWith('usage', ['u1']);
    cleanup();
    entryDistances.mockClear();
    wrap(<UsageHistoryPage />, seller);
    await screen.findByTestId('usage-row');
    expect(screen.queryByTestId('usage-where')).toBeNull();
    expect(entryDistances).not.toHaveBeenCalled();
  });

  it('words a shop with no location plainly', () => {
    expect(whereWords({ id: 'x', state: 'shop_not_set', accuracy_m: 9, words: 'shop location not set' })).toBe('shop location not set');
    expect(whereWords({ id: 'x', state: 'located', distance_m: 3200, accuracy_m: 30, words: '3.2 km from the shop' })).toBe('3.2 km from the shop, the phone sure to about 30 m');
  });
});

describe('Business set-up', () => {
  it('sets a shop location from where the owner stands, never showing the position', async () => {
    shopLocations.mockResolvedValue([{ branch_id: branch, name: 'Test Branch A', has_location: false }]);
    setShopLocation.mockResolvedValue({ branch_id: branch, name: 'Test Branch A', has_location: true });
    wrap(<ShopLocationsCard />);
    expect(await screen.findByText('No location yet')).toBeTruthy();
    fireEvent.click(screen.getByText('Use where I am now'));
    await waitFor(() => expect(setShopLocation).toHaveBeenCalledWith(branch, { latitude: 0.31396, longitude: 32.5811, accuracy_m: 12 }));
    expect(document.body.textContent).not.toContain('32.58');
  });
});
