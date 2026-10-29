// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { ALL_BRANCHES } from '../../../auth/branch';
import { ShopHere, switchedTo, useBranchSwitch } from '../branch-picker';
import { TOAST_MS, type ToastTone, useToast } from './toast';

// Issue #680: every staff page names the shop it works in above its content, drawn once by the staff layout, and one tap there
// switches shop; every switch says "You are now in <shop>." in the house toast. Every name below is invented.

const me = {
  user_id: 'u-self',
  all_branches: true,
  branches: [
    { id: 'b-one', code: 'HQ', name: 'Test Shop One', stocks_goods: true, sells_goods: true },
    { id: 'b-two', code: 'SHOP2', name: 'Test Shop Two', stocks_goods: true, sells_goods: true },
  ],
} as unknown as Me;

const one = { ...me, all_branches: false, branches: [me.branches?.[0]] } as unknown as Me;

function Harness({ who = me, branch = 'b-one', choose }: { who?: Me; branch?: string; choose: (selection: string) => boolean }) {
  const { choose: switchTo, toast } = useBranchSwitch(who, branch, choose);
  return (
    <>
      <ShopHere me={who} branch={branch} onChoose={switchTo} />
      {toast}
    </>
  );
}

/** The name the label shows, without the options of its select. */
const shown = () => screen.getByTestId('shop-here').querySelector('.shop-here-name')?.textContent;

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('the shop under the page title (issue #680)', () => {
  it('names the shop in use and switches shop in one tap', () => {
    const choose = vi.fn(() => true);
    render(<Harness choose={choose} />);
    expect(shown()).toBe('Test Shop One (HQ)');
    const select = screen.getByRole('combobox', { name: 'Shop: Test Shop One (HQ). Switch shop' });
    expect([...select.querySelectorAll('option')].map((o) => o.textContent)).toEqual(['All branches', 'Test Shop One (HQ)', 'Test Shop Two (SHOP2)']);
    fireEvent.change(select, { target: { value: 'b-two' } });
    expect(choose).toHaveBeenCalledWith('b-two');
  });

  it('shows a lone shop as plain words with nothing to switch, and All branches by name', () => {
    render(<Harness who={one} choose={() => true} />);
    expect(screen.getByTestId('shop-here').textContent).toBe('Test Shop One (HQ)');
    expect(screen.queryByRole('combobox')).toBeNull();
    cleanup();
    render(<Harness branch={ALL_BRANCHES} choose={() => true} />);
    expect(shown()).toBe('All branches');
  });

});

describe('the toast on a change of shop (issue #680)', () => {
  it('says where the person now is, politely, in the dark neutral toast, for four seconds', () => {
    vi.useFakeTimers();
    render(<Harness choose={() => true} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'b-two' } });
    const toast = screen.getByRole('status');
    expect(toast.textContent).toBe('You are now in Test Shop Two (SHOP2).');
    expect(toast.getAttribute('aria-live')).toBe('polite');
    expect(toast.className).toBe('toast toast-info');
    act(() => void vi.advanceTimersByTime(TOAST_MS));
    expect(screen.queryByRole('status')).toBeNull();
  });

  // JUSTIFICATION-A3: a new test case for the one-toast rule of round 3, not a wrapper around a small change.
  it('shows one toast at a time: a switch replaces the screen toast, and the next screen toast replaces the switch', () => {
    let screenSays: ((message: string, tone: ToastTone) => void) | undefined;
    function ScreenWithToast() {
      const { show, toast } = useToast();
      screenSays = show;
      return toast;
    }
    render(
      <>
        <ScreenWithToast />
        <Harness choose={() => true} />
      </>,
    );
    act(() => screenSays?.('Could not save the count.', 'error'));
    expect(screen.getByRole('alert').textContent).toBe('Could not save the count.');
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'b-two' } });
    expect(screen.queryByRole('alert')).toBeNull();
    expect(document.querySelectorAll('.toast')).toHaveLength(1);
    expect(screen.getByRole('status').textContent).toBe('You are now in Test Shop Two (SHOP2).');
    act(() => screenSays?.('Stock corrected.', 'success'));
    expect([...document.querySelectorAll('.toast')].map((t) => t.textContent)).toEqual(['Stock corrected.']);
  });

  it('says nothing when a screen holds the branch, or the same shop is chosen again', () => {
    render(<Harness choose={() => false} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'b-two' } });
    expect(screen.queryByRole('status')).toBeNull();
    cleanup();
    const choose = vi.fn(() => true);
    render(<Harness choose={choose} />);
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'b-one' } });
    expect(choose).not.toHaveBeenCalled();
    expect(screen.queryByRole('status')).toBeNull();
  });

  it('words All branches as a view, not a place', () => {
    expect(switchedTo(me, ALL_BRANCHES)).toBe('You now see all branches.');
    expect(switchedTo(me, 'b-two')).toBe('You are now in Test Shop Two (SHOP2).');
  });
});
