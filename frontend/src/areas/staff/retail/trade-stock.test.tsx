import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Me } from '../../../api/client';
import type { InFlightReport, StockByLocation, Transfer } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { InTransitList } from './in-transit';
import { canUse, screensFor } from './permissions';
import { LocationList, LocationTable, LocationTotals, groupsOf } from './stock-by-location';
import { TransferSummary } from './transfer';
import { TransferList, statusBadge } from './transfers';
import { Gate } from './ui';

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');

// The trade screens of ADR-033 (issue #193): stock by store and shop, stock on its way, and the
// transfer statuses. Static markup, as the other screen tests: what each screen shows, and above all
// that with every capability off the screens are exactly today's.

const [A, B] = mockMe('admin').branches ?? [];
const branch = A?.id ?? '';
const trade = (me: Me, ...capabilities: string[]): Me => ({ ...me, module_capabilities: { retail: capabilities } });
const page = (me: Me, node: ReactElement) =>
  renderToString(
    <QueryClientProvider client={new QueryClient()}>
      <StaffContext.Provider value={{ me, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );

describe('capabilities off: the retail menu is today\'s', () => {
  it('offers neither trade screen to an administrator until its capability is on', () => {
    const admin = mockMe('admin');
    const screens = screensFor(admin).map((s) => s.screen);
    expect(screens).not.toContain('stockByLocation');
    expect(screens).not.toContain('inTransit');
    expect(canUse(admin, 'stockByLocation')).toBe(false);
    expect(canUse(admin, 'inTransit')).toBe(false);
    expect(page(admin, <Gate screen="inTransit" title="On the way"><p>SECRET</p></Gate>)).toContain('You do not have access');
  });

  it('offers each screen with its own capability and the permission to move stock, and a seller sees neither', () => {
    const admin = mockMe('admin');
    expect(canUse(trade(admin, 'branch_roles'), 'stockByLocation')).toBe(true);
    expect(canUse(trade(admin, 'branch_roles'), 'inTransit')).toBe(false);
    expect(canUse(trade(admin, 'transfer_receipt'), 'inTransit')).toBe(true);
    expect(canUse(trade(admin, 'transfer_receipt'), 'stockByLocation')).toBe(false);
    expect(canUse(trade(mockMe('sales'), 'branch_roles', 'transfer_receipt'), 'stockByLocation')).toBe(false);
    expect(canUse(trade(mockMe('sales'), 'branch_roles', 'transfer_receipt'), 'inTransit')).toBe(false);
    expect(canUse({ permissions: ['retail.sale.create'], module_capabilities: { retail: ['branch_roles', 'transfer_receipt'] } }, 'inTransit')).toBe(false);
    expect(screensFor(trade(mockMe('admin'), 'branch_roles', 'transfer_receipt')).map((s) => s.screen)).toEqual(
      expect.arrayContaining(['stockByLocation', 'inTransit']),
    );
  });
});

const report: StockByLocation = {
  currency: 'UGX',
  branches: [
    { id: 'b-store-1', code: 'ST1', name: 'Store One', head_office: true, role: 'store' },
    { id: 'b-store-2', code: 'ST2', name: 'Store Two', head_office: false, role: 'store' },
    { id: 'b-shop', code: 'SH1', name: 'The Shop', head_office: false, role: 'shop' },
  ],
  items: [
    {
      product_id: 'p1', code: 'P001', description: 'Cable roll', category: 'Cables', unit: 'roll',
      balances: [
        { branch_id: 'b-store-1', qty: '10.000', negative: false },
        { branch_id: 'b-store-2', qty: '5.000', negative: false },
        { branch_id: 'b-shop', qty: '3.000', negative: false },
      ],
      subtotals: [{ role: 'store', qty: '15.000' }, { role: 'shop', qty: '3.000' }],
      in_transit_qty: '4.000', combined_qty: '22.000', negative: false, sell_minor: 230000,
      value_minor: 100, combined_value_minor: 150,
    },
  ],
  totals: { by_branch: [], by_role: [{ role: 'store', value_minor: 90 }, { role: 'shop', value_minor: 10 }], value_minor: 100, in_transit_value_minor: 50, combined_value_minor: 150 },
};

describe('stock by store and shop', () => {
  it('groups the branches by role in the server\'s order', () => {
    expect(groupsOf(report.branches ?? []).map((g) => [g.role, g.branches.map((b) => b.code)])).toEqual([
      ['store', ['ST1', 'ST2']],
      ['shop', ['SH1']],
    ]);
  });

  it('draws stores before shops with a subtotal where a role has several branches, then on the way and combined', () => {
    const html = page(trade(mockMe('admin'), 'branch_roles'), <LocationTable report={report} />);
    expect(html).toContain('Stores');
    expect(html).toContain('Shops');
    expect(html.indexOf('Stores')).toBeLessThan(html.indexOf('Shops'));
    expect(html.match(/Subtotal/g)).toHaveLength(1);
    expect(html).toContain('<strong>15</strong>');
    expect(html).toContain('On the way');
    expect(html).toContain('Combined');
    expect(html).toContain('22 roll');
    expect(html).toContain('Value at cost');
  });

  it('draws a phone card with the combined total, the roles and what is on the way', () => {
    const html = page(trade(mockMe('admin'), 'branch_roles'), <LocationList report={report} />);
    expect(html).toContain('Cable roll');
    expect(html).toContain('Combined 22 roll');
    expect(html).toContain('15 roll');
    expect(html).toContain('4 roll');
    expect(html).toContain('Show branches');
  });

  it('shows value totals to a session with retail.profit.read and no cost at all without it', () => {
    expect(page(mockMe('admin'), <LocationTotals report={report} />)).toContain('UGX 150');
    const withoutCost: StockByLocation = {
      ...report,
      items: [{ ...(report.items?.[0] ?? {}), value_minor: undefined, combined_value_minor: undefined }],
      totals: { by_branch: [], by_role: [] },
    };
    expect(page(mockMe('sales'), <LocationTotals report={withoutCost} />)).toBe('');
    expect(page(mockMe('sales'), <LocationTable report={withoutCost} />)).not.toMatch(/Value at cost/);
  });
});

const sent: Transfer = {
  id: 'tr1', from_branch_id: A?.id, to_branch_id: B?.id, transfer_date: '2026-10-06', status: 'in_transit', currency: 'UGX',
  lines: [{ line_no: 1, product_id: 'p1', code: 'P003', description: 'LED bulb 9W screw', qty: '4.000', unit_cost_minor: 3500, line_cost_minor: 14000 }],
  cost_total_minor: 14000,
};

describe('transfer statuses', () => {
  it('says each status in words', () => {
    expect(statusBadge(sent)).toBe('on its way');
    expect(statusBadge({ ...sent, status: 'completed' })).toBeNull();
    expect(statusBadge({ ...sent, status: 'voided' })).toBe('cancelled');
    expect(statusBadge({ ...sent, status: 'cancelled' })).toBe('taken back');
    expect(statusBadge({ ...sent, status: 'received' })).toBe('received');
    expect(statusBadge({ ...sent, status: 'received', lines: [{ ...(sent.lines?.[0] ?? {}), received_qty: '3.000' }] })).toBe('received, some short');
  });

  it('lists a transfer on its way, and shows the sent summary without cost for a seller', () => {
    expect(page(mockMe('admin'), <TransferList items={[sent]} onOpen={() => undefined} />)).toContain('on its way');
    const sellerSummary = page(mockMe('sales'), <TransferSummary transfer={{ ...sent, cost_total_minor: undefined, lines: [{ ...(sent.lines?.[0] ?? {}), unit_cost_minor: undefined, line_cost_minor: undefined }] }} />);
    expect(sellerSummary).toContain('Stock sent');
    expect(sellerSummary).toContain('neither branch');
    expect(sellerSummary).not.toMatch(/cost/i);
  });

  it('shows what arrived against what was sent, and the loss at cost only with retail.profit.read', () => {
    const received: Transfer = { ...sent, status: 'received', lines: [{ ...(sent.lines?.[0] ?? {}), received_qty: '3.000' }], shortage_cost_minor: 3500 };
    const admin = page(mockMe('admin'), <TransferSummary transfer={received} />);
    expect(admin).toContain('Stock received');
    expect(admin).toContain('4 sent, 3 received');
    expect(admin).toContain('Lost on the way, at cost: UGX 3,500');
    const seller = page(mockMe('sales'), <TransferSummary transfer={{ ...received, shortage_cost_minor: undefined, cost_total_minor: undefined }} />);
    expect(seller).toContain('4 sent, 3 received');
    expect(seller).not.toMatch(/cost|Lost on the way/);
  });
});

describe('stock on its way', () => {
  const flight = (items: InFlightReport['items']): InFlightReport => ({ currency: 'UGX', items, in_transit_value_minor: 14000, clearing_minor: 14000, reconciled: true });
  const open = { ...sent, age_days: 2, dispatched_at: '2026-10-06T08:00:00Z' };

  it('says plainly when nothing is on the way', () => {
    expect(page(mockMe('admin'), <InTransitList report={flight([])} />)).toContain('Nothing is on the way.');
  });

  it('offers the receive form where the user may act on the destination and the take-back where they may act on the source', () => {
    const html = page(mockMe('admin'), <InTransitList report={flight([open])} />);
    expect(html).toContain('on its way for 2 days');
    expect(html).toContain('Check what arrived');
    expect(html).toContain('Mark as received');
    expect(html).toContain('Take this back');
    expect(html).toContain('Value at cost: UGX 14,000');
    expect(html).toContain('The books agree with this list.');
  });

  it('shows a seller the list without any form and without cost', () => {
    const seller = mockMe('sales');
    const html = page(seller, <InTransitList report={{ currency: 'UGX', items: [{ ...open, cost_total_minor: undefined }] }} />);
    expect(html).toContain('LED bulb 9W screw');
    expect(html).not.toContain('Mark as received');
    expect(html).not.toContain('Take this back');
    expect(html).not.toMatch(/cost|books/i);
  });

  it('warns when the books do not agree with the list', () => {
    const html = page(mockMe('admin'), <InTransitList report={{ ...flight([open]), reconciled: false }} />);
    expect(html).toContain('The books do not agree with this list.');
  });
});
