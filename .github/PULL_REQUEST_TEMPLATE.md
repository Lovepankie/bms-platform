<!--
Follow PROCESS.md section 6. One pull request closes one Task or Chore.
Delete the sections that do not apply.
-->

## Linked issue

Closes #<!-- task or chore number -->

## Requirements delivered

<!-- FR and NFR IDs from docs/sdd/03 and 04, for example FR-REP-01, FR-REP-02. -->

## Summary

<!-- One to three bullet points: what changed and why. -->

## Definition of done

- [ ] Acceptance criteria of the cited FR and NFR IDs are proven by tests
- [ ] CI is green (tests on real PostgreSQL, isolation and boundary tests, guards)
- [ ] Docs updated in this pull request: SDD chapter, data model, endpoint catalogue, permission matrix, `docs/workspace.dsl`, ADR (whichever apply)
- [ ] Migrations are expand and contract
- [ ] No em dashes or en dashes; no real client or borrower data; no secrets
- [ ] Commit messages follow PROCESS.md section 5; branch follows section 4

## Reviewer

<!-- @-mention the reviewer. Default: @arindahills for docs and architecture. -->

## Notes for the reviewer

<!-- Optional: what to look at first, trade-offs, open questions. -->
