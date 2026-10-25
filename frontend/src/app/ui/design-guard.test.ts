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
  'app/ui/buttons.css': [
    { property: 'background', why: 'a button fades to its hover and pressed fill in --motion-fast rather than flashing' },
    { property: 'border-color', why: 'the edge of a button fades with its fill' },
  ],
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
    expect(fields.length).toBe(5);
    expect(form).toContain('<ItemCodeLine id={`${id}-code`}');
    expect(fields.filter((props) => /\n\s*required\n/.test(props)).map((props) => props.match(/id=\{`\$\{id\}-(\w+)`\}/)?.[1]))
      .toEqual(['description', 'unit', 'cost', 'sell', 'qty']);
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
    for (const screen of ['banking', 'expenses', 'savings', 'withdrawals', 'usage', 'transfer', 'catalogue-import', 'labels', 'sale']) {
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
    expect(code('areas/staff/retail/screen-link.tsx')).toMatch(/action \? 'btn btn-primary' : 'link-row'/);
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
  // neutral Hint line, the long explanation behind the Hint's info button, and links nowhere until a screen sets the limit.
  it('draws the Retail settings screens without an amber note, and the returns note as a Hint', () => {
    for (const file of ['settings', 'purchasing-settings', 'credit-recognition', 'slow-movers']) {
      // A badge that marks an unsaved change ("Will be switched on") is real status and may stay amber.
      // A class as a string or a template literal, or a tone prop, in amber or a warning tone.
      const amber = /className=(?:"[^"]*|\{`[^`]*)(?:(?:alert|text|note|hint)-warning|amber)|tone=(?:"|\{['"`])(?:warning|amber)/;
      expect(code(`areas/staff/retail/${file}.tsx`).match(amber)?.[0] ?? null, file).toBeNull();
    }
    const note = code('areas/staff/retail/settings.tsx').match(/export function ReturnsThresholdWarning[\s\S]*?\n\}/)?.[0] ?? '';
    expect(note).toMatch(/<Hint>\s*Returns need no second administrator yet\. /);
    expect(note).not.toMatch(/<Link\b|<a\b/);
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
