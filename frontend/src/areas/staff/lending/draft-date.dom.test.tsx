// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { investments, type Investment } from '../../../api/investments';
import { lending, type Loan } from '../../../api/lending';
import { StaffContext } from '../context';
import { DisburseForm, RepaymentForm } from './actions';
import { FundForm } from './investment-actions';

// Issue #670: a money form's draft kept in the tab is restored as it was, but its date was the day it was started. Read back on a
// later business day, with its key never sent, the date starts again from today (a date after today becomes today) and the form
// says so; the rest and the Idempotency-Key stay. A key that was sent keeps the body it went with, so a retry posts the same
// thing once. Every name is invented.

const ID = '00000000-0000-4000-8000-0000000000a1';
const me = { user_id: '00000000-0000-4000-8000-0000000000f2', full_name: 'Test Officer 01', kind: 'staff', permissions: [] } as unknown as Me;
const loan: Loan = { id: ID, loan_no: 'LN-BR1-000001', status: 'approved', currency: 'UGX', approved_principal_minor: 1000000 };
const inv = { id: ID, principal_minor: 1000000, currency: 'UGX' } as unknown as Investment;
const NOTE = /started on an earlier day, so its date is now today/;

const FORMS: { name: string; kind: string; draftName: string; field: string; label: string; form: ReactElement }[] = [
  { name: 'repayment', kind: 'repayment', draftName: `lending-repay:${me.user_id}:${ID}`, field: 'valueDate', label: 'Date paid', form: <RepaymentForm loan={{ ...loan, status: 'active' }} onDone={() => undefined} /> },
  { name: 'disbursement', kind: 'disbursement', draftName: `lending-disburse:${me.user_id}:${ID}`, field: 'date', label: 'Disbursement date', form: <DisburseForm loan={loan} /> },
  { name: 'investment funding', kind: 'funding', draftName: `invest-fund:${me.user_id}:${ID}`, field: 'date', label: 'Date received', form: <FundForm inv={inv} /> },
];

function show(f: (typeof FORMS)[number], saved?: { draft: Record<string, string>; sent?: string }): HTMLInputElement {
  if (saved) sessionStorage.setItem(`retail-draft:${f.draftName}`, JSON.stringify({ key: 'test-key-670', ...saved }));
  render(
    <QueryClientProvider client={new QueryClient()}>
      <StaffContext.Provider value={{ me, branch: null }}>{f.form}</StaffContext.Provider>
    </QueryClientProvider>,
  );
  return screen.getByLabelText(f.label) as HTMLInputElement;
}

const kept = (f: (typeof FORMS)[number]) => JSON.parse(sessionStorage.getItem(`retail-draft:${f.draftName}`) ?? '{}') as { key?: string; draft?: Record<string, string>; sent?: string };
const draftOn = (f: (typeof FORMS)[number], date: string, startedOn?: string) => ({
  method: 'cash', reference: 'TEST-REF-01', [f.field]: date, ...(f.name === 'repayment' ? { amount: '50000' } : {}), ...(startedOn ? { startedOn } : {}),
});

beforeEach(() => {
  sessionStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  // 08:00 in Kampala on 29 Oct.
  vi.setSystemTime(new Date('2026-10-29T05:00:00Z'));
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
  sessionStorage.clear();
});

describe.each(FORMS)('a $name draft restored from the tab', (f) => {
  it('opens fresh on today, with no note', () => {
    expect(show(f).value).toBe('2026-10-29');
    expect(screen.queryByText(NOTE)).toBeNull();
  });

  it('from an earlier day, starts its date again from today, says so, and keeps the rest and the key', () => {
    const date = show(f, { draft: draftOn(f, '2026-10-27', '2026-10-28') });
    expect(date.value).toBe('2026-10-29');
    expect(screen.getByText(NOTE)).toBeTruthy();
    expect(kept(f)).toEqual({ key: 'test-key-670', draft: draftOn(f, '2026-10-29', '2026-10-29') });
  });

  it('kept before #670, without startedOn, starts its date from today too', () => {
    expect(show(f, { draft: draftOn(f, '2026-10-20') }).value).toBe('2026-10-29');
  });

  it('with a date after today (a zone ahead of this one), clamps it to today', () => {
    expect(show(f, { draft: draftOn(f, '2026-10-30', '2026-10-29') }).value).toBe('2026-10-29');
    expect(screen.getByText(NOTE)).toBeTruthy();
  });

  it('keeps an earlier date chosen today, with no note', () => {
    expect(show(f, { draft: draftOn(f, '2026-10-20', '2026-10-29') }).value).toBe('2026-10-20');
    expect(screen.queryByText(NOTE)).toBeNull();
  });

  it('whose key was sent on an earlier day, keeps the body and key it went with and says it may be saved', () => {
    const date = show(f, { draft: draftOn(f, '2026-10-28', '2026-10-28'), sent: '2026-10-28' });
    expect(date.value).toBe('2026-10-28');
    expect(screen.queryByText(NOTE)).toBeNull();
    expect(screen.getByText(`This ${f.kind} was sent on 28 Oct 2026 and may already be saved. Send it again to check.`)).toBeTruthy();
    expect(kept(f)).toEqual({ key: 'test-key-670', draft: draftOn(f, '2026-10-28', '2026-10-28'), sent: '2026-10-28' });
  });

  it('whose key was sent today, keeps its body with no line', () => {
    expect(show(f, { draft: draftOn(f, '2026-10-20', '2026-10-28'), sent: '2026-10-29' }).value).toBe('2026-10-20');
    expect(screen.queryByText(/may already be saved/)).toBeNull();
    expect(screen.queryByText(NOTE)).toBeNull();
  });
});

describe('sending a money form', () => {
  it('marks its key as sent in the tab before the answer comes, so a later day leaves the date alone', () => {
    const repay = vi.spyOn(lending, 'repay').mockImplementation(() => new Promise(() => undefined));
    const f = FORMS[0]!;
    show(f, { draft: draftOn(f, '2026-10-29', '2026-10-29') });
    fireEvent.submit(screen.getByRole('form', { name: 'Record a repayment' }));
    return vi.waitFor(() => {
      expect(repay).toHaveBeenCalledWith(ID, expect.objectContaining({ value_date: '2026-10-29' }), 'test-key-670');
      expect(kept(f).sent).toBe('2026-10-29');
    });
  });

  it('does the same for a funding', () => {
    vi.spyOn(investments, 'fund').mockImplementation(() => new Promise(() => undefined));
    const f = FORMS[2]!;
    show(f, { draft: draftOn(f, '2026-10-29', '2026-10-29') });
    fireEvent.submit(screen.getByRole('form', { name: 'Record funding' }));
    return vi.waitFor(() => expect(kept(f).sent).toBe('2026-10-29'));
  });
});
