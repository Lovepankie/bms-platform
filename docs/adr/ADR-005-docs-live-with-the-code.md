# ADR-005: Documentation lives with the code

## Status

Accepted (2026-09-29)

## Context

Two developers will build this platform with coding agents. An agent builds what the
documents in front of it say. If the specification lives in a separate repository, a
shared drive or someone's head, the agent builds from a stale or missing picture, and
the code and the design drift apart within weeks.

Other projects in this organisation keep the System Design Document, the decision log
and the Structurizr model in the same repository as the code, guarded by CI checks.
That arrangement has held up: reviews see the design change and the code change
together.

## Decision

- All design documentation lives in this repository under `docs/`: the Structurizr model
  (`docs/workspace.dsl`), the System Design Document (`docs/sdd/`), the decision log
  (`docs/adr/`), runbooks, specifications, API contract snapshots, diagrams and meeting
  notes. There is no separate documentation repository.
- `AGENTS.md` at the repository root is the entry point for people and coding agents. It
  names the module map, the rules and where each module's specification lives.
- A change to behaviour and the change to the document that describes it land in the
  **same pull request**. A pull request that changes an endpoint, a table, a posting rule
  or a permission without updating the corresponding SDD chapter is not done.
- The Structurizr model wires in the SDD chapters and the ADRs, so the model, the prose
  and the decisions render as one browsable site. The model is validated in CI with a
  pinned CLI version (`structurizr/cli:2024.09.19`); the `latest` tag is a no-op stub and
  is never used.
- CI guards, all present from the first commit:
  - `dash-guard`: no em dashes or en dashes in any changed text file.
  - `adr-citation-guard`: every ADR cited in `docs/`, `AGENTS.md` or `README.md` exists,
    or is written as "pending ADR-NNN".
  - `architecture-model`: the workspace validates and the static site builds.
  - `linked-issue-guard`: every code or architecture pull request closes an issue.
- ADRs are never rewritten after merge. A changed decision is a new ADR that supersedes
  the old one, and both Status blocks record it.
- The repository is technical only. Commercial terms and anything naming a client, a
  borrower or a real individual (other than the team in `AGENTS.md`) live outside it.

## Consequences

**Better:**

- A developer or an agent cloning the repository has the whole design in hand.
- Reviews see design and code together, so drift is caught at the pull request.
- The decision log explains why the system is the way it is, in the same history as the
  code.

**Worse:**

- Every feature pull request carries documentation work, which feels slower.
- Guards occasionally block a pull request for a reason that looks trivial (a stray
  dash).

**Watch for:**

- Chapters going stale because a change was judged "too small" to document. The
  definition of done in `AGENTS.md` makes no exception for size.
- The model and the chapters disagreeing. Where they do, the ADR is the authority, and
  the model and chapter are corrected in the next pull request.

## Related ADRs

- ADR-001 and ADR-002 are the decisions most chapters elaborate.
