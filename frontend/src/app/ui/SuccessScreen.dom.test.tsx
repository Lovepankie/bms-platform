// @vitest-environment jsdom
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { ReactElement } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { router as appRouter } from '../router';
import { SuccessScreen } from './SuccessScreen';

// The one "it worked" screen (#520): a drawn check, the title focused, the amount large, the next steps primary first.

afterEach(cleanup);

function show(ui: ReactElement) {
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff'] }) });
  render(<RouterContextProvider router={router}>{ui}</RouterContextProvider>);
}

describe('SuccessScreen', () => {
  it('names the step, focuses it, and shows the amount large', () => {
    const again = vi.fn();
    show(
      <SuccessScreen
        title="Sale recorded"
        amount="UGX 12,500"
        detail="Paid in cash."
        actions={[{ label: 'New sale', onClick: again }, { label: 'View receipt', to: '/staff/retail/sales' }]}
      />,
    );
    const heading = screen.getByRole('heading', { name: 'Sale recorded', level: 1 });
    expect(document.activeElement).toBe(heading);
    expect(screen.getByRole('region', { name: 'Sale recorded' }).className).toBe('success-screen');
    expect(screen.getByText('UGX 12,500').className).toBe('success-amount');
    expect(document.querySelector('.success-mark')?.getAttribute('aria-hidden')).toBe('true');
  });

  it('takes its own id and an h2 under a page heading, so two can sit on one page', () => {
    show(
      <>
        <SuccessScreen title="First" level={2} focus={false} actions={[{ label: 'One', onClick: () => undefined }]} />
        <SuccessScreen title="Second" level={2} focus={false} actions={[{ label: 'Two', onClick: () => undefined }]} />
      </>,
    );
    expect(screen.getByRole('region', { name: 'First' })).toBeTruthy();
    expect(screen.getByRole('region', { name: 'Second' })).toBeTruthy();
    expect(screen.getByRole('heading', { name: 'Second', level: 2 })).toBeTruthy();
    expect(document.activeElement).toBe(document.body);
  });

  it('lists the next steps as plain buttons in order, only the first primary', () => {
    const again = vi.fn();
    show(<SuccessScreen title="Saved" actions={[{ label: 'New sale', onClick: again }, { label: 'View receipt', to: '/staff/retail/sales' }]} />);
    const steps = [...document.querySelectorAll('.success-actions > *')];
    expect(steps.map((s) => s.textContent)).toEqual(['New sale', 'View receipt']);
    expect(steps[0]?.classList.contains('btn-primary')).toBe(true);
    expect(steps[1]?.classList.contains('btn-primary')).toBe(false);
    expect(screen.getByRole('link', { name: 'View receipt' }).getAttribute('href')).toBe('/staff/retail/sales');
    fireEvent.click(screen.getByRole('button', { name: 'New sale' }));
    expect(again).toHaveBeenCalledOnce();
  });

  it('draws the check in 400 ms by transform only', () => {
    const css = readFileSync(join(process.cwd(), 'src/app/ui/kit.css'), 'utf8');
    const rule = (selector: string) => css.match(new RegExp(`\\n${selector.replace(/[.]/g, '\\$&')} \\{([^}]*)\\}`))?.[1] ?? '';
    expect(rule('.success-tick')).toMatch(/animation: kit-wipe 400ms var\(--ease-out\)/);
    expect(rule('.success-tick-inner')).toMatch(/animation: kit-unwipe 400ms var\(--ease-out\)/);
  });
});
