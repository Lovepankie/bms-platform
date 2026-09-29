# CLAUDE.md

Read `AGENTS.md` first and follow it. It is the single onboarding brief for every coding
agent in this repository; this file only adds what is specific to Claude Code.

- **No em dashes or en dashes** in anything you write, including commit messages and
  code comments. Check with `grep -rnP '[\x{2014}\x{2013}]' .` before you finish.
- **Docs in the same change.** When you change behaviour, update the SDD chapter, the data
  model, the endpoint catalogue, the permission matrix or the Structurizr model in the same
  branch. If you add a decision, write the ADR; cite unwritten ADRs only as
  "pending ADR-NNN". Run `python3 .github/scripts/adr_citation_guard.py` and validate
  `docs/workspace.dsl` (command in `README.md`) before you finish.
- **Backend framework is pending ADR-010.** Do not extend `backend/` until it is accepted.
- **Never** put real client, borrower or staff data, real phone or ID numbers, or real
  figures in code, fixtures, tests, docs or commit messages. Fabricate them.
- `.claude/settings.json` denies edits to `.env` files and secrets, `rm -rf`, and global
  git config changes. `.claude/hooks/surgical-change-guard.py` blocks tiny new utility
  files and bloated edits; if it blocks you, make the smaller change, or add the
  justification line it asks for when the change really is minimal.
- Commit only when asked. Pull requests close a Task or Chore (`PROCESS.md`).
