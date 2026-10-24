import { fireEvent, screen } from '@testing-library/react';

// For tests: pick an item the way a person does with the item picker (ItemCombobox, issue #534), by typing into the box and
// tapping the option that starts with the item's name. `box` is the box's label, `nth` which of several boxes
// with that label (the last by default), `typed` what is typed when it is not the name.

export const PICKER_LABEL = 'Find an item by name, code or category';

// A screen that loads its route first can take a few seconds under a full test run.
const WAIT = { timeout: 4000 };

const escaped = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export async function pickItem(name: string, options: { box?: string; typed?: string; nth?: number } = {}): Promise<void> {
  const boxes = await screen.findAllByRole('combobox', { name: options.box ?? PICKER_LABEL }, WAIT);
  const box = boxes[options.nth ?? boxes.length - 1] as HTMLInputElement;
  fireEvent.change(box, { target: { value: options.typed ?? name } });
  fireEvent.click(await screen.findByRole('option', { name: new RegExp(`^${escaped(name)}(\\s|$)`) }, WAIT));
}
