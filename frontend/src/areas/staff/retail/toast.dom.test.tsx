// @vitest-environment jsdom
import { act, cleanup, render, renderHook, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TOAST_MS, Toast, useToast } from './toast';

// The dark toast (#508, docs/ui-rules.md rule 15): a leading icon for its kind, announced by kind, gone after about four seconds.

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('Toast', () => {
  it('leads with the check icon and is a polite status for a success', () => {
    render(<Toast message="Sale recorded." tone="success" />);
    const toast = screen.getByRole('status');
    expect(toast.className).toBe('toast toast-success');
    expect(toast.firstElementChild?.className).toBe('toast-icon');
    expect(toast.querySelector('svg')?.getAttribute('aria-hidden')).toBe('true');
    expect(toast.textContent).toBe('Sale recorded.');
  });

  it('is an alert with the error icon for a failure, and hidden when the screen already says it', () => {
    render(<Toast message="Could not save." tone="error" />);
    expect(screen.getByRole('alert').className).toBe('toast toast-error');
    cleanup();
    const { container } = render(<Toast message="Saved." tone="success" silent />);
    expect(container.querySelector('.toast')?.getAttribute('aria-hidden')).toBe('true');
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('shows for four seconds, then goes', () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useToast());
    act(() => result.current.show('Saved.', 'info'));
    const { container, unmount } = render(<>{result.current.toast}</>);
    expect(container.querySelector('.toast-info')).not.toBeNull();
    unmount();
    expect(TOAST_MS).toBe(4000);
    act(() => vi.advanceTimersByTime(TOAST_MS));
    expect(result.current.toast).toBeNull();
  });

  it('has no default tone, so a refusal can never draw the success check by omission', () => {
    const { result } = renderHook(() => useToast());
    // @ts-expect-error the tone is required
    act(() => result.current.show('Already on the sale.'));
    act(() => result.current.show('Already on the sale.', 'info'));
    const { container } = render(<>{result.current.toast}</>);
    expect(container.querySelector('.toast')?.className).toBe('toast toast-info');
  });
});
