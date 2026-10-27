// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { focusFirstMissing } from './Field';

// The empty choice (issue #619): one plain line and one way to fill the list, or who to ask; Save takes the person to it.

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, useRouter: () => null, Link: ({ children, to, search, ...rest }: { children: unknown; to: string; search?: Record<string, string> }) => <a href={`${to}?back=${encodeURIComponent(search?.back ?? '')}`} {...rest}>{children as never}</a> };
});

const { EmptyChoice, LOAD_FAILED, showsEmptyChoice, useComeBack } = await import('./EmptyChoice');

afterEach(() => {
  cleanup();
  window.history.replaceState(null, '', '/');
});

describe('EmptyChoice', () => {
  it('names the field, says the list is empty and offers the screen that adds one, to come back here', () => {
    window.history.replaceState(null, '', '/staff/retail/restock');
    render(<EmptyChoice id="supplier" label="Supplier" text="No suppliers yet." ask="Ask the owner to add a supplier first." add={{ label: 'Add a supplier', to: '/staff/retail/catalogue/suppliers' }} />);
    expect(screen.getByText('Supplier')).toBeTruthy();
    expect(screen.getByText('No suppliers yet.')).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Add a supplier' }).getAttribute('href')).toBe('/staff/retail/catalogue/suppliers?back=%2Fstaff%2Fretail%2Frestock');
    expect(screen.queryByText(/Ask the owner/)).toBeNull();
  });

  it('never calls a list it could not read empty: it says so and offers Try again, whoever is asking (review of #619)', () => {
    const refetch = vi.fn();
    render(<EmptyChoice id="unit" label="Counted in" text="No units yet." ask="Ask the owner to add a unit first." add={{ label: 'Add a new unit', onClick: () => undefined }} load={{ isError: true, refetch }} />);
    expect(screen.getByRole('alert').textContent).toBe(LOAD_FAILED);
    expect(screen.queryByText('No units yet.')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add a new unit' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('makes Try again where Save goes when the field is needed and its list could not be read', () => {
    const { container } = render(<EmptyChoice id="unit" label="Counted in" required text="No units yet." load={{ isError: true, refetch: () => undefined }} />);
    expect(focusFirstMissing(container)).toBe(true);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Try again' }));
  });

  it('is shown for a list that answered with none or failed, never while it loads', () => {
    expect(showsEmptyChoice({ isError: false, data: undefined })).toBe(false);
    expect(showsEmptyChoice({ isError: false, data: [] })).toBe(true);
    expect(showsEmptyChoice({ isError: false, data: [1] })).toBe(false);
    expect(showsEmptyChoice({ isError: false, data: [1] }, [])).toBe(true);
    expect(showsEmptyChoice({ isError: true, data: undefined })).toBe(true);
  });

  it('says who to ask when the session may not add one', () => {
    render(<EmptyChoice id="unit" text="No units yet." ask="Ask the owner to add a unit first." add={null} />);
    expect(screen.getByText('No units yet. Ask the owner to add a unit first.')).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('is where Save takes the person when the field is needed', () => {
    const { container } = render(
      <div>
        <input aria-required="true" defaultValue="filled" />
        <EmptyChoice id="unit" label="Counted in" required text="No units yet." ask="x" add={{ label: 'Add a new unit', onClick: () => undefined }} />
      </div>,
    );
    expect(focusFirstMissing(container)).toBe(true);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Add a new unit' }));
  });

  it('comes back only to a page of the app', () => {
    let back: (() => void) | null = null;
    function Probe() {
      back = useComeBack();
      return null;
    }
    window.history.replaceState(null, '', '/staff/retail/catalogue/units?back=%2Fstaff%2Fretail%2Fsale');
    render(<Probe />);
    expect(back).not.toBeNull();
    cleanup();
    window.history.replaceState(null, '', '/staff/retail/catalogue/units?back=%2F%2Fexample.test');
    render(<Probe />);
    expect(back).toBeNull();
    cleanup();
    window.history.replaceState(null, '', '/staff/retail/catalogue/units?back=%2F%5Cexample.test');
    render(<Probe />);
    expect(back).toBeNull();
    cleanup();
    window.history.replaceState(null, '', '/staff/retail/catalogue/units');
    render(<Probe />);
    expect(back).toBeNull();
  });
});
