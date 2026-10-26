// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ApprovalRule, ApprovalRules } from '../../../api/approval-rules';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';
import { changesOf, draftOf, loosens, reviewLines, ruleWords, zeroWords } from './state';

// The approval rules screen (issue #343, ADR-091): lending always on, retail switches with an amount, a review in plain words, the
// administrator's own password before saving, and the others told when a rule is loosened. Fabricated data.

const get = vi.fn();
const save = vi.fn();

vi.mock('../../../api/approval-rules', async (original) => {
  const real = await original<typeof import('../../../api/approval-rules')>();
  return { ...real, approvalRules: { get: () => get(), save: (...a: unknown[]) => save(...a) } };
});

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const { ApprovalRulesScreen } = await import('./page');

afterEach(() => {
  cleanup();
  get.mockReset();
  save.mockReset();
});

const rules: ApprovalRule[] = [
  { action_type: 'loan_disbursement', module: 'lending', label: 'Loan disbursement', has_amount: true, switchable: false, on: true, threshold_minor: 0 },
  { action_type: 'loan_write_off', module: 'lending', label: 'Loan write off', has_amount: false, switchable: false, on: true },
  { action_type: 'retail_sale_void', module: 'retail', label: 'Sale voids', has_amount: true, switchable: true, on: false, threshold_minor: 0 },
  { action_type: 'retail_sale_write_off', module: 'retail', label: 'Credit write-offs', has_amount: true, switchable: true, on: true, threshold_minor: 0 },
];
const read: ApprovalRules = { rules, currency: 'UGX', version: 4 };

const admin = mockMe('admin');
const manager = { ...admin, permissions: (admin.permissions ?? []).filter((p) => p !== 'core.settings.manage') };

function screenOf(me = { ...admin, permissions: [...(admin.permissions ?? []), 'core.settings.manage'], mfa_enabled: false }) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: null }}>
        <ApprovalRulesScreen />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('the approval rules state', () => {
  it('sends only what changed, with the amount of an action that has one', () => {
    const draft = draftOf(rules);
    expect(changesOf(rules, draft)).toEqual([]);
    draft.retail_sale_void = { on: true, amount: 50_000 };
    expect(changesOf(rules, draft)).toEqual([{ action_type: 'retail_sale_void', on: true, threshold_minor: 50_000 }]);
    expect(reviewLines(rules, draft, 'UGX')).toEqual(['Sale voids: a second person from UGX 50,000 (was no second person).']);
    expect(loosens(rules, draft)).toBe(false);
  });

  it('calls switching off or raising an amount a loosening, and says every one for 0', () => {
    const draft = draftOf(rules);
    draft.retail_sale_write_off = { on: false, amount: 0 };
    expect(loosens(rules, draft)).toBe(true);
    expect(ruleWords({ has_amount: true }, { on: true, amount: 0 }, 'UGX')).toBe('a second person for every one');
    expect(ruleWords({ has_amount: false }, { on: true, amount: null }, 'UGX')).toBe('a second person for every one');
    expect(ruleWords({ has_amount: true, action_type: 'retail_sale_write_off' }, { on: true, amount: 0 }, 'UGX')).toBe('a second administrator when the business has two or more');
    expect(ruleWords({ has_amount: true, action_type: 'retail_sale_write_off' }, { on: true, amount: 5000 }, 'UGX')).toBe('a second person from UGX 5,000');
    expect(zeroWords('retail_sale_write_off')).toBe('0 means a second administrator when the business has two or more.');
    expect(zeroWords('retail_expense')).toBe('0 means every one.');
  });
});

describe('the approval rules screen', () => {
  it('lists lending as always on and retail as switches, with the below-cost note', async () => {
    get.mockResolvedValue(read);
    screenOf();
    const lending = await screen.findByRole('region', { name: 'Lending' });
    expect(within(lending).getAllByText('Always on')).toHaveLength(2);
    expect(within(lending).queryByRole('checkbox')).toBeNull();
    const shop = screen.getByRole('region', { name: 'Shop' });
    expect((within(shop).getByRole('checkbox', { name: /Sale voids/ }) as HTMLInputElement).checked).toBe(false);
    expect((within(shop).getByRole('checkbox', { name: /Credit write-offs/ }) as HTMLInputElement).checked).toBe(true);
    expect(screen.getByTestId('below-cost-note').textContent).toContain('Selling below cost is not an approval.');
    expect((screen.getByRole('button', { name: 'Review and save' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('reads the change back, asks for the password, and saves with the version it read', async () => {
    get.mockResolvedValue(read);
    save.mockResolvedValue({ ...read, version: 5, rules: rules.map((r) => (r.action_type === 'retail_sale_void' ? { ...r, on: true, threshold_minor: 50_000 } : r)) });
    screenOf();
    fireEvent.click(await screen.findByRole('checkbox', { name: /Sale voids/ }));
    fireEvent.change(screen.getByLabelText(/Sale voids from this amount/), { target: { value: '50000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Review and save' }));
    const review = screen.getByRole('group', { name: 'Check before you save' });
    expect(review.textContent).toContain('Sale voids: a second person from UGX 50,000 (was no second person).');
    expect(review.textContent).not.toContain('will be told');
    fireEvent.click(within(review).getByRole('button', { name: 'Yes, save this' }));
    expect(save).not.toHaveBeenCalled();
    fireEvent.change(within(review).getByLabelText('Your own password'), { target: { value: 'Fabricated-Pass-2026' } });
    fireEvent.click(within(review).getByRole('button', { name: 'Yes, save this' }));
    await waitFor(() => expect(save).toHaveBeenCalledTimes(1));
    expect(save.mock.calls[0]).toEqual([4, [{ action_type: 'retail_sale_void', on: true, threshold_minor: 50_000 }], { password: 'Fabricated-Pass-2026' }]);
    expect((await screen.findByRole('status')).textContent).toContain('Saved.');
  });

  it('says the others will be told before a rule is switched off', async () => {
    get.mockResolvedValue(read);
    screenOf();
    fireEvent.click(await screen.findByRole('checkbox', { name: /Credit write-offs/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Review and save' }));
    const review = screen.getByRole('group', { name: 'Check before you save' });
    // With no amount the write-off keeps ADR-043's rule, and the screen says so rather than "every one".
    expect(review.textContent).toContain('Credit write-offs: no second person (was a second administrator when the business has two or more).');
    expect(review.textContent).toContain('The other administrators will be told');
  });

  it('shows a refused proof in plain words and keeps the review open', async () => {
    get.mockResolvedValue(read);
    save.mockRejectedValue(new Error('That password is not right.'));
    screenOf();
    fireEvent.click(await screen.findByRole('checkbox', { name: /Sale voids/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Review and save' }));
    fireEvent.change(screen.getByLabelText('Your own password'), { target: { value: 'wrong' } });
    fireEvent.click(screen.getByRole('button', { name: 'Yes, save this' }));
    expect((await screen.findByRole('alert')).textContent).toBe('That password is not right.');
    expect(screen.getByRole('group', { name: 'Check before you save' })).toBeTruthy();
  });

  it('asks for the authenticator code of an administrator who has one', async () => {
    get.mockResolvedValue(read);
    screenOf({ ...admin, permissions: [...(admin.permissions ?? []), 'core.settings.manage'], mfa_enabled: true });
    fireEvent.click(await screen.findByRole('checkbox', { name: /Sale voids/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Review and save' }));
    expect(screen.getByLabelText('The current code from your authenticator app')).toBeTruthy();
  });

  it('scrolls to the row the link names once the rules have loaded (the returns Hint, #575)', async () => {
    let answer: (v: ApprovalRules) => void = () => undefined;
    get.mockReturnValue(new Promise<ApprovalRules>((resolve) => { answer = resolve; }));
    const scrolled = vi.fn();
    const original = Element.prototype.scrollIntoView;
    Element.prototype.scrollIntoView = function (this: Element) { scrolled(this.id); } as never;
    window.location.hash = '#rule-retail_sale_void';
    try {
      screenOf();
      expect(scrolled).not.toHaveBeenCalled();
      answer(read);
      await waitFor(() => expect(scrolled).toHaveBeenCalledWith('rule-retail_sale_void'));
    } finally {
      Element.prototype.scrollIntoView = original;
      window.location.hash = '';
    }
  });

  it('shows no rules to someone who may not change the settings', () => {
    screenOf({ ...manager, mfa_enabled: false });
    expect(get).not.toHaveBeenCalled();
    expect(screen.queryByRole('checkbox')).toBeNull();
  });
});
