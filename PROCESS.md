# PROCESS

**Adopted:** 2026-09-29 · **Owner:** Hillary Arinda (Dev Lead) · **Applies to:** `bms-platform`

Every unit of work traces from a commit up to an epic, and every change reaches
production through the same deterministic path. This document is the standard.

## 1. Hierarchy

```
Epic
 └─ Feature                 (a capability delivering business value)
     └─ Story
         ├─ Task            (implementation work; PR raised here)
         ├─ Bug             (defect description; parent is always a Story)
         │   └─ Task        (the fix; PR raised here)
         └─ Spike           (research; time-boxed)
             └─ (optional) Task (only if the spike produces shippable code)

Chore                       (maintenance; may sit under a Story or Epic, or stand alone)
```

**Pull requests are always raised against Tasks or Chores.**

**Pragmatic Feature rule.** Business epics decompose into Features. Enabler epics, such as
the engineering foundation, may skip the Feature layer and go straight to Stories.

Suggested epics, matching `docs/specs/lending-mvp-scope.md`: Foundation; Tenancy,
identity and approvals; General ledger; Members and collateral; Loan origination;
Disbursement and repayments; Arrears, collections and SMS; Pilot import; Reports and
go-live; then phase 2 epics for savings, investments, member portal and payments.

### 1.1 The what against the when

The hierarchy is the **what**. The iteration a Story is scheduled into is the **when**.
Iterations are milestones named `Sprint N (DD Mon YYYY)` after their start date; default
length two weeks, set at planning.

### 1.2 Linked issues

- Parent links use GitHub's native sub-issues. Where a native link is not possible, the
  child's body carries a plain line: `Parent Epic: #NNN`, `Parent Story: #NNN`,
  `Parent Bug: #NNN`, `Parent Spike: #NNN`.
- Every Story names the FR and NFR IDs it delivers (from `docs/sdd/03-functional-requirements.md`
  and `04-non-functional-requirements.md`), and its acceptance criteria are those IDs'
  criteria.
- Every pull request that touches code or architecture carries `Closes #<task>`;
  `linked-issue-guard` fails it otherwise.

## 2. Work item types

| Type | Meaning |
|---|---|
| Epic | A large body of work with its own acceptance criteria; production usable when done |
| Feature | A capability that delivers business value end to end |
| Story | A coherent slice of a Feature, demonstrable on its own |
| Task | Implementation work; the unit a pull request closes |
| Bug | A defect; its parent is always a Story; the fix is a child Task |
| Spike | Time boxed research; the deliverable is usually an ADR |
| Chore | Maintenance with no direct business value |

## 3. Definition of Done

| Level | Done when |
|---|---|
| **Task / Bug / Chore** | Pull request merged, CI green, docs updated in the same pull request, deployed to staging, issue closed |
| **Spike** | Deliverable committed, usually an ADR; Spike closed with a link to it |
| **Story** | Every child closed, the FR acceptance criteria pass on staging, demo done |
| **Feature** | Every child Story closed and the capability works end to end on staging |
| **Epic** | Every child closed, Epic acceptance criteria met, released to production |

The Task level checklist is in `AGENTS.md` ("Definition of done").

## 4. Branch naming

Short-lived branches from `main`, merged within days, not weeks.
`<type>/<slug>-issue-<n>` where type is one of `feat, fix, docs, chore, refactor, test,
spike`:

```
feat/loan-schedule-flat-issue-42
fix/phone-normalise-nine-digits-issue-58
docs/adr-010-backend-framework
spike/payment-gateway-sandbox
```

## 5. Commit convention

```
<type>(<scope>): <subject> (#<task-number>)

<body>

Refs #<task-number>
```

Subject imperative, lower case, no full stop, under 72 characters. Scope is the module
(`ledger`, `lending-loans`, `imports`, `web`, `docs`).

Escape hatch prefixes that may omit the issue number: `chore:`, `docs:` for top level
markdown only, `docs(meeting):`, `docs(record):`, `style:`, `test:`. If a commit also
makes substantive changes, the escape hatch does not apply.

## 6. Pull requests

1. Title matches the commit subject.
2. Body contains `Closes #NNN`, the FR or NFR IDs delivered, and the definition of done
   checklist.
3. One pull request equals one Task.
4. Requires one approving review from someone other than the author, green CI, branch up
   to date with `main`, and linear history. Squash or rebase, never merge commits.
5. **Docs in the same pull request.** A change to behaviour, a table, an endpoint, a
   permission or a posting rule updates the chapter that describes it in the same pull
   request (ADR-005).
6. **Architecture coupling.** A change to `docs/workspace.dsl` that reflects a decision
   needs the corresponding ADR in the same pull request, and an accepted ADR that changes
   structure needs the model updated in the same pull request.
7. **Record type fast track.** Files under `docs/meetings/` and `docs/specs/` may be self
   merged by the author, still through a short-lived branch and a pull request.

## 7. Delivery pipeline

Deterministic and repeatable. The design is recorded in ADR-006 and detailed in
`docs/sdd/10-cicd-pipeline.md`; this section is the contract developers work to.

```
short-lived branch ──PR──> CI (lint, types, tests on PostgreSQL, frontend build,
                              docs guards, model validation)
        │ merge (squash)
        ▼
      main ──> build images once, tag sha-<short>, push to the registry
        │      ──> deploy the same images to STAGING automatically
        │
        │ git tag vX.Y.Z on a commit already green on staging
        ▼
   PRODUCTION <── retag those exact images (no rebuild); GitHub Environment
                  `production` requires Hillary's approval; migrations run
                  before the app containers switch; failed health check rolls back
```

Rules:

- `main` is always deployable. Nobody pushes to `main` directly.
- Nothing reaches production that has not run on staging as the identical image.
- Migrations are backward compatible (expand and contract, chapter 6 section 6.9), because
  rollback swaps containers without reversing migrations.
- Versioning is semantic: `vMAJOR.MINOR.PATCH`. PATCH for fixes, MINOR for features and
  backward compatible API changes, MAJOR for breaking API changes. Before the pilot
  go-live, versions are `v0.x.y`.
- Deploy jobs skip with a clear notice, rather than fail, while the target host's secrets
  are not yet configured.
- A hotfix follows the same path: branch, pull request, `main`, staging, tag.

## 8. Content boundary: this repository is technical only

No client names, borrower names or details, real phone or ID numbers, logos, rates or fees
actually charged, budgets, book sizes, contracts or commercial terms. The pilot customer
is only ever "the pilot tenant". Commercial material lives outside this repository. Do not
commit it and delete it later, because a commit is permanent.

## 9. Enforcement

| Mechanism | Enforces |
|---|---|
| `.github/workflows/dash-guard.yml` | No em dashes or en dashes in any changed text file |
| `.github/workflows/adr-citation-guard.yml` | Every cited ADR exists or is marked pending |
| `.github/workflows/architecture-model.yml` | `docs/workspace.dsl` validates against the pinned CLI and the static site builds |
| `.github/workflows/linked-issue-guard.yml` | Every code or architecture pull request closes an issue |
| `.github/workflows/estimate-guard.yml` | Leaf-only estimates, every item typed, no orphans (runs once the board is configured) |
| `.github/workflows/ci.yml` (ADR-006) | Lint, types, tests, isolation and boundary tests, golden tests, contract snapshot, workflow and script checks |
| `.github/workflows/deploy.yml` (ADR-006) | Images built once on `main` and deployed to staging; version tags retag and deploy to production |
| `.claude/hooks/surgical-change-guard.py` | Blocks tiny throwaway files and bloated edits made by Claude Code |
| Branch protection on `main` | Review required, linear history, no force push, no deletion |
| GitHub Environment `production` | Required reviewer: Hillary |

The Structurizr CLI version is **pinned** (`2024.09.19`). The `latest` tag is a no-op
deprecation stub that exits successfully without validating anything. Never use it.

## 10. Estimation and effort logging

- Put hour estimates on leaf items only (Tasks, Bugs, Chores, Spikes). Stories, Features
  and Epics carry none; the board sums the estimate column flat, so parent numbers
  double-count.
- Each person estimates their own ticket when it is assigned, in whole hours, and logs
  actual hours on it when closing it.

## 11. Traceability chain

Commit, then pull request closing a Task, then Task under a Story, Bug or Spike, then
Story under a Feature, then Feature under an Epic, and Story to FR IDs to tests. Any
commit that cannot be traced up to an Epic is a smell.

## 12. Amendments

Changes to this document are made by pull request and take effect on merge.
