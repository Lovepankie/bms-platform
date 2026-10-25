// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { retail } from '../../../api/retail';
import { createMockRetail, mockMe } from '../../../api/retail-mock';
import { pickItem } from '../../../test-utils/item-picker';
import { StaffContext } from '../context';
import { RestockForm } from './restock';
import { SaleForm } from './sale';
import { StocktakePage } from './stocktake';
import { addedMessage } from './toast';
import { TransferForm } from './transfer';

// Issue #498 (Hillary, 23 Oct 2026): every add on Sell, Buy stock, Move stock and Count stock says so in the dark toast with the
// quantity the line now has, picked from the search or scanned alike, and the line added or topped up flashes once. A screen
// reader hears the toast's words once: the Scan box no longer says Added beside it. The retail adapter is the fabricated mock.

vi.setConfig({ testTimeout: 20000 });

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to }: { children: unknown; to: string }) => <a href={to}>{children as never}</a> };
});

const BRANCH = '00000000-0000-4000-8000-0000000000a1';
const LAMP = '00000000-0000-4000-8000-0000000b0003';
const NAME = 'LED bulb 9W screw';
const EAN = '4006381333931';

beforeEach(async () => {
  Object.assign(retail, createMockRetail());
  await retail.addBarcode(LAMP, { code: EAN });
});
afterEach(() => {
  cleanup();
  sessionStorage.clear();
  localStorage.clear();
  vi.unstubAllGlobals();
});

function open(node: ReactElement) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <StaffContext.Provider value={{ me: mockMe('admin'), branch: BRANCH }}>{node}</StaffContext.Provider>
    </QueryClientProvider>,
  );
}

/** A code typed into the Scan box and Enter, the path a scanner and the camera both take. */
async function scan(code: string) {
  const box = await screen.findByLabelText('Scan here');
  fireEvent.change(box, { target: { value: code } });
  fireEvent.keyDown(box, { key: 'Enter' });
}

const toast = () => document.querySelector('.toast');
const flashes = () => [...document.querySelectorAll('.line-card > .line-added')];

/** The toast says `text` as a polite status, and exactly one line flashes, the one holding `field`. */
async function saysAndFlashes(text: string, field: () => HTMLElement | null) {
  await waitFor(() => expect(toast()?.textContent).toBe(text));
  expect(toast()?.className).toBe('toast toast-success');
  expect(toast()?.getAttribute('role')).toBe('status');
  expect(flashes()).toHaveLength(1);
  expect(flashes()[0]?.getAttribute('aria-hidden')).toBe('true');
  expect(flashes()[0]?.parentElement?.contains(field())).toBe(true);
}

/** The Scan box's own message, which must not say Added beside the toast. */
const scanSays = () => document.querySelector('.scan-bar [aria-live="polite"]')?.textContent ?? '';

describe('#498 the words', () => {
  it('says the quantity the line now has, or that one is still to type', () => {
    expect(addedMessage('Test soap', '3')).toBe('Added Test soap, now 3');
    expect(addedMessage('Test soap', '')).toBe('Added Test soap. Type how many.');
    expect(addedMessage(undefined, '1')).toBe('Added the item, now 1');
  });
});

describe('#498 Sell', () => {
  it('says Added with the quantity and flashes the line, picked or scanned', async () => {
    open(<SaleForm branchId={BRANCH} />);
    const qty = () => document.getElementById('qty-0');
    await pickItem(NAME);
    await saysAndFlashes(`Added ${NAME}, now 1`, qty);
    const first = flashes()[0];
    await scan(EAN);
    await saysAndFlashes(`Added ${NAME}, now 2`, qty);
    // A new flash for the top-up, so the eye is drawn again.
    expect(flashes()[0]).not.toBe(first);
    expect((qty() as HTMLInputElement).value).toBe('2');
    expect(scanSays()).not.toContain('Added');
  });
});

describe('#498 Buy stock', () => {
  it('says Added and flashes the line on a pick, then the quantity at this branch on a scan', async () => {
    open(<RestockForm />);
    const qty = () => document.getElementById(`q-0-${BRANCH}`);
    await pickItem(NAME);
    await saysAndFlashes(`Added ${NAME}. Type how many.`, qty);
    await scan(EAN);
    await saysAndFlashes(`Added ${NAME}, now 1`, qty);
    await scan(EAN);
    await saysAndFlashes(`Added ${NAME}, now 2`, qty);
    expect((qty() as HTMLInputElement).value).toBe('2');
    expect(scanSays()).not.toContain('Added');
  });

  it('says an item already on the purchase is there, with no success check and no flash', async () => {
    open(<RestockForm />);
    await pickItem(NAME);
    await waitFor(() => expect(toast()?.className).toBe('toast toast-success'));
    await pickItem(NAME);
    await waitFor(() => expect(toast()?.textContent).toBe(`${NAME} is already on the purchase. Change its quantity below.`));
    expect(toast()?.className).toBe('toast toast-info');
    expect(document.querySelectorAll('.line-card')).toHaveLength(1);
  });
});

describe('#498 Move stock', () => {
  it('says Added with the quantity to move and flashes the line, picked or scanned', async () => {
    open(<TransferForm fromBranchId={BRANCH} />);
    const qty = () => document.getElementById('tq-0');
    await pickItem(NAME);
    await saysAndFlashes(`Added ${NAME}, now 1`, qty);
    await scan(EAN);
    await saysAndFlashes(`Added ${NAME}, now 2`, qty);
    expect(scanSays()).not.toContain('Added');
  });

  it('says an item already on the move is there, and adds no second line', async () => {
    open(<TransferForm fromBranchId={BRANCH} />);
    await pickItem(NAME);
    await waitFor(() => expect(toast()?.className).toBe('toast toast-success'));
    await pickItem(NAME);
    await waitFor(() => expect(toast()?.className).toBe('toast toast-info'));
    expect(toast()?.textContent).toBe(`${NAME} is already on the move. Change its quantity below.`);
    expect(document.querySelectorAll('[id^="tq-"]')).toHaveLength(1);
  });
});

describe('#498 Count stock', () => {
  it('says the count now on each scan and flashes the counted line, and says so on a pick', async () => {
    open(<StocktakePage />);
    const count = () => document.getElementById(`count-${LAMP}`);
    await screen.findByText('No items yet. Find an item above, scan it, or count it in the list below.');
    await scan(EAN);
    await saysAndFlashes(`Added ${NAME}, now 1`, count);
    await scan(EAN);
    await saysAndFlashes(`Added ${NAME}, now 2`, count);
    expect((count() as HTMLInputElement).value).toBe('2');
    expect(scanSays()).not.toContain('Added');
    await pickItem(NAME);
    await saysAndFlashes(`Added ${NAME}, now 2`, count);
  });

  it('asks for the count when an item not yet counted is picked', async () => {
    open(<StocktakePage />);
    await pickItem(NAME);
    await saysAndFlashes(`Added ${NAME}. Type how many.`, () => document.getElementById(`count-${LAMP}`));
  });
});

describe('#498 under reduced motion', () => {
  const css = (file: string) => readFileSync(join(__dirname, '../../../app/ui', file), 'utf8');

  it('still says it and marks the line, as a fade with no movement', async () => {
    vi.stubGlobal('matchMedia', (query: string) => ({ matches: query.includes('reduce'), media: query, addEventListener: () => undefined, removeEventListener: () => undefined }));
    open(<TransferForm fromBranchId={BRANCH} />);
    await scan(EAN);
    await saysAndFlashes(`Added ${NAME}, now 1`, () => document.getElementById('tq-0'));
    const feedback = css('feedback.css');
    // The flash only fades (opacity), on the fast ease-out, and under reduced motion it is a still tint that goes in one step.
    const frames = feedback.match(/@keyframes rt-line-added \{([\s\S]*?)\n\}/)?.[1] ?? '';
    expect([...frames.matchAll(/([a-z-]+):/g)].map((m) => m[1]).every((p) => p === 'opacity')).toBe(true);
    expect(feedback).toMatch(/\.line-added \{[^}]*animation: rt-line-added [^;]*var\(--ease-out\)/);
    expect(feedback).toMatch(/@media \(prefers-reduced-motion: reduce\) \{\s*\.line-added \{\s*animation: rt-line-added-still [^;]*steps\(1, end\)/);
    expect(readFileSync(join(__dirname, '../../../app/theme.css'), 'utf8')).toContain('--ease-out: cubic-bezier(0.2, 0, 0, 1);');
    expect(feedback).toMatch(/\.line-card \{\s*position: relative;\s*isolation: isolate;/);
    // Behind the words, so the new quantity reads at full contrast.
    expect(feedback).toMatch(/\.line-added \{[^}]*z-index: -1;/);
  });
});
