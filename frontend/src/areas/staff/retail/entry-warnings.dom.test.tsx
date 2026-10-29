// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';
import { businessToday, retail, type RetailApi } from '../../../api/retail';
import { createMockRetail, mockEntryConfirmations, mockMe, setMockEntryNorms } from '../../../api/retail-mock';
import { offendingWords } from '../../../app/glossary';
import { pickItem } from '../../../test-utils/item-picker';
import { StaffContext } from '../context';
import { RestockForm } from './restock';
import { SaleForm } from './sale';
import { StocktakePage } from './stocktake';
import { TransferForm } from './transfer';

// The entry warnings on each screen (issue #353, ADR-093): a line far from the item's own history shows one plain warning next
// to its field when saved, with Confirm and Change. Confirm records it and saves; Change takes the warning away, puts the
// cursor in the field and saves nothing; the save button is never disabled by a warning; a usual line saves at once. The retail
// adapter is the fabricated mock, given a fabricated history for one item.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const BRANCH = '00000000-0000-4000-8000-0000000000a1';
const OTHER = '00000000-0000-4000-8000-0000000000a2';
const LAMP = '00000000-0000-4000-8000-0000000b0003';
const NAME = 'LED bulb 9W screw';

/** The lamp: bought about 10 at a time, sold one at a time at 6,000, moved about 100 at a time. 10 are on the shelf here. */
const HISTORY = {
  buy: { lines_read: 6, median_qty: '10', usual_unit: 'piece' },
  sell: { lines_read: 20, median_qty: '1', usual_unit: 'piece' },
  move: { lines_read: 4, median_qty: '100', usual_unit: 'piece' },
  last_sell_minor: 6000,
};

let normsSpy: MockInstance<RetailApi['entryNorms']>;
beforeEach(() => {
  Object.assign(retail, createMockRetail());
  setMockEntryNorms(LAMP, HISTORY);
  mockEntryConfirmations.length = 0;
  normsSpy = vi.spyOn(retail, 'entryNorms');
});

let client: QueryClient;

/**
 * Waits until the screen holds the item's history: a save before that is not warned about, by design. React Query tells its
 * observers on a timer of its own after the answer, so the wait is for the screen's observer to have the data.
 */
async function normsRead() {
  await waitFor(() => expect(normsSpy).toHaveBeenCalled());
  await waitFor(() => {
    const query = client.getQueryCache().find({ queryKey: ['retail', 'entry-norms', LAMP] });
    expect(query?.state.status).toBe('success');
    expect(query?.observers.every((o) => o.getCurrentResult().data !== undefined)).toBe(true);
  });
  await act(async () => {
    await new Promise((r) => setTimeout(r, 0));
  });
}
afterEach(() => {
  cleanup();
  sessionStorage.clear();
  localStorage.clear();
  setMockEntryNorms(LAMP, null);
  vi.restoreAllMocks();
});

function open(node: ReactElement) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me: mockMe('admin'), branch: BRANCH }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

/** The save button holds itself for two ticks after a press (BusyButton's double press guard); a person is slower than that. */
const settle = () => act(async () => { await new Promise((r) => setTimeout(r, 10)); });
const type = (id: string, value: string) => fireEvent.change(document.getElementById(id) as HTMLElement, { target: { value } });
const warning = (kind = 'quantity') => screen.queryByTestId(`entry-warning-${kind}`);
const press = (name: RegExp | string) => fireEvent.click(screen.getByRole('button', { name }));

/** The warning is a tinted note in plain words, with two 48px-class buttons, and the save button stays usable. */
function looksRight(save: HTMLElement) {
  const note = warning();
  expect(note?.className).toBe('alert alert-warning entry-warning');
  expect(note?.getAttribute('role')).toBe('alert');
  expect(offendingWords(note?.textContent ?? '')).toEqual([]);
  expect(note?.textContent).not.toMatch(/[\u2013\u2014]/);
  expect(save.hasAttribute('disabled')).toBe(false);
}

describe('Buy stock', () => {
  it('warns about 500 when about 10 are bought; Change keeps the form, Confirm records it and saves', async () => {
    const saved = vi.fn();
    const create = vi.spyOn(retail, 'createPurchase');
    open(<RestockForm onSaved={saved} />);
    await pickItem(NAME);
    type(`q-0-${BRANCH}`, '500');
    const save = screen.getByRole('button', { name: 'Save the purchase' });
    await normsRead();
    fireEvent.click(save);
    await screen.findByTestId('entry-warning-quantity');
    expect(warning()?.textContent).toContain('You usually buy about 10 pieces of this at a time; you entered 500 pieces. Is that right?');
    looksRight(save);
    expect(create).not.toHaveBeenCalled();

    press('Change');
    expect(warning()).toBeNull();
    expect(document.activeElement?.id).toBe(`q-0-${BRANCH}`);
    expect((document.getElementById(`q-0-${BRANCH}`) as HTMLInputElement).value).toBe('500');
    expect(create).not.toHaveBeenCalled();
    expect(saved).not.toHaveBeenCalled();

    await settle();
    fireEvent.click(save);
    await screen.findByTestId('entry-warning-quantity');
    press('Confirm');
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(create).toHaveBeenCalledTimes(1);
    expect(mockEntryConfirmations).toEqual([expect.objectContaining({ product_id: LAMP, screen: 'buy', kind: 'quantity', branch_id: BRANCH, entered_qty: '500', median_qty: '10' })]);
  });

  it('saves a usual quantity at once, with no warning', async () => {
    const saved = vi.fn();
    open(<RestockForm onSaved={saved} />);
    await pickItem(NAME);
    type(`q-0-${BRANCH}`, '12');
    await normsRead();
    fireEvent.click(screen.getByRole('button', { name: 'Save the purchase' }));
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(warning()).toBeNull();
    expect(mockEntryConfirmations).toEqual([]);
  });

  it('saves at once when the item has no history', async () => {
    setMockEntryNorms(LAMP, null);
    const saved = vi.fn();
    open(<RestockForm onSaved={saved} />);
    await pickItem(NAME);
    type(`q-0-${BRANCH}`, '5000');
    await normsRead();
    fireEvent.click(screen.getByRole('button', { name: 'Save the purchase' }));
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(warning()).toBeNull();
  });
});

describe('Sell', () => {
  it('warns about a quantity and a typed price; Change keeps the sale, each Confirm is recorded and the sale saves after the last', async () => {
    const saved = vi.fn();
    const create = vi.spyOn(retail, 'createSale');
    open(<SaleForm branchId={BRANCH} onSaved={saved} />);
    await pickItem(NAME);
    type('qty-0', '10');
    type('price-0', '60000');
    const save = screen.getByRole('button', { name: /Record sale/ });
    await normsRead();
    fireEvent.click(save);
    await screen.findByTestId('entry-warning-price');
    expect(warning()?.textContent).toContain('You usually sell about 1 piece of this at a time; you entered 10 pieces. Is that right?');
    expect(warning('price')?.textContent).toContain('You last sold this at UGX 6,000 a piece; you entered UGX 60,000. Is that right?');
    looksRight(save);

    // Change on the price: the cursor goes to it, the typed price stays, nothing is sent.
    fireEvent.click(warning('price')?.querySelector('button:last-child') as HTMLElement);
    expect(document.activeElement?.id).toBe('price-0');
    expect((document.getElementById('price-0') as HTMLInputElement).value).toBe('60000');
    expect(create).not.toHaveBeenCalled();

    type('price-0', '6500');
    await settle();
    fireEvent.click(save);
    await screen.findByTestId('entry-warning-quantity');
    expect(warning('price')).toBeNull();
    press('Confirm');
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(create).toHaveBeenCalledTimes(1);
    expect(mockEntryConfirmations.map((c) => `${c.screen} ${c.kind}`)).toEqual(['sell quantity']);
  });
});

describe('Confirm checks the screen as it is now (review of #590)', () => {
  it('clears the warnings when a line is edited, then records the sale the screen shows', async () => {
    const saved = vi.fn();
    const create = vi.spyOn(retail, 'createSale');
    open(<SaleForm branchId={BRANCH} onSaved={saved} />);
    await pickItem(NAME);
    type('qty-0', '10');
    type('price-0', '60000');
    const save = screen.getByRole('button', { name: /Record sale/ });
    await normsRead();
    fireEvent.click(save);
    await screen.findByTestId('entry-warning-quantity');
    expect(warning('price')).toBeTruthy();
    // The seller edits the quantity without Change: every warning and the waiting save go.
    type('qty-0', '1');
    expect(warning()).toBeNull();
    expect(warning('price')).toBeNull();
    await settle();
    expect(create).not.toHaveBeenCalled();
    // Save asks again about the sale as it is now: only the price.
    fireEvent.click(save);
    await screen.findByTestId('entry-warning-price');
    expect(warning()).toBeNull();
    press('Confirm');
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(create).toHaveBeenCalledTimes(1);
    expect(create.mock.calls[0]?.[0].lines?.[0]).toMatchObject({ qty: expect.stringMatching(/^1(\.000)?$/) });
    await waitFor(() => expect(mockEntryConfirmations.map((c) => `${c.kind} ${c.entered_qty}`)).toEqual(['price 1']));
  });

  it('asks again about a quantity changed after the warning, rather than saving it unchecked', async () => {
    const saved = vi.fn();
    open(<RestockForm onSaved={saved} />);
    await pickItem(NAME);
    type(`q-0-${BRANCH}`, '500');
    await normsRead();
    fireEvent.click(screen.getByRole('button', { name: 'Save the purchase' }));
    await screen.findByTestId('entry-warning-quantity');
    type(`q-0-${BRANCH}`, '50000');
    expect(warning()).toBeNull();
    await settle();
    fireEvent.click(screen.getByRole('button', { name: 'Save the purchase' }));
    await screen.findByTestId('entry-warning-quantity');
    expect(warning()?.textContent).toContain('you entered 50,000 pieces');
    expect(saved).not.toHaveBeenCalled();
    press('Confirm');
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(mockEntryConfirmations.map((c) => c.entered_qty)).toEqual(['50000']);
  });

  it('records nothing when one warning is confirmed and the other changed, or the save is refused', async () => {
    const saved = vi.fn();
    open(<SaleForm branchId={BRANCH} onSaved={saved} />);
    await pickItem(NAME);
    type('qty-0', '10');
    type('price-0', '60000');
    await normsRead();
    fireEvent.click(screen.getByRole('button', { name: /Record sale/ }));
    await screen.findByTestId('entry-warning-price');
    fireEvent.click(warning()?.querySelector('button') as HTMLElement);
    fireEvent.click(warning('price')?.querySelector('button:last-child') as HTMLElement);
    await settle();
    expect(saved).not.toHaveBeenCalled();
    expect(mockEntryConfirmations).toEqual([]);

    // A price at or below cost is refused by the server: the confirmation of the quantity is not recorded.
    vi.spyOn(retail, 'createSale').mockRejectedValueOnce(new Error('refused'));
    type('price-0', '6000');
    // The edit forgot the earlier confirmation: the quantity is asked about again and confirmed for this save.
    fireEvent.click(screen.getByRole('button', { name: /Record sale/ }));
    await screen.findByTestId('entry-warning-quantity');
    press('Confirm');
    await waitFor(() => expect(retail.createSale).toHaveBeenCalled());
    await settle();
    expect(saved).not.toHaveBeenCalled();
    expect(mockEntryConfirmations).toEqual([]);
  });
});

describe('a confirmation answers one save (review of #590)', () => {
  it('asks again about the same item and quantity on the next sale, and records only what was confirmed', async () => {
    const saved = vi.fn();
    open(<SaleForm branchId={BRANCH} onSaved={saved} />);
    for (const round of [1, 2]) {
      await pickItem(NAME);
      type('qty-0', '10');
      await normsRead();
      await settle();
      fireEvent.click(screen.getByRole('button', { name: /Record sale/ }));
      await screen.findByTestId('entry-warning-quantity');
      press('Confirm');
      await waitFor(() => expect(saved).toHaveBeenCalledTimes(round));
      await waitFor(() => expect(mockEntryConfirmations).toHaveLength(round));
      // Stock for the second sale: the lamp's shelf of 10 was sold by the first.
      if (round === 1) await retail.createPurchase({ purchased_on: businessToday(), payment_method: 'cash', lines: [{ product_id: LAMP, cost_minor: 3500, sell_minor: 6000, qty_by_branch: [{ branch_id: BRANCH, qty: '10' }] }] }, 'k-restock-1');
    }
    expect(mockEntryConfirmations.map((c) => `${c.screen} ${c.kind} ${c.entered_qty}`)).toEqual(['sell quantity 10', 'sell quantity 10']);
  });
});

describe('a change to the form forgets what was confirmed (review of #590)', () => {
  it('asks again about a confirmed warning once the line was edited, even back to the same value', async () => {
    const create = vi.spyOn(retail, 'createSale');
    open(<SaleForm branchId={BRANCH} />);
    await pickItem(NAME);
    type('qty-0', '10');
    type('price-0', '60000');
    await normsRead();
    fireEvent.click(screen.getByRole('button', { name: /Record sale/ }));
    await screen.findByTestId('entry-warning-price');
    // The quantity is confirmed; the price is still open, so nothing is sent.
    fireEvent.click(warning()?.querySelector('button') as HTMLElement);
    expect(create).not.toHaveBeenCalled();
    // An edit and back: the confirmation is gone with the edit.
    type('qty-0', '9');
    type('qty-0', '10');
    await settle();
    fireEvent.click(screen.getByRole('button', { name: /Record sale/ }));
    await screen.findByTestId('entry-warning-quantity');
    expect(warning('price')).toBeTruthy();
    expect(create).not.toHaveBeenCalled();
    expect(mockEntryConfirmations).toEqual([]);
  });
});

describe('Move stock', () => {
  it('warns about 8 when about 100 are moved; Change keeps the move, Confirm records it and saves', async () => {
    const saved = vi.fn();
    open(<TransferForm fromBranchId={BRANCH} onSaved={saved} />);
    fireEvent.change(screen.getByLabelText('To branch'), { target: { value: OTHER } });
    await pickItem(NAME);
    type('tq-0', '8');
    const save = screen.getByRole('button', { name: 'Move stock' });
    await normsRead();
    fireEvent.click(save);
    await screen.findByTestId('entry-warning-quantity');
    expect(warning()?.textContent).toContain('You usually move about 100 pieces of this at a time; you entered 8 pieces. Is that right?');
    looksRight(save);
    press('Change');
    expect(document.activeElement?.id).toBe('tq-0');
    expect(saved).not.toHaveBeenCalled();
    await settle();
    fireEvent.click(save);
    await screen.findByTestId('entry-warning-quantity');
    press('Confirm');
    await waitFor(() => expect(saved).toHaveBeenCalledTimes(1));
    expect(mockEntryConfirmations.map((c) => `${c.screen} ${c.kind} ${c.entered_qty}`)).toEqual(['move quantity 8']);
  });
});

describe('Count stock', () => {
  it('warns when a count is ten times what the records hold; Change keeps the count, Confirm goes on to the review', async () => {
    const create = vi.spyOn(retail, 'createStocktake');
    open(<StocktakePage />);
    await pickItem(NAME);
    const box = `count-${LAMP}`;
    await waitFor(() => expect(document.getElementById(box)).toBeTruthy());
    // The records hold 10 here (the fabricated sheet); 1,000 counted.
    type(box, '1000');
    const review = screen.getByRole('button', { name: /Review the difference/ });
    fireEvent.click(review);
    // Other items are not counted: the screen asks first, as before.
    fireEvent.click(await screen.findByRole('button', { name: 'Review the difference anyway' }));
    await screen.findByTestId('entry-warning-quantity');
    expect(warning()?.textContent).toContain('The records show 10 pieces here; you counted 1,000 pieces. Is that right?');
    looksRight(review);
    press('Change');
    expect(document.activeElement?.id).toBe(box);
    expect(create).not.toHaveBeenCalled();

    await settle();
    fireEvent.click(review);
    fireEvent.click(await screen.findByRole('button', { name: 'Review the difference anyway' }));
    await screen.findByTestId('entry-warning-quantity');
    press('Confirm');
    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    // The review is not the save: nothing is recorded until the count is committed.
    await screen.findByRole('button', { name: 'Commit the count' });
    await settle();
    expect(mockEntryConfirmations).toEqual([]);
    fireEvent.click(screen.getByRole('button', { name: 'Commit the count' }));
    await waitFor(() => expect(mockEntryConfirmations).toHaveLength(1));
    expect(mockEntryConfirmations).toEqual([expect.objectContaining({ screen: 'count', kind: 'quantity', product_id: LAMP, entered_qty: '1000', median_qty: '10' })]);
  });
});
