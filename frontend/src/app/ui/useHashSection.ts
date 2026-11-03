import { useEffect, useRef } from 'react';

// A link to one section of a page that loads its content first (Set-up's #reports from the Reports page, issue #783; one
// approval rule, #575): the section exists only once the page has loaded, so the browser's own jump to the hash finds
// nothing on arrival. Once loaded, the page scrolls to the section and puts the cursor on it, so a keyboard or screen
// reader user lands where the link pointed too.

const CONTROL = 'input:not([disabled]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), a[href]';

/** The id the address's hash names; a malformed escape (a cut or hand typed link) is taken as typed, never thrown. */
export function hashId(hash: string): string {
  const raw = hash.replace(/^#/, '');
  try {
    return decodeURIComponent(raw);
  } catch {
    return raw;
  }
}

/**
 * Scrolls to the element named by the address's hash once `loaded` is true, and focuses it: its first control (`control`,
 * Set-up's switch) or the section itself (`section`, for a page where a field in focus would open a phone's keyboard over
 * the row). Once per hash; a section that renders later is found on a later render, and a new hash is followed.
 */
export function useHashSection(loaded: boolean, focus: 'control' | 'section' = 'control'): void {
  const done = useRef<string | null>(null);
  const land = () => {
    const id = hashId(window.location.hash);
    if (!loaded || !id || done.current === id) return;
    const section = document.getElementById(id);
    if (!section) return;
    done.current = id;
    section.scrollIntoView?.({ block: 'start' });
    const control = focus === 'section' ? null : section.matches(CONTROL) ? section : section.querySelector<HTMLElement>(CONTROL);
    if (control) control.focus({ preventScroll: true });
    else {
      // A section with no control of its own (a heading, a row) takes the focus itself.
      if (!section.hasAttribute('tabindex')) section.setAttribute('tabindex', '-1');
      section.focus({ preventScroll: true });
    }
  };
  // Each render: the section may render after `loaded`, behind a second read.
  useEffect(land);
  useEffect(() => {
    window.addEventListener('hashchange', land);
    window.addEventListener('popstate', land);
    return () => {
      window.removeEventListener('hashchange', land);
      window.removeEventListener('popstate', land);
    };
  });
}
