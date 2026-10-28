// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { AllBranchesStock, StockRow } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Stock shown in packs and pieces (issue #650): an item with a standard pack reads in cartons and pieces with the total in
// pieces, part of a carton and a shortage read plainly, and an item with no pack reads as it always did. Fabricated names.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const { StockTable, AllBranchesList } = await import('./stock');

afterEach(cleanup);

const admin = mockMe('admin');
const branch = admin.branches?.[0]?.id ?? '';
const carton = { unit: 'Carton', factor: '24.000' };
const rows: StockRow[] = [
  { product_id: 'p1', code: 'TS-1', description: 'Test soap', category: 'Soap', unit: 'Piece', qty: '127.000', negative: false, standard_pack: carton },
  { product_id: 'p2', code: 'TS-2', description: 'Test biscuits', category: 'Food', unit: 'Piece', qty: '7.000', negative: false, standard_pack: carton },
  { product_id: 'p3', code: 'TS-3', description: 'Test juice', category: 'Food', unit: 'Piece', qty: '-27.000', negative: true, standard_pack: carton },
  { product_id: 'p4', code: 'TS-4', description: 'Test cable', category: 'Cables', unit: 'Metre', qty: '40.000', negative: false },
];

function screenOf(node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: admin, branch }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const rowOf = (name: string) => screen.getByText(name).closest('tr') as HTMLElement;

describe('Stock in packs and pieces', () => {
  it('shows cartons and pieces with the total, part of a carton, a shortage and an item with no pack', () => {
    screenOf(<StockTable rows={rows} showCost={false} />);
    expect(rowOf('Test soap').textContent).toContain('5 Cartons and 7 Pieces (127 Pieces)');
    expect(rowOf('Test biscuits').textContent).toContain('7 Pieces');
    expect(rowOf('Test biscuits').textContent).not.toContain('Carton');
    expect(rowOf('Test juice').textContent).toContain('1 Carton and 3 Pieces short (27 Pieces short)');
    expect(rowOf('Test juice').textContent).toContain('Negative');
    expect(rowOf('Test cable').textContent).toContain('40 Metre');
  });

  it('shows the total and each branch in packs and pieces on a phone', () => {
    const data: AllBranchesStock = {
      branches: [{ id: 'b1', code: 'HQ', name: 'Test Main' }, { id: 'b2', code: 'B2', name: 'Test Two' }],
      items: [{ product_id: 'p1', code: 'TS-1', description: 'Test soap', unit: 'Piece', total_qty: '127.000', negative: false, standard_pack: carton,
        balances: [{ branch_id: 'b1', qty: '100.000', negative: false }, { branch_id: 'b2', qty: '27.000', negative: false }] }],
    };
    screenOf(<AllBranchesList data={data} showCost={false} />);
    expect(screen.getByText(/Total/).textContent).toContain('5 Cartons and 7 Pieces (127 Pieces)');
    fireEvent.click(screen.getByRole('button', { name: 'Show branches' }));
    const branches = [...document.querySelectorAll('.rt-card .rt-row')].slice(1).map((r) => r.textContent);
    expect(branches).toEqual(['Test Main (HQ)4 Cartons and 4 Pieces (100 Pieces)', 'Test Two (B2)1 Carton and 3 Pieces (27 Pieces)']);
  });
});
