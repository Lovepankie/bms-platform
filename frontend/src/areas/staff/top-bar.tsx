import { QueryClientContext } from '@tanstack/react-query';
import { type ReactNode, useContext, useEffect, useId, useRef, useState } from 'react';
import type { Me } from '../../api/client';
import { useShellBrand } from '../../app/branding';
import { useDeviceOnline, useLoadedAt } from '../../app/refresh-button';
import { useOffline, useOnline } from '../../offline/offline-context';
import { figuresTime } from '../../offline/read-cache';
import { recordsSalesOffline } from './retail/permissions';
import { THEME_LABELS } from '../../app/shell';
import { THEME_CHOICES, type ThemeChoice, useThemeChoice } from '../../app/theme-mode';
import { BusyButton } from '../../app/ui/BusyButton';
import { Segmented } from '../../app/ui/Segmented';
import { Sheet } from '../../app/ui/Sheet';
import { ALL_BRANCHES, branchLabel } from '../../auth/branch';
import { icons } from '../../components/icons';
import { useHelpSheet } from '../../tour/host';
import { BranchOptions, ShopLogo } from './branch-picker';

// The parts of the staff top bar (issue #526): one 56px bar whose title is the business and the branch in use, tappable to
// switch branch, and one menu at the right that holds who is signed in, Help, Light or dark and Sign out (Refresh is a button in
// the bar again since issue #631). The Online dot is drawn only when it has something to say (offline, sending, or sales waiting)
// and the offline banner sits under the bar.

/**
 * The bar's title: the business on a small line, the branch in use under it in larger type. With more than one branch to
 * choose, the whole title is a native select laid over it (named Branch), so a tap opens the phone's own picker.
 */
export function BranchTitle({ me, branch, onChoose }: { me: Me; branch: string | null; onChoose: (selection: string) => void }) {
  const brand = useShellBrand();
  const branches = me.branches ?? [];
  const active = branches.find((b) => b.id === branch);
  const name = active ? branchLabel(active) : branch === ALL_BRANCHES ? 'All branches' : '';
  const choices = branches.length + (me.all_branches ? 1 : 0);
  return (
    <div className="top-title" data-tour="branch-picker">
      {active ? <ShopLogo url={active.logo_url} name={name} /> : null}
      <span className="top-title-text">
        <span className="top-title-business">{brand.name}</span>
        <span className="top-title-branch">
          {name}
          {choices > 1 && <span className="top-title-chevron" aria-hidden="true">{icons.chevron}</span>}
        </span>
      </span>
      {choices > 1 && (
        <select className="top-title-select" aria-label="Branch" value={branch ?? ''} onChange={(e) => onChoose(e.target.value)}>
          <BranchOptions me={me} />
        </select>
      )}
    </div>
  );
}

/** Light, Dark or Same as my phone, as one Segmented inside the menu. */
function ThemeChoices() {
  const [choice, setChoice] = useThemeChoice();
  return (
    <div className="top-menu-theme">
      <span className="top-menu-label">Theme</span>
      <Segmented<ThemeChoice> label="Theme" value={choice} onChange={setChoice} options={THEME_CHOICES.map((value) => ({ value, label: THEME_LABELS[value] }))} />
    </div>
  );
}

/**
 * The one menu at the right of the top bar. A disclosure, not a role=menu: it holds a status line and a Segmented as well as
 * buttons. It closes on Escape (focus back to its button) and on a press outside, and keeps its items mounted while closed. Help closes it and opens the Help sheet, which lives outside it.
 */
export function TopMenu({ me, onSignOut, onSignOutEverywhere, onMyDetails, busy, signOut, children }: { me: Me; onSignOut: () => void; onSignOutEverywhere?: () => void; onMyDetails?: () => void; busy: boolean; signOut: boolean; children?: ReactNode }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const wrap = useRef<HTMLDivElement>(null);
  const button = useRef<HTMLButtonElement>(null);
  const help = useHelpSheet(true);

  useEffect(() => {
    if (!open) return;
    const onPointer = (event: PointerEvent) => {
      if (!wrap.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      button.current?.focus();
    };
    document.addEventListener('pointerdown', onPointer);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPointer);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className="top-menu" ref={wrap}>
      <button
        ref={button}
        type="button"
        className="btn-ghost btn-icon top-menu-button"
        aria-label={`Menu for ${me.full_name ?? 'you'}`}
        aria-expanded={open}
        aria-controls={panelId}
        data-tour="top-menu"
        onClick={() => setOpen(!open)}
      >
        <span className="avatar">{icons.user}</span>
      </button>
      <div id={panelId} className="top-menu-panel" hidden={!open}>
        <p className="top-menu-user">{me.full_name}</p>
        {onMyDetails && (
          // Issue #883: the signed-in person's name, phone and email, in a sheet.
          <button
            type="button"
            className="btn-ghost top-menu-item"
            aria-haspopup="dialog"
            data-tour="my-details"
            onClick={() => {
              setOpen(false);
              onMyDetails();
            }}
          >
            {icons.user}
            My details
          </button>
        )}
        <button
          type="button"
          className="btn-ghost top-menu-item"
          aria-haspopup="dialog"
          onClick={() => {
            setOpen(false);
            help.open();
          }}
        >
          {icons.info}
          Help
        </button>
        <ThemeChoices />
        {children}
        {signOut && (
          <BusyButton type="button" className="btn-ghost top-menu-item" busy={busy} onClick={onSignOut}>
            {icons.signOut}
            Sign out
          </BusyButton>
        )}
        {onSignOutEverywhere && (
          // On every screen size: a lost phone is signed out from whichever device is at hand (issue #647).
          <button
            type="button" /* busy-exempt: opens a sheet that asks first */
            className="btn-ghost top-menu-item"
            aria-haspopup="dialog"
            disabled={busy}
            onClick={() => {
              setOpen(false);
              onSignOutEverywhere();
            }}
          >
            {icons.signOut}
            Sign out everywhere
          </button>
        )}
      </div>
      {help.sheet}
    </div>
  );
}

/**
 * Asks before Sign out everywhere (issue #647): it ends every session of the person, this device included, so a lost or lent
 * phone is refused on its next request. Sales still waiting on this phone stay here, as with Sign out.
 */
export function SignOutEverywhere({ open, waiting, busy, onConfirm, onClose }: { open: boolean; waiting: number; busy: boolean; onConfirm: () => void; onClose: () => void }) {
  return (
    <Sheet
      open={open}
      onClose={onClose}
      title="Sign out everywhere?"
      actions={
        <>
          <button type="button" /* busy-exempt: closes the sheet */ className="btn-ghost" disabled={busy} onClick={onClose}>
            Cancel
          </button>
          <BusyButton type="button" className="btn-danger" busy={busy} onClick={onConfirm}>
            Sign out everywhere
          </BusyButton>
        </>
      }
    >
      <p>You are signed out on every phone and computer, this one too. Use it when a phone is lost or someone else may be using your account.</p>
      <p>You sign in again with your password.</p>
      {waiting > 0 && <p className="muted">{waiting === 1 ? '1 sale is' : `${waiting} sales are`} still on this phone. They stay here and are sent when you sign in here again.</p>}
    </Sheet>
  );
}

/**
 * Under the bar while the device has no network or cannot reach the server: what still works and how old the figures are,
 * in one line, the page's only offline notice (issue #763). Nothing at all while online.
 */
export function OfflineBanner({ me }: { me?: Me }) {
  const deviceOnline = useDeviceOnline();
  const reachable = useOnline();
  const offline = useOffline();
  const loadedAt = useLoadedAt(useContext(QueryClientContext)?.getQueryCache());
  const shown = !(deviceOnline && reachable);
  // "today at" stays true across midnight: the time is read again each minute while the banner shows.
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!shown) return;
    setNow(Date.now());
    const id = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(id);
  }, [shown]);
  if (!shown) return null;
  // Only a person who may sell has the sale screen offline (ADR-067); the same check as the Home hint (home-cards.tsx).
  const sells = offline !== null && me !== undefined && recordsSalesOffline(me);
  const sell = sells ? ' You can still sell; your sales will send when you are back online.' : ' You see what was loaded before.';
  const figures = loadedAt > 0 ? ` Figures are from ${figuresTime(loadedAt, Math.max(now, loadedAt))}.` : '';
  return (
    <p role="status" className="offline-banner">
      {icons.info}
      <span>{`You are offline.${sell}${figures}`}</span>
    </p>
  );
}
