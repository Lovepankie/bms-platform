// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { offendingWords } from '../../../app/glossary';
import { StaffContext } from '../context';
import { ProductForm } from './catalogue-products';
import { RestockForm } from './restock';

// While a new item's name is typed (issue #353, ADR-093): "Did you mean" lists the items it may already be, case and spacing
// ignored, and the category most items like it are in is offered as a button; nothing is chosen for the person and nothing is
// blocked. On Buy stock an existing item is used instead of a new one. The retail adapter is the fabricated mock.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const BRANCH = '00000000-0000-4000-8000-0000000000a1';
const LAMP = '00000000-0000-4000-8000-0000000b0003';

beforeEach(() => {
  Object.assign(retail, createMockRetail());
});
afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

function open(node: ReactElement, me = mockMe('admin')) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me, branch: BRANCH }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

const hints = () => screen.findByRole('note', { name: 'Did you mean' });
const listed = (note: HTMLElement) => within(note).getAllByRole('listitem').map((li) => li.querySelector('span')?.textContent);

describe('Add an item', () => {
  it('asks Did you mean while the name is typed, ignoring case and spacing, and opens the item tapped', async () => {
    const opened = vi.fn();
    open(<ProductForm onSaved={() => undefined} onCancel={() => undefined} onOpen={opened} />);
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'LEDBULB' } });
    const note = await hints();
    expect(listed(note)).toEqual(['LED bulb 9W screw (P003), Lighting, per piece', 'LED bulb 15W screw (P004), Lighting, per piece']);
    expect(note.className).toBe('alert alert-info');
    expect(offendingWords(note.textContent ?? '')).toEqual([]);
    fireEvent.click(within(note).getByRole('button', { name: 'Open: LED bulb 9W screw' }));
    expect(opened).toHaveBeenCalledWith(LAMP);

    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'led  bulb 9w screw' } });
    await waitFor(async () => expect(listed(await hints())[0]).toBe('LED bulb 9W screw (P003), Lighting, per piece'));
  });

  it('offers the category of similar items, which the person chooses with a tap', async () => {
    open(<ProductForm onSaved={() => undefined} onCancel={() => undefined} />);
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'LED bulb 12W' } });
    const offer = await screen.findByTestId('item-category-hint');
    expect(offer.textContent).toContain('Items like this are in Lighting, such as LED bulb 9W screw.');
    // Nothing is chosen for them: the item code still waits for a category.
    expect(screen.getByTestId('item-code').textContent).toBe('Item code: made by the app once you choose the category');
    fireEvent.click(within(offer).getByRole('button', { name: 'Choose Lighting' }));
    await waitFor(() => expect(screen.getByTestId('item-code').textContent).toMatch(/^Item code: [A-Z]{2,4}-\d{4}, made by the app$/));
    // Once chosen, the offer goes.
    expect(screen.queryByTestId('item-category-hint')).toBeNull();
  });

  it('says nothing for a name of fewer than three letters or one no item is like', async () => {
    const asked = vi.spyOn(retail, 'nameHints');
    open(<ProductForm onSaved={() => undefined} onCancel={() => undefined} />);
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'le' } });
    await new Promise((r) => setTimeout(r, 400));
    expect(asked).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'Padlock 40mm' } });
    await waitFor(() => expect(asked).toHaveBeenCalledWith('Padlock 40mm'));
    expect(screen.queryByRole('note', { name: 'Did you mean' })).toBeNull();
    expect(screen.queryByTestId('item-category-hint')).toBeNull();
    // The form saves as before: Add item is never held back by a hint.
    expect(screen.getByRole('button', { name: 'Add item' }).hasAttribute('disabled')).toBe(false);
  });

  it('suggests nothing while an item is changed, only while one is added', async () => {
    const asked = vi.spyOn(retail, 'nameHints');
    const product = await retail.getProduct(LAMP);
    open(<ProductForm product={product} onSaved={() => undefined} onCancel={() => undefined} />);
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'LED bulb 9W screw cap' } });
    await new Promise((r) => setTimeout(r, 400));
    expect(asked).not.toHaveBeenCalled();
  });
});

describe('a new item on Buy stock', () => {
  it('uses the existing item instead of making a second one', async () => {
    const create = vi.spyOn(retail, 'createProduct');
    open(<RestockForm />);
    fireEvent.click(await screen.findByRole('button', { name: 'Not in the list? Add it here' }));
    fireEvent.change(screen.getByLabelText(/^Description/), { target: { value: 'led bulb 9W' } });
    const note = await hints();
    fireEvent.click(within(note).getByRole('button', { name: 'Use this item: LED bulb 9W screw' }));
    await screen.findByText('LED bulb 9W screw', { selector: '.line-card strong' });
    expect(create).not.toHaveBeenCalled();
  });
});
