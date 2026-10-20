#!/usr/bin/env python3
"""
placeholder_migration_guard: a migration whose version is the placeholder 99 must never reach main.

Flyway runs with outOfOrder off, so a version 99 that was applied on a server makes every migration from V61 to V98 a number below
an applied one, and none of them can ever run. A branch that cannot know the next free number (it is taken at merge, above the
highest one on any open branch) uses V99 as a placeholder and is renumbered by the maintainer before it merges. A renumbered file
is no longer version 99, so there is no escape word and no override: any such file fails, on a pull request, on a push to main and
in the first job of the deploy workflow (which refuses to build or to move the staging pointer).

Version 99 is every spelling Flyway reads as that version, with a dot or an underscore between the parts: V99__x.sql, V099__x.sql,
V99.0__x.sql, V99_0__x.sql and V99.0.0__x.sql (the same
pattern is written in the deploy workflow's shell step, and a test keeps the two equal).

MIGRATIONS may name other directories (separated like a PATH), for the tests of this script (test_placeholder_migration_guard.py).
Every file under them counts, subfolders included.
"""
import os
import pathlib
import re
import sys

PATTERN = r"^V0*99([._]0+)*__"
# Flyway scans db/migration on the classpath, subfolders included: the SQL files under resources and the Java migrations
# (V99__X.java) under src/main/java.
DEFAULT_DIRS = "backend/src/main/resources/db/migration:backend/src/main/java/db/migration"


def main() -> int:
    directories = [pathlib.Path(d) for d in os.environ.get("MIGRATIONS", DEFAULT_DIRS).split(os.pathsep) if d]
    found = sorted(
        str(p) for d in directories if d.is_dir() for p in d.rglob("*") if p.is_file() and re.match(PATTERN, p.name)
    )
    if not found:
        print("No placeholder (version 99) migration. Guard passes.")
        return 0
    for path in found:
        print(f"::error file={path}::placeholder migration version 99. Renumber it to the next free number "
              "before it merges (Flyway runs with outOfOrder off: a version 99 on main blocks every later migration).")
    return 1


if __name__ == "__main__":
    sys.exit(main())
