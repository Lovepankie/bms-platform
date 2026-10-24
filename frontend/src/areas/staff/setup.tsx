import { useQuery, useQueryClient } from '@tanstack/react-query';
import { createLazyRoute } from '@tanstack/react-router';
import { type ChangeEvent, useEffect, useRef, useState } from 'react';
import { api, fetchSettings, problemOf, type TenantSettings } from '../../api/client';
import { BRANDING_QUERY, appTitle, brandOf, useShellBrand } from '../../app/branding';
import { checkColour, colourMessage, suggestColour } from '../../app/contrast';
import { BusyButton } from '../../app/ui/BusyButton';
import { Tile, TileGrid } from '../../app/ui/Tile';
import { icons } from '../../components/icons';
import { DataExportCard } from './data-export';
import { NewIconNote, rememberIconVersion } from '../../install/NewIcon';
import { useStaff } from './context';
import { PlanCard } from './plan';
import { Hint } from '../../app/ui/Hint';

// Business set-up (FR-TEN-08): name, logo, theme colour and receipt footer, with a checklist a new
// tenant admin lands on until it is dismissed. The logo is checked and re-encoded by the API; the
// colour rule is the same one the API enforces (app/contrast.ts), so the preview and the save agree.

const ACCEPT = 'image/png,image/jpeg,image/webp';
const HINT = { display: 'block', fontSize: '0.875em', opacity: 0.8 } as const;

/** What the reports cover, in the words of the modules the business has on (issue #468); null modules means an older /me without the list. */
export function reportsWording(modules: string[] | null | undefined): string {
  const retail = !modules || modules.includes('retail');
  const lending = !modules || modules.includes('lending');
  if (retail && lending) return 'sales, stock, profit, portfolio at risk and others';
  if (lending) return 'portfolio at risk and others';
  if (retail) return 'sales, stock, profit and others';
  return 'and others';
}

/** The business notices are all retail ones today, so a business without retail is not shown their page (issue #468). */
export function hasRetail(modules: string[] | null | undefined): boolean {
  return !modules || modules.includes('retail');
}

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

/**
 * Which of the business's own checklist items are done, from what is saved (issue #514): the name when the business has one
 * (the one given at onboarding counts), the logo when there is one, the theme colour once one was chosen (none means the
 * platform look), the receipt footer when it is not blank.
 */
export function brandDone(settings: TenantSettings): { name: boolean; logo: boolean; colour: boolean; footer: boolean } {
  return {
    name: Boolean(settings.display_name_set) || (settings.display_name ?? '').trim() !== '',
    logo: Boolean(settings.logo_document_id),
    colour: (settings.theme_primary ?? '') !== '',
    footer: (settings.receipt_footer ?? '').trim() !== '',
  };
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

  // ADR-071: the day the app became the register; only the Owner moves it (the server answers owner_only for anyone else).
  function setGoLive(date: string) {
    void run(async () => {
      const { error } = await api.PATCH('/api/v1/settings', {
        params: { header: { 'If-Match': `"${current.version}"` } },
        body: { go_live_date: date },
      });
      if (error) {
        const problem = problemOf(error);
        throw new Error(problem.errors?.[0]?.message ?? problem.detail ?? 'Could not save the go-live date.');
      }
      await refresh();
      return `The go-live date is ${date}. Existing loans given out before it can be brought in.`;
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

  const done = brandDone(current);
  const items: { label: string; done: boolean | null }[] = [
    { label: 'Business name', done: done.name },
    { label: 'Logo', done: done.logo },
    { label: 'Theme colour', done: done.colour },
    { label: 'Receipt footer', done: done.footer },
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
          <BusyButton busy={busy} onClick={dismiss}>
            Dismiss the checklist
          </BusyButton>
        </section>
      )}

      <h2>Business</h2>
      <div style={{ display: 'grid', gap: 12, maxWidth: 480 }}>
        <div data-tour="setup-name">
          <label>
            Business name
            <input value={name} maxLength={100} aria-describedby="name-hint" onChange={(e) => setName(e.target.value)} style={{ display: 'block', width: '100%' }} />
          </label>
          <span id="name-hint" style={HINT}>The name your staff and customers see on the app, receipts and text messages.</span>
        </div>
        <div>
          <label>
            Receipt footer
            <textarea
              value={footer}
              maxLength={500}
              placeholder="Thank you for shopping with us. Returns within 7 days with this receipt."
              aria-describedby="footer-hint"
              onChange={(e) => setFooter(e.target.value)}
              style={{ display: 'block', width: '100%' }}
              />
          </label>
          <span id="footer-hint" style={HINT}>
            A short line printed at the bottom of every receipt, for example a thank-you, your phone or Instagram, or how returns work.
          </span>
        </div>
        {footer.trim() !== '' && (
          <div data-testid="footer-preview" role="group" aria-label="Bottom of a receipt" style={{ fontFamily: 'monospace', border: '1px dashed', padding: 8, maxWidth: 320, textAlign: 'center', whiteSpace: 'pre-wrap' }}>
            <div>TOTAL ...</div>
            <div>--------------------</div>
            {footer.trim()}
          </div>
        )}

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
            <BusyButton busy={busy} onClick={removeLogo}>
              Remove the logo
            </BusyButton>
          )}
          {iconSaved && current.logo_document_id && <NewIconNote name={appTitle(brand)} />}
        </fieldset>

        <fieldset data-tour="setup-colour">
          <legend>Theme colour</legend>
          <p id="colour-hint" style={HINT}>
            The colour of the app's top bar and buttons. The app picks white or dark text for it; colours where neither is readable are refused.
          </p>
          <label>
            Colour
            <input type="color" value={check.ok || check.reason === 'contrast' ? colour : '#0d5c75'} onChange={(e) => { setColour(e.target.value.toUpperCase()); setSuggested(false); }} aria-label="Pick a colour" aria-describedby="colour-hint" />
          </label>{' '}
          <label>
            Hex
            <input
              value={colour}
              placeholder="#0D5C75"
              maxLength={7}
              aria-invalid={colourProblem !== null}
              aria-describedby={colourProblem ? 'colour-hint colour-problem' : 'colour-hint'}
              onChange={(e) => { setColour(e.target.value); setSuggested(false); }}
            />
          </label>{' '}
          {current.theme_primary && (
            <button type="button" /* busy-exempt: only changes the form */ disabled={busy} onClick={() => { setColour(''); setSuggested(false); }}>
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
            Let staff run reports ({reportsWording(me.modules)}), prepared in the background and kept for 30 days
          </label>
        </fieldset>

        {permissions.includes('core.data_export.run') && <DataExportCard modules={me.modules} zone={me.tenant_timezone} />}

        {permissions.includes('core.settings.manage') && <PlanCard mfaEnabled={me.mfa_enabled === true} />}

        {(me.modules ?? ['lending']).includes('lending') && (
          <fieldset>
            <legend>Go-live date</legend>
            <label htmlFor="go-live-date">The day the app became your register (only the Owner of the business changes it)</label>
            <input id="go-live-date" type="date" disabled={busy} defaultValue={current.go_live_date ?? ''}
              onBlur={(e) => { if (e.target.value && e.target.value !== current.go_live_date) setGoLive(e.target.value); }} />
            <Hint>
              Loans given out before this day can be brought in from your paper register with their real dates. Until it is set, the day an
              administrator first signed in counts.
            </Hint>
          </fieldset>
        )}

        <BusyButton busy={busy} disabled={colour !== '' && !check.ok} onClick={save} data-tour="setup-save">
          Save
        </BusyButton>
        {message && <p role="status">{message}</p>}
      </div>

      <h2>More set-up</h2>
      <TileGrid label="More set-up">
        {permissions.includes('core.branches.read') && (
          <Tile to="/staff/setup/branches" icon={icons.building} label="Branches" hint="Close or reopen a shop, or say your head office keeps no goods" />
        )}
        {permissions.includes('core.users.read') && (
          <Tile to="/staff/users" icon={icons.people} label="Users" hint="Invite your staff, give each a role, see who has not joined" />
        )}
        {permissions.includes('core.billing.read') && (
          <Tile to="/staff/setup/billing" icon={icons.creditSales} label="Plan and billing" hint="Your plan, what is paid and until when, and every receipt" />
        )}
        <Tile to="/staff/setup/two-step" icon={icons.shield} label="Two-step sign-in" hint="Ask your staff for a code from their phone as well" />
        <Tile to="/staff/two-step" icon={icons.lock} label="Two-step for yourself" hint="Turn on a code from your phone for your own sign-in" />
        {hasRetail(me.modules) && (
          <Tile to="/staff/setup/notifications" icon={icons.bell} label="Notifications for the business" hint="Notices for new orders, low stock, credit to collect, and the daily summary" />
        )}
        {permissions.includes('core.referrals.read') && (
          <Tile to="/staff/setup/referrals" icon={icons.arrow} label="Referrals" hint="Your link for other businesses and who signed up with it" />
        )}
        <Tile to="/staff/sms" icon={icons.chat} label="Text messages" hint="Switch SMS on, choose the sender name and hours, reword messages" />
      </TileGrid>
    </main>
  );
}

export const Route = createLazyRoute('/staff/setup')({ component: Setup });
