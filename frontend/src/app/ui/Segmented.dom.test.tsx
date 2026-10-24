// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { Segmented } from './Segmented';

// The one "pick one" control (#520): a Radix ToggleGroup of one choice, a named radio group, one Tab stop, arrow keys between.

afterEach(cleanup);

const LEVELS = [
  { value: '', label: 'All items' },
  { value: 'out', label: 'Out of stock' },
  { value: 'low', label: 'Low stock' },
] as const;

type Level = (typeof LEVELS)[number]['value'];

function Example({ clearable = false, start = '' as Level | null }: { clearable?: boolean; start?: Level | null }) {
  const [value, setValue] = useState<Level | null>(start);
  return clearable ? (
    <Segmented label="Show" tour="stock-show" options={LEVELS} value={value} onChange={setValue} clearable />
  ) : (
    <Segmented label="Show" tour="stock-show" options={LEVELS} value={value} onChange={setValue} />
  );
}

const checked = () => screen.getAllByRole('radio').map((b) => [b.textContent, b.getAttribute('aria-checked')]);

describe('Segmented', () => {
  it('is a named radio group of buttons with only the chosen one checked, an empty value included', () => {
    render(<Example />);
    const group = screen.getByRole('radiogroup', { name: 'Show' });
    expect(group.className).toBe('segmented');
    expect(group.getAttribute('data-tour')).toBe('stock-show');
    expect(checked()).toEqual([['All items', 'true'], ['Out of stock', 'false'], ['Low stock', 'false']]);
    for (const b of screen.getAllByRole('radio')) {
      expect(b.tagName).toBe('BUTTON');
      expect(b.getAttribute('type')).toBe('button');
    }
  });

  it('moves the choice on a press, and keeps it when the chosen one is pressed again', () => {
    render(<Example />);
    fireEvent.click(screen.getByRole('radio', { name: 'Low stock' }));
    expect(checked()).toEqual([['All items', 'false'], ['Out of stock', 'false'], ['Low stock', 'true']]);
    fireEvent.click(screen.getByRole('radio', { name: 'Low stock' }));
    expect(screen.getByRole('radio', { name: 'Low stock' }).getAttribute('aria-checked')).toBe('true');
  });

  it('lets a clearable screen clear the choice with a second press', () => {
    render(<Example clearable start="out" />);
    fireEvent.click(screen.getByRole('radio', { name: 'Out of stock' }));
    expect(screen.getAllByRole('radio').every((b) => b.getAttribute('aria-checked') === 'false')).toBe(true);
  });

  it('is one Tab stop, the chosen option, and moves focus with the arrow keys, wrapping at the ends', async () => {
    render(<Example start="out" />);
    // One Tab stop: the group, which hands focus to the chosen option.
    expect(screen.getByRole('radiogroup').getAttribute('tabindex')).toBe('0');
    expect(screen.getAllByRole('radio').map((b) => b.getAttribute('tabindex'))).toEqual(['-1', '-1', '-1']);
    const [all, out, low] = screen.getAllByRole('radio') as HTMLButtonElement[];
    fireEvent.focus(screen.getByRole('radiogroup'));
    await waitFor(() => expect(document.activeElement).toBe(out));
    fireEvent.keyDown(out!, { key: 'ArrowRight' });
    await waitFor(() => expect(document.activeElement).toBe(low));
    fireEvent.keyDown(low!, { key: 'ArrowRight' });
    await waitFor(() => expect(document.activeElement).toBe(all));
    fireEvent.keyDown(all!, { key: 'ArrowLeft' });
    await waitFor(() => expect(document.activeElement).toBe(low));
  });
});
