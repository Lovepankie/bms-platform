// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { Sheet } from './Sheet';
import { announceToSheet } from './announce';
import { Toast } from '../../areas/staff/retail/toast';

// The one sheet (#520): modal, focus inside and trapped, Escape, the close button, the dimmed page and a swipe down close it.

afterEach(cleanup);

function Example() {
  const [open, setOpen] = useState(false);
  return (
    <>
      <main>
        <button type="button" onClick={() => setOpen(true)}>
          Choose a payment
        </button>
        <p className="toast" role="status" aria-live="polite">
          Added Sample soap
        </p>
      </main>
      <Sheet
        open={open}
        onClose={() => setOpen(false)}
        title="How did the buyer pay?"
        actions={
          <>
            <button type="button" className="btn-primary" onClick={() => setOpen(false)}>
              Use cash
            </button>
            <button type="button" onClick={() => setOpen(false)}>
              Cancel
            </button>
          </>
        }
      >
        <p>Pick one.</p>
        <button type="button">Mobile money</button>
      </Sheet>
    </>
  );
}

function openIt() {
  render(<Example />);
  const opener = screen.getByRole('button', { name: 'Choose a payment' });
  opener.focus();
  fireEvent.click(opener);
  return opener;
}

const dialog = () => screen.queryByRole('dialog', { name: 'How did the buyer pay?' });

describe('Sheet', () => {
  it('opens as a named modal dialog with focus on its first control and the page behind hidden', () => {
    openIt();
    expect(dialog()).toBeTruthy();
    expect(document.activeElement?.textContent).toBe('Mobile money');
    // Radix hides the rest of the page from a screen reader while the sheet is open, but never a live region: a toast is heard.
    const opener = [...document.querySelectorAll('main button')].find((b) => b.textContent === 'Choose a payment')!;
    expect(opener.closest('[aria-hidden="true"]')).toBeTruthy();
    expect(screen.getByText('Added Sample soap').closest('[aria-hidden="true"]')).toBeNull();
  });

  it('says again, inside the sheet, a toast that appears after it opened under the hidden page', async () => {
    function Paying() {
      const [open, setOpen] = useState(true);
      const [toast, setToast] = useState<string | null>(null);
      return (
        <>
          <main>{toast && <Toast message={toast} tone="error" />}</main>
          <Sheet open={open} onClose={() => setOpen(false)} title="How did the buyer pay?">
            <button type="button" onClick={() => setToast('Mobile money is not set up for this shop.')}>
              Mobile money
            </button>
          </Sheet>
        </>
      );
    }
    render(<Paying />);
    fireEvent.click(screen.getByRole('button', { name: 'Mobile money' }));
    const pill = document.querySelector('.toast')!;
    expect(pill.closest('[aria-hidden="true"]')).toBeTruthy();
    // An error is said again assertively, and the same words a second time are said again (a new node in the region).
    const relay = dialog()!.querySelector('[aria-live="assertive"]')!;
    await waitFor(() => expect(relay.textContent).toBe('Mobile money is not set up for this shop.'));
    const first = relay.firstElementChild;
    act(() => announceToSheet('Mobile money is not set up for this shop.', null, true));
    expect(relay.textContent).toBe('Mobile money is not set up for this shop.');
    expect(relay.firstElementChild).not.toBe(first);
    act(() => announceToSheet('Added Sample soap', null));
    expect(dialog()!.querySelector('[aria-live="polite"]')!.textContent).toBe('Added Sample soap');
    expect(relay.textContent).toBe('');
  });

  it('keeps Tab inside, both ways', () => {
    openIt();
    const close = screen.getByRole('button', { name: 'Close' });
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    cancel.focus();
    fireEvent.keyDown(cancel, { key: 'Tab' });
    expect(document.activeElement).toBe(close);
    fireEvent.keyDown(close, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(cancel);
  });

  it('closes on Escape and gives focus back to what opened it, with the page live again', async () => {
    const opener = openIt();
    fireEvent.keyDown(document.activeElement!, { key: 'Escape' });
    expect(dialog()).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(opener));
    expect(document.querySelector('[aria-hidden="true"] main')).toBeNull();
  });

  it('closes from the close button and from a tap on the dimmed page', async () => {
    openIt();
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(dialog()).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Choose a payment' }));
    // Radix listens for a tap outside from the next task after it opens.
    await act(() => new Promise((done) => setTimeout(done, 0)));
    const scrim = document.querySelector('.sheet-scrim')!;
    fireEvent.pointerDown(scrim);
    fireEvent.pointerUp(scrim);
    fireEvent.click(scrim);
    await waitFor(() => expect(dialog()).toBeNull());
  });

  it('closes on a swipe down its top, and springs back from a short one', () => {
    openIt();
    const head = document.querySelector('.sheet-head')!;
    fireEvent.pointerDown(head, { clientY: 100, pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerMove(head, { clientY: 140, pointerId: 1 });
    expect((dialog() as HTMLElement).style.transform).toBe('translateY(40px)');
    fireEvent.pointerUp(head, { clientY: 140, pointerId: 1 });
    expect((dialog() as HTMLElement).style.transform).toBe('');
    expect(dialog()).toBeTruthy();
    fireEvent.pointerDown(head, { clientY: 100, pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerMove(head, { clientY: 260, pointerId: 1 });
    fireEvent.pointerUp(head, { clientY: 260, pointerId: 1 });
    expect(dialog()).toBeNull();
  });

  it('springs back, and stays open, when the browser cancels a long drag', () => {
    openIt();
    const head = document.querySelector('.sheet-head')!;
    fireEvent.pointerDown(head, { clientY: 100, pointerId: 1, pointerType: 'touch' });
    fireEvent.pointerMove(head, { clientY: 300, pointerId: 1 });
    fireEvent.pointerCancel(head, { pointerId: 1 });
    expect(dialog()).toBeTruthy();
    expect((dialog() as HTMLElement).style.transform).toBe('');
  });

  it.each(['mouse', 'pen'])('never closes on a %s drag, only a touch', (pointerType) => {
    openIt();
    const head = document.querySelector('.sheet-head')!;
    fireEvent.pointerDown(head, { clientY: 100, pointerId: 1, pointerType });
    fireEvent.pointerMove(head, { clientY: 300, pointerId: 1 });
    fireEvent.pointerUp(head, { clientY: 300, pointerId: 1 });
    expect(dialog()).toBeTruthy();
  });

  it('draws nothing while closed', () => {
    render(<Example />);
    expect(dialog()).toBeNull();
    expect(document.querySelector('.sheet-layer')).toBeNull();
  });
});
