# Re-review: Dennis stack 2, fix commits (#44 ffa8aa9, #45 0c11cd8), 2026-10-05

Method: read the fix commits' diffs and the files at the branch heads (`git diff <sha>~1 <sha>`, worktree of
`feat/loan-application-issue-41`), compared each item with `review-dennis-stack-2.md` (branch
`review/dennis-stack-2-2026-10-02`). I did not trust the author's summaries. No tests were run (CI stated green); the
race-test conclusion below is by reasoning about Postgres behaviour. Line numbers are at the PR head.

| PR | Verdict |
|---|---|
| #44 loan products (#40) | **APPROVE** (all blocking items fixed; 2 non-blocking gaps below, chapter 7 codes first) |
| #45 loan application (#41) | **APPROVE with non-blocking follow-ups** (all 3 blocking items fixed in code; the race test does not prove the lock) |

Stack checks: Flyway is V6 on main (#38), V7 (#44), V8 (#45), no gap or collision. `git merge-tree` of #44 onto
current main is clean. Em and en dashes in added lines of both PR diffs: 0 (Python scan). `docs/workspace.dsl` not
touched by either; acceptable, the `loans -> collateral` and `loans -> products` relationships already exist and the new
`lockForPledge` is a method on an existing interface.

---

## #44: APPROVE

### Status of the earlier items
1. Unbounded money inputs, `long` overflow, 500 from `longValueExact`: **FIXED.**
   `ProductApi.java:28` `MAX_MONEY_MINOR = 10^15`; `@Max` on fee `amountMinor` (line 45), `rateBp` (48, 10000),
   `min/maxPrincipalMinor`, penalty flat (rate 100000, cap 1000000), cover bp, preview `principalMinor` and `maxTermCount`
   3660. `ProductService.feeTotal` (about line 183) and the preview totals use `Math.addExact`/`reduce(0, Math::addExact)`;
   `preview` (line 127 to 139) maps `ArithmeticException` to 422 `amount_out_of_range`. Preview date is bounded
   2000-01-01..2100-12-31 (item 10, FIXED). Tests: `LoanProductsIT.outOfRangeAmountsAreRefused` (line 372).
2. Fees making disbursement impossible, fee rate ceiling: **FIXED.**
   `ProductService.checkFeesLeaveADisbursement` (about line 326) runs in `toVersion` after `checkFees`, sums the
   deducted fees at `min_principal_minor` with `addExact` and stops at the first fee that reaches the principal
   (`fees[i]`, `fees_exceed_principal`); because it stops early the running sum cannot overflow. Percent fees scale with
   the principal and flat fees shrink relatively, so the check at the minimum covers every larger principal.
   Ceiling: `@Max(10000)` plus DB `CHECK (rate_bp BETWEEN 1 AND 10000)` (V7 line 103). Test
   `feesMustLeaveSomethingToDisburse` (409).
3. Declining schedule non-negativity: **FIXED.** `ScheduleCalculator.java:166` `Math.clamp(instalment - interest[k], 0, balance)`;
   `aTinyPrincipalAtAVeryHighRateNeverGoesNegative` covers P = 1, 2, 3 at 100000 bp.
4. Cover required with collateral: **FIXED.** Field error `min_collateral_cover_bp` (`ProductService.toVersion`), V7 CHECK
   `NOT requires_collateral OR min_collateral_cover_bp IS NOT NULL`; test `aSecuredProductStatesItsMinimumCover`.
5. Duplicate code race: **FIXED.** `DuplicateKeyException` on `insertProduct` mapped to `duplicate_product_code`.
6. `current_version_id` tied to its product: **FIXED.** V7: `UNIQUE (tenant_id, product_id, id)` on versions and
   `FOREIGN KEY (tenant_id, id, current_version_id)` (the product row is inserted with a null version first, then linked,
   so the order works).
7. Audit payload: **FIXED.** `ProductService.terms` puts the whole version (fees included) in `now`; test
   `theAuditRowHoldsTheWholeTerms`. (A create has no `was` by nature.)
8. No effective dating: **FIXED as documentation.** Chapter 3 states immediate effect and that scheduling is `Later`.
9. Negative tests: **FIXED.** `changesNeedThePermissionAndTheVersion` (line 483), `aYearlyRateWorksWithEachTermUnit`.
10. Preview date range: **FIXED** (see 1).
Docs asked for (chapter 3 acceptance notes): **FIXED** (chapter 3 lines 417 to 430, chapter 6 for the FK, CHECKs, fee rate).

### New problems in the fix commit
Blocking: none.

Non-blocking:
- N1. **New error codes missing from chapter 7.** `amount_out_of_range` (422) and `fees_exceed_principal` (field
  code on `fees[i]`) appear only in chapter 3 (`grep` of `docs/sdd/07-api-design.md` finds neither). The fix commit
  did not touch chapter 7. Fix: add both to the 422 rule/field code list (about line 198) and the product endpoints'
  notes, in this branch.
- N2. **Fee list has no `@Size`.** `ProductApi.java:92` and `:111` `@Valid List<Fee> fees`. `ProductCatalogService.addedFeesMinor`
  (line 47) uses `addExact`, but `added_to_loan` fees are not summed at save, so a product with about 9,300 flat fees of
  10^15 can be saved (request body about 1.5 MB) and then every loan on it throws `ArithmeticException` out of
  `LoanService.scheduleOf`, a 500 on create and on every GET of that loan (`respond` builds the schedule). Fix:
  `@Size(max = 20)` on both `fees` lists; optionally sum added fees with `addExact` at save and return
  `amount_out_of_range`.
- N3. Other DB-level bounds (flat `amount_minor <= 10^15`) are only in the API layer; a bound CHECK is cheap while V7 is
  unmerged. Optional.

---

## #45: APPROVE with non-blocking follow-ups

### Blocking items of the earlier report
1. **Double pledge race: FIXED in code, PARTLY PROVEN by the test.**
   - Lock in `setPledges`: `LoanService.java:288` locks the loan (`lockForChange`), then `lockItems` (line 364) calls
     `CollateralLookup.lockForPledge` for each distinct item in sorted id order; that is `repo.lock` = `SELECT ... FOR UPDATE OF c`
     (`CollateralRepository.java:48`), `Propagation.MANDATORY` (`CollateralLookupService.java:28`). The check
     `pledgedElsewhere` (line 307) runs after the locks and now counts any unreleased pledge on the item regardless of
     loan status (`LoanRepository` about line 232).
   - Submit: `submit` (line 398) locks the loan then the items in the same sorted order (line 429) before re-checking.
   - Release: both paths already lock the item first, then ask `CollateralPledges`: `CollateralService.java:318`
     (`lockForChange` -> `repo.lock`, then `securesOpenLoan` at 322) and `CollateralReleaseAction.java:76 to 81`. Custody
     and valuation updates also use `lockForChange`, so they serialise with a pledge too.
   - Lock order and deadlock: pledge and submit take loan row then items in ascending id; release takes one item only and
     reads pledges without locking the loan; cancel/return take only the loan row and update pledge rows (no key change,
     so no FK lock on the item). One global item order, no cycle found. Backstop: V8 partial unique index
     `lending_loan_collateral_one_open_pledge (tenant_id, collateral_id) WHERE released_at IS NULL`, and `DuplicateKeyException`
     on `replacePledges` is mapped to the same 409 (line 319). Pledges are now released (`released_at`) in
     `LoanRepository.move` when the loan reaches `cancelled`, `rejected` or `closed` (line 137), which is the precondition
     I set for the index. `written_off` keeps its pledges (earlier item 4, FIXED, with Javadoc).
   - Test: `LoanApplicationIT.twoLoansPledgingOneItemAtOnceGiveOneWinner` (line 432) uses two threads and a latch and
     asserts exactly one 200 and one 409 and one open row. **It would still pass with the lock removed:** without the
     lock both transactions pass `pledgedElsewhere`, the second INSERT blocks on the unique index until the first
     commits, then fails with `DuplicateKeyException` and the same 409. So the test proves the index, not the lock.
     That is acceptable as a correctness guarantee for pledge-vs-pledge. It leaves the lock's real job (pledge vs
     release, and a stable read of custody, value and currency) untested. See N4.
2. **Pledged value above item value, unvalued items, bounds: FIXED.** `pledgeRefusal` (line 338): `pledge_exceeds_value`
   on `collateral[i].pledged_value_minor`; `collateral_not_valued` when the product requires collateral and the item has no
   value; `LoanApi.java:24` `MAX_MONEY_MINOR` with `@Max` on principal (30, 41), guaranteed amount (50) and pledged value
   (60). Test `aPledgeCannotExceedTheItemsValue` includes `Long.MAX_VALUE`. Residual in N5.
3. **Submit re-validation: FIXED.** `submit` lines 398 to 453: borrower `active` (`member_not_active`) and not blacklisted
   (`member_blacklisted`); each guarantor `guarantor_not_active` / `guarantor_blacklisted`; all pledge rules re-run on the
   locked rows (custody, currency, value, pledged-above-value, pledged elsewhere); guarantor/collateral required by the
   product. Test `submitChecksAgainWhatTheDraftCollected` exercises each code, including a custody change and a value
   drop after the pledge.

### Non-blocking items of the earlier report
4. Open-loan set vs spec: **FIXED.** `RELEASES_PLEDGES = {cancelled, rejected, closed}`; written off holds; Javadoc and
   chapter 3 and ADR-019 updated. `restructured` left to increment 5, noted.
5. Provisional schedule: **FIXED.** `scheduleOf` (line 602) uses `products.addedFeesMinor(...)`; submit stores today as the
   proposed date (line 447); `theProvisionalScheduleCarriesFeesAndAFixedDate`. Net disbursement and deducted fees are
   still not shown on the loan (the preview shows them); acceptable.
6. Audit before/after: **FIXED.** `guarantors_set` and `collateral_set` carry `was` and `now` lists with amounts
   (lines 271 to 277, 324 to 330).
7. Cover and guarantor adequacy: **FIXED as documentation.** Chapter 3 states submit does not measure adequacy, cover is
   FR-ORG-07 at approval, and the guarantor limit is an open question for the product owner.
8. Draft locks collateral: **PARTLY FIXED.** The rule is stated in chapter 3 and ADR-019 ("a draft holds its pledges")
   but the abandoned-draft problem (a `loans.create` holder can park a draft on a member's only item) is not weighed in
   the ADR and there is no expiry. Non-blocking; raise it with the product owner.
9. DELETE/UPDATE without a status guard: **FIXED.** V8 triggers `lending_loan_collateral_guard` and
   `lending_loan_guarantors_guard` (lines 150 to 205) refuse INSERT/UPDATE/DELETE unless the parent loan is `draft`,
   except an UPDATE that leaves item, value and loan unchanged; test `pledgesAndGuarantorsChangeOnlyOnADraft`. See N6.
10. ADR-019: **FIXED.** Amendment section records one open loan per item, release statuses, lock order, index backstop and
    the race test; the ADR was already Accepted (merged in #24), so an amendment is the right form.
11. a) PATCH can clear `purpose_text` with `""`, past dates give `in_the_past`: **FIXED** (chapter 7 note,
    `requireNotPast`). b) staff free text: **FIXED** (8.3/8.6 note). c) guarantor member number disclosure: **FIXED**
    (documented in 8.3). d) `LN` plus 6 digits widening: **NOT ADDRESSED**, trivial, still a known note.
12. Negative tests: **MOSTLY FIXED.** Present: `unknown_member` (line 707), `currency_mismatch`, `member_not_active`,
    `product_archived`, 428 and stale-version table over PUT/PATCH/submit/return/cancel (line 670 to 693), submit of a
    non-draft 409, cross-tenant (`changesNeedTheVersionAndStayInsideTheTenant`), the concurrent pledge. Not seen: officer
    calling `return` (403) and PATCH of a submitted loan (409) by name; I did not execute the tests.

### Documentation of the fix commit
Updated and consistent: chapter 3 (origination rules), 6 (pledge index, triggers, pledged-value rule), 7 (new field and rule
codes, `in_the_past`, `purpose_text: ""`, money bound), 8.3 and 8.6, 15 (races row), ADR-019 amendment, `openapi.json`
(the `@Max` on pledges/guarantors), `schema.d.ts`. `collateral_already_pledged` was already in the 409 list.

### New problems in the fix commit
Blocking: none.

Non-blocking:
- N4. **The concurrency test does not fail without the lock.** Scenario: someone deletes `lockItems` in `setPledges`;
  `twoLoansPledgingOneItemAtOnceGiveOneWinner` stays green because the unique index and `DuplicateKeyException` produce
  the same 200/409. Then the pledge-vs-release window and stale reads of custody/value return silently. Fix: add a test
  that holds the item lock from the owner connection (`SELECT ... FOR UPDATE` in an open transaction on
  `TestDatabase.owner()`), fires `PUT .../collateral` in a thread, asserts it has not finished after about 500 ms, then
  commits and asserts it completes; plus a pledge-vs-`release` two-thread test (release request vs pledge, then the
  outcome is consistent: either release refused because the item is pledged, or pledge refused with `collateral_not_held`).
- N5. **Unvalued item on an unsecured product takes any pledge up to 10^15.** `pledgeRefusal` line 348 to 353 returns null
  when the item has no value and the product does not require collateral, so `pledged_value_minor` is unchecked. Harmless
  today (no cover check for such products), but FR-ORG-07 reads pledged values later. Fix: reject
  `collateral_not_valued` for any pledge, or state that unsecured products ignore pledged values.
- N6. **Guard trigger lets `released_at` move freely on a frozen loan.** `lending_loan_collateral_guard` returns early for any
  UPDATE that keeps loan, item and value, so `SET released_at = NULL` (un-release) or a back-dated release on an active
  loan is allowed to `bms_app`. Un-releasing would trip the unique index only if another open pledge exists. Fix: in the
  early-return branch require `OLD.released_at IS NULL AND NEW.released_at IS NOT NULL` or no change to `released_at`.
- N7. **Lists without `@Size`.** `PledgesRequest.collateral` and `GuarantorsRequest.guarantors` (`LoanApi.java:54, 64`) are
  unbounded; a long list means that many row locks and per-item queries in one transaction. Fix: `@Size(max = 20)`.
- N8. **Guarantor state is read without a lock at submit.** A guarantor blacklisted in a concurrent transaction after the
  check and before commit passes. Single-statement window, accepted risk; note in 8.3 if wanted.
- N9. **ADR-019 draft-hoarding question** (item 8) still open. See above.

---

## Summary
Both PRs: every blocking item from the 2026-10-02 report is fixed in code and in the docs, the migrations are numbered and
shaped correctly (V7 and V8 are additive, FK and CHECK changes are sound, RLS and composite tenant FKs unchanged), money
arithmetic is exact on the preview and loan paths, and audit payloads now carry `was` and `now`. Merge order #44 then #45.
Before merge, cheap to do: #44 N1 (chapter 7 codes) and N2 (`@Size` on fees); #45 N4 (a test that really exercises the lock).
