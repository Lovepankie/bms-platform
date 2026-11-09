# Runbook template: customer data cutover

**Applies to:** every tenant onboarding with existing data · **Specification:**
`docs/specs/customer-data-onboarding.md` · **Decision:** ADR-027 · **Design:** SDD chapter 13 ·
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
| Modules and template versions | for example `retail` with `retail_v1`, or `lending` with `lending_loans_v1` |
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
- [ ] Lending loan register (`lending_loans_v1`, SDD chapter 13 section 13.14.1 10/16; the fabricated
      example is `fixtures/imports/lending_loans_v1.pilot_register.mapping.json`):
      - one mapping source per register file and branch; the source's `defaults` give the loan product
        code (`product`, an active product of the business) and, for a branch other than the head office,
        the branch code (`branch`), unless the sheet has a column for them;
      - `row_rules` switch on `subheader_month` when the sheet has month rows (`MARCH 2026`) and list the
        sheet's placeholder texts in `placeholder_rows`;
      - the run is opened by the staff user who will be the loans officer of the imported loans (their
        remarks are kept as collection notes only then), and its cutover moment is the day the loans are
        aged to (days past due) and the opening journal is dated;
      - the decision `disbursed_principal` (`requested_as_disbursed`: the register has no disbursed
        amount, so the requested principal is taken as disbursed in full) is recorded with the
        customer's confirmation, like the receivable decision; the run is not ready without it.
- [ ] Lending: each branch gets one import opening journal of loans, ever. Import a branch's whole
      register in one run (one file per branch is one batch; a second file of the same branch gets
      `BRANCH_IN_TWO_FILES` and the run is not ready until its rows are left out, so merge the files
      first); a later run that adds loans to a branch already opened posts no journal and its report
      lists them for a correcting entry.
- [ ] Rows per file: until #880 is fixed, a batch holds at most 2,500 rows (`TOO_MANY_ROWS` above it),
      a size the big file test commits on the staging heap well inside the time limits (SDD chapter 15
      section 15.7: 2,500 items dry run in 43 to 85 s and commit in 53 to 130 s; 5,000 took up to 473 s
      of the 10 minutes a batch may take; 10,000 took 17 minutes and nearly filled the heap). Plan the
      window from those figures, on the host the import runs on. Split a larger file into files of at most 2,500
      rows, each its own batch, and rehearse
      the dry run on the largest one first. The limit goes back up to 100,000 with #880.

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
      against it (spec section 5.5, ADR-027 decision 9): for a run of the import screens, on the
      production host, `cd /opt/bms && ./restore-drill.sh --rehearse-import <run id>`
      (`docs/runbooks/restore-from-backup.md` section 5.2), the run in review or ready, once
      last night's backup holds the business (the run's rows are copied live); exit 0. For the
      `import-retail` command, which the rehearsal refuses: the scratch database steps of
      `docs/runbooks/import-retail.md`, "If the real run was wrong", step 2, then the command
      pointed at the scratch database.
- [ ] Rehearsal stored on the run (`import_rehearsals`): `outcome` committed, `matches_dry_run`
      true (or each difference explained: journal entry numbers move when the business posted
      after the backup), the reconciliation in it signable, every difference itemised.
- [ ] Timings recorded from its `timings` (`dry_run_ms`, `commit_ms`); total commit time fits the
      cutover window with at least half the window spare.
- [ ] Throwaway database dropped: the script's last line says it removed what it made, and
      `docker ps -aq --filter name=bms-rehearsal-` is empty.

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
| Resume answers `IMPORT_IN_PROGRESS` | Wait | Operator | A commit loop of this business is still alive (a batch, or the gap between two). Wait for it to stop or finish; pressing stop again is safe. The refusal never clears the stop. The lock ends with the loop's connection, so after a host restart resume works at once |
| The commit stopped with `core.import_run.claim_lost` in the audit | Resume | Operator | The loop lost its connection to the database (or its lock) between two batches and stopped before writing more; a probe that gets no answer counts as lost after about 10 seconds. Committed batches stay; resume continues from the first pending one. If it repeats, ask the dev lead to check the database's idle timeout and the network path |
| The same batch fails on every resume | Restore | Dev lead | The run stays `committing` and keeps the business's one committing slot, so no other import of the business can start. There is no abandon button yet (follow-up with #141): restore the pre-import dump (`docs/runbooks/import-retail.md`, "If the real run was wrong"), which also takes the run back to before its commit, then fix the cause, dry run again and commit again |
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
- [ ] Note the pre-import dump's file name and SHA-256 recorded on the run (cutover record header),
      then delete it: `rm /opt/bms/backups/pre-import-<slug>.*`.
- [ ] Quarantined files, per-run key and staging personal values purged, with a second platform
      operator as witness (issue #139; SDD chapter 13 section 13.14.1 13/16). On the host, from
      `/opt/bms`, in a short-lived API container as `import-retail` runs (its runbook, section 3);
      it starts no web server and no scheduler:

      ```bash
      cd /opt/bms
      set -o pipefail   # so the exit status is purge-import's, not tee's
      docker compose --project-name bms -f compose.yml run --rm --no-deps \
        api purge-import --tenant <business id> --run <run id> \
        --operator <your sign-in email> --witness <the witness's sign-in email> \
        --dump-file <dump file name as recorded> --dump-sha256 <its SHA-256> \
        | tee ~/certificate-of-deletion-<slug>.txt
      ```

      The certificate holds no value of a row, but it lists the files by the names the customer gave
      them, as the run does: if a file name holds a person's name, rename the copy before upload
      (stage 3) next time, and keep the certificate with the cutover record, not on the host, once
      sent. Exit 3 with `storage_unavailable` or `purge-import failed` means some files may already be
      deleted and nothing else changed: run the same command again until it prints the certificate.

      Only a `signed_off` run is purged this way; a run without a dump takes no `--dump-*`. Exit 0
      prints the certificate of deletion and its `sha256`. Exit 3 with `run_not_signed_off`,
      `pre_import_dump_differs`, `pre_import_dump_confirmation_required`, `witness_is_operator` or
      `platform_operator_unknown` is a refusal before anything is deleted: fix the command. Exit 3
      with `storage_unavailable`, `run_changed`, `run_committing` or `purge-import failed` comes after
      the files are being deleted: some files may already be gone while the run, its key and its
      rows are unchanged, so run the same command again until it prints the certificate. Running it
      after a purge prints the same certificate.
- [ ] Any copy on the operator's own device deleted, and the channel's copy removed.
- [ ] Certificate of deletion (spec section 6.4): the printed text, with files, sizes and SHA-256,
      staging row counts, the dump's name and hash, time, operator and witness, what is retained.
      Stored on the run; saved with the cutover record and sent to the customer.
- [ ] If you do not purge within 30 days of the sign-off, the daily `core.imports.retention` does,
      on day 29, whether the business is active or suspended. A warning reaches the operators'
      Telegram chat a week before; if it never went, the purge still happens and a "purged without
      warning" notice reaches the chat and the Owner's email. Its certificate says the dump
      was not confirmed: delete the dump, then run `purge-import` as above with the dump, which
      issues the certificate's next version with your confirmation and the witness. A run never
      signed off is purged 90 days after its last activity, with the same warning; working on it
      again starts that clock again.

### 6.3 Hypercare (stage 10), two weeks

- [ ] Daily check of the tenant's first trading days: errors, unusual balances, support requests.
- [ ] Every defect traced to the import logged against the run with the issue code it should
      have had.
- [ ] First stock-take planned for every branch with negative balances (retail).
- [ ] Metrics recorded (spec section 8): rows by outcome, issues by code, time per stage, defects.
- [ ] Lessons turned into template rules, issue codes or changes to this template, by pull request.
