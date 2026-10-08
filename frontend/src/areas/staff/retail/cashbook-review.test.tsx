import { QueryClient } from '@tanstack/react-query';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { RetailError, businessToday, daysBefore, type Advance, type BankingExpected } from '../../../api/retail';
import { AdvanceDetailView } from './advances';
import { ExpectedFigures } from './banking';
import { alreadyBankedLabel } from './banking-state';
import { RecordsByShop } from './cash-ui';
import { savedFor, voidOffered } from './cash-state';
import { branch, otherBranch, page, pageAs, sessionOf } from './cashbook-test-utils';
import { PartyPicker } from './party-picker';
import { addableKinds } from './permissions';
import { afterSavingsConflict } from './savings-state';

const today = businessToday();
const noop = () => undefined;

describe('a saved panel belongs to its shop (review C1)', () => {
  it('shows the saved record only for the shop it was saved for', () => {
    const saved = { branchId: branch, value: { amount: 1 } };
    expect(savedFor(saved, branch)).toEqual({ amount: 1 });
    expect(savedFor(saved, otherBranch)).toBeNull();
    expect(savedFor(saved, null)).toBeNull();
    expect(savedFor(null, branch)).toBeNull();
  });

  it('keeps every cash book screen on the branch-scoped hook, with no unscoped saved state left', () => {
    for (const file of ['savings', 'banking', 'expenses', 'withdrawals', 'advances']) {
      const source = readFileSync(new URL(`./${file}.tsx`, import.meta.url), 'utf8');
      expect(source, file).toContain('useSavedFor');
      expect(source, file).not.toMatch(/useState<[^>]*\| null>\(null\)\s*;\s*\n\s*const \[round/);
    }
  });
});

describe('adding a party (review C2)', () => {
  const picker = (kinds: Parameters<typeof addableKinds>[1], role: 'sales' | 'admin') =>
    page(role, <PartyPicker id="p" label="Who" parties={[]} kinds={[...kinds] as never} value="" onChange={noop} noneLabel="None" addLabel="Add a new one" />);

  it('offers a shop user the add button for a beneficiary but not for an owner, staff or related party', () => {
    expect(picker(['other'], 'sales')).toContain('Add a new one');
    expect(picker(['owner', 'staff', 'related_entity'], 'sales')).not.toContain('Add a new one');
    expect(picker(['owner', 'staff', 'related_entity'], 'admin')).toContain('Add a new one');
  });

  it('keeps only the kinds the session may create', () => {
    expect(addableKinds(sessionOf('sales'), ['owner', 'supplier', 'other'])).toEqual(['supplier', 'other']);
    expect(addableKinds(sessionOf('admin'), ['owner', 'supplier'])).toEqual(['owner', 'supplier']);
  });
});

describe('savings refusals (review C3 and C6)', () => {
  const key = ['retail', 'savings-suggestion', branch, today];
  const seeded = () => {
    const client = new QueryClient();
    client.setQueryData(key, { branch_id: branch, business_date: today, suggestion_token: 'old', suggested_minor: 100 });
    return client;
  };

  it('on savings_exists refetches the suggestion so the form is blocked instead of retrying the same 409', () => {
    const client = seeded();
    const outcome = afterSavingsConflict(client, key, new RetailError('Savings exist', 409, 'savings_exists'));
    expect(outcome).toBe('refetched');
    expect(client.getQueryState(key)?.isInvalidated).toBe(true);
  });

  it('on suggestion_changed takes the new token and suggestion and asks the person to look again', () => {
    const client = seeded();
    const outcome = afterSavingsConflict(client, key, new RetailError('Changed', 409, 'suggestion_changed', { suggestion_token: 'new', suggested_minor: 250 }));
    expect(outcome).toBe('look_again');
    expect(client.getQueryData(key)).toMatchObject({ suggestion_token: 'new', suggested_minor: 250 });
    expect(client.getQueryState(key)?.isInvalidated).toBe(true);
  });

  it('leaves any other error to the form', () => {
    const client = seeded();
    expect(afterSavingsConflict(client, key, new RetailError('Nope', 422, 'reason_required'))).toBe('none');
    expect(afterSavingsConflict(client, key, new Error('offline'))).toBe('none');
    expect(client.getQueryState(key)?.isInvalidated).toBe(false);
  });

  it('wires the form error handler to that function', () => {
    const source = readFileSync(new URL('./savings.tsx', import.meta.url), 'utf8');
    expect(source).toContain('afterSavingsConflict(queryClient, suggestionKey, error)');
  });
});

describe('the banking label names the chosen day (review C4)', () => {
  it('says today for today and the day for any other', () => {
    expect(alreadyBankedLabel(today, today)).toBe('Already banked today');
    expect(alreadyBankedLabel(undefined, today)).toBe('Already banked today');
    expect(alreadyBankedLabel(daysBefore(today, 3), today)).toMatch(/^Already banked on /);
  });

  it('draws the chosen date in the figures', () => {
    const expected: BankingExpected = { branch_id: branch, business_date: daysBefore(today, 3), cash_expected_minor: 1000, banked_so_far_minor: 500 };
    const out = page('admin', <ExpectedFigures expected={expected} date={daysBefore(today, 3)} />);
    expect(out).toContain('Already banked on ');
    expect(out).not.toContain('Already banked today');
  });
});

describe('no Void on an imported record (review C5)', () => {
  it('offers the void only to a person who may, on a live record', () => {
    expect(voidOffered({ voided: false, historical: false }, true)).toBe(true);
    expect(voidOffered({ voided: false, historical: true }, true)).toBe(false);
    expect(voidOffered({ voided: true, historical: false }, true)).toBe(false);
    expect(voidOffered({ voided: false, historical: false }, false)).toBe(false);
  });

  const rows = [
    { id: 'live', branch_id: branch, business_date: today, voided: false, historical: false },
    { id: 'old', branch_id: branch, business_date: today, voided: false, historical: true },
  ];

  it('draws Void on the live row only in a record list', () => {
    const out = page('admin', <RecordsByShop rows={rows} empty="none" what="record" renderRow={(r) => <span>{r.id}</span>} voidWith={() => Promise.resolve()} />);
    expect((out.match(/>Void</g) ?? []).length).toBe(1);
  });

  it('draws no Void on an imported advance or its imported repayment', () => {
    const advance: Advance = { id: 'a1', advance_no: 'RA00000001', branch_id: branch, business_date: today, party_name: 'Test Owner 01', principal_minor: 5000, repaid_minor: 1000, balance_minor: 4000, voided: false, historical: true,
      repayments: [{ id: 'r1', advance_id: 'a1', amount_minor: 1000, method: 'cash', paid_on: today, voided: false, historical: true }] };
    const out = pageAs(sessionOf('admin'), <AdvanceDetailView advance={advance} />);
    expect(out).not.toContain('>Void<');
    expect(out).toContain('Imported');
  });
});
