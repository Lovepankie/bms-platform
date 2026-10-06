// The brand colour rule (FR-TEN-08), in one place: WCAG 2.x relative luminance and contrast, the
// better of white or near-black text on a colour, and a suggestion taken from a logo's pixels. The
// backend enforces the same rule on theme_primary (BrandColour.java); both are tested against the
// same reference values, so a colour the screen accepts is a colour the API accepts.

export const LIGHT_TEXT = '#FFFFFF';
export const DARK_TEXT = '#111111';
export const MIN_CONTRAST = 4.5;

export const HEX = /^#[0-9A-Fa-f]{6}$/;

export type Rgb = [number, number, number];

export function parseHex(hex: string): Rgb | null {
  if (!HEX.test(hex)) return null;
  return [parseInt(hex.slice(1, 3), 16), parseInt(hex.slice(3, 5), 16), parseInt(hex.slice(5, 7), 16)];
}

export function toHex([r, g, b]: Rgb): string {
  const part = (v: number) => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0');
  return `#${part(r)}${part(g)}${part(b)}`.toUpperCase();
}

function channel(value: number): number {
  const c = value / 255;
  return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/** WCAG relative luminance of a colour, 0 (black) to 1 (white). */
export function luminance([r, g, b]: Rgb): number {
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

/** WCAG contrast ratio of two colours, 1 to 21. */
export function contrast(a: Rgb, b: Rgb): number {
  const la = luminance(a);
  const lb = luminance(b);
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
}

export type ColourCheck =
  | { ok: true; text: string; ratio: number }
  | { ok: false; reason: 'format' | 'contrast'; ratio: number };

/** The text colour with the better contrast on this colour; not ok when neither reaches 4.5. */
export function checkColour(hex: string): ColourCheck {
  const rgb = parseHex(hex);
  if (!rgb) return { ok: false, reason: 'format', ratio: 0 };
  const light = contrast(rgb, parseHex(LIGHT_TEXT) as Rgb);
  const dark = contrast(rgb, parseHex(DARK_TEXT) as Rgb);
  const ratio = Math.max(light, dark);
  if (ratio < MIN_CONTRAST) return { ok: false, reason: 'contrast', ratio };
  return { ok: true, text: light >= dark ? LIGHT_TEXT : DARK_TEXT, ratio };
}

/** The message the set-up screen shows for a refused colour. */
export function colourMessage(check: ColourCheck): string | null {
  if (check.ok) return null;
  return check.reason === 'format'
    ? 'Use a colour written as #RRGGBB, for example #0D5C75.'
    : 'Text on this colour would be hard to read. Pick a lighter or a darker colour.';
}

/** Darkens a colour in small steps until its text is readable; null if it never gets there. */
export function fitToReadable(hex: string): string | null {
  let rgb = parseHex(hex);
  if (!rgb) return null;
  for (let step = 0; step <= 20; step++) {
    const candidate = toHex(rgb);
    if (checkColour(candidate).ok) return candidate;
    rgb = [rgb[0] * 0.95, rgb[1] * 0.95, rgb[2] * 0.95];
  }
  return null;
}

/**
 * The dominant colour of an image's RGBA pixels (as ImageData.data), made readable. Transparent
 * pixels and near-white backgrounds are ignored, and each colour counts by how many pixels it has
 * in a 16 level grid, so anti-aliased edges do not win. Null for an image with nothing to pick
 * (all transparent, white or black).
 */
export function suggestColour(pixels: ArrayLike<number>): string | null {
  const buckets = new Map<number, { n: number; r: number; g: number; b: number }>();
  for (let i = 0; i + 3 < pixels.length; i += 4) {
    const [r, g, b, a] = [pixels[i] ?? 0, pixels[i + 1] ?? 0, pixels[i + 2] ?? 0, pixels[i + 3] ?? 0];
    if (a < 128 || Math.min(r, g, b) > 235 || Math.max(r, g, b) < 20) continue;
    const key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
    const bucket = buckets.get(key) ?? { n: 0, r: 0, g: 0, b: 0 };
    bucket.n += 1;
    bucket.r += r;
    bucket.g += g;
    bucket.b += b;
    buckets.set(key, bucket);
  }
  let best: { n: number; r: number; g: number; b: number } | null = null;
  for (const bucket of buckets.values()) {
    if (!best || bucket.n > best.n) best = bucket;
  }
  if (!best) return null;
  return fitToReadable(toHex([best.r / best.n, best.g / best.n, best.b / best.n]));
}
