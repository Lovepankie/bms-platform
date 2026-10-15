// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterContextProvider, createMemoryHistory, createRouter } from '@tanstack/react-router';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactElement } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { mockMe } from '../../../api/retail-mock';
import type { SmsImportResult, SmsOptOut, SmsSettings, SmsTemplate } from '../../../api/sms';
import { router as appRouter } from '../../../app/router';
import { StaffContext } from '../context';

// The SMS screens in a browser (extras slice B3, issue #218), with the API client replaced: switching on needs the notice read
// and ticked; a cap of 0 is sent as 0; a cap above the ceiling is refused on the screen; a sender name can only be chosen from
// the approved ones; a lost race says so and reloads; the wording preview shows the text as text; a pasted list is checked
// before it is added. Fabricated names and numbers.

const api = {
  settings: vi.fn(),
  changeSettings: vi.fn(),
  optOuts: vi.fn(),
  addOptOut: vi.fn(),
  importOptOuts: vi.fn(),
  removeOptOut: vi.fn(),
  templates: vi.fn(),
  saveTemplate: vi.fn(),
  revertTemplate: vi.fn(),
  previewTemplate: vi.fn(),
  testMessage: vi.fn(),
  memberOptOut: vi.fn(),
  setMemberOptOut: vi.fn(),
  liftMemberOptOut: vi.fn(),
};

vi.mock('../../../api/sms', async (original) => {
  const real = await original<typeof import('../../../api/sms')>();
  const forward = (name: string) => (...args: unknown[]) => ((api as Record<string, (...a: unknown[]) => unknown>)[name] as (...a: unknown[]) => unknown)(...args);
  return { ...real, sms: new Proxy({}, { get: (_t, name: string) => forward(name) }) };
});

const { SmsError } = await import('../../../api/sms');
const { SmsSettingsScreen } = await import('./settings');
const { WordingScreen } = await import('./wording');
const { OptOutsScreen } = await import('./opt-outs');
const { MemberSmsControl } = await import('../lending/member-sms');

const saved: SmsSettings = {
  enabled: false,
  operator_enabled: true,
  mode: 'live',
  test_message_available: false,
  sender_id: null,
  sender_status: 'none',
  approved_sender_ids: ['BrandTest1', 'BrandTest2'],
  window_start: '08:00',
  window_end: '20:00',
  window_zone: 'Africa/Kampala',
  window_earliest: '06:00',
  window_latest: '21:00',
  window_min_minutes: 60,
  daily_cap: null,
  monthly_cap: null,
  daily_ceiling: 300,
  monthly_ceiling: 9300,
  effective_daily_cap: 300,
  effective_monthly_cap: 9300,
  used_today: 0,
  used_this_month: 0,
  include_balance: false,
  acknowledgement: { text_version: 'sms-1', text: 'Switching on SMS sends messages to your members. Every message costs money.' },
  acknowledged_text_version: null,
  acknowledged_at: null,
  acknowledged_by: null,
  version: 3,
};

const ADMIN = ['core.settings.read', 'core.settings.manage', 'core.notifications.read', 'core.notification_templates.manage', 'lending.members.read', 'lending.members.update'];

function shell(node: ReactElement, permissions: string[] = ADMIN) {
  const me = { ...mockMe('admin'), permissions };
  const router = createRouter({ routeTree: appRouter.routeTree, history: createMemoryHistory({ initialEntries: ['/staff/sms'] }) });
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  return render(
    <RouterContextProvider router={router}>
      <QueryClientProvider client={client}>
        <StaffContext.Provider value={{ me, branch: null }}>{node}</StaffContext.Provider>
      </QueryClientProvider>
    </RouterContextProvider>,
  );
}

beforeEach(() => {
  api.settings.mockResolvedValue(saved);
});

afterEach(() => {
  cleanup();
  Object.values(api).forEach((m) => m.mockReset());
  vi.useRealTimers();
});

describe('switching SMS on', () => {
  it('shows the cost notice, needs it ticked, and sends the version of the text that was shown', async () => {
    api.changeSettings.mockResolvedValue({ ...saved, enabled: true, version: 4, acknowledged_text_version: 'sms-1', acknowledged_at: '2031-03-10T07:00:00Z' });
    shell(<SmsSettingsScreen />);
    fireEvent.click(await screen.findByRole('button', { name: 'Switch SMS on' }));
    expect(screen.getByTestId('sms-notice').textContent).toContain('Every message costs money.');
    const confirm = within(screen.getByRole('form', { name: 'Switch SMS on' })).getByRole('button', { name: 'Switch SMS on' }) as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);
    fireEvent.click(confirm);
    expect(api.changeSettings).not.toHaveBeenCalled();

    fireEvent.click(screen.getByLabelText(/I have read this and accept it/));
    expect(confirm.disabled).toBe(false);
    fireEvent.click(confirm);
    await waitFor(() => expect(api.changeSettings).toHaveBeenCalledTimes(1));
    expect(api.changeSettings).toHaveBeenCalledWith(3, { enabled: true, acknowledged_text_version: 'sms-1' });
    expect(await screen.findByText('SMS is on.')).toBeTruthy();
  });

  it('offers no switch to someone who may only look', async () => {
    shell(<SmsSettingsScreen />, ['core.settings.read']);
    await screen.findByText('Text messages (SMS)');
    expect(screen.queryByRole('button', { name: 'Switch SMS on' })).toBeNull();
    expect(screen.getByText(/Only a business administrator can switch SMS on or off/)).toBeTruthy();
    expect((screen.getByLabelText('Most messages a day') as HTMLInputElement).disabled).toBe(true);
    expect(screen.queryByRole('button', { name: 'Save changes' })).toBeNull();
  });

  it('says when the platform has not switched SMS on and when this is a test system', async () => {
    api.settings.mockResolvedValue({ ...saved, operator_enabled: false });
    shell(<SmsSettingsScreen />);
    expect(await screen.findByText(/platform has not switched SMS on for your business yet/)).toBeTruthy();
    cleanup();
    api.settings.mockResolvedValue({ ...saved, mode: 'sink' });
    shell(<SmsSettingsScreen />);
    expect(await screen.findByText(/messages are recorded but never leave the platform/)).toBeTruthy();
  });
});

describe('the settings form', () => {
  it('sends a cap of 0 as the number 0 and warns that it stops everything', async () => {
    api.settings.mockResolvedValue({ ...saved, enabled: true });
    api.changeSettings.mockResolvedValue({ ...saved, enabled: true, daily_cap: 0, effective_daily_cap: 0, version: 4 });
    shell(<SmsSettingsScreen />);
    const box = (await screen.findByLabelText('Most messages a day')) as HTMLInputElement;
    fireEvent.change(box, { target: { value: '0' } });
    expect(box.value).toBe('0');
    expect(screen.getByText(/A limit of 0 stops every message, receipts included/)).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.changeSettings).toHaveBeenCalledTimes(1));
    const [version, change] = api.changeSettings.mock.calls[0] as [number, Record<string, unknown>];
    expect(version).toBe(3);
    expect(change).toEqual({ daily_cap: 0 });
    expect(change.daily_cap).toBe(0);
    // Saved: the box still says 0, not empty, and the line says what it means.
    await waitFor(() => expect((screen.getByLabelText('Most messages a day') as HTMLInputElement).value).toBe('0'));
    expect(screen.getByText(/Now: 0 messages a day: nothing is sent while it is 0/)).toBeTruthy();
  });

  it('refuses a cap above the ceiling on the screen and does not ask the server', async () => {
    api.settings.mockResolvedValue({ ...saved, enabled: true });
    shell(<SmsSettingsScreen />);
    fireEvent.change(await screen.findByLabelText('Most messages a day'), { target: { value: '301' } });
    expect(screen.getByText('At most 300. A cap can be lowered, not raised.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Save changes' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Most messages a day'), { target: { value: '2.5' } });
    expect(screen.getByText(/Use a whole number/)).toBeTruthy();
    expect(api.changeSettings).not.toHaveBeenCalled();
  });

  it('lets the sender be chosen only from the approved names, never typed', async () => {
    shell(<SmsSettingsScreen />);
    const select = (await screen.findByLabelText('Sender name')) as HTMLSelectElement;
    expect(select.tagName).toBe('SELECT');
    expect(Array.from(select.options).map((o) => o.textContent)).toEqual(['The shared sender (default)', 'BrandTest1', 'BrandTest2']);
    expect(document.querySelector('input#sms-sender')).toBeNull();
  });

  it('warns when the platform withdrew the chosen sender name', async () => {
    api.settings.mockResolvedValue({ ...saved, sender_id: 'BrandOld1', sender_status: 'withdrawn', approved_sender_ids: ['BrandTest1'] });
    shell(<SmsSettingsScreen />);
    expect(await screen.findByText(/withdrew the sender name BrandOld1/)).toBeTruthy();
    expect(screen.getByRole('option', { name: 'BrandOld1 (no longer approved)' })).toBeTruthy();
  });

  it('refuses a window the server would refuse, and sends a good one', async () => {
    api.changeSettings.mockResolvedValue({ ...saved, window_start: '09:30', version: 4 });
    shell(<SmsSettingsScreen />);
    fireEvent.change(await screen.findByLabelText('From'), { target: { value: '05:00' } });
    expect(screen.getByText('Messages cannot start before 06:00.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Save changes' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('From'), { target: { value: '09:30' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.changeSettings).toHaveBeenCalledWith(3, { window_start: '09:30' }));
  });

  it('puts the server\'s word next to the box when it refuses a field', async () => {
    api.changeSettings.mockRejectedValue(new SmsError('Above the limit.', 422, 'validation_failed', { daily_cap: 'Above the limit set for your business. A cap can be lowered, not raised.' }));
    shell(<SmsSettingsScreen />);
    fireEvent.change(await screen.findByLabelText('Most messages a day'), { target: { value: '50' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Above the limit set for your business. A cap can be lowered, not raised.')).toBeTruthy();
  });

  it('says so, and loads the latest, when someone else saved first', async () => {
    api.changeSettings.mockRejectedValue(new SmsError('Someone else changed this. The latest has been loaded; check it and try again.', 409, 'version_conflict'));
    shell(<SmsSettingsScreen />);
    fireEvent.change(await screen.findByLabelText('Most messages a day'), { target: { value: '50' } });
    api.settings.mockResolvedValue({ ...saved, daily_cap: 10, effective_daily_cap: 10, version: 4 });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText(/Someone else changed this/)).toBeTruthy();
    // The form now starts from what the other person saved.
    await waitFor(() => expect((screen.getByLabelText('Most messages a day') as HTMLInputElement).value).toBe('10'));
    expect(api.settings).toHaveBeenCalledTimes(2);
  });
});

// ---- The wording -------------------------------------------------------------------------------------

const measure = { encoding: 'gsm7', units: 60, segments: 1, worst_case_segments: 3, estimated_cost_minor: 32, currency: 'UGX' };
const template = (over: Partial<SmsTemplate> = {}): SmsTemplate => ({
  event_key: 'savings.deposit',
  channel: 'sms',
  title: 'Savings deposit receipt',
  default_text: '{tenant_name}: Deposit of {currency} {amount} on account {account_no}. Receipt {receipt_no}.',
  placeholders: [
    { name: 'tenant_name', description: 'Your business name', sample: 'Sunrise Savers', max_length: 40 },
    { name: 'amount', description: 'The amount of this transaction', sample: '50,000', max_length: 15 },
    { name: 'account_no', description: 'The savings account number', sample: 'SV000123', max_length: 20 },
  ],
  override: null,
  sample_text: 'Sunrise Savers: Deposit of UGX 50,000 on account SV000123. Receipt RC-0042.',
  measure,
  note: null,
  max_chars: 320,
  max_worst_case_segments: 4,
  ...over,
});

describe('the wording', () => {
  it('previews the text as plain text, with its encoding, segments and cost estimate', async () => {
    api.templates.mockResolvedValue([template()]);
    api.previewTemplate.mockResolvedValue({
      text: '<b>A & B</b> "Sacco": paid 50,000',
      measure: { ...measure, encoding: 'ucs2', units: 33, segments: 1 },
    });
    shell(<WordingScreen />);
    const preview = await screen.findByTestId('sms-preview-text');
    await waitFor(() => expect(preview.textContent).toBe('<b>A & B</b> "Sacco": paid 50,000'));
    // Shown as text, never as markup.
    expect(preview.querySelector('b')).toBeNull();
    expect(screen.getByText(/Unicode \(UCS-2\)/)).toBeTruthy();
    expect(screen.getByText(/33 characters: 1 text message/)).toBeTruthy();
    expect(screen.getByText(/up to 3 if a name is long/)).toBeTruthy();
    expect(screen.getByText('About UGX 32 for this message, from what your messages cost in the last 30 days.')).toBeTruthy();
  });

  it('says so when the preview shows the default words because a value is too long for the wording', async () => {
    api.templates.mockResolvedValue([template({ note: 'The business name is too long for this wording, so the default words are shown.' })]);
    api.previewTemplate.mockResolvedValue({ text: 'Default words', measure, note: 'The business name is too long for this wording, so the default words are shown.' });
    shell(<WordingScreen />);
    expect(await screen.findByText(/business name is too long for this wording/)).toBeTruthy();
    expect(screen.getByRole('note').textContent).toMatch(/default words are shown/);
  });

  it('says there is no estimate when there is no history, and never invents one', async () => {
    api.templates.mockResolvedValue([template()]);
    api.previewTemplate.mockResolvedValue({ text: 'Paid', measure: { ...measure, estimated_cost_minor: null, currency: null } });
    shell(<WordingScreen />);
    expect(await screen.findByText(/No cost estimate yet: there are no sent messages in the last 30 days/)).toBeTruthy();
  });

  it('previews what is typed after a short pause, and saves under the override version (0 while there is none)', async () => {
    api.templates.mockResolvedValue([template()]);
    api.previewTemplate.mockImplementation(async (_event: string, text: string) => ({ text: `Preview of ${text}`, measure }));
    api.saveTemplate.mockResolvedValue(template({ override: { text: 'Paid {amount} on {account_no}', version: 1, updated_at: '2031-03-10T07:00:00Z' } }));
    shell(<WordingScreen />);
    const box = (await screen.findByLabelText('Message')) as HTMLTextAreaElement;
    fireEvent.change(box, { target: { value: 'Paid {amount} on {account_no}' } });
    await waitFor(() => expect(api.previewTemplate).toHaveBeenCalledWith('savings.deposit', 'Paid {amount} on {account_no}', expect.anything()));
    expect((await screen.findByTestId('sms-preview-text')).textContent).toBe('Preview of Paid {amount} on {account_no}');
    fireEvent.click(screen.getByRole('button', { name: 'Save my words' }));
    await waitFor(() => expect(api.saveTemplate).toHaveBeenCalledWith('savings.deposit', 0, 'Paid {amount} on {account_no}'));
  });

  it('shows the server\'s refusal of a placeholder and does not offer to save it', async () => {
    api.templates.mockResolvedValue([template()]);
    api.previewTemplate.mockImplementation(async (_event: string, text: string) => {
      if (text.includes('{unknown}')) {
        throw new SmsError('{unknown} is not a placeholder of this message.', 422, 'validation_failed', { text: '{unknown} is not a placeholder of this message.' });
      }
      return { text: 'ok', measure };
    });
    shell(<WordingScreen />);
    fireEvent.change(await screen.findByLabelText('Message'), { target: { value: 'Paid {unknown} {amount} {account_no}' } });
    expect(await screen.findByText('{unknown} is not a placeholder of this message.')).toBeTruthy();
    expect((screen.getByRole('button', { name: 'Save my words' }) as HTMLButtonElement).disabled).toBe(true);
    expect(api.saveTemplate).not.toHaveBeenCalled();
  });

  it('inserts a placeholder from its button, and offers no balance among them', async () => {
    api.templates.mockResolvedValue([template()]);
    api.previewTemplate.mockResolvedValue({ text: 'x', measure });
    shell(<WordingScreen />);
    const box = (await screen.findByLabelText('Message')) as HTMLTextAreaElement;
    fireEvent.change(box, { target: { value: 'Paid ' } });
    box.setSelectionRange(5, 5);
    fireEvent.click(screen.getByRole('button', { name: 'Insert {amount}' }));
    await waitFor(() => expect((screen.getByLabelText('Message') as HTMLTextAreaElement).value).toBe('Paid {amount}'));
    expect(screen.queryByRole('button', { name: 'Insert {balance}' })).toBeNull();
  });

  it('goes back to the default words only after a second click', async () => {
    api.templates.mockResolvedValue([template({ override: { text: 'Mine {amount} {account_no}', version: 2, updated_at: '2031-03-10T07:00:00Z' } })]);
    api.previewTemplate.mockResolvedValue({ text: 'x', measure });
    api.revertTemplate.mockResolvedValue(template());
    shell(<WordingScreen />);
    fireEvent.click(await screen.findByRole('button', { name: 'Go back to the default words' }));
    expect(api.revertTemplate).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Yes, use the default words' }));
    await waitFor(() => expect(api.revertTemplate).toHaveBeenCalledWith('savings.deposit', 2));
  });

  it('shows the test result in words and sends only what was asked', async () => {
    api.templates.mockResolvedValue([template()]);
    api.previewTemplate.mockResolvedValue({ text: 'x', measure });
    api.testMessage.mockResolvedValue({ sent: false, outcome: 'sunk', reason: null, mode: 'sink', recipient_masked: '+256*****0611', text: 'TEST: x', measure });
    shell(<WordingScreen />);
    fireEvent.change(await screen.findByLabelText('Phone number'), { target: { value: '0700000611' } });
    fireEvent.click(screen.getByRole('button', { name: 'Send a test message' }));
    expect(await screen.findByText('Recorded by the test sink for +256*****0611. Nothing left the platform.')).toBeTruthy();
    expect(api.testMessage).toHaveBeenCalledWith('savings.deposit', template().default_text, '0700000611');
  });

  it('lets someone who may only read look at the wording and nothing more', async () => {
    api.templates.mockResolvedValue([template()]);
    shell(<WordingScreen />, ['core.notifications.read']);
    expect(await screen.findByText(/Only a business administrator can change it/)).toBeTruthy();
    expect((screen.getByLabelText('Message') as HTMLTextAreaElement).readOnly).toBe(true);
    expect(screen.queryByRole('button', { name: 'Save my words' })).toBeNull();
    expect(screen.queryByLabelText('Phone number')).toBeNull();
    expect(api.previewTemplate).not.toHaveBeenCalled();
    expect(screen.getByTestId('sms-preview-text').textContent).toBe(template().sample_text);
  });

  it('tells a second administrator that someone else saved first', async () => {
    api.templates.mockResolvedValue([template()]);
    api.previewTemplate.mockResolvedValue({ text: 'x', measure });
    api.saveTemplate.mockRejectedValue(new SmsError('Someone else changed this. The latest has been loaded; check it and try again.', 409, 'version_conflict'));
    shell(<WordingScreen />);
    fireEvent.change(await screen.findByLabelText('Message'), { target: { value: 'Paid {amount} {account_no}' } });
    await waitFor(() => expect(api.previewTemplate).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: 'Save my words' }));
    expect(await screen.findByText(/Someone else changed this/)).toBeTruthy();
  });
});

// ---- The opt-out list --------------------------------------------------------------------------------

const row = (over: Partial<SmsOptOut> = {}): SmsOptOut => ({
  id: '00000000-0000-4000-8000-0000000000a1',
  kind: 'number',
  member_id: null,
  scope: 'all',
  source: 'number',
  recipient_masked: '+256*****0601',
  created_at: '2031-03-10T07:00:00Z',
  created_by: null,
  ...over,
});

describe('the opt-out list', () => {
  it('lists masked numbers and members, and searches after a pause', async () => {
    api.optOuts.mockResolvedValue({ items: [row(), row({ id: 'b', kind: 'member', member_id: '00000000-0000-4000-8000-0000000000m1', recipient_masked: null, scope: 'reminders' })], next_cursor: null });
    shell(<OptOutsScreen />);
    expect(await screen.findByText('+256*****0601')).toBeTruthy();
    expect(screen.getByText('Any number')).toBeTruthy();
    const table = within(screen.getByRole('table'));
    expect(table.getByText('Reminders and arrears notices')).toBeTruthy();
    expect(table.getByText('Every text message, receipts too')).toBeTruthy();
    expect(api.optOuts.mock.calls[0]?.[0]).toEqual({ q: undefined, scope: undefined, kind: undefined, cursor: undefined });

    api.optOuts.mockResolvedValue({ items: [row()], next_cursor: null });
    fireEvent.change(screen.getByLabelText('Find a number by its last digits'), { target: { value: '0601' } });
    await waitFor(() => expect(api.optOuts.mock.calls.some((c) => (c[0] as { q?: string }).q === '0601')).toBe(true));
  });

  it('adds a number and shows the server\'s word for one it cannot read', async () => {
    api.optOuts.mockResolvedValue({ items: [], next_cursor: null });
    api.addOptOut.mockRejectedValueOnce(new SmsError('Use a Uganda mobile number such as 0700 000 001.', 422, 'validation_failed', { phone: 'Use a Uganda mobile number such as 0700 000 001.' }));
    api.addOptOut.mockResolvedValueOnce(row());
    shell(<OptOutsScreen />);
    fireEvent.change(await screen.findByLabelText('Phone number'), { target: { value: '2125550101' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to the list' }));
    expect(await screen.findByText('Use a Uganda mobile number such as 0700 000 001.')).toBeTruthy();
    fireEvent.change(screen.getByLabelText('Phone number'), { target: { value: '0700000601' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add to the list' }));
    await waitFor(() => expect(api.addOptOut).toHaveBeenLastCalledWith('0700000601', 'all'));
    expect(await screen.findByText('Added.')).toBeTruthy();
  });

  it('checks a pasted list first and adds it only on the second click, naming the lines it could not read', async () => {
    api.optOuts.mockResolvedValue({ items: [], next_cursor: null });
    const dry: SmsImportResult = { dry_run: true, rows_read: 3, added: 2, changed: 0, unchanged: 0, invalid: 1, rows: [{ line: 1, outcome: 'added' }, { line: 2, outcome: 'invalid_phone' }, { line: 3, outcome: 'added' }] };
    api.importOptOuts.mockResolvedValueOnce(dry);
    api.importOptOuts.mockResolvedValueOnce({ ...dry, dry_run: false });
    shell(<OptOutsScreen />);
    fireEvent.change(await screen.findByLabelText(/Numbers, one on each line/), { target: { value: '0700000801\nnope\n0700000803' } });
    fireEvent.click(screen.getByRole('button', { name: 'Check the list' }));
    expect(await screen.findByText(/Checked, nothing added yet: 3 read, 2 would be added, 1 not a number we can text/)).toBeTruthy();
    expect(screen.getByText('Not read: number 2.')).toBeTruthy();
    expect(api.importOptOuts).toHaveBeenCalledTimes(1);
    expect(api.importOptOuts).toHaveBeenLastCalledWith('0700000801\nnope\n0700000803', 'all', true);
    fireEvent.click(screen.getByRole('button', { name: 'Add 2 to the list' }));
    await waitFor(() => expect(api.importOptOuts).toHaveBeenLastCalledWith('0700000801\nnope\n0700000803', 'all', false));
    expect(await screen.findByText(/Done: 3 read, 2 added/)).toBeTruthy();
  });

  it('lifts a row, and offers neither adding nor lifting to someone who may only look', async () => {
    api.optOuts.mockResolvedValue({ items: [row()], next_cursor: null });
    api.removeOptOut.mockResolvedValue(undefined);
    shell(<OptOutsScreen />);
    fireEvent.click(await screen.findByRole('button', { name: 'Lift the opt-out for +256*****0601' }));
    // The first click only opens the confirmation: nothing is sent until a reason is given and it is confirmed.
    const confirm = await screen.findByRole('button', { name: 'Lift the opt-out' });
    expect(api.removeOptOut).not.toHaveBeenCalled();
    expect((confirm as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Why is it lifted?'), { target: { value: 'ab' } });
    expect((confirm as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(screen.getByLabelText('Why is it lifted?'), { target: { value: 'Member asked at the counter' } });
    fireEvent.click(confirm);
    await waitFor(() => expect(api.removeOptOut).toHaveBeenCalledWith(row().id, 'Member asked at the counter'));
    cleanup();
    shell(<OptOutsScreen />, ['core.notifications.read']);
    await screen.findByText('+256*****0601');
    expect(screen.queryByRole('button', { name: /Lift the opt-out/ })).toBeNull();
    expect(screen.queryByLabelText('Phone number')).toBeNull();
  });
});

// ---- The member's own page --------------------------------------------------------------------------

describe('the member\'s text-message choice', () => {
  it('stops reminders, widens to every message, and lifts it again', async () => {
    api.memberOptOut.mockResolvedValue({ opted_out: false, scope: null, recipient_masked: null, since: null });
    api.setMemberOptOut.mockResolvedValue({ opted_out: true, scope: 'reminders', recipient_masked: '+256*****1201', since: '2031-03-10T07:00:00Z' });
    shell(<MemberSmsControl memberId="m1" />);
    expect(await screen.findByText('This member gets text messages.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: 'Stop reminders' }));
    await waitFor(() => expect(api.setMemberOptOut).toHaveBeenCalledWith('m1', 'reminders'));
    expect(await screen.findByText(/Reminders and arrears notices are not sent. Receipts still are/)).toBeTruthy();
    expect(screen.getByText(/Recorded when the member's number was \+256\*\*\*\*\*1201/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Stop reminders' })).toBeNull();
    api.liftMemberOptOut.mockResolvedValue({ opted_out: false, scope: null, recipient_masked: null, since: null });
    fireEvent.click(screen.getByRole('button', { name: 'Allow text messages again' }));
    expect(api.liftMemberOptOut).not.toHaveBeenCalled();
    fireEvent.change(screen.getByLabelText('Why is it lifted?'), { target: { value: 'Member asked at the counter' } });
    fireEvent.click(screen.getByRole('button', { name: 'Lift the opt-out' }));
    await waitFor(() => expect(api.liftMemberOptOut).toHaveBeenCalledWith('m1', 'Member asked at the counter'));
    expect(await screen.findByText('This member gets text messages.')).toBeTruthy();
  });

  it('lets an officer stop more, but offers neither narrowing nor lifting', async () => {
    const officer = ['lending.members.read', 'lending.members.update'];
    api.memberOptOut.mockResolvedValue({ opted_out: true, scope: 'all', recipient_masked: null, since: '2031-03-10T07:00:00Z' });
    shell(<MemberSmsControl memberId="m1" />, officer);
    expect(await screen.findByText(/No text message at all is sent/)).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
    cleanup();
    api.memberOptOut.mockResolvedValue({ opted_out: true, scope: 'reminders', recipient_masked: null, since: '2031-03-10T07:00:00Z' });
    shell(<MemberSmsControl memberId="m1" />, officer);
    expect(await screen.findByRole('button', { name: 'Change to: stop every text message' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Allow text messages again' })).toBeNull();
  });

  it('shows the choice but no buttons to someone who may not change it, and nothing without permission to read', async () => {
    api.memberOptOut.mockResolvedValue({ opted_out: true, scope: 'all', recipient_masked: null, since: '2031-03-10T07:00:00Z' });
    shell(<MemberSmsControl memberId="m1" />, ['lending.members.read']);
    expect(await screen.findByText(/No text message at all is sent, receipts included/)).toBeTruthy();
    expect(screen.queryByRole('button')).toBeNull();
    cleanup();
    api.memberOptOut.mockReset();
    shell(<MemberSmsControl memberId="m1" />, ['lending.savings.read']);
    expect(screen.queryByText('Text messages')).toBeNull();
    expect(api.memberOptOut).not.toHaveBeenCalled();
  });
});
