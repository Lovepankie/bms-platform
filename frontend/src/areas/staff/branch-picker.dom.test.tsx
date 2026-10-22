// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import type { Me } from '../../api/client';
import { BranchPicker } from './branch-picker';

// The branch picker in the staff bar (issue #461, ADR-068): the shop in use shows its own logo beside the picker, and a shop
// without one, or All branches, shows none (the business logo stays in the brand bar). Every name below is invented.

const LOGO = '/api/v1/branches/b-two/logo?v=doc-1';

const me = {
  user_id: 'u-self',
  all_branches: true,
  branches: [
    { id: 'b-one', code: 'HQ', name: 'Test Shop One', is_head_office: true, stocks_goods: true, sells_goods: true, logo_url: null },
    { id: 'b-two', code: 'KLA', name: 'Test Shop Two', is_head_office: false, stocks_goods: true, sells_goods: true, logo_url: LOGO },
  ],
} as unknown as Me;

function picker(branch: string | null) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <QueryClientProvider client={client}>
      <BranchPicker me={me} branch={branch} onChoose={() => undefined} />
    </QueryClientProvider>,
  );
}

afterEach(() => cleanup());

describe('the shop logo beside the picker', () => {
  it('shows the logo of the shop in use', async () => {
    picker('b-two');
    const image = (await screen.findByAltText('Logo of Test Shop Two (KLA)')) as HTMLImageElement;
    expect(image.getAttribute('src')).toBe(LOGO);
    expect(image.className).toBe('shop-logo');
  });

  it('shows none for a shop without its own logo, or for All branches', async () => {
    picker('b-one');
    await screen.findByRole('combobox');
    expect(screen.queryByRole('img')).toBeNull();
    cleanup();
    picker('__all__');
    await screen.findByRole('combobox');
    expect(screen.queryByRole('img')).toBeNull();
  });
});
