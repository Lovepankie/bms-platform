import { useEffect, useRef, useState } from 'react';

// The phone camera as a barcode reader (ADR-070, issue #345). Loaded only when Scan is tapped, so it never weighs on
// the first load. The browser's own BarcodeDetector reads the frames where it exists (Chrome and Edge on Android);
// elsewhere (Safari on an iPhone) Quagga2 (MIT), loaded on demand, reads a still of the frame. EAN-13, EAN-8, UPC-A and
// Code 128 only, the formats an item accepts.

const FORMATS = ['ean_13', 'ean_8', 'upc_a', 'code_128'];
const READERS = ['ean_reader', 'ean_8_reader', 'upc_reader', 'code_128_reader'] as const;
const EVERY_MS = 250;

type Detector = { detect(source: CanvasImageSource): Promise<{ rawValue: string }[]> };
type DetectorClass = new (options: { formats: string[] }) => Detector;

/** Why the camera did not open, in plain words for the person holding the phone. */
export function cameraProblem(error: unknown): string {
  const name = error instanceof Error || (typeof error === 'object' && error !== null && 'name' in error) ? (error as { name: string }).name : '';
  switch (name) {
    case 'NotAllowedError':
    case 'PermissionDeniedError':
      return 'The camera is blocked for this app. Allow the camera for this site in the browser settings (on an iPhone: Settings, Safari, Camera), then tap Scan again.';
    case 'NotFoundError':
    case 'OverconstrainedError':
      return 'This device has no camera the app can use. Use a scanner or type the barcode instead.';
    case 'NotReadableError':
    case 'AbortError':
      return 'Another app is using the camera. Close it and tap Scan again.';
    case 'SecurityError':
      return 'The camera works only when the app is opened on its secure (https) address.';
    default:
      return 'The camera could not be opened. Type the barcode instead, or try again.';
  }
}

/**
 * A misread frame can yield a wrong code, so a code counts only when two frames in a row read it: returns the code on the
 * second matching read, null otherwise (a frame with nothing, or a different code, starts again).
 */
export function twoFramesInARow(): (code: string | null) => string | null {
  let last: string | null = null;
  return (code) => {
    const sure = code !== null && code === last ? code : null;
    last = sure ? null : code;
    return sure;
  };
}

/** A reader of one frame: the browser's BarcodeDetector, or the Quagga2 fallback loaded now. */
async function makeReader(): Promise<(frame: HTMLCanvasElement) => Promise<string | null>> {
  const Native = (window as unknown as { BarcodeDetector?: DetectorClass & { getSupportedFormats?: () => Promise<string[]> } }).BarcodeDetector;
  if (Native) {
    const supported = (await Native.getSupportedFormats?.()) ?? FORMATS;
    const formats = FORMATS.filter((f) => supported.includes(f));
    if (formats.length > 0) {
      const detector = new Native({ formats });
      return async (frame) => (await detector.detect(frame))[0]?.rawValue ?? null;
    }
  }
  const module = await import('@ericblade/quagga2');
  const Quagga = (module as unknown as { default?: typeof module.default }).default ?? (module as unknown as typeof module.default);
  return (frame) =>
    new Promise((resolve) => {
      Quagga.decodeSingle(
        { src: frame.toDataURL('image/jpeg', 0.8), numOfWorkers: 0, locate: true, inputStream: { size: 800 }, decoder: { readers: [...READERS] } },
        (result) => resolve(result?.codeResult?.code ?? null),
      );
    });
}

export default function CameraScanner({ onCode, onClose }: { onCode: (code: string) => void; onClose: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const [problem, setProblem] = useState<string | null>(null);
  // The parent's callback changes on every render; the camera opens once.
  const found = useRef(onCode);
  found.current = onCode;

  useEffect(() => {
    let stream: MediaStream | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let stopped = false;
    const canvas = document.createElement('canvas');
    const stop = () => {
      stopped = true;
      if (timer !== null) clearTimeout(timer);
      stream?.getTracks().forEach((t) => t.stop());
    };
    (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' }, audio: false });
        if (stopped || !video.current) return stop();
        video.current.srcObject = stream;
        await video.current.play().catch(() => undefined);
        const read = await makeReader();
        const confirm = twoFramesInARow();
        const tick = async () => {
          if (stopped || !video.current) return;
          const v = video.current;
          if (v.videoWidth > 0) {
            canvas.width = v.videoWidth;
            canvas.height = v.videoHeight;
            canvas.getContext('2d')?.drawImage(v, 0, 0);
            const code = confirm(await read(canvas).catch(() => null));
            if (code && !stopped) {
              stop();
              found.current(code);
              return;
            }
          }
          timer = setTimeout(() => void tick(), EVERY_MS);
        };
        void tick();
      } catch (error) {
        stop();
        setProblem(cameraProblem(error));
      }
    })();
    return stop;
  }, []);

  return (
    <div role="dialog" aria-modal="true" aria-label="Scan with the camera" className="camera-scan">
      {problem ? (
        <p role="alert" className="alert alert-danger">{problem}</p>
      ) : (
        <>
          <video ref={video} muted playsInline aria-label="Camera view" />
          <p className="hint">Hold the barcode inside the frame, about a hand away.</p>
        </>
      )}
      <button type="button" onClick={onClose}>Close the camera</button>
    </div>
  );
}
