#!/usr/bin/env python3
"""
tour_guard: a pull request that adds or renames a staff route must also change the spotlight tours (issue #337, ADR-049).

Every route has a page tour, breadcrumbs and cross links (ADR-049). The tours are data in frontend/src/tour/ (ADR-025), so the cheap
and checkable part of the rule is: a change that declares a new route, or changes the path of one, touches a file under
frontend/src/tour/ in the same pull request (a new tour, or a bumped version of an existing one).

A route is declared by one of these lines in a non-test file under frontend/src/areas (createLazyRoute, createFileRoute) or in
frontend/src/app/router.tsx (createRoute, retailScreen, lendingScreen). An ADDED line of that shape is an added route; a renamed
route changes its path string, so it is an added line too. A line that is only removed is a deleted route and needs no tour.

The one escape is a line in the pull request body: `TOUR-EXEMPT: <reason>` with a reason of at least three characters (a redirect,
a screen with no controls of its own, a route that only re-exports another). It is read from PR_BODY and is the only override.

Inputs (environment): BASE_SHA and HEAD_SHA of the pull request, PR_BODY its text. The tests (test_tour_guard.py) run it in a
scratch repository.
"""
from __future__ import annotations

import os
import re
import subprocess
import sys

AREAS = "frontend/src/areas/"
ROUTER = "frontend/src/app/router.tsx"
TOURS = "frontend/src/tour/"
ROUTE_LINE = re.compile(r"\b(createLazyRoute|createFileRoute|createRoute|retailScreen|lendingScreen)\s*\(")
EXEMPT = re.compile(r"^[ \t]*TOUR-EXEMPT:[ \t]*(\S.{2,})$", re.MULTILINE)
TEST_FILE = re.compile(r"\.(test|spec)\.[jt]sx?$")


def is_route_file(path: str) -> bool:
    if TEST_FILE.search(path):
        return False
    return (path.startswith(AREAS) and path.endswith((".ts", ".tsx"))) or path == ROUTER


def route_lines(diff: str) -> list[str]:
    """The added lines that declare a route, as `path: line`, from a zero-context unified diff."""
    found: list[str] = []
    current = ""
    for line in diff.splitlines():
        if line.startswith("+++ "):
            current = line[4:].removeprefix("b/")
        elif line.startswith("+") and is_route_file(current) and ROUTE_LINE.search(line[1:]):
            found.append(f"{current}: {line[1:].strip()}")
    return found


def tour_changed(names: list[str]) -> bool:
    return any(n.startswith(TOURS) for n in names)


def exemption(body: str) -> str | None:
    match = EXEMPT.search((body or "").replace("\r", ""))
    return match.group(1).strip() if match else None


def git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def main() -> int:
    base, head = os.environ.get("BASE_SHA", ""), os.environ.get("HEAD_SHA", "")
    if not base or not head:
        print("::error::BASE_SHA and HEAD_SHA are required")
        return 2
    diff = git("diff", "--unified=0", "--no-color", f"{base}...{head}", "--", AREAS, ROUTER)
    routes = route_lines(diff)
    if not routes:
        print("No route added or renamed. Guard passes.")
        return 0
    names = git("diff", "--name-only", f"{base}...{head}").splitlines()
    if tour_changed(names):
        print(f"{len(routes)} route line(s) added and frontend/src/tour/ changed. Guard passes.")
        return 0
    reason = exemption(os.environ.get("PR_BODY", ""))
    if reason:
        print(f"TOUR-EXEMPT given: {reason}. Guard passes.")
        return 0
    for route in routes:
        print(f"::error::route added or renamed without a tour change: {route}")
    print("::error::Every route has a page tour (ADR-049). Add or update its tour under frontend/src/tour/ (bump the version of a tour you "
          "change), or put 'TOUR-EXEMPT: <reason>' on a line of the pull request body.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
