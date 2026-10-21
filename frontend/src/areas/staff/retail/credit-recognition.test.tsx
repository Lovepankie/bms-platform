import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { renderToString as render } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Dashboard } from '../../../api/retail-analytics';
import type { DailyProfit } from '../../../api/retail';
import { CANNOT_SWITCH_OFF, CreditRecognitionSetting, RECOGNITION_CHOICES, recognitionOf, recognitionSentences, ruleStarted, startDay } from './credit-recognition';
import { DashboardView } from './dashboard';
import { ProfitTable } from './profit';

const renderToString = (node: ReactElement) => render(node).replaceAll('<!-- -->', '');

// How credit sales count (ADR-043, issue #340): the setting in plain words, the home cards and the profit report under each
// value. The default value keeps every screen exactly as it was.

const nameOf = () => 'Head office';

const range = (current: NonNullable<Dashboard['range']>['current']): Dashboard['range'] => ({
  period: 'today', from: '2026-10-20', to: '2026-10-20', previous_from: '2026-10-19', previous_to: '2026-10-19', group: 'day',
  current, previous: { sales_minor: 4000, sale_count: 2, average_sale_minor: 2000 }, series: [{ start: '2026-10-20', sales_minor: current?.sales_minor ?? 0, previous_sales_minor: 4000 }],
});

const base: Dashboard = {
  date: '2026-10-20',
  currency: 'UGX',
  profit_visible: true,
  today: { sales_minor: 5000, sale_count: 3, cash_minor: 2000, credit_minor: 3000, gross_profit_minor: 2000 },
  last7_sales_minor: 5000,
  last30_sales_minor: 5000,
  shops: [],
  range: range({ sales_minor: 5000, sale_count: 3, average_sale_minor: 1667, gross_profit_minor: 2000 }),
};

const onPayment: Dashboard = {
  ...base,
  credit_sale_recognition: 'on_payment',
  today: { sales_minor: 5000, sale_count: 3, cash_minor: 2000, credit_minor: 3000, gross_profit_minor: 800, received_minor: 2000, on_credit_minor: 7000, on_credit_count: 2 },
  range: range({ sales_minor: 5000, sale_count: 3, average_sale_minor: 1667, gross_profit_minor: 800, received_minor: 2000 }),
};

describe('the setting', () => {
  it('reads anything but on_payment as the way it works today', () => {
    expect(recognitionOf(undefined)).toBe('on_sale');
    expect(recognitionOf({})).toBe('on_sale');
    expect(recognitionOf({ credit_sale_recognition: 'on_sale' })).toBe('on_sale');
    expect(recognitionOf({ credit_sale_recognition: 'on_payment' })).toBe('on_payment');
  });

  it('says in plain words what each choice does and what a change will do', () => {
    expect(RECOGNITION_CHOICES.map((c) => c.value)).toEqual(['on_sale', 'on_payment']);
    expect(recognitionSentences('on_sale', 'on_sale')).toEqual([]);
    const toPayment = recognitionSentences('on_sale', 'on_payment', '2026-10-20').join(' ');
    expect(toPayment).toContain('On credit');
    expect(toPayment).toContain('From 2026-10-20 (tomorrow) on');
    expect(toPayment).toContain('A payment is then dated today');
    expect(toPayment).toContain('Credit sales dated before 2026-10-20 keep counting the way they did');
    expect(toPayment).toContain('Stock still goes down');
    expect(toPayment).toContain('Savings already saved are not changed');
    expect(toPayment).toContain('cannot be switched off');
    const back = recognitionSentences('on_payment', 'on_sale', '2026-10-25').join(' ');
    expect(back).toContain('cancels the change before it starts');
    expect(`${toPayment} ${RECOGNITION_CHOICES.map((c) => c.hint).join(' ')}`).not.toMatch(/[\u2013\u2014]/);
  });

  it('shows both choices with the saved one chosen and a Review button that waits for a change', () => {
    const client = new QueryClient();
    const html = renderToString(
      <QueryClientProvider client={client}>
        <CreditRecognitionSetting read={{ capabilities: { profile: 'shop' }, version: 1 }} />
      </QueryClientProvider>,
    );
    expect(html).toContain('When the sale is made');
    expect(html).toContain('When the buyer pays');
    expect(html).toContain('data-tour="retail-credit-recognition"');
    expect(html).toMatch(/checked=""[^>]*\/><span><strong>When the sale is made/);
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>Review change/);
  });

  it('starts the day after today', () => {
    expect(startDay('2026-10-20')).toBe('2026-10-21');
    expect(startDay('2026-12-31')).toBe('2027-01-01');
  });

  it('says since when the saved rule stands', () => {
    const html = renderToString(
      <QueryClientProvider client={new QueryClient()}>
        <CreditRecognitionSetting read={{ capabilities: { profile: 'shop', credit_sale_recognition: 'on_payment', credit_recognition_effective_from: '2026-10-20' }, version: 2 }} />
      </QueryClientProvider>,
    );
    expect(html).toContain('This rule has stood since 2026-10-20');
    const future = renderToString(
      <QueryClientProvider client={new QueryClient()}>
        <CreditRecognitionSetting read={{ capabilities: { profile: 'shop', credit_sale_recognition: 'on_payment', credit_recognition_effective_from: '2999-01-01' }, version: 2 }} />
      </QueryClientProvider>,
    );
    expect(future).toContain('This rule will stand from 2999-01-01');
  });

  it('is one way: once started the other choice is off and there is nothing to review', () => {
    expect(CANNOT_SWITCH_OFF).toBe('This rule cannot be switched off once it has started. Ask Rincol support.');
    expect(ruleStarted({ credit_sale_recognition: 'on_payment', credit_recognition_effective_from: '2026-10-20' }, '2026-10-20')).toBe(true);
    expect(ruleStarted({ credit_sale_recognition: 'on_payment', credit_recognition_effective_from: '2026-10-21' }, '2026-10-20')).toBe(false);
    expect(ruleStarted({ credit_sale_recognition: 'on_sale' }, '2026-10-20')).toBe(false);
    const started = renderToString(
      <QueryClientProvider client={new QueryClient()}>
        <CreditRecognitionSetting read={{ capabilities: { profile: 'shop', credit_sale_recognition: 'on_payment', credit_recognition_effective_from: '2026-10-20' }, version: 2 }} />
      </QueryClientProvider>,
    );
    expect(started).toContain(CANNOT_SWITCH_OFF);
    expect(started).toMatch(/<input[^>]*disabled=""[^>]*\/><span><strong>When the sale is made/);
    expect(started).not.toContain('Review change');
    const pending = renderToString(
      <QueryClientProvider client={new QueryClient()}>
        <CreditRecognitionSetting read={{ capabilities: { profile: 'shop', credit_sale_recognition: 'on_payment', credit_recognition_effective_from: '2999-01-01' }, version: 2 }} />
      </QueryClientProvider>,
    );
    expect(pending).not.toContain(CANNOT_SWITCH_OFF);
    expect(pending).toContain('you can cancel it');
    expect(pending).toContain('Review change');
  });

  it('starts on the buyer pays choice when that is saved', () => {
    const html = renderToString(
      <QueryClientProvider client={new QueryClient()}>
        <CreditRecognitionSetting read={{ capabilities: { profile: 'shop', credit_sale_recognition: 'on_payment' }, version: 2 }} />
      </QueryClientProvider>,
    );
    expect(html).toMatch(/checked=""[^>]*\/><span><strong>When the buyer pays/);
  });
});

describe('the home cards', () => {
  it('keep their words under the default way', () => {
    const html = renderToString(<DashboardView data={base} nameOf={nameOf} choice={{ period: 'today' }} />);
    expect(html).toContain('Sales, Today');
    expect(html).toContain('UGX 5,000');
    expect(html).toContain('Up UGX 1,000, 25% on yesterday');
    expect(html).not.toContain('Money collected');
    expect(html).not.toContain('not paid yet');
    // The Today card keeps its figures exactly under the default way.
    expect(html).toContain('Cash and mobile money');
    expect(html).toContain('UGX 3,000');
    expect(html).toContain('Profit today');
  });

  it('say Sold, money collected, On credit now and profit of the money received when the buyer pays', () => {
    const html = renderToString(<DashboardView data={onPayment} nameOf={nameOf} choice={{ period: 'today' }} />);
    expect(html).toContain('Sold, Today');
    expect(html).toContain('credit included');
    expect(html).toContain('Money collected');
    expect(html).toContain('UGX 2,000');
    expect(html).toContain('On credit now');
    expect(html).toContain('UGX 7,000');
    expect(html).toContain('2 sales not paid yet');
    expect(html).toContain('From the money received');
    expect(html).toContain('UGX 800');
    // The Today card under the rule: Sales today including credit, Money received today, On credit, Profit today.
    expect(html).toContain('Sales today');
    expect(html).toContain('including credit');
    expect(html).toContain('Money received today');
    expect(html).toContain('Profit today');
    expect(html).not.toContain('UGX 3,000');
  });

  it('show no profit to a caller without it, and the money collected still', () => {
    const noProfit: Dashboard = { ...onPayment, profit_visible: false, range: range({ sales_minor: 5000, sale_count: 3, received_minor: 2000 }) };
    const html = renderToString(<DashboardView data={noProfit} nameOf={nameOf} choice={{ period: 'today' }} />);
    expect(html).toContain('Money collected');
    expect(html).not.toContain('Profit');
  });
});

describe('the daily profit report', () => {
  const rows = [
    { branch_id: 'b1', date: '2026-10-19', sales_minor: 0, cost_of_sales_minor: 0, gross_profit_minor: 0, usage_cost_minor: 0, stocktake_difference_minor: 0, profit_minor: 0, sold_minor: 3000 },
    { branch_id: 'b1', date: '2026-10-20', sales_minor: 1000, cost_of_sales_minor: 600, gross_profit_minor: 400, usage_cost_minor: 0, stocktake_difference_minor: 0, profit_minor: 400, sold_minor: 0 },
  ];
  const report = (extra: Partial<DailyProfit>): DailyProfit => ({
    from: '2026-10-19', to: '2026-10-20', currency: 'UGX', rows, sales_minor: 1000, cost_of_sales_minor: 600, usage_cost_minor: 0, stocktake_difference_minor: 0, profit_minor: 400, ...extra,
  });

  it('is unchanged under the default way', () => {
    const html = renderToString(<ProfitTable report={report({ credit_sale_recognition: 'on_sale' })} />);
    expect(html).toContain('Profit for the period');
    expect(html).toContain('>Sales<');
    expect(html).not.toContain('Received');
    expect(html).not.toContain('On credit now');
  });

  it('shows Sold, Received and what is still owed when the buyer pays', () => {
    const html = renderToString(<ProfitTable report={report({ credit_sale_recognition: 'on_payment', sold_minor: 3000, on_credit_minor: 2000 })} />);
    expect(html).toContain('Profit from money received, for the period');
    expect(html).toContain('>Received<');
    expect(html).toContain('>Sold<');
    expect(html).toContain('On credit now');
    expect(html).toContain('UGX 3,000');
    expect(html).toContain('UGX 2,000');
    expect(html).toContain('Savings use this profit');
    expect(html).not.toContain('Credit counted at the sale');
  });

  const straddle = (extra: Partial<DailyProfit> = {}): DailyProfit =>
    report({
      credit_sale_recognition: 'on_payment',
      sold_minor: 3000,
      on_credit_minor: 500,
      received_minor: 1900,
      sales_minor: 2500,
      cost_of_sales_minor: 1500,
      profit_minor: 1000,
      counted_at_sale_minor: 400,
      rows: [
        { branch_id: 'b1', date: '2026-10-10', sales_minor: 1000, cost_of_sales_minor: 600, gross_profit_minor: 400, usage_cost_minor: 0, stocktake_difference_minor: 0, profit_minor: 400, sold_minor: 1000, received_minor: 400, counted_at_sale_minor: 400 },
        { branch_id: 'b1', date: '2026-10-20', sales_minor: 1500, cost_of_sales_minor: 900, gross_profit_minor: 600, usage_cost_minor: 0, stocktake_difference_minor: 0, profit_minor: 600, sold_minor: 2000, received_minor: 1500, counted_at_sale_minor: 0 },
      ],
      ...extra,
    });
  const summaryOf = (html: string): string => {
    const at = html.indexOf('data-testid="credit-recognition-summary"');
    return at < 0 ? '' : html.slice(at, html.indexOf('</ul>', at));
  };
  const tile = (summary: string, label: string): string | undefined =>
    new RegExp(`an-stat-label">${label}</span><strong class="an-stat-value num">([^<]*)</strong>`).exec(summary)?.[1];

  it('shows Received as the money recorded as collected, by any method, apart from the profit counted at the sale before the rule', () => {
    const html = renderToString(<ProfitTable report={straddle()} />);
    const summary = summaryOf(html);
    expect(tile(summary, 'Sold')).toBe('UGX 3,000');
    expect(tile(summary, 'Received')).toBe('UGX 1,900');
    expect(tile(summary, 'On credit now')).toBe('UGX 500');
    expect(tile(summary, 'Net credit profit counted at the sale')).toBe('UGX 400');
    expect(summary).not.toContain('not collected');
    expect(html).not.toContain('Profit from money received');
    // The 2026-10-10 row: Sold, Received (the 400 deposit that day), then what counted for profit.
    const cells = /<td>2026-10-10<\/td><td class="num">([^<]*)<\/td><td class="num">([^<]*)<\/td><td class="num">([^<]*)<\/td>/.exec(html);
    expect(cells?.slice(1, 4)).toEqual(['UGX 1,000', 'UGX 400', 'UGX 1,000']);
  });

  it('words a negative credit profit counted at the sale as a net figure', () => {
    const html = renderToString(<ProfitTable report={straddle({ counted_at_sale_minor: -300 })} />);
    const summary = summaryOf(html);
    expect(summary).toContain('Net credit profit counted at the sale');
    expect(summary).not.toContain('>Credit counted at the sale<');
    expect(tile(summary, 'Net credit profit counted at the sale')).toMatch(/300/);
  });

  it('shows no such tile when nothing was counted at the sale', () => {
    const html = renderToString(<ProfitTable report={straddle({ counted_at_sale_minor: 0 })} />);
    expect(summaryOf(html)).not.toContain('counted at the sale');
  });
});
