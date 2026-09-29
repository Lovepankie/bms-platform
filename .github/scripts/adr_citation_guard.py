#!/usr/bin/env python3
"""Fail if any ADR citation points at a record that does not exist and is not marked pending.

Two Accepted ADRs once cited ADR-007 as settled fact while it was unwritten, so the
decision log asserted a dependency on a document nobody could read. That is the failure
this guard exists to prevent. An unwritten record may be referenced, but only as pending.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADR_DIR = ROOT / "docs" / "adr"
CITE = re.compile(r"ADR-(\d{3})")

existing = {m.group(1) for f in ADR_DIR.glob("ADR-*.md") if (m := CITE.match(f.name))}
scan = list((ROOT / "docs").rglob("*.md")) + list((ROOT / "docs").rglob("*.dsl"))
scan += [ROOT / "AGENTS.md", ROOT / "README.md"]

failures = []
for f in scan:
    if not f.exists():
        continue
    for i, line in enumerate(f.read_text().splitlines(), 1):
        # a bullet in a Pending list is a declaration, not a citation
        if re.match(r"\s*-\s*ADR-\d{3}\b", line):
            continue
        for num in CITE.findall(line):
            if num in existing:
                continue
            if re.search(rf"[Pp]ending\s+ADR-{num}", line) or re.search(rf"ADR-{num}\s+will\b", line):
                continue
            failures.append(f"{f.relative_to(ROOT)}:{i}: cites ADR-{num}, which does not exist and is not marked pending")

if failures:
    print("ADR citation guard failed:\n")
    for x in failures:
        print(" ", x)
    print("\nAn unwritten ADR may be referenced, but only as 'pending ADR-NNN' or 'ADR-NNN will ...'.")
    sys.exit(1)
print(f"ADR citation guard passed. {len(existing)} records, all citations resolve or are marked pending.")
