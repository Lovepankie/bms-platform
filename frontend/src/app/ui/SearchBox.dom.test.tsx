// @vitest-environment jsdom
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SearchBox } from './SearchBox';
import { useLiveSearch } from './useLiveSearch';

function Box() {
  const search = useLiveSearch();
  return <SearchBox id="t-q" label="Find" search={search} fetching={false} count={0} />;
}

describe('the search box in a browser', () => {
  it('puts the cursor back in the box after Clear, emptied, so typing can go on', () => {
    render(<Box />);
    const input = screen.getByLabelText('Find') as HTMLInputElement;
    fireEvent.change(input, { target: { value: 'kato' } });
    expect(input.value).toBe('kato');
    fireEvent.click(screen.getByRole('button', { name: 'Clear search' }));
    expect(input.value).toBe('');
    expect(document.activeElement).toBe(input);
    expect(screen.queryByRole('button', { name: 'Clear search' })).toBeNull();
  });

  it('empties the box on Escape and keeps the focus there', () => {
    render(<Box />);
    const input = screen.getByLabelText('Find') as HTMLInputElement;
    input.focus();
    fireEvent.change(input, { target: { value: 'kato' } });
    fireEvent.keyDown(input, { key: 'Escape' });
    expect(input.value).toBe('');
    expect(document.activeElement).toBe(input);
  });
});
