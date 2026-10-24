import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Product, Sale } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { PackEditor } from './catalogue-packs';
import { Receipt, SaleForm, SaleLineCard } from './sale';
import { newLine } from './sale-state';

// Units of measure on the screens (ADR-033, issue #194). With the capability off nothing is drawn:
// "all off equals today". Names and amounts are fabricated.

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');
const branch = mockMe('admin').branches?.[0]?.id ?? '';
const withUnits = { ...mockMe('admin'), module_capabilities: { retail: ['units'] } };

const page = (me: ReturnType<typeof mockMe>, node: ReactElement, client = new QueryClient()) =>
  renderToString(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );

const pen: Product = { id: 'p1', code: 'T-PEN', description: 'Test pen', unit: 'piece', sell_minor: 100, cost_minor: 83, active: true, qty: '100.000' };

describe('pack sizes on the item', () => {
  it('draws nothing while the units capability is off', () => {
    expect(page(mockMe('admin'), <PackEditor product={pen} />)).toBe('');
  });

  it('draws nothing for a user who cannot manage the catalogue', () => {
    const seller = { ...mockMe('sales'), module_capabilities: { retail: ['units'] } };
    expect(page(seller, <PackEditor product={pen} />)).toBe('');
  });

  it('is offered to a catalogue manager once the capability is on', () => {
    const html = page(withUnits, <PackEditor product={pen} />);
    expect(html).toContain('Cartons, boxes and other packs');
    expect(html).toContain('Add pack');
    expect(html).toContain('for="pack-factor"');
  });
});

describe('the sale form', () => {
  const withPacks: Product = { ...pen, packs: [{ id: 'k1', product_id: 'p1', unit_id: 'u1', unit: 'carton', factor: '12.000', active: true, version: 1 }] };
  const client = new QueryClient();
  client.setQueryData(['retail', 'products', branch, ''], [withPacks]);

  it('offers no unit choice on an empty sale', () => {
    expect(page(withUnits, <SaleForm branchId={branch} />, client)).not.toContain('Sell by');
  });
});

describe('a sale line', () => {
  const withPacks: Product = { ...pen, packs: [{ id: 'k1', product_id: 'p1', unit_id: 'u1', unit: 'carton', factor: '12.000', active: true, version: 1 }] };
  const card = (product: Product, unitsOn: boolean, unitId?: string) =>
    renderToString(
      <SaleLineCard
        line={{ ...newLine(product), ...(unitId ? { unitId } : {}) }}
        index={0}
        unitsOn={unitsOn}
        onQty={() => undefined}
        onPrice={() => undefined}
        onUnit={() => undefined}
        onRemove={() => undefined}
      />,
    );

  it('offers the pack as a way to sell when the capability is on', () => {
    const html = card(withPacks, true);
    expect(html).toContain('Sell by');
    expect(html).toContain('carton (12 piece)');
    expect(html).toContain('data-tour="sale-unit"');
    expect(html).toContain('Quantity (piece)');
  });

  it('prices and counts in the pack once it is chosen', () => {
    const html = card(withPacks, true, 'u1');
    expect(html).toContain('Quantity (carton)');
    expect(html).toContain('Price per carton');
  });

  it('is exactly the old line when the capability is off, packs or not', () => {
    for (const product of [withPacks, pen]) {
      const html = card(product, false, 'u1');
      expect(html).not.toContain('Sell by');
      expect(html).not.toContain('carton');
      expect(html).toContain('Quantity (piece)');
      expect(html).toContain('Unit price');
    }
  });

  it('is the old line for an item without packs even when the capability is on', () => {
    const html = card(pen, true);
    expect(html).not.toContain('Sell by');
    expect(html).toContain('Unit price');
  });
});

describe('pack sizes on the item, accessibly', () => {
  it('names each button for its pack and links a problem to its field', () => {
    // The row is drawn from the query data; the editor's own markup is checked through the fixed labels.
    const html = page(withUnits, <PackEditor product={pen} />);
    expect(html).toContain('for="pack-unit"');
    expect(html).toContain('for="pack-factor"');
  });
});

describe('the receipt', () => {
  const sale: Sale = {
    id: 's1', branch_id: branch, sale_date: '2026-10-05', payment_method: 'cash',
    lines: [
      { product_id: 'p1', description: 'Test pen', qty: '24.000', unit_price_minor: 100, line_total_minor: 2400, sale_unit_id: 'u1', sale_unit: 'carton', sale_qty: '2.000', sale_unit_price_minor: 1200, unit_factor: '12.000' },
      { product_id: 'p2', description: 'Test bulb', qty: '3.000', unit_price_minor: 500, line_total_minor: 1500 },
    ],
    total_minor: 3900, paid_minor: 3900, balance_minor: 0,
  };

  it('names the unit used on a pack line and shows an older line as it was', () => {
    const html = page(mockMe('sales'), <Receipt sale={sale} />);
    expect(html).toContain('2 carton');
    expect(html).toContain('UGX 1,200');
    expect(html).toContain('UGX 2,400');
    expect(html).toContain('>3<');
    expect(html).toContain('UGX 500');
  });
});
