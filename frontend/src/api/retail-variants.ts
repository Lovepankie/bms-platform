// Items with sizes and colours (issue #384, ADR-051): the rules of the quick creation, the same as the server's VariantGrid, so
// the codes the preview shows are the codes saved. A variant is a product; its name is the item's name with its size and colour.

/** What one variant will be: its size and colour (one may be absent), code, prices and place in the item's list. */
export interface PlannedVariant {
  size?: string;
  colour?: string;
  code: string;
  costMinor: number;
  sellMinor: number;
  position: number;
}

/** At most this many are made at a time (the server refuses more with too_many_variants). */
export const MAX_VARIANTS = 100;

/** Each of the size and the colour gives at most seven letters or digits to a generated code. */
export const VARIANT_TOKEN = 7;

/** Trimmed, or undefined when blank. */
export function cleanOption(s: string | undefined | null): string | undefined {
  const t = (s ?? '').trim();
  return t === '' ? undefined : t;
}

/** Trimmed, blanks dropped, each once ignoring case, in the order given. */
export function distinctOptions(values: readonly string[] | undefined): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const v of values ?? []) {
    const t = cleanOption(v);
    if (t !== undefined && !seen.has(t.toLowerCase())) {
      seen.add(t.toLowerCase());
      out.push(t);
    }
  }
  return out;
}

/** The sizes or colours typed in one box, split at commas, semicolons and new lines: "0-3m, 3-6m, 6-12m". */
export function splitOptions(text: string): string[] {
  return distinctOptions(text.split(/[,;\n]/));
}

/** The letters and digits of a size or colour in capitals, seven at most: "0-3m" gives 03M, "Sky blue" SKYBLUE. */
export function codeToken(part: string | undefined): string {
  if (!part) return '';
  // Letters and decimal digits, as Java's Character.isLetterOrDigit keeps them on the server.
  const t = [...part.toUpperCase()].filter((c) => /[\p{L}\p{Nd}]/u.test(c)).join('');
  return [...t].slice(0, VARIANT_TOKEN).join('');
}

/** The code a variant gets unless the owner types one: ROMP, 0-3m and Blue give ROMP-03M-BLUE. */
export function variantCode(baseCode: string, size?: string, colour?: string): string {
  return [baseCode.trim(), codeToken(size), codeToken(colour)].filter((p) => p !== '').join('-');
}

/** "0-3m, Blue", "0-3m" or "Blue". */
export function variantLabel(size?: string | null, colour?: string | null): string {
  return [size, colour].filter((p): p is string => !!p).join(', ');
}

/** What every variant is called: the item's name, then its size and colour, "Baby romper (0-3m, Blue)". */
export function variantDescription(name: string, size?: string, colour?: string): string {
  return `${name.trim()} (${variantLabel(size, colour)})`;
}

/** The size and colour as the server compares them, ignoring case. */
export const variantKey = (size?: string | null, colour?: string | null) => `${(size ?? '').toLowerCase()}\u0000${(colour ?? '').toLowerCase()}`;

/**
 * Every combination of the sizes and colours, sizes first in the order given, each with its code and the item's prices. A
 * combination the item has already (`existing`, keys of variantKey) is left out; positions continue from `nextPosition`.
 */
export function planVariants({ baseCode, sizes, colours, costMinor, sellMinor, existing = new Set<string>(), nextPosition = 0 }: {
  baseCode: string;
  sizes: readonly string[];
  colours: readonly string[];
  costMinor: number;
  sellMinor: number;
  existing?: ReadonlySet<string>;
  nextPosition?: number;
}): PlannedVariant[] {
  const ss = distinctOptions(sizes);
  const cs = distinctOptions(colours);
  const sizeAxis: (string | undefined)[] = ss.length > 0 ? ss : [undefined];
  const colourAxis: (string | undefined)[] = cs.length > 0 ? cs : [undefined];
  const seen = new Set(existing);
  const out: PlannedVariant[] = [];
  let position = nextPosition;
  for (const size of sizeAxis) {
    for (const colour of colourAxis) {
      if (size === undefined && colour === undefined) continue;
      const key = variantKey(size, colour);
      if (seen.has(key)) continue;
      seen.add(key);
      out.push({ ...(size ? { size } : {}), ...(colour ? { colour } : {}), code: variantCode(baseCode, size, colour), costMinor, sellMinor, position: position++ });
    }
  }
  return out;
}

/** The codes that two planned variants share (ignoring case), which the server would refuse: give one of them another code. */
export function repeatedCodes(plan: readonly { code: string }[]): string[] {
  const seen = new Set<string>();
  const repeated = new Set<string>();
  for (const p of plan) {
    const k = p.code.trim().toLowerCase();
    if (seen.has(k)) repeated.add(p.code.trim());
    seen.add(k);
  }
  return [...repeated];
}
