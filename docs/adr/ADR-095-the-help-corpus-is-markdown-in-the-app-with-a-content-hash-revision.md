# ADR-095: The help corpus is Markdown in the app, filtered by permission, with a content-hash revision

## Status

Proposed (issue #598, owner decision D51 of 26 Oct 2026), accepted on merge. Step 1 of in-app help; step 2 is #20 (Ask BMS,
owner decision D52), which answers from this corpus and keys its answer cache on the revision defined here. Builds on ADR-025
(guided tours: the same permission rules for a step), ADR-049 (every staff route has a page tour), ADR-050 (the installable app
and its service worker) and ADR-065 (in-app feedback). No migration, no endpoint, no permission added. Nothing here calls a model.

## Context

Pilot users learn by seeing, not reading: a pilot had to message the team to learn how a storekeeper moves stock from a store to
a shop. The tours (#19) show a screen's parts but not a task from start to end, and they are not searchable. #20 needs a corpus
of the same tasks to ground its answers, and a way to know when that corpus changed so a cached answer is never stale.

## Decision

1. **One short Markdown article per task, in the app's source.** `frontend/src/help/articles/<id>.md`, the id being the file
   name. Front matter of plain `key: value` lines: `title` (a question, "How do I ...?"), `job` (one of nine: Sell, Buy stock,
   Move stock, Count stock, Items and categories, Branches and stores, Users and invitations, Money, Loans), `order` within the
   job, `module` (`retail` or `lending`, optional), `needs` (every permission is needed) and `needs_any` (one is enough), `screens`
   (route paths whose How do I? link opens it, a `$name` segment matching any one segment) and `words` (what a person may search
   with). The body is an introduction, a numbered list of steps in plain words, every step with one picture written
   `![caption](<id>-<n>.webp)`, and notes as `>` lines. `**bold**` is the only inline mark. No HTML: the app renders the parsed
   article as React elements (the CSP and the design guard forbid inline HTML).
2. **Who sees an article** is decided by `stepVisible` of the tours (ADR-025): the same `/me` permissions, modules and rules as a
   tour step, so a menu, a tour and an answer never disagree. A person who opens an article their role may not read is told so.
3. **Pictures are real phone screenshots**, 412x900, taken with fabricated data only (`frontend/scripts/help-screenshots.mjs`:
   the retail mock for the shop screens, a local API seeded with `make seed` and `make seed-lending` for the rest), with the
   caption drawn on the picture and the thing to tap ringed. The caption is the picture's alt text in the Markdown, so the two
   never differ. WebP, about 25 KB each, in `frontend/public/help/`.
4. **The revision is a content hash.** The first 16 hex digits of the SHA-256 of, for each article file in name order, its file
   name, a NUL, its raw bytes and a NUL; `.gitattributes` keeps the articles LF on every checkout, so a Windows clone and the
   server compute the same value. Pictures are not in it: they show what the text says. Any edit, addition or removal of an
   article changes it. The Vite plugin `bms-help-corpus` (`frontend/vite.config.ts`) reads the folder at build time and hands the
   app `virtual:help-corpus` (the sources and the revision); `corpus.test.ts` recomputes it the same way, and #20 computes it from
   the same files, with no list to keep in step by hand.
5. **Search is on the phone, with no network.** The articles are bundled; the search lowers case, drops punctuation and a plural
   or -ing ending, ignores words such as "how do I", and needs every remaining word in the title (weight 3), the search words (2)
   or the text (1), a word of 3 letters or more also matching the start of a longer one, best score first. No PostgreSQL search
   until #20 needs one on the server.
6. **Offline.** The articles are in the precached JavaScript; the service worker also precaches `help/*.webp` (`includeAssets`),
   so How do I...? and its pictures work offline once the app is installed. The precache grows by about 1.5 MB.
7. **Where it shows.** How do I...? on the Help sheet that Help and feedback opens (staff area), the pages `/staff/help` (search
   and the groups) and `/staff/help/$articleId` (one answer), each with a page tour, and a small How do I? link beside the
   breadcrumbs of every screen an article names, for someone who may read it. "Not answered? Ask us" opens Feedback
   (`/staff/feedback?ask=...`) as a question with the search text typed in.
8. **Checked in tests.** Each article parses, has at least two steps with pictures, names only seeded permissions and routed
   screens, uses the glossary words and no long dashes; every picture named is a 412x900 WebP and every picture in the folder is
   named; the search answers the questions pilots asked; a seller, an administrator and a loan officer each see their own set.

## Consequences

- Writing help is a pull request: a new article is a Markdown file and its pictures; the tests say what is missing. A screen
  that changes its words needs its article and pictures retaken, which the capture script makes a single command.
- The corpus is English only; a locale would add a folder per language and the locale to #20's cache key.
- The corpus ships with every app build, so an answer can be wrong only between a screen change and its article's update, the
  same window as a tour.
- #20 reads the same files on the server; its cache key carries the revision, so an edited article retires its answers at once.

## Amendment: getting started (issue #609)

- A tenth job, **Getting started**, comes first: how a business starts using BMS (`/sign-up`, `/sign-up/verify`,
  `/application`), opening an invitation (`/accept-invitation`), signing in and a forgotten password or sign-in page
  (`/sign-in`, `/forgot-password`, `/reset-password`, `/find-sign-in`) and installing the app (`/staff/notifications`). They
  need no permission and no module, so everyone sees them, and Ask BMS grounds on them like any other article. The How do I?
  link is drawn by the staff layout only, so of these screens only `/staff/notifications` shows one; the screens before sign-in
  are named to tie each article to what it describes (checked by `corpus.test.ts`: every word an article puts in bold is a label
  in the code of those screens), and the articles are read from How do I...? and Ask BMS once signed in.
- Their screens open before sign-in, where the capture script of decision 3 needs a local API, so these four ship as text
  first: `PICTURES_PENDING` in `corpus.test.ts` names them, checks they have no picture yet, and shrinks as their pictures
  land. Every other article still has a picture on every step (decision 8).
- The search drops its stop words before it stems a word, so "this" no longer becomes "thi" and matches "this branch".
