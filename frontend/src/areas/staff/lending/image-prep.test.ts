import { describe, expect, it } from 'vitest';
import { checkPicked, fitWithin, MAX_SIDE, MAX_UPLOAD_BYTES, prepareFile, PrepareError, type Codec } from './image-prep';

const blobOf = (size: number, type: string, name = 'pic'): Blob & { name: string } => Object.assign(new Blob([new Uint8Array(size)], { type }), { name });

/** A codec that records what was asked, and answers with a size from `sizeAt`. */
function fake(width: number, height: number, sizeAt: (w: number, h: number, q: number) => number | null) {
  const calls: { w: number; h: number; q: number }[] = [];
  const state = { decodes: 0, closes: 0 };
  const codec: Codec = {
    decode: async () => {
      state.decodes++;
      return {
        width,
        height,
        close: () => { state.closes++; },
        encode: async (w, h, q) => {
          calls.push({ w, h, q });
          const n = sizeAt(w, h, q);
          return n === null ? null : new Blob([new Uint8Array(n)], { type: 'image/jpeg' });
        },
      };
    },
  };
  return { codec, calls, state };
}

describe('checkPicked', () => {
  it('refuses a kind of file that is not a photo or PDF, and an empty one', () => {
    expect(checkPicked({ name: 'a.docx', type: 'application/msword', size: 10 })).toMatchObject({ ok: false });
    expect(checkPicked({ name: 'a.jpg', type: 'image/jpeg', size: 0 })).toMatchObject({ ok: false, message: expect.stringMatching(/empty/) });
  });
  it('refuses a PDF over 5 MB but lets a big photo through to be shrunk', () => {
    expect(checkPicked({ name: 'a.pdf', type: 'application/pdf', size: MAX_UPLOAD_BYTES + 1 })).toMatchObject({ ok: false, message: expect.stringMatching(/5 MB/) });
    expect(checkPicked({ name: 'a.jpg', type: 'image/jpeg', size: 12_000_000 })).toEqual({ ok: true, kind: 'image' });
    expect(checkPicked({ name: 'a.jpg', type: 'image/jpeg', size: 40_000_000 })).toMatchObject({ ok: false });
  });
  it('reads the kind from the name when the phone gives no type', () => {
    expect(checkPicked({ name: 'IMG_1.JPG', type: '', size: 5 })).toEqual({ ok: true, kind: 'image' });
    expect(checkPicked({ name: 'scan.pdf', type: '', size: 5 })).toEqual({ ok: true, kind: 'pdf' });
  });
});

describe('fitWithin', () => {
  it('keeps the shape and caps the longest side', () => {
    expect(fitWithin(4000, 3000)).toEqual({ width: MAX_SIDE, height: 1500 });
    expect(fitWithin(1000, 800)).toEqual({ width: 1000, height: 800 });
  });
});

describe('prepareFile', () => {
  it('sends a PDF and a small photo as they are, without drawing', async () => {
    const { codec, calls } = fake(1000, 800, () => 1);
    const pdf = blobOf(1000, 'application/pdf', 'a.pdf');
    expect((await prepareFile(pdf, codec)).blob).toBe(pdf);
    const small = blobOf(200_000, 'image/jpeg', 'a.jpg');
    expect((await prepareFile(small, codec)).blob).toBe(small);
    expect(calls).toHaveLength(0);
  });
  it('shrinks a big phone photo to at most 2000 px, as a JPEG under the limit', async () => {
    const { codec, calls } = fake(4000, 3000, (w, h, q) => Math.round(w * h * q * 0.3));
    const out = await prepareFile(blobOf(9_000_000, 'image/jpeg', 'IMG_9.jpeg'), codec);
    expect(out.fileName).toBe('IMG_9.jpg');
    expect(out.blob.size).toBeLessThanOrEqual(MAX_UPLOAD_BYTES);
    expect(Math.max(calls[0]!.w, calls[0]!.h)).toBe(MAX_SIDE);
  });
  it('tries smaller until it fits, and says so when it never does', async () => {
    const tries = fake(4000, 3000, (w) => (w < 1000 ? 100_000 : 20_000_000));
    expect((await prepareFile(blobOf(9_000_000, 'image/jpeg'), tries.codec)).blob.size).toBe(100_000);
    expect(tries.calls.length).toBeGreaterThan(1);
    // The picture is read once however many sizes are tried, and let go of afterwards.
    expect(tries.state.decodes).toBe(1);
    expect(tries.state.closes).toBe(1);
    const never = fake(4000, 3000, () => 20_000_000);
    await expect(prepareFile(blobOf(9_000_000, 'image/jpeg'), never.codec)).rejects.toThrow(/still too big/);
  });
  it('refuses with words a picture the browser cannot read or draw', async () => {
    const unreadable: Codec = { decode: async () => null };
    await expect(prepareFile(blobOf(100, 'image/png'), unreadable)).rejects.toBeInstanceOf(PrepareError);
    const cannotDraw = fake(4000, 3000, () => null);
    await expect(prepareFile(blobOf(9_000_000, 'image/jpeg'), cannotDraw.codec)).rejects.toThrow(/could not be made smaller/);
  });
  it('refuses a wrong kind before decoding', async () => {
    const { codec } = fake(1, 1, () => 1);
    await expect(prepareFile(blobOf(10, 'text/plain', 'a.txt'), codec)).rejects.toThrow(/cannot be used/);
  });
});
