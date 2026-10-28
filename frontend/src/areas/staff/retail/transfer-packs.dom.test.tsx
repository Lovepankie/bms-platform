// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Product } from '../../../api/retail';
import type { TransferLineDraft } from './transfer-state';
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

function open(lines: TransferLineDraft[]) {
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

// Review of #654: a move keeps the pack its cartons were typed in, so an item read again (a draft restored after the catalogue
// changed, a line merged with a fresher item) never reads the same cartons in another size or drops them.
describe('a pack that changes while moving', () => {
  const dozenNow: Product = { ...soap, packs: [{ ...(soap.packs ?? [])[1]!, standard: true }] };
  const noPack: Product = { ...soap, packs: [] };

  it.each([
    ['the carton is switched off and a dozen is the pack now', dozenNow],
    ['the item has no pack any more', noPack],
  ])('keeps 2 Cartons and 6 Pieces as 54 when %s', async (_, product) => {
    createTransfer.mockReturnValue(new Promise(() => undefined));
    open([{ product, qty: '6', packs: '2', pack: { unit: 'Carton', factor: '24.000' } }]);
    expect(document.getElementById('tq-0-total')?.textContent).toBe('Moving 2 Cartons and 6 Pieces (54 Pieces)');
    expect(screen.getByText("Moving in Cartons of 24, as when you typed them. This item's pack has changed since.")).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Move stock' }));
    await waitFor(() => expect(createTransfer).toHaveBeenCalledTimes(1));
    expect(createTransfer.mock.calls[0]?.[0].lines).toEqual([{ product_id: 'p1', qty: '54.000' }]);
  });

  it('keeps the pack of the first carton typed with the line', () => {
    open([{ product: soap, qty: '' }]);
    fireEvent.change(screen.getByRole('textbox', { name: 'Test Soap Cartons' }), { target: { value: '3' } });
    const saved = JSON.parse(sessionStorage.getItem(`retail-draft:transfer:${me.user_id}:${from}`) ?? '{}');
    expect(saved.draft.lines[0]).toMatchObject({ packs: '3', pack: { unit: 'Carton', factor: '24.000' } });
    expect(screen.queryByText(/pack has changed since/)).toBeNull();
  });

  it('moves in the pack the server marks, not a box whose usual pack is 50 metres (#660, the payload RetailUnitsIT asserts)', () => {
    const contract = JSON.parse(readFileSync('../fixtures/retail/standard-pack-660.json', 'utf8')) as {
      item_packs: { unit: string; factor: string; purpose: string; standard: boolean }[];
      stock_row: { unit: string; qty: string };
    };
    const pen: Product = {
      id: 'p3', code: 'TP-1', description: 'Test Pen', unit: contract.stock_row.unit, sell_minor: 100, active: true, qty: contract.stock_row.qty,
      packs: contract.item_packs.map((k, i) => ({ id: `k${i}`, product_id: 'p3', unit_id: `u${i}`, active: true, version: 1, ...k })),
    };
    open([{ product: pen, qty: '' }]);
    expect(screen.getByRole('textbox', { name: 'Test Pen cartons' })).toBeTruthy();
    expect(screen.queryByRole('textbox', { name: /box/i })).toBeNull();
  });

  it('says a number of cartons too large to hold is too many, not too few', () => {
    open([{ product: soap, qty: '', packs: '99999999999999' }]);
    expect(screen.getByRole('alert').textContent).toBe('That is more than BMS can move at once. Check the number.');
  });
});
