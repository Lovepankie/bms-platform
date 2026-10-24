import { useNavigate } from '@tanstack/react-router';
import { useEffect, useMemo, useState } from 'react';
import type { Me } from '../../api/client';
import { WORDS } from '../../app/glossary';
import { ItemCombobox } from '../../app/ui/ItemCombobox';
import { Sheet } from '../../app/ui/Sheet';
import { useLiveSearch } from '../../app/ui/useLiveSearch';
import { icons } from '../../components/icons';
import { useIsPhone } from '../../components/side-menu';
import { featuresFor, findFeatures, type Feature } from './feature-search-state';
import { useSells } from './retail/nav';

// Feature search (issue #551): a Search button in the staff bar, and one at the top of the More page on a phone, opens a sheet
// (the kit's Sheet: from the bottom on a phone, a centred dialog on a laptop) holding the one picker of #534. Screens show only
// while something is typed; Up and Down move, Enter or a tap opens the screen, and Escape closes the sheet. On a laptop / or
// Ctrl+K (Cmd+K on a Mac) opens it from anywhere; / is left alone while a field has the cursor. What it lists is
// feature-search-state.ts: the menu's own sources, so a screen the session cannot open never shows.

const SCREENS_NOUN = { one: 'screen', many: 'screens' };

// The fields where / is a character being typed, not the shortcut.
const FIELDS = 'input, textarea, select, [contenteditable]:not([contenteditable="false"])';

/** True for / outside a field, and for Ctrl+K or Cmd+K anywhere; never while another dialog is open. */
export function isSearchShortcut(e: Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'altKey' | 'target' | 'defaultPrevented' | 'isComposing'>): boolean {
  // A key that is part of composing a word (an input method) is never the shortcut.
  if (e.defaultPrevented || e.altKey || e.isComposing) return false;
  if (document.querySelector('[role="dialog"], dialog[open]')) return false;
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') return true;
  return e.key === '/' && !e.ctrlKey && !e.metaKey && !(e.target instanceof Element && e.target.closest(FIELDS));
}

function FindScreen({ me, onGo, onClose }: { me: Me; onGo: (feature: Feature) => void; onClose: () => void }) {
  const sells = useSells(me);
  const features = useMemo(() => featuresFor(me, { sells }), [me, sells]);
  const search = useLiveSearch();
  const results = useMemo(() => (search.term === '' ? undefined : findFeatures(features, search.term)), [features, search.term]);
  return (
    <div
      className="find-screen"
      // One Escape closes the search, whatever the list is doing (the picker's own Escape first closes its list).
      onKeyDownCapture={(e) => {
        if (e.key !== 'Escape') return;
        e.preventDefault();
        e.stopPropagation();
        onClose();
      }}
    >
      <ItemCombobox
        id="feature-search"
        label="What do you want to do?"
        search={search}
        results={results}
        optionKey={(f) => `${f.to} ${f.label}`}
        noun={SCREENS_NOUN}
        emptyText={(term) => `Nothing matches "${term}". Try another word, like sell, buy or owe.`}
        hint={`Type a word, like sell, ${WORDS.buyStock.toLowerCase()}, expenses or owe.`}
        onPick={onGo}
        renderOption={(f) => (
          <span className="cb-text">
            {f.label}
            {f.hint && <span className="cb-meta">{f.hint}</span>}
          </span>
        )}
      />
    </div>
  );
}

/**
 * The Search button and its sheet. In the staff bar (`place` bar) it also listens for the keyboard shortcut and shows its word
 * only from 720px, the icon alone on a phone; on the More page it is a full width button with its word.
 */
export function FeatureSearch({ me, place = 'bar' }: { me: Me; place?: 'bar' | 'more' }) {
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const phone = useIsPhone();
  const bar = place === 'bar';

  useEffect(() => {
    if (!bar) return;
    const onKey = (e: KeyboardEvent) => {
      if (!isSearchShortcut(e)) return;
      e.preventDefault();
      setOpen(true);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [bar]);

  const go = (feature: Feature) => {
    setOpen(false);
    void navigate({ to: feature.to as never });
  };

  return (
    <>
      <button
        type="button"
        className={bar ? 'btn-ghost btn-sm btn-icon find-open' : 'btn-block btn-icon find-open find-open-more'}
        aria-haspopup="dialog"
        aria-keyshortcuts={bar ? '/ Control+K Meta+K' : undefined}
        data-tour={bar ? 'feature-search' : 'more-search'}
        onClick={() => setOpen(true)}
      >
        {icons.search}
        <span className={bar && phone ? 'visually-hidden' : undefined}>{bar ? 'Search' : 'Search for what you want to do'}</span>
      </button>
      <Sheet open={open} onClose={() => setOpen(false)} title="Find a screen">
        <FindScreen me={me} onGo={go} onClose={() => setOpen(false)} />
      </Sheet>
    </>
  );
}
