import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

// The design guard (#106): the production CSP (style-src 'self') refuses an inline <style> element,
// and a hard-coded colour bypasses the tokens and the tenant colour. So no component renders a
// <style> element or sets inline HTML, and no component or stylesheet of the design layer writes a
// hex colour: colours live in src/app/theme.css only.

const SRC = fileURLToPath(new URL('../../', import.meta.url));

function files(dir: string, ext: RegExp): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path, ext);
    return ext.test(name) && !/\.test\.tsx?$/.test(name) ? [path] : [];
  });
}

const components = files(SRC, /\.tsx$/);
const stylesheets = files(join(SRC, 'app/ui'), /\.css$/);
// Every stylesheet under src, for the motion rule: a stylesheet outside the design layer animates under the same rule.
const allStylesheets = files(SRC, /\.css$/);
const HEX = /#[0-9a-fA-F]{3,8}\b/;
// What may move (rule 13): only transform and opacity, which the browser animates without laying out or repainting the page.
const MOVES = new Set(['transform', 'opacity']);
// The named exceptions, each a colour or background fade with its reason; anything else that animates fails the guard.
const MOTION_EXCEPTIONS: Record<string, { property: string; why: string }[]> = {
  'app/ui/forms.css': [{ property: 'border-color', why: 'a field edge fades to the hover and focus colour' }],
  'app/ui/surfaces.css': [{ property: 'border-color', why: 'a link card edge fades to the accent on hover' }],
  'app/ui/feedback.css': [{ property: 'background-position', why: 'the skeleton shimmer slides its gradient while a page loads' }],
};
// The words of a transition item that are not its property: durations, delays, curves and the motion tokens.
const NOT_A_PROPERTY = /^(?:-?[\d.]+m?s|var\(--[\w-]+\)|linear|ease(?:-in|-out|-in-out)?|step-start|step-end|(?:steps|cubic-bezier|linear)\([^)]*\)|allow-discrete|normal)$/;

/** The properties a `transition` or `transition-property` value animates; an item that names none animates `all`. */
function transitioned(value: string): string[] {
  return value.split(/,(?![^(]*\))/).map((item) => item.trim().split(/\s+(?![^(]*\))/).find((word) => !NOT_A_PROPERTY.test(word)) ?? 'all');
}

// The pulse ban (issue #603, rule 13) is a plain token ban, not a CSS parser: what loops says `infinite` or a bare count above
// one (issue #605), and a var() other than a motion token (var(--motion...), var(--ease...)) could hide a name or a count, so none
// may stand in an animation value. A count is a bare number (an exponent too) once the functions (cubic-bezier, steps) are taken
// out; an animation-iteration-count longhand holds only plain numbers up to one, so calc() or 1e3 there fails as well.
const ANIMATION_DECL = /(?<![\w-])(?:-webkit-)?animation(-name|-iteration-count)?\s*:\s*([^;}]+)/g;
const MOTION_TOKEN = /var\(--(?:motion|ease)[\w-]*\)/g;

/** Each rule of `css`: its selectors and its declarations. */
const rulesOf = (css: string) => [...css.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(([, selector, body]) => ({ selectors: (selector as string).split(',').map((s) => s.trim()), body: body as string }));

const FUNCTION = /[\w-]+\([^()]*\)/g;
const COUNT = /(?<![\w.%-])\d*\.?\d+(?:[eE][+-]?\d+)?(?![\w.%-])/g;
const ONCE = /^\s*(?:0*\.\d+|0*1?(?:\.0*)?)\s*(?:!important\s*)?$/;

/** True when an animation value loops, runs more than once, or carries a var() that is not a motion token. */
const loops = (value: string, longhand = '') =>
  /\binfinite\b/.test(value) ||
  /var\(/.test(value.replace(MOTION_TOKEN, '')) ||
  [...value.replace(FUNCTION, ' ').matchAll(COUNT)].some(([n]) => Number(n) > 1) ||
  (longhand === '-iteration-count' && !value.split(',').every((count) => ONCE.test(count)));

/** The selectors of `css` whose animation loops, bar those naming .new-blink, which `blinksOffPin` pins to five runs. */
function loopsIn(css: string): string[] {
  return rulesOf(css).flatMap(({ selectors, body }) =>
    [...body.matchAll(ANIMATION_DECL)].some(([, longhand, value]) => loops(value as string, longhand)) ? selectors.filter((s) => !/\.new-blink(?![\w-])/.test(s)) : [],
  );
}

/** The rules of `css` that name .new-blink and do anything but run rt-new-blink five times on the tokens, or nothing. */
function blinksOffPin(css: string): string[] {
  return rulesOf(css)
    .filter(({ selectors }) => selectors.some((s) => /\.new-blink(?![\w-])/.test(s)))
    .flatMap(({ body }) => [...body.matchAll(ANIMATION_DECL)].map(([decl]) => (decl as string).trim()))
    .filter((decl) => decl !== 'animation: rt-new-blink 1s var(--ease-out) 5' && decl !== 'animation: none');
}

/**
 * True when code animates without a stylesheet: a loop near animation, animate( or iterations (Number.POSITIVE_INFINITY too), or an
 * animation style set from code, as a key with a value or as a shorthand key holding an imported value (`style={{ animation }}`).
 * It is a token ban, so it is conservative and has limits: `import { animation }` and `const { animation } = x` fail too, while a
 * spread (`style={{ ...anim }}`) is not seen. Review catches a spread; a name other than animation needs no exception.
 */
const animatesInCode = (source: string) =>
  /(?:animation|animate\(|iterations)[\s\S]{0,200}?\b(?:Infinity|infinite|POSITIVE_INFINITY)\b/i.test(source) ||
  /\.style\.(?:webkit)?animation|setProperty\(\s*['"](?:-webkit-)?animation|['"](?:-webkit-)?animation[\w-]*['"]\s*:|\b(?:Webkit|Moz|ms)Animation|(?<![\w.-])animation[A-Za-z]*\s*:|[{,]\s*animation[A-Za-z]*\s*[,}]/.test(source);

/** The source with its comments blanked: comments cite issues (#95) and name what was removed. */
function code(file: string): string {
  return readFileSync(join(SRC, file), 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, (comment) => comment.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:'"`])\/\/.*$/gm, '$1');
}

// Values, not paint: the business set-up screen's colour input shows a sample tenant colour.
const ALLOWED_HEX: Record<string, RegExp> = {
  'areas/staff/setup.tsx': /colour : '#0d5c75'|placeholder="#0D5C75"/,
};

describe('design guard', () => {
  it('finds the components and stylesheets it guards', () => {
    expect(components.length).toBeGreaterThan(10);
    expect(stylesheets.length).toBeGreaterThan(5);
  });

  it.each(components.map((f) => relative(SRC, f)))('%s renders no <style> element and no inline HTML', (file) => {
    const source = code(file);
    expect(source).not.toMatch(/<style[\s>]/);
    expect(source).not.toMatch(/dangerouslySetInnerHTML/);
  });

  it.each(components.map((f) => relative(SRC, f)))('%s writes no hex colour', (file) => {
    const allowed = ALLOWED_HEX[file];
    code(file)
      .split('\n')
      .forEach((line, i) => {
        if (HEX.test(line) && !(allowed && allowed.test(line))) {
          throw new Error(`${file}:${i + 1} writes a hex colour; use a token from src/app/theme.css`);
        }
      });
  });

  it.each(stylesheets.map((f) => relative(SRC, f)))('%s writes no hex colour', (file) => {
    const css = readFileSync(join(SRC, file), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    expect(css).not.toMatch(HEX);
  });

  // Motion (#508, docs/ui-rules.md rule 13): everything eases out on the one curve. Ease-in and ease-in-out feel slow to answer,
  // a curve whose y goes past 0..1 bounces, and only transform and opacity move, bar the named fades above.
  // JUSTIFICATION-A3: two new guard rules asked for by #508; no existing test checks motion.
  it.each(allStylesheets.map((f) => relative(SRC, f)))('%s eases out without bounce and animates only transform and opacity', (file) => {
    const css = code(file);
    const excepted = new Set((MOTION_EXCEPTIONS[file] ?? []).map((e) => e.property));
    const allowed = (prop: string) => MOVES.has(prop) || excepted.has(prop);
    expect(css, `${file} eases in or in and out`).not.toMatch(/\bease-in(?:-out)?\b/);
    expect(css).not.toMatch(/\bbounce\b|\belastic\b/);
    for (const [, value] of css.matchAll(/(?<![\w-])transition(?:-property)?\s*:\s*([^;}]+)/g)) {
      if ((value as string).trim() === 'none') continue;
      for (const prop of transitioned(value as string)) {
        expect(allowed(prop) ? null : prop, `${file}: transition: ${value} animates ${prop}`).toBeNull();
      }
    }
    for (const [, name, frames] of css.matchAll(/@keyframes\s+([\w-]+)\s*\{((?:[^{}]*\{[^{}]*\})*\s*)\}/g)) {
      for (const [, prop] of (frames as string).matchAll(/([a-z-]+)\s*:/g)) {
        expect(allowed(prop as string) ? null : prop, `${file}: @keyframes ${name} animates ${prop}`).toBeNull();
      }
    }
    for (const [, curve] of css.matchAll(/cubic-bezier\(([^)]*)\)/g)) {
      const [, y1, , y2] = (curve as string).split(',').map(Number);
      expect([y1, y2].every((y) => (y as number) >= 0 && (y as number) <= 1), `${file}: cubic-bezier(${curve}) overshoots`).toBe(true);
    }
  });

  // Rule 13: no pulsing or blinking dot or badge to catch the eye. A loop (`infinite`, in any form) may only show work in progress
  // and stops when the work does; each such selector is named here with its reason. The one attention blink is the new-feature
  // badge's .new-blink (issue #603): the owner asked for it, every rule that names it runs rt-new-blink five times (and
  // NewBadge.tsx takes the class off once the page load's five seconds are up, WCAG 2.2.2), and it stands still under reduced
  // motion. Code may not animate past the stylesheets. The fixtures below feed each way around the ban to the same checkers.
  // JUSTIFICATION-A3: #603 asks for the pulse ban to be held by the guard with one named exception; no existing test checks loops.
  const LOOPS: Record<string, string> = {
    '.busy-spinner': 'the busy button, and its pulse under reduced motion, while it writes',
    '.brand-loader-arc': 'the loading mark, while a page loads',
    '.brand-loader-core': 'the loading mark, while a page loads',
    '.sync-dot-syncing': 'the sync dot, while sales are being sent',
    '.spinner': 'a spinner, while something loads',
    '.sb-searching::before': 'the search box spinner, while it searches',
    '.loading::before': 'the loading line spinner, while something loads',
    '.skeleton': 'the skeleton shimmer, while a page loads',
    '.refresh-spinning': 'the refresh icon, while it refreshes',
  };
  const STYLESHEETS = () => allStylesheets.map((f) => code(relative(SRC, f))).join('\n');
  it('loops nothing but work in progress, and pins every .new-blink rule to five runs', () => {
    expect(new Set(loopsIn(STYLESHEETS()))).toEqual(new Set(Object.keys(LOOPS)));
    expect(blinksOffPin(STYLESHEETS())).toEqual([]);
    expect(code('app/ui/navigation.css')).toMatch(/\.new-blink \{\s*animation: rt-new-blink 1s var\(--ease-out\) 5;/);
    expect(code('app/ui/navigation.css')).toMatch(/@media \(prefers-reduced-motion: reduce\) \{\s*\.new-blink \{\s*animation: none;/);
    const sources = files(SRC, /\.tsx?$/).map((f) => relative(SRC, f));
    for (const file of sources) expect(animatesInCode(code(file)) ? file : null).toBeNull();
  });

  it.each([
    ['the name after the duration', '.x { animation: 2s pulse infinite; }'],
    ['the longhand count', '.x { animation-name: pulse; animation-iteration-count: infinite; }'],
    ['a longhand count over a shorthand', '.x { animation: pulse 1s; animation-iteration-count: infinite; }'],
    ['a list of counts', '.x { animation-name: spin, pulse; animation-iteration-count: 1, infinite; }'],
    ['the second item of a list', '.x { animation: spin 1s linear, pulse 2s infinite; }'],
    ['a name in a var()', '.x { animation: var(--pulse); }'],
    ['a longhand name in a var()', '.x { animation-name: var(--n); }'],
    ['a count in a var()', '.x { animation: pulse 1s var(--forever); }'],
    ['the -webkit- prefix', '.x { -webkit-animation: pulse 1s infinite; }'],
    ['an important shorthand', '.x { animation: pulse 1s infinite !important; }'],
    ['a count above one', '.x { animation: pulse 1s 30; }'],
    ['a longhand count above one', '.x { animation-name: pulse; animation-iteration-count: 3; }'],
    ['a count above one in a list', '.x { animation: spin 1s linear, pulse 2s 2.5; }'],
    ['a count as an exponent', '.x { animation: pulse 1s 1e3; }'],
    ['a longhand count as an exponent', '.x { animation-iteration-count: 1e3; }'],
    ['a longhand count in calc()', '.x { animation-iteration-count: calc(10); }'],
  ])('fails a loop written with %s', (_way, rule) => {
    expect(loopsIn(rule)).toEqual(['.x']);
  });

  it('lets a fade that runs once on the motion tokens, and no animation, pass', () => {
    expect(loopsIn('.a { animation: pulse var(--motion) var(--ease-out); }\n.b { animation: none; }\n.c { animation-iteration-count: 1; }')).toEqual([]);
    expect(loopsIn('.d { animation: fade 1.5s cubic-bezier(0.2, 0, 0, 1) 0.2s 1; }\n.e { animation: tick 2s steps(4) both; }')).toEqual([]);
    expect(loopsIn('.new-blink { animation: rt-new-blink 1s var(--ease-out) 5; }')).toEqual([]);
    expect(loopsIn('* { animation-iteration-count: 1 !important; }\n.f { animation-iteration-count: 0.5, 1; }')).toEqual([]);
  });

  it.each([
    ['set to loop', '.new-blink { animation-iteration-count: infinite; }'],
    ['given another animation', '.new-blink { animation: pulse 1s 5; }'],
    ['run more times', 'nav .new-blink { animation: rt-new-blink 1s var(--ease-out) 50; }'],
    ['with a loop on a descendant', '.new-blink .spinner { animation: spin 1s infinite; }'],
    ['with a loop on a compound selector', '.new-badge.new-blink { animation: rt-new-blink 1s var(--ease-out) infinite; }'],
  ])('fails a .new-blink rule %s in any stylesheet', (_way, rule) => {
    expect(blinksOffPin(rule)).toHaveLength(1);
  });

  it.each([
    ['a style key', '<span style={{ animation: "pulse 1s infinite" }} />'],
    ['a quoted style key', "<span style={{ 'animation': 'pulse 1s' }} />"],
    ['WebkitAnimation', '<span style={{ WebkitAnimation: "pulse 1s" }} />'],
    ['style.animation', "el.style.animation = 'pulse 1s';"],
    ['setProperty', "el.style.setProperty('animation', 'pulse 1s');"],
    ['animate with Infinity', 'el.animate([{ opacity: 1 }, { opacity: 0.3 }], { duration: 1000, iterations: Infinity });'],
    ['animate with Number.POSITIVE_INFINITY', 'el.animate(frames, { duration: 1000, iterations: Number.POSITIVE_INFINITY });'],
    ['an imported value as a style key', '<span style={{ animation }} />'],
    ['an imported value after another key', '<span style={{ opacity, animation }} />'],
  ])('fails code that animates with %s', (_way, source) => {
    expect(animatesInCode(source)).toBe(true);
  });

  it('lets code that only names a class pass', () => {
    expect(animatesInCode('<span className="new-badge new-blink" />')).toBe(false);
  });

  it('reads the property out of each transition item, and an item without one as all', () => {
    expect(transitioned('transform var(--motion) var(--ease-out)')).toEqual(['transform']);
    expect(transitioned('background var(--transition), border-color 120ms cubic-bezier(0.2, 0, 0, 1)')).toEqual(['background', 'border-color']);
    expect(transitioned('var(--transition)')).toEqual(['all']);
    expect(transitioned('200ms ease-out 50ms box-shadow')).toEqual(['box-shadow']);
  });

  it('keeps every motion exception in use, so the list stays short', () => {
    for (const [file, list] of Object.entries(MOTION_EXCEPTIONS)) {
      const css = code(file);
      const used = [...css.matchAll(/(?<![\w-])transition(?:-property)?\s*:\s*([^;}]+)/g)].flatMap(([, v]) => transitioned(v as string));
      const framed = [...css.matchAll(/@keyframes\s+[\w-]+\s*\{((?:[^{}]*\{[^{}]*\})*\s*)\}/g)].flatMap(([, f]) => [...(f as string).matchAll(/([a-z-]+)\s*:/g)].map((m) => m[1]));
      for (const { property } of list) expect([...used, ...framed], `${file}: ${property} is excepted but never animated`).toContain(property);
    }
  });

  it('the motion tokens are 120 and 200 ms on the ease-out curve, with a display size for the key amount', () => {
    const theme = code('app/theme.css');
    expect(theme).toMatch(/--motion-fast:\s*120ms/);
    expect(theme).toMatch(/--motion:\s*200ms/);
    expect(theme).toMatch(/--ease-out:\s*cubic-bezier\(0\.2,\s*0,\s*0,\s*1\)/);
    expect(theme).toMatch(/--text-display:/);
    expect(code('app/ui/base.css')).toMatch(/prefers-reduced-motion: reduce\)\s*\{\s*:root\s*\{[^}]*--motion:\s*0ms/);
  });

  // Pressed (#682): every button, tile, card and row answers a tap by the shared press alone: transform and opacity, nothing
  // else, in --motion-fast (100 to 160 ms) on the ease-out curve, and no shrinking under reduced motion. A plain <button> takes
  // it from buttons.css, so a screen never draws its own. The busy rule only cancels the press, so it is not a press.
  // JUSTIFICATION-A3: #682 asks the guard to hold the press; no existing test checks :active rules.
  it('presses by transform and opacity only, in 100 to 160 ms on the ease-out curve, and not by scale under reduced motion', () => {
    const pressed = allStylesheets.flatMap((f) =>
      rulesOf(code(relative(SRC, f)))
        .filter(({ selectors }) => selectors.some((s) => /:active\b/.test(s)) && !/aria-busy/.test(selectors.join()))
        .flatMap(({ selectors, body }) => [...body.matchAll(/([a-z-]+)\s*:/g)].map(([, prop]) => `${selectors.join(', ')} ${prop}`)),
    );
    expect(pressed.length).toBeGreaterThan(4);
    expect(pressed.filter((p) => !/ (transform|opacity)$/.test(p))).toEqual([]);
    const buttons = rulesOf(code('app/ui/buttons.css'));
    const base = buttons.find(({ selectors }) => selectors.length === 2 && selectors.includes('button') && selectors.includes('.btn'));
    expect(transitioned(/transition:\s*([^;]+)/.exec(base?.body ?? '')?.[1] ?? '')).toEqual(['transform', 'opacity']);
    const press = buttons.find(({ selectors }) => selectors[0]?.startsWith('button:active:not('));
    expect(press?.body).toMatch(/transform:\s*scale\(var\(--press-scale\)\)/);
    expect(press?.body).toMatch(/opacity:\s*var\(--press-opacity\)/);
    const theme = code('app/theme.css');
    const fast = Number(/--motion-fast:\s*(\d+)ms/.exec(theme)?.[1]);
    expect(fast).toBeGreaterThanOrEqual(100);
    expect(fast).toBeLessThanOrEqual(160);
    expect(theme).toMatch(/--transition:\s*var\(--motion-fast\)\s+var\(--ease-out\)/);
    expect(code('app/ui/base.css')).toMatch(/prefers-reduced-motion: reduce\)\s*\{\s*:root\s*\{[^}]*--press-scale:\s*1;/);
  });

  // The palette tokens do not change with the theme (#429), so a stylesheet draws with the colour roles, which do. The only
  // palette colours left are white text on a strong fill, black for a darker mix, and the QR code's fixed pair.
  it.each(stylesheets.map((f) => relative(SRC, f)))('%s draws with theme roles, not palette tokens', (file) => {
    const css = readFileSync(join(SRC, file), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
    const palette = [...css.matchAll(/var\((--rt-[a-z0-9-]+)\)/g)].map((m) => m[1]);
    expect(palette.filter((name) => !['--rt-white', '--rt-black', '--rt-grey-900'].includes(name as string))).toEqual([]);
  });
  // Hillary, 23 Oct 2026: a thick coloured stripe on the left edge of alerts, toasts and cards is a tell of generated UI.
  // Status is shown by a tinted background, an icon or words, never by a side stripe.
  it('draws no coloured side or top stripe in the design layer', () => {
    for (const file of stylesheets) {
      const css = code(relative(SRC, file));
      expect(css, relative(SRC, file)).not.toMatch(/border-(left|inline-start)(-width)?\s*:\s*([2-9]|\d{2,})px/);
      expect(css, relative(SRC, file)).not.toMatch(/border-(left|top|inline-start|block-start)\s*:\s*([2-9]|\d{2,})px\s+solid\s+var\(--color-(accent|primary|danger|warning|success)/);
      expect(css, relative(SRC, file)).not.toMatch(/inset\s+([2-9]|\d{2,})px\s+0\s+0/);
      expect(css, relative(SRC, file)).not.toMatch(/inset\s+0\s+([2-9]|\d{2,})px\s+0\s+var\(--color-(accent|primary|danger|warning|success)/);
    }
  });
});

// The component kit (#520): the rules of docs/ui-rules.md that a test can read off the source.
describe('design guard, the rules the kit holds every screen to', () => {
  const sources = files(SRC, /\.(tsx?|css)$/).map((f) => relative(SRC, f));

  // Rule 3: an emoji draws differently on every phone and reads as generated. Icons come from components/icons.tsx.
  it.each(sources)('%s draws no emoji', (file) => {
    expect(code(file).match(/\p{Emoji_Presentation}|️/u)?.[0] ?? null, file).toBeNull();
  });

  // Rule 17 and CLAUDE.md: no em or en dash anywhere, in copy, comments or styles.
  it.each(sources)('%s writes no dash', (file) => {
    expect(readFileSync(join(SRC, file), 'utf8').match(/[\u2013\u2014]/)?.[0] ?? null, file).toBeNull();
  });

  // Rule 2: no gradient on text, buttons or backgrounds, no glow and no glass. The two gradients left are not paint: the table's
  // scroll shadows (a hint that it scrolls) and the old skeleton shimmer, which phase 3 replaces with the kit's Skeleton.
  const GRADIENTS = new Set(['app/ui/tables.css', 'app/ui/feedback.css']);
  it.each(allStylesheets.map((f) => relative(SRC, f)))('%s paints no gradient, glow or glass', (file) => {
    const css = code(file);
    if (!GRADIENTS.has(file)) expect(css, file).not.toMatch(/gradient\(/);
    expect(css, file).not.toMatch(/text-shadow|backdrop-filter|filter:\s*blur/);
    // A glow is a blurred shadow in the brand or a status colour; a focus ring (no blur) and the neutral shadows are not.
    for (const [, value] of css.matchAll(/box-shadow:\s*([^;]+);/g)) {
      for (const layer of (value as string).split(/,(?![^(]*\))/)) {
        const blur = Number(layer.trim().replace(/^inset\s+/, '').split(/\s+/)[2]?.replace('px', '') ?? 0);
        const coloured = /var\(--color-(accent|primary|success|danger|warning|link|focus)/.test(layer);
        expect(blur > 0 && coloured ? layer.trim() : null, `${file} glows`).toBeNull();
      }
    }
  });

  it('keeps the gradient list to files that still draw one', () => {
    for (const file of GRADIENTS) expect(code(file), file).toMatch(/gradient\(/);
  });

  // Rule 11: a choice of 2 to 5 options is the one Segmented control, not a row of separate pressed buttons.
  it.each(components.map((f) => relative(SRC, f)))('%s draws no row of pressed buttons in place of Segmented', (file) => {
    expect(code(file), file).not.toMatch(/className=\{?["'`][^"'`]*(?<![\w-])filters(?![\w-])/);
  });

  const kit = code('app/ui/kit.css');
  const rule = (selector: string) => kit.match(new RegExp(`\\n${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} \\{([^}]*)\\}`))?.[1] ?? '';

  // Rule 4: two radii and two shadows. The kit keeps to --radius and --radius-lg, --shadow-sm and --shadow-lg.
  it('the kit uses the two radii and the two shadows only', () => {
    const radii = [...kit.matchAll(/border-radius:\s*([^;]+);/g)].flatMap(([, v]) => (v as string).split(/\s+/));
    expect(new Set(radii)).toEqual(new Set(['var(--radius)', 'var(--radius-lg)', '0']));
    // The focus ring is not a depth: it is the keyboard ring, put back where a reset removed it.
    const shadows = [...kit.matchAll(/box-shadow:\s*([^;]+);/g)].flatMap(([, v]) => (v as string).split(/,\s*/)).filter((v) => v !== 'var(--focus-ring)');
    expect(new Set(shadows)).toEqual(new Set(['none', 'var(--shadow-sm)', 'var(--shadow-lg)']));
  });

  // Rule 12: every target of the kit is at least 48px. A bare button already has min-height: var(--tap) (buttons.css).
  it('gives every kit target 48px', () => {
    expect(rule('.segmented > button')).toMatch(/min-height: var\(--tap\)/);
    expect(rule('.stepper > button')).toMatch(/width: var\(--tap\)/);
    expect(rule('.stepper')).toMatch(/grid-template-columns: var\(--tap\) [^;]+ var\(--tap\)/);
    expect(rule('.sheet-close')).toMatch(/width: var\(--tap\)/);
    expect(rule('.list-row')).toMatch(/min-height: 64px/);
  });

  // Rules 10 and 12 on the inline Add an item of Buy stock and Count stock (issue #505): every field is the kit's Field and
  // required (red asterisk, aria-required), the form starts with the needed line, and its controls are plain buttons and fields,
  // which buttons.css and forms.css keep at --tap, 48px. Issue #562: the Item code is text the app makes, never a field.
  it('builds the inline Add an item from Field and the category picker, every field required, the Item code as text and 48px controls', () => {
    const form = code('areas/staff/retail/new-item-here.tsx');
    const fields = [...form.matchAll(/<Field\b([\s\S]*?)\n\s*\/>/g)].map(([, props]) => props as string);
    expect(fields.length).toBe(4);
    expect(form).toContain('<ItemCodeLine id={`${id}-code`}');
    expect(fields.filter((props) => /\n\s*required\n/.test(props)).map((props) => props.match(/id=\{`\$\{id\}-(\w+)`\}/)?.[1]))
      .toEqual(['description', 'cost', 'sell', 'qty']);
    // Counted in is the unit picker (issue #619), which draws its own Field, required unless told otherwise, after the description.
    expect(form).toMatch(/<UnitPicker\n\s*id=\{`\$\{id\}-unit`\}/);
    expect(code('areas/staff/retail/unit-picker.tsx')).toMatch(/required = true/);
    expect(form.indexOf('<UnitPicker')).toBeGreaterThan(form.indexOf(`id={\`\${id}-description\`}`));
    // The category is the one picker (#578), which draws its own Field, required, first.
    expect(form.match(/<CategoryPicker\b([\s\S]*?)\n\s*\/>/)?.[1]).toMatch(/id=\{`\$\{id\}-category`\}[\s\S]*\n\s*required\n/);
    expect(form.indexOf('<CategoryPicker')).toBeLessThan(form.search(/<Field\s/));
    expect(form).toContain('<FieldsNeededNote />');
    expect(form).not.toMatch(/<input\b|<label\b|btn-sm|style=\{/);
    expect(code('app/theme.css')).toMatch(/--tap: 48px;/);
    expect(code('app/ui/buttons.css').match(/\nbutton,\n\.btn \{([^}]*)\}/)?.[1]).toMatch(/min-height: var\(--tap\)/);
  });

  // Rule 8: every amount the kit draws is in tabular figures, and the success amount is the display size.
  it('draws every kit amount in tabular figures', () => {
    for (const selector of ['.stepper > input', '.money-input > input', '.list-row-amount', '.success-amount']) {
      expect(rule(selector), selector).toMatch(/font-variant-numeric: tabular-nums/);
    }
    expect(rule('.success-amount')).toMatch(/font-size: var\(--text-display\)/);
  });

  // Rule 13: under reduced motion every animation stops: base.css ends them all, and kit.css names each of its own.
  it('stops every animation under reduced motion', () => {
    const reduce = code('app/ui/base.css').match(/prefers-reduced-motion: reduce\)\s*\{([\s\S]*?)\n\}/)?.[1] ?? '';
    expect(reduce).toMatch(/animation-duration: 1ms !important/);
    expect(reduce).toMatch(/animation-iteration-count: 1 !important/);
    const still = kit.match(/prefers-reduced-motion: reduce\)\s*\{([\s\S]*?)\n\}/)?.[1] ?? '';
    expect(still).toMatch(/animation: none/);
    const animated = [...kit.matchAll(/\n([^\n{}]+)\{[^}]*animation(?:-name)?: (?!none)/g)].map(([, sel]) => (sel as string).trim());
    expect(animated.length).toBeGreaterThanOrEqual(6);
    for (const selector of animated) expect(still, `${selector} still moves under reduced motion`).toContain(selector);
  });

  // Rule 13 (owner rule, 24 Oct 2026): the kit moves on the motion tokens, 300ms at most, never in a loop; only the sale-complete
  // check takes 400ms.
  it('times every kit motion with the tokens, never loops, and only the check takes 400 ms', () => {
    const times = [...kit.matchAll(/animation:\s*[\w-]+\s+([^\s;]+)/g)].map(([, t]) => t);
    expect(new Set(times)).toEqual(new Set(['var(--motion)', '400ms']));
    expect(kit).not.toMatch(/infinite/);
    expect(rule('.success-tick')).toMatch(/400ms/);
    expect(rule('.success-tick-inner')).toMatch(/400ms/);
    expect([...kit.matchAll(/animation:\s*[\w-]+\s+400ms/g)]).toHaveLength(2);
  });
});

// Phase 3a of the UI rework (issue #526): the cross-cutting fixes, each held here so a screen cannot bring the old look back.
describe('design guard, the cross-cutting fixes of the 70-screen audit', () => {
  /** The declarations of every rule whose selector matches `selector`, across the design layer. */
  const rulesFor = (selector: RegExp) =>
    stylesheets.flatMap((f) => [...code(relative(SRC, f)).matchAll(/([^{}]+)\{([^{}]*)\}/g)].filter(([, sel]) => selector.test(sel as string)).map(([, sel, body]) => ({ file: relative(SRC, f), sel: (sel as string).trim(), body: body as string })));

  // Item 5: an empty list, a "nothing in range" and a no-access line are one plain line, never a dashed box.
  it('draws no empty state as a box: no dashed edge, and no border at all', () => {
    const empty = rulesFor(/empty/);
    expect(empty.length).toBeGreaterThan(1);
    for (const { file, sel, body } of empty) {
      expect(body, `${file}: ${sel}`).not.toMatch(/dashed/);
      expect(body, `${file}: ${sel}`).not.toMatch(/(^|[;\s])border(-style|-width)?\s*:\s*(?!0|none)/);
    }
  });

  it('keeps every no-access line on the one NoAccess, which says who can give access and links Home', () => {
    for (const file of components.map((f) => relative(SRC, f))) {
      expect(code(file), file).not.toMatch(/<p className="empty-state">You do not have access/);
    }
    const kitFile = code('app/ui/EmptyState.tsx');
    expect(kitFile).toMatch(/The owner or an administrator of the business can give it to you/);
    expect(kitFile).toMatch(/Go to Home/);
  });

  // Item 13: an information note is neutral grey with the info icon: no lavender, indigo or brand tint, which reads as a status.
  it('draws the info note neutral, with its icon, and no chosen row in a tint', () => {
    const info = rulesFor(/^\s*\.alert-info\s*$/);
    expect(info).toHaveLength(1);
    expect(info[0]?.body).toMatch(/background: var\(--color-neutral-tint\)/);
    expect(info[0]?.body).not.toMatch(/accent|primary|brand|info-tint/);
    expect(rulesFor(/\.alert-info::before/)[0]?.body).toMatch(/mask:/);
    expect(rulesFor(/label:has\(> input:checked\)/)[0]?.body).not.toMatch(/accent-tint|info-tint/);
  });

  // Item 6: the main action is brand filled and never looks off; on a phone it sticks full width at the bottom.
  it('keeps the main action of the forms of the audit in the sticky ActionBar, and never greys one whose fields are not ready', () => {
    for (const screen of ['banking', 'expenses', 'savings', 'withdrawals', 'usage', 'transfer', 'catalogue-import', 'labels', 'sale', 'restock', 'shop-setup']) {
      expect(code(`areas/staff/retail/${screen}.tsx`), screen).toMatch(/<ActionBar\b|<SaleBar\b/);
    }
    const phone = code('app/ui/kit.css').match(/@media \(max-width: 719px\) \{\s*\.action-bar \{([^}]*)\}/)?.[1] ?? '';
    expect(phone).toMatch(/position: sticky;\s*bottom: 0;/);
    const busy = code('app/ui/BusyButton.tsx');
    // A form passes notReady while its fields are not right (the button stays enabled); disabled keeps its plain meaning.
    expect(busy).toMatch(/const notReady = fieldsNotReady && !busy && !disabled;/);
    expect(busy).toMatch(/data-not-ready=\{notReady \|\| undefined\}/);
    expect(busy).toMatch(/focusFirstMissing/);
  });

  // Item 8: a date range is the one PeriodPicker (Today, This week, This month, Custom in a sheet), never two bare date fields,
  // and dates read "23 Oct 2026" through the one formatter, showDate.
  it('draws every date range with the PeriodPicker and formats dates in one place', () => {
    for (const file of components.map((f) => relative(SRC, f)).filter((f) => f !== 'app/ui/PeriodPicker.tsx')) {
      expect(code(file).match(/>From<\/label>[^\n]{0,40}\n?[^\n]{0,200}type="date"|>From<\/label><input [^>]*type="date"/)?.[0] ?? null, file).toBeNull();
    }
    expect(code('areas/staff/retail/maths.ts')).toMatch(/export \{ showDate \} from '\.\.\/\.\.\/\.\.\/app\/ui\/PeriodPicker'/);
  });

  // Item 10: a related screen is a row with a chevron, an action a button; never an underlined word.
  it('draws links to other screens as chevron rows and figures that open rows without an underline', () => {
    // A step of a Next row (issue #700) is a plain button too.
    expect(code('areas/staff/retail/screen-link.tsx')).toMatch(/action \? 'btn btn-primary' : step \? 'btn' : 'link-row'/);
    for (const sel of [/^\s*\.link-row\s*$/, /^\s*\.figure-link\s*$/]) expect(rulesFor(sel)[0]?.body).toMatch(/text-decoration: none/);
    expect(code('areas/staff/retail/analytics-ui.tsx').match(/<Link className="figure-link"/g)).toHaveLength(2);
    expect(code('areas/staff/home-cards.tsx')).not.toMatch(/<Link to="[^"]*">Open the/);
  });

  // Item 14: every figure in tabular digits, amounts only through formatMinor, and the key figure at the display size.
  it('draws figures tabular everywhere, formats money in one place, and shows the key figure large', () => {
    expect(rulesFor(/^\s*body\s*$/).map((r) => r.body).join('')).toMatch(/font-variant-numeric: tabular-nums/);
    const value = rulesFor(/^\s*\.key-figure-value\s*$/)[0]?.body ?? '';
    expect(value).toMatch(/font-size: var\(--text-display\)/);
    expect(value).toMatch(/font-variant-numeric: tabular-nums/);
    for (const file of components.map((f) => relative(SRC, f)).filter((f) => !/mock/.test(f))) {
      expect(code(file).match(/`UGX \$\{|Intl\.NumberFormat\([^)]*currency/)?.[0] ?? null, file).toBeNull();
    }
    for (const screen of ['areas/staff/home-cards.tsx', 'areas/staff/retail/profit.tsx', 'areas/staff/retail/savings.tsx', 'areas/staff/retail/credit-control.tsx']) {
      expect(code(screen), screen).toMatch(/<KeyFigure\b/);
    }
  });

  // Item 15: a help text is one line on screen, the rest behind the info button of the kit's Hint.
  it('draws no help paragraph of more than one sentence, or longer than a line, outside the Hint', () => {
    for (const file of components.map((f) => relative(SRC, f)).filter((f) => f.startsWith('areas/'))) {
      for (const [, text] of code(file).matchAll(/<p className="hint"(?: data-tour="[^"]*")?>([^<>{}]+)<\/p>/g)) {
        const long = (text as string).trim().length > 90 || ((text as string).match(/[.!?](\s|$)/g) ?? []).length > 1;
        expect(long ? `${file}: ${text}` : null).toBeNull();
      }
    }
    expect(rulesFor(/^\s*\.hint-line > \.hint\s*$/)[0]?.body).toMatch(/white-space: nowrap;[\s\S]*text-overflow: ellipsis/);
  });

  // Issue #575: a normal settings screen draws no status colour. A note about a default (returns with no approval limit) is one
  // neutral Hint line, the long explanation behind the Hint's info button, and a link to the returns limit on the approval
  // rules (issue #343).
  it('draws the Retail settings screens without an amber note, and the returns note as a Hint', () => {
    for (const file of ['settings', 'purchasing-settings', 'credit-recognition', 'slow-movers']) {
      // A badge that marks an unsaved change ("Will be switched on") is real status and may stay amber.
      // A class as a string or a template literal, or a tone prop, in amber or a warning tone.
      const amber = /className=(?:"[^"]*|\{`[^`]*)(?:(?:alert|text|note|hint)-warning|amber)|tone=(?:"|\{['"`])(?:warning|amber)/;
      expect(code(`areas/staff/retail/${file}.tsx`).match(amber)?.[0] ?? null, file).toBeNull();
    }
    const note = code('areas/staff/retail/settings.tsx').match(/export function ReturnsThresholdWarning[\s\S]*?\n\}/)?.[0] ?? '';
    expect(note).toMatch(/<Hint>\s*Returns need no second administrator yet\. /);
    expect(note).toMatch(/<Link to="\/staff\/setup\/approvals" hash="rule-retail_sale_return"/);
    expect(note).not.toMatch(/tone=|warning|amber/);
  });

  // Item 3: on a phone the crumb is one back link with no slash before it.
  it('draws no slash before the one crumb a phone shows', () => {
    const nav = code('app/ui/navigation.css');
    const phone = nav.slice(nav.indexOf('.breadcrumbs li:not(.crumb-parent)'));
    expect(phone).toMatch(/\.breadcrumbs li\.crumb-parent::before \{\s*content: none;/);
    expect(nav).toMatch(/\.breadcrumbs li \+ li::before/);
    expect(nav).not.toMatch(/\.breadcrumbs li::before/);
  });
});

// Rule 26 (issue #688): a picker that chooses a record (a category, a unit, a supplier, a customer, a brand, a pack, a branch)
// is the shared PickerWithAdd (or its hook useAddInPlace), whose list ends with "+ Add a <thing>". A hand-built <select> over a
// list of those records fails here, unless it is listed below with the reason it stays as it is. The list is read off the name
// of the list the options map, so a select of records under another name is for review to catch.
// JUSTIFICATION-A3: issue #688 asks for this guard; it reuses runtimeSelects of rule 25 below rather than a second parser.
const RECORD_LIST = /categor|unit|supplier|customer|buyer|brand|pack|branch|places/i;
// The exemptions, each with its reason (the issue asks for a list with reasons; JUSTIFICATION-A3: one entry per picker found).
const BRANCH = 'a branch is set up on the Branches screen with its own checks, never in the middle of this form';
const ITEM_PACK = "an item's pack needs its size, use and price, so it is added on the item's page, whose pack form adds a unit in place";
const ADD_IN_PLACE_EXEMPT: Record<string, string> = {
  'areas/staff/retail/account.tsx branches': BRANCH,
  'areas/staff/retail/purchasing-ui.tsx branches': BRANCH,
  'areas/staff/retail/return-new.tsx branches.filter((b) => b.stocks_goods !== false)': BRANCH,
  // Issue #728: Quick add's Where became AddsTo, the shop beside How many with Change, shared with Add an item.
  'areas/staff/branch-picker.tsx places': `Where holds only the branches this person may add stock at; ${BRANCH}`,
  'areas/staff/retail/catalogue-barcodes.tsx packs': ITEM_PACK,
  'areas/staff/retail/goods-receive.tsx line.packs': ITEM_PACK,
  'areas/staff/retail/purchase-order-form.tsx line.packs': ITEM_PACK,
  'areas/staff/retail/restock.tsx packs': ITEM_PACK,
  'areas/staff/retail/sale.tsx packs': ITEM_PACK,
  'areas/staff/retail/unit-usual-pack.tsx choices': 'It holds sits in the Usual pack sheet of the Units page, so a second sheet is not opened over it; with no unit to hold it adds one in place',
  'areas/staff/retail/new-item-packs.tsx choices': 'the levels of the ladder being typed on this form (issue #699), whose packages are added right above it',
  'areas/staff/retail/price-lists.tsx units': "the item's own unit and its packs, not the list of units; a pack is added on the item's page",
  'areas/staff/retail/expenses.tsx activeCategories(list)': 'expense categories hold the items an expense is booked to; the empty list opens their screen and comes back (rule 25); moves onto PickerWithAdd next',
  'areas/staff/retail/sale.tsx (customers.data ?? [])': 'the credit buyer already adds a buyer in place with a name and a phone, saved with the sale and kept in the draft; moves onto PickerWithAdd next',
  'areas/staff/retail/sale.tsx (offline?.catalogue?.customers ?? [])': 'the offline sale reads the customers kept on the phone, and adding one needs the internet',
};

// A list under a plain name (choices, options, rows) is a record list too in a file that works with records (review of #691:
// the pack form's unit select mapped `choices`).
const PLAIN_LIST = /^\(?(?:choices|options|rows|list|items)\b/;
const RECORD_TYPES = /\btype (?:Unit|Supplier|Customer|Category|Branch)\b|: (?:Unit|Supplier|Customer|Category)\[\]/;

/** The hand-built record pickers of `source`, as `list` (the list the options map). */
export function recordSelects(source: string): string[] {
  const records = RECORD_TYPES.test(source);
  return runtimeSelects(source).map(({ list }) => list).filter((list) => RECORD_LIST.test(list) || (records && PLAIN_LIST.test(list)));
}

describe('design guard, a record picker adds what is missing in place (issue #688)', () => {
  const sources = components.map((f) => relative(SRC, f)).filter((f) => f !== 'app/ui/PickerWithAdd.tsx');

  it('finds a select of records and passes one of something else', () => {
    expect(recordSelects('<select>{suppliers.map((s) => <option />)}</select>')).toEqual(['suppliers']);
    expect(recordSelects('<select>{(customers.data ?? []).map((c) => <option />)}</select>')).toEqual(['(customers.data ?? [])']);
    expect(recordSelects('<select>{periods.map((p) => <option />)}</select>')).toEqual([]);
    expect(recordSelects('<select>{PACKS.map((p) => <option />)}</select>')).toEqual([]);
    expect(recordSelects("import { type Unit } from 'x';\n<select>{choices.map((u) => <option />)}</select>")).toEqual(['choices']);
    expect(recordSelects('<select>{choices.map((c) => <option />)}</select>')).toEqual([]);
  });

  it.each(sources)('%s builds every record picker on PickerWithAdd, or is exempt with a reason', (file) => {
    const found = recordSelects(readFileSync(join(SRC, file), 'utf8')).map((list) => `${file} ${list}`);
    expect(found.filter((key) => !ADD_IN_PLACE_EXEMPT[key])).toEqual([]);
  });

  it('keeps every exemption in use and gives each a reason', () => {
    const all = new Set(sources.flatMap((file) => recordSelects(readFileSync(join(SRC, file), 'utf8')).map((list) => `${file} ${list}`)));
    for (const [key, why] of Object.entries(ADD_IN_PLACE_EXEMPT)) {
      expect(all.has(key) ? key : null, `${key} is exempt but no longer found`).toBe(key);
      expect(why.trim().length, key).toBeGreaterThan(20);
    }
  });
});

// Rule 25 (issue #619): a list a form needs is never a dead end. A <select> whose options are read at run time (a list mapped from
// data or props, not a constant such as METHODS or Object.keys(WORDS)) shows the EmptyChoice when that list is empty, or says
// with an `empty:` comment in the lines just above it why it never is (a filter with its "All", a list the server always fills).
// The comment is read, not blanked, so the reason stays next to the select it excuses.
const CONSTANT_OPTIONS = /^\(*(?:\[|(?:Object\.(?:keys|entries)\()?[A-Z][A-Z0-9_]*\b)/;

/** Each <select> of `source` whose options come from a list read at run time: its line and the list it maps. */
export function runtimeSelects(source: string): { line: number; list: string }[] {
  return [...source.matchAll(/<select\b/g)].flatMap((m) => {
    const body = source.slice(m.index, source.indexOf('</select>', m.index));
    const lists = [...body.matchAll(/\{\s*([^{}]*?)\??\.map\(/g)].map(([, list]) => (list as string).trim()).filter((list) => !CONSTANT_OPTIONS.test(list));
    return lists.length === 0 ? [] : [{ line: source.slice(0, m.index).split('\n').length, list: lists.join(', ') }];
  });
}

/** True when the 12 lines above `line` hold an EmptyChoice or an `empty:` comment, or the 15 below an EmptyChoice (its other branch). */
export function handlesEmpty(source: string, line: number): boolean {
  const lines = source.split('\n');
  const above = lines.slice(Math.max(0, line - 13), line).join('\n');
  const below = lines.slice(line, line + 15).join('\n');
  return /<EmptyChoice\b|\/\/ empty: \S|\{\/\* empty: \S/.test(above) || /<EmptyChoice\b/.test(below);
}

describe('design guard, an empty list is never a dead end (issue #619)', () => {
  const sources = components.map((f) => relative(SRC, f));

  it('finds the selects of run-time lists, and passes a constant list or one handled', () => {
    expect(runtimeSelects('<select>{METHODS.map((m) => <option />)}</select>')).toEqual([]);
    expect(runtimeSelects('<select>{(Object.keys(WORDS) as W[]).map((m) => <option />)}</select>')).toEqual([]);
    expect(runtimeSelects('<select>\n{(units.data ?? []).map((u) => <option />)}</select>')).toEqual([{ line: 1, list: '(units.data ?? [])' }]);
    expect(runtimeSelects('<select>{lists.data?.map((u) => <option />)}</select>')).toEqual([{ line: 1, list: 'lists.data' }]);
    expect(handlesEmpty('x\n<select>', 2)).toBe(false);
    expect(handlesEmpty('{units.length === 0 ? <EmptyChoice id="u" /> : (\n<select>', 2)).toBe(true);
    expect(handlesEmpty('// empty: a filter, "All" is always there\n<select>', 2)).toBe(true);
  });

  // The lending forms' SelectField draws the EmptyChoice itself when given `empty`: one of a run-time list must pass it.
  it.each(sources)('%s passes `empty` to every SelectField of a run-time list', (file) => {
    const source = readFileSync(join(SRC, file), 'utf8');
    const missing = [...source.matchAll(/<SelectField\b[\s\S]*?\/>/g)]
      .map(([tag]) => tag as string)
      .filter((tag) => /options=\{\s*\(?[a-z]/.test(tag) && !/\bempty=/.test(tag) && !/\/\* empty: \S/.test(tag))
      .map((tag) => `${file}: ${tag.slice(0, 80)}`);
    expect(missing).toEqual([]);
  });

  it.each(sources)('%s shows the EmptyChoice for every select of a run-time list, or says why it is never empty', (file) => {
    const source = readFileSync(join(SRC, file), 'utf8');
    const missing = runtimeSelects(source).filter(({ line }) => !handlesEmpty(source, line)).map(({ line, list }) => `${file}:${line} maps ${list}`);
    expect(missing).toEqual([]);
  });
});

// Issue #680: a retail column or label says Selling price or Buying price, never a bare Price or Cost ("Average cost" is a retired
// word of the glossary guard): a table header, a card row's span, a term, a form label, a card heading, or a label prop (Stat,
// KeyFigureRow, Field). JUSTIFICATION-A3: the issue asks for this design-guard test; it is one regex and its fixtures.
const BARE_PRICE_LABEL = /<(th|span|dt|label|h[2-4]|strong)\b[^>]*>\s*(?:\{\s*['"`])?(?:Price|Cost)(?:['"`]\s*\})?\s*<\/\1>|\blabel=(?:\{\s*)?['"`](?:Price|Cost)['"`]/;

describe('design guard, no bare Price or Cost label on a retail screen (issue #680)', () => {
  it('finds a bare column, row label or label prop, and passes the plain words', () => {
    const bare = ['<th className="num">Price</th>', "<th>{'Cost'}</th>", '<span>Cost</span>', '<dt>Price</dt>', '<label htmlFor="p">Price</label>', '<h3 className="cat-title">Cost</h3>', '<strong>Price</strong>', '<Stat label="Price" />', "<KeyFigureRow label={'Cost'} />"];
    expect(bare.filter((s) => !BARE_PRICE_LABEL.test(s))).toEqual([]);
    const plain = ['<th className="num">Selling price</th>', '<th>Price list</th>', '<span>At buying price</span>', '<h2>Price history</h2>', '<label htmlFor="p">Selling price</label>', '<Stat label="Price lists" />'];
    expect(plain.filter((s) => BARE_PRICE_LABEL.test(s))).toEqual([]);
  });

  it.each([...files(join(SRC, 'areas/staff/retail'), /\.tsx$/), ...files(join(SRC, 'offline'), /\.tsx$/)].map((f) => relative(SRC, f)))('%s has no bare Price or Cost label', (file) => {
    expect(code(file).match(BARE_PRICE_LABEL)?.[0] ?? null).toBeNull();
  });
});

// Issue #825: every button is a 48 by 48px target on a phone and as wide as its label on a laptop (buttons.css), which
// layout/buttons.layout.test.ts measures on the daily screens. A class named as a tap target (.btn, .btn-*, .chip, .pill and
// *-btn, *-button, *-chip, *-pill, *-clear, *-close, *-toggle) is listed here once it has been checked against those sizes, so a
// new one fails until it is; and no rule of a listed class may make its target smaller than --tap. JUSTIFICATION-A3: the issue
// asks for this design-guard rule.
const BUTTON_CLASS = /\.((?:btn(?:-[a-z0-9]+)*)|chip|pill|(?:[a-z0-9-]+-(?:btn|button|chip|pill|clear|close|toggle)))(?![\w-])/g;
// Named like a target but drawn as a picture inside one, never tapped on its own: listed with why, and not held to --tap.
const NOT_TARGETS: Record<string, string> = {
  'icon-chip': 'the icon tile inside a Tile or a landing card, whose whole card is the target',
  'swatch-chip': 'a colour sample on the style page',
};
const BUTTON_CLASSES: Record<string, string> = {
  'find-chip': 'a category filter of My stock and Items, --tap tall',
  'rc-chip': 'a range of the shop charts, --tap tall since #825',
  'sb-clear': 'the Clear of a SearchBox, 48 by 48px since #825',
  'sheet-close': 'the Close of a Sheet, 48px square',
  'side-menu-close': 'the Close of the phone menu, a plain button',
  'tour-close': 'the Close of a tour step, 48px wide',
  btn: 'a link drawn as a button; the base rule of buttons.css',
  'btn-block': 'full width on a phone, its label wide on a laptop',
  'btn-brand': 'the primary fill in the tenant colour',
  'btn-danger': 'destroys or refuses',
  'btn-ghost': 'a quiet action, still a 48px target',
  'btn-icon': 'sizes the icon inside, never the target',
  'btn-lg': 'taller than --tap',
  'btn-primary': 'the one main action',
  'btn-sm': 'smaller text and padding, still 48 by 48px',
  'busy-button': 'keeps its width while busy',
  'help-tip-button': 'a 48px question mark',
  'iv-drill-btn': 'an insights drill down, --tap tall',
  'iv-tip-btn': 'an insights tip, 48 by 48px',
  'iv-value-btn': 'an insights figure that opens its rows, --tap tall',
  'menu-button': 'the phone menu, 48px wide',
  'refresh-button': 'the staff bar Refresh, a plain button',
  'sync-button': 'the staff bar sync indicator, 48 by 48px',
  'top-menu-button': 'the staff bar account menu, 48px wide',
};
// A size of a target (width, height, inline-size, block-size, with or without min- or max-) and its value.
// JUSTIFICATION-A3: review of #833 found the one regex missed max-*, logical sizes, var() and calc(); this replaces it.
const SIZE_DECL = /^\s*(?:(?:min|max)-)?(?:height|width|inline-size|block-size)\s*:\s*(.+?)\s*(?:!important)?\s*$/;
// Every token of theme.css (its first value), so a var() is read as the size it stands for, through other tokens and rem.
const TOKENS = new Map<string, string>();
for (const [, name, value] of code('app/theme.css').matchAll(/(--[\w-]+):\s*([^;]+);/g)) if (!TOKENS.has(name as string)) TOKENS.set(name as string, (value as string).trim());
// The classes of targets that are not named as buttons: the staff top bar's Search and Ask, the Hint's info button, the rows
// and headers that open something, the category picker, the theme menu. A rule ending on one is held to --tap as well.
const TARGET_CLASSES = ['find-open', 'ask-open', 'hint-more', 'list-row', 'link-row', 'cat-head', 'cp-trigger', 'theme-menu-item'];

/** The px a size stands for (px, rem or em at 16px, zero, a token), 'part' for a percentage, or null when the guard cannot tell. */
function pxOf(value: string, depth = 0): number | 'part' | null {
  const v = value.trim();
  if (/^\d*\.?\d+%$/.test(v)) return 'part';
  if (v === '0') return 0;
  const px = /^(\d*\.?\d+)px$/.exec(v)?.[1];
  if (px !== undefined) return Number(px);
  const em = /^(\d*\.?\d+)r?em$/.exec(v)?.[1];
  if (em !== undefined) return Number(em) * 16;
  const token = /^var\((--[\w-]+)\)$/.exec(v)?.[1];
  if (token && depth < 8) {
    const held = TOKENS.get(token);
    if (held === undefined) return null;
    // A token made from --tap and more (the tab bar's height) is at least the target.
    if (/^calc\(/.test(held) && /var\(--tap\)\s*\+/.test(held)) return 48;
    return pxOf(held, depth + 1);
  }
  return null;
}

/** The arguments of a CSS function call, split at its own commas. */
const argsOf = (call: string) => call.slice(call.indexOf('(') + 1, call.lastIndexOf(')')).split(/,(?![^(]*\))/).map((a) => a.trim());

/**
 * True when a size value can be under the 48px target: a length or token under 48px; a min() with any argument under 48px or
 * one the guard cannot read; a max() with no argument of 48px or more; a clamp() whose floor is under 48px; a calc() without
 * --tap. A min() or max() against 100% reads its other arguments. Words such as auto, none, fit-content or 100% pass.
 */
function underTap(value: string): boolean {
  const v = value.trim();
  if (/^min\(/.test(v)) return argsOf(v).map((a) => pxOf(a)).some((n) => n === null || (typeof n === 'number' && n < 48));
  if (/^max\(/.test(v)) return !argsOf(v).map((a) => pxOf(a)).some((n) => n === 'part' || (typeof n === 'number' && n >= 48));
  if (/^clamp\(/.test(v)) { const floor = pxOf(argsOf(v)[0] ?? ''); return !(typeof floor === 'number' && floor >= 48); }
  if (/^calc\(/.test(v)) return !/var\(--tap\)/.test(v);
  const n = pxOf(v);
  if (n === 'part') return false;
  if (n === null) return /^var\(/.test(v);
  return n < 48;
}

/** True when the last part of a selector is a target: a listed button class, a target class, or a button element. */
function namesTarget(selector: string): boolean {
  const last = selector.split(/\s+|>|\+|~/).filter(Boolean).pop() ?? '';
  if (/::/.test(last)) return false;
  return [...last.matchAll(BUTTON_CLASS)].some(([, c]) => (c as string) in BUTTON_CLASSES)
    || TARGET_CLASSES.some((c) => new RegExp(`\\.${c}(?![\\w-])`).test(last))
    || /^button(?![\w-])/.test(last);
}

/** The button classes a stylesheet's selectors name. */
const buttonClassesIn = (css: string) => new Set([...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{/g)].flatMap(([, sel]) => [...(sel as string).matchAll(BUTTON_CLASS)].map(([, c]) => c as string)));

// JUSTIFICATION-A3: the third review of #833 asks for a guard over the order of every sheet's actions; no reader of JSX props exists.
const PRIMARY_CLASS = /\b(?:rt-primary|btn-primary|btn-brand|btn-danger)\b/;

/**
 * What is wrong with the order of each sheet's actions in `source`: the `actions={...}` of every `<Sheet>` (up to its `</Sheet>`
 * or the end of a self-closed tag), its button tags in order (button, BusyButton, a Link drawn as a button), and of two or more
 * the one with a primary class must be the last. A sheet of one button may have any.
 */
function sheetActionProblems(source: string): string[] {
  return [...source.matchAll(/<Sheet\b/g)].flatMap((m) => {
    const rest = source.slice(m.index);
    const start = rest.indexOf('actions={');
    const end = rest.indexOf('</Sheet>');
    if (start < 0 || (end >= 0 && start > end)) return [];
    let depth = 0;
    let i = start + 'actions='.length;
    for (; i < rest.length; i++) {
      if (rest[i] === '{') depth++;
      else if (rest[i] === '}' && --depth === 0) break;
    }
    const actions = rest.slice(start, i);
    // Each button tag's attributes run to the first > outside braces, so an onClick={() => ...} does not end them.
    const tags = [...actions.matchAll(/<(?:button|BusyButton|Link)\b/g)].map((t) => {
      let braces = 0;
      let j = (t.index ?? 0) + t[0].length;
      for (; j < actions.length && !(actions[j] === '>' && braces === 0); j++) braces += actions[j] === '{' ? 1 : actions[j] === '}' ? -1 : 0;
      return actions.slice(t.index, j).match(PRIMARY_CLASS)?.[0] ?? 'plain';
    });
    if (tags.length < 2) return [];
    const primaries = tags.filter((t) => t !== 'plain').length;
    if (primaries === 0) return [`no primary among ${tags.length} buttons`];
    return primaries === 1 && tags.at(-1) !== 'plain' ? [] : [`the primary is not last: ${tags.join(', ')}`];
  });
}

/** The rules of `css` whose selector ends on a target (the target itself, not an icon inside it) and can shrink it. */
function shrinkingButtons(css: string): string[] {
  return rulesOf(css).flatMap(({ selectors, body }) => {
    const targets = selectors.filter(namesTarget);
    return targets.length > 0 && body.split(';').some((decl) => underTap(SIZE_DECL.exec(decl)?.[1] ?? 'auto')) ? targets : [];
  });
}

describe('design guard, every button class keeps the 48px target (issue #825)', () => {
  it('finds a button class and a size under the target, and passes the target and an icon inside', () => {
    expect([...buttonClassesIn('.btn-tiny:hover { color: red; } .save-btn, .x-button > svg { width: 20px; }')].sort()).toEqual(['btn-tiny', 'save-btn', 'x-button']);
    expect(shrinkingButtons('.btn-sm { min-height: 36px; }')).toEqual(['.btn-sm']);
    expect(shrinkingButtons('.btn-sm { height: 2rem; }')).toEqual(['.btn-sm']);
    expect(shrinkingButtons('.bar .btn-ghost { min-width: 0; }')).toEqual(['.bar .btn-ghost']);
    expect(shrinkingButtons('.btn-sm { min-height: var(--tap); padding: 6px 12px; }')).toEqual([]);
    expect(shrinkingButtons('.btn-icon svg { width: 20px; height: 20px; }')).toEqual([]);
    expect(shrinkingButtons('.btn-lg { min-height: 52px; }')).toEqual([]);
    // The bypasses the review of #833 found: a maximum, a logical size, a small token and a sum the guard cannot work out.
    expect(shrinkingButtons('.btn-sm { max-height: 36px; }')).toEqual(['.btn-sm']);
    expect(shrinkingButtons('.btn-sm { block-size: 40px; }')).toEqual(['.btn-sm']);
    expect(shrinkingButtons('.btn-sm { min-inline-size: 2em; }')).toEqual(['.btn-sm']);
    expect(shrinkingButtons('.btn-sm { min-height: var(--space-6); }')).toEqual(['.btn-sm']);
    expect(shrinkingButtons('.btn-sm { height: calc(2rem + 4px); }')).toEqual(['.btn-sm']);
    expect(shrinkingButtons('.rc-chip { min-height: 40px; }')).toEqual(['.rc-chip']);
    expect(shrinkingButtons('.btn-sm { height: calc(var(--tap) + 4px); max-width: min(100%, var(--button-max)); }')).toEqual([]);
    // The second review of #833: a token through another token, a rem token, a min() against 100%, an element and the top bar.
    expect(shrinkingButtons('.btn-sm { min-height: var(--tap-gap); }')).toEqual(['.btn-sm']);
    expect(shrinkingButtons('.btn-sm { min-height: var(--text-xl); }')).toEqual(['.btn-sm']);
    expect(shrinkingButtons('.btn-sm { min-height: min(100%, 20px); }')).toEqual(['.btn-sm']);
    expect(shrinkingButtons('.foo button { height: 32px; }')).toEqual(['.foo button']);
    expect(shrinkingButtons('.staff-bar-top .find-open, .ask-open:hover { min-width: 40px; }')).toEqual(['.staff-bar-top .find-open', '.ask-open:hover']);
    expect(shrinkingButtons('.btn-sm { min-width: min(100%, var(--button-min-wide)); } .foo button svg { width: 20px; }')).toEqual([]);
    expect([...buttonClassesIn('.chip, .x-pill, .y-clear, .z-close, .w-toggle { color: red; }')].sort()).toEqual(['chip', 'w-toggle', 'x-pill', 'y-clear', 'z-close']);
  });

  it('lists every button class a stylesheet names, with what it is', () => {
    const named = new Set(allStylesheets.flatMap((f) => [...buttonClassesIn(readFileSync(f, 'utf8'))]));
    const unlisted = [...named].filter((c) => !(c in BUTTON_CLASSES) && !(c in NOT_TARGETS));
    expect(unlisted, 'a new button class: check it is 48 by 48px on a phone and as wide as its label on a laptop, then list it').toEqual([]);
    expect([...Object.keys(BUTTON_CLASSES), ...Object.keys(NOT_TARGETS)].filter((c) => !named.has(c)), 'a listed class no stylesheet names any more').toEqual([]);
  });

  // Review of #833: the ActionBar and the sheet's buttons are seen in the order of the page, so the Tab order matches (WCAG 2.4.3).
  // What is seen matches the Tab order (WCAG 2.4.3): no stylesheet moves a box away from its place in the page, and no component
  // does so in an inline style. A grid placement is allowed in general layout, but never on the ActionBar, a sheet's foot or a
  // button in them.
  it.each(allStylesheets.map((f) => relative(SRC, f)))('%s never reorders what it draws', (file) => {
    const css = code(file);
    expect(css.match(/row-reverse|column-reverse|(?<![\w-])order\s*:|direction\s*:\s*rtl|grid-auto-flow\s*:[^;]*dense|(?<![\w-])float\s*:\s*(?:left|right|inline-start|inline-end)/)?.[0] ?? null).toBeNull();
    const bars = rulesOf(css).filter(({ selectors }) => selectors.some((s) => /\.(?:action-bar|sheet-actions)(?![\w-])/.test(s)));
    expect(bars.filter(({ body }) => /grid-(?:column|row|area)\s*:/.test(body)).map(({ selectors }) => selectors.join(', '))).toEqual([]);
  });

  it.each(components.map((f) => relative(SRC, f)))('%s never reorders what it draws in an inline style', (file) => {
    expect(code(file).match(/flexDirection:\s*['"`](?:row|column)-reverse|(?<![\w-])order:\s*[-\d'"`]|direction:\s*['"`]rtl|float:\s*['"`]/)?.[0] ?? null).toBeNull();
  });

  // The Sheet contract (issue #825): of the buttons in a sheet's actions, one is the primary, named by its class, and it is the last.
  it('finds the buttons of a sheet in order, and a primary that is not last', () => {
    expect(sheetActionProblems('<Sheet open actions={<><button className="btn-primary">Save</button><button>Cancel</button></>}>')).toEqual(['the primary is not last: btn-primary, plain']);
    expect(sheetActionProblems('<Sheet open actions={<><button>A</button><button>B</button></>}>')).toEqual(['no primary among 2 buttons']);
    expect(sheetActionProblems('<Sheet open actions={<>{x && <BusyButton type="button">Remove</BusyButton>}<BusyButton className="rt-primary">Save</BusyButton></>}>')).toEqual([]);
    expect(sheetActionProblems('<Sheet open actions={<button className="rt-primary">Done</button>}>')).toEqual([]);
    // A class after an arrow function handler is still read (the fourth review of #833).
    expect(sheetActionProblems('<Sheet open actions={<><button onClick={() => go()} className="btn-primary">Go</button><button onClick={() => x > 1}>No</button></>}>')).toEqual(['the primary is not last: btn-primary, plain']);
    expect(sheetActionProblems('<Sheet open actions={<><button onClick={() => no()}>No</button><BusyButton onClick={() => go()} className="rt-primary">Go</BusyButton></>}>')).toEqual([]);
  });

  it.each(components.filter((f) => /<Sheet\b/.test(readFileSync(f, 'utf8'))).map((f) => relative(SRC, f)))('%s puts the primary button of every sheet last', (file) => {
    expect(sheetActionProblems(code(file))).toEqual([]);
  });

  it.each(allStylesheets.map((f) => relative(SRC, f)))('%s never makes a button smaller than the target', (file) => {
    expect(shrinkingButtons(code(file))).toEqual([]);
  });

  it('keeps the sizes on the tokens: 48px, 8px apart, at most --button-max wide from 1024px', () => {
    const theme = code('app/theme.css');
    expect(theme).toMatch(/--tap:\s*48px/);
    expect(theme).toMatch(/--tap-gap:\s*var\(--space-2\)/);
    expect(theme).toMatch(/--button-max:\s*[\d.]+rem/);
    const base = rulesOf(code('app/ui/buttons.css')).find(({ selectors }) => selectors.includes('button') && selectors.includes('.btn'));
    expect(base?.body).toMatch(/min-width:\s*var\(--tap\)/);
    expect(base?.body).toMatch(/min-height:\s*var\(--tap\)/);
    for (const file of ['app/ui/buttons.css', 'app/ui/forms.css', 'app/ui/retail.css']) {
      expect(code(file), file).toMatch(/@media \(min-width: 1024px\)[\s\S]*?max-width:\s*min\(100%,\s*var\(--button-max\)\)/);
    }
  });
});
