import { describe, expect, it } from 'vitest';
import { renderToString } from 'react-dom/server';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { BusinessDetail, PlanPrice } from '../../api/operator';
import { AddonSection } from './addon';
import { addonLine, expectedAmount, withAddon } from './operator-state';

// The AI assistant add-on on the operator's screens (issue #423, ADR-112). The figures are invented.

const plan = { monthly_minor: 40_000, annual_months: 10, annual_minor: 400_000, currency: 'UGX' } as PlanPrice;

describe('what a business owes with the add-on', () => {
  it('adds a charged add-on to the month and twelve of it to the year', () => {
    const owed = withAddon(plan, { charged: true, monthly_minor: 7_300 });
    expect(owed?.monthly_minor).toBe(47_300);
    expect(owed?.annual_minor).toBe(487_600);
    expect(expectedAmount(owed, 1)).toBe('47300');
  });

  it('adds nothing when the add-on is off, not charged or has no price', () => {
    expect(withAddon(plan, { charged: false, monthly_minor: 7_300 })).toBe(plan);
    expect(withAddon(plan, { charged: true, monthly_minor: null })).toBe(plan);
    expect(withAddon(plan, undefined)).toBe(plan);
  });

  it('says in one plain line whether the business may use it and what it costs', () => {
    expect(addonLine(undefined, 'UGX')).toContain('Off');
    expect(addonLine({ on: true, no_charge: true }, 'UGX')).toBe('On, with no charge.');
    expect(addonLine({ on: true, charged: true, monthly_minor: 7_300 }, 'UGX')).toContain('UGX 7,300 a month');
  });
});

describe('the add-on on the Business page', () => {
  const detail = (on: boolean) =>
    ({ tenant: { name: 'Test Shop 01', currency: 'UGX' }, ai_addon: { on, no_charge: false, charged: on, monthly_minor: 7_300 } }) as unknown as BusinessDetail;
  const render = (d: BusinessDetail) =>
    renderToString(
      <QueryClientProvider client={new QueryClient()}>
        <AddonSection tenantId="t-1" detail={d} />
      </QueryClientProvider>,
    );

  it('offers to switch it on, charged or with no charge, while it is off', () => {
    const html = render(detail(false));
    expect(html).toContain('Switch on');
    expect(html).toContain('Switch on with no charge');
    expect(html).not.toContain('Switch off');
  });

  it('offers to switch it off while it is on', () => {
    const html = render(detail(true));
    expect(html).toContain('Switch off');
    expect(html).toContain('UGX 7,300 a month');
  });
});
