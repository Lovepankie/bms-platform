import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { BusyButton } from '../../../app/ui/BusyButton';
import { retail } from '../../../api/retail';

// Item photos (issue #384, ADR-053): a small picture of an item in the item lists, the sale screen and the item's page, read
// from the app's own origin (GET /retail/photos/{id}) as a blob, since the content security policy allows no other image host.
// A photo never changes under its id, so it is fetched once and kept for the session.

/** The largest file the server takes; checked here first so a phone photo that is too big is refused in words at once. */
export const MAX_PHOTO_BYTES = 5 * 1024 * 1024;

export const PHOTO_TYPES = 'image/png,image/jpeg,image/webp';

/** Why a chosen file cannot be a photo, in plain words, or null when it can be sent. */
export function photoProblem(file: { size: number; type: string }): string | null {
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) return 'Choose a photo: a PNG, JPEG or WebP picture.';
  if (file.size > MAX_PHOTO_BYTES) return 'This photo is larger than 5 MB. Take a smaller one or send it at a lower size.';
  return null;
}

/** The picture of a photo id, or nothing while it loads or when there is none. */
export function ItemPhoto({ photoId, label, size = 56 }: { photoId?: string | null; label: string; size?: number }) {
  // An item without a photo asks for nothing at all, so a list of such items reads exactly as before. A picture of 96 px or
  // less is the small copy the server keeps for cards and pickers.
  return photoId ? <LoadedPhoto photoId={photoId} label={label} size={size} small={size <= 96} /> : null;
}

/** The photo an item shows: its own, else its item's (a size without a photo of its own shows its item's). */
export const shownPhoto = (p: { photo_id?: string; item_photo_id?: string }) => p.photo_id ?? p.item_photo_id;

function LoadedPhoto({ photoId, label, size, small }: { photoId: string; label: string; size: number; small: boolean }) {
  const blob = useQuery({
    queryKey: ['retail', 'photo', photoId, small],
    queryFn: ({ signal }) => retail.photo(photoId, signal, small),
    staleTime: Infinity,
    gcTime: 30 * 60_000,
    retry: 1,
  });
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    if (!blob.data || typeof URL.createObjectURL !== 'function') return undefined;
    const made = URL.createObjectURL(blob.data);
    setUrl(made);
    return () => URL.revokeObjectURL(made);
  }, [blob.data]);
  if (!url) return null;
  return <img className="rt-photo" src={url} alt={`Photo of ${label}`} width={size} height={size} style={{ objectFit: 'cover', borderRadius: 6 }} loading="lazy" />;
}

/**
 * Choose, change or take off a photo; `onSaved` gets the words to show. `photoId` is the subject's own photo, the only one Take
 * the photo off is offered for; `shownId` is what it shows now (a size without its own shows its item's).
 */
export function PhotoEditor({ inputId, photoId, shownId, label, onUpload, onClear, onSaved, tour }: {
  /** Unique on the page: the id of the item or size, never its name. */
  inputId: string;
  photoId?: string | null;
  shownId?: string | null;
  label: string;
  onUpload: (file: File) => Promise<unknown>;
  onClear: () => Promise<unknown>;
  onSaved?: (text: string) => void;
  tour?: string;
}) {
  const queryClient = useQueryClient();
  const input = useRef<HTMLInputElement>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const upload = useMutation({
    mutationFn: (file: File) => onUpload(file),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['retail'] }); onSaved?.(`Saved the photo of ${label}.`); },
  });
  const clear = useMutation({
    mutationFn: () => onClear(),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ['retail'] }); onSaved?.(`Took the photo of ${label} off.`); },
  });
  const id = `photo-${inputId}`;
  return (
    <div className="rt-photo-editor" data-tour={tour}>
      <ItemPhoto photoId={shownId ?? photoId} label={label} size={160} />
      {!photoId && shownId && <p className="hint">This shows the item's photo. Add one here for this size alone.</p>}
      <label htmlFor={id}>{photoId ? 'Change the photo' : 'Add a photo'}</label>
      <input
        id={id}
        ref={input}
        type="file"
        accept={PHOTO_TYPES}
        disabled={upload.isPending}
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (!file) return;
          const why = photoProblem(file);
          setProblem(why);
          if (why === null) upload.mutate(file);
          if (input.current) input.current.value = '';
        }}
      />
      <p className="hint">A PNG, JPEG or WebP picture up to 5 MB. It shows in the item list and when you pick the item in a sale.</p>
      {upload.isPending && <p className="loading">Sending the photo</p>}
      {problem && <p role="alert" className="rt-flag">{problem}</p>}
      {(upload.error || clear.error) && <p role="alert" className="rt-flag">{((upload.error ?? clear.error) as Error).message}</p>}
      {photoId && <BusyButton busy={clear.isPending} type="button" className="btn-ghost" onClick={() => clear.mutate()}>Take the photo off</BusyButton>}
    </div>
  );
}
