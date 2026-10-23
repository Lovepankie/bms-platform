// @vitest-environment jsdom
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it } from 'vitest';
import { router as appRouter } from '../router';
import { icons } from '../../components/icons';
import { Tile, TileGrid } from './Tile';

// The one navigation tile of the hub pages (#508): an icon above the label, a hint under it, named by the label and described by
// the hint, with a pressed state and a plain focus ring in the stylesheet.

afterEach(cleanup);

function show(ui: ReactElement) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(<RouterContextProvider router={router}>{ui}</RouterContextProvider>);
}

describe('Tile', () => {
  it('is a link named by its label, described by its hint, with the icon above the label', () => {
    show(
      <TileGrid label="Retail pages" tour="retail-tiles">
        <Tile to="/staff/retail/sale" icon={icons.sale} label="Record a sale" hint="Sell to a walk-in buyer" tour="retail-sale" />
      </TileGrid>,
    );
    const list = screen.getByRole('list', { name: 'Retail pages' });
    expect(list.getAttribute('data-tour')).toBe('retail-tiles');
    const link = screen.getByRole('link', { name: 'Record a sale' });
    expect(link.getAttribute('href')).toBe('/staff/retail/sale');
    expect(link.className).toBe('tile');
    expect(link.getAttribute('data-tour')).toBe('retail-sale');
    const hint = document.getElementById(link.getAttribute('aria-describedby') ?? '');
    expect(hint?.textContent).toBe('Sell to a walk-in buyer');
    const parts = [...link.children].map((c) => c.className);
    expect(parts).toEqual(['icon-chip', 'tile-label', 'tile-hint']);
    expect(link.querySelector('.icon-chip svg')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('leaves out the hint and its description when there is none', () => {
    show(
      <TileGrid label="Pages">
        <Tile to="/staff/users" icon={icons.people} label="Users" />
      </TileGrid>,
    );
    const link = screen.getByRole('link', { name: 'Users' });
    expect(link.hasAttribute('aria-describedby')).toBe(false);
    expect(link.querySelector('.tile-hint')).toBeNull();
  });
});

describe('the tile stylesheet', () => {
  const css = readFileSync(join(process.cwd(), 'src/app/ui/navigation.css'), 'utf8');
  const rule = (selector: string) => css.match(new RegExp(`\\n${selector.replace(/[.:]/g, '\\$&')} \\{([^}]*)\\}`))?.[1] ?? '';

  it('is at least a 48px target that shrinks to 0.98 on a press in 120 ms, animating the transform only', () => {
    expect(rule('.tile')).toMatch(/min-height: 132px/);
    expect(rule('.tile')).toMatch(/transition: transform var\(--motion-fast\) var\(--ease-out\);/);
    expect(rule('.tile:active')).toMatch(/transform: scale\(var\(--press-scale\)\)/);
  });

  it('shows a 3px focus ring and a hover edge without growing the shadow', () => {
    expect(rule('.tile:focus-visible')).toMatch(/0 0 0 3px var\(--color-focus\)/);
    expect(rule('.tile:hover')).not.toMatch(/shadow/);
    expect(rule('.tile:focus-visible')).not.toMatch(/--shadow-lg|blur/);
  });
});
