# Review: Dennis stack (#22, #15, #23), 2026-09-30

Method: read each PR's own diff (`git diff <base>...<head>`) on the fetched branches, plus the
issues (#10 body not re-read; #11 and #12 read), SDD chapters 3, 6, 7, 8, AGENTS.md and CLAUDE.md.
CI was not re-run (stated as green). Line numbers are lines in the file at the PR head.

Verdicts:

| PR | Branch | Verdict |
|---|---|---|
| #22 member lifecycle (#10) | feat/member-lifecycle-issue-10 | APPROVE (non-blocking notes) |
| #15 next of kin (#11) | feat/next-of-kin-issue-11 | APPROVE (non-blocking notes) |
| #23 documents (#12) | feat/documents-issue-12 | CHANGES REQUESTED (3 blocking) |

Cross-stack checks, all clean:
- Flyway: main has V1, V2. #15 adds V3, #23 adds V4, and #24 (collateral) adds V5 on top of them. No number collision and no gap. All additive.
- Dashes: no em or en dash in any added line of the three diffs (byte-level grep).
- Fixtures use `Test Borrower NN`, `07000000NN`, `CMTEST00000NNA`. No real-looking data seen.
- Tenant isolation: every new table has tenant_id, `bms_apply_tenant_rls`, composite FKs on (tenant_id, x_id); inserts take tenant from `current_setting('app.tenant_id')`; the new view is `security_invoker` and has an isolation test; `documents` and `lending_member_documents` are in `RlsIsolationIT`. Object keys are server generated (`tenants/<tenant>/upload/yyyy/mm/<uuid>.<ext>`) and a DB CHECK ties the prefix to the row tenant, so no path traversal and no caller-influenced key.
- Audit masking: `JdbcAuditLog.MASKED_KEYS` masks national_id, phone_e164, alt_phone_e164, phone, other_id_number, so the new `was`/`now` payloads are masked for those keys.
- Maker-checker: chapter 8.4 does not list KYC decision or blacklist as maker-checker actions, so single-step with permission is correct. Permissions used (`verify_kyc`, `blacklist`, `update`, `create`, `read`) match the matrix rows.

---

## #22 member lifecycle: APPROVE

### Blocking
None.

### Non-blocking
1. **FR-MEM-10 exit rule is not implemented and not written down in the SDD.** `MemberService.java:156` (Javadoc only) says the open-account check "arrives with the accounts". The FR-MEM-10 row in chapter 3 still says acceptance "Tested". Scenario: nothing stops `PATCH status=exited` today, and nothing records that the check is owed. Fix: add a "pending increment 4" note to the FR-MEM-10 acceptance cell (or a tracked issue reference) so it cannot be forgotten when loans land.
2. **No status transition rules on PATCH.** `MemberService.update` (line 161) accepts `active|inactive|exited` from any state, so `exited` back to `active` is allowed and audited only as a field change. Fix: decide whether `exited` is terminal or needs an explicit reinstate permission; at least test the chosen behaviour.
3. **A verified member can change identity and stay verified.** `update` changes `id_type`, `national_id`, `other_id_number` without touching `kyc_status`. Scenario: KYC verified on NIN A, an officer with `update` swaps the NIN to B, member remains `verified`. Fix: on an identity edit of a `verified` member, move to `pending_verification` (audited) or require `verify_kyc` to edit identity fields. Raise with the SDD owner: FR-MEM-05 is silent.
4. **NIN race surfaces as generic 409.** Create and update check `memberNoByNationalId` then write; the unique index `lending_members_nin` (V1) protects correctness, but the loser gets `ApiExceptionHandler.handleIntegrity` `conflict` instead of `duplicate_nin`. Fix: catch `DuplicateKeyException` in the service and map to `duplicate_nin` (re-query the member number). Add a concurrent test if practical.
5. **Phone duplicate is a check-then-write with no constraint** (`checkPhoneShared`, line 83). This is by design (a shared phone is allowed after confirmation), so acceptable; note only that two concurrent creates can both pass. No fix needed.
6. **Cross-branch disclosure via duplicate-check and `duplicate_phone`.** `duplicateCheck` and `checkPhoneShared` search the whole tenant and return member numbers of members outside the caller's branch scope (`inScope:false` rows, and the 409 detail lists holder numbers). Same disclosure class as the existing `duplicate_nin`, but phone is new: any user with `lending.members.create` can probe whether a phone is registered anywhere in the tenant. Fix: accept and document in chapter 8, or return only "a member in another branch" for out-of-scope phone matches.
7. **`officer_user_id` not validated on PATCH.** `update` copies `officerUserId` without checking the user exists or is active in the branch; a bad id becomes a generic 409 from the FK. Fix: validate like create does (or add a test showing the FK error mapping).
8. **Audit of `monthly_income_minor` and `date_of_birth`.** `was`/`now` includes both unmasked; masking covers only the listed keys. Low risk (audit is permissioned) but AGENTS rule 9 and chapter 8.9 favour minimisation. Fix: add `date_of_birth` to MASKED_KEYS or omit from the diff.
9. **Tests:** `MemberLifecycleIT` covers stale If-Match, shared phone, NIN clash, KYC only from pending, blacklist reason, scope. Missing: PATCH with no If-Match header (expect 428), lift of blacklist by a role without `blacklist`, `verify_kyc` reject without note, duplicate-check with no fields. Add them.

---

## #15 next of kin: APPROVE

Depends on #22 (stacked); merge order must be #22 then #15 then #23.

### Blocking
None.

### Non-blocking
1. **Scope gap against issue #11.** The issue lists "KYC completeness moving a member to pending_verification"; that lives in #23 (`markKycCompleteIfReady`). Until #23 merges nothing ever moves a member to `pending_verification`, so `KYC_COMPLETE` in `NextOfKinService.java:39` and the KYC decision of #22 are unreachable. Fine as a stack, but the PR description should say "KYC completeness ships in #23", and #15 must not be closed as done for #11 without #23.
2. **Backward linking rewrites other members' next of kin without their own audit row.** `NextOfKinRepository.linkToMember` (line 149) updates kin rows of members in any branch (bumping `version`, so a client holding an ETag for that kin gets a 409 on its next edit). Only one `lending.member.kin_links_found` audit row is written, on the member being registered, listing kin ids. Fix: also write a `lending.next_of_kin.link_*` audit row per touched kin (entity = the kin, branch = its owner's branch) so the kin's own history explains the change.
3. **Stale links after a member's NIN or phone changes.** `linkToMember` only adds links. A kin linked by NIN to member M stays linked if M's NIN is later corrected to another value. Scenario: typo fixed on M, the old kin link still shows on M's relationship panel and (via the view) in exposure logic later. Fix: on NIN change, unlink kin whose `link_method='nin'` and NIN no longer matches (or mark for review), with audit.
4. **`resolve` phone match (line 122) ignores member status/blacklist and branch scope.** It suggests any tenant member with that phone; the suggested member's `linked_member_no` is returned in the kin response to a user who may lack read scope on that member. Same disclosure class as #22 note 6. Fix: mask like the panel does (`RelatedMember.visible`) or accept and document.
5. **DB constraint gap (V3).** `CHECK ((linked_member_id IS NULL) = (link_method IS NULL))` exists, but nothing stops `link_status IN ('suggested','confirmed')` with a NULL `linked_member_id`. Fix: add `CHECK (link_status IN ('none') OR linked_member_id IS NOT NULL)` (V3 is unmerged so it can be edited in place).
6. **Concurrent primary flags.** `clearPrimary` then insert relies on the partial unique index; the loser sees generic 409 `conflict`. Acceptable; a retry hint would be kinder.
7. **No frontend.** Issue title says relationship panel; only the typed client (`schema.d.ts`) changed. If the issue expects UI, that is missing; otherwise say so in the PR.
8. **Tests:** `NextOfKinIT` has 6 tests. Missing: missing or stale If-Match on PATCH, DELETE and link (409/428), a phone shared by two members must not be suggested, kin id from another tenant returns 404, `lending.members.update` denied on a read-only role for POST/DELETE.
9. **Docs:** chapter 6, 7, 3, 5, 15 updated; openapi and schema.d.ts regenerated. OK.

---

## #23 documents: CHANGES REQUESTED

### Blocking
1. **Rejected members can never re-enter the KYC flow.** `MemberRepository.markKycCompleteIfReady` (line 252) only moves `incomplete` to `pending_verification` (`WHERE ... kyc_status = 'incomplete'`, line 256). After `decideKyc(rejected)` (#22) no edit, kin add or upload moves the member out of `rejected`; nothing else does either. Scenario: a reject for a blurry ID photo, the officer uploads a new id_front and nothing changes, the member can never be verified, so with `allow_loans_before_kyc_verified` off they can never borrow. Fix: let `rejected` re-enter `pending_verification` when a new document or edit arrives after the rejection (audited), and test it.
2. **Merge to `main` will break staging boot unless secrets exist.** `application.yml:131` defaults `bms.storage.provider` to `r2`, and `StorageConfiguration` throws at startup if the R2 documents endpoint, keys or bucket are empty. `main` deploys to staging automatically (ADR-006) and V4 is applied before the swap. Scenario: merge, migration runs, new API container crash-loops, staging is down. The PR edits `deploy/compose*.yml` and the runbook but nothing proves the staging host and the documents bucket and token exist. Fix: state in the PR description the merge precondition (bucket, bucket-scoped token, env on both hosts) and confirm it is done first, or make the documents module degrade (fail the upload with 503) when unconfigured rather than block startup.
3. **Signed URL serves content inline with no hardening headers.** `R2ObjectStorage.signedGetUrl` (line 68) presigns a plain GET. A PDF (stored raw, not sanitised) or a mislabelled image is rendered in the browser from the storage origin. Fix: add `responseContentDisposition("attachment; filename=...")` (server generated name, not user input) and keep `contentType` from the stored, content-sniffed value; consider `response-cache-control: private, no-store`. Add the same to the fake for test parity. Also: PDF metadata and embedded JavaScript are not stripped (only image EXIF is); say so in chapter 8.8 or reject PDFs containing `/JavaScript` or `/Launch`.

### Non-blocking
4. **Decoder memory.** `DocumentService.MAX_PIXELS = 40_000_000` (line 52): a 40 MP image decodes to roughly 160 MB of heap per request, and the staging host is a shared ARM64 board. A handful of concurrent uploads can OOM the API. Fix: lower to about 16 MP (ID cards need far less) and cap concurrent re-encodes with a semaphore.
5. **Upload size checked before re-encode only** (line 126). A re-encoded PNG can exceed 5 MB, and the stored size is unchecked. Fix: check `stored.length` too or accept and document.
6. **Object written before the DB insert, inside a transaction** (`storage.put` line 136). If the insert or a later step fails and the transaction rolls back, the object is orphaned in the bucket. Fix: write the row first, put after, and delete the object on failure, or add a sweeper for keys without a row.
7. **No limit or lifecycle on member documents.** Any number of `id_front`/`photo` uploads per member, no delete or supersede, table is INSERT only. Storage growth and a stale `id_front` counting for KYC. Fix: cap per member per kind, or mark the latest per kind as current.
8. **Read access to ID images uses `lending.members.read`** (`MemberDocumentAccess.canRead`). That includes the read-only roles in the matrix (columns 4 to 6). ID images are more sensitive than list data. Confirm with the product owner whether a narrower permission (for example `lending.members.documents.read`) is wanted; if not, note the decision in chapter 8.8.
9. **403 vs 404 on existence.** `DocumentService.readable` returns 404 for an unknown id and 403 for an out-of-scope one, which confirms existence of a document id. The issue demands 403 before any URL, and ids are random UUIDs, so risk is low; note only.
10. **`Documents.find` is public and has no access check.** Any module can read any document's metadata by id. Fine while only `lending.members` uses it; add a Javadoc warning or make callers go through `DocumentAccess`.
11. **Missing tests:** invalid `doc_kind` (422), upload by a role lacking `lending.members.update` beyond the matrix test, download of another tenant's document id (expect 404), download in an out-of-scope branch (403, if not already in `downloadUrlsAreCheckedSignedAndShortLived`), a rejected member re-upload (see blocking 1), an empty file, a PDF over 5 MB, a polyglot (JPEG magic bytes with a `.pdf` name) to confirm the type comes from content.
12. **Docs:** chapters 3, 5, 6, 7, 8, 12, runbook, openapi, schema.d.ts and workspace.dsl (documents component and storage link already exist) are covered. No ADR for choosing R2 with a fake and signed-URL access; ADR-018 and chapter 12 may suffice, but the decision that the fake serves a public route in dev and test profiles deserves one line in chapter 8.8. `.env.example` uses placeholders only. OK.

---

## Summary of what to do first
1. #23: fix the rejected-member dead end (blocking 1), confirm staging R2 config before merge (2), add content-disposition (3).
2. #22 and #15: schedule the non-blocking items 3 and 4 of #22 and item 3 of #15 (stale links) before increment 4 builds on them.
