// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// The screen that makes an item with sizes and colours (issue #384): the sizes and prints typed once give the list of what will
// be made, each with its code and price the owner may change, and Save sends exactly that list. Fabricated items.

vi.setConfig({ testTimeout: 20000 });

const createFamily = vi.fn();
const joinFamily = vi.fn();
const romperFamily = () => Promise.resolve({ id: 'f9', name: 'Test Romper', unit_id: 'u1', variants: [{ id: 's1', variant: { family_id: 'f9', family_name: 'Test Romper', size: 'S', position: 0 } }] });
const getFamily = vi.fn<(...a: unknown[]) => Promise<unknown>>(romperFamily);

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve([{ id: 'c1', name: 'Baby wear', active: true, product_count: 0, version: 1 }]),
      listUnits: () => Promise.resolve([{ id: 'u1', name: 'piece', active: true, product_count: 0, version: 1 }]),
      createFamily: (...a: unknown[]) => createFamily(...a),
      joinFamily: (...a: unknown[]) => joinFamily(...a),
      listProducts: () => Promise.resolve([
        { id: 'p1', code: 'CAB-15', description: 'Test Cable thin', unit: 'piece', unit_id: 'u1', sell_minor: 2000 },
        { id: 's1', code: 'ROMP-S', description: 'Test Romper (S)', unit: 'piece', unit_id: 'u1', sell_minor: 9000, variant: { family_id: 'f9', family_name: 'Test Romper', size: 'S', position: 0 } },
      ]),
      getFamily: (...a: unknown[]) => getFamily(...a),
    },
  };
});

const { NewFamilyPage, JoinPage } = await import('./catalogue-variants');

afterEach(() => {
  cleanup();
  createFamily.mockReset();
  joinFamily.mockReset();
  getFamily.mockReset();
  getFamily.mockImplementation(romperFamily);
});

const admin = mockMe('admin');
const branch = admin.branches?.[0]?.id ?? '';
const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });

function screenOf(node: ReactElement) {
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <StaffContext.Provider value={{ me: admin, branch }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

describe('an item with sizes and colours', () => {
  it('asks the buying price before the selling price (#494)', async () => {
    screenOf(<NewFamilyPage />);
    const selling = await screen.findByLabelText(/^Selling price/);
    const buying = screen.getByLabelText(/^Buying price/);
    expect(buying.compareDocumentPosition(selling) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('lists every combination with its code and sends the list the owner checked', async () => {
    createFamily.mockResolvedValue({ id: 'f1', name: 'Test romper', variants: [] });
    screenOf(<NewFamilyPage />);
    fireEvent.change(screen.getByLabelText('What is it called?'), { target: { value: 'Test romper' } });
    fireEvent.change(screen.getByLabelText('Short code'), { target: { value: 'ROMP' } });
    await waitFor(() => expect(screen.getAllByRole('option').length).toBeGreaterThan(3));
    fireEvent.change(screen.getByLabelText('Category'), { target: { value: 'c1' } });
    fireEvent.change(screen.getByLabelText('Unit'), { target: { value: 'u1' } });
    fireEvent.change(screen.getByLabelText(/^Selling price/), { target: { value: '9000' } });
    fireEvent.change(screen.getByLabelText('Sizes'), { target: { value: '0-3m, 3-6m' } });
    fireEvent.change(screen.getByLabelText('Colours or prints'), { target: { value: 'Blue, Pink' } });
    expect(screen.getByText('4 to be made')).toBeTruthy();
    expect((screen.getByLabelText('Code of 3-6m, Pink') as HTMLInputElement).value).toBe('ROMP-36M-PINK');
    // A bigger size costs more: its price is changed in the list.
    fireEvent.change(screen.getByLabelText('Selling price of 3-6m, Pink'), { target: { value: '11000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Make these 4' }));
    await waitFor(() => expect(createFamily).toHaveBeenCalledTimes(1));
    const body = createFamily.mock.calls[0]?.[0] as { variants: { code: string; sell_minor: number }[] };
    expect(body.variants.map((v) => v.code)).toEqual(['ROMP-03M-BLUE', 'ROMP-03M-PINK', 'ROMP-36M-BLUE', 'ROMP-36M-PINK']);
    expect(body.variants[3]?.sell_minor).toBe(11000);
    expect(body.variants[0]?.sell_minor).toBe(9000);
  });

  it('says in plain words when two would share a code', () => {
    screenOf(<NewFamilyPage />);
    fireEvent.change(screen.getByLabelText('Short code'), { target: { value: 'ROMP' } });
    fireEvent.change(screen.getByLabelText('Sizes'), { target: { value: '0-3m, 03m' } });
    expect(screen.getByRole('note').textContent).toContain('Two of them would have the code ROMP-03M');
  });

  it('joins items into an item that exists, sending only the items and not a name', async () => {
    joinFamily.mockResolvedValue({ id: 'f9', name: 'Test Romper', variants: [] });
    screenOf(<JoinPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Join this item Test Romper (S)' }));
    expect(await screen.findByText('Test Romper', { selector: 'strong' })).toBeTruthy();
    expect(screen.queryByLabelText('What is the item called?')).toBeNull();
    fireEvent.click(await screen.findByRole('button', { name: 'Add as a size Test Cable thin' }));
    fireEvent.change(screen.getByLabelText('Size of Test Cable thin'), { target: { value: 'M' } });
    fireEvent.click(screen.getByRole('button', { name: 'Join them as one item' }));
    await waitFor(() => expect(joinFamily).toHaveBeenCalledTimes(1));
    expect(joinFamily.mock.calls[0]?.[0]).toBe('f9');
    expect(joinFamily.mock.calls[0]?.[1]).toEqual({ items: [{ product_id: 'p1', size: 'M' }] });
  });

  it('warns before sending a size the item has already', async () => {
    screenOf(<JoinPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Join this item Test Romper (S)' }));
    await screen.findByText('Test Romper', { selector: 'strong' });
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Add as a size Test Cable thin' }).length).toBe(1));
    fireEvent.click(screen.getByRole('button', { name: 'Add as a size Test Cable thin' }));
    fireEvent.change(screen.getByLabelText('Size of Test Cable thin'), { target: { value: 's' } });
    await waitFor(() => expect(screen.getByRole('note').textContent).toContain('Test Romper has one of these sizes and colours already'));
    expect((screen.getByRole('button', { name: 'Join them as one item' }) as HTMLButtonElement).disabled).toBe(true);
  });

  it('says so when the item picked to join has no sizes, instead of doing nothing', async () => {
    screenOf(<JoinPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Join this item Test Cable thin' }));
    expect(screen.getByRole('note').textContent).toContain('Test Cable thin has no sizes yet');
    expect(screen.getByLabelText('What is the item called?')).toBeTruthy();
  });

  it('waits for the item it joins before judging the units, and says when it could not be read', async () => {
    getFamily.mockImplementation(() => new Promise(() => {}));
    screenOf(<JoinPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Join this item Test Romper (S)' }));
    await screen.findByText('Test Romper', { selector: 'strong' });
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Add as a size Test Cable thin' }).length).toBe(1));
    fireEvent.click(screen.getByRole('button', { name: 'Add as a size Test Cable thin' }));
    fireEvent.change(screen.getByLabelText('Size of Test Cable thin'), { target: { value: 'M' } });
    expect(screen.getByRole('note').textContent).toContain('Reading Test Romper');
    expect(screen.getByRole('note').textContent).not.toContain('one unit');
    cleanup();

    getFamily.mockImplementation(() => Promise.reject(new Error('offline')));
    screenOf(<JoinPage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Join this item Test Romper (S)' }));
    await screen.findByText('Test Romper', { selector: 'strong' });
    await waitFor(() => expect(screen.getAllByRole('button', { name: 'Add as a size Test Cable thin' }).length).toBe(1));
    fireEvent.click(screen.getByRole('button', { name: 'Add as a size Test Cable thin' }));
    await waitFor(() => expect(screen.getByRole('note').textContent).toContain('Test Romper could not be read'));
    expect((screen.getByRole('button', { name: 'Join them as one item' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
