#!/usr/bin/env python3
"""Decides whether a CI job does its work (issue #549). Branch protection requires the Backend, Frontend and
"Workflows, scripts and compose files" checks, so ci.yml has no paths-ignore: a workflow it skipped would leave those
checks unreported and the pull request blocked for ever. Each job runs this first instead. On a pull request whose
merge commit changes only prose (docs/specs, docs/runbooks, docs/ui, CONTEXT.md) it writes run=false to
GITHUB_OUTPUT, the job skips its other steps and reports success; anything else, or any other event, writes run=true.
The job checks out with fetch-depth 2, so HEAD^1 is the base the pull request is merged onto."""
import os
import re
import subprocess

# The folders the old paths-ignore listed. docs/sdd, docs/api, docs/adr and AGENTS.md are NOT prose here: tests read
# them (permission matrix, OpenAPI snapshot, ADR index).
PROSE = re.compile(r"^(docs/(specs|runbooks|ui)/|CONTEXT\.md$)")


def prose_only(files: list[str]) -> bool:
    return bool(files) and all(PROSE.match(f) for f in files)


def main() -> None:
    run = True
    if os.environ.get("GITHUB_EVENT_NAME") == "pull_request":
        # --no-renames lists both sides of a move, so code moved into a prose folder still runs the job.
        changed = subprocess.run(
            ["git", "-c", "diff.renames=false", "diff", "--no-renames", "--name-only", "HEAD^1", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
        if prose_only(changed):
            run = False
            print("::notice::only prose changed (docs/specs, docs/runbooks, docs/ui, CONTEXT.md): "
                  "this job reports success without running (issue #549)")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as out:
        out.write(f"run={'true' if run else 'false'}\n")


if __name__ == "__main__":
    main()
