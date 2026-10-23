import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { contrast, parseHex, toHex, type Rgb } from '../contrast';

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

  it('a tenant theme_primary only changes the accent family, derived from --brand', () => {
    const block = css.match(/:root\[style\*='--brand'\]\s*\{([^}]*)\}/);
    expect(block).not.toBeNull();
    const props = [...(block?.[1] ?? '').matchAll(/(--[a-z-]+):\s*([^;]+);/g)];
    const allowed = new Set(['--color-accent', '--color-accent-tint', '--color-info-tint', '--color-link', '--color-focus']);
    expect(props.length).toBeGreaterThan(0);
    for (const [, name, value] of props) {
      expect(allowed.has(name as string), `${name} must not change with the tenant colour`).toBe(true);
      expect(value, `${name} must be derived from --brand`).toContain('var(--brand)');
    }
  });

  it('every colour defined on :root belongs to the Rincol tokens or the tenant brand pair', () => {
    for (const block of css.matchAll(/(?:^|\n):root\s*\{([^}]*)\}/g)) {
      for (const [, name, value] of (block[1] ?? '').matchAll(/(--[a-z0-9-]+):\s*([^;]+);/g)) {
        const isColour = /#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(|color-mix\(/.test(value as string);
        if (!isColour) continue;
        expect(
          /^--(rt-|color-|brand|shadow|focus)/.test(name as string),
          `${name} sets a colour outside the Rincol tokens`,
        ).toBe(true);
      }
    }
  });

  it('uses no font other than the self-hosted Open Sans family', () => {
    expect(css).not.toMatch(/Campton/i);
    expect(css).not.toMatch(/https?:\/\/fonts\.(googleapis|gstatic)\.com/);
  });
});

// The dark theme (#429): the same rules hold on the dark surface, so the token pairs in
// docs/ui/design-system.md are proven here rather than trusted.
const dark = css.match(/:root\[data-theme='dark'\]\s*\{([^}]*)\}/)?.[1] ?? '';

function darkToken(name: string): Rgb {
  const match = dark.match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{6})`));
  const rgb = match ? parseHex(match[1] as string) : null;
  if (!rgb) throw new Error(`no dark hex value for ${name}`);
  return rgb;
}

const D_SURFACE = darkToken('--color-surface');
const D_BG = darkToken('--color-bg');
const D_MUTED = darkToken('--color-surface-muted');

/** color-mix(in srgb, a p%, b) as the browser computes it. */
const mix = (a: Rgb, p: number, b: Rgb): Rgb => [0, 1, 2].map((i) => (a[i] as number) * p + (b[i] as number) * (1 - p)) as Rgb;

describe('the dark theme tokens', () => {
  it('has a dark block that sets every colour role the light theme sets and a few of its own', () => {
    for (const name of ['--color-bg', '--color-surface', '--color-text', '--color-text-muted', '--color-link', '--color-focus', '--color-danger', '--color-success', '--color-warning']) {
      expect(dark, name).toContain(`${name}:`);
    }
    expect(dark).toContain('color-scheme: dark');
  });

  it.each(['--color-text', '--color-text-muted', '--color-link', '--color-danger', '--color-success', '--color-warning'])(
    '%s reads at 4.5:1 on the dark surface, the page and the muted surface',
    (name) => {
      for (const on of [D_SURFACE, D_BG, D_MUTED]) expect(contrast(darkToken(name), on)).toBeGreaterThanOrEqual(4.5);
    },
  );

  it('text, muted text and links read on the neutral tint of a disabled control or badge', () => {
    for (const name of ['--color-text', '--color-text-muted']) expect(contrast(darkToken(name), darkToken('--color-neutral-tint'))).toBeGreaterThanOrEqual(4.5);
  });

  it('the status colours and links read on their own dark tints, so badges and alerts keep their meaning', () => {
    for (const [text, tint] of [
      ['--color-danger', '--color-danger-tint'],
      ['--color-success', '--color-success-tint'],
      ['--color-warning', '--color-warning-tint'],
      ['--color-link', '--color-accent-tint'],
    ]) {
      expect(contrast(darkToken(text as string), darkToken(tint as string))).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('keeps each status in its own hue family: red danger, green success, amber warning', () => {
    const [dr, dg] = [darkToken('--color-danger'), darkToken('--color-success')];
    expect(dr[0]).toBeGreaterThan(dr[1] + 60);
    expect(dg[1]).toBeGreaterThan(dg[0] + 60);
    const w = darkToken('--color-warning');
    expect(w[0]).toBeGreaterThan(w[2] + 100);
    expect(w[1]).toBeGreaterThan(w[2] + 60);
  });

  it('input edges and the focus colour reach 3:1 on the dark surface', () => {
    expect(contrast(darkToken('--color-border-strong'), D_SURFACE)).toBeGreaterThanOrEqual(3);
    expect(contrast(darkToken('--color-focus'), D_SURFACE)).toBeGreaterThanOrEqual(3);
    expect(contrast(darkToken('--color-focus'), D_BG)).toBeGreaterThanOrEqual(3);
  });

  it('white text on the destructive button fill and the inverse toast text read at 4.5:1', () => {
    expect(contrast(WHITE, darkToken('--color-danger-fill'))).toBeGreaterThanOrEqual(4.5);
    expect(contrast(darkToken('--color-on-inverse'), darkToken('--color-inverse'))).toBeGreaterThanOrEqual(4.5);
    expect(contrast(WHITE, token('--color-danger'))).toBeGreaterThanOrEqual(4.5);
  });

  it('the three chart slots keep their order (blue, orange, green) and stand out from the dark surface at 3:1', () => {
    for (const name of ['--rt-viz-blue-dark', '--rt-viz-orange-dark', '--rt-viz-green-dark']) {
      expect(contrast(parseHex((css.match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{6})`)) as RegExpMatchArray)[1] as string) as Rgb, D_SURFACE), name).toBeGreaterThanOrEqual(3);
    }
    expect(dark).toMatch(/--viz-1-value:\s*var\(--rt-viz-blue-dark\)/);
    expect(dark).toMatch(/--viz-2-value:\s*var\(--rt-viz-orange-dark\)/);
    expect(dark).toMatch(/--viz-3-value:\s*var\(--rt-viz-green-dark\)/);
  });

  const brandBlock = () => css.match(/:root\[data-theme='dark'\]\[style\*='--brand'\]\s*\{([^}]*)\}/)?.[1] ?? '';

  it('a tenant colour in the dark theme only changes the accent family, and its link and focus read on the dark surface for any colour', () => {
    const block = brandBlock();
    const props = [...block.matchAll(/(--[a-z-]+):\s*([^;]+);/g)];
    const allowed = new Set(['--color-accent', '--color-accent-tint', '--color-info-tint', '--color-link', '--color-focus']);
    expect(props.length).toBeGreaterThan(0);
    for (const [, name, value] of props) {
      expect(allowed.has(name as string), `${name} must not change with the tenant colour`).toBe(true);
      expect(value).toContain('var(--brand)');
    }
    // The link is the brand mixed 35% into white: the worst case is a black brand.
    for (const brand of ['#000000', '#0d5c75', '#7a0019', '#ffffff', '#00adef']) {
      const link = mix(parseHex(brand) as Rgb, 0.35, WHITE);
      expect(contrast(link, D_SURFACE), `${brand} as a link ${toHex(link)}`).toBeGreaterThanOrEqual(4.5);
      expect(contrast(link, mix(parseHex(brand) as Rgb, 0.18, D_SURFACE)), `${brand} link on its tint`).toBeGreaterThanOrEqual(4.5);
    }
  });

  it('the primary button label stays 4.5:1 on hover and press in both themes (the fill darkens, never lightens toward a white label)', () => {
    expect(dark).not.toMatch(/--color-primary-(hover|active):/);
    const black = parseHex('#000000') as Rgb;
    const hover = css.match(/--color-primary-hover:\s*color-mix\(in srgb, var\(--color-primary\) (\d+)%, #000\)/);
    const active = css.match(/--color-primary-active:\s*color-mix\(in srgb, var\(--color-primary\) (\d+)%, #000\)/);
    expect(hover).not.toBeNull();
    expect(active).not.toBeNull();
    // Brands that carry a white label (--brand-contrast #fff, 4.5:1 or more at rest).
    for (const brand of ['#000000', '#0d5c75', '#7a0019', '#004d40', '#0077b6', '#00695c']) {
      const fill = parseHex(brand) as Rgb;
      expect(contrast(WHITE, fill), `${brand} at rest`).toBeGreaterThanOrEqual(4.5);
      for (const m of [hover, active]) {
        const pressed = mix(fill, Number(m?.[1]) / 100, black);
        expect(contrast(WHITE, pressed), `${brand} ${m?.[0]}`).toBeGreaterThanOrEqual(4.5);
      }
    }
  });

  it('a tenant accent (stripe, active tab, chosen card edge) is 3:1 on the dark surface for any colour', () => {
    expect(brandBlock()).toMatch(/--color-accent:\s*color-mix\(in srgb, var\(--brand\) 45%, #fff\)/);
    for (const brand of ['#000000', '#0d5c75', '#7a0019', '#004d40', '#ffffff', '#00adef']) {
      const accent = mix(parseHex(brand) as Rgb, 0.45, WHITE);
      expect(contrast(accent, D_SURFACE), `${brand} as an accent ${toHex(accent)}`).toBeGreaterThanOrEqual(3);
    }
  });

  it('every dark colour is a hex or a derived mix, never a keyword that would skip the tokens', () => {
    expect(dark).not.toMatch(/:\s*(white|black|red|green|blue|gray|grey)\s*;/);
  });
});

describe('the toast tokens (#508)', () => {
  it('white text reads on the dark pill and the icons reach 3:1 on it, in both themes', () => {
    for (const pill of [token('--rt-grey-900'), darkToken('--color-toast')]) {
      expect(contrast(WHITE, pill)).toBeGreaterThanOrEqual(4.5);
      for (const icon of ['--color-toast-success', '--color-toast-error']) expect(contrast(token(icon), pill)).toBeGreaterThanOrEqual(3);
    }
  });

  it('the pill stays dark in the dark theme rather than turning light like the other inverse surfaces', () => {
    expect(contrast(darkToken('--color-toast'), WHITE)).toBeGreaterThanOrEqual(4.5);
  });
});
