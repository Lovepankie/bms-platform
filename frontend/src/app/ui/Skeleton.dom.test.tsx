// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SKELETON_DELAY, Skeleton } from './Skeleton';

// The one loading state (#520): nothing for a second, then rows, and never over content already shown.

beforeEach(() => vi.useFakeTimers());
afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

const rows = () => document.querySelectorAll('.skeleton-row').length;

describe('Skeleton', () => {
  it('shows nothing at all for the first second of a load', () => {
    render(<Skeleton loading rows={4}>Content</Skeleton>);
    expect(document.body.textContent).toBe('');
    act(() => vi.advanceTimersByTime(SKELETON_DELAY - 1));
    expect(rows()).toBe(0);
  });

  it('then shows the rows, hidden from a screen reader behind one Loading', () => {
    render(<Skeleton loading rows={4}>Content</Skeleton>);
    act(() => vi.advanceTimersByTime(SKELETON_DELAY));
    expect(rows()).toBe(4);
    expect(screen.getByRole('status').getAttribute('aria-busy')).toBe('true');
    expect(screen.getByRole('status').textContent).toBe('Loading');
    expect(document.querySelector('.skeleton-row')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('shows the content once loaded', () => {
    const { rerender } = render(<Skeleton loading>Content</Skeleton>);
    act(() => vi.advanceTimersByTime(SKELETON_DELAY));
    rerender(<Skeleton loading={false}>Content</Skeleton>);
    expect(rows()).toBe(0);
    expect(screen.getByText('Content')).toBeTruthy();
  });

  it('never covers content already on the screen with a later load', () => {
    const { rerender } = render(<Skeleton loading={false}>Content</Skeleton>);
    rerender(<Skeleton loading>Content</Skeleton>);
    act(() => vi.advanceTimersByTime(SKELETON_DELAY * 3));
    expect(rows()).toBe(0);
    expect(screen.getByText('Content')).toBeTruthy();
  });

  it('starts again for a different list, which is not content already shown', () => {
    const { rerender } = render(<Skeleton loading={false} of="all">All items</Skeleton>);
    rerender(<Skeleton loading of="low">All items</Skeleton>);
    expect(screen.queryByText('All items')).toBeNull();
    act(() => vi.advanceTimersByTime(SKELETON_DELAY));
    expect(rows()).toBe(3);
    rerender(<Skeleton loading={false} of="low">Low stock</Skeleton>);
    expect(screen.getByText('Low stock')).toBeTruthy();
  });

  it('waits a fresh second for a new list after a slow first one, never flashing the old skeleton', () => {
    const { rerender } = render(<Skeleton loading of="all">All items</Skeleton>);
    act(() => vi.advanceTimersByTime(SKELETON_DELAY));
    expect(rows()).toBe(3);
    rerender(<Skeleton loading={false} of="all">All items</Skeleton>);
    expect(rows()).toBe(0);
    // Records every node put on the page, so a skeleton drawn for one frame and taken away by an effect still counts.
    const seen = new MutationObserver(() => undefined);
    seen.observe(document.body, { childList: true, subtree: true });
    rerender(<Skeleton loading of="low">All items</Skeleton>);
    const drawn = seen.takeRecords().flatMap((r) => [...r.addedNodes]).some((n) => n instanceof Element && n.matches('.skeleton-rows'));
    seen.disconnect();
    expect(drawn).toBe(false);
    expect(rows()).toBe(0);
    act(() => vi.advanceTimersByTime(SKELETON_DELAY - 1));
    expect(rows()).toBe(0);
    act(() => vi.advanceTimersByTime(1));
    expect(rows()).toBe(3);
  });

  it('shows nothing when the load ends inside the second', () => {
    const { rerender } = render(<Skeleton loading>Content</Skeleton>);
    act(() => vi.advanceTimersByTime(400));
    rerender(<Skeleton loading={false}>Content</Skeleton>);
    act(() => vi.advanceTimersByTime(SKELETON_DELAY));
    expect(rows()).toBe(0);
    expect(screen.getByText('Content')).toBeTruthy();
  });
});
