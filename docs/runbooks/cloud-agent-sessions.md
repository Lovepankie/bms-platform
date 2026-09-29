# Runbook: Claude cloud agent sessions

Work on this repository can run as a Claude Code session in Anthropic's cloud instead
of on a developer's machine. The session gets a fresh clone of the repository, works
on a branch, and hands back a branch or pull request for review like any other
contributor. Nothing about the review, CI or merge rules changes.

## What the cloud sandbox provides

- A clone of this repository at the default branch.
- Maven 3.9, Docker (so Testcontainers integration tests run), Node, 4 CPUs, about 15 GiB of memory.
- OpenJDK 21 only. `.claude/hooks/cloud-setup.sh` runs at session start, detects a
  cloud session (`CLAUDE_CODE_REMOTE=true`), installs `openjdk-25-jdk-headless` from the
  Ubuntu archive, exports `JAVA_HOME` and `PATH` for the rest of the session, and starts
  the Docker daemon for Testcontainers. On a developer machine the hook does nothing.
- Outbound network through an egress proxy with an allow list. Verified 2026-09-29:
  allowed are Maven Central, the Ubuntu archive, Docker Hub, npm and git access to this
  repository; blocked are Adoptium, download.java.net and GitHub release downloads.

## Writing a task for a cloud session

The session starts with no context beyond this repository, so the task must be
self-contained:

1. Name the issue it closes and the branch to create (`feat/<issue>-<slug>`).
2. Point at the specification: the FR and NFR IDs in `docs/sdd/03` and `docs/sdd/04`,
   the increment in `docs/specs/lending-mvp-scope.md`, and the reference slice
   (`lending.members`) to copy.
3. State the definition of done from `AGENTS.md`: tests, docs in the same change,
   `mvn verify` green, guards green.
4. Say what it may do: commit and push its branch, open a pull request that links the
   issue. It never merges, never pushes to `main`, never tags.

## Checking the result

The session's transcript is visible to the person who launched it at
claude.ai/code. The pull request it opens goes through the normal review in
`PROCESS.md`, and CI runs on it as on any pull request.

## Content boundary

The cloud session sees only what is in this repository. Real client material stays
out of the repository (see `AGENTS.md`), so it never reaches a cloud session either.
