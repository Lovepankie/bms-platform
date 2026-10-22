# ADR-066: The installed app uses the business's own logo and name as its icon

## Status

Proposed (owner request of 22 Oct 2026, issue #446, FR-TEN-08). Builds on ADR-050 (BMS installs as an app) and on the tenant branding
of FR-TEN-08 (the logo as a core document, the theme colour rule). Amends ADR-050 decision 1 only in where the manifest and the iPhone
icon come from; every other decision of ADR-050 stands. Numbered ADR-066 because ADR-065 (in-app feedback) is taken and pending ADR-064 (collection lists) is claimed by an
open pull request.

## Context

ADR-050 made the staff app installable with one static manifest built into the PWA: the name `BMS` and the BMS icons for every
business. A business that has uploaded its logo still sees the BMS icon on the home screen, in the app list and in the task switcher,
so each client's app looks like ours and not theirs. The owner wants the installed app to carry the business's own logo and name.

The web container serves the same files on every host (ADR-018), so it cannot tell one business from another. The API can: it
resolves the business from the host on every request and already serves the logo publicly at `/api/v1/branding/logo`.

## Decision

1. **The manifest comes from the API, per host.** `GET /api/v1/branding/app/manifest.webmanifest` (public, `application/manifest+json`)
   keeps the identity of ADR-050 (`id` and `start_url` `/staff`, `scope` `/`, `display` `standalone`), takes `name` from the business's
   display name and `short_name` from its first 12 characters as a reader counts them (grapheme clusters, so an emoji or an accented
   letter is never split), cut at the last word boundary when that keeps at least 6, and `theme_color` from its readable theme colour (else `#0077B6`, the
   colour of the old static manifest). It is `Cache-Control: no-cache` with an ETag, so a phone revalidates it cheaply on every load.
   Keeping the `id` means an installed app stays the same app when its name or icon changes.
2. **The icons are drawn on the server from the logo**, with Java2D and ImageIO and no new dependency, at
   `/api/v1/branding/app/{icon}`: `icon-192.png` and `icon-512.png` (purpose `any`, the logo within 80 percent of the side),
   `icon-maskable-192.png` and `icon-maskable-512.png` (the logo inside the safe zone, the circle of 40 percent radius every launcher
   mask keeps), and the 180 px `apple-touch-icon.png`, every one a square of the theme colour with the logo centred and no
   transparency (iOS draws transparency black). The icons are kept in memory per business under one version (a render version constant raised
   whenever the drawing changes, the logo's checksum and the colour), drawn once however many requests miss at the same moment; that
   version and the size are the ETag, and the icons are and is `Cache-Control: public, max-age=3600` with `Vary: Host`. The manifest names the icons with `?v=` the render version, the logo id
   and the colour, so a new logo, colour or drawing is a new URL.
3. **The BMS icons stay the fallback.** A business with no logo gets its name and colour with the BMS icons the web container already
   serves under `/icons/`; a host that is no business (the platform host, an unknown name) gets the old BMS manifest, never a 404, and
   each icon route answers 302 to the BMS icon of the same name, as it does when storage cannot give the logo and when the stored logo
   cannot be drawn (never a 500; that failure is logged once and kept for the logo and colour). The manifest and the five icon paths,
   compared exactly, are the only paths the tenant filter lets through without a business; every other branding route still answers 404 `unknown_tenant` there.
4. **The page links the API's manifest and icon.** `index.html` links `/api/v1/branding/app/manifest.webmanifest` and
   `/api/v1/branding/app/apple-touch-icon.png`; vite-plugin-pwa runs with `manifest: false`, so the build writes and injects no manifest of
   its own, while it keeps the service worker, `push-sw.js` and the precached BMS icons. The service worker never serves the new routes:
   nothing under `/api/` is precached or cached at run time (ADR-009), and the navigation fallback refuses `/api/`. iOS takes the home
   screen name from `apple-mobile-web-app-title`, not from the manifest, so `index.html` keeps that tag (`BMS`) and the shell sets it at
   run time to the business's short name, the same cut as the manifest's (`fixtures/app-short-names.json` holds the cases both test).
   The browser tab and address bar icon is the same logo: `index.html` links `/api/v1/branding/app/icon-192.png` as its only
   `rel="icon"` (owner request of 22 Oct 2026), so a business with a logo sees it in the tab too, and every other host gets the BMS icon
   through the same 302 fallback. The shell adds `?v=` from the logo address and colour to that link, so a new logo is a new tab icon
   address (Safari keeps favicons long).
5. **Under `/api/v1/branding/`.** The routes sit with the other public branding routes, so the edge proxy already sends them to the
   API (`/api/*`) and neither Caddyfile changes.
6. **When a change reaches a phone.** Android Chrome checks the manifest of an installed app from time to time and updates its icon
   and name by itself (it may ask the person to confirm). An iPhone takes the icon and the name when Add to Home Screen is tapped and
   never looks again, so after a logo change the business's staff remove the app and add it again on an iPhone.

## Consequences

**Better:**

- Each business's installed app shows its own logo and name, with nothing to set up beyond the logo it already uploads.
- No new dependency, no new table, no new permission, and no change to the proxies.

**Worse:**

- The manifest is no longer a static file: the API must be up for a browser to read it, and a phone that installs while the API is down
  sees no manifest.
- Drawing an icon costs some milliseconds of CPU the first time per logo, colour and size on each API instance.
- An iPhone keeps the old icon until the app is added again.

**Watch for:**

- A logo with a white background on a white theme colour, or a very wide logo, makes a small icon; the business can change the colour
  or upload a squarer logo.
- The in-memory icon cache is per instance and holds the icons of at most 1000 businesses (five icons each at most, the current logo and
  colour only); past that it renders again after an eviction, which is cheap but not free.
- If a member area is installed later with its own identity, it needs its own manifest `id` and start page; this decision covers the
  staff app.
