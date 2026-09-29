#!/usr/bin/env python3
"""
estimate_guard: enforce the delivery-tracking rules on the org Projects v2 board
(PROCESS.md). It guards three things:

1. LEAF-ONLY NUMBERS (hard fail). Parent-type issues (epic / feature / story)
   must carry neither Estimate (h) nor Actual (h): those roll up from the leaf
   sub-issues. A number on a parent double-counts the board total.
2. EVERY ITEM TYPED (see STRICT). Each issue on the board must carry exactly one
   type label: epic / feature / story / task / bug / chore / spike. Untyped items
   are invisible to the hierarchy.
3. NO ORPHANS (see STRICT). Every non-epic must have a parent (feature/story/leaf
   sit under something). Epics are the roots.

Rule 1 always fails the run. Rules 2 and 3 are warnings until STRICT is turned on
(flip after the backlog is fully typed and parented), then they fail too. A leaf
with no estimate is always a soft warning so the timesheet stays complete.

Requires GH_TOKEN with read:project + repo scope (PROJECT_TOKEN secret in CI).
Config via env: ORG (required), PROJECT (required), ESTIMATE_FIELD, ACTUAL_FIELD,
STRICT ("1" to make typing/parenting fatal).
"""
import json
import os
import subprocess
import sys

ORG = os.environ.get("ORG") or sys.exit("ORG is required; refusing to guess an org")
PROJECT = int(os.environ.get("PROJECT", "2"))
ESTIMATE_FIELD = os.environ.get("ESTIMATE_FIELD", "Estimate (h)")
ACTUAL_FIELD = os.environ.get("ACTUAL_FIELD", "Actual (h)")
STRICT = os.environ.get("STRICT", "0") == "1"

PARENT_LABELS = {"epic", "feature", "story"}
LEAF_LABELS = {"task", "bug", "chore", "spike"}
# "meeting" is a valid type: a meeting-with-children is a parent (rolls up),
# a per-attendee meeting item is a leaf (carries the person's hours).
TYPE_LABELS = PARENT_LABELS | LEAF_LABELS | {"meeting"}

QUERY = """
query($org: String!, $num: Int!, $cursor: String) {
  organization(login: $org) {
    projectV2(number: $num) {
      title
      items(first: 100, after: $cursor) {
        pageInfo { hasNextPage endCursor }
        nodes {
          content {
            __typename
            ... on Issue {
              number
              title
              state
              labels(first: 20) { nodes { name } }
              parent { number }
              subIssues(first: 1) { totalCount }
            }
          }
          fieldValues(first: 30) {
            nodes {
              __typename
              ... on ProjectV2ItemFieldNumberValue {
                number
                field { ... on ProjectV2FieldCommon { name } }
              }
            }
          }
        }
      }
    }
  }
}
"""


def gh_graphql(cursor):
    args = ["gh", "api", "graphql", "-f", "query=" + QUERY,
            "-F", "org=" + ORG, "-F", "num=%d" % PROJECT]
    if cursor:
        args += ["-F", "cursor=" + cursor]
    r = subprocess.run(args, capture_output=True, text=True)
    if r.returncode != 0:
        sys.stderr.write("GraphQL error:\n" + r.stderr + "\n")
        sys.exit(2)
    return json.loads(r.stdout)


def fetch_items():
    items, cursor = [], None
    while True:
        page = gh_graphql(cursor)["data"]["organization"]["projectV2"]["items"]
        items.extend(page["nodes"])
        if page["pageInfo"]["hasNextPage"]:
            cursor = page["pageInfo"]["endCursor"]
        else:
            break
    return items


def number_field(item, name):
    for fv in item.get("fieldValues", {}).get("nodes", []):
        if fv.get("__typename") == "ProjectV2ItemFieldNumberValue":
            if (fv.get("field") or {}).get("name") == name:
                return fv.get("number")
    return None


def main():
    items = fetch_items()
    parent_numbers = []   # (num, kind, est, act, title)  HARD
    untyped = []          # (num, title)
    orphans = []          # (num, kind, title)
    missing_est = []      # (num, kind, title)  soft

    for it in items:
        c = it.get("content") or {}
        if c.get("__typename") != "Issue":
            continue
        num = c.get("number")
        title = (c.get("title") or "")[:58]
        state = c.get("state")
        labels = {l["name"] for l in c.get("labels", {}).get("nodes", [])}
        kinds = labels & TYPE_LABELS
        est = number_field(it, ESTIMATE_FIELD)
        act = number_field(it, ACTUAL_FIELD)
        has_parent = bool(c.get("parent"))
        kids = (c.get("subIssues") or {}).get("totalCount", 0)

        if not kinds:
            untyped.append((num, title))
            continue
        kind = next(iter(kinds))
        # A parent is anything labelled epic/feature/story OR anything with
        # children (covers meeting parents). Parents must carry no numbers.
        is_parent = bool(labels & PARENT_LABELS) or kids > 0
        if is_parent and (est is not None or act is not None):
            pkind = next(iter(labels & PARENT_LABELS), kind)
            parent_numbers.append((num, pkind, est, act, title))
        if kind != "epic" and not has_parent:
            orphans.append((num, kind, title))
        if kind in LEAF_LABELS and est is None and state == "OPEN":
            missing_est.append((num, kind, title))

    out = ["# Board guard (PROCESS.md delivery tracking)\n"]

    def table(header, rows, cols):
        out.append("## " + header + "\n")
        out.append("| " + " | ".join(cols) + " |")
        out.append("|" + "|".join(["---"] * len(cols)) + "|")
        for r in rows:
            out.append("| " + " | ".join(str(x) for x in r) + " |")
        out.append("")

    if parent_numbers:
        table("HARD: parent items carrying Estimate/Actual (must be cleared)",
              [("#%d" % n, k, "" if e is None else e, "" if a is None else a, t)
               for n, k, e, a, t in parent_numbers],
              ["Issue", "Type", "Estimate", "Actual", "Title"])
    else:
        out.append("Leaf-only numbers holding: no estimate/actual on any parent.\n")

    sev = "HARD" if STRICT else "WARN"
    if untyped:
        table("%s: items with no type label (epic/feature/story/task/...)" % sev,
              [("#%d" % n, t) for n, t in untyped], ["Issue", "Title"])
    if orphans:
        table("%s: non-epic items with no parent (orphans)" % sev,
              [("#%d" % n, k, t) for n, k, t in orphans], ["Issue", "Type", "Title"])
    if missing_est:
        table("WARN: open leaf items with no estimate",
              [("#%d" % n, k, t) for n, k, t in missing_est], ["Issue", "Type", "Title"])

    report = "\n".join(out)
    summ = os.environ.get("GITHUB_STEP_SUMMARY")
    if summ:
        with open(summ, "a") as f:
            f.write(report + "\n")
    print(report)

    fatal = len(parent_numbers) + (len(untyped) + len(orphans) if STRICT else 0)
    if fatal:
        msg = "FAIL: %d parent(s) with numbers" % len(parent_numbers)
        if STRICT:
            msg += ", %d untyped, %d orphan" % (len(untyped), len(orphans))
        print(msg + ".")
        sys.exit(1)
    extra = ""
    if untyped or orphans:
        extra = " (%d untyped, %d orphan still to clean, warnings)" % (len(untyped), len(orphans))
    print("PASS: leaf-only numbers holding." + extra)
    sys.exit(0)


if __name__ == "__main__":
    main()
