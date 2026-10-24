// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Hint } from './Hint';

// The one help line (issue #526): one line on screen, the rest behind an info button, the whole text always in the page.

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

const LONG = 'Every purchase is kept. If you entered one wrongly, open Correct this purchase, then enter it again.';

describe('the hint', () => {
  it('is a plain line with no button when it fits', () => {
    render(<Hint>Short words.</Hint>);
    expect(screen.getByText('Short words.').className).toBe('hint');
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('keeps the whole text in the page and opens the rest from the info button when it is cut', () => {
    vi.spyOn(HTMLElement.prototype, 'scrollWidth', 'get').mockReturnValue(900);
    vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(300);
    render(<Hint tour="restocks-help">{LONG}</Hint>);
    expect(screen.getByText(LONG)).toBeTruthy();
    const more = screen.getByRole('button', { name: 'Show more' });
    expect(more.getAttribute('aria-expanded')).toBe('false');
    fireEvent.click(more);
    expect(screen.getByRole('button', { name: 'Show less' }).getAttribute('aria-expanded')).toBe('true');
    expect(document.querySelector('[data-tour="restocks-help"]')?.className).toBe('hint-line hint-open');
  });
});
