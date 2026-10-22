import { Link, useRouterState } from '@tanstack/react-router';
import { type KeyboardEvent, type ReactNode, type TouchEvent, createContext, useContext, useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore } from 'react';
import { createPortal } from 'react-dom';
import { BusyAnnouncer, BusyButton } from '../app/ui/BusyButton';
import { useTour } from '../tour/host';
import { icons } from './icons';

// The side menu (issue #455, docs/ui/design-system.md "Navigation"): one list of areas, each with an icon, on every screen size.
// From 720px it is a sidebar at the left of the page (SideNav) that can fold to its icons; below 720px a menu button at the top left
// of the bar opens the same list in a drawer from the left (SideMenu). Both draw MenuLinks, so the items, groups and the current
// one (aria-current) cannot drift apart. The drawer closes on the backdrop, Escape, a left swipe or a chosen link, keeps focus
// inside while open and gives it back to the menu button; any change of page closes it. Between 720 and 1023px the sidebar starts
// folded, from 1024px unfolded, until the person chooses. A tour step that points at one of the data-tour anchors opens the drawer,
// or unfolds the folded sidebar, for as long as the step is shown, without taking focus from the tour card. When the links name two or
// more sections (issue #471, a business with both Shop and Lending), each section's links sit under a heading that folds them, the
// folded ones kept per person on the device; the links of no section stay above and below them. With one section the list is flat.
// A folded section shows its links while the page is one of them, or while a tour step points into it.

const PHONE = '(max-width: 719px)';
const NARROW = '(max-width: 1023px)';

function useMedia(media: string): boolean {
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
  /** Links shown as a group under this one in the side menu, such as the retail shortcuts. */
  group?: ReactNode;
  /** The paths of the group's links: on one of them this item is drawn as the section and the group's link as the current one. */
  groupPaths?: string[];
  /** The id of the MenuSection it belongs to; none for the links every business shares. */
  section?: string;
}

/** A headed, foldable part of the menu (issue #471), shown only when the links name two or more sections. */
export interface MenuSection {
  id: string;
  label: string;
  icon: ReactNode;
  /** The data-tour ids inside its links' groups: a tour step on one of them unfolds the section. */
  tours?: string[];
}

const FOCUSABLE = 'a[href], button:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';
const SWIPE = 50;

// True inside the folded sidebar, so a group's links fold with the items above them.
const Folded = createContext(false);

/** True inside the folded sidebar: what the sidebar shows above its links keeps only its picture, as the links keep their icons. */
export function useFolded(): boolean {
  return useContext(Folded);
}

const under = (pathname: string, path: string) => pathname === path || pathname.startsWith(`${path}/`);

/**
 * One link of the side menu: its icon and label, the label kept as the accessible name and shown as a tooltip when folded. With
 * `groupPaths`, on a page of its group it is drawn as the section (.side-menu-section) and the group's link is the current one.
 */
export function MenuItemLink({ to, label, icon, tour, exact, groupPaths }: { to: string; label: string; icon: ReactNode; tour?: string; exact?: boolean; groupPaths?: string[] }) {
  const folded = useContext(Folded);
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const section = !!groupPaths?.some((path) => under(pathname, path));
  return (
    <Link
      to={to as never}
      activeOptions={exact || section ? { exact: true } : undefined}
      className={section ? 'side-menu-section' : undefined}
      data-tour={tour}
      title={folded ? label : undefined}
    >
      {icon}
      <span className={folded ? 'visually-hidden' : undefined}>{label}</span>
    </Link>
  );
}

function MenuItems({ links }: { links: MenuLink[] }) {
  return (
    <ul>
      {links.map((l) => (
        <li key={l.to}>
          <MenuItemLink to={l.to} label={l.label} icon={l.icon} tour={l.tour} exact={l.exact} groupPaths={l.groupPaths} />
          {l.group}
        </li>
      ))}
    </ul>
  );
}

const sectionsKey = (person: string) => `bms.menu.sections.${person}`;

// The ids of the sections the person folded, kept on the device per person.
function readFoldedSections(person: string): string[] {
  try {
    const kept: unknown = JSON.parse(window.localStorage.getItem(sectionsKey(person)) ?? '[]');
    return Array.isArray(kept) ? kept.filter((id): id is string => typeof id === 'string') : [];
  } catch {
    return [];
  }
}

function keepFoldedSections(person: string, ids: string[]) {
  try {
    window.localStorage.setItem(sectionsKey(person), JSON.stringify(ids));
  } catch {
    // Storage blocked: the sections stay as chosen until the page is reloaded.
  }
}

/**
 * One section's heading, a button that folds its links, and the links. Unfolded while the page is one of its links or their groups,
 * so the current link is never hidden, and while a tour step points into it.
 */
function MenuSectionBlock({ section, links, folded, onFold }: { section: MenuSection; links: MenuLink[]; folded: boolean; onFold: (fold: boolean) => void }) {
  const iconsOnly = useContext(Folded);
  const list = useId();
  const { target } = useTour();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const anchors = [...links.map((l) => l.tour), ...(section.tours ?? [])].filter(Boolean);
  const here = links.some((l) => (l.exact ? pathname === l.to : under(pathname, l.to)) || !!l.groupPaths?.some((path) => under(pathname, path)));
  const forced = here || (target !== undefined && anchors.includes(target));
  // As in SideNav, a press while the section is held open wins for that tour step and page. Another step, the tour ending or
  // another page forgets the press, so the section is held open again.
  const [pressed, setPressed] = useState(false);
  const [pressAt, setPressAt] = useState({ target, pathname });
  if (pressAt.target !== target || pressAt.pathname !== pathname) {
    setPressAt({ target, pathname });
    setPressed(false);
  }
  const shut = folded && (!forced || pressed);

  // From what the person sees: a press on a held-open section folds it, and the choice is kept.
  function toggle() {
    onFold(!shut);
    if (forced) setPressed(true);
  }

  return (
    <div className="side-menu-block">
      <button type="button" className="side-menu-heading" aria-expanded={!shut} aria-controls={list} title={iconsOnly ? section.label : undefined} onClick={toggle}>
        {section.icon}
        <span className={iconsOnly ? 'visually-hidden' : undefined}>{section.label}</span>
        {!iconsOnly && <span className="side-menu-caret" aria-hidden="true" />}
      </button>
      <div id={list} hidden={shut}>
        <MenuItems links={links} />
      </div>
    </div>
  );
}

/**
 * The list of areas both the sidebar and the drawer draw, the groups under their item. With links in two or more `sections`, the
 * shared links before the first sectioned one come first, then each section under its heading, then the other shared links.
 */
function MenuLinks({ label, tour, links, folded = false, sections = [], person = '' }: { label: string; tour?: string; links: MenuLink[]; folded?: boolean; sections?: MenuSection[]; person?: string }) {
  const [kept, setKept] = useState(() => ({ person, ids: readFoldedSections(person) }));
  // Another person signed in on the device: their own folded sections, read again.
  const shut = kept.person === person ? kept.ids : readFoldedSections(person);
  if (kept.person !== person) setKept({ person, ids: shut });
  const used = sections.filter((s) => links.some((l) => l.section === s.id));
  const grouped = used.length > 1;
  const first = links.findIndex((l) => l.section !== undefined);

  function fold(id: string, folding: boolean) {
    const next = folding ? [...shut.filter((s) => s !== id), id] : shut.filter((s) => s !== id);
    keepFoldedSections(person, next);
    setKept({ person, ids: next });
  }

  return (
    <Folded.Provider value={folded}>
      <nav className="side-menu-links" aria-label={label} data-tour={tour}>
        {grouped ? (
          <>
            <MenuItems links={links.slice(0, first)} />
            {used.map((s) => (
              <MenuSectionBlock key={s.id} section={s} links={links.filter((l) => l.section === s.id)} folded={shut.includes(s.id)} onFold={(folding) => fold(s.id, folding)} />
            ))}
            <MenuItems links={links.slice(first).filter((l) => l.section === undefined)} />
          </>
        ) : (
          <MenuItems links={links} />
        )}
      </nav>
    </Folded.Provider>
  );
}

const FOLDED_KEY = 'bms.sidebar';

// The person's own choice, `folded` or `open`; null until they choose, and the width decides.
function readFolded(): boolean | null {
  try {
    const kept = window.localStorage.getItem(FOLDED_KEY);
    return kept === 'folded' ? true : kept === 'open' ? false : null;
  } catch {
    return null;
  }
}

function keepFolded(folded: boolean) {
  try {
    window.localStorage.setItem(FOLDED_KEY, folded ? 'folded' : 'open');
  } catch {
    // Storage blocked (a private window): the choice lasts until the page is reloaded.
  }
}

/**
 * The sidebar from 720px: the same list as the drawer, about 240px wide, or 64px of icons when folded. It starts folded below
 * 1024px and unfolded from there; once the person folds or unfolds it, that choice is kept on the device. `head` is shown above
 * the links, as in the drawer (the shop in use, issue #461).
 */
export function SideNav({ label, tour, links, tours = [], sections, person, head }: { label: string; tour?: string; links: MenuLink[]; tours?: string[]; sections?: MenuSection[]; person?: string; head?: ReactNode }) {
  const [choice, setChoice] = useState(readFolded);
  const narrow = useMedia(NARROW);
  const folded = choice ?? narrow;
  const root = useRef<HTMLDivElement>(null);
  const { target } = useTour();
  const anchors = [tour, ...links.map((l) => l.tour), ...tours].filter(Boolean);
  const forTour = target !== undefined && anchors.includes(target);
  // A press during a tour step wins for that step. A step that points somewhere else, or the tour ending, forgets the press, so
  // the next step, going back to this one, or the next run of the tour unfolds it again.
  const [pressed, setPressed] = useState(false);
  const [pressTarget, setPressTarget] = useState(target);
  if (pressTarget !== target) {
    setPressTarget(target);
    setPressed(false);
  }
  // Unfolded while a tour step points into it, so the step can show the label it talks about, unless the person pressed.
  const shownFolded = folded && (!forTour || pressed);

  // From what the person sees: unfolded for a tour step, a press folds it, and the choice is kept.
  function toggle() {
    keepFolded(!shownFolded);
    setChoice(!shownFolded);
    if (forTour) setPressed(true);
  }

  // Narrowed to a phone while the focus is in the sidebar: it goes to the menu button that now holds the list. A layout effect, so
  // the sidebar is still in the page when its clean-up looks for the focus.
  useLayoutEffect(() => {
    const node = root.current;
    return () => {
      if (node?.contains(document.activeElement)) window.setTimeout(() => document.querySelector<HTMLElement>('.menu-button')?.focus(), 0);
    };
  }, []);

  return (
    <div ref={root} className={shownFolded ? 'side-nav side-nav-folded' : 'side-nav'}>
      <button
        type="button"
        className="btn-ghost side-nav-toggle"
        aria-label="Menu labels"
        aria-expanded={!shownFolded}
        title={shownFolded ? 'Show menu labels' : 'Hide menu labels'}
        onClick={toggle}
      >
        {icons.menu}
      </button>
      {head && <Folded.Provider value={shownFolded}>{head}</Folded.Provider>}
      <MenuLinks label={label} tour={tour} links={links} folded={shownFolded} sections={sections} person={person} />
    </div>
  );
}

/**
 * The menu button and its drawer. `label` names the list of links, `tour` is its data-tour id, `tours` the ids inside the groups,
 * `sections` the headed parts, `person` whose folded sections are kept and `busy` holds Sign out while signing out runs.
 * `head` what is shown above the links (the shop in use, issue #461).
 */
export function SideMenu({ label, tour, links, name, onSignOut, busy = false, tours = [], sections, person, head }: { label: string; tour?: string; links: MenuLink[]; name?: string; onSignOut: () => void; busy?: boolean; tours?: string[]; sections?: MenuSection[]; person?: string; head?: ReactNode }) {
  const [open, setOpen] = useState(false);
  const heading = useId();
  const button = useRef<HTMLButtonElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const swipe = useRef<{ x: number; y: number } | null>(null);
  const { running, target } = useTour();
  const anchors = [tour, ...links.map((l) => l.tour), ...tours].filter(Boolean);
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
    // The links of a folded section are in the page but hidden: they take no part in the cycle.
    const controls = Array.from(panel.current?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? []).filter((el) => !el.closest('[hidden]'));
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
        <div className="side-menu-head">
          <h2 id={heading} className="side-menu-title">Menu</h2>
          <button type="button" className="btn-ghost menu-button" aria-label="Close menu" onClick={close}>
            {icons.close}
          </button>
        </div>
        {head}
        <MenuLinks label={label} tour={tour} links={links} sections={sections} person={person} />
        <div className="side-menu-foot">
          <span className="staff-user">
            <span className="avatar">{icons.user}</span>
            <span className="staff-user-name">{name}</span>
          </span>
          <BusyButton type="button" className="btn-ghost" busy={busy} onClick={onSignOut}>
            {icons.signOut}
            <span>Sign out</span>
          </BusyButton>
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
