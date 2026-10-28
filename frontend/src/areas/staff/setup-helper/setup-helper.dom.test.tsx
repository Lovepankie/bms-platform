// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { ExistingLoanImportResult, ExistingLoansApi } from '../../../api/existing-loans';
import { mockMe } from '../../../api/retail-mock';
import type { ItemDraft, LoanDraft, SetupHelperApi } from '../../../api/setup-helper';
import { StaffContext } from '../context';

// The setup helper on the two import screens (issue #626, ADR-102): the box shows only while the helper is on; the app's rows
// can be changed and removed and become the import's file; Check runs the import's own check and nothing is added until the
// owner taps Add or Record; a failed read says so and the owner types it or imports a file. The provider is never called: the
// helper's API is a fake. Every name and number is fabricated.

vi.setConfig({ testTimeout: 20000 });

const importProducts = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, importProducts: (...a: unknown[]) => importProducts(...a) } };
});

const { ImportPage } = await import('../retail/catalogue-import');
const { ExistingLoansImport } = await import('../lending/existing-loans-import');

afterEach(() => {
  cleanup();
  importProducts.mockReset();
});

const admin = {
  ...mockMe('admin'),
  modules: [...(mockMe('admin').modules ?? []), 'lending'],
  permissions: [...(mockMe('admin').permissions ?? []), 'core.settings.manage', 'lending.loans.bring_in'],
};

function draw(node: ReactNode, branch: string | null = admin.branches?.[0]?.id ?? '') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: admin, branch } as never}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

function helper(overrides: Partial<SetupHelperApi> = {}): SetupHelperApi {
  return {
    status: () => Promise.resolve({ enabled: true, photos: true }),
    draftItems: vi.fn(),
    draftLoans: vi.fn(),
    ...overrides,
  } as SetupHelperApi;
}

const twoItems: ItemDraft = {
  reason: null,
  currency_exponent: 0,
  rows: [
    { description: 'Test Nails 3 inch', category: 'Test Fasteners', unit: 'Kg', sell_price_minor: 7500, cost_price_minor: null, pack_unit: null, pack_factor: null },
    { description: 'Test Padlock', category: 'Test Locks', unit: 'Piece', sell_price_minor: 25000, cost_price_minor: null, pack_unit: null, pack_factor: null },
  ],
};

const checked = (added: number, dryRun = true) => ({
  dry_run: dryRun, rows_read: added, added, skipped: 0, errors: 0, categories_created: [], units_created: [],
  rows: [{ line: 2, code: '', description: 'Test Padlock', outcome: 'added', message: 'Would be added.' }],
});

describe('the setup helper on Import items', () => {
  it('is not drawn while the helper is off', async () => {
    draw(<ImportPage helperApi={helper({ status: () => Promise.resolve({ enabled: false, photos: false }) })} />);
    expect(await screen.findByLabelText('Or paste the rows here')).toBeTruthy();
    expect(screen.queryByText('Let the app read your list')).toBeNull();
  });

  it('reads a typed list into rows that are checked by the import, and adds nothing until Add', async () => {
    const draftItems = vi.fn().mockResolvedValue(twoItems);
    importProducts.mockImplementation((_csv: string, dry: boolean) => Promise.resolve(checked(1, dry)));
    draw(<ImportPage helperApi={helper({ draftItems })} shop="Test Hardware" />);
    fireEvent.change(await screen.findByLabelText('Your items, one on each line'), { target: { value: 'nails 7500 a kg\npadlock 25000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Read my list' }));
    await waitFor(() => expect(draftItems).toHaveBeenCalledWith({ text: 'nails 7500 a kg\npadlock 25000' }, 'Test Hardware'));
    expect(await screen.findByText('2 items from your list')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Remove Test Nails 3 inch' }));
    fireEvent.change(screen.getByLabelText('Selling price'), { target: { value: '26000' } });
    const file = screen.getByLabelText('Or paste the rows here') as HTMLTextAreaElement;
    expect(file.value).toContain('Test Padlock,Test Locks,Piece');
    expect(file.value).toContain('26000');
    expect(file.value).not.toContain('Nails');
    expect(importProducts).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Check these items' }));
    await waitFor(() => expect(importProducts).toHaveBeenCalledWith(file.value, true, false));
    expect(importProducts).toHaveBeenCalledTimes(1);
    fireEvent.click(await screen.findByRole('button', { name: 'Add 1 items' }));
    await waitFor(() => expect(importProducts).toHaveBeenLastCalledWith(expect.stringContaining('Test Padlock'), false, false));
  });

  it('says plainly when the list could not be read, and the owner types it or imports a file', async () => {
    const draftItems = vi.fn().mockResolvedValueOnce({ reason: 'all_failed', rows: [], currency_exponent: 0 }).mockRejectedValueOnce(new Error('The file cannot be read.'));
    draw(<ImportPage helperApi={helper({ draftItems })} />);
    fireEvent.change(await screen.findByLabelText('Your items, one on each line'), { target: { value: 'nails' } });
    fireEvent.click(screen.getByRole('button', { name: 'Read my list' }));
    expect(await screen.findByText('The app could not read your list this time. Try again, or type your items below or import a file.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Read my list' }));
    expect(await screen.findByText('The file cannot be read.')).toBeTruthy();
    expect(screen.getByText('You can still type it below or import a file.')).toBeTruthy();
    expect(screen.queryByText(/from your list/)).toBeNull();
    expect(importProducts).not.toHaveBeenCalled();
  });

  it('shows a draft field\'s problem only once the field is left (docs/ui-rules.md rule 10)', async () => {
    const noPrice: ItemDraft = { ...twoItems, rows: [{ ...twoItems.rows[1]!, sell_price_minor: null }] };
    draw(<ImportPage helperApi={helper({ draftItems: vi.fn().mockResolvedValue(noPrice) })} />);
    fireEvent.change(await screen.findByLabelText('Your items, one on each line'), { target: { value: 'padlock' } });
    fireEvent.click(screen.getByRole('button', { name: 'Read my list' }));
    const price = (await screen.findByLabelText('Selling price')) as HTMLInputElement;
    expect(price.value).toBe('');
    expect(screen.queryByText('Add the selling price.')).toBeNull();
    fireEvent.blur(price);
    expect(await screen.findByText('Add the selling price.')).toBeTruthy();
  });

  it('offers a photo only when a provider reads pictures', async () => {
    draw(<ImportPage helperApi={helper({ status: () => Promise.resolve({ enabled: true, photos: false }) })} />);
    expect(await screen.findByRole('radio', { name: 'File' })).toBeTruthy();
    expect(screen.queryByRole('radio', { name: 'Photo' })).toBeNull();
  });
});

const register: LoanDraft = {
  reason: null,
  currency_exponent: 0,
  loans: [
    {
      ref: 'Y-01', member_no: null, member_full_name: null, member_name: 'Test Member New', member_phone: '+256700000202',
      principal_minor: 400000, disbursement_date: '2026-08-01', term_months: 2, agreed_total_minor: 480000, rate_percent: '10', total_worked_out: true,
      payments: [{ date: '2026-09-01', amount_minor: 240000 }],
    },
  ],
};

function loansApi(importCsv: ExistingLoansApi['importCsv']): ExistingLoansApi {
  return {
    products: () => Promise.resolve([{ id: 'p1', code: 'LOAN1', name: 'Test Monthly', status: 'active' }]),
    importCsv,
  } as unknown as ExistingLoansApi;
}

describe('the setup helper on Import existing loans', () => {
  it('sends no branch while none is in use, so the server asks for one', async () => {
    const result: ExistingLoanImportResult = { dry_run: true, rows_read: 1, loans: 1, repayments: 0, added: 0, skipped: 0, errors: 1, members_added: 0, rows: [] };
    const importCsv = vi.fn().mockResolvedValue(result);
    draw(<ExistingLoansImport api={loansApi(importCsv)} helperApi={helper({ draftLoans: vi.fn().mockResolvedValue(register) })} />, null);
    fireEvent.change(await screen.findByLabelText('The loans, one on each line'), { target: { value: 'Y-01 Test Member New 400000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Read my list' }));
    expect(await screen.findByText('New members join the branch in use; choose one at the top.')).toBeTruthy();
    fireEvent.change(await screen.findByLabelText(/Loan product for these loans/), { target: { value: 'LOAN1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Check these loans' }));
    await waitFor(() => expect(importCsv).toHaveBeenCalledTimes(1));
    expect(importCsv.mock.calls[0]?.[2]).toBeUndefined();
  });

  it('asks the owner to confirm a member found by phone under another name before the check sends anything', async () => {
    const result: ExistingLoanImportResult = { dry_run: true, rows_read: 1, loans: 1, repayments: 0, added: 1, skipped: 0, errors: 0, members_added: 0, rows: [] };
    const importCsv = vi.fn().mockResolvedValue(result);
    const known = { ...register, loans: [{ ...register.loans[0]!, member_no: 'M000001', member_full_name: 'Test Member Known', member_name: 'T. Other' }] };
    draw(<ExistingLoansImport api={loansApi(importCsv)} helperApi={helper({ draftLoans: vi.fn().mockResolvedValue(known) })} />);
    fireEvent.change(await screen.findByLabelText('The loans, one on each line'), { target: { value: 'Y-01 T. Other 400000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Read my list' }));
    expect(await screen.findByText('Member M000001, Test Member Known, has this phone already. The register says T. Other. If this is the same person, tick This is the same person; if not, remove this loan.')).toBeTruthy();
    fireEvent.change(await screen.findByLabelText(/Loan product for these loans/), { target: { value: 'LOAN1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Check these loans' }));
    const same = screen.getByLabelText('This is the same person') as HTMLInputElement;
    expect(same.checked).toBe(false);
    expect(importCsv).not.toHaveBeenCalled();
    fireEvent.click(same);
    fireEvent.click(screen.getByRole('button', { name: 'Check these loans' }));
    await waitFor(() => expect(importCsv).toHaveBeenCalledTimes(1));
    expect(importCsv.mock.calls[0]?.[0]).toContain('loan,Y-01,,T. Other,+256700000202,LOAN1');
  });

  it('reads a register into loans with a new member by name and phone, checked by the import in the branch in use', async () => {
    const result: ExistingLoanImportResult = { dry_run: true, rows_read: 2, loans: 1, repayments: 1, added: 1, skipped: 0, errors: 0, members_added: 1, rows: [] };
    const importCsv = vi.fn().mockResolvedValue(result);
    const draftLoans = vi.fn().mockResolvedValue(register);
    draw(<ExistingLoansImport api={loansApi(importCsv)} helperApi={helper({ draftLoans })} />);
    fireEvent.change(await screen.findByLabelText('The loans, one on each line'), { target: { value: 'Y-01 Test Member New 400000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Read my list' }));
    expect(await screen.findByText('1 loan from your register')).toBeTruthy();
    expect(screen.getByText('Worked out from 10% a month for 2 months. Check it against the register.')).toBeTruthy();
    fireEvent.change(await screen.findByLabelText(/Loan product for these loans/), { target: { value: 'LOAN1' } });
    expect(importCsv).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Check these loans' }));
    await waitFor(() => expect(importCsv).toHaveBeenCalledTimes(1));
    const [csv, dryRun, branch] = importCsv.mock.calls[0] as [string, boolean, string];
    expect(dryRun).toBe(true);
    expect(branch).toBe(admin.branches?.[0]?.id);
    expect(csv).toContain('loan,Y-01,,Test Member New,+256700000202,LOAN1,400000,2026-08-01,2,480000');
    expect(csv).toContain('repayment,Y-01,,,,,,,,,,2026-09-01,240000,cash,');
    expect(await screen.findByText('1 new members would come in with their loans.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Record 1 loans and send them for approval' })).toBeTruthy();
  });
});
