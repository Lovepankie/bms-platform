// @vitest-environment jsdom
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Me } from '../../../api/client';
import { mockMe } from '../../../api/retail-mock';
import { StaffContext } from '../context';

// Packs within reach (issue #630): while packs are off, the item form says so in one line. A settings manager turns them on
// through the capabilities route with its If-Match and the pack section shows at once; anyone else reads who can turn them
// on. Fabricated items.

vi.setConfig({ testTimeout: 20000 });

const capabilities = vi.fn();
const changeCapabilities = vi.fn();

vi.mock('@tanstack/react-router', async (original) => {
  const real = await original<typeof import('@tanstack/react-router')>();
  return { ...real, Link: ({ children, to, params, className }: { children: unknown; to: string; params?: { articleId?: string }; className?: string }) => <a href={to.replace('$articleId', params?.articleId ?? '')} className={className}>{children as never}</a> };
});

vi.mock('../../../api/retail', async (original) => {
  const real = await original<typeof import('../../../api/retail')>();
  return {
    ...real,
    retail: {
      ...real.retail,
      listCategories: () => Promise.resolve([{ id: 'c1', name: 'Test drinks', active: true, product_count: 1, version: 1 }]),
      listUnits: () => Promise.resolve([{ id: 'u1', name: 'piece', active: true, product_count: 1, version: 1 }, { id: 'u2', name: 'carton', active: true, product_count: 0, version: 1 }]),
      listPacks: () => Promise.resolve([]),
      listBarcodes: () => Promise.resolve([]),
      capabilities: () => capabilities(),
      changeCapabilities: (...a: unknown[]) => changeCapabilities(...a),
    },
  };
});

const { ProductForm } = await import('./catalogue-products');
const { ASK_OWNER_PACKS, PACKS_ON_ADDING, PACKS_QUESTION, withPacksOn } = await import('./catalogue-packs');

afterEach(() => {
  cleanup();
  capabilities.mockReset();
  changeCapabilities.mockReset();
});

const packsOff = (me: Me): Me => ({ ...me, module_capabilities: { ...me.module_capabilities, retail: (me.module_capabilities?.retail ?? []).filter((c) => c !== 'units') } });
const owner = packsOff(mockMe('admin'));
const manager = { ...owner, permissions: (owner.permissions ?? []).filter((p) => p !== 'core.settings.manage') };
const water = { id: 'p1', code: 'TD-0001', description: 'Test water 500ml', category_id: 'c1', category: 'Test drinks', unit: 'piece', unit_id: 'u1', sell_minor: 1_000, active: true, version: 1 };

/** The form under the staff context that reads /me from the query cache, as the staff layout does. */
function Staffed({ children }: { children: React.ReactNode }) {
  const me = useQuery({ queryKey: ['me', 't'], queryFn: (): Me => { throw new Error('not read'); }, enabled: false });
  return me.data ? <StaffContext.Provider value={{ me: me.data, branch: null }}>{children}</StaffContext.Provider> : null;
}

function screenOf(me: Me, product?: typeof water) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  client.setQueryData(['me', 't'], me);
  return render(
    <QueryClientProvider client={client}>
      <Staffed><ProductForm product={product} onSaved={() => undefined} onCancel={() => undefined} /></Staffed>
    </QueryClientProvider>,
  );
}

describe('the turn-on line on the item form', () => {
  it('turns packs on with the settings version and shows the pack section at once on Edit', async () => {
    capabilities.mockResolvedValue({ capabilities: { units: false }, version: 7 });
    changeCapabilities.mockResolvedValue({ capabilities: { units: true }, version: 8 });
    screenOf(owner, water);

    expect(screen.getByText(PACKS_QUESTION)).toBeTruthy();
    expect(screen.queryByTestId('pack-editor')).toBeNull();
    expect(screen.getByRole('link', { name: /How do I sell or buy in cartons\?/ }).getAttribute('href')).toBe('/staff/help/sell-or-buy-in-cartons');
    fireEvent.click(screen.getByRole('button', { name: 'Turn on cartons' }));

    await screen.findByTestId('pack-editor');
    expect(changeCapabilities).toHaveBeenCalledWith({ units: true }, 7);
    expect(screen.queryByText(PACKS_QUESTION)).toBeNull();
  });

  it('says on Add an item that the packs are added once the item is', async () => {
    capabilities.mockResolvedValue({ capabilities: { units: false }, version: 3 });
    changeCapabilities.mockResolvedValue({ capabilities: { units: true }, version: 4 });
    screenOf(owner);

    fireEvent.click(screen.getByRole('button', { name: 'Turn on cartons' }));

    expect((await screen.findByTestId('packs-on')).textContent).toBe(PACKS_ON_ADDING);
    expect(changeCapabilities).toHaveBeenCalledWith({ units: true }, 3);
  });

  it('sends no change when packs were already turned on elsewhere', async () => {
    capabilities.mockResolvedValue({ capabilities: { units: true }, version: 9 });
    screenOf(owner, water);

    fireEvent.click(screen.getByRole('button', { name: 'Turn on cartons' }));

    await screen.findByTestId('pack-editor');
    expect(changeCapabilities).not.toHaveBeenCalled();
  });

  it('tells someone who may not change settings who can turn packs on, with no button', () => {
    screenOf(manager, water);

    expect(screen.getByText(PACKS_QUESTION)).toBeTruthy();
    expect(screen.getByTestId('packs-ask').textContent).toBe(ASK_OWNER_PACKS);
    expect(screen.queryByRole('button', { name: 'Turn on cartons' })).toBeNull();
  });

  it('is not there while packs are on', async () => {
    screenOf(withPacksOn(owner), water);

    await waitFor(() => expect(screen.getByTestId('pack-editor')).toBeTruthy());
    expect(screen.queryByTestId('packs-turn-on')).toBeNull();
  });
});
