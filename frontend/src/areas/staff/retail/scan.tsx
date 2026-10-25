import { useQueryClient } from '@tanstack/react-query';
import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { retail, type Product } from '../../../api/retail';
import { useOffline } from '../../../offline/offline-context';
import { icons } from '../../../components/icons';
import { useStaff } from '../context';
import { findByBarcodeIn, spreadsheetDamaged } from './barcode-state';
import { holds } from './permissions';
import { Problem, ProductPicker } from './ui';

// Scanning on the sale, restock, move stock and stock count forms (ADR-070, issue #345; FR-RET-68). Three ways in,
// one path out: a USB or Bluetooth scanner that types the code into the Scan box and presses Enter, a code typed there
// by hand, or the phone camera. Each looks the code up and hands the form the item and the pack the barcode is on; the
// form adds a line of one or adds one to the line it has. A scanner is read only through the Scan box (D7): keys typed
// into a quantity, price or count field are never watched, so they are never taken for a scan.

const CameraScanner = lazy(() => import('./camera-scan'));

/** The camera can be offered: the browser can open it (an https page on a phone or computer with a camera API). */
export function cameraSupported(): boolean {
  return typeof navigator !== 'undefined' && typeof navigator.mediaDevices?.getUserMedia === 'function';
}

/** The page is on a plain http address, where a browser never offers the camera. */
export function insecureAddress(): boolean {
  return typeof window !== 'undefined' && window.isSecureContext === false;
}

/** A mouse or trackpad is the main pointer (a till or a laptop, not a phone); true where the browser cannot say. */
function finePointer(): boolean {
  try {
    return window.matchMedia('(pointer: fine)').matches;
  } catch {
    return true;
  }
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

/** The signs checkBarcode refuses in a stored code, which also cannot travel in the lookup's path. */
const UNSTORABLE = /[%;?#/\\]/;

type Outcome = { kind: 'added'; name: string } | { kind: 'refused'; message: string } | { kind: 'unknown'; code: string } | { kind: 'failed'; error: unknown } | null;

/**
 * The Scan box and the camera button over a form's lines. `onFound` gets the item, with its balance at `branchId` when
 * given, and the pack the barcode is on (absent for the item's own unit); it returns a message instead when the form
 * cannot take the scan (a pack it cannot use, an item not on the sheet), shown in place of Added. A form that raises its own
 * toast for the add (`toasts`, issue #498) says Added there, so it is seen and heard once, with the quantity the line now has.
 */
export function ScanBar({ id, branchId, onFound, autoFocus = false, toasts = false }: { id: string; branchId?: string; onFound: (product: Product, unitId?: string) => string | void; autoFocus?: boolean; toasts?: boolean }) {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const offline = useOffline();
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<Outcome>(null);
  const [camera, setCamera] = useState(false);
  const [assigning, setAssigning] = useState(false);
  const [assignError, setAssignError] = useState<unknown>(null);
  // As on the item form: a code that looks spreadsheet-damaged (a GTIN-14, a UPC-E) is added only as printed (Code 128).
  const [asPrinted, setAsPrinted] = useState(false);
  const canAssign = holds(me, 'retail.catalogue.manage');
  // A scanner types into the Scan box only (ADR-070, D7). With autoFocus the cursor starts there, so the first scan lands
  // in it, but only where there is a mouse or trackpad (a till or a laptop): on a phone it would open the keyboard.
  const box = useRef<HTMLInputElement>(null);
  const holdFocus = autoFocus && finePointer();
  useEffect(() => {
    if (holdFocus) box.current?.focus();
  }, [holdFocus]);
  // After a scan the box keeps the cursor for the next one, unless the person has since moved it to another field.
  const refocus = () => {
    const at = document.activeElement;
    if (at === box.current || ((at === null || at === document.body) && (holdFocus || finePointer()))) box.current?.focus();
  };

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
    setAsPrinted(false);
    // A foreign Code 128 with a sign no barcode here may hold would be refused in the address; no item can have it.
    if (UNSTORABLE.test(code)) {
      setBusy(false);
      feedback(false);
      setOutcome({ kind: 'unknown', code });
      refocus();
      return;
    }
    try {
      const match = await retail.findByBarcode(code, branchId);
      if (match?.product) found(match.product, match.unit_id);
      else {
        feedback(false);
        setOutcome({ kind: 'unknown', code });
      }
    } catch (error) {
      // No answer from the server (offline): the items this screen has already read carry their barcodes.
      // The phone's offline catalogue (issue #458) holds the items of each selling branch with their barcodes too.
      const onPhone = offline?.catalogue?.products ?? {};
      const cached = [
        ...(branchId ? onPhone[branchId] ?? [] : Object.values(onPhone).flat()),
        ...queryClient.getQueriesData<Product[] | { items?: Product[] }>({ queryKey: ['retail', 'products'] })
          .flatMap(([, data]) => (Array.isArray(data) ? data : data?.items ?? [])),
      ];
      const hit = error instanceof TypeError ? findByBarcodeIn(cached, code) : null;
      if (hit) found(hit.product, hit.unitId);
      else {
        feedback(false);
        setOutcome({ kind: 'failed', error });
      }
    } finally {
      setBusy(false);
      refocus();
    }
  };

  const assign = async (product: Product) => {
    if (outcome?.kind !== 'unknown') return;
    setAssignError(null);
    try {
      await retail.addBarcode(product.id ?? '', { code: outcome.code, ...(asPrinted ? { format: 'code128' } : {}) });
      void queryClient.invalidateQueries({ queryKey: ['retail', 'products'] });
      setAssigning(false);
      await lookUp(outcome.code);
    } catch (error) {
      setAssignError(error);
    }
  };

  return (
    <div className="scan-bar" data-tour="scan-bar">
      <label htmlFor={id}>Scan here</label>
      <div className="rt-row">
        <input
          id={id}
          ref={box}
          data-scan-box=""
          autoComplete="off"
          autoCorrect="off"
          autoCapitalize="off"
          spellCheck={false}
          enterKeyHint="search"
          placeholder="Scan or type a barcode, then Enter"
          aria-describedby={`${id}-hint`}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key !== 'Enter') return;
            // Enter here looks the code up; it never submits the form around the box.
            e.preventDefault();
            const code = text;
            setText('');
            void lookUp(code);
          }}
        />
        {cameraSupported() && (
          <button type="button" className="scan-camera" aria-label="Scan with the camera" title="Scan with the camera" onClick={() => setCamera(true)} data-tour="scan-camera">
            {icons.scan}
          </button>
        )}
      </div>
      <p id={`${id}-hint`} className="hint">A barcode scanner types into this box. You can also type the barcode and press Enter.</p>
      {!cameraSupported() && insecureAddress() && <p className="hint">The camera works only on the secure (https) address.</p>}
      <p aria-live="polite" className="hint">
        {busy ? 'Looking up' : outcome?.kind === 'added' && !toasts ? `Added ${outcome.name}.` : ''}
      </p>
      {outcome?.kind === 'unknown' && (
        <div role="alert" className="alert alert-warning" data-testid="scan-unknown">
          <p>No item has the barcode {outcome.code}.{canAssign ? '' : ' Ask an administrator to add it to the item, or find the item by name above.'}</p>
          {canAssign && !assigning && !UNSTORABLE.test(outcome.code) && (
            <button type="button" onClick={() => setAssigning(true)}>
              Add this barcode to a product
            </button>
          )}
          {canAssign && assigning && (
            <>
              {spreadsheetDamaged(outcome.code) && (
                <label>
                  <input type="checkbox" style={{ width: 'auto', minHeight: 24, marginRight: 8 }} checked={asPrinted} onChange={(e) => setAsPrinted(e.target.checked)} /> This code is exactly as printed (Code 128)
                </label>
              )}
              <ProductPicker id={`${id}-assign`} label="Find the item this barcode belongs to" branchId={branchId} addLabel="Choose" onAdd={(p) => void assign(p)} />
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
