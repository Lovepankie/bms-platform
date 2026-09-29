# 1. Introduction

**Status:** Draft · **Owner:** Hillary

## 1.1 Purpose

This System Design Document (SDD) is the canonical technical reference for BMS Platform.
It is written so that a developer working with a coding agent can build the platform core
and the lending vertical from it without further clarification. Where something is not
yet decided, the chapter says so and names the pending ADR or the open question.

## 1.2 The product

BMS Platform is a multi-tenant, subscription-based business management system for small
and medium enterprises in Uganda and East Africa. It is a server platform reached through
a browser and an installable web app (PWA).

It is built as a **platform core plus vertical modules** (ADR-001):

- The **core** gives every tenant what every business needs: companies (tenants) on their
  own subdomain, branches, staff users with roles, a member or customer portal identity,
  an audit log, maker-checker approvals, a double-entry general ledger, SMS and email
  notifications, PDF documents, a reporting framework, a spreadsheet import framework, and
  payment intake from mobile money through a gateway.
- A **vertical module** adds one line of business. A tenant switches on the verticals it
  needs.
  - **Lending** (first, being built now) serves microfinance institutions and money
    lenders: members with KYC, loan products, applications, appraisal, approval,
    disbursement, schedules, repayments, arrears and penalties, collateral, savings,
    investments, collections, member self-service and SMS notifications.
  - **Retail** (later, not built now): stock, point of sale, invoices, purchases,
    expenses and debtors.

## 1.3 The first tenant

The first tenant is referred to throughout this repository only as **the pilot tenant**:
a licensed money lender (Tier 4, Uganda) with loans, savings and investment products.
Today it keeps a single spreadsheet loan register with no repayment tracking and no
general ledger. The shape of that spreadsheet (not its content) drives the import design
in chapter 13 and is documented in `docs/specs/pilot-data-dictionary.md`.

## 1.4 Scope of this document

| In scope | Out of scope |
|---|---|
| Platform core | Retail vertical (named only, ADR-001) |
| Lending vertical | Synchronisation with the offline single-device product (ADR-007) |
| Staff area, member area, platform console | Native mobile apps |
| SMS notifications; USSD as phase 2 | Regulator return formats (open question) |
| Mobile money intake through a gateway | Accrual accounting and provisioning (ADR-004) |
| Import of the pilot tenant's loan register | Commercial terms, pricing, contracts (kept outside this repository) |

## 1.5 How to read this document

| Reader | Read |
|---|---|
| Anyone new | Chapters 1, 2, 5; then `docs/specs/lending-mvp-scope.md` |
| Backend developer | 3, 5, 6, 7, 8, 13, 15, and ADR-002, ADR-003, ADR-004, ADR-010 |
| Frontend developer | 3, 7, 11, ADR-009, and chapter 4 section 4.8 (offline) |
| Whoever owns deployment | 9, 10, `docs/runbooks/`, ADR-006 and ADR-008 |
| Reviewer of money correctness | Chapter 3 section 3.4, chapter 6 section 6.6, chapter 14, ADR-004 |

Chapter list and ownership are in `docs/sdd/README.md`.

## 1.6 Conventions

- "Shall" marks a requirement. Requirements have stable IDs (`FR-...` in chapter 3,
  `NFR-...` in chapter 4).
- Money is in integer minor units; for UGX that is whole shillings (ADR-004).
- All worked examples, fixtures and sample data are fabricated. No real person, borrower,
  phone number, ID number or figure from any client appears in this repository.
- House style: no em dashes or en dashes anywhere (enforced in CI).

## 1.7 Related documents

- `AGENTS.md`: onboarding for developers and coding agents; module map; rules.
- `PROCESS.md`: work item hierarchy, branch and pull request flow.
- `docs/adr/`: the decision log.
- `docs/workspace.dsl`: the Structurizr C4 model that renders these chapters, the ADRs and
  the diagrams as one site.
- `docs/specs/lending-mvp-scope.md`: the MVP cut and the open questions for the pilot
  tenant.
