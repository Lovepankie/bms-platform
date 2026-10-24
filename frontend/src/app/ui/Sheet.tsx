import * as Dialog from '@radix-ui/react-dialog';
import { useEffect, useLayoutEffect, useRef, useState, type PointerEvent, type ReactNode } from 'react';
import { icons } from '../../components/icons';
import { hearToasts } from './announce';

// The one sheet (#520): pickers and confirmations open in it. It is a Radix Dialog styled by our own tokens. On a phone (under
// 720px) it rises from the bottom edge and a swipe down on its top, by touch only, closes it; on a wider
// screen it is a centred dialog. Radix makes it modal: focus is trapped inside, Escape, a tap on the dimmed page and the close
// button close it, the page does not scroll, and focus goes back to what opened it. The rest of the page is hidden from a screen
// reader, but never a live region (aria-live), so a toast already on the page is still heard; a toast that appears after the
// sheet opened lands under that hiding, so the toast tells the sheet (announceToSheet in announce.ts) and the sheet says it again in a live
// region of its own. Focus starts on the first control of the
// content (not the close button). The screen owns `open` and closes it in `onClose`. The look is .sheet-layer in
// src/app/ui/kit.css.

const FOCUSABLE = 'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex]:not([tabindex="-1"])';
// How far down the top must be dragged, in px, to close the sheet rather than spring back.
const SWIPE_CLOSE = 96;

export function Sheet({ open, onClose, title, children, actions }: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  /** The buttons at the foot, the primary one first. */
  actions?: ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);
  // What had focus when the sheet opened, given it back on close (Radix gives it back only to a Dialog.Trigger).
  const opener = useRef<HTMLElement | null>(null);
  // Taken when the open is committed, before Radix moves focus inside (its focus runs in a later effect), never in a render that
  // might be thrown away.
  useLayoutEffect(() => {
    if (open) opener.current = document.activeElement as HTMLElement | null;
  }, [open]);
  const drag = useRef<{ from: number; by: number } | null>(null);
  // The last toast said again, numbered so the same words twice are said twice; an error is said assertively.
  const [heard, setHeard] = useState<{ text: string; n: number; urgent: boolean } | null>(null);

  useEffect(() => {
    if (!open) return;
    const hear = (message: string, from: Element | null, urgent: boolean) => {
      if (!from || from.closest('[aria-hidden="true"]')) setHeard((was) => ({ text: message, n: (was?.n ?? 0) + 1, urgent }));
    };
    const stop = hearToasts(hear);
    return () => {
      stop();
      setHeard(null);
    };
  }, [open]);

  const grab = (e: PointerEvent<HTMLElement>) => {
    if (e.pointerType !== 'touch' || (e.target as HTMLElement).closest('button')) return;
    drag.current = { from: e.clientY, by: 0 };
    e.currentTarget.setPointerCapture?.(e.pointerId);
  };
  const pull = (e: PointerEvent<HTMLElement>) => {
    if (!drag.current || !panel.current) return;
    drag.current.by = Math.max(0, e.clientY - drag.current.from);
    panel.current.style.transform = drag.current.by ? `translateY(${drag.current.by}px)` : '';
  };
  // A finger lifted far enough down closes the sheet; a gesture the browser cancelled (a scroll took over) only springs back.
  const release = (cancelled: boolean) => {
    if (!drag.current || !panel.current) return;
    const far = !cancelled && drag.current.by >= SWIPE_CLOSE;
    drag.current = null;
    panel.current.style.transform = '';
    if (far) onClose();
  };

  return (
    <Dialog.Root open={open} onOpenChange={(next) => next || onClose()}>
      <Dialog.Portal>
        <div className="sheet-layer">
          <Dialog.Overlay className="sheet-scrim" />
          <Dialog.Content
            ref={panel}
            className="sheet-panel"
            aria-describedby={undefined}
            onCloseAutoFocus={(e) => {
              e.preventDefault();
              opener.current?.focus?.();
            }}
            onOpenAutoFocus={(e) => {
              // The first control of the content, then of the actions; the sheet itself when there is none.
              const parts = [...(panel.current?.querySelectorAll('.sheet-body, .sheet-actions') ?? [])];
              const first = parts.map((part) => part.querySelector<HTMLElement>(FOCUSABLE)).find(Boolean);
              e.preventDefault();
              (first ?? panel.current)?.focus();
            }}
          >
            <header className="sheet-head" onPointerDown={grab} onPointerMove={pull} onPointerUp={() => release(false)} onPointerCancel={() => release(true)}>
              <span className="sheet-grab" aria-hidden="true" />
              <Dialog.Title>{title}</Dialog.Title>
              <Dialog.Close className="sheet-close" aria-label="Close">
                {icons.close}
              </Dialog.Close>
            </header>
            <p className="visually-hidden" role="status" aria-live="polite">
              {heard && !heard.urgent && <span key={heard.n}>{heard.text}</span>}
            </p>
            <p className="visually-hidden" role="alert" aria-live="assertive">
              {heard?.urgent && <span key={heard.n}>{heard.text}</span>}
            </p>
            <div className="sheet-body">{children}</div>
            {actions && <div className="sheet-actions">{actions}</div>}
          </Dialog.Content>
        </div>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
