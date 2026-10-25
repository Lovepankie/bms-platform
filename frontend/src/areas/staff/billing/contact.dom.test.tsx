// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BillingContactRequest, BillingView } from '../../../api/billing';

// Who gets the bills (issue #571, ADR-090, FR-SUB-13): the Owner sends reminders and receipts to someone else and back again; an
// administrator only reads who it is; the form checks the fields before sending. Fabricated people and numbers.

const saved: BillingContactRequest[] = [];
let cleared = 0;

const base: BillingView = { plan_name: 'Test Plan', currency: 'UGX', grace_days: 7, payments: [], bills_go_to: 'Test Owner 01', can_change_contact: true };

vi.mock('../../../api/billing', async (original) => ({
  ...(await original<typeof import('../../../api/billing')>()),
  setBillingContact: (body: BillingContactRequest) => {
    saved.push(body);
    return Promise.resolve({ ...base, billing_contact: { full_name: body.full_name, email: body.email, phone: body.phone }, bills_go_to: body.full_name });
  },
  clearBillingContact: () => {
    cleared += 1;
    return Promise.resolve(base);
  },
}));

const { BillingContactCard, goesToSentence, phoneProblem } = await import('./contact');

function show(view: BillingView) {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <BillingContactCard view={view} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  saved.length = 0;
  cleared = 0;
});

afterEach(() => cleanup());

describe('who gets the bills', () => {
  it('says the owner gets them while no one else is named', () => {
    expect(goesToSentence(base)).toBe('Reminders, invoices and receipts go to the owner, Test Owner 01.');
  });

  it('lets the Owner name an accountant, checking the fields first', async () => {
    show(base);
    fireEvent.click(screen.getByRole('button', { name: 'Send the bills to someone else' }));
    fireEvent.change(screen.getByLabelText(/Name/), { target: { value: 'Test Accountant 01' } });
    fireEvent.change(screen.getByLabelText(/Email/), { target: { value: 'not-an-email' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(saved).toHaveLength(0);
    fireEvent.change(screen.getByLabelText(/Email/), { target: { value: 'accountant01@example.test' } });
    fireEvent.change(screen.getByLabelText(/Mobile number/), { target: { value: '0700 000 009' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(saved).toEqual([{ full_name: 'Test Accountant 01', email: 'accountant01@example.test', phone: '0700 000 009' }]));
    expect(await screen.findByText('Saved. The next reminder and receipt go to this person.')).toBeTruthy();
  });

  it('lets the Owner send them back to themselves', async () => {
    show({ ...base, billing_contact: { full_name: 'Test Accountant 01', email: 'accountant01@example.test' }, bills_go_to: 'Test Accountant 01' });
    expect(screen.getByText('Reminders, invoices and receipts go to Test Accountant 01 (accountant01@example.test).')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Send them to me again' }));
    await waitFor(() => expect(cleared).toBe(1));
  });

  it('shows an administrator who it is and no button', () => {
    show({ ...base, can_change_contact: false });
    expect(screen.getByText('Only the owner of the business can change this.')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('says that sign-in does not change', () => {
    show(base);
    expect(screen.getByText(/Sign-in codes and password links still go to each person's own email or phone/)).toBeTruthy();
  });

  it('takes an empty phone or a Ugandan mobile number', () => {
    expect(phoneProblem('')).toBeNull();
    expect(phoneProblem('+256 700 000 009')).toBeNull();
    expect(phoneProblem('12345')).not.toBeNull();
  });
});
