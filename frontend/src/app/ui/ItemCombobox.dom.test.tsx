// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ItemCombobox, PICKER_LIMIT, pickerStatus } from './ItemCombobox';
import { SEARCH_DELAY_MS, matchesSearch, useLiveSearch } from './useLiveSearch';

// The one item picker (issue #534): matches only while something is typed, the combobox pattern of the WAI-ARIA practices,
// and a pick that empties and closes the list and leaves the cursor in the box. Fabricated item names.

const ITEMS = ['Test Bulb 9W', 'Test Bulb 15W', 'Test Lamp', 'Test Cable 2m'];

function Picker({ onPick, items = ITEMS }: { onPick: (item: string) => void; items?: string[] }) {
  const search = useLiveSearch();
  const results = search.term === '' ? undefined : items.filter((i) => matchesSearch(i, search.term));
  return <ItemCombobox id="t-item" label="Find an item" search={search} results={results} optionKey={(i) => i} renderOption={(i) => <span className="cb-text">{i}</span>} onPick={onPick} />;
}

const box = () => screen.getByRole('combobox', { name: 'Find an item' }) as HTMLInputElement;

/** Type and let the debounce run, as a person who stops typing. */
function type(text: string) {
  fireEvent.change(box(), { target: { value: text } });
  act(() => {
    vi.advanceTimersByTime(SEARCH_DELAY_MS);
  });
}

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('the item picker', () => {
  it('is a combobox that controls a list, closed and with no options until something is typed', () => {
    vi.useFakeTimers();
    render(<Picker onPick={() => undefined} />);
    expect(box().getAttribute('aria-expanded')).toBe('false');
    expect(box().getAttribute('aria-autocomplete')).toBe('list');
    expect(box().getAttribute('aria-controls')).toBeNull();
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(screen.queryAllByRole('option')).toHaveLength(0);
  });

  it('waits for the pause after the last key before it searches', () => {
    vi.useFakeTimers();
    render(<Picker onPick={() => undefined} />);
    fireEvent.change(box(), { target: { value: 'bulb' } });
    expect(screen.queryAllByRole('option')).toHaveLength(0);
    expect(screen.getByText('Searching', { selector: '.cb-empty' })).toBeTruthy();
    // The live region waits for the answer, so a screen reader hears it once per pause.
    expect(screen.getByRole('status').textContent).toBe('');
    act(() => {
      vi.advanceTimersByTime(SEARCH_DELAY_MS);
    });
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['Test Bulb 9W', 'Test Bulb 15W']);
    expect(screen.getByRole('status').textContent).toBe('2 items found');
    expect(box().getAttribute('aria-controls')).toBe('t-item-list');
    expect(document.getElementById('t-item-list')?.getAttribute('role')).toBe('listbox');
    expect(box().getAttribute('aria-expanded')).toBe('true');
  });

  it('moves the highlight with the arrow keys, announces it, and picks it with Enter', () => {
    vi.useFakeTimers();
    const onPick = vi.fn();
    render(<Picker onPick={onPick} />);
    box().focus();
    type('bulb');
    expect(box().getAttribute('aria-activedescendant')).toBeNull();
    fireEvent.keyDown(box(), { key: 'ArrowDown' });
    fireEvent.keyDown(box(), { key: 'ArrowDown' });
    fireEvent.keyDown(box(), { key: 'ArrowDown' });
    const options = screen.getAllByRole('option');
    expect(options.map((o) => o.getAttribute('aria-selected'))).toEqual(['false', 'true']);
    expect(box().getAttribute('aria-activedescendant')).toBe(options[1]?.id);
    fireEvent.keyDown(box(), { key: 'ArrowUp' });
    expect(box().getAttribute('aria-activedescendant')).toBe(options[0]?.id);
    fireEvent.keyDown(box(), { key: 'Enter' });
    expect(onPick).toHaveBeenCalledWith('Test Bulb 9W');
  });

  it('never picks on Enter when nothing is highlighted', () => {
    vi.useFakeTimers();
    const onPick = vi.fn();
    render(<Picker onPick={onPick} />);
    type('lamp');
    fireEvent.keyDown(box(), { key: 'Enter' });
    expect(onPick).not.toHaveBeenCalled();
    expect(screen.getAllByRole('option')).toHaveLength(1);
  });

  it('on a tap adds the item, empties the box, closes the list and keeps the cursor in the box', () => {
    vi.useFakeTimers();
    const onPick = vi.fn();
    render(<Picker onPick={onPick} />);
    box().focus();
    type('cable');
    const option = screen.getByRole('option', { name: 'Test Cable 2m' });
    fireEvent.mouseDown(option);
    fireEvent.click(option);
    expect(onPick).toHaveBeenCalledWith('Test Cable 2m');
    expect(box().value).toBe('');
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(box().getAttribute('aria-expanded')).toBe('false');
    expect(document.activeElement).toBe(box());
  });

  it('closes the list on Escape and empties the box on a second Escape', () => {
    vi.useFakeTimers();
    render(<Picker onPick={() => undefined} />);
    box().focus();
    type('bulb');
    fireEvent.keyDown(box(), { key: 'Escape' });
    expect(screen.queryByRole('listbox')).toBeNull();
    expect(box().value).toBe('bulb');
    fireEvent.keyDown(box(), { key: 'ArrowDown' });
    expect(screen.getAllByRole('option')).toHaveLength(2);
    fireEvent.keyDown(box(), { key: 'Escape' });
    fireEvent.keyDown(box(), { key: 'Escape' });
    expect(box().value).toBe('');
  });

  it('closes when the cursor leaves it, and opens again on the way back', () => {
    vi.useFakeTimers();
    render(
      <>
        <Picker onPick={() => undefined} />
        <button type="button">Elsewhere</button>
      </>,
    );
    box().focus();
    type('bulb');
    fireEvent.blur(box(), { relatedTarget: screen.getByRole('button', { name: 'Elsewhere' }) });
    expect(screen.queryByRole('listbox')).toBeNull();
    fireEvent.focus(box());
    expect(screen.getAllByRole('option')).toHaveLength(2);
  });

  it('hides the matches of the earlier text as soon as more is typed, so a quick tap cannot pick one', () => {
    vi.useFakeTimers();
    const onPick = vi.fn();
    render(<Picker onPick={onPick} />);
    box().focus();
    type('bulb');
    expect(screen.getAllByRole('option')).toHaveLength(2);
    fireEvent.change(box(), { target: { value: 'bulb 15' } });
    expect(screen.queryAllByRole('option')).toHaveLength(0);
    act(() => {
      vi.advanceTimersByTime(SEARCH_DELAY_MS);
    });
    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual(['Test Bulb 15W']);
    expect(onPick).not.toHaveBeenCalled();
  });

  it('starts a new answer with nothing highlighted in the same render', () => {
    vi.useFakeTimers();
    const onPick = vi.fn();
    render(<Picker onPick={onPick} />);
    box().focus();
    type('bulb');
    fireEvent.keyDown(box(), { key: 'ArrowDown' });
    type('test');
    expect(box().getAttribute('aria-activedescendant')).toBeNull();
    fireEvent.keyDown(box(), { key: 'Enter' });
    expect(onPick).not.toHaveBeenCalled();
  });

  it('keeps Escape to itself, so a panel around it does not close as well', () => {
    vi.useFakeTimers();
    const outer = vi.fn();
    render(
      <div onKeyDown={(e) => { if (e.key === 'Escape') outer(); }}>
        <Picker onPick={() => undefined} />
      </div>,
    );
    box().focus();
    type('bulb');
    fireEvent.keyDown(box(), { key: 'Escape' });
    fireEvent.keyDown(box(), { key: 'Escape' });
    expect(outer).not.toHaveBeenCalled();
    // With the list closed and the box empty, Escape has nothing to do here and goes on to the panel.
    fireEvent.keyDown(box(), { key: 'Escape' });
    expect(outer).toHaveBeenCalledTimes(1);
  });

  it('names its list after its own box, so two pickers on a screen are told apart', () => {
    vi.useFakeTimers();
    render(<Picker onPick={() => undefined} />);
    type('bulb');
    expect(screen.getByRole('listbox', { name: 'Find an item' })).toBeTruthy();
  });

  it('says plainly when no item matches', () => {
    vi.useFakeTimers();
    render(<Picker onPick={() => undefined} />);
    type('fridge');
    expect(screen.queryAllByRole('option')).toHaveLength(0);
    expect(screen.getByText('No item matches "fridge".', { selector: '.cb-empty' })).toBeTruthy();
    expect(screen.getByRole('status').textContent).toBe('No item matches "fridge".');
  });

  it('shows a short list, and asks for more letters when there are more matches', () => {
    vi.useFakeTimers();
    const many = Array.from({ length: PICKER_LIMIT + 5 }, (_, i) => `Test Bolt ${i + 1}`);
    render(<Picker onPick={() => undefined} items={many} />);
    type('bolt');
    expect(screen.getAllByRole('option')).toHaveLength(PICKER_LIMIT);
    expect(screen.getByRole('status').textContent).toBe(`First ${PICKER_LIMIT} items shown. Type more to narrow.`);
  });
});

describe('the item picker status line', () => {
  it('says nothing for an empty box, and one item in the singular', () => {
    expect(pickerStatus({ text: '', term: '', settled: false, count: undefined, more: false })).toBe('');
    expect(pickerStatus({ text: 'x', term: 'x', settled: false, count: 1, more: false })).toBe('');
    expect(pickerStatus({ text: 'x', term: 'x', settled: true, count: 1, more: false })).toBe('1 item found');
    expect(pickerStatus({ text: 'x', term: 'x', settled: true, count: 0, more: false, empty: 'Nothing here.' })).toBe('Nothing here.');
  });
});
