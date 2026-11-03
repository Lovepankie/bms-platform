import { describe, expect, it } from 'vitest';
import { renderToString } from 'react-dom/server';
import type { Me } from '../../../api/client';
import type { AdvisorBrief } from '../../../api/advisor';
import { retailAdvisor } from '../../../api/retail-advisor';
import { advisor } from '../../../api/advisor';
import { AdvisorBriefView } from '../insights/advisor';
import { settingsClientFor } from '../assistant/page';
import { canConfigureRetailAdvisor, canUseRetailAdvisor, pageOf } from './advisor-card';

// The shop's AI assistant card on Home (issue #422). Every figure and name here is fabricated.

const me = (permissions: string[], modules = ['retail']) => ({ permissions, modules }) as unknown as Me & { modules: string[] };

const brief: AdvisorBrief = {
  advisory: true,
  source: 'model',
  reason: null,
  label: 'AI assistant (advisory)',
  as_of: '2026-10-20T06:05:00Z',
  facts_as_of: '2026-10-19',
  summary: 'Advisory summary for 19 October 2026. These are suggestions to check before acting; nothing has been changed.',
  points: [{ text: 'Medium priority. Test Soap Bar: sales in the last seven days (UGX 300,000). Suggested action: Keep enough stock.', facts: ['item_a.sales_week'] }],
  facts: [{ key: 'item_a.sales_week', label: 'Test Soap Bar: sales in the last seven days', display: 'UGX 300,000', metric: 'item.sales_week' }],
  note: 'Made from your shop figures. Check before acting. It does not change any record.',
};

describe('the shop AI brief card', () => {
  it('is for a shop session holding retail.advisor.use', () => {
    expect(canUseRetailAdvisor(me(['retail.advisor.use', 'retail.stock.read']))).toBe(true);
    expect(canUseRetailAdvisor(me(['retail.stock.read']))).toBe(false);
    expect(canUseRetailAdvisor(me(['retail.advisor.use', 'retail.stock.read'], ['lending']))).toBe(false);
  });

  it('opens the page behind each figure', () => {
    expect(pageOf('sales.yesterday')).toBe('/staff/retail/sales-analysis');
    expect(pageOf('item.sales_week')).toBe('/staff/retail/sales-analysis');
    expect(pageOf('own.sales_yesterday')).toBe('/staff/retail/sales');
    expect(pageOf('credit.overdue')).toBe('/staff/retail/credit-control');
    expect(pageOf('stock.low')).toBe('/staff/retail/stock');
    expect(pageOf('stock.slow_items')).toBe('/staff/retail/stock-health');
    expect(pageOf('controls.voided_sales')).toBe('/staff/retail/controls');
    expect(pageOf('orders.open')).toBe('/staff/retail/orders');
    expect(pageOf('anything.else')).toBeNull();
  });

  it('draws the label, the points and a button per figure', () => {
    const html = renderToString(<AdvisorBriefView brief={brief} onChip={() => {}} />);
    expect(html).toContain('AI assistant (advisory)');
    expect(html).toMatch(/<button[^>]*>Test Soap Bar: sales in the last seven days<!-- -->: <!-- -->UGX 300,000<\/button>/);
  });
});

describe('the assistant settings of a shop', () => {
  it('reads the lending settings for a lending administrator and the shop settings otherwise', () => {
    expect(settingsClientFor(me(['lending.advisor.configure'], ['lending']))?.client).toBe(advisor);
    expect(settingsClientFor(me(['retail.advisor.configure', 'retail.stock.read']))?.client).toBe(retailAdvisor);
    expect(settingsClientFor(me(['retail.stock.read']))).toBeNull();
    expect(canConfigureRetailAdvisor(me(['retail.advisor.configure']))).toBe(true);
  });

  it('reads the shop settings for the administrator of a shop only business, even holding both configure permissions', () => {
    const both = ['lending.advisor.configure', 'retail.advisor.configure', 'retail.stock.read'];
    expect(settingsClientFor(me(both, ['retail']))?.client).toBe(retailAdvisor);
    expect(settingsClientFor(me(both, ['lending']))?.client).toBe(advisor);
    expect(settingsClientFor(me(['lending.advisor.configure'], ['retail']))).toBeNull();
  });
});
