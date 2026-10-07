import { MutationObserver, QueryClient, QueryObserver } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';
import { RetailError } from '../../../api/retail';
import { createMockRetail } from '../../../api/retail-mock';
import { STALE_TEXT, changeFailureText, requireVersion } from './ui';

// The change mutation of the four lists (#146): a stale version reloads the list and the retry
// carries the new version. The screens run this same mutation shape with these helpers.

async function screen(kind: 'category' | 'buyer') {
  const api = createMockRetail();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  const list = kind === 'category' ? () => api.listCategories() : () => api.listCustomers();
  const rows = new QueryObserver(queryClient, { queryKey: ['retail', 'catalogue', kind], queryFn: list });
  rows.subscribe(() => undefined);
  await rows.refetch();
  const row = () => (rows.getCurrentResult().data ?? [])[0] as { id?: string; version?: number };
  const change = new MutationObserver(queryClient, {
    mutationFn: (v: { id: string; version: number | undefined }) => {
      const version = requireVersion(v.version);
      return kind === 'category' ? api.updateCategory(v.id, version, { active: false }) : api.updateCustomer(v.id, version, { contact: '+256700000007' });
    },
  });
  const tap = async (version: number | undefined) => {
    try {
      await change.mutate({ id: row().id ?? '', version });
      return null;
    } catch (e) {
      return changeFailureText(e, queryClient);
    }
  };
  return { api, row, tap, rows };
}

describe.each(['category', 'buyer'] as const)('a %s change after someone else changed it', (kind) => {
  it('says so, reloads the list, and the retry with the new version succeeds', async () => {
    const { api, row, tap, rows } = await screen(kind);
    const shown = row();
    expect(shown.version).toBe(1);
    // Someone else saves first, so the version this screen holds is stale.
    if (kind === 'category') await api.updateCategory(shown.id ?? '', 1, { name: 'Test Renamed Elsewhere' });
    else await api.updateCustomer(shown.id ?? '', 1, { name: 'Test Renamed Elsewhere' });

    expect(await tap(shown.version)).toBe(STALE_TEXT);
    await rows.refetch();
    expect(row().version).toBe(2);
    expect(await tap(row().version)).toBeNull();
    expect(row().version).toBe(2);
  });
});

describe('changeFailureText and requireVersion', () => {
  it('invalidates the retail queries only for a version conflict', async () => {
    const queryClient = new QueryClient();
    let fetched = 0;
    const rows = new QueryObserver(queryClient, { queryKey: ['retail', 'x'], queryFn: () => ++fetched });
    rows.subscribe(() => undefined);
    await rows.refetch();
    expect(changeFailureText(new RetailError('A category with this name already exists.', 409, 'duplicate_category'), queryClient)).toBe(
      'A category with this name already exists.',
    );
    expect(fetched).toBe(1);
    expect(changeFailureText(new RetailError('x', 409, 'version_conflict'), queryClient)).toBe(STALE_TEXT);
    await new Promise((r) => setTimeout(r, 0));
    expect(fetched).toBe(2);
  });

  it('treats a missing version as an error and never defaults it', () => {
    expect(() => requireVersion(undefined)).toThrow(/no version/);
    expect(requireVersion(3)).toBe(3);
    expect(changeFailureText(new Error('This row has no version.'), new QueryClient())).toBe('This row has no version.');
  });
});
