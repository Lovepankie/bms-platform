# Runbook template: customer data cutover

**Applies to:** every tenant onboarding with existing data · **Specification:**
`docs/specs/customer-data-onboarding.md` · **Decision:** ADR-025 · **Design:** SDD chapter 13 ·
**Related:** `docs/runbooks/import-retail.md`, `docs/runbooks/restore-from-backup.md`,
`docs/runbooks/onboard-tenant.md`

Copy this template into the tenant's **cutover record**, which lives outside the repository with
the operator's records (it names the customer and holds real figures). Fill every field; a blank
field is a no-go. The stages are those of the spec, section 2.

Until the framework tasks of the spec (section 9) are built, the retail steps run with the
`import-retail` command (`docs/runbooks/import-retail.md`) and the steps marked **(framework)** are
done by hand as the notes say.

## 1. Cutover record header

| Field | Value |
|---|---|
| Tenant slug | |
| Modules and template versions | for example `retail` with `retail_v1` |
| Mapping version used for the final run | |
| Customer contact who answers questions and signs off | (name kept in the record only) |
| Operator (maker) | |
| Checker (a different person) | |
| Dev lead (restore decisions, exceptions) | |
| Agreed cutover moment (date, time, zone) | |
| Source freeze confirmed by the customer at | |
| Receivable decision and other policy decisions, signed on | |
| Rehearsal date and result | |
| Pre-import dump file, time and SHA-256 | |
| Run id | |
| Sign-off date | |
| Certificate of deletion date | |

## 2. Before cutover day

### 2.1 Discovery (stage 1)

- [ ] Inventory every source: sheets, tabs, form app, paper books. For each: owner, row count, in
      scope or not, and why.
- [ ] Out of scope items told to the customer in writing (for example the cash book tabs,
      pending ADR-022).
- [ ] The customer shared a **copy** through the encrypted channel (spec section 6.2). No email
      attachments in clear, no chat uploads.
- [ ] The copy is stored only in the run's quarantine (framework) or, today, in
      `/opt/bms/import/<slug>/` on the production host, mode 700.

### 2.2 Mapping (stage 2)

- [ ] Canonical template chosen. Anything the template cannot express is raised now as a template
      change with a pull request and a fabricated golden case, not later.
- [ ] Every source column mapped or ignored with a reason; personal columns not needed are ignored.
- [ ] Mapping validator passes (framework), or the export script produces the canonical export
      format and its line check passes (`docs/runbooks/import-retail.md` step 1).

### 2.3 Cleaning and review (stage 3)

- [ ] Review queue has no open blocking or warning issue.
- [ ] Customer questions sent as a numbered list with file and line, without personal values, and
      every answer recorded against its question.
- [ ] Unclear rows decided by the customer: unnamed items, negative stock, duplicates, rows without
      an amount or a borrower.
- [ ] **Receivable decision** recorded and signed (spec section 3.8): open from a customer list,
      treat as settled, or defer with the dev lead's exception.
- [ ] Other policy decisions of the template recorded and signed.

### 2.4 Rehearsal (stage 4)

- [ ] Last night's production backup restored into a throwaway database and the real commit run
      against it (spec section 5.5; framework: the restore drill's rehearsal mode; today: the
      scratch database steps of `docs/runbooks/import-retail.md`, "If the real run was wrong",
      step 2, then the command pointed at the scratch database).
- [ ] Rehearsal reconciliation ties; timings recorded; total commit time fits the cutover window
      with at least half the window spare.
- [ ] Throwaway database dropped.

### 2.5 Readiness checklist (go to cutover day only when every line is ticked)

- [ ] Tenant exists with the right modules, branches and plan limits (`docs/runbooks/onboard-tenant.md`).
- [ ] Tenant admin and staff invited; at least two users for maker-checker.
- [ ] The tenant has **not started trading** on the platform, or the trading override is approved
      and recorded with its reason.
- [ ] Last backup and last restore drill green (`state/last_restore_drill`).
- [ ] Free disk on the host above three times the export size plus the expected dump size.
- [ ] Operator, checker and the customer contact available for the whole window; dev lead
      reachable.
- [ ] The customer knows the freeze: no edits to the source after the cutover moment, and where
      to record sales during the window (paper, to be entered after go-live).
- [ ] No deploy to production scheduled in the window.

## 3. Cutover day timeline

Times are relative to the agreed cutover moment T. Adjust the window to the rehearsal timings.

| Time | Step | Owner | Done when |
|---|---|---|---|
| T minus 1 day | Readiness checklist re-checked; go/no-go 1 | Operator, dev lead | All ticked |
| T | Customer stops editing the source; freeze confirmed in writing | Customer | Confirmation in the record |
| T + 15 min | Final copy received through the encrypted channel; SHA-256 recorded | Operator | Hash in the record |
| T + 30 min | Upload and validate (framework) or export and line check (today) | Operator | No batch failed |
| T + 45 min | Compare with the rehearsal: new rows since the freeze are explained, nothing else changed | Operator | Differences listed |
| T + 1 h | Dry run on production; report saved | Operator | Checksum `match=yes`; no blocking issue |
| T + 1 h 15 | **Go/no-go 2** (section 4) | Operator, dev lead | Decision recorded |
| T + 1 h 20 | Verified pre-import dump (`docs/runbooks/import-retail.md` step 5); file, time and hash recorded on the run | Operator | `pg_restore --list` exits 0 |
| T + 1 h 30 | Commit: maker requests, checker approves (framework); or the real `import-retail` run (today) | Operator, checker | Every batch committed |
| T + 2 h | Reconciliation report generated and checked by the operator | Operator | Every check ties or is itemised |
| T + 2 h 30 | Customer checks totals and spot checks ten records in the app | Customer | Agreed or differences raised |
| T + 3 h | **Go/no-go 3**: sign-off, or rollback decision | Customer, dev lead | Decision recorded |
| T + 3 h 15 | Customer starts trading on the platform; paper records from the window entered | Customer | First live sale or transaction |

## 4. Go/no-go criteria

**Go/no-go 1 (day before):** readiness checklist complete; rehearsal tied; policy decisions signed.

**Go/no-go 2 (before commit):**

- dry run report equals the rehearsal except for explained new rows;
- checksum `match=yes`; every journal in the report balanced;
- no blocking issue; no warning without a resolution;
- tenant not trading, or the override is approved;
- host healthy: `/readyz` UP, free disk as in 2.5, no other heavy job running.

Any "no" is a postponement: the customer keeps using the source; nothing has been written.

**Go/no-go 3 (after reconciliation):** every reconciliation check ties or each difference is
itemised and accepted by the customer. Otherwise go to section 5.

## 5. Rollback plan and decision points

| Situation | Decision | Who | Action |
|---|---|---|---|
| A batch fails during commit | Fix and resume, or stop | Operator, dev lead if unsure | The failed batch rolled back alone. Fix the cause (mapping or source answer), dry run again, resume. Never edit tables by hand |
| The kill switch was used or the host restarted mid commit | Resume or restore | Dev lead | Resume continues from the first uncommitted batch (spec section 10.2) |
| Reconciliation does not tie and **nothing but the import wrote** since the dump | Restore | Dev lead | `docs/runbooks/import-retail.md`, "If the real run was wrong", steps 2 and 3: restore the pre-import dump next to the live database, compare, swap. Then fix, dry run, and repeat the cutover on a new date |
| Reconciliation does not tie and **anything else wrote** since the dump (another tenant, or this tenant traded) | No restore | Dev lead | Correct in the application: stock-takes for quantities, edits for prices, reversing journals through the ledger for opening balances, agreed with the customer's accountant |
| The customer started trading | No restore | Dev lead | Corrections only through the application and the ledger |

Record every decision, its time and who made it in the cutover record.

## 6. After cutover

### 6.1 Sign-off (stage 8)

- [ ] Customer signed the reconciliation in the app (framework) or on the printed report (today);
      the signed report stored with the cutover record.

### 6.2 Source deletion (stage 9), within 30 days of sign-off

- [ ] Plaintext export deleted from the host: `rm -r /opt/bms/import/<slug>`.
- [ ] Pre-import dump deleted: `rm /opt/bms/backups/pre-import-<slug>.*`.
- [ ] Quarantined files, per-run key and staging personal values purged (framework: run state
      `purged`).
- [ ] Any copy on the operator's own device deleted, and the channel's copy removed.
- [ ] Certificate of deletion issued (spec section 6.4): files with sizes and SHA-256, staging row
      counts, the dump's name and hash, time, operator and witness, what is retained. Sent to the
      customer.

### 6.3 Hypercare (stage 10), two weeks

- [ ] Daily check of the tenant's first trading days: errors, unusual balances, support requests.
- [ ] Every defect traced to the import logged against the run with the issue code it should
      have had.
- [ ] First stock-take planned for every branch with negative balances (retail).
- [ ] Metrics recorded (spec section 8): rows by outcome, issues by code, time per stage, defects.
- [ ] Lessons turned into template rules, issue codes or changes to this template, by pull request.
