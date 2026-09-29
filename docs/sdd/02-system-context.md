# 2. System Context

**Status:** Draft · **Owner:** Hillary

## 2.1 Context

BMS Platform sits between a tenant's staff and members on one side, and a small set of
external services on the other. The System Context view in `docs/workspace.dsl` is the
diagram of this chapter.

```
            Platform operator (super admin)
                        |
 Tenant staff ----> [ BMS Platform ] <---- Members (PWA; USSD in phase 2)
 (admin, manager,       |   |   |
  officer, cashier,     |   |   +--> Object storage (PDFs, uploads, backups)
  accountant, auditor)  |   +------> Payment gateway ----> Mobile money operators
                        +----------> SMS / USSD aggregator ----> Mobile networks
                        +----------> Transactional email service
```

## 2.2 Actors

| Actor | Description | Channel |
|---|---|---|
| Platform operator | Runs the platform: creates tenants, manages plans and subscriptions, monitors, supports. Holds the super admin role. | Platform console on `app.<base domain>` |
| Tenant admin | Owner or general manager of a tenant. Configures the tenant, users, products, settings. | Staff area |
| Branch manager | Approves loans and checker actions for their branches, supervises collections. | Staff area |
| Loan officer | Registers members, captures applications, appraises, follows up arrears in the field. | Staff area, often on an Android phone |
| Cashier or teller | Records disbursements, repayments, deposits, withdrawals and payouts. | Staff area |
| Accountant | Chart of accounts, manual journals, period close, reconciliation, financial reports. | Staff area |
| Auditor | Internal or external auditor; read-only access to records, reports and the audit log. | Staff area |
| Member | A borrower, saver or investor of a tenant. Views balances and schedules, applies, pays. | Member area (PWA); SMS; USSD in phase 2 |
| Regulator | Receives compliance returns. Not a system user; returns are exported and submitted by the tenant. | Exported reports |

Full permissions per role are in chapter 8.

## 2.3 External systems

| System | Purpose | Status | Chapter |
|---|---|---|---|
| SMS and USSD aggregator | Outbound SMS (receipts, reminders, one-time codes), delivery reports, USSD sessions in phase 2 | Provider pending ADR-013; Africa's Talking is the working assumption | 12 |
| Payment gateway | Mobile money collection (MTN MoMo, Airtel Money) and card payments, callbacks, settlement reports | Provider pending ADR-011; Pesapal and Interswitch are the candidates, and the pilot tenant has mentioned Interswitch | 12 |
| Mobile money operators | Hold the payer's wallet; reached only through the gateway | Via gateway | 12 |
| Object storage | Cloudflare R2, S3 API: generated PDFs, uploads, encrypted backups | Decided in the product plan | 9, 12 |
| Transactional email | Staff invitations, password resets, report-ready notices | Provider to be chosen with the infrastructure work | 12 |
| DNS and TLS | Wildcard DNS for tenant subdomains, wildcard certificate | Chapter 9 | 9 |
| Offline single-device product | A separate product; no integration | ADR-007 | none |

## 2.4 Tenancy model at the boundary

- Each tenant is reached at `<slug>.<base domain>`. A branch is not a URL; staff switch
  branch inside the app (FR-BR-03).
- The platform console is on `app.<base domain>`; gateway and aggregator callbacks arrive
  on `api.<base domain>`.
- The product's own domain is not yet registered; configuration calls it the base domain,
  and documents use the reserved `.invalid` suffix in examples.

## 2.5 Constraints

| Constraint | Consequence |
|---|---|
| Members mostly use Android phones on intermittent mobile data | PWA, small member bundle, offline read cache (NFR-OFF, NFR-PERF-06) |
| Many members use feature phones | SMS for all key events; USSD in phase 2 |
| Mobile money is the dominant payment rail | Gateway integration and callback design (chapter 12) |
| The team is two developers plus the dev lead | Modular monolith, one host per environment (ADR-002) |
| Tenants are licensed and regulated | Double-entry ledger, audit, maker-checker (ADR-004, chapter 8) |
| Personal data law: Uganda Data Protection and Privacy Act, 2019 | Chapter 4 section 4.9 |
| The pilot tenant's data is a spreadsheet with quality issues | Review-queue import (chapter 13) |
