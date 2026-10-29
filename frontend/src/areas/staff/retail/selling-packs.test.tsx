import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Pack, Product } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { PackEditor, packBelowCostHint, packChange, shownPackPrice, suggestedHint } from './catalogue-packs';
import { SaleLineCard } from './sale';
import { newLine } from './sale-state';
import { defaultPrice } from './sale-state';
import { buyingPacks, mayKeepSize, packWalkInPrice, sellingPacks } from './units-state';

// Selling packs with their own prices (issue #624, ADR-099): a walk-in sale takes a pack's own price, else the piece price
// times the size; a pack is offered only where it is used; Edit item shows the packs in a small table. Names and amounts are
// fabricated.

const pack = (unit: string, factor: string, extra: Partial<Pack> = {}): Pack => ({
  id: `k-${unit}`, product_id: 'p1', unit_id: `u-${unit}`, unit, factor, active: true, version: 1, purpose: 'both', ...extra,
});

const carton = pack('carton', '24.000', { sell_minor: 20_000, suggested_sell_minor: 24_000 });
const dozen = pack('dozen', '12.000', { purpose: 'sell', suggested_sell_minor: 12_000 });
const box = pack('box', '10.000', { purpose: 'buy' });
const bulb: Product = { id: 'p1', code: 'T-BULB', description: 'Test bulb', unit: 'piece', unit_id: 'u-piece', sell_minor: 1_000, cost_minor: 750, active: true, qty: '100.000', packs: [carton, dozen, box] };

describe('the walk-in price of a pack', () => {
  it('is the pack\'s own price when it has one, else the piece price times the size', () => {
    expect(packWalkInPrice(1_000, carton)).toBe(20_000);
    expect(packWalkInPrice(1_000, dozen)).toBe(12_000);
    expect(defaultPrice(bulb, 'u-carton')).toBe(20_000);
    expect(defaultPrice(bulb, 'u-dozen')).toBe(12_000);
    expect(defaultPrice(bulb, undefined)).toBe(1_000);
  });

  it('offers a pack only where it is used', () => {
    expect(sellingPacks(bulb).map((k) => k.unit)).toEqual(['carton', 'dozen']);
    expect(buyingPacks(bulb).map((k) => k.unit)).toEqual(['carton', 'box']);
    // A pack read before #624 has no purpose and is used for both, as before.
    const old: Product = { ...bulb, packs: [{ ...carton, purpose: undefined }] };
    expect(sellingPacks(old)).toHaveLength(1);
    expect(buyingPacks(old)).toHaveLength(1);
  });

  it('shows the own price, the suggested one, or none for a pack only for buying', () => {
    expect(shownPackPrice(bulb, carton)).toEqual({ minor: 20_000, own: true });
    expect(shownPackPrice(bulb, dozen)).toEqual({ minor: 12_000, own: false });
    expect(shownPackPrice(bulb, box)).toBeNull();
    expect(suggestedHint(bulb, '12')).toContain('Suggested UGX 12,000');
    expect(suggestedHint(bulb, '0')).toBeNull();
  });
});

describe('the below-cost hint while a price is typed', () => {
  it('warns when the pack price is not above its size times the last price paid, only with the cost', () => {
    // 24 x 750 = 18,000.
    expect(packBelowCostHint(bulb, '24', 18_000)).toContain('not above the buying price of 24 piece at the last price paid, UGX 18,000');
    expect(packBelowCostHint(bulb, '24', 18_001)).toBeNull();
    // An empty price is the suggested one, 24,000: above.
    expect(packBelowCostHint(bulb, '24', null)).toBeNull();
    const noCost: Product = { ...bulb, cost_minor: undefined };
    expect(packBelowCostHint(noCost, '24', 1)).toBeNull();
  });
});

describe('the sale line, which quotations and orders draw too', () => {
  it('offers the selling packs only, and prices a pack at its own price', () => {
    const me = { ...mockMe('admin'), module_capabilities: { retail: ['units'] } };
    const html = render(
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>
        <SaleLineCard line={{ ...newLine(bulb), unitId: 'u-carton', price: String(defaultPrice(bulb, 'u-carton')) }} index={0} unitsOn onQty={() => undefined} onPrice={() => undefined} onUnit={() => undefined} onRemove={() => undefined} />
      </StaffContext.Provider>,
    ).replaceAll('<!-- -->', '');
    expect(html).toContain('>carton (24 piece)<');
    expect(html).toContain('>dozen (12 piece)<');
    expect(html).not.toContain('>box (10 piece)<');
    expect(html).toContain('value="20000"');
  });
});

describe('use from now on on a pack with its own price', () => {
  it('is offered only to someone who may change prices, or on a pack without its own price', () => {
    expect(mayKeepSize(carton, true, true)).toBe(true);
    expect(mayKeepSize(carton, true, false)).toBe(false);
    expect(mayKeepSize(dozen, true, false)).toBe(true);
    expect(mayKeepSize(dozen, false, true)).toBe(false);
  });
});

describe('saving a pack', () => {
  it('sends only what changed', () => {
    expect(packChange(carton, { factor: '24', purpose: 'both', priceMinor: 20_000 }, true)).toEqual({});
    expect(packChange(carton, { factor: '30', purpose: 'both', priceMinor: 19_500 }, true)).toEqual({ factor: '30', sell_minor: 19_500 });
    // An emptied price goes back to the suggested one.
    expect(packChange(carton, { factor: '24', purpose: 'both', priceMinor: null }, true)).toEqual({ clear_sell_minor: true });
    // A pack made for buying only loses its price on the server; nothing about the price is sent.
    expect(packChange(carton, { factor: '24', purpose: 'buy', priceMinor: 20_000 }, true)).toEqual({ purpose: 'buy' });
    // Someone who may not change prices never sends one.
    expect(packChange(carton, { factor: '24', purpose: 'both', priceMinor: 1 }, false)).toEqual({});
  });
});

describe('the pack table on Edit item', () => {
  const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');
  const me = { ...mockMe('admin'), module_capabilities: { retail: ['units'] } };
  const client = new QueryClient();
  client.setQueryData(['retail', 'packs', 'p1'], [carton, dozen, box, pack('tray', '30.000', { suggested_sell_minor: 30_000, sell_minor: 22_000, below_cost: true })]);
  const html = renderToString(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>
        <PackEditor product={bulb} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );

  it('lists each pack with its size and price, the piece first', () => {
    expect(html).toContain('data-testid="pack-table"');
    expect(html).toContain('>Size<');
    expect(html).toContain('>Selling price<');
    expect(html).toContain('UGX 20,000');
    expect(html).toContain('UGX 12,000');
    expect(html).toContain('suggested');
    expect(html).toContain('Not sold');
    expect(html).toContain('Buying only');
    expect(html).toContain('Selling only');
    expect(html.indexOf('UGX 1,000')).toBeLessThan(html.indexOf('UGX 20,000'));
  });

  it('marks a pack below cost and names each Change button', () => {
    expect(html).toContain('Below buying price');
    expect(html).toContain('aria-label="Change the carton pack"');
  });

  it('asks what a new pack is for and its price', () => {
    expect(html).toContain('aria-label="Used for"');
    expect(html).toContain('Selling price of one pack');
  });
});
