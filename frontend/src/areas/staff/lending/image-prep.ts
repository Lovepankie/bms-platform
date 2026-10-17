// Getting a photo ready to upload (issue #280, FR-MEM-09, FR-COL-01). A phone camera makes a 3 to 12 MB
// picture; the server takes 5 MB at most and keeps nothing bigger than it needs. So the browser first
// checks what was picked (type and size, in words), then shrinks a photo to a sane size and encodes it
// as JPEG, and only that goes over the mobile network. A PDF is sent as it is. The browser's decoding and
// canvas are behind `Codec` so the rules here are tested without a browser.

/** The server's limit for one file (chapter 7 section 7.11.11). */
export const MAX_UPLOAD_BYTES = 5 * 1024 * 1024;
/** The most a person may pick: a photo is shrunk, so the pick may be bigger than what is sent. */
export const MAX_PICK_BYTES = 30 * 1024 * 1024;
/** The longest side of a photo that is sent, in pixels: ample to read an ID, small to send. */
export const MAX_SIDE = 2000;
/** A photo already this small and this size is sent as it is. */
const KEEP_BYTES = 1_500_000;
/** Aim below the limit, so the multipart wrapper never tips a file over. */
const TARGET_BYTES = Math.floor(MAX_UPLOAD_BYTES * 0.9);

/** What the file picker offers. A phone offers its camera for images. */
// HEIC is not offered: most browsers cannot draw it, and a phone given only JPEG and PNG to choose from hands over a JPEG.
export const ACCEPT = 'image/jpeg,image/png,image/webp,application/pdf';

export interface Picked {
  name: string;
  type: string;
  size: number;
}

export type Checked = { ok: true; kind: 'image' | 'pdf' } | { ok: false; message: string };

const IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif']);

/** Whether a picked file may go on: a photo or a PDF, and not absurdly big. Words for a lender, not codes. */
export function checkPicked(file: Picked): Checked {
  const type = file.type.toLowerCase();
  const pdf = type === 'application/pdf' || (type === '' && /\.pdf$/i.test(file.name));
  const image = IMAGE_TYPES.has(type) || (type === '' && /\.(jpe?g|png|webp|heic|heif)$/i.test(file.name));
  if (!pdf && !image) return { ok: false, message: 'This kind of file cannot be used. Take a photo, or choose a JPEG, PNG or PDF file.' };
  if (file.size === 0) return { ok: false, message: 'This file is empty. Take the photo again.' };
  if (pdf && file.size > MAX_UPLOAD_BYTES) return { ok: false, message: 'This PDF is bigger than 5 MB. Choose a smaller one, or take a photo of the page.' };
  if (file.size > MAX_PICK_BYTES) return { ok: false, message: 'This file is far too big. Take the photo again, or choose a smaller one.' };
  return { ok: true, kind: pdf ? 'pdf' : 'image' };
}

/** The size to draw a picture at so its longest side is at most `max`, keeping its shape. */
export function fitWithin(width: number, height: number, max: number = MAX_SIDE): { width: number; height: number } {
  const longest = Math.max(width, height);
  if (longest <= max) return { width, height };
  const scale = max / longest;
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

/** A picture the browser has read once: its size, and a way to draw it smaller. Closed when done. */
export interface Decoded {
  width: number;
  height: number;
  /** The picture drawn at a size and written as JPEG at a quality (0 to 1), or null when that fails. */
  encode(width: number, height: number, quality: number): Promise<Blob | null>;
  close(): void;
}

/** The browser's part, replaced by a fake in tests. The picture is read once and drawn as often as needed. */
export interface Codec {
  /** The decoded picture, or null when the browser cannot read this kind of picture. */
  decode(file: Blob): Promise<Decoded | null>;
}

export interface Prepared {
  blob: Blob;
  fileName: string;
}

export class PrepareError extends Error {}

const baseName = (name: string) => name.replace(/\.[^.]+$/, '') || 'photo';

/**
 * The file as it will be sent. A PDF and a small JPEG or PNG go as they are; any other photo is drawn at
 * most MAX_SIDE pixels on its longest side and written as JPEG, at lower quality and then smaller size
 * until it is under the limit. Throws PrepareError with words when the picture cannot be read or cannot
 * be made small enough.
 */
export async function prepareFile(file: Blob & { name?: string }, codec: Codec): Promise<Prepared> {
  const name = file.name ?? 'photo';
  const checked = checkPicked({ name, type: file.type, size: file.size });
  if (!checked.ok) throw new PrepareError(checked.message);
  if (checked.kind === 'pdf') return { blob: file, fileName: name };
  const size = await codec.decode(file);
  if (!size) throw new PrepareError('This picture could not be read. Take the photo again, or choose a JPEG or PNG file.');
  try {
    const plainType = file.type === 'image/jpeg' || file.type === 'image/png';
    if (plainType && file.size <= KEEP_BYTES && Math.max(size.width, size.height) <= MAX_SIDE) return { blob: file, fileName: name };
    const target = fitWithin(size.width, size.height);
    for (const [quality, shrink] of [[0.85, 1], [0.7, 1], [0.6, 0.75], [0.5, 0.6], [0.45, 0.45]] as const) {
      const w = Math.max(1, Math.round(target.width * shrink));
      const h = Math.max(1, Math.round(target.height * shrink));
      const out = await size.encode(w, h, quality);
      if (!out) throw new PrepareError('This picture could not be made smaller. Take the photo again.');
      if (out.size <= TARGET_BYTES) return { blob: out, fileName: `${baseName(name)}.jpg` };
    }
    throw new PrepareError('This picture is still too big after making it smaller. Take it again from further away, or choose another.');
  } finally {
    size.close();
  }
}

/** The real codec, on the browser's own decoder and a canvas. */
export const browserCodec: Codec = {
  async decode(file) {
    let bitmap: ImageBitmap;
    try {
      // The browser turns a phone photo the right way up (its default orientation is "from-image").
      bitmap = await createImageBitmap(file);
    } catch {
      return null;
    }
    return {
      width: bitmap.width,
      height: bitmap.height,
      close: () => bitmap.close(),
      async encode(width, height, quality) {
        try {
          const canvas = document.createElement('canvas');
          canvas.width = width;
          canvas.height = height;
          const context = canvas.getContext('2d');
          if (!context) return null;
          context.drawImage(bitmap, 0, 0, width, height);
          return await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality));
        } catch {
          return null;
        }
      },
    };
  },
};
