// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { Stepper, stepped } from './Stepper';

/** A button that may be pressed: not disabled, and not a primary button whose form is still not ready (issue #526). */
const ready = (b: unknown) => !(b as HTMLButtonElement).disabled && (b as HTMLElement).getAttribute('data-not-ready') !== 'true';

// The quantity control (#520): minus and plus around a labelled number field, within min and max, with the keyboard.

afterEach(cleanup);

function Example({ start = '1', min = 1, max, step }: { start?: string; min?: number; max?: number; step?: number }) {
  const [qty, setQty] = useState(start);
  return <Stepper id="qty" label="Quantity" name="Sugar" value={qty} onChange={setQty} min={min} max={max} step={step} inputMode="decimal" />;
}

const field = () => screen.getByLabelText('Quantity') as HTMLInputElement;

describe('Stepper', () => {
  it('labels the field and names the buttons after what is counted', () => {
    render(<Example />);
    expect(field().id).toBe('qty');
    expect(field().getAttribute('inputmode')).toBe('decimal');
    for (const name of ['One less Sugar', 'One more Sugar']) {
      const button = screen.getByRole('button', { name });
      expect(button.getAttribute('type')).toBe('button');
      expect(button.getAttribute('aria-controls')).toBe('qty');
    }
  });

  it('adds and takes away one, and stops at min and max', () => {
    render(<Example start="2" max={3} />);
    fireEvent.click(screen.getByRole('button', { name: 'One more Sugar' }));
    expect(field().value).toBe('3');
    const more = screen.getByRole('button', { name: 'One more Sugar' });
    expect(more.getAttribute('aria-disabled')).toBe('true');
    fireEvent.click(more);
    expect(field().value).toBe('3');
    fireEvent.click(screen.getByRole('button', { name: 'One less Sugar' }));
    fireEvent.click(screen.getByRole('button', { name: 'One less Sugar' }));
    expect(field().value).toBe('1');
    expect(screen.getByRole('button', { name: 'One less Sugar' }).getAttribute('aria-disabled')).toBe('true');
  });

  it('keeps focus on a button that reaches the end, since it is never disabled', () => {
    render(<Example start="2" />);
    const less = screen.getByRole('button', { name: 'One less Sugar' }) as HTMLButtonElement;
    less.focus();
    fireEvent.click(less);
    expect(ready(less)).toBe(true);
    expect(document.activeElement).toBe(less);
  });

  it('still takes a typed value, and steps from it', () => {
    render(<Example />);
    fireEvent.change(field(), { target: { value: '12.5' } });
    expect(field().value).toBe('12.5');
    fireEvent.click(screen.getByRole('button', { name: 'One more Sugar' }));
    expect(field().value).toBe('13.5');
  });

  it('answers the arrow keys in the field, and leaves Home and End to move the caret', () => {
    render(<Example start="4" max={9} />);
    fireEvent.keyDown(field(), { key: 'ArrowUp' });
    expect(field().value).toBe('5');
    fireEvent.keyDown(field(), { key: 'ArrowDown' });
    fireEvent.keyDown(field(), { key: 'ArrowDown' });
    expect(field().value).toBe('3');
    fireEvent.keyDown(field(), { key: 'Home' });
    fireEvent.keyDown(field(), { key: 'End' });
    expect(field().value).toBe('3');
  });

  it('never raises a value below the minimum with ArrowDown, nor lowers one above the maximum with ArrowUp', () => {
    render(<Example start="0.5" max={9} />);
    fireEvent.keyDown(field(), { key: 'ArrowDown' });
    expect(field().value).toBe('0.5');
    fireEvent.change(field(), { target: { value: '12' } });
    fireEvent.keyDown(field(), { key: 'ArrowUp' });
    expect(field().value).toBe('12');
  });

  it('steps without drift, counts an empty or unreadable value as min, and keeps within the bounds', () => {
    expect(stepped('0.2', 0.1, 0)).toBe('0.3');
    expect(stepped('', 1, 1)).toBe('2');
    expect(stepped('abc', -1, 1)).toBe('1');
    expect(stepped('1.5', -1, 1)).toBe('1');
    expect(stepped('8', 5, 0, 10)).toBe('10');
  });
});
