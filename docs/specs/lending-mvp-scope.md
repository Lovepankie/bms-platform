# Lending MVP scope

**Status:** Draft · **Owner:** Hillary · **Applies to:** the first release for the pilot tenant

The pilot tenant is a licensed money lender (Tier 4, Uganda) with loans, savings and
investment products. Today it runs one spreadsheet loan register with no repayment
tracking and no general ledger.

The MVP puts the pilot tenant's **loan book** on the platform, operated by its staff:
members, loan products, origination with maker-checker approval, disbursement, schedules,
partial repayments, arrears and penalties, collateral, collections, SMS, the general
ledger and the core reports, starting from an import of the existing register. Savings,
investments, the member portal and online payments follow in phase 2, once the open
questions below are answered.

Commercial terms (budget, timeline commitments, pricing, contract) are agreed outside this
repository and are not recorded here.

## 1. MVP increments, in build order

Each increment is sized to one sprint for the two developers (default sprint length two
weeks; set at planning). Each lists the requirements it delivers (chapter 3 FR IDs,
chapter 4 NFR IDs). An increment is done when its requirements' acceptance criteria pass
in CI and on staging.

| # | Increment | Delivers | Demo at the end |
|---|---|---|---|
| 0 | **Foundation.** Repository, CI, local environment, database roles, RLS helper and catalogue test, tenant resolution from host, module boundary test, kernel (money, clock), audit writer, outbox and worker skeleton, health endpoints, staging deploy | FR-TEN-02, FR-AUD-01, FR-AUD-02, NFR-ISO-01, NFR-ISO-02, NFR-ISO-03, NFR-SEC-03, NFR-MNT-01 | Two fabricated tenants on staging; isolation suite green |
| 1 | **Tenancy, identity, approvals.** Platform tenant creation, plans and modules, branches, staff invitation and sign-in, MFA for admins, sessions, roles and the permission matrix, branch scope, maker-checker mechanism | FR-TEN-01, FR-TEN-03 to FR-TEN-06, FR-TEN-08, FR-BR-01 to FR-BR-05, FR-IAM-01 to FR-IAM-08, FR-IAM-11, FR-IAM-12, FR-AUD-03 to FR-AUD-05, FR-APR-01 to FR-APR-08, NFR-ISO-04 | Tenant admin invites a branch manager and a cashier; a checker approves a test action |
| 2 | **General ledger.** Chart of accounts seeding, `post_entry`, periods, manual journals, payment method mapping, trial balance, profit and loss, balance sheet, reconciliation job | FR-GL-01 to FR-GL-08, FR-GL-10, FR-RPT-01 to FR-RPT-03, reports `core.trial_balance`, `core.income_statement`, `core.balance_sheet`, `core.gl_detail`, `core.cash_book` | Manual journal made and approved; statements balance |
| 3 | **Members and collateral.** Member registration, phone and NIN normalisation, duplicate check, KYC, next of kin, relationship linking, documents, search, blacklist, collateral register | FR-MEM-01 to FR-MEM-11, FR-MEM-13, FR-DOC-02, FR-DOC-03, FR-COL-01 to FR-COL-05 | Officer registers a member with next of kin who is another member; relationship panel shows it |
| 4 | **Products and origination.** Loan products with versions and fees, schedule preview, application, guarantors and collateral pledges, appraisal with the default score, approval with the approver rule, expiry | FR-PRD-01 to FR-PRD-05, FR-ORG-01 to FR-ORG-08, chapter 3 section 3.18.1 | Application submitted, appraised, approved by a second user |
| 5 | **Disbursement and repayments.** Schedule generation (flat and declining), disbursement with checker, fee handling, repayments with allocation, overpayment credit, reversal, payoff quote, closure, write-off and recovery, receipts and vouchers | FR-DIS-01 to FR-DIS-04, FR-REP-01 to FR-REP-06, FR-LCL-01 to FR-LCL-03, FR-DOC-01 (receipt, voucher, schedule, loan statement), FR-DOC-04, rules R-ROUND to R-PAYOFF | Loan disbursed, part paid, reversed, paid off; trial balance and subledger agree |
| 6 | **Arrears, collections, SMS.** Nightly job (DPD, penalties, snapshots, expiry), waivers, officer assignment, due and arrears lists, collection actions and promises, SMS outbox with templates and sending window, fake and first real SMS adapter | FR-ARR-01 to FR-ARR-04, FR-CLN-01 to FR-CLN-06, FR-NTF-01 to FR-NTF-08 (loan events), NFR-AVL-04 | Overdue loan gets penalties and reminders; officer logs a promise to pay |
| 7 | **Pilot import.** Import framework, `pilot_loan_register_v1` template, review queue, preview, commit with checker, opening balances, reconciliation report, golden test | FR-IMP-01 to FR-IMP-08, FR-GL-09, chapter 13 | Fixture imported end to end on staging; then a dry run on the real file, outside the repository |
| 8 | **Reports and go-live readiness.** Portfolio, PAR, disbursements, collections, officer performance, collateral register, regulatory summary; async report runs; performance tests; backup and restore drill; go-live checklist | FR-RPT-04, FR-RPT-05, FR-COL-06, chapter 14 sections 14.3 to 14.7, NFR-PERF-01 to NFR-PERF-05, NFR-BAK-01 to NFR-BAK-04, NFR-DP-07, NFR-DP-08 | Restore drill within 2 hours; pilot sign-off on reports |

**Pilot go-live** after increment 8: the pilot tenant's staff operate the loan book on
production, with the imported register as opening position. A parallel run with the
spreadsheet for one agreed period is recommended.

## 2. Phase 2, in likely order

| # | Increment | Delivers | Depends on |
|---|---|---|---|
| 9 | Savings | FR-SAV-01 to FR-SAV-07, savings SMS events, savings reports | Open question 2 |
| 10 | Investments | FR-INV-01 to FR-INV-07, investment reports | Open question 3 |
| 11 | Member portal (PWA) | FR-IAM-09, FR-IAM-10, FR-MSS-01 to FR-MSS-03, FR-MSS-05, FR-MSS-06, FR-ORG-09, NFR-OFF-01 to NFR-OFF-06, NFR-PERF-06 | Increments 9 and 10 for the savings and investment screens |
| 12 | Online payments | FR-PAY-01 to FR-PAY-06, FR-MSS-04 | Pending ADR-011; open question 6 |
| 13 | Remaining P2 items | FR-REP-04a, FR-REP-07, FR-REP-08, FR-LCL-04, FR-DIS-05, FR-BR-06, FR-TEN-07, FR-MEM-12, FR-IMP-09, FR-DOC-05, FR-PRD-06 | |

Later: USSD (FR-MSS-07, pending ADR-013), tenant data export and deletion (FR-TEN-09),
top-ups (FR-LCL-05), custom roles, holiday calendar, accrual accounting, retail vertical.

## 3. Open questions for the pilot tenant

Each answer lands as a change to the named chapter (and, where it is a decision, an ADR).
Until answered, the default stated in the chapter applies.

| # | Question | Default until answered | Affects |
|---|---|---|---|
| 1 | **Partial repayments and late penalties.** In what order does a part payment settle penalty, fees, interest and principal? Is there a late penalty: flat or percentage, per day, week or month, after how many grace days, with what cap? On early full payment of a flat loan, is any interest given back? | Order penalty, fee, interest, principal; no penalty; no early settlement rebate | Chapter 3 R-ALLOC, R-PEN, R-PAYOFF; product setup |
| 2 | **Savings products.** Voluntary or compulsory (for example a percentage of each loan)? Interest paid or not, at what rate and how calculated? Minimum balance, withdrawal notice, fees, dormancy rules? Can savings secure a loan? | Voluntary, no interest, no fees | FR-SAV-*, chapter 6 savings tables |
| 3 | **Investment products.** Terms offered, how the return is agreed (per year or per term), paid monthly or at maturity, early withdrawal allowed and on what terms, rollover practice? | Fixed term, return at maturity, no early withdrawal | FR-INV-* |
| 4 | **Branches and staff roles.** How many branches now and planned? How many staff per role? Who approves loans, disbursements and write-offs? What amount thresholds should skip the second approver? | One head office branch; every action needs a checker | FR-BR-*, FR-APR-04, chapter 8 |
| 5 | **Regulator and compliance.** Which returns must be filed, to whom, how often, in what format? What record retention periods apply under the licence? | `lending.regulatory_summary` only; no automatic deletion | Chapter 14 section 14.7, NFR-DP-06 |
| 6 | **Payments.** Which payment gateway account exists or is planned (Interswitch has been mentioned)? Which mobile money wallets and merchant codes does the tenant use today (MTN MoMo, Airtel Money)? | Cashier records mobile money receipts manually (chapter 12 section 12.4.3) | Pending ADR-011, FR-PAY-* |
| 7 | **Loan products.** Are all current loans single-payment (bullet) with a rate for the whole term, as the register suggests? Are instalment loans offered or planned? Are any fees charged (application, processing)? Is the requested principal always disbursed in full? | Flat, per term, bullet; no fees; requested equals disbursed | FR-PRD-*, chapter 13 section 13.8 |
| 8 | **Collateral handling.** Which items or documents does the tenant physically keep, where, and how are they released? Is a national ID card taken as collateral, and has the tenant confirmed that practice is lawful? | Type `national_id` enabled but can be disabled per tenant | FR-COL-*, FR-COL-05 |
| 9 | **Next of kin contact.** May the tenant contact a borrower's next of kin about arrears, by SMS or otherwise? | No messages to next of kin | Chapter 11 section 11.3.2 |
| 10 | **SMS.** Preferred sender name; languages for messages; quiet hours | Aggregator default sender; English; 08:00 to 20:00 | FR-NTF-* |
| 11 | **The register.** Is the day/month order in the spreadsheet dates day first? Are closed loans from earlier years in the file or elsewhere? Is there one sheet or several? Are there repayment records outside the sheet (receipt books, mobile money statements)? | Rules of chapter 13; import what the file holds | Chapter 13 |
| 12 | **Cut-over.** Go-live date, parallel run length, and who at the tenant signs off the imported opening position | Parallel run recommended | Go-live checklist |
| 13 | **Data protection.** Is the tenant registered with the Personal Data Protection Office? Does it accept hosting outside Uganda under the Act's conditions? | Must be settled before go-live | NFR-DP-07, NFR-DP-08 |

## 4. Explicitly not in the MVP

Member portal, online payments, USSD, savings, investments (phase 2); retail vertical;
accrual accounting and provisioning; native apps; multi-currency operation; custom roles.
