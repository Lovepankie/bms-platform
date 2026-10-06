import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { contrast, parseHex, type Rgb } from '../contrast';

// The design tokens (#95) keep the WCAG rule the brand colour follows (contrast.ts): text roles
// reach 4.5:1 on white and on the page background, input edges and the focus colour 3:1.

const css = readFileSync(new URL('../theme.css', import.meta.url), 'utf8');

function token(name: string): Rgb {
  const match = css.match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{6})`));
  const rgb = match ? parseHex(match[1] as string) : null;
  if (!rgb) throw new Error(`no hex value for ${name}`);
  return rgb;
}

const WHITE = parseHex('#ffffff') as Rgb;
const PAGE = token('--rt-grey-50');

describe('design tokens', () => {
  it('the Rincol blue is too light for text, which is why it is only the accent', () => {
    expect(contrast(token('--rt-blue'), WHITE)).toBeLessThan(4.5);
  });

  it('the strong blue carries white button text and links on white and on the page', () => {
    expect(contrast(token('--rt-blue-strong'), WHITE)).toBeGreaterThanOrEqual(4.5);
    expect(contrast(token('--rt-blue-strong'), PAGE)).toBeGreaterThanOrEqual(4.5);
  });

  it.each(['--rt-grey-900', '--rt-grey-600', '--color-danger', '--color-success', '--color-warning'])(
    '%s reads at 4.5:1 on white and on the page',
    (name) => {
      expect(contrast(token(name), WHITE)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(token(name), PAGE)).toBeGreaterThanOrEqual(4.5);
    },
  );

  it('input edges reach 3:1 on white', () => {
    expect(contrast(token('--rt-grey-500'), WHITE)).toBeGreaterThanOrEqual(3);
  });

  it('the status colours read on their own tints', () => {
    for (const [text, tint] of [['--color-danger', '--color-danger-tint'], ['--color-success', '--color-success-tint'], ['--color-warning', '--color-warning-tint']]) {
      expect(contrast(token(text as string), token(tint as string))).toBeGreaterThanOrEqual(4.5);
    }
    expect(contrast(token('--rt-blue-strong'), token('--rt-blue-tint'))).toBeGreaterThanOrEqual(4.5);
  });
});
