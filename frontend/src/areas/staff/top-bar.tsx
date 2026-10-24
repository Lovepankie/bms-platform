import { type ReactNode, useEffect, useId, useRef, useState } from 'react';
import type { Me } from '../../api/client';
import { useShellBrand } from '../../app/branding';
import { RefreshButton, useDeviceOnline } from '../../app/refresh-button';
import { THEME_LABELS } from '../../app/shell';
import { THEME_CHOICES, type ThemeChoice, useThemeChoice } from '../../app/theme-mode';
import { BusyButton } from '../../app/ui/BusyButton';
import { Segmented } from '../../app/ui/Segmented';
import { ALL_BRANCHES, branchLabel } from '../../auth/branch';
import { icons } from '../../components/icons';
import { useHelpSheet } from '../../tour/host';
import { ShopLogo } from './branch-picker';

// The parts of the staff top bar (issue #526): one 56px bar whose title is the business and the branch in use, tappable to
// switch branch, and one menu at the right that holds who is signed in, Help, Refresh, Light or dark and Sign out. The Online
// dot is drawn only when it has something to say (offline, sending, or sales waiting) and the offline banner sits under the bar.

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
          {me.all_branches && <option value={ALL_BRANCHES}>All branches</option>}
          {branches.map((b) => (
            <option key={b.id} value={b.id}>
              {branchLabel(b)}
              {b.stocks_goods === false ? ' (no stock)' : b.sells_goods === false ? ' (store)' : ''}
            </option>
          ))}
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
 * buttons. It closes on Escape (focus back to its button) and on a press outside, and keeps its items mounted while closed so
 * Refresh keeps its live region and its "Updated" line. Help closes it and opens the Help sheet, which lives outside it.
 */
export function TopMenu({ me, onSignOut, busy, signOut, children }: { me: Me; onSignOut: () => void; busy: boolean; signOut: boolean; children?: ReactNode }) {
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
        <RefreshButton />
        <ThemeChoices />
        {children}
        {signOut && (
          <BusyButton type="button" className="btn-ghost top-menu-item" busy={busy} onClick={onSignOut}>
            {icons.signOut}
            Sign out
          </BusyButton>
        )}
      </div>
      {help.sheet}
    </div>
  );
}

/** Under the bar while the device has no network: what still works, in one line. Nothing at all while online. */
export function OfflineBanner() {
  const online = useDeviceOnline();
  if (online) return null;
  return (
    <p role="status" className="offline-banner">
      {icons.info}
      <span>You are offline. You see what was loaded before, and the figures update once you are back online.</span>
    </p>
  );
}
