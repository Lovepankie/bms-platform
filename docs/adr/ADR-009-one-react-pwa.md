# ADR-009: Frontend: one React PWA with staff and member areas

## Status

Accepted (2026-09-29)

## Context

Two audiences use the platform through a browser.

- **Staff** (tenant admins, branch managers, loan officers, cashiers, accountants,
  auditors) work at a desk or in the field on laptops and Android phones. They need
  dense tables, forms, approvals and reports.
- **Members** (the tenant's borrowers, savers and investors) use Android phones, often on
  slow or intermittent mobile data. They need to see balances and schedules, apply for a
  loan, invest and pay.

The stack is fixed: React 18, TypeScript, Vite, `vite-plugin-pwa`, TanStack Query and
Router, shadcn/ui. The open question was one application or two.

1. **Two applications** in `frontend/`, one for staff and one for members. Clean
   separation, but two builds, two sets of shared components, two service workers and two
   deploy paths for a two person team.
2. **One application with a route-level split** into a staff area and a member area,
   code-split so a member never downloads staff code.

## Decision

Adopt option 2.

- One Vite project in `frontend/`, built into one static bundle served by the `web`
  container, on every tenant host (`<slug>.<base domain>`).
- Routes: `/staff/...` is the staff area; `/member/...` is the member area; `/` redirects
  to the area matching the current session, or to a sign-in chooser. The platform super
  admin console is served on the platform host (`app.<base domain>`) under `/platform/...`
  from the same bundle.
- Each area is a lazily loaded route tree, so the member bundle excludes staff screens.
  A bundle size budget for the member area is checked in CI (`docs/sdd/04-non-functional-requirements.md`).
- One web app manifest and one service worker. The PWA is installable; the manifest's
  `start_url` is `/` so each user lands in their own area.
- The frontend never holds tenant logic or authorisation rules of its own. It hides what
  the principal's permission list says it cannot use, and the API enforces the same list.
- API types are generated from the API's OpenAPI document; hand-written request types are
  not allowed.

## Consequences

**Better:**

- One design system, one build, one service worker, one deploy.
- A staff user who is also a member (for example a loan officer who saves with the
  tenant) uses one installed app.

**Worse:**

- A bug in shared layout code can affect both audiences.
- The service worker's caching rules must distinguish staff data (never cached for
  offline use beyond drafts) from member data (last-synced read cache).

**Watch for:**

- Staff code leaking into the member chunk. The bundle budget check catches the size
  signal.
- Pressure to build a separate native app. The PWA is the member channel until a
  measured need says otherwise; USSD covers feature phones in phase 2.

## Related ADRs

- ADR-001 places both areas over one core.
- ADR-003 makes the tenant host the tenant boundary the frontend relies on.
