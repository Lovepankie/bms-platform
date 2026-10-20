# ADR-049: Every route has a tour, breadcrumbs and cross links, enforced by a CI guard

## Status

Proposed (2026-10-20, issues #336 and #337; not yet accepted by the product owner). Builds on ADR-025 (first run and guided tours, tours are data), ADR-009 (one React PWA) and ADR-005 (docs live with the code). The number was checked on 2026-10-20: 043 is held by pull request #349 and 044 by the correction records (pull request #364 and main), 045 to 048 are taken or reserved by open work, so this record takes 049.

## Context

The owner wants a new user to need no training. Three things carry that on every screen: a spotlight tour that explains the page in plain words, a breadcrumb trail that says where the user is and the way back, and links in the page itself that follow the way people think (a buyer's name opens the buyer, an item opens its stock, a sale opens its documents and returns, a branch opens its stock, a total opens the rows behind it).

Breadcrumbs were already structural (FR-UXN-01): each staff route declares a `crumb` label in the router and a test fails when one has none. Tours were not. A tour is data and a screen is code, so nothing connected them: when the audit of 2026-10-20 counted the staff routes there were 105, and 29 had a page tour (the audit table is in `docs/specs/retail-ui-notes.md`, section "Navigation and tour coverage"; this change brought the count to 105 of 105). The first-run and capability tours had been kept current by hand and by the discipline of whoever built the screen, which is exactly the kind of rule that is forgotten under time pressure. The owner raised it on 19 October 2026: the tour must be updated whenever a feature is added.

## Decision

1. **The rule.** Every staff route has (a) a page tour in `frontend/src/tour/`, with plain words and one step per main action of the page, (b) a breadcrumb label, and (c) the cross links of issue #336 wherever the page shows a buyer, an item, a sale, a branch or a total, using routes that already exist (a new link never needs a new screen). A change to what a page offers bumps the version of its tour, so a user who finished the old one sees the new steps once.
2. **Breadcrumbs stay structural.** The trail is read from the route table, so a route gets its place by declaring a label; the existing route-table test fails when it has none. Nothing new is needed for (b).
3. **Tours are checked twice.**
   - `frontend/src/tour/route-coverage.test.ts` fails when a staff route in the route table has no page tour (an exemption is listed in the test with its reason, which a reviewer reads), and when a tour points at a route that is gone. The page tour tests render every step's screen and fail when its `data-tour` anchor is missing (`tours.test.tsx` and one test file per slice, sharing `test-kit.tsx`).
   - A CI guard, `.github/scripts/tour_guard.py`, run by `.github/workflows/tour-guard.yml` on the same `CI_RUNS_ON` runners as the other small guards, fails a pull request that adds or renames a route (an added `createLazyRoute`, `createFileRoute`, `createRoute`, `retailScreen` or `lendingScreen` line in a non-test file under `frontend/src/areas` or in `frontend/src/app/router.tsx`) without any change under `frontend/src/tour/`. The guard has its own unit test (`test_tour_guard.py`) and runs it first.
4. **One explicit escape, two places.** A line `TOUR-EXEMPT: <reason>` in the pull request body (a reason of at least three characters, on a line of its own) passes the `tour-guard` CI check only. It is for a redirect, a screen with no controls of its own, or a route that only re-exports another. It does not satisfy `route-coverage.test.ts`: a route that really needs no tour must also be listed, with its reason, in `NO_TOUR` in that test, which a reviewer reads in the diff. The pull request template carries a checkbox that names both. The workflow also runs when the body is edited, so adding the line re-runs the check.
5. **What the guard does not do.** It cannot read the quality of a tour, and it does not check that a changed tour is the right one; it checks that the person adding a route looked at the tours. The tests of decision 3 check the anchors and the coverage, and the reviewer reads the words.

## Consequences

- Better: a screen cannot ship dark. The count of routes without a tour is zero and stays zero, the owner's "every feature" is a CI fact, and the pull request template and the two process documents say it where people look.
- Better: the tour tests are split by slice with one shared harness, so a new screen adds one small file or one entry, not a change to a long shared switch.
- Worse: every new route costs a tour, an anchor on markup the screen draws before data arrives (the tests render statically), and a test entry. That is the price of the rule and is small against the cost of an unexplained screen.
- Worse: the guard is a text match on declaration lines, so a route declared in some new way (a new helper that wraps `createRoute`) is not seen until the pattern in `tour_guard.py` learns it; the route-coverage test still catches it, because it reads the route table itself.
- Watch: the `TOUR-EXEMPT` line is the only override and is visible in the pull request body; if it becomes common the rule is being worn down and the owner should be told.
- Not decided here: the cross links are a convention applied page by page, not a checked rule, because what counts as a buyer or an item on a page cannot be matched by a script. They are reviewed.
