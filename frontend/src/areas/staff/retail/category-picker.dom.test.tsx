// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import type { Category } from '../../../api/retail';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Issue #578: one category picker wherever a category is chosen. A search that matches any part of the name whatever the
// case, "Add a new category" for whoever manages the catalogue with the code start the app makes, the new one chosen at once
// with a dark toast, the arrow keys, Enter and Escape, and "No category called X. Add it?". On a phone it is a full height
// sheet with Done. Fabricated categories.

vi.setConfig({ testTimeout: 20000 });

const createCategory = vi.fn();
const suggestCategoryPrefix = vi.fn();
const updateCategory = vi.fn();
// The whole catalogue list, a switched-off category too, which the screens leave out of their choices.
const torches: Category = { id: 'c4', name: 'Torches', active: false, version: 2, code_prefix: 'TRC' };

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      createCategory: (...a: unknown[]) => createCategory(...a),
      suggestCategoryPrefix: (...a: unknown[]) => suggestCategoryPrefix(...a),
      updateCategory: (...a: unknown[]) => updateCategory(...a),
      listCategories: () => Promise.resolve([...list, torches]),
    },
  };
});

const { CategoryPicker, matchCategories, sameName } = await import('./category-picker');
const { ScreenToast, useToast } = await import('./toast');
const { unwrap } = await import('../../../api/retail');

const list: Category[] = [
  { id: 'c1', name: 'Bulbs', active: true, version: 1, code_prefix: 'BLB' },
  { id: 'c2', name: 'Cables and wires', active: true, version: 1, code_prefix: 'CBL' },
  { id: 'c3', name: 'LED strips', active: true, version: 1, code_prefix: 'LED' },
];

const withoutManage = (): Me => {
  const me = mockMe('admin');
  return { ...me, permissions: (me.permissions ?? []).filter((p) => p !== 'retail.catalogue.manage') };
};

/** A screen with its own toast, which the picker uses (ScreenToast) rather than drawing one of its own. */
function Harness({ noneLabel, allowAdd }: { noneLabel?: string; allowAdd?: boolean }) {
  const [value, setValue] = useState('c1');
  const { show, toast } = useToast();
  return (
    <ScreenToast.Provider value={show}>
      <CategoryPicker id="pick" categories={list} value={value} onChange={setValue} noneLabel={noneLabel} allowAdd={allowAdd} required />
      <output data-testid="value">{value}</output>
      {toast}
    </ScreenToast.Provider>
  );
}

function open(node: JSX.Element, me: Me = mockMe('admin'), client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  render(
    <QueryClientProvider client={client}>
      <StaffContext.Provider value={{ me, branch: me.branches?.[0]?.id ?? '' }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

/** Makes the window as narrow as a phone (under 720px) for the next render. */
function asPhone() {
  vi.stubGlobal('matchMedia', (media: string) => ({ matches: media === '(max-width: 719px)', media, addEventListener: () => undefined, removeEventListener: () => undefined }));
}

const field = () => screen.getByRole('combobox', { name: /^Category/ });
const search = () => screen.getByRole('combobox', { name: 'Search categories' });
const options = () => within(screen.getByRole('listbox', { name: 'Categories' })).getAllByRole('option').map((o) => o.textContent);

afterEach(() => {
  cleanup();
  createCategory.mockReset();
  suggestCategoryPrefix.mockReset();
  updateCategory.mockReset();
  vi.unstubAllGlobals();
});

describe('the category picker on a laptop', () => {
  it('opens a searchable list with the cursor in the search and the chosen category marked', async () => {
    open(<Harness />);
    expect(field().textContent).toBe('Bulbs');
    fireEvent.click(field());
    await waitFor(() => expect(document.activeElement).toBe(search()));
    expect(screen.getByRole('dialog', { name: 'Choose a category' })).toBeTruthy();
    expect(options()).toEqual(['Bulbs', 'Cables and wires', 'LED strips']);
    expect(screen.getByRole('option', { name: 'Bulbs' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByRole('option', { name: 'LED strips' }).getAttribute('aria-selected')).toBe('false');
  });

  it('filters on any part of the name whatever the case, and keeps names as typed', async () => {
    expect(matchCategories(list, 'led').map((c) => c.name)).toEqual(['LED strips']);
    expect(matchCategories(list, ' WIRE ').map((c) => c.name)).toEqual(['Cables and wires']);
    open(<Harness />);
    fireEvent.click(field());
    fireEvent.change(search(), { target: { value: 'b' } });
    expect(options()).toEqual(['Bulbs', 'Cables and wires']);
    expect(document.getElementById('pick-status')?.textContent).toBe('2 categories found');
    fireEvent.change(search(), { target: { value: 'STRIP' } });
    expect(options()).toEqual(['LED strips']);
    fireEvent.click(screen.getByRole('option', { name: 'LED strips' }));
    expect(screen.getByTestId('value').textContent).toBe('c3');
    expect(field().textContent).toBe('LED strips');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('is used with the keyboard: arrows, Enter, Escape, and the focus goes back to the field', async () => {
    open(<Harness />);
    field().focus();
    fireEvent.keyDown(field(), { key: 'ArrowDown' });
    await waitFor(() => expect(document.activeElement).toBe(search()));
    fireEvent.keyDown(search(), { key: 'ArrowDown' });
    fireEvent.keyDown(search(), { key: 'ArrowDown' });
    const active = search().getAttribute('aria-activedescendant');
    expect(document.getElementById(active ?? '')?.textContent).toBe('Cables and wires');
    fireEvent.keyDown(search(), { key: 'ArrowUp' });
    expect(document.getElementById(search().getAttribute('aria-activedescendant') ?? '')?.textContent).toBe('Bulbs');
    fireEvent.keyDown(search(), { key: 'End' });
    fireEvent.keyDown(search(), { key: 'Enter' });
    expect(screen.getByTestId('value').textContent).toBe('c3');
    await waitFor(() => expect(document.activeElement).toBe(field()));
    // Escape closes without changing the choice.
    fireEvent.click(field());
    await waitFor(() => expect(document.activeElement).toBe(search()));
    fireEvent.keyDown(search(), { key: 'ArrowDown' });
    fireEvent.keyDown(document.activeElement as Element, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByRole('listbox')).toBeNull());
    expect(screen.getByTestId('value').textContent).toBe('c3');
    await waitFor(() => expect(document.activeElement).toBe(field()));
  });

  it('keeps its Enter to itself inside the item form', async () => {
    const formKeys = vi.fn();
    open(<div onKeyDown={(e) => formKeys(e.key)}><Harness /></div>);
    fireEvent.click(field());
    fireEvent.change(search(), { target: { value: 'led' } });
    fireEvent.keyDown(search(), { key: 'Enter' });
    expect(screen.getByTestId('value').textContent).toBe('c3');
    expect(formKeys).not.toHaveBeenCalledWith('Enter');
  });

  it('says plainly when nothing matches and offers to add it', async () => {
    open(<Harness />);
    fireEvent.click(field());
    fireEvent.change(search(), { target: { value: 'Rice' } });
    expect(screen.getByText('No category called "Rice". Add it?')).toBeTruthy();
    expect(document.getElementById('pick-status')?.textContent).toBe('0 categories found');
    fireEvent.click(screen.getByRole('button', { name: 'Add Rice' }));
    expect((screen.getByLabelText(/^Name of the new category/) as HTMLInputElement).value).toBe('Rice');
  });

  it('adds a category inline with the code start the app makes, chooses it and shows a dark toast', async () => {
    suggestCategoryPrefix.mockResolvedValue('SLR');
    createCategory.mockResolvedValue({ id: 'c9', name: 'solar panels', active: true, version: 1, code_prefix: 'SLR', next_code: 'SLR-0001' });
    open(<Harness />);
    fireEvent.click(field());
    fireEvent.click(screen.getByRole('button', { name: 'Add a new category' }));
    const name = screen.getByLabelText(/^Name of the new category/) as HTMLInputElement;
    await waitFor(() => expect(document.activeElement).toBe(name));
    expect(screen.queryByLabelText(/code/i)).toBeNull();
    fireEvent.change(name, { target: { value: 'solar panels' } });
    expect(await screen.findByText('SLR', { selector: 'strong' })).toBeTruthy();
    expect(screen.getByTestId('pick-add-code').textContent).toBe('Item codes here will start with SLR, like SLR-0001, made by the app.');
    expect(suggestCategoryPrefix).toHaveBeenLastCalledWith('solar panels');
    fireEvent.keyDown(name, { key: 'Enter' });
    await waitFor(() => expect(screen.getByTestId('value').textContent).toBe('c9'));
    // Only the name is sent: the code start is the app's, and the case is the owner's.
    expect(createCategory).toHaveBeenCalledTimes(1);
    expect(createCategory).toHaveBeenCalledWith('solar panels');
    // One toast, the screen's, dark and neutral with the success check; the same words in the picker's live region.
    expect(document.querySelectorAll('.toast')).toHaveLength(1);
    const toast = document.querySelector('.toast');
    expect(toast?.classList.contains('toast-success')).toBe(true);
    expect(toast?.textContent).toBe('Added the category solar panels. Its item codes start with SLR.');
    expect(screen.getByTestId('pick-said').textContent).toBe('Added the category solar panels. Its item codes start with SLR.');
    expect(screen.queryByRole('listbox')).toBeNull();
  });

  it('names the new category at once and puts it in the Stock filter\'s list too', async () => {
    const rice: Category = { id: 'c9', name: 'Rice', active: true, version: 1, code_prefix: 'RCE', next_code: 'RCE-0001' };
    createCategory.mockResolvedValue(rice);
    suggestCategoryPrefix.mockResolvedValue('RCE');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } });
    client.setQueryData(['retail', 'catalogue', 'categories'], list);
    client.setQueryData(['retail', 'categories'], list);
    open(<Harness />, mockMe('admin'), client);
    fireEvent.click(field());
    fireEvent.change(search(), { target: { value: 'Rice' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add Rice' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add category' }));
    await waitFor(() => expect(screen.getByTestId('value').textContent).toBe('c9'));
    // The Harness's own list is fixed, as a screen whose list has not caught up yet: the field still names it.
    expect(field().textContent).toBe('Rice');
    // The Stock page's list (no page reads it now) carries it, and is fetched again when that page opens.
    expect((client.getQueryData<Category[]>(['retail', 'categories']) ?? []).map((c) => c.id)).toContain('c9');
    expect(client.getQueryState(['retail', 'categories'])?.isInvalidated).toBe(true);
  });

  it('asks for a name before adding, and shows the server\'s refusal of a name taken meanwhile in plain words', async () => {
    suggestCategoryPrefix.mockResolvedValue('');
    // The real 409 of POST /retail/categories, through the client's own unwrap: a name another phone added a moment ago.
    createCategory.mockImplementation(async () => unwrap({ error: { code: 'duplicate_category', status: 409, detail: 'A category with this name exists.' }, response: new Response(null, { status: 409 }) }));
    open(<Harness />);
    fireEvent.click(field());
    fireEvent.click(screen.getByRole('button', { name: 'Add a new category' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add category' }));
    expect(screen.getByText('Type the name of the category.')).toBeTruthy();
    expect(createCategory).not.toHaveBeenCalled();
    const name = screen.getByLabelText(/^Name of the new category/);
    expect(document.activeElement).toBe(name);
    fireEvent.change(name, { target: { value: 'Rice' } });
    fireEvent.keyDown(name, { key: 'Enter' });
    expect(await screen.findByText('A category with this name already exists.')).toBeTruthy();
    expect(screen.getByTestId('value').textContent).toBe('c1');
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(options()).toEqual(['Bulbs', 'Cables and wires', 'LED strips']);
  });

  it('says plainly that a name is taken, whatever the case, and offers that category instead of sending', async () => {
    expect(sameName([...list, torches], '  bULBS ')?.id).toBe('c1');
    expect(sameName([...list, torches], 'Bulb')).toBeUndefined();
    open(<Harness />);
    fireEvent.click(field());
    fireEvent.change(search(), { target: { value: 'LED' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add a new category' }));
    const name = screen.getByLabelText(/^Name of the new category/);
    fireEvent.change(name, { target: { value: 'bulbs' } });
    expect(await screen.findByText('There is already a category called Bulbs.')).toBeTruthy();
    fireEvent.keyDown(name, { key: 'Enter' });
    expect(createCategory).not.toHaveBeenCalled();
    fireEvent.change(name, { target: { value: 'led strips' } });
    fireEvent.click(screen.getByRole('button', { name: 'Choose LED strips' }));
    expect(screen.getByTestId('value').textContent).toBe('c3');
    expect(createCategory).not.toHaveBeenCalled();
  });

  it('finds a switched-off category of the same name and switches it back on to choose it', async () => {
    updateCategory.mockResolvedValue({ ...torches, active: true, version: 3 });
    open(<Harness />);
    fireEvent.click(field());
    expect(options()).not.toContain('Torches');
    fireEvent.click(screen.getByRole('button', { name: 'Add a new category' }));
    fireEvent.change(screen.getByLabelText(/^Name of the new category/), { target: { value: 'torches' } });
    expect(await screen.findByText('There is already a category called Torches (switched off).')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Switch Torches on and choose it' }));
    await waitFor(() => expect(screen.getByTestId('value').textContent).toBe('c4'));
    expect(updateCategory).toHaveBeenCalledWith('c4', 2, { active: true });
    expect(createCategory).not.toHaveBeenCalled();
    expect(field().textContent).toBe('Torches');
    expect(document.querySelector('.toast')?.textContent).toBe('Switched Torches on and chose it.');
  });

  it('hides Add from whoever does not manage the catalogue, and on a filter', async () => {
    open(<Harness />, withoutManage());
    fireEvent.click(field());
    fireEvent.change(search(), { target: { value: 'Rice' } });
    expect(screen.queryByRole('button', { name: 'Add a new category' })).toBeNull();
    expect(screen.queryByRole('button', { name: /^Add/ })).toBeNull();
    expect(screen.getByText('No category called "Rice".')).toBeTruthy();
    cleanup();
    open(<Harness noneLabel="All categories" allowAdd={false} />);
    fireEvent.click(field());
    expect(screen.queryByRole('button', { name: 'Add a new category' })).toBeNull();
    expect(options()).toEqual(['All categories', 'Bulbs', 'Cables and wires', 'LED strips']);
    fireEvent.click(screen.getByRole('option', { name: 'All categories' }));
    expect(screen.getByTestId('value').textContent).toBe('');
    expect(field().textContent).toBe('All categories');
  });
});

describe('the category picker on a phone', () => {
  it('is a full height sheet with the search first, Add, the list and Done', async () => {
    asPhone();
    open(<Harness />);
    fireEvent.click(field());
    const sheet = await screen.findByRole('dialog', { name: 'Choose a category' });
    expect(sheet.classList.contains('sheet-tall')).toBe(true);
    await waitFor(() => expect(document.activeElement).toBe(search()));
    const order = [search(), within(sheet).getByRole('button', { name: 'Add a new category' }), within(sheet).getByRole('listbox', { name: 'Categories' }), within(sheet).getByRole('button', { name: 'Done' })];
    order.slice(1).forEach((el, i) => expect((order[i]?.compareDocumentPosition(el) ?? 0) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy());
    fireEvent.click(within(sheet).getByRole('button', { name: 'Done' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(screen.getByTestId('value').textContent).toBe('c1');
    await waitFor(() => expect(document.activeElement).toBe(field()));
  });

  it('adds a category from the sheet and chooses it', async () => {
    asPhone();
    suggestCategoryPrefix.mockResolvedValue('PHN');
    createCategory.mockResolvedValue({ id: 'c8', name: 'Phone cases', active: true, version: 1, code_prefix: 'PHN', next_code: 'PHN-0001' });
    open(<Harness />);
    fireEvent.click(field());
    fireEvent.change(await screen.findByRole('combobox', { name: 'Search categories' }), { target: { value: 'Phone cases' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add Phone cases' }));
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Add category' })); });
    await waitFor(() => expect(screen.getByTestId('value').textContent).toBe('c8'));
    expect(createCategory).toHaveBeenCalledWith('Phone cases');
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(document.querySelector('.toast')?.textContent).toBe('Added the category Phone cases. Its item codes start with PHN.');
  });
});

describe('with no category to choose (issue #619)', () => {
  it('says so and adds the first one in place for whoever manages the catalogue', async () => {
    open(<CategoryPicker id="c" categories={[]} value="" onChange={() => undefined} />);
    expect(await screen.findByText('No categories yet.')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Add a category' })).toBeTruthy();
  });

  it('tells anyone else to ask the owner', async () => {
    open(<CategoryPicker id="c" categories={[]} value="" onChange={() => undefined} />, mockMe('sales'));
    expect(await screen.findByText('No categories yet. Ask the owner to add a category first.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Add a category' })).toBeNull();
  });
});

