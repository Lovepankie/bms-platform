// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { MyReferrals } from '../../../api/referrals';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';
import { ReferralsPage } from './page';
import { moneyWords, showReferrals, whatsappShare } from './state';

// The Referrals page under Business set-up (issue #464, ADR-069), with the API replaced: the link with Copy and Share on WhatsApp,
// the businesses referred with their status and money, nothing for someone without the permission. Fabricated businesses only.

const OWNER = [...(mockMe('admin').permissions ?? []), 'core.referrals.read'];

const sample: MyReferrals = {
  code: 'TESTS7K',
  link: 'https://bms.example.test/sign-up?ref=TESTS7K',
  items: [
    { referred_name: 'Test Hardware Shop', joined_at: '2026-10-01T08:00:00Z', status: 'paying', totals: [{ currency: 'UGX', earned_minor: 1500, paid_minor: 1000, due_minor: 500 }] },
    { referred_name: 'Test Pharmacy', joined_at: '2026-10-20T08:00:00Z', status: 'applied', totals: [] },
  ],
  totals: [{ currency: 'UGX', earned_minor: 1500, paid_minor: 1000, due_minor: 500 }],
};

function shell(load: () => Promise<MyReferrals>, permissions: string[] = OWNER) {
  const me = { ...mockMe('admin'), permissions };
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/setup/referrals'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>
          <ReferralsPage load={load} />
        </StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe('the referral link', () => {
  it('shows the link and the code, copies the link, and shares it on WhatsApp with the link in the message', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    shell(() => Promise.resolve(sample));
    const field = (await screen.findByLabelText('Referral link')) as HTMLInputElement;
    expect(field.value).toBe('https://bms.example.test/sign-up?ref=TESTS7K');
    expect(field.readOnly).toBe(true);
    expect(screen.getByText('TESTS7K')).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('https://bms.example.test/sign-up?ref=TESTS7K'));
    expect(await screen.findByText('Copied. Paste it into a message.')).toBeTruthy();

    const share = screen.getByRole('link', { name: 'Share on WhatsApp' }) as HTMLAnchorElement;
    expect(share.href.startsWith('https://wa.me/?text=')).toBe(true);
    expect(decodeURIComponent(share.href)).toContain('https://bms.example.test/sign-up?ref=TESTS7K');
    expect(share.target).toBe('_blank');
    expect(share.rel).toContain('noopener');
  });

  it('selects the link when the phone will not copy it', async () => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: vi.fn().mockRejectedValue(new Error('denied')) }, configurable: true });
    shell(() => Promise.resolve(sample));
    await screen.findByLabelText('Referral link');
    fireEvent.click(screen.getByRole('button', { name: 'Copy' }));
    expect(await screen.findByText(/would not copy it/)).toBeTruthy();
  });
});

describe('the list', () => {
  it('lists each business referred with its status and money, and the totals', async () => {
    shell(() => Promise.resolve(sample));
    const list = await screen.findByRole('list', { name: 'Businesses you referred' });
    expect(list.textContent).toContain('Test Hardware Shop');
    expect(list.textContent).toContain('Paying');
    expect(list.textContent).toContain('Test Pharmacy');
    expect(list.textContent).toContain('Applied, not started yet');
    expect(list.textContent).toContain('Earned UGX 1,500; paid UGX 1,000; to be paid UGX 500');
    expect(screen.getByText(/still to be paid/).textContent).toContain('UGX 500');
  });

  it('says so when nobody signed up yet', async () => {
    shell(() => Promise.resolve({ ...sample, items: [], totals: [] }));
    expect(await screen.findByText('No business has signed up with your link yet.')).toBeTruthy();
  });

  it('shows the error in plain words', async () => {
    shell(() => Promise.reject(new Error('Your referrals could not be loaded. Try again.')));
    expect((await screen.findByRole('alert')).textContent).toContain('could not be loaded');
  });

  it('shows nothing and loads nothing for someone without the permission', async () => {
    const load = vi.fn(() => Promise.resolve(sample));
    shell(load, ['core.settings.read']);
    expect(await screen.findByText('Only the owner and the administrators of the business see its referrals.')).toBeTruthy();
    expect(screen.queryByLabelText('Referral link')).toBeNull();
    expect(load).not.toHaveBeenCalled();
  });
});

describe('the words', () => {
  it('needs the permission, writes money per currency and puts the link in the WhatsApp message', () => {
    expect(showReferrals({ permissions: ['core.referrals.read'] })).toBe(true);
    expect(showReferrals({ permissions: ['core.settings.manage'] })).toBe(false);
    expect(moneyWords([], 'earned_minor')).toBe('Nothing yet');
    expect(moneyWords([{ currency: 'UGX', earned_minor: 25000, paid_minor: 0, due_minor: 25000 }], 'due_minor')).toBe('UGX 25,000');
    expect(decodeURIComponent(whatsappShare('Test Shop', 'https://bms.example.test/sign-up?ref=TESTS7K'))).toContain('Test Shop');
  });
});
