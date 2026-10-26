import { Link, useRouterState } from '@tanstack/react-router';
import { type KeyboardEvent, type ReactNode, type TouchEvent, createContext, useContext, useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import { BusyAnnouncer, BusyButton } from '../app/ui/BusyButton';
import { useTour } from '../tour/host';
import { icons } from './icons';

// The side menu (issue #455, docs/ui/design-system.md "Navigation"): one list of areas, each with an icon, on every screen size.
// From 720px it is a fixed sidebar at the left of the page (SideNav); below 720px a menu button at the top left of the bar opens
// the same list in a drawer from the left (SideMenu). Both draw MenuLinks, so the items, groups and the current one (aria-current)
// cannot drift apart. The drawer closes on the backdrop, Escape, a left swipe or a chosen link, keeps focus inside while open and
// gives it back to the menu button; any change of page closes it. A tour step that points at one of the data-tour anchors opens
// the drawer for as long as the step is shown, without taking focus from the tour card. Since issue #564 (ADR-086) the links come
// in groups, each under a small muted heading (Today, Stock, Money, Business), never folded, and the sidebar no longer folds to
// its icons: the menu is the same list on a phone and a laptop. `head` is drawn above the list and `foot` below it.

const PHONE = '(max-width: 719px)';

/** True while `media` matches; false when rendered without a window. */
export function useMedia(media: string): boolean {
  return useSyncExternalStore(
    (notify) => {
      if (typeof window === 'undefined' || !window.matchMedia) return () => undefined;
      const query = window.matchMedia(media);
      query.addEventListener('change', notify);
      return () => query.removeEventListener('change', notify);
    },
    () => typeof window !== 'undefined' && !!window.matchMedia && window.matchMedia(media).matches,
    () => false,
  );
}

/** True below 720px, the design system's phone width. False when rendered without a window. */
export function useIsPhone(): boolean {
  return useMedia(PHONE);
}

export interface MenuLink {
  to: string;
  label: string;
  icon: ReactNode;
  /** The data-tour id a tour step lights up. */
  tour?: string;
  /** Marked current only on its own page, not on the pages under it. */
  exact?: boolean;
  /** A muted figure at the right of the row, such as an open count or "5 low"; nothing when empty. */
  count?: string;
  /** The id of the MenuSection (the module) it belongs to; none for the links every business shares. The More page reads it. */
  section?: string;
  /** Drawn after the label, such as the New pill of a new feature (issue #603). */
  badge?: ReactNode;
}

/** A module of the business (Shop, Lending): the Home cards and the More page head their parts with it (issue #471). */
export interface MenuSection {
  id: string;
  label: string;
  icon: ReactNode;
}

/** One group of the menu (issue #564): a small muted heading, none for the first group (Home), and its links. */
export interface MenuGroup {
  id: string;
  label?: string;
  /** The data-tour id of the group as a whole. */
  tour?: string;
  links: MenuLink[];
}

const FOCUSABLE = 'a[href], button:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';
const SWIPE = 50;

// Closes the drawer from inside it (a footer button that opens a sheet); does nothing in the sidebar.
const CloseMenu = createContext<() => void>(() => undefined);

/** Closes the phone drawer this is drawn in; in the sidebar it does nothing. */
export function useCloseMenu(): () => void {
  return useContext(CloseMenu);
}

/** One link of the side menu: its icon, its label and its count, read out together as "Orders, 3". */
export function MenuItemLink({ to, label, icon, tour, exact, count, badge }: Omit<MenuLink, 'section'>) {
  return (
    <Link to={to as never} activeOptions={exact ? { exact: true } : undefined} data-tour={tour}>
      {icon}
      <span className="side-menu-label">{label}</span>
      {badge}
      {count && (
        <span className="side-menu-count">
          <span className="visually-hidden">, </span>
          {count}
        </span>
      )}
    </Link>
  );
}

function MenuItems({ links }: { links: MenuLink[] }) {
  return (
    <ul>
      {links.map((l) => (
        <li key={l.to}>
          <MenuItemLink to={l.to} label={l.label} icon={l.icon} tour={l.tour} exact={l.exact} count={l.count} badge={l.badge} />
        </li>
      ))}
    </ul>
  );
}

/** The groups both the sidebar and the drawer draw, each heading naming its list; a group with no links is left out. */
function MenuLinks({ label, tour, groups }: { label: string; tour?: string; groups: MenuGroup[] }) {
  const prefix = useId();
  return (
    <nav className="side-menu-links" aria-label={label} data-tour={tour}>
      {groups
        .filter((g) => g.links.length > 0)
        .map((g) => (
          <div key={g.id} className="side-menu-group" role={g.label ? 'group' : undefined} aria-labelledby={g.label ? `${prefix}${g.id}` : undefined} data-tour={g.tour}>
            {g.label && (
              <p id={`${prefix}${g.id}`} className="side-menu-heading">
                {g.label}
              </p>
            )}
            <MenuItems links={g.links} />
          </div>
        ))}
    </nav>
  );
}

const groupsOf = (links: MenuLink[] = [], groups?: MenuGroup[]): MenuGroup[] => groups ?? [{ id: 'all', links }];

/**
 * The sidebar from 720px: the same list as the drawer, about 264px wide, fixed at the left while the page scrolls. `head` is shown
 * above the links and `foot` below them, as in the drawer.
 */
export function SideNav({ label, tour, links, groups, head, foot }: { label: string; tour?: string; links?: MenuLink[]; groups?: MenuGroup[]; head?: ReactNode; foot?: ReactNode }) {
  const root = useRef<HTMLDivElement>(null);

  // Narrowed to a phone while the focus is in the sidebar: it goes to the menu button that now holds the list. A layout effect, so
  // the sidebar is still in the page when its clean-up looks for the focus.
  useLayoutEffect(() => {
    const node = root.current;
    return () => {
      if (node?.contains(document.activeElement)) window.setTimeout(() => document.querySelector<HTMLElement>('.menu-button')?.focus(), 0);
    };
  }, []);

  return (
    <div ref={root} className="side-nav">
      {head}
      <MenuLinks label={label} tour={tour} groups={groupsOf(links, groups)} />
      {foot && <div className="side-menu-foot">{foot}</div>}
    </div>
  );
}

/**
 * The menu button and its drawer. `label` names the list of links, `tour` is its data-tour id, `tours` the ids drawn in `head` or
 * `foot`, and `busy` holds Sign out while signing out runs. `head` is what is shown above the links (the business, issue #564),
 * `foot` what is shown below them; without a `foot` the drawer ends with `name` and Sign out.
 */
export function SideMenu({
  label,
  tour,
  links,
  groups,
  name,
  onSignOut,
  busy = false,
  tours = [],
  head,
  foot,
}: {
  label: string;
  tour?: string;
  links?: MenuLink[];
  groups?: MenuGroup[];
  name?: string;
  onSignOut: () => void;
  busy?: boolean;
  tours?: string[];
  head?: ReactNode;
  foot?: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const heading = useId();
  const button = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const swipe = useRef<{ x: number; y: number } | null>(null);
  const { running, target } = useTour();
  const list = groupsOf(links, groups);
  const anchors = [tour, ...list.flatMap((g) => [g.tour, ...g.links.map((l) => l.tour)]), ...tours].filter(Boolean);
  // Opened by a tour step: shown at once, with no focus moved or kept, so the tour card keeps the keyboard.
  const shownForTour = target !== undefined && anchors.includes(target);
  const shown = open || shownForTour;
  const pathname = useRouterState({ select: (state) => state.location.pathname });

  function close() {
    setOpen(false);
    window.setTimeout(() => button.current?.focus(), 0);
  }

  // Any change of page closes the menu: a chosen link, the back button, a tour or a redirect.
  // Closed by a new page, the page behind keeps the new page's scroll instead of getting the old position back.
  const restoreScroll = useRef(true);
  const shownAt = useRef(pathname);
  useEffect(() => {
    if (shownAt.current === pathname) return;
    shownAt.current = pathname;
    if (!open) return;
    const hadFocus = panel.current?.contains(document.activeElement);
    restoreScroll.current = false;
    setOpen(false);
    if (hadFocus) window.setTimeout(() => button.current?.focus(), 0);
  }, [pathname, open]);

  // A tour that starts while the menu is open takes over; the menu waits for the steps that point into it.
  useEffect(() => {
    if (running) setOpen(false);
  }, [running]);

  useEffect(() => {
    if (!open) return;
    const node = panel.current;
    node?.querySelector<HTMLElement>(FOCUSABLE)?.focus();
    // The page behind stays where it was: iOS Safari scrolls a body with overflow hidden, so the body is fixed at the scroll position.
    const scrollY = window.scrollY;
    document.body.style.setProperty('--side-menu-scroll', `-${scrollY}px`);
    document.body.classList.add('side-menu-open');
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        close();
      }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      // Widened past the phone width while open (the drawer is gone): the focus goes to the sidebar's first link.
      const unmounted = !node?.isConnected;
      document.body.classList.remove('side-menu-open');
      document.body.style.removeProperty('--side-menu-scroll');
      if (restoreScroll.current) window.scrollTo(0, scrollY);
      restoreScroll.current = true;
      document.removeEventListener('keydown', onKey);
      if (unmounted) window.setTimeout(() => document.querySelector<HTMLElement>('.side-nav .side-menu-links a')?.focus(), 0);
    };
  }, [open]);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (!open || event.key !== 'Tab') return;
    const controls = Array.from(panel.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []);
    if (controls.length === 0) return;
    const at = controls.indexOf(document.activeElement as HTMLElement);
    const next = event.shiftKey ? (at <= 0 ? controls.length - 1 : at - 1) : at === controls.length - 1 ? 0 : at + 1;
    event.preventDefault();
    controls[next]?.focus();
  }

  function onTouchEnd(event: TouchEvent<HTMLDivElement>) {
    const from = swipe.current;
    const touch = event.changedTouches[0];
    swipe.current = null;
    if (!open || !from || !touch) return;
    const dx = touch.clientX - from.x;
    if (dx < -SWIPE && Math.abs(dx) > Math.abs(touch.clientY - from.y)) close();
  }

  const drawer = (
    <div className={shownForTour && !open ? 'side-menu side-menu-instant' : 'side-menu'} hidden={!shown}>
      <div className="side-menu-backdrop" onClick={open ? close : undefined} />
      <div
        ref={panel}
        className="side-menu-panel"
        role="dialog"
        aria-modal={open ? 'true' : undefined}
        aria-labelledby={heading}
        onKeyDown={onKeyDown}
        onClick={(event) => {
          const link = event.target instanceof Element ? event.target.closest('a') : null;
          if (!open || !link) return;
          // A link to another page opens it here, and it keeps its own scroll, not the old page's position. The same page, or a
          // link opened elsewhere (Ctrl, Cmd, Shift or a middle click), leaves this page, so its position comes back.
          const elsewhere = event.ctrlKey || event.metaKey || event.shiftKey || event.altKey || event.button !== 0;
          if (link.pathname !== pathname && !elsewhere) restoreScroll.current = false;
          close();
        }}
        onTouchStart={(event) => {
          const touch = event.touches[0];
          swipe.current = touch ? { x: touch.clientX, y: touch.clientY } : null;
        }}
        onTouchEnd={onTouchEnd}
      >
        {head ? (
          <>
            <h2 id={heading} className="visually-hidden">
              Menu
            </h2>
            <button type="button" className="btn-ghost menu-button side-menu-close" aria-label="Close menu" onClick={close}>
              {icons.close}
            </button>
            {head}
          </>
        ) : (
          <div className="side-menu-head">
            <h2 id={heading} className="side-menu-title">Menu</h2>
            <button type="button" className="btn-ghost menu-button" aria-label="Close menu" onClick={close}>
              {icons.close}
            </button>
          </div>
        )}
        <MenuLinks label={label} tour={tour} groups={list} />
        <div className="side-menu-foot">
          {foot ? (
            <CloseMenu.Provider value={close}>{foot}</CloseMenu.Provider>
          ) : (
            <>
              <span className="staff-user">
                <span className="avatar">{icons.user}</span>
                <span className="staff-user-name">{name}</span>
              </span>
              <BusyButton type="button" className="btn-ghost" busy={busy} onClick={onSignOut}>
                {icons.signOut}
                <span>Sign out</span>
              </BusyButton>
            </>
          )}
          {/* The drawer the person opened is aria-modal, which hides the shell's announcer, so it carries its own while open and
              the shell's steps aside, so there is one Working at a time. */}
          {open && <BusyAnnouncer modal />}
        </div>
      </div>
    </div>
  );

  return (
    <>
      <button ref={button} type="button" className="btn-ghost menu-button" aria-label="Menu" aria-haspopup="dialog" aria-expanded={shown} onClick={() => setOpen(true)}>
        {icons.menu}
      </button>
      {/* In the body, because the staff bar clips what is drawn inside it (its full-bleed band is a clip-path). */}
      {createPortal(drawer, document.body)}
    </>
  );
}
