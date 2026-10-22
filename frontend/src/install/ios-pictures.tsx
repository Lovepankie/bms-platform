import type { ReactNode } from 'react';

// The pictures beside the iPhone install steps (issue #448): our own simple drawings of the part of Safari each step is about,
// not screenshots and not Apple artwork. Like src/components/illustrations.tsx they carry no colour of their own: the classes in
// src/app/ui/illustrations.css paint them from the tokens, so they follow the light and dark themes and the business's colour.
// Each one is an image with a short label that describes the drawing, not the step, so a screen reader does not read a step twice;
// the words of the step stay beside it. The 100 unit viewBox is drawn at 104px, so the 11 unit labels read at about 11px.

function Picture({ label, height = 76, children }: { label: string; height?: number; children: ReactNode }) {
  return (
    <svg className="ill ios-step-pic" viewBox={`0 0 100 ${height}`} role="img" aria-label={label}>
      {children}
    </svg>
  );
}

/** Safari in a normal tab: the address bar at the foot of a light page, no Private label. */
export const safariTab = (
  <Picture label="Picture: Safari with a normal tab open.">
    <rect className="ill-surface" x="16" y="2" width="68" height="72" rx="10" />
    <path className="ill-line" d="M26 16h48M26 26h34M26 36h40" />
    <rect className="ill-tint" x="22" y="48" width="56" height="18" rx="9" />
    <path className="ill-accent-line" d="M30 57h3" />
    <text className="ill-text" x="39" y="61">
      bms
    </text>
  </Picture>
);

/** The bottom toolbar, Share (the square with an arrow) ringed, the address bar above and the ⋯ button beside it. */
export const shareButton = (
  <Picture label="Picture: Safari's Share button, ringed, in the bottom toolbar. On an iPad, Share is at the top right.">
    <rect className="ill-surface" x="2" y="6" width="96" height="64" rx="10" />
    <rect className="ill-tint" x="8" y="12" width="66" height="18" rx="9" />
    <text className="ill-text" x="16" y="25">
      bms
    </text>
    <text className="ill-text ill-text-strong" x="80" y="25">
      ⋯
    </text>
    <path className="ill-line ill-thin" d="M14 47l-4 5 4 5M26 47l4 5-4 5M76 46h10v10H76z" />
    <circle className="ill-accent-ring" cx="50" cy="52" r="11" />
    <path className="ill-accent-line ill-thin" d="M47 49h-3v9h12v-9h-3M50 54V43M46.5 46.5l3.5-3.5 3.5 3.5" />
  </Picture>
);

/**
 * The share sheet's Add to Home Screen row picked out, and View More in a frame of its own under it, because on some iPhones
 * the row is only found behind View More, so the two are not always seen together.
 */
export const addToHomeScreen = (
  <Picture label="Picture: Add to Home Screen, ringed, and View More as a second place to look." height={84}>
    <rect className="ill-surface" x="2" y="2" width="96" height="46" rx="10" />
    <rect className="ill-accent-ring" x="8" y="8" width="84" height="34" rx="6" />
    <text className="ill-text ill-text-strong" x="50" y="22" textAnchor="middle">
      Add to
    </text>
    <text className="ill-text ill-text-strong" x="50" y="35" textAnchor="middle">
      Home Screen
    </text>
    <rect className="ill-surface ill-dashed" x="2" y="56" width="96" height="26" rx="8" />
    <text className="ill-text" x="10" y="73">
      or View More
    </text>
    <path className="ill-line ill-thin" d="M86 64l4 5-4 5" />
  </Picture>
);

/** The Add button at the top right, above the new icon. */
export const addButton = (
  <Picture label="Picture: the Add button, ringed, at the top right, and the new icon.">
    <rect className="ill-surface" x="2" y="2" width="96" height="72" rx="10" />
    <path className="ill-line" d="M10 15h20" />
    <rect className="ill-accent-ring" x="64" y="6" width="30" height="18" rx="6" />
    <text className="ill-text ill-text-accent" x="79" y="19" textAnchor="middle">
      Add
    </text>
    <rect className="ill-primary" x="36" y="32" width="28" height="28" rx="7" />
    <path className="ill-line" d="M36 68h28" />
  </Picture>
);
