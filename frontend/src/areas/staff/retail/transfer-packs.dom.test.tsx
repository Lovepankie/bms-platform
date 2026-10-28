// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Move stock in cartons and pieces (issue #650): an item with a standard pack is moved in cartons and pieces, or pieces alone,
// the total in pieces shows before saving, what is sent is pieces, and more than the branch holds is said in cartons and
// pieces. An item with no pack keeps its one box. Fabricated names, codes and quantities.

vi.setConfig({ testTimeout: 20000 });

const createTransfer = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return { ...real, retail: { ...real.retail, createTransfer: (...a: unknown[]) => createTransfer(...a), entryNorms: () => Promise.resolve([]), listProducts: () => Promise.resolve([]) } };
});

const { TransferForm } = await import('./transfer');

afterEach(() => {
  cleanup();
  createTransfer.mockReset();
  sessionStorage.clear();
});

const me = mockMe('admin');
const from = me.branches?.[0]?.id ?? '';
const to = me.branches?.[1]?.id ?? '';
const soap: Product = {
  id: 'p1', code: 'TS-1', description: 'Test Soap', unit: 'Piece', sell_minor: 1000, active: true, qty: '130.000',
  packs: [
    { id: 'k1', product_id: 'p1', unit_id: 'u1', unit: 'Carton', factor: '24.000', active: true, purpose: 'both', version: 1, standard: true },
    { id: 'k2', product_id: 'p1', unit_id: 'u2', unit: 'Dozen', factor: '12.000', active: true, purpose: 'sell', version: 1 },
  ],
};
const lamp: Product = { id: 'p2', code: 'TL-1', description: 'Test Lamp', unit: 'piece', sell_minor: 5000, active: true, qty: '9.000' };

function open(lines: { product: Product; qty: string; packs?: string }[]) {
  const draft = { toBranchId: to, transferDate: '2026-10-23', note: '', lines };
  sessionStorage.setItem(`retail-draft:transfer:${me.user_id}:${from}`, JSON.stringify({ key: 'test-key-650', draft }));
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: from }}>
        <TransferForm fromBranchId={from} />
      </StaffContext.Provider>
    </QueryClientProvider>,
  );
}

describe('Move stock in cartons and pieces', () => {
  it('takes cartons and pieces, shows the total in pieces and sends pieces', async () => {
    createTransfer.mockReturnValue(new Promise(() => undefined));
    open([{ product: soap, qty: '' }, { product: lamp, qty: '2' }]);
    fireEvent.change(screen.getByRole('textbox', { name: 'Test Soap Cartons' }), { target: { value: '2' } });
    fireEvent.change(screen.getByRole('textbox', { name: 'Test Soap Pieces' }), { target: { value: '6' } });
    expect(document.getElementById('tq-0-total')?.textContent).toBe('Moving 2 Cartons and 6 Pieces (54 Pieces)');
    expect(screen.getByLabelText('Quantity (piece)')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Move stock' }));
    await waitFor(() => expect(createTransfer).toHaveBeenCalledTimes(1));
    expect(createTransfer.mock.calls[0]?.[0].lines).toEqual([{ product_id: 'p1', qty: '54.000' }, { product_id: 'p2', qty: '2.000' }]);
  });

  it('takes pieces alone, and says in cartons and pieces when the branch holds less', async () => {
    open([{ product: soap, qty: '24' }]);
    expect(document.getElementById('tq-0-total')?.textContent).toBe('Moving 1 Carton (24 Pieces)');
    fireEvent.change(screen.getByRole('textbox', { name: 'Test Soap Cartons' }), { target: { value: '6' } });
    expect(screen.getByRole('alert').textContent).toBe('Only 5 Cartons and 10 Pieces (130 Pieces) in stock at this branch.');
    // Both boxes say the quantity is refused, as the one box of an item with no pack does.
    expect(screen.getByRole('textbox', { name: 'Test Soap Cartons' }).getAttribute('aria-invalid')).toBe('true');
    expect(screen.getByRole('textbox', { name: 'Test Soap Pieces' }).getAttribute('aria-invalid')).toBe('true');
    fireEvent.click(screen.getByRole('button', { name: 'Move stock' }));
    expect(createTransfer).not.toHaveBeenCalled();
  });

  it('refuses part of a carton in plain words, once', async () => {
    open([{ product: soap, qty: '', packs: '1.5' }]);
    expect(screen.getAllByRole('alert').map((a) => a.textContent)).toEqual(['Type whole Cartons, and the rest in Pieces.']);
    fireEvent.click(screen.getByRole('button', { name: 'Move stock' }));
    expect(createTransfer).not.toHaveBeenCalled();
  });
});
