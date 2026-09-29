# BMS Platform System Design Document

The canonical technical reference for BMS Platform, a multi-tenant business management
system built as a platform core plus vertical modules, with lending as the first vertical.
The model in `docs/workspace.dsl` is the structure; these chapters are the prose; `docs/adr/`
is the decision log. Structurizr renders all three as one browsable site.

Each chapter is a separate markdown file and is the source of truth for its area. A change
to behaviour updates the chapter in the same pull request (ADR-005).

## Chapters

| # | Chapter | Owner | Status |
|---|---|---|---|
| 1 | [Introduction](01-introduction.md) | Hillary | Draft |
| 2 | [System Context](02-system-context.md) | Hillary | Draft |
| 3 | [Functional Requirements](03-functional-requirements.md) | Hillary | Draft |
| 4 | [Non-Functional Requirements](04-non-functional-requirements.md) | Hillary | Draft |
| 5 | [Architecture](05-architecture.md) | Hillary | Draft |
| 6 | [Database Design](06-database-design.md) | Hillary | Draft |
| 7 | [API Design](07-api-design.md) | Hillary | Draft |
| 8 | [Security Design](08-security-design.md) | Hillary | Draft |
| 9 | [Infrastructure Design](09-infrastructure-design.md) | Hillary | Draft |
| 10 | [CI/CD Pipeline](10-cicd-pipeline.md) | Hillary | Draft |
| 11 | [Member Channels](11-member-channels.md) | Hillary | Draft |
| 12 | [Integration Design](12-integration-design.md) | Hillary | Draft |
| 13 | [Data Migration and Import](13-data-migration-and-import.md) | Hillary | Draft |
| 14 | [Reporting](14-reporting.md) | Hillary | Draft |
| 15 | [Test Strategy](15-test-strategy.md) | Hillary | Draft |

Chapter ownership moves to the developer building the area when a sprint starts on it;
the owner keeps the chapter true to the code.

## Architecture Decision Records

Chapters describe what the system is; ADRs record why, and they are the authority where
the two disagree. The list of accepted and pending records is in `AGENTS.md`.

## How to read this document

- **First time:** chapters 1, 2 and 5, then `docs/specs/lending-mvp-scope.md`.
- **Building a feature:** find its FR IDs in chapter 3, its tables in chapter 6, its
  endpoints in chapter 7, its permissions in chapter 8, its tests in chapter 15.
- **Money correctness:** chapter 3 section 3.4, chapter 6 section 6.6, chapter 14,
  ADR-004.
- **Isolation and security:** ADR-003, chapter 6 section 6.3, chapter 8, chapter 15
  section 15.4.
- **Running and releasing it:** ADR-006, ADR-008, ADR-010, chapters 9 and 10,
  `docs/runbooks/`.
- **Pilot data:** chapter 13 and `docs/specs/pilot-data-dictionary.md`.
