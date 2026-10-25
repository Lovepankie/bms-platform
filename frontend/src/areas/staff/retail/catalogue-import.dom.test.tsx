// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Owner decision D45 (issue #562): Let the app make the codes is unticked by default, the check and the add send the tick,
// and a file already checked is checked again when the tick changes. Fabricated rows.

const importProducts = vi.fn();

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, importProducts: (...a: unknown[]) => importProducts(...a) } };
});

const { ImportPage } = await import('./catalogue-import');

afterEach(() => {
  cleanup();
  importProducts.mockReset();
});

const admin = { ...mockMe('admin'), permissions: [...(mockMe('admin').permissions ?? []), 'core.settings.manage'] };
const csv = 'code,description,category,unit,sell price\nOLD-7,Test bulb 12W,Lighting,piece,2000\n';
const result = (made: boolean) => ({
  dry_run: true, rows_read: 1, added: 1, skipped: 0, errors: 0, categories_created: [], units_created: [],
  rows: [{ line: 2, code: made ? '' : 'OLD-7', description: 'Test bulb 12W', outcome: 'added', message: made ? 'Would be added. The code OLD-7 in the file is not used.' : 'Would be added.' }],
});

describe('Let the app make the codes', () => {
  it('is unticked by default, and a checked file is checked again when it changes', async () => {
    importProducts.mockImplementation((_text: string, _dry: boolean, made: boolean) => Promise.resolve(result(made)));
    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me: admin, branch: admin.branches?.[0]?.id ?? '' }}><ImportPage /></StaffContext.Provider>
      </QueryClientProvider>,
    );
    const tick = screen.getByLabelText('Let the app make the codes') as HTMLInputElement;
    expect(tick.checked).toBe(false);
    fireEvent.change(screen.getByLabelText('Or paste the rows here'), { target: { value: csv } });
    fireEvent.click(screen.getByRole('button', { name: 'Check the file' }));
    await waitFor(() => expect(importProducts).toHaveBeenCalledWith(csv, true, false));
    expect(await screen.findByText('Would be added.')).toBeTruthy();
    fireEvent.click(tick);
    await waitFor(() => expect(importProducts).toHaveBeenLastCalledWith(csv, true, true));
    expect(await screen.findByText('Would be added. The code OLD-7 in the file is not used.')).toBeTruthy();
    fireEvent.click(await screen.findByRole('button', { name: 'Add 1 items' }));
    await waitFor(() => expect(importProducts).toHaveBeenLastCalledWith(csv, false, true));
  });
});
