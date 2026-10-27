// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { GroupCard } from './customer-groups';

// Review of #619: editing a customer group never calls the price lists empty when their read failed. Fabricated names.

afterEach(cleanup);

describe('a customer group being edited', () => {
  const group = { id: 'g1', name: 'Test Group 01', active: true, customer_count: 0, version: 1 };

  it('says the price lists could not be loaded, with Try again, when their read failed', () => {
    const refetch = vi.fn();
    render(<GroupCard group={group} lists={[]} listsLoad={{ isError: true, refetch }} onSave={() => undefined} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit Test Group 01' }));
    expect(screen.getByRole('alert').textContent).toBe('This list could not be loaded. Check the connection.');
    expect(screen.queryByText('No price lists yet.')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('keeps the select while the price lists are on their way, and says there are none once they answer empty', () => {
    const { rerender } = render(<GroupCard group={group} lists={[]} listsLoad={{ isError: false, refetch: () => undefined }} onSave={() => undefined} />);
    fireEvent.click(screen.getByRole('button', { name: 'Edit Test Group 01' }));
    expect(screen.queryByText('No price lists yet.')).toBeNull();
    rerender(<GroupCard group={group} lists={[]} listsLoad={{ isError: false, refetch: () => undefined, data: [] }} onSave={() => undefined} />);
    expect(screen.getByText('No price lists yet. Ask the owner to add a price list first.')).toBeTruthy();
  });
});
