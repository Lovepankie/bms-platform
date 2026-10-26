# How do I...? pictures (issue #598, ADR-095)

The 60 pictures of the help articles live in `frontend/public/help/` (412x900 WebP, the caption drawn on each and the thing to
tap ringed). They were taken on 2026-10-26 by `frontend/scripts/help-screenshots.mjs` in headless Chromium at 412x900, 2x, with
touch and reduced motion, and only fabricated data:

- **Shop screens**: `VITE_DEV_TENANT=demo VITE_RETAIL_MOCK=1 npx vite --port 5174` (the retail mock). In the browser only, the
  script names the mock's two branches Test Shop Ntinda (NTD) and Test Store Kireka (KRK, a store), and gives every item a usual
  buying quantity of 24 so an entry warning can show.
- **Branches, staff, approvals and loans**: `VITE_DEV_TENANT=demo npx vite --port 5173` against a local API (`make dev`, or the
  jar with the dev profile) seeded with `make seed` and `make seed-lending`, signed in as the seeded owner
  (`HELP_OWNER_EMAIL`, `HELP_OWNER_PASSWORD`). Before the run: a loan officer and a cashier invited from Staff (the officer's
  link accepted), and two payouts asked for by the officer, so Approvals has requests the owner may decide. The script adds the
  store Test Store Kireka and invites Test Seller 03 itself.

Retake one article's pictures with `node scripts/help-screenshots.mjs <article-id>` from `frontend/`, or every picture with no
argument. The caption of each picture is its alt text in the article, so change the words there. `corpus.test.ts` fails on a
missing, unused or wrongly sized picture.
