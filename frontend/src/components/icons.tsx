import type { ReactNode } from 'react';

// Inline SVG icons for the design layer (#95, docs/ui/design-system.md): 24px line icons that
// take the text colour, drawn here so no image is fetched and the CSP needs nothing extra. Every
// icon is decorative (aria-hidden); the text next to it carries the meaning.

function Svg({ children }: { children: ReactNode }) {
  return (
    <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false">
      {children}
    </svg>
  );
}

export const icons = {
  home: (
    <Svg>
      <path d="M3 11l9-7 9 7" />
      <path d="M5 10v10h14V10" />
      <path d="M10 20v-6h4v6" />
    </Svg>
  ),
  sale: (
    <Svg>
      <path d="M3 4h2l2.4 11.2a2 2 0 0 0 2 1.6h7.7a2 2 0 0 0 2-1.5L21 8H6.2" />
      <circle cx="10" cy="20" r="1.2" />
      <circle cx="17" cy="20" r="1.2" />
    </Svg>
  ),
  creditSales: (
    <Svg>
      <rect x="3" y="6" width="18" height="12" rx="2" />
      <path d="M3 10h18" />
      <path d="M7 15h4" />
    </Svg>
  ),
  salesHistory: (
    <Svg>
      <path d="M6 3h12v18l-3-2-3 2-3-2-3 2z" />
      <path d="M9 8h6M9 12h6" />
    </Svg>
  ),
  restock: (
    <Svg>
      <path d="M1 6h13v10H1z" />
      <path d="M14 9h4l3 3v4h-7" />
      <circle cx="5.5" cy="17.5" r="1.8" />
      <circle cx="17.5" cy="17.5" r="1.8" />
    </Svg>
  ),
  transfer: (
    <Svg>
      <path d="M4 8h14" />
      <path d="M14 4l4 4-4 4" />
      <path d="M20 16H6" />
      <path d="M10 12l-4 4 4 4" />
    </Svg>
  ),
  transfers: (
    <Svg>
      <path d="M8 6h12" />
      <path d="M8 12h12" />
      <path d="M8 18h12" />
      <path d="M4 6h.01" />
      <path d="M4 12h.01" />
      <path d="M4 18h.01" />
    </Svg>
  ),
  usage: (
    <Svg>
      <path d="M12 3l9 16H3z" />
      <path d="M12 10v4" />
      <path d="M12 17h.01" />
    </Svg>
  ),
  stock: (
    <Svg>
      <path d="M21 8l-9-5-9 5 9 5 9-5z" />
      <path d="M3 8v8l9 5 9-5V8" />
      <path d="M12 13v8" />
    </Svg>
  ),
  stocktake: (
    <Svg>
      <path d="M9 4h6v3H9z" />
      <path d="M15 5h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h3" />
      <path d="M9 13l2 2 4-4" />
    </Svg>
  ),
  valuation: (
    <Svg>
      <rect x="2" y="6" width="20" height="12" rx="2" />
      <circle cx="12" cy="12" r="2.5" />
      <path d="M6 9v.01M18 15v.01" />
    </Svg>
  ),
  catalogue: (
    <Svg>
      <path d="M4 5h16v4H4z" />
      <path d="M5 9v10h14V9" />
      <path d="M9 13h6" />
    </Svg>
  ),
  profit: (
    <Svg>
      <path d="M3 17l6-6 4 4 8-8" />
      <path d="M15 7h6v6" />
    </Svg>
  ),
  user: (
    <Svg>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21a8 8 0 0 1 16 0" />
    </Svg>
  ),
  lock: (
    <Svg>
      <rect x="4" y="11" width="16" height="10" rx="2" />
      <path d="M8 11V7a4 4 0 0 1 8 0v4" />
    </Svg>
  ),
  calendar: (
    <Svg>
      <rect x="3" y="5" width="18" height="16" rx="2" />
      <path d="M3 10h18M8 3v4M16 3v4" />
    </Svg>
  ),
  shield: (
    <Svg>
      <path d="M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z" />
      <path d="M9 12l2 2 4-4" />
    </Svg>
  ),
  building: (
    <Svg>
      <path d="M4 21V5a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v16" />
      <path d="M15 10h4a1 1 0 0 1 1 1v10" />
      <path d="M8 8h3M8 12h3M8 16h3M2 21h20" />
    </Svg>
  ),
  arrow: (
    <Svg>
      <path d="M5 12h14M13 6l6 6-6 6" />
    </Svg>
  ),
} as const;

export type IconName = keyof typeof icons;
