import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { illustrations } from './illustrations';
import { BrandLoader, EmptyState, SkeletonList, StatusPanel } from './states';

// The illustrations and state components (#106): decorative drawings painted only by token classes,
// and states that say their meaning in words to every reader.

describe('illustrations', () => {
  for (const [name, art] of Object.entries(illustrations)) {
    it(`${name} is decorative, carries no colour of its own and stays small`, () => {
      const svg = renderToStaticMarkup(art);
      expect(svg).toContain('aria-hidden="true"');
      expect(svg).toContain('focusable="false"');
      expect(svg).not.toMatch(/\b(fill|stroke|stop-color|color)="(?!none)/);
      expect(svg).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/i);
      expect(svg).not.toMatch(/<(image|use|style|script)\b|href=/);
      expect(svg.length).toBeLessThan(2000);
    });
  }
});

describe('state components', () => {
  // Issue #526: one plain line, no drawing and no dashed box.
  it('an empty state is one plain line of its title and help, with no drawing', () => {
    const html = renderToStaticMarkup(<EmptyState title="No sales yet.">Sales appear here.</EmptyState>);
    expect(html).toBe('<div class="empty-line"><p>No sales yet. Sales appear here.</p></div>');
  });

  it('an error panel is an alert and a success panel a status', () => {
    expect(renderToStaticMarkup(<StatusPanel tone="error" title="Failed." />)).toContain('role="alert"');
    expect(renderToStaticMarkup(<StatusPanel tone="success" title="Done." />)).toContain('role="status"');
  });

  it('loaders say they are loading in words', () => {
    expect(renderToStaticMarkup(<BrandLoader label="Loading stock" />)).toMatch(/role="status".*Loading stock/);
    const skeleton = renderToStaticMarkup(<SkeletonList rows={4} label="Loading members" />);
    expect(skeleton).toContain('aria-busy="true"');
    expect(skeleton).toContain('<span class="visually-hidden">Loading members</span>');
    expect(skeleton.match(/class="skeleton-card"/g)).toHaveLength(4);
  });
});
