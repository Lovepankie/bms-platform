import { useQueryClient } from '@tanstack/react-query';
import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { retail, type Product } from '../../../api/retail';
import { useStaff } from '../context';
import { createWedge, findByBarcodeIn } from './barcode-state';
import { holds } from './permissions';
import { Problem, ProductPicker } from './ui';

// Scanning on the sale, restock, move stock and stock count forms (ADR-070, issue #345; FR-RET-68). Three ways in,
// one path out: a USB or Bluetooth scanner that types the code and presses Enter (anywhere on the screen, or in the
// scan box), a code typed in the scan box, or the phone camera. Each looks the code up and hands the form the item
// and the pack the barcode is on; the form adds a line of one or adds one to the line it has.

const CameraScanner = lazy(() => import('./camera-scan'));

/** The camera can be offered: the browser can open it (an https page on a phone or computer with a camera API). */
export function cameraSupported(): boolean {
  return typeof navigator !== 'undefined' && typeof navigator.mediaDevices?.getUserMedia === 'function';
}

/** A short beep and a buzz on a phone that can, so a scan is noticed without looking; silent where it cannot. */
function feedback(ok: boolean) {
  try {
    navigator.vibrate?.(ok ? 40 : [60, 60, 60]);
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const tone = ctx.createOscillator();
    tone.frequency.value = ok ? 1200 : 300;
    tone.connect(ctx.destination);
    tone.start();
    tone.stop(ctx.currentTime + (ok ? 0.08 : 0.25));
    tone.onended = () => void ctx.close();
  } catch {
    // Sound and vibration are a courtesy; a browser that refuses them still scans.
  }
}

/** True while focus is in a field a person types into, where a key belongs to that field unless it is a scanner burst. */
function editable(target: EventTarget | null): target is HTMLInputElement | HTMLTextAreaElement {
  return target instanceof HTMLTextAreaElement || (target instanceof HTMLInputElement && !['checkbox', 'radio', 'button', 'submit'].includes(target.type));
}

/** Puts a value into a React controlled input as if typed, so its onChange runs. */
function setFieldValue(field: HTMLInputElement | HTMLTextAreaElement, value: string) {
  const proto = field instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value')?.set?.call(field, value);
  field.dispatchEvent(new Event('input', { bubbles: true }));
}

/** A text field as it was before a burst's first key: its value and selection, put back when the burst is a scan. */
type FieldSnapshot = { field: HTMLInputElement | HTMLTextAreaElement; value: string; start: number | null; end: number | null };

function restore(snap: FieldSnapshot) {
  if (snap.field.value !== snap.value) setFieldValue(snap.field, snap.value);
  try {
    if (snap.start !== null && snap.end !== null) snap.field.setSelectionRange(snap.start, snap.end);
  } catch {
    // A field type without a selection (number, email) keeps just its value.
  }
}

/**
 * A keyboard-wedge scanner anywhere on the screen: a burst of keys faster than a person types, closed by Enter. In a
 * text field the value and selection from before the burst's first key are put back, so a scan with the cursor in a
 * quantity box never changes the quantity, wherever the cursor was, whatever was selected and whatever its onChange did.
 */
export function useWedgeScan(onScan: (code: string) => void, enabled = true) {
  const handler = useRef(onScan);
  handler.current = onScan;
  useEffect(() => {
    if (!enabled) return undefined;
    const wedge = createWedge();
    let before: FieldSnapshot | null = null;
    const down = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (e.key === 'Enter') {
        const code = wedge.enter(e.timeStamp || performance.now());
        if (code === null) return;
        // The scan box handles its own Enter.
        if (e.target instanceof HTMLElement && e.target.dataset.scanBox !== undefined) return;
        e.preventDefault();
        e.stopPropagation();
        if (before && before.field === e.target) restore(before);
        before = null;
        handler.current(code);
      } else if (e.key.length === 1) {
        const t = e.target;
        if (wedge.key(e.key, e.timeStamp || performance.now())) before = editable(t) ? { field: t, value: t.value, start: t.selectionStart, end: t.selectionEnd } : null;
      }
    };
    document.addEventListener('keydown', down, true);
    return () => document.removeEventListener('keydown', down, true);
  }, [enabled]);
}

type Outcome = { kind: 'added'; name: string } | { kind: 'refused'; message: string } | { kind: 'unknown'; code: string } | { kind: 'failed'; error: unknown } | null;

/**
 * The scan box and the camera button over a form's lines. `onFound` gets the item, with its balance at `branchId` when
 * given, and the pack the barcode is on (absent for the item's own unit); it returns a message instead when the form
 * cannot take the scan (a pack it cannot use, an item not on the sheet), shown in place of Added.
 */
export function ScanBar({ id, branchId, onFound }: { id: string; branchId?: string; onFound: (product: Product, unitId?: string) => string | void }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<Outcome>(null);
  const [camera, setCamera] = useState(false);
  const [assigning, setAssigning] = useState(false);
  const [assignError, setAssignError] = useState<unknown>(null);
  const canAssign = holds(me, 'retail.catalogue.manage');

  const found = (product: Product, unitId?: string) => {
    const refused = onFound(product, unitId);
    feedback(!refused);
    setOutcome(refused ? { kind: 'refused', message: refused } : { kind: 'added', name: product.description ?? product.code ?? '' });
  };

  const lookUp = async (raw: string) => {
    const code = raw.trim();
    if (code === '') return;
    setBusy(true);
    setAssigning(false);
    try {
      const match = await retail.findByBarcode(code, branchId);
      if (match?.product) found(match.product, match.unit_id);
      else {
        feedback(false);
        setOutcome({ kind: 'unknown', code });
      }
    } catch (error) {
      // No answer from the server (offline): the items this screen has already read carry their barcodes.
      const cached = queryClient.getQueriesData<Product[] | { items?: Product[] }>({ queryKey: ['retail', 'products'] })
        .flatMap(([, data]) => (Array.isArray(data) ? data : data?.items ?? []));
      const hit = error instanceof TypeError ? findByBarcodeIn(cached, code) : null;
      if (hit) found(hit.product, hit.unitId);
      else {
        feedback(false);
        setOutcome({ kind: 'failed', error });
      }
    } finally {
      setBusy(false);
    }
  };

  useWedgeScan((code) => void lookUp(code), !camera);

  const assign = async (product: Product) => {
    if (outcome?.kind !== 'unknown') return;
    setAssignError(null);
    try {
      await retail.addBarcode(product.id ?? '', { code: outcome.code });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'products'] });
      setAssigning(false);
      await lookUp(outcome.code);
    } catch (error) {
      setAssignError(error);
    }
  };

  return (
    <div className="scan-bar" data-tour="scan-bar">
      <label htmlFor={id}>Scan or type a barcode, then press Enter</label>
      <div className="rt-row">
        <input
          id={id}
          data-scan-box=""
          autoComplete="off"
          enterKeyHint="search"
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key !== 'Enter') return;
            e.preventDefault();
            const code = text;
            setText('');
            void lookUp(code);
          }}
        />
        {cameraSupported() && (
          <button type="button" onClick={() => setCamera(true)} data-tour="scan-camera">
            Scan
          </button>
        )}
      </div>
      <p aria-live="polite" className="hint">
        {busy ? 'Looking up' : outcome?.kind === 'added' ? `Added ${outcome.name}.` : ''}
      </p>
      {outcome?.kind === 'unknown' && (
        <div role="alert" className="alert alert-warning" data-testid="scan-unknown">
          <p>No item has the barcode {outcome.code}.{canAssign ? '' : ' Ask an administrator to add it to the item, or find the item by name above.'}</p>
          {canAssign && !assigning && (
            <button type="button" onClick={() => setAssigning(true)}>
              Add this barcode to a product
            </button>
          )}
          {canAssign && assigning && (
            <>
              <ProductPicker id={`${id}-assign`} branchId={branchId} addLabel="Choose" onAdd={(p) => void assign(p)} />
              <Problem error={assignError} />
            </>
          )}
        </div>
      )}
      {outcome?.kind === 'refused' && <p role="alert" className="alert alert-warning" data-testid="scan-refused">{outcome.message}</p>}
      {outcome?.kind === 'failed' && <Problem error={outcome.error} />}
      {camera && (
        <Suspense fallback={<p role="status">Opening the camera</p>}>
          <CameraScanner
            onCode={(code) => {
              setCamera(false);
              void lookUp(code);
            }}
            onClose={() => setCamera(false)}
          />
        </Suspense>
      )}
    </div>
  );
}
