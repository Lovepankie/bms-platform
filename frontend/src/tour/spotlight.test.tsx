import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { HelpMenu } from './host';
import { TourBackdrop, TourCard, type CardProps } from './spotlight';

// Accessibility of the spotlight card and the Help entry (issue #19), checked on the markup: a
// labelled modal dialog, a live region, real buttons with names, no style attributes (the CSP
// forbids them), and the controls a first-run tour needs.

const noop = () => undefined;
const props = (over: Partial<CardProps> = {}): CardProps => ({
  tourTitle: 'Welcome tour',
  step: { id: 'branch', target: 'branch-picker', title: 'Your branch', body: 'Pick the branch you are working in.' },
  index: 1,
  total: 4,
  side: 'bottom',
  canRemember: true,
  remember: false,
  onRemember: noop,
  onNext: noop,
  onBack: noop,
  onClose: noop,
  ...over,
});

const card = (over: Partial<CardProps> = {}) => renderToStaticMarkup(<TourCard {...props(over)} />);

describe('the spotlight card', () => {
  it('is a modal dialog named by its title and described by its text', () => {
    const html = card();
    expect(html).toContain('role="dialog"');
    expect(html).toContain('aria-modal="true"');
    expect(html).toContain('aria-labelledby="tour-title"');
    expect(html).toContain('aria-describedby="tour-body"');
    expect(html).toContain('<h2 id="tour-title" tabindex="-1">Your branch</h2>');
    expect(html).toContain('<p id="tour-body">Pick the branch you are working in.</p>');
  });

  it('announces each step in a polite live region', () => {
    expect(card()).toContain('aria-live="polite">Step 2 of 4. Your branch. Pick the branch you are working in.</p>');
  });

  it('shows where the user is in words and in dots, the dots hidden from screen readers', () => {
    const html = card();
    expect(html).toContain('Step 2 of 4');
    expect(html).toContain('<ol class="tour-dots" aria-hidden="true"><li class="is-done"></li><li class="is-current"></li><li></li><li></li></ol>');
  });

  it('offers Skip on the first step, Back after it, and Finish on the last', () => {
    expect(card({ index: 0 })).toContain('Skip the tour');
    expect(card({ index: 0 })).not.toContain('>Back<');
    expect(card({ index: 1 })).toContain('>Back<');
    expect(card({ index: 3 })).toContain('>Finish<');
    expect(card({ index: 1 })).toContain('>Next<');
  });

  it('has a named close button and real buttons only', () => {
    const html = card();
    expect(html).toContain('aria-label="Close the tour"');
    const buttons = html.match(/<button[^>]*>/g) ?? [];
    expect(buttons.length).toBeGreaterThanOrEqual(3);
    for (const b of buttons) expect(b).toContain('type="button"');
  });

  it('offers Do not show this again only on a first-run tour, as a labelled checkbox', () => {
    expect(card()).toMatch(/<label class="tour-remember"><input type="checkbox"\/>Do not show this again<\/label>/);
    expect(card({ canRemember: false })).not.toContain('Do not show this again');
  });

  it('docks to the side away from the target', () => {
    expect(card({ side: 'top' })).toContain('class="tour-card tour-card-top"');
    expect(card({ side: 'bottom' })).toContain('class="tour-card tour-card-bottom"');
  });

  it('dims with classes only: no style attribute anywhere', () => {
    const lit = renderToStaticMarkup(<TourBackdrop lit>{<TourCard {...props()} />}</TourBackdrop>);
    const centred = renderToStaticMarkup(<TourBackdrop lit={false}>{<TourCard {...props()} />}</TourBackdrop>);
    expect(lit).toContain('class="tour-hole"');
    expect(lit).toContain('class="tour-backdrop"');
    expect(centred).toContain('class="tour-backdrop is-dim"');
    expect(centred).not.toContain('tour-hole');
    expect(lit + centred).not.toContain('style=');
  });
});

describe('Help', () => {
  it('is a labelled button that opens a dialog, and is a tour anchor itself', () => {
    const html = renderToStaticMarkup(<HelpMenu />);
    expect(html).toContain('data-tour="help-menu"');
    expect(html).toContain('aria-haspopup="dialog"');
    expect(html).toContain('>Help</button>');
    expect(html).toContain('<dialog class="sheet help-sheet" aria-labelledby="help-title">');
    expect(html).not.toContain('style=');
  });
});
