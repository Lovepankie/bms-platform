import { fireEvent, screen, within } from '@testing-library/react';

// For tests: choose a category the way a person does with the category picker (issue #578): tap the field, then the
// category in the list that opens (the sheet on a phone, the popover on a wider screen).

const WAIT = { timeout: 4000 };

const escaped = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export async function chooseCategory(field: HTMLElement, name: string): Promise<void> {
  fireEvent.click(field);
  const list = await screen.findByRole('listbox', { name: 'Categories' }, WAIT);
  fireEvent.click(await within(list).findByRole('option', { name: new RegExp(`^${escaped(name)}`) }, WAIT));
}
