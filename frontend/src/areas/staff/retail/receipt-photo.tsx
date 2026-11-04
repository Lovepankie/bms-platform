import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ChangeEvent } from 'react';
import { Hint } from '../../../app/ui/Hint';
import { Sheet } from '../../../app/ui/Sheet';
import { retail, type ReceiptKind } from '../../../api/retail';
import { useOnline } from '../../../offline/offline-context';
import { PHOTO_TYPES, photoProblem } from './item-photo';
import { sayDone } from './toast';

// A photo of the paper receipt on a sale, a delivery, a usage or damage entry and an expense (issue #819, ADR-124, FR-RET-89),
// as the owner's old app had. One optional button on each form, through the item photo's upload and limits; the form saves the
// entry first and sends the photo after, so a photo never holds a save. Offline no image is kept on the phone: the entry is
// kept without it and the form says the photo can be added from the entry once online. On a saved entry whoever sees it sees
// the photo as a small picture that opens full size.

/** What the form says with no network: the entry saves, the photo waits for the entry. */
export const RECEIPT_OFFLINE = 'No network. The entry saves without the photo. Add the photo from the entry once you are online.';

/** The owner's words (review of #846) when the photo did not go after the entry was saved: it is added from the record. */
export const RECEIPT_LATER = 'Saved. The photo did not go; add it from the record.';

/** The owner's words (second review of #846) when an expense with a photo is kept on the phone: the photo stays behind. */
export const RECEIPT_KEPT = 'Saved on this phone. Add the photo from the record once it is sent.';

/** The same, after the expense's own saved words. */
export const RECEIPT_LATER_NOTE = 'The photo did not go; add it from the record.';

/** The saved message with what happened to the photo after it, when it was not saved. */
export const withPhotoNote = (words: string, note: string | null): string => (note ? words + '. ' + note : words);

/** The longest a receipt photo is given to go up (review of #846): on a weak link it is stopped, never left to hold anything. */
export const RECEIPT_SEND_MS = 30_000;

/**
 * Runs `send` with an abort signal and settles within `ms` whatever the network does: the answer, or a rejection once the
 * time is up (the upload is aborted too).
 */
export function bounded<T>(send: (signal: AbortSignal) => Promise<T>, ms: number = RECEIPT_SEND_MS): Promise<T> {
  const stop = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  const late = new Promise<never>((_, reject) => {
    timer = setTimeout(() => { stop.abort(); reject(new Error('The receipt photo took too long to send.')); }, ms);
  });
  return Promise.race([send(stop.signal), late]).finally(() => clearTimeout(timer));
}

/** Sends the photo of an entry just saved, within the time limit; the words to say when it failed, or null. */
export async function sendReceiptPhoto(kind: ReceiptKind, recordId: string | undefined, file: File | null, ms: number = RECEIPT_SEND_MS): Promise<string | null> {
  if (!file || !recordId) return null;
  try {
    await bounded((signal) => retail.addReceiptPhoto(kind, recordId, file, signal), ms);
    return null;
  } catch {
    return RECEIPT_LATER;
  }
}

/**
 * The photo of an entry already saved and said, sent after the save so it never holds the form (review of #846): the next
 * entry can start at once, and only a photo that was not saved is said, in the app's toast.
 */
export function sendReceiptPhotoAfter(kind: ReceiptKind, recordId: string | undefined, file: File | null): void {
  if (!file || !recordId) return;
  void sendReceiptPhoto(kind, recordId, file).then((note) => {
    if (note) sayDone(note);
  });
}

/** The picture of a chosen or stored photo, drawn from a blob. */
function useBlobUrl(blob: Blob | null | undefined): string | null {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!blob || typeof URL.createObjectURL !== 'function') return setUrl(null);
    const made = URL.createObjectURL(blob);
    setUrl(made);
    return () => URL.revokeObjectURL(made);
  }, [blob]);
  return url;
}

/**
 * The form's Add receipt photo button: one button, the label of a file input, so a phone offers the camera or a saved picture.
 * Nothing is sent here; the form sends the file once the entry is saved. Offline the button gives way to the words.
 */
export function ReceiptPhotoChoice({ id, file, onChange }: { id: string; file: File | null; onChange: (file: File | null) => void }) {
  const offlineMode = !useOnline();
  const [problem, setProblem] = useState<string | null>(null);
  const preview = useBlobUrl(file);
  // A photo chosen before the network went is not sent with an entry kept on the phone.
  useEffect(() => {
    if (offlineMode && file) onChange(null);
  }, [offlineMode, file, onChange]);
  if (offlineMode) {
    return <p className="hint" role="status" data-testid={`${id}-offline`}>{RECEIPT_OFFLINE}</p>;
  }
  const pick = (e: ChangeEvent<HTMLInputElement>) => {
    const chosen = e.target.files?.[0];
    e.target.value = '';
    if (!chosen) return;
    const why = photoProblem(chosen);
    setProblem(why);
    onChange(why === null ? chosen : null);
  };
  return (
    <div className="photo-choice receipt-photo-choice" role="group" aria-label="Receipt photo" data-testid={id}>
      {preview && <img className="rt-photo" src={preview} alt="The receipt photo chosen" width={64} height={64} style={{ objectFit: 'cover', borderRadius: 6 }} />}
      <p className="cluster">
        <label className="btn" htmlFor={`${id}-file`}>
          <input id={`${id}-file`} type="file" accept={PHOTO_TYPES} className="visually-hidden" onChange={pick} />
          {file ? 'Change receipt photo' : 'Add receipt photo'}
        </label>
        {file && <button type="button" className="btn-ghost" onClick={() => { setProblem(null); onChange(null); }}>Take the photo off</button>}
      </p>
      <Hint full>Optional. A photo of the paper receipt, PNG, JPEG or WebP up to 5 MB.</Hint>
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
    </div>
  );
}

/**
 * The receipt photo on a saved entry: a small picture that opens full size, or, when the entry has none and the person may
 * add one, the Add receipt photo button, which sends it at once. Offline it says the photo can be added once online.
 */
export function ReceiptPhoto({ kind, recordId, has, canAdd }: { kind: ReceiptKind; recordId?: string; has?: boolean | null; canAdd: boolean }) {
  const queryClient = useQueryClient();
  const offlineMode = !useOnline();
  const [open, setOpen] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const id = recordId ?? '';
  const photo = useQuery({
    queryKey: ['retail', 'receipt-photo', kind, id],
    queryFn: ({ signal }) => retail.receiptPhoto(kind, id, signal),
    enabled: has === true && id !== '',
    staleTime: 5 * 60_000,
    retry: 1,
  });
  const url = useBlobUrl(photo.data);
  const add = useMutation({
    mutationFn: (file: File) => retail.addReceiptPhoto(kind, id, file),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['retail'] }),
    meta: { success: () => 'Receipt photo saved' },
  });
  if (has) {
    if (!url) return photo.isError ? <p className="hint">The receipt photo could not be shown.</p> : null;
    return (
      <>
        <button type="button" className="btn-ghost receipt-photo-open" aria-label="Open the receipt photo" onClick={() => setOpen(true)} data-testid={`receipt-photo-${id}`}>
          <img className="rt-photo" src={url} alt="Receipt photo" width={48} height={48} style={{ objectFit: 'cover', borderRadius: 6 }} />
          Receipt photo
        </button>
        <Sheet open={open} onClose={() => setOpen(false)} title="Receipt photo" tall>
          <img src={url} alt="Receipt photo, full size" style={{ width: '100%', height: 'auto' }} />
        </Sheet>
      </>
    );
  }
  if (!canAdd || id === '') return null;
  if (offlineMode) return <p className="hint" data-testid={`receipt-photo-later-${id}`}>Add the receipt photo once you are online.</p>;
  const inputId = `receipt-photo-add-${id}`;
  return (
    <>
      <label className="btn btn-sm" htmlFor={inputId} aria-disabled={add.isPending}>
        <input
          id={inputId}
          type="file"
          accept={PHOTO_TYPES}
          className="visually-hidden"
          disabled={add.isPending}
          onChange={(e) => {
            const file = e.target.files?.[0];
            e.target.value = '';
            if (!file) return;
            const why = photoProblem(file);
            setProblem(why);
            if (why === null) add.mutate(file);
          }}
        />
        {add.isPending ? 'Sending the photo' : 'Add receipt photo'}
      </label>
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      {add.error && <p role="alert" className="rt-flag">{(add.error as Error).message}</p>}
    </>
  );
}
