import { describe, expect, it } from 'vitest';
import { checkColour, colourMessage, contrast, fitToReadable, luminance, parseHex, suggestColour } from './contrast';

const rgb = (hex: string) => parseHex(hex) as [number, number, number];

// The reference values are the ones in BrandColourTest.java: the two implementations must agree.
describe('WCAG contrast', () => {
  it('has the WCAG luminance and ratio extremes', () => {
    expect(luminance(rgb('#FFFFFF'))).toBeCloseTo(1, 9);
    expect(luminance(rgb('#000000'))).toBeCloseTo(0, 9);
    expect(contrast(rgb('#000000'), rgb('#FFFFFF'))).toBeCloseTo(21, 9);
    expect(contrast(rgb('#FFFFFF'), rgb('#FFFFFF'))).toBeCloseTo(1, 9);
  });

  it('matches the known reference ratios', () => {
    expect(contrast(rgb('#767676'), rgb('#FFFFFF'))).toBeCloseTo(4.54, 2);
    expect(contrast(rgb('#777777'), rgb('#FFFFFF'))).toBeCloseTo(4.48, 2);
  });

  it('picks the better text colour', () => {
    expect(checkColour('#0D5C75')).toMatchObject({ ok: true, text: '#FFFFFF' });
    expect(checkColour('#FFD54F')).toMatchObject({ ok: true, text: '#111111' });
    expect(checkColour('#ffffff')).toMatchObject({ ok: true, text: '#111111' });
    expect(checkColour('#000000')).toMatchObject({ ok: true, text: '#FFFFFF' });
    expect(checkColour('#808080')).toMatchObject({ ok: true, text: '#111111' });
  });

  it('refuses a colour with no readable text, and anything that is not #RRGGBB', () => {
    expect(checkColour('#777777')).toMatchObject({ ok: false, reason: 'contrast' });
    expect(checkColour('#7A7A7A')).toMatchObject({ ok: false, reason: 'contrast' });
    for (const bad of ['', 'red', '#FFF', '0D5C75', '#0D5C7', '#0D5C75F', '#GGGGGG', ' #0D5C75']) {
      expect(checkColour(bad)).toMatchObject({ ok: false, reason: 'format' });
    }
    expect(colourMessage(checkColour('#777777'))).toMatch(/lighter or a darker/);
    expect(colourMessage(checkColour('red'))).toMatch(/#RRGGBB/);
    expect(colourMessage(checkColour('#0D5C75'))).toBeNull();
  });
});

describe('fitToReadable', () => {
  it('keeps a readable colour and darkens one in the unreadable band', () => {
    expect(fitToReadable('#0D5C75')).toBe('#0D5C75');
    const fitted = fitToReadable('#777777');
    expect(fitted).not.toBeNull();
    expect(checkColour(fitted as string).ok).toBe(true);
  });
});

describe('suggestColour', () => {
  const fill = (colours: [number, number, number, number][], counts: number[]) => {
    const out: number[] = [];
    colours.forEach((c, i) => {
      for (let n = 0; n < (counts[i] ?? 0); n++) out.push(...c);
    });
    return out;
  };

  it('takes the colour most of the logo is made of', () => {
    const pixels = fill(
      [
        [13, 92, 117, 255],
        [200, 30, 30, 255],
        [255, 255, 255, 255],
      ],
      [60, 30, 500],
    );
    expect(suggestColour(pixels)).toBe('#0D5C75');
  });

  it('ignores transparent pixels and near-white backgrounds', () => {
    const pixels = fill(
      [
        [0, 0, 0, 0],
        [250, 250, 250, 255],
        [200, 30, 30, 255],
      ],
      [400, 400, 5],
    );
    expect(suggestColour(pixels)).toBe('#C81E1E');
  });

  it('returns a readable colour even when the dominant one is not', () => {
    const suggestion = suggestColour(fill([[119, 119, 119, 255]], [50]));
    expect(suggestion).not.toBeNull();
    expect(checkColour(suggestion as string).ok).toBe(true);
  });

  it('has no suggestion for an image of nothing to pick', () => {
    expect(suggestColour([])).toBeNull();
    expect(suggestColour(fill([[255, 255, 255, 255]], [100]))).toBeNull();
    expect(suggestColour(fill([[10, 10, 10, 255]], [100]))).toBeNull();
  });
});
