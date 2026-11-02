// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { RINCOLTECH_LOGO, RINCOLTECH_LOGO_ON_DARK } from '../../app/branding';
import { LogoImg } from '../../app/shell';
import { router as appRouter } from '../../app/router';
import { resetThemeMemory } from '../../app/theme-mode';
import { StylePage } from './route';

// The dark theme's leftovers (#115): the Rincol Tech logo has a light-text version for dark surfaces, theme.css shows the one
// that suits the theme, and the style page can be looked at in both themes.

beforeEach(() => {
  vi.stubGlobal('matchMedia', ((query: string) => ({ media: query, matches: false, addEventListener: () => undefined, removeEventListener: () => undefined })) as unknown as typeof window.matchMedia);
  resetThemeMemory();
  document.documentElement.removeAttribute('data-theme');
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe('the Rincol Tech logo in both themes (#115)', () => {
  it('draws the black lettering and the light lettering, and a tenant logo once', () => {
    const { container } = render(
      <>
        <LogoImg src={RINCOLTECH_LOGO} alt="Rincol Tech" />
        <LogoImg src="/api/v1/branding/logo?v=1" alt="Test Trading Co" />
      </>,
    );
    const imgs = [...container.querySelectorAll('img')].map((img) => [img.getAttribute('src'), img.className]);
    expect(imgs).toEqual([
      [RINCOLTECH_LOGO, 'logo-on-light'],
      [RINCOLTECH_LOGO_ON_DARK, 'logo-on-dark'],
      ['/api/v1/branding/logo?v=1', ''],
    ]);
  });

  // Which version shows in each theme, and that the light one has no plate, is proved on the computed style in Chromium:
  // layout/logo.layout.test.ts.

  it('ships the light-text logo beside the black one', () => {
    const png = readFileSync(join(__dirname, '../../../public', RINCOLTECH_LOGO_ON_DARK));
    expect(png.subarray(1, 4).toString('latin1')).toBe('PNG');
  });
});

describe('the style page in both themes (#115)', () => {
  it('switches the whole page between light and dark, and shows the logo for each', () => {
    const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/style'] }) });
    const { container } = render(
      <QueryClientProvider client={new QueryClient()}>
        <RouterContextProvider router={router}>
          <StylePage />
        </RouterContextProvider>
      </QueryClientProvider>,
    );
    fireEvent.click(screen.getByRole('radio', { name: 'Dark' }));
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    fireEvent.click(screen.getByRole('radio', { name: 'Light' }));
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(container.querySelector('img.logo-on-light')).not.toBeNull();
    expect(container.querySelector('img.logo-on-dark')).not.toBeNull();
  });
});
