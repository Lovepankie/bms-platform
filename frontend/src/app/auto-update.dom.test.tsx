// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// #576, ADR-088: the toast before the app reloads by itself, the full screen when the API answers 426 app_too_old, and the
// unsaved-work registry that a money form's draft fills. Builds are invented.

function setOnline(value: boolean) {
  Object.defineProperty(window.navigator, 'onLine', { configurable: true, value });
}

beforeEach(() => {
  vi.resetModules();
  vi.stubEnv('VITE_BMS_BUILD', 'sha-old0001');
  vi.stubEnv('VITE_BMS_BUILT', '20261025120000');
  sessionStorage.clear();
  setOnline(true);
});

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('the app is too old for the server (426 app_too_old)', () => {
  it('sends its build, covers the app with the update screen and loads the new app, keeping the worker', async () => {
    const unregister = vi.fn();
    const update = vi.fn(async () => undefined);
    Object.defineProperty(window.navigator, 'serviceWorker', {
      configurable: true,
      value: { getRegistrations: async () => [{ update, unregister, installing: null, waiting: null }], addEventListener: vi.fn() },
    });
    const sent: Request[] = [];
    // openapi-fetch builds a Request from the path; Node's Request needs a whole address.
    const Base = globalThis.Request;
    vi.stubGlobal('Request', class extends Base {
      constructor(input: RequestInfo | URL, init?: RequestInit) {
        super(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init);
      }
    });
    vi.stubGlobal('fetch', async (request: Request) => {
      sent.push(request);
      return new Response(JSON.stringify({ code: 'app_too_old', detail: 'A new version of the app is ready.' }), {
        status: 426,
        headers: { 'content-type': 'application/problem+json', 'Bms-Build': 'sha-new0002' },
      });
    });
    const { api } = await import('../api/client');
    const { AutoUpdateLayer } = await import('./auto-update');
    render(<AutoUpdateLayer />);
    await act(async () => {
      await api.GET('/api/v1/me');
    });
    expect(sent[0]?.headers.get('Bms-Client-Build')).toBe('sha-old0001; built=20261025120000');
    expect(screen.getByRole('alertdialog').textContent).toContain('A new version of the app is ready. Updating…');
    await act(async () => {
      await new Promise((r) => setTimeout(r, 0));
    });
    expect(update).toHaveBeenCalled();
    expect(unregister).not.toHaveBeenCalled();
    Reflect.deleteProperty(window.navigator, 'serviceWorker');
  });

  it('offline it says so and reloads nothing, then updates once back online', async () => {
    setOnline(false);
    const { AutoUpdateLayer, forceUpdate } = await import('./auto-update');
    const load = vi.fn(async () => 'reloaded' as const);
    render(<AutoUpdateLayer />);
    act(() => forceUpdate(load, () => true));
    expect(screen.getByRole('alertdialog').textContent).toContain('You are offline. The app will update when you are back online.');
    expect(load).not.toHaveBeenCalled();
    setOnline(true);
    act(() => forceUpdate(load, () => true));
    expect(load).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('alertdialog').textContent).toContain('Updating…');
  });

  it('after a reload within the minute it offers the button instead of reloading again', async () => {
    const { AutoUpdateLayer, forceUpdate } = await import('./auto-update');
    const load = vi.fn(async () => 'reloaded' as const);
    render(<AutoUpdateLayer />);
    act(() => forceUpdate(load, () => false));
    expect(load).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: 'Load the page again' })).toBeTruthy();
  });
});

describe('the app reloads by itself', () => {
  it('shows the dark toast "Updating the app…" first', async () => {
    vi.useFakeTimers();
    const { AutoUpdateLayer, updater } = await import('./auto-update');
    render(<AutoUpdateLayer />);
    act(() => updater.workerChanged());
    const toast = screen.getByRole('status');
    expect(toast.textContent).toBe('Updating the app…');
    expect(toast.className).toContain('toast');
  });
});

describe('a money form holds unsaved work', () => {
  it('while its draft differs from a blank one, until it is finished or closed', async () => {
    const { usePersistedDraft } = await import('../areas/staff/retail/idempotency');
    const { hasUnsavedWork } = await import('./unsaved-work');
    let form: ReturnType<typeof usePersistedDraft<{ lines: string[] }>> | undefined;
    function Cart() {
      form = usePersistedDraft('test-cart', { lines: [] as string[] });
      return null;
    }
    const view = render(<Cart />);
    expect(hasUnsavedWork()).toBe(false);
    act(() => form?.setDraft({ lines: ['Test Item 01'] }));
    expect(hasUnsavedWork()).toBe(true);
    act(() => form?.finish());
    expect(hasUnsavedWork()).toBe(false);
    view.unmount();
    sessionStorage.clear();
    const again = render(<Cart />);
    act(() => form?.setDraft({ lines: ['Test Item 02'] }));
    expect(hasUnsavedWork()).toBe(true);
    again.unmount();
    expect(hasUnsavedWork()).toBe(false);
  });
});

describe('a save on its way (review of #579)', () => {
  /** Node's Request needs a whole address; openapi-fetch builds one from the path. */
  function wholeAddresses() {
    const Base = globalThis.Request;
    vi.stubGlobal('Request', class extends Base {
      constructor(input: RequestInfo | URL, init?: RequestInit) {
        super(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init);
      }
    });
  }

  it('is counted until the call ends, also when a middleware throws or the deploy-gap words end it', async () => {
    wholeAddresses();
    vi.stubGlobal('fetch', async () => new Response('bad gateway', { status: 502, headers: { 'content-type': 'text/html' } }));
    const { api, updatingRetry } = await import('../api/client');
    const { updater } = await import('./auto-update');
    updatingRetry.delayMs = 0;
    let during = -1;
    api.use({
      onRequest() {
        during = updater.writing;
        throw new Error('the session could not be refreshed');
      },
    });
    await expect(api.POST('/api/v1/retail/sales', { body: {} as never })).rejects.toThrow('the session could not be refreshed');
    expect(during).toBe(1);
    expect(updater.writing).toBe(0);
  });

  it('a save answered by the deploy gap ends counted at zero, with its retry', async () => {
    wholeAddresses();
    let calls = 0;
    vi.stubGlobal('fetch', async () => {
      calls++;
      throw new TypeError('Failed to fetch');
    });
    const { api, updatingRetry } = await import('../api/client');
    const { updater } = await import('./auto-update');
    updatingRetry.delayMs = 0;
    await expect(
      api.POST('/api/v1/retail/sales', { body: {} as never, params: { header: { 'Idempotency-Key': 'key-test-1' } } as never }),
    ).rejects.toBeTruthy();
    expect(calls).toBe(2);
    expect(updater.writing).toBe(0);
  });
});

describe('a save that never ends (second review of #579)', () => {
  it('stops holding the update back after two minutes', async () => {
    vi.useFakeTimers();
    const Base = globalThis.Request;
    vi.stubGlobal('Request', class extends Base {
      constructor(input: RequestInfo | URL, init?: RequestInit) {
        super(typeof input === 'string' && input.startsWith('/') ? `http://localhost${input}` : input, init);
      }
    });
    vi.stubGlobal('fetch', () => new Promise<Response>(() => undefined));
    const { api, WRITE_HOLD_MS } = await import('../api/client');
    const { AutoUpdateLayer, updater } = await import('./auto-update');
    render(<AutoUpdateLayer />);
    void api.POST('/api/v1/retail/sales', { body: {} as never });
    await vi.advanceTimersByTimeAsync(0);
    expect(updater.writing).toBe(1);
    // The new worker took over while the save hangs: the update waits for it.
    act(() => updater.workerChanged());
    expect(screen.queryByText('Updating the app…')).toBeNull();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(WRITE_HOLD_MS - 1);
    });
    expect(updater.writing).toBe(1);
    expect(screen.queryByText('Updating the app…')).toBeNull();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1);
    });
    expect(updater.writing).toBe(0);
    // Released, the held update goes ahead by itself.
    expect(screen.getByText('Updating the app…')).toBeTruthy();
  });
});

describe('the update screen never hangs', () => {
  it('a load that fails offers the button', async () => {
    const { AutoUpdateLayer, forceUpdate } = await import('./auto-update');
    render(<AutoUpdateLayer />);
    await act(async () => {
      forceUpdate(() => Promise.reject(new Error('no worker')), () => true);
      await Promise.resolve();
    });
    expect(screen.getByRole('button', { name: 'Load the page again' })).toBeTruthy();
  });
});

describe('a money form after finish and discard', () => {
  it('registers its next draft again', async () => {
    const { usePersistedDraft } = await import('../areas/staff/retail/idempotency');
    const { hasUnsavedWork } = await import('./unsaved-work');
    let form: ReturnType<typeof usePersistedDraft<{ lines: string[] }>> | undefined;
    function Cart() {
      form = usePersistedDraft('test-cart-2', { lines: [] as string[] });
      return null;
    }
    const view = render(<Cart />);
    act(() => form?.setDraft({ lines: ['Test Item 01'] }));
    act(() => form?.finish());
    act(() => form?.discard());
    act(() => form?.setDraft({ lines: ['Test Item 02'] }));
    expect(hasUnsavedWork()).toBe(true);
    view.unmount();
  });
});

describe('a money form kept on screen after finish (second review of #579)', () => {
  it('registers and saves the next cart, with its own key, without a discard', async () => {
    const { usePersistedDraft } = await import('../areas/staff/retail/idempotency');
    const { hasUnsavedWork } = await import('./unsaved-work');
    let form: ReturnType<typeof usePersistedDraft<{ lines: string[] }>> | undefined;
    function Cart() {
      form = usePersistedDraft('test-cart-3', { lines: [] as string[] });
      return null;
    }
    const view = render(<Cart />);
    act(() => form?.setDraft({ lines: ['Test Item 01'] }));
    const firstKey = form?.key;
    act(() => form?.finish());
    expect(hasUnsavedWork()).toBe(false);
    expect(sessionStorage.getItem('retail-draft:test-cart-3')).toBeNull();
    act(() => form?.setDraft({ lines: ['Test Item 02'] }));
    expect(hasUnsavedWork()).toBe(true);
    const kept = JSON.parse(sessionStorage.getItem('retail-draft:test-cart-3') ?? '{}') as { key?: string; draft?: { lines: string[] } };
    expect(kept.draft?.lines).toEqual(['Test Item 02']);
    expect(kept.key).toBe(form?.key);
    expect(kept.key).not.toBe(firstKey);
    view.unmount();
  });

  it('a change after finish that leaves the record as it was starts nothing new', async () => {
    const { usePersistedDraft } = await import('../areas/staff/retail/idempotency');
    const { hasUnsavedWork } = await import('./unsaved-work');
    let form: ReturnType<typeof usePersistedDraft<{ lines: string[] }>> | undefined;
    function Cart() {
      form = usePersistedDraft('test-cart-4', { lines: [] as string[] });
      return null;
    }
    const view = render(<Cart />);
    act(() => form?.setDraft({ lines: ['Test Item 01'] }));
    const key = form?.key;
    act(() => form?.finish());
    act(() => form?.setDraft((d) => ({ ...d })));
    expect(form?.key).toBe(key);
    expect(hasUnsavedWork()).toBe(false);
    expect(sessionStorage.getItem('retail-draft:test-cart-4')).toBeNull();
    view.unmount();
    expect(hasUnsavedWork()).toBe(false);
  });
});
