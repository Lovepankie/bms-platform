import { QueryClient } from '@tanstack/react-query';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { ALL_BRANCHES } from '../../../auth/branch';
import { pageAs, sessionOf, html } from './cashbook-test-utils';
import { SCREENS, canUse, needsOf, screensFor, type RetailScreen } from './permissions';
import { TOAST_MS, Toast } from './toast';
import { BranchRequired, Gate } from './ui';

const CASH_SCREENS: RetailScreen[] = ['savings', 'banking', 'expenses', 'withdrawals', 'advances', 'cashSummary', 'bankingReport', 'expensesReport', 'expenseSetup'];

describe('cash book routes and tiles', () => {
  it('has a route for every tile', () => {
    const router = readFileSync(new URL('../../../app/router.tsx', import.meta.url), 'utf8');
    for (const s of SCREENS) {
      const suffix = s.path.replace('/staff/retail', '') || '/';
      expect(router, s.path).toContain(`retailScreen('${suffix}')`);
    }
  });

  it('offers each cash book tile only to a session holding its permission', () => {
    const admin = screensFor(sessionOf('admin')).map((s) => s.screen);
    const cashier = screensFor(sessionOf('cashier')).map((s) => s.screen);
    const sales = screensFor(sessionOf('sales')).map((s) => s.screen);
    for (const s of CASH_SCREENS) expect(admin).toContain(s);
    expect(cashier.filter((s) => CASH_SCREENS.includes(s))).toEqual(['banking', 'savings', 'expenses', 'cashSummary', 'bankingReport', 'expensesReport']);
    expect(sales.filter((s) => CASH_SCREENS.includes(s))).toEqual([]);
  });

  it('shows a screen only to a session that may use it, and says so otherwise', () => {
    for (const screen of ['withdrawals', 'advances', 'expenseSetup'] as const) {
      expect(canUse(sessionOf('cashier'), screen)).toBe(false);
      const denied = pageAs(sessionOf('cashier'), <Gate screen={screen} title="Hidden"><p>SECRET-CONTENT</p></Gate>, new QueryClient());
      expect(denied).toContain('You do not have access');
      expect(denied).not.toContain('SECRET-CONTENT');
      expect(pageAs(sessionOf('admin'), <Gate screen={screen} title="Shown"><p>CONTENT</p></Gate>, new QueryClient())).toContain('CONTENT');
    }
  });

  it('drops a screen when one of its permissions is taken away', () => {
    expect(canUse(sessionOf('admin', ['retail.savings.record']), 'savings')).toBe(false);
    expect(canUse(sessionOf('admin', ['retail.cashbook.read']), 'expenseSetup')).toBe(false);
    expect(canUse(sessionOf('admin', ['retail.advance.create']), 'advances')).toBe(true);
    expect(canUse(sessionOf('admin', ['retail.advance.create', 'retail.advance.repay']), 'advances')).toBe(false);
  });

  it('asks for one branch on every write screen when All branches is chosen', () => {
    for (const screen of ['savings', 'banking', 'expenses', 'withdrawals'] as const) {
      const out = pageAs(sessionOf('admin'), <BranchRequired permissions={needsOf(screen)} />, new QueryClient(), ALL_BRANCHES);
      expect(out, screen).toContain('Choose a branch to continue');
    }
    // A session with no branch where the permission is held is told so rather than offered nothing.
    const none = pageAs(sessionOf('cashier'), <BranchRequired permissions={['retail.withdrawal.record']} />, new QueryClient(), ALL_BRANCHES);
    expect(none).toContain('You have no branch where you can do this');
  });
});

describe('toast', () => {
  it('is a polite status message that goes away by itself', () => {
    const out = html(<Toast message="Savings recorded." />);
    expect(out).toContain('role="status"');
    expect(out).toContain('class="toast"');
    expect(out).toContain('Savings recorded.');
    expect(TOAST_MS).toBeGreaterThan(1000);
    expect(TOAST_MS).toBeLessThanOrEqual(10000);
  });
});
