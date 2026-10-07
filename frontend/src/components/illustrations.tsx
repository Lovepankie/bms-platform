import type { ReactNode } from 'react';

// Inline SVG illustrations (#106, docs/ui/design-system.md): original drawings in the Rincol look,
// drawn here so no image is fetched and the CSP needs nothing extra. They carry no colour of their
// own: every shape has a class from src/app/ui/illustrations.css that reads the tokens, so a tenant
// colour tints the accent family (accent, tint) and nothing else; success and danger stay status
// colours. Every illustration is decorative (aria-hidden); the words next to it carry the meaning.

function Art({ children, viewBox = '0 0 160 120', className = '' }: { children: ReactNode; viewBox?: string; className?: string }) {
  return (
    <svg className={`ill ${className}`.trim()} viewBox={viewBox} aria-hidden="true" focusable="false">
      {children}
    </svg>
  );
}

/** The soft tinted backdrop every illustration sits on. */
function Backdrop() {
  return (
    <>
      <circle className="ill-tint" cx="80" cy="62" r="50" />
      <circle className="ill-accent" cx="128" cy="24" r="4" />
      <circle className="ill-accent ill-faint" cx="30" cy="96" r="3" />
    </>
  );
}

export const illustrations = {
  /** A blank receipt: nothing sold yet. */
  noSales: (
    <Art>
      <Backdrop />
      <path className="ill-surface" d="M52 22h56v72l-7-5-7 5-7-5-7 5-7-5-7 5-7-5-7 5z" />
      <path className="ill-line" d="M62 36h36M62 48h24M62 60h30" />
      <path className="ill-accent-line" d="M62 74h36" />
      <circle className="ill-primary" cx="112" cy="84" r="13" />
      <path className="ill-on-primary-line" d="M112 78v12M106 84h12" />
    </Art>
  ),
  /** An open, empty box: no products yet. */
  noProducts: (
    <Art>
      <Backdrop />
      <path className="ill-surface" d="M40 52l40-16 40 16v38l-40 16-40-16z" />
      <path className="ill-line" d="M40 52l40 16 40-16M80 68v38" />
      <path className="ill-tint-strong" d="M40 52l-10-14 40-16 10 14zM120 52l10-14-40-16-10 14z" />
      <path className="ill-accent-line" d="M60 44l40 16v12" />
    </Art>
  ),
  /** A clipboard with a blank form: no applications yet. */
  noApplications: (
    <Art>
      <Backdrop />
      <rect className="ill-surface" x="50" y="22" width="60" height="78" rx="6" />
      <rect className="ill-primary" x="66" y="16" width="28" height="12" rx="4" />
      <circle className="ill-tint-strong" cx="64" cy="46" r="5" />
      <path className="ill-line" d="M74 46h24M62 62h36M62 74h28M62 86h20" />
      <path className="ill-accent-line ill-thick" d="M104 92l18-18 6 6-18 18-8 2z" />
    </Art>
  ),
  /** An empty tray with a tick: nothing waiting. */
  nothingWaiting: (
    <Art>
      <Backdrop />
      <path className="ill-surface" d="M34 66l14-30h64l14 30v26a4 4 0 0 1-4 4H38a4 4 0 0 1-4-4z" />
      <path className="ill-line" d="M34 66h28l6 10h24l6-10h28" />
      <circle className="ill-primary" cx="80" cy="44" r="12" />
      <path className="ill-on-primary-line" d="M74 44l4 4 8-8" />
    </Art>
  ),
  /** A tick in a ring: done. */
  success: (
    <Art>
      <circle className="ill-success-tint" cx="80" cy="60" r="50" />
      <circle className="ill-surface" cx="80" cy="60" r="32" />
      <path className="ill-success-line ill-thick" d="M66 61l9 9 19-20" />
      <circle className="ill-accent" cx="126" cy="26" r="4" />
      <circle className="ill-accent ill-faint" cx="34" cy="94" r="3" />
    </Art>
  ),
  /** A warning sign: something went wrong. */
  error: (
    <Art>
      <circle className="ill-danger-tint" cx="80" cy="60" r="50" />
      <path className="ill-surface" d="M80 26l36 62H44z" />
      <path className="ill-danger-line ill-thick" d="M80 50v18M80 78v1" />
    </Art>
  ),
  /** The landing hero: a phone showing the day's figures, with a shield for trust. */
  hero: (
    <Art viewBox="0 0 240 160" className="ill-hero">
      <circle className="ill-tint" cx="120" cy="84" r="70" />
      <circle className="ill-accent" cx="196" cy="30" r="5" />
      <circle className="ill-accent ill-faint" cx="40" cy="128" r="4" />
      <rect className="ill-surface" x="84" y="14" width="72" height="136" rx="12" />
      <rect className="ill-primary" x="92" y="28" width="56" height="24" rx="5" />
      <path className="ill-on-primary-line" d="M98 40h20" />
      <rect className="ill-tint-strong" x="96" y="88" width="8" height="18" rx="2" />
      <rect className="ill-tint-strong" x="110" y="78" width="8" height="28" rx="2" />
      <rect className="ill-accent" x="124" y="68" width="8" height="38" rx="2" />
      <rect className="ill-primary" x="138" y="60" width="8" height="46" rx="2" />
      <path className="ill-line" d="M96 118h48M96 128h32" />
      <rect className="ill-surface ill-raised" x="30" y="56" width="58" height="40" rx="8" />
      <path className="ill-accent-line" d="M40 70h28" />
      <path className="ill-line" d="M40 82h38" />
      <path className="ill-surface ill-raised" d="M178 76l22 8v16c0 14-10 22-22 26-12-4-22-12-22-26V84z" />
      <path className="ill-success-line ill-thick" d="M169 101l6 6 12-13" />
    </Art>
  ),
  /** The sign-up hero: a shop front with an awning in the accent. */
  shopfront: (
    <Art viewBox="0 0 240 160" className="ill-hero">
      <circle className="ill-tint" cx="120" cy="84" r="70" />
      <circle className="ill-accent" cx="198" cy="34" r="5" />
      <rect className="ill-surface" x="58" y="58" width="124" height="88" rx="6" />
      <path className="ill-primary" d="M50 40h140l-6 26H56z" />
      <path className="ill-accent" d="M76 40h22l-3 26H74zM120 40h22l1 26h-22z" />
      <path className="ill-accent" d="M164 40h22l-2 26h-21z" />
      <rect className="ill-tint-strong" x="72" y="80" width="44" height="34" rx="4" />
      <rect className="ill-surface" x="132" y="86" width="34" height="60" rx="4" />
      <circle className="ill-primary" cx="158" cy="118" r="3" />
      <path className="ill-line" d="M80 92h28M80 102h18" />
    </Art>
  ),
  /** A padlock on a card: the sign-in family. */
  secure: (
    <Art>
      <Backdrop />
      <rect className="ill-surface" x="44" y="36" width="72" height="56" rx="8" />
      <path className="ill-accent-line ill-thick" d="M68 52v-8a12 12 0 0 1 24 0v8" />
      <rect className="ill-primary" x="62" y="52" width="36" height="28" rx="5" />
      <path className="ill-on-primary-line" d="M80 62v8" />
    </Art>
  ),
} as const;

export type IllustrationName = keyof typeof illustrations;
