import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, createLazyRoute } from '@tanstack/react-router';
import { type ChangeEvent, useEffect, useRef, useState } from 'react';
import { api, fetchSettings, problemOf, type TenantSettings } from '../../api/client';
import { BRANDING_QUERY, appTitle, brandOf, useShellBrand } from '../../app/branding';
import { checkColour, colourMessage, suggestColour } from '../../app/contrast';
import { NewIconNote, rememberIconVersion } from '../../install/NewIcon';
import { useStaff } from './context';

// Business set-up (FR-TEN-08): name, logo, theme colour and receipt footer, with a checklist a new
// tenant admin lands on until it is dismissed. The logo is checked and re-encoded by the API; the
// colour rule is the same one the API enforces (app/contrast.ts), so the preview and the save agree.

const ACCEPT = 'image/png,image/jpeg,image/webp';

/** The dominant colour of a chosen image, from a small copy of it; null when it cannot be read. */
async function suggestFrom(file: File): Promise<string | null> {
  try {
    const bitmap = await createImageBitmap(file);
    const canvas = document.createElement('canvas');
    canvas.width = 64;
    canvas.height = 64;
    const context = canvas.getContext('2d');
    if (!context) return null;
    context.drawImage(bitmap, 0, 0, 64, 64);
    bitmap.close();
    return suggestColour(context.getImageData(0, 0, 64, 64).data);
  } catch {
    return null;
  }
}

export function Setup() {
  const { me } = useStaff();
  const queryClient = useQueryClient();
  const brand = useShellBrand();
  const permissions = me.permissions ?? [];
  const settings = useQuery({ queryKey: ['settings'], queryFn: fetchSettings });
  const branches = useQuery({
    queryKey: ['setup-branches'],
    enabled: permissions.includes('core.branches.read'),
    queryFn: async () => (await api.GET('/api/v1/branches')).data?.items?.length ?? 0,
  });
  const users = useQuery({
    queryKey: ['setup-users'],
    enabled: permissions.includes('core.users.read'),
    queryFn: async () => (await api.GET('/api/v1/users', { params: { query: { limit: 2 } } })).data?.items?.length ?? 0,
  });

  const [name, setName] = useState('');
  const [footer, setFooter] = useState('');
  const [colour, setColour] = useState('');
  const [suggested, setSuggested] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // A saved logo or colour changes the installed app's icon (ADR-066), so the admin is told how it reaches a phone (issue #453).
  const [iconSaved, setIconSaved] = useState(false);

  // The form is filled once from the saved settings and then belongs to the admin: a logo upload,
  // removal or dismissal changes the settings version but must not wipe what has been typed.
  const loaded = settings.data;
  const seeded = useRef(false);
  useEffect(() => {
    if (!loaded || seeded.current) return;
    seeded.current = true;
    setName(loaded.display_name ?? '');
    setFooter(loaded.receipt_footer ?? '');
    setColour(loaded.theme_primary ?? '');
  }, [loaded]);

  if (settings.isError) return <p>Could not load the settings.</p>;
  if (!loaded) return <p>Loading</p>;
  const current: TenantSettings = loaded;
  const check = checkColour(colour);
  const colourProblem = colour === '' ? null : colourMessage(check);

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ['settings'] });
    await queryClient.invalidateQueries({ queryKey: ['branding'] });
  }

  /** After a logo or colour save: this device stores the new icon version, so its own new icon card stays away (issue #453). */
  async function refreshIcon() {
    await queryClient.invalidateQueries({ queryKey: ['settings'] });
    try {
      const branding = await queryClient.fetchQuery({ ...BRANDING_QUERY, staleTime: 0 });
      rememberIconVersion(brandOf(branding, null).iconVersion ?? '');
    } catch {
      // The save itself worked; at worst this device shows the card once.
    }
  }

  async function run(action: () => Promise<string | null>) {
    setBusy(true);
    setMessage(null);
    try {
      setMessage(await action());
    } catch (error) {
      setMessage(error instanceof Error ? error.message : (problemOf(error).detail ?? 'Something went wrong. Try again.'));
    } finally {
      setBusy(false);
    }
  }

  function save() {
    void run(async () => {
      const body: Record<string, string> = {};
      if (name.trim() !== (current.display_name ?? '')) body.display_name = name.trim();
      if (footer !== (current.receipt_footer ?? '')) body.receipt_footer = footer;
      if (colour.toUpperCase() !== (current.theme_primary ?? '')) body.theme_primary = colour;
      if (Object.keys(body).length === 0) return 'Nothing to save.';
      const { error } = await api.PATCH('/api/v1/settings', {
        params: { header: { 'If-Match': `"${current.version}"` } },
        body,
      });
      if (error) {
        const problem = problemOf(error);
        throw new Error(problem.errors?.[0]?.message ?? problem.detail ?? 'Could not save.');
      }
      if (body.theme_primary === undefined) {
        await refresh();
      } else {
        await refreshIcon();
        setIconSaved(true);
      }
      return colour === '' ? 'Saved. The platform colour is in use.' : 'Saved.';
    });
  }

  function upload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    void run(async () => {
      const { error } = await api.PUT('/api/v1/settings/logo', {
        body: { file } as unknown as { file: string },
        bodySerializer: (body) => {
          const form = new FormData();
          form.append('file', (body as unknown as { file: File }).file);
          return form;
        },
      });
      if (error) throw new Error(problemOf(error).detail ?? 'Could not upload the logo.');
      await refreshIcon();
      setIconSaved(true);
      const found = await suggestFrom(file);
      if (found) {
        setColour(found);
        setSuggested(true);
        return 'Logo saved. A theme colour was suggested from it: check the preview, then save.';
      }
      return 'Logo saved.';
    });
  }

  function removeLogo() {
    void run(async () => {
      const { error } = await api.DELETE('/api/v1/settings/logo');
      if (error) throw new Error(problemOf(error).detail ?? 'Could not remove the logo.');
      await refreshIcon();
      setIconSaved(false);
      return 'Logo removed.';
    });
  }

  function setReports(on: boolean) {
    void run(async () => {
      const { error } = await api.PATCH('/api/v1/settings', {
        params: { header: { 'If-Match': `"${current.version}"` } },
        body: { reports_enabled: on },
      });
      if (error) throw new Error(problemOf(error).detail ?? 'Could not change the reports setting.');
      await refresh();
      await queryClient.invalidateQueries({ queryKey: ['me'] });
      return on ? 'Reports are on. The Reports tab is now shown to staff who may run reports.' : 'Reports are off.';
    });
  }

  function dismiss() {
    void run(async () => {
      const { error } = await api.PATCH('/api/v1/settings', {
        params: { header: { 'If-Match': `"${current.version}"` } },
        body: { setup_dismissed: true },
      });
      if (error) throw new Error(problemOf(error).detail ?? 'Could not dismiss the checklist.');
      await refresh();
      return null;
    });
  }

  const items: { label: string; done: boolean | null }[] = [
    { label: 'Business name', done: current.display_name_set ?? false },
    { label: 'Logo', done: Boolean(current.logo_document_id) },
    { label: 'Theme colour', done: Boolean(current.theme_primary) },
    { label: 'Receipt footer', done: (current.receipt_footer ?? '') !== '' },
    { label: 'First branch (besides the head office)', done: branches.data === undefined ? null : branches.data > 1 },
    { label: 'First staff user (besides you)', done: users.data === undefined ? null : users.data > 1 },
  ];

  return (
    <main>
      <h1>Business set-up</h1>
      {!current.setup_dismissed && (
        <section aria-labelledby="checklist" data-tour="setup-checklist">
          <h2 id="checklist">Checklist</h2>
          <ul>
            {items
              .filter((item) => item.done !== null)
              .map((item) => (
                <li key={item.label}>
                  {item.label}: <strong>{item.done ? 'Done' : 'To do'}</strong>
                </li>
              ))}
          </ul>
          <button disabled={busy} onClick={dismiss}>
            Dismiss the checklist
          </button>
        </section>
      )}

      <h2>Business</h2>
      <div style={{ display: 'grid', gap: 12, maxWidth: 480 }}>
        <label data-tour="setup-name">
          Business name
          <input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} style={{ display: 'block', width: '100%' }} />
        </label>
        <label>
          Receipt footer
          <textarea value={footer} maxLength={500} onChange={(e) => setFooter(e.target.value)} style={{ display: 'block', width: '100%' }} />
        </label>

        <fieldset data-tour="setup-logo">
          <legend>Logo</legend>
          {current.logo_document_id && brand.logoSrc ? (
            <p>
              <img src={brand.logoSrc} alt={`Current logo of ${name || 'the business'}`} style={{ maxHeight: 96 }} />
            </p>
          ) : (
            <p>No logo yet.</p>
          )}
          <label>
            {current.logo_document_id ? 'Replace the logo' : 'Upload a logo'} (PNG, JPEG or WebP, up to 1 MB, at least 128 px)
            <input type="file" accept={ACCEPT} disabled={busy} onChange={upload} style={{ display: 'block' }} />
          </label>
          {current.logo_document_id && (
            <button disabled={busy} onClick={removeLogo}>
              Remove the logo
            </button>
          )}
          {iconSaved && current.logo_document_id && <NewIconNote name={appTitle(brand)} />}
        </fieldset>

        <fieldset data-tour="setup-colour">
          <legend>Theme colour</legend>
          <label>
            Colour
            <input type="color" value={check.ok || check.reason === 'contrast' ? colour : '#0d5c75'} onChange={(e) => { setColour(e.target.value.toUpperCase()); setSuggested(false); }} aria-label="Pick a colour" />
          </label>{' '}
          <label>
            Hex
            <input
              value={colour}
              placeholder="#0D5C75"
              maxLength={7}
              aria-invalid={colourProblem !== null}
              aria-describedby={colourProblem ? 'colour-problem' : undefined}
              onChange={(e) => { setColour(e.target.value); setSuggested(false); }}
            />
          </label>{' '}
          {current.theme_primary && (
            <button type="button" disabled={busy} onClick={() => { setColour(''); setSuggested(false); }}>
              Use the platform colour
            </button>
          )}
          {suggested && <p role="status">Suggested from your logo.</p>}
          {colourProblem && <p id="colour-problem" role="alert">{colourProblem}</p>}
          {check.ok && (
            <p>
              <span style={{ background: colour, color: check.text, padding: '6px 12px', borderRadius: 4 }}>Preview of text on your colour</span>{' '}
              (contrast {check.ratio.toFixed(1)} to 1)
            </p>
          )}
        </fieldset>

        <fieldset>
          <legend>Reports</legend>
          <label>
            <input type="checkbox" checked={current.reports_enabled === true} disabled={busy} onChange={(e) => setReports(e.target.checked)} />{' '}
            Let staff run reports (portfolio at risk and others), prepared in the background and kept for 30 days
          </label>
        </fieldset>

        {permissions.includes('core.branches.read') && (
          <fieldset>
            <legend>Branches</legend>
            <p>
              <Link to={'/staff/setup/branches' as never}>Branches</Link>: close a shop you no longer use and keep its history, reopen it, or say that your head office keeps no goods.
            </p>
          </fieldset>
        )}

        {permissions.includes('core.users.read') && (
          <fieldset>
            <legend>Staff</legend>
            <p>
              <Link to="/staff/users">Users</Link>: invite your staff, give each person a role, and see who has not joined yet.
            </p>
          </fieldset>
        )}

        <fieldset>
          <legend>Two-step sign-in</legend>
          <p>
            <Link to={'/staff/setup/two-step' as never}>Two-step sign-in</Link>: ask your staff for a code from their phone as well as the password. It is optional until you choose who must use it.
          </p>
          <p>
            <Link to={'/staff/two-step' as never}>Turn it on for yourself</Link>.
          </p>
        </fieldset>

        <fieldset>
          <legend>Notifications</legend>
          <p>
            <Link to={'/staff/setup/notifications' as never}>Notifications for the business</Link>: which notices your staff get on their phones (new
            orders, low stock, credit to collect) and when the daily summary goes.
          </p>
        </fieldset>

        <fieldset>
          <legend>Text messages</legend>
          <p>
            <Link to="/staff/sms">SMS settings, wording and opt-outs</Link>: switch text messages on, choose the sender name and sending hours, and reword the messages.
          </p>
        </fieldset>

        <button disabled={busy || (colour !== '' && !check.ok)} onClick={save} data-tour="setup-save">
          Save
        </button>
        {message && <p role="status">{message}</p>}
      </div>
    </main>
  );
}

export const Route = createLazyRoute('/staff/setup')({ component: Setup });
