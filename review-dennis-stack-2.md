# Review: Dennis stack 2 (#38, #44, #45), 2026-10-02

Method: fetched the three branches and read each PR's own diff (`git diff <base>...<head>`), the
issue bodies (#29, #40, #41), the earlier review (`review/dennis-stack-2026-09-30`,
`review-dennis-stack.md`), SDD chapters 3, 6, 7, 8, `docs/specs/lending-mvp-scope.md`, AGENTS.md and
CLAUDE.md. CI was not re-run (stated green); no tests were executed, this is a reading review.
Line numbers are lines in the file at the PR head.

Verdicts:

| PR | Branch | Verdict |
|---|---|---|
| #38 documents hardening (#29) | feat/documents-hardening-issue-29 | APPROVE (non-blocking notes) |
| #44 loan products (#40) | feat/loan-products-issue-40 | CHANGES REQUESTED (2 blocking) |
| #45 loan application (#41) | feat/loan-application-issue-41 | CHANGES REQUESTED (3 blocking) |

Cross-stack checks:
- Flyway: main has V1 to V5 (V5 collateral). #38 adds V6, #44 V7, #45 V8. No collision, no gap, and no other
  open PR carries a migration (#26 and #37 are deploy scripts). All three migrations are additive.
- Dashes: no em or en dash in any added line of the three diffs (checked with a Python scan of the `+`
  lines, because `grep -P '\x{2014}'` errors in this locale).
- Fixtures: nothing real-looking seen in the added tests and docs (round UGX figures, fabricated members).
- Tenant isolation of new tables: V7 and V8 call `bms_apply_tenant_rls` on all 7 new tables, composite FKs
  on `(tenant_id, x_id)` throughout, inserts take `tenant_id` from `current_setting('app.tenant_id')`,
  `RlsIsolationIT` has factory rows for them. Clean. Two non-blocking gaps are in #44 item 6 and #45 item 9.
- `docs/workspace.dsl`: not changed by any PR. Acceptable: the products and loans components and the
  `loans -> members/products/collateral` relationships already exist (lines 60, 131 to 133).
  `loans -> collateral` direction is fine; the reverse `CollateralPledges` hook is a registry, not a dependency.
- openapi.json and schema.d.ts are regenerated in #44 and #45 (the large `-` count in #45's openapi diff is
  schema reordering, not lost content). #38 has no API shape change, correct.

---

## #38 documents hardening (#29): APPROVE

### Status of the earlier review's blocking items (review-dennis-stack.md, #23)
Items 1 to 3 were already fixed on `main` by commit `0e571b7` ("the three #23 review blockers"), and I
confirmed them in code at the #38 head:
1. Rejected member re-entering KYC: FIXED. `MemberRepository.markKycCompleteIfReady` (line 264) now matches
   `kyc_status IN ('incomplete', 'rejected')`; `MemberDocumentsIT.aRejectedMemberCanResubmitWithANewDocument`
   (line 314) proves it. See non-blocking 3 below for a residual weakness.
2. R2 unconfigured startup: FIXED. `StorageConfiguration` with provider `r2` and all four R2 values blank
   logs and falls back to `UnconfiguredObjectStorage` (documents answer 503 `storage_unavailable`); a
   partial R2 setting still fails startup. The 503 code is now in chapter 7.
3. Content-Disposition on signed URLs: FIXED. `StorageConfiguration.attachment()` and `NO_STORE`
   (`private, no-store`) are applied to every signed GET; the `ObjectStorage` Javadoc states it.

Items this PR is responsible for (the non-blocking set, now taken):
4. Decoder memory cap: FIXED. `MAX_PIXELS` 16 MP (`DocumentService`), at most 2 concurrent re-encodes via a
   semaphore, 10 s wait then 503 `uploads_busy`; the wait happens outside any DB transaction
   (`prepare` has no `@Transactional`; `upload` composes `prepare` + `store`). Tested
   (`canvasesOverSixteenMegapixelsAreRefused`).
5. Re-encoded size over 5 MB: FIXED (`prepare` checks `stored.length`), tested.
6. Orphaned objects: PARTLY FIXED, acceptably. `deleteIfRolledBack` registers an `afterCompletion` hook
   (`DocumentService` line 179 and about 355) that deletes only on a known rollback. Unknown outcomes keep the
   object on purpose (a row without its object is worse). A crash between `put` and commit still orphans;
   chapter 8.8 names the sweeper as issue #39. `DocumentRollbackIT` covers the rollback case.
7. Per-member document caps: FIXED by supersede rather than refusal (`MAX_PER_KIND = 10`; the member row is
   locked with `lockById` so concurrent uploads cannot exceed it; `concurrentUploadsNeverLeaveMoreThanTenActive`).
   V6 is expand-only, grants UPDATE on the two supersede columns only, has a CHECK that the pair is set
   together and a self-reference CHECK. Good.
8. Narrower read permission: FIXED. `MemberDocumentAccess.canRead(principal, subjectId, documentId)` requires
   `lending.members.verify_kyc` in the member's branch for `id_front` and `id_back`, `read` otherwise; a document
   with no link row is denied (fail closed); the list hides ID images from callers who cannot open them;
   denials are audited. Per-role tests exist (`idImagesNeedVerifyKycPerRoleAndPerBranch`,
   `aDocumentWithNoKnownKindIsDenied`). Chapter 8.3.2 and 8.8 record the decision.
Issue #29's test list (invalid `doc_kind` 422, cross-tenant 404, empty file, PDF over 5 MB, JPEG bytes with a
`.pdf` name) is covered by the new tests I could see by name (`emptyFilesAreRefusedAndJpegsAreJpegsWhateverTheirName`,
`anotherTenantCannotGetADownloadUrl`, `oversizedFilesAndImagesAreRefused`); I did not see a dedicated invalid
`doc_kind` test among the new method names (the validation exists at `MemberDocumentService.upload`).

### Blocking
None.

### Non-blocking
1. **PR description contradicts the diff.** The #38 body says "No migration" and "at most 10 per member and kind
   (422 `document_limit_reached`)". The code adds `V6__member_document_supersede.sql` and supersedes the oldest
   instead of refusing; the string `document_limit_reached` appears nowhere in code or docs. #45's body even
   relies on "#38's V6". Fix: edit the PR description before merge so the reviewer and the release notes are
   right (the SDD text is correct).
2. **Denied-download audit can be flooded and holds two connections.** `DocumentService` line 259 writes
   `core.document.access_denied` in a `REQUIRES_NEW` transaction from inside the (read-only) request
   transaction. Scenario: a cashier (no `verify_kyc`) loops `POST /documents/{id}/download-url` on one ID image:
   each call writes an audit row and needs a second pooled connection while the first is still held; a burst
   of 20 such calls on a small pool can exhaust Hikari and stall the API, and the audit table grows without
   bound. Fix: rate limit the route, or write the denial row after the outer transaction (an event listener
   `AFTER_COMPLETION`), or accept and size the pool; at least dedupe per (user, document, minute).
3. **Rejected members re-enter KYC on any edit.** `markKycCompleteIfReady` runs after any edit, kin or
   document change (comment at line 248 to 252 says so). Scenario: KYC rejected for a blurry ID, an officer
   changes the village and the member is back in `pending_verification` with the same bad image, so the verifier
   queue fills with unchanged resubmissions. Fix: for `rejected`, re-enter only on a new document or an identity
   field change; add a test that a village edit alone does not move a rejected member.
4. **Uploader cannot view the ID image they just uploaded.** A holder of `lending.members.update` but not
   `verify_kyc` (a loan officer) can upload `id_front` but gets 403 on download and does not see it in the list.
   This is the dev lead's decision (chapter 8.8) so it is not a defect, but the UI must say "uploaded, awaiting
   verification" rather than show an empty list, otherwise officers will upload duplicates until the cap
   supersedes real documents. Add that to the frontend task.
5. **`Documents.store` is a public method that trusts `subjectType`, `subjectId` and `branchId`.** Any module can
   attach a document to any subject in any branch with no access check (same class as the earlier note on
   `Documents.find`). Fix: Javadoc warning now, and route callers through the `DocumentAccess` of the subject
   when a second module (collateral) starts uploading.
6. **Superseded documents stay downloadable by direct id.** Intended (kept for the audit trail), but then the
   KYC "current" semantics are only enforced in the list and in `markKycCompleteIfReady`. State in 8.8 that a
   superseded file keeps the same read rule, so nobody assumes supersede removes access.

---

## #44 loan products (#40): CHANGES REQUESTED

What is good, so the blocking items are read in context: `ScheduleCalculator` uses `BigDecimal` with
DECIMAL128 and `HALF_UP`, no floats anywhere. R-TERM, R-RATE, R-FLAT and R-DECL match chapter 3.4 line by line
(per_year divisors 365 and 12; `i = r_term / n`; last item takes the remaining balance; flat remainder on the
last item; month-end clamping computed from the disbursement date). Worked examples A, B, C are exact tests, and
example C's `69,788.5` interest line is itself a half-up boundary test. Versions and fees are insert-only for
`bms_app` (`SELECT, INSERT`), `UNIQUE (product_id, version_no)` and `FOR UPDATE` plus If-Match make concurrent edits
safe, and `lending.products.read/manage` match the matrix.

### Blocking
1. **Unbounded money inputs: preview 500s and a silent `long` overflow that defeats the fee check.**
   `ProductApi.PreviewRequest.principalMinor` (line about 160) is `@Positive Long` with no `@Max`; `Fee.amountMinor`
   (line 39) is `@Positive Long` and `Fee.rateBp` has no `@Max`. Scenarios:
   a) `principal_minor = 9223372036854775807`: `ScheduleCalculator.round` (line 207) calls `longValueExact()`, which
      throws `ArithmeticException`, so the endpoint returns a 500 to any user with `lending.products.read`
      (6 of 7 roles).
   b) Two `deducted_at_disbursement` flat fees of `4611686018427775808` sum in `ProductService.feeTotal`
      (line 150, `mapToLong(...).sum()`) to a negative number; `deducted >= principal` (line 125) is then false and
      the preview returns a negative `deductedAtDisbursementMinor` and a net disbursement larger than the principal.
      `r.principalMinor() + interest + added` (line 144) can wrap the same way.
   Fix: cap `principalMinor`, `amountMinor`, `min/maxPrincipalMinor` (for example at 10^15 minor units) and
   `rateBp` (for example 100000) in the request records; use `Math.addExact` in `feeTotal` and the totals and map
   `ArithmeticException` to a 422 `amount_out_of_range`. Add tests for 2^63-1 principal and for the two-fee wrap.
2. **A version can be saved whose fees make disbursement impossible, and percentage fees have no ceiling.**
   `ProductService.toVersion` calls `checkFees` (shape only). Scenarios: a flat `deducted_at_disbursement` fee of
   500,000 on a product with `min_principal_minor = 100,000` saves fine; every loan at the minimum then nets a
   negative or zero disbursement, and only the preview (not the save, not the application in #45) refuses it. A
   `percent_of_principal` fee of 99,999 bp (about 1000 percent) deducted at disbursement is accepted too. Fix: at
   save, for each version require that the sum of deducted fees at `min_principal_minor` (flat amount plus percent
   of min principal, using `percentOf`) is strictly less than `min_principal_minor`, cap fee `rate_bp` (suggest
   10000), and reject with a field error on `fees[i]`. Test both cases. (The matching DB CHECK on
   `lending_loan_product_fees` can add `rate_bp <= 10000`.)

### Non-blocking
3. **Declining schedule has no non-negativity guard.** `ScheduleCalculator.declining` (line 152 to 166):
   `principal[k] = instalment - interest[k]` for `k < n-1` is not checked against `balance`. With a very small
   principal and a high per-instalment rate the rounded instalment can be below the rounded interest (negative
   principal, the balance then grows and the last item absorbs a larger principal than P) or can overshoot the
   balance (last principal negative). The random-property test asserts "no negative lines" over 2,000 seeded
   cases, which suggests the guard may be unnecessary in the tested range, but the range is not stated. Fix: clamp
   `principal[k]` to `[0, balance]` or throw a rule error, and add an explicit test for P = 1, 2, 3 minor units at
   100000 bp.
4. **`min_collateral_cover_bp` is saved as null when collateral is required.** `ProductService.toVersion` stores
   `collateral ? t.minCollateralCoverBp() : null`, so `requires_collateral = true` with no cover is accepted and
   FR-ORG-07 ("cover meets the product minimum") will have nothing to compare. Fix: require `min_collateral_cover_bp`
   when `requires_collateral` is true (422 field error), and add the V7 CHECK
   `(NOT requires_collateral) OR min_collateral_cover_bp IS NOT NULL`.
5. **Duplicate code race returns a generic error.** `create` (line 64) is check-then-insert on `code`. The
   `UNIQUE (tenant_id, code)` protects data, but the losing request gets `ApiExceptionHandler.handleIntegrity`
   (generic 409) instead of `duplicate_product_code`. Also `PL1` and `pl1` differ only by case: the request regex
   forces uppercase, so fine. Fix: catch `DuplicateKeyException` and map to `duplicate_product_code`.
6. **`current_version_id` is not tied to the same product in the schema.** The FK
   `(tenant_id, current_version_id)` (V7 line 82) references any version in the tenant, so a bug or a manual SQL
   fix could point product A at product B's version; the application never reads `product_id` of the current
   version to check. Fix: make the FK `(tenant_id, id, current_version_id)` against a unique
   `(tenant_id, product_id, id)` on versions, or a trigger. Low risk, cheap to do while the table is new.
7. **Audit payload of a version is a summary, not the terms.** `ProductService.summary` records method, rate,
   units, pattern and the fee count only. Principal limits, penalties, collateral and guarantor flags, allocation
   order and the fees themselves are not in the audit row, so "who changed the processing fee from 1 percent to
   3 percent" cannot be answered from the audit log (it can from the version rows, but those do not say who
   created version N+1 versus who approved it). Fix: include the whole terms object (no PII in it) in `now`.
8. **No effective dating.** FR-PRD-04 as written only requires a pin; the issue title says "versions". A new
   version is current immediately. If the business wants "new rates from the 1st", that needs `effective_from`.
   State in chapter 3 or the PR that immediate effect is the MVP rule.
9. **Missing negative tests.** `LoanProductsIT` has no test for: a role without `lending.products.manage`
   getting 403 on create, new version and archive; missing `If-Match` (428) and stale `If-Match` (409) on
   `/versions` and `/archive` (one 409/428-style grep hit exists, for the duplicate code); archiving twice;
   a flat fee with a rate (rule shape is covered by `checkFees` but not asserted over HTTP I could find);
   `rate_unit` per_year with each term unit through the endpoint. The seeded-role permission test pattern from
   members would cover the first two cheaply.
10. **`ProductService.preview` has no `@Transactional` and reads nothing**, fine, but it is exposed on
    `lending.products.read` only: the preview accepts a `disbursementDate` far in the past or future with no
    bound (year 9999 plus months is fine in `LocalDate`, `plusMonths` past 999999999 would throw). Cap the date
    range or the term count (`termCount` is already capped at 3660).

### Docs
Updated: chapters 5, 6, 7 (7.11.12 and `duplicate_product_code`), 15, openapi.json and schema.d.ts. Not updated and
should be: chapter 3 FR-PRD-01 acceptance (note the fee ceiling and the cover rule once added), chapter 8 needs no
change (matrix rows exist). No ADR needed for versioning (FR-PRD-04 is the decision), but see item 8.

---

## #45 loan application, guarantors, collateral pledges (#41): CHANGES REQUESTED

What is good: the loan copies the product version's terms at creation (`product_version_id` plus the copied
`interest_*`, `term_unit`, `repayment_pattern` columns) and the provisional schedule is computed from the loan's
own columns, so a product edit cannot change it (`aLoanKeepsTheTermsItWasCreatedWith`). The approver CHECK
(`approved_by <> submitted_by` and `<> appraised_by`) is in force from V8. Status history is append-only and
written by `move`. Every state change audits (`created`, `updated`, `guarantors_set`, `collateral_set`,
`submitted`, `returned`, `cancelled`). Every route declares a permission that exists in the matrix; `return`
needs `lending.loans.approve` (manager) and `cancel` is own draft or manager, matching chapter 3.18. All child
and parent lookups go through RLS plus a branch check (`inScope`, `lockForChange`), out-of-scope is a 404.
If-Match is required on PATCH, both PUTs, submit, return and cancel.

### Blocking
1. **Double pledge is a check-then-insert with no database constraint, so two requests can pledge the same item
   to two open loans.** `LoanService.setPledges` (line 283 to 311) calls `repo.pledgedElsewhere` and later
   `repo.replacePledges`; the only lock is on the one loan row (`lockForChange`). V8 has
   `UNIQUE (tenant_id, loan_id, collateral_id)` which only stops the same item twice on the same loan. Scenario:
   a member has two drafts L1 and L2 and one item C; two officers (or one user, two tabs) `PUT .../collateral`
   at the same moment, each transaction sees no other open pledge and both commit; C now secures two open loans,
   violating FR-ORG-02 and FR-COL-04, and the release check (`LoanPledgesCheck`) then reports it as secured
   by two loans. The same window exists between `CollateralReleaseAction` (checks `securesOpenLoan`, then marks
   `released`) and a concurrent pledge: the item is released and pledged at once. Fix: enforce it in the
   database, in two steps: (a) lock the collateral row
   `SELECT ... FROM lending_collateral_items WHERE id = ? FOR UPDATE` (through `CollateralLookup`, add a
   `lockForPledge`) before the check in `setPledges`, and take the same lock in the release path before
   `securesOpenLoan`; and (b) add a backstop partial unique index on `lending_loan_collateral (tenant_id,
   collateral_id) WHERE released_at IS NULL` only if pledge rows of cancelled, closed and rejected loans get
   `released_at` set on those transitions (they do not today, so (a) is the minimum). Add a concurrent test
   (two threads, expect exactly one 200 and one 409 `collateral_already_pledged`).
2. **A pledge can state any value, including more than the item is worth.** `PledgeInput.pledgedValueMinor` is
   `@Positive Long` and `setPledges` stores it without comparing it to `CollateralSummary.collateralValueMinor`
   (the forced sale value or estimate, which the response even returns beside it). Scenario: an item valued at
   1,000,000 is pledged at 50,000,000; when FR-ORG-07 and the cover score (chapter 3.18.1, `c = pledged value /
   principal`) land in the next increment they will read `pledged_value_minor` and the cover check passes on a
   number nobody validated. Also `guaranteed_amount_minor` has no upper bound. Fix: reject
   `pledged_value_minor > collateral_value_minor` with a field error (`pledge_exceeds_value`) and reject items
   whose value is null (unvalued) for a product that `requires_collateral`; bound both money inputs with `@Max`
   as in #44 item 1; add tests. If partial pledging is meant to be capped at the item value only, say so in
   FR-ORG-02.
3. **Submit does not re-validate what the draft earlier passed, so a frozen application can hold an item or
   guarantor that is no longer valid.** `LoanService.submit` (line 336 to 355) only checks that the guarantor and
   pledge lists are non-empty (`repo.guarantors(id).isEmpty()`, `repo.pledges(id).isEmpty()`) and the KYC
   status. Between `setPledges` and `submit` an item can change custody (for example to a state other than
   `pledged` or `in_custody`, or its currency can be corrected) and a guarantor member can be exited or
   blacklisted; submit still succeeds and "freezes the terms" (FR-ORG-03) with those rows. The member status and
   blacklist are also not rechecked (create checks `active`, submit does not). Fix: in `submit`, re-run the same
   per-item checks used in `setPledges` (held, same currency, not pledged elsewhere), require guarantors to be
   active and not blacklisted, and require the borrower to still be `active` and not `blacklisted`, each with its
   own rule code. Test: pledge, change the item's custody, submit, expect 422.

### Non-blocking
4. **Open-loan set disagrees with the spec for written-off loans.** `LoanRepository.OPEN` (line 23) is
   `draft, submitted, appraised, approved, active`. `CollateralPledges` Javadoc says "closed, cancelled, rejected
   or written off" are not open, while FR-COL-04 (chapter 3) says release is allowed when every loan is `closed`,
   `cancelled` or `rejected`. A `written_off` loan is still being recovered (FR-LCL-03), so its collateral must
   stay held, and `restructured` hands the pledge to the replacement loan. Fix: make OPEN include `written_off`
   (and decide `restructured`), fix the Javadoc, and add a status-parameterised test when those statuses become
   reachable in increment 5.
5. **Provisional schedule is neither frozen nor consistent with the product preview.** `LoanService.provisional`
   (line 507) passes `addedFeesMinor = 0`, so a product with an `added_to_loan` fee shows a schedule with no fee
   column and a total that differs from the #44 preview for the same terms, and `net disbursed`, deducted and
   upfront fees are not shown at all. It also falls back to `clock.today` when there is no proposed disbursement
   date, so a submitted application's displayed due dates move every day (FR-ORG-03 says submit freezes the
   terms; the schedule is display only, but a customer who was quoted dates sees them change). Fix: include the
   version's fees (`ProductCatalog` needs to expose them) via `ScheduleCalculator.percentOf`, and for
   `submitted` and `appraised` loans compute from a stored reference date (store `proposed_disbursement_date` at
   submit, defaulting to the submit date).
6. **Guarantor and pledge replacement loses history.** `replaceGuarantors` and `replacePledges` do `DELETE` then
   `INSERT`, and the audit rows (`guarantors_set`, `collateral_set`) list only the new member and item ids: no
   before-state and no amounts (`guaranteed_amount_minor`, `pledged_value_minor`). Scenario: a guaranteed amount
   is lowered from 2,000,000 to 200,000 in draft; nothing records who or what it was. Fix: put `was` and `now`
   lists with amounts into the audit payloads (money is not PII, fine for audit); keep deletes (drafts only).
7. **`min_collateral_cover_bp` and guarantor adequacy are not checked at all in this PR.** Deferred to approval
   by FR-ORG-07, which is acceptable, but nothing in this PR records that, and the user can submit a product
   that requires collateral with a pledge worth 1 minor unit (`collateral_required` only tests non-empty).
   Fix: either enforce a minimum at submit (cover from the product) or add a "pending #FR-ORG-07" note to the PR
   and chapter 3 so the check is not forgotten; the same note for a guarantor exposure limit (a member can
   guarantee any number of loans for any amount; chapter 3 has no limit yet, so raise it with the product owner).
8. **Any draft locks the borrower's collateral.** Because `draft` counts as open, any holder of
   `lending.loans.create` can park a draft that pledges a member's only item and block release and other
   applications until someone cancels it (cancel of another's draft needs a manager). It is the documented
   one-open-loan rule, but consider releasing pledges when a draft is returned or abandoned, or expiring stale
   drafts. Record the choice in ADR-019 (see 10).
9. **Collateral and guarantor tables grant DELETE and UPDATE with no status guard in the database.** V8 grants
   `SELECT, INSERT, UPDATE, DELETE` on `lending_loan_guarantors` and `lending_loan_collateral`, and the only
   protection that a submitted or active loan's rows cannot be replaced is the service's `requireStatus(draft)`.
   A later bug elsewhere could delete the security behind an active loan with no trace. Fix: a trigger that
   refuses INSERT, UPDATE and DELETE on these tables unless the parent loan is `draft` (`released_at` and
   `status = 'released'` updates excepted).
10. **ADR-019 is still "proposed" and the PR asks the reviewer to decide the one-open-loan rule.**
    AGENTS.md says a decision gets an ADR in the same branch. Fix: once decided, accept ADR-019 and record the
    rule and the release behaviour in it (do not rewrite a merged ADR; if it is already merged use a new one).
11. **Smaller points.**
    a) `update` (PATCH) cannot clear `purpose_text` (null means unchanged) and `proposed_disbursement_date`
       is not validated against today (a date in the past is accepted).
    b) `returnForCorrection` and `cancel` store the free-text note and reason in the audit payload and
       `lending_loan_status_history.reason`; notes can contain personal data typed by staff. Acceptable under the
       audit rules, but make sure the loan detail and history routes are not shown to the member portal later.
    c) Guarantors and borrowers outside the caller's branch: `members.find(g.memberId())` is tenant-wide, so an
       officer can attach and then read `memberNo` of a member in another branch through the guarantor list
       (same disclosure class as the earlier review's #22 note 6). Document it in chapter 8.
    d) Loan number `"LN%06d"` is unique through `UNIQUE (tenant_id, loan_no)` and `TenantSequences`; beyond
       999,999 it widens to 7 digits, which is fine for the column, but FR-ORG-01 says "LN plus 6 digits".
12. **Missing negative tests** (`LoanApplicationIT` has 7 tests): I found no test for `unknown_member`,
    `currency_mismatch`, `collateral_not_held`, missing or stale `If-Match` on PUT, PATCH, submit, return and
    cancel (412/428/409), submit of a non-draft (409), PATCH of a submitted loan (409), `return` by an officer
    (403; `cancel` by an officer on someone else's draft is covered at line 351), an archived product, an inactive
    member, cross-tenant loan, principal above the product maximum on PATCH, or a concurrent double pledge
    (blocking 1). Each is a few lines with the existing seeded-role helpers.

### Docs
Updated: chapters 3 (FR-ORG-02, FR-COL-04), 5, 6, 7, openapi.json, schema.d.ts, `RlsIsolationIT` rows,
`ModularityTest`. Missing: chapter 8 (no new permission, but the `CollateralPledges` hook and the guarantor
disclosure belong in 8.3), the ADR for the one-open-loan rule (item 10), chapter 15 test strategy (race tests),
and `allow_loans_before_kyc_verified` is read through the new `TenantSettings` method: confirm chapter 4 or the
settings catalogue lists it.

---

## Summary of what to do first
1. #45: lock the collateral row (and take the same lock in release) plus a concurrent test (blocking 1); cap
   pledged value at the item value (blocking 2); re-validate items, guarantors and the member at submit
   (blocking 3).
2. #44: bound every money input and use exact arithmetic in preview (blocking 1); validate fees against the
   minimum principal at save (blocking 2).
3. #38: fix the PR description (no migration and `document_limit_reached` are wrong), then merge; the earlier
   review's seven items are all addressed (three on main already, four here).
4. Merge order #38, #44, #45 is right; V6, V7, V8 are collision free.
