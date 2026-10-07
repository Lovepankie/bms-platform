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
  it('an empty state shows its drawing, title and help', () => {
    const html = renderToStaticMarkup(<EmptyState art="noSales" title="No sales yet.">Sales appear here.</EmptyState>);
    expect(html).toContain('class="empty-state empty-state-art"');
    expect(html).toContain('No sales yet.');
    expect(html).toContain('Sales appear here.');
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
