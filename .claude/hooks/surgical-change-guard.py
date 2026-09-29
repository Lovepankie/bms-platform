#!/usr/bin/env python3
"""
Surgical Change Guard  (PreToolUse: Write | Edit | MultiEdit)

Enforces "smallest change wins" + "check the existing mechanism before adding
new" (DRY) as a hard, tool-level rule across every project. Adapted from the
pattern-bridge pattern `surgical-change-hook-with-mechanism-check`.

Two hard blocks, both with a named escape hatch:

  A2  tiny NEW file in a utility path (scripts/ bin/ helpers/ utils/ lib/).
      Usually the code should be inlined into the caller.
      Bypass: put  JUSTIFICATION-A2: <reason>  anywhere in the file content.

  A3  bloated edit: replacing a small anchor with a much larger block, which
      usually means wrapping new mechanism around a tiny change instead of
      making the change.
      Bypass: put  JUSTIFICATION-A3: <reason>  in the new_string.

Deliberately conservative so it fires rarely and precisely:
  - Only CODE files (not .md / .txt / .json data / docs).
  - A2 only when the file does not already exist (a genuine new file).
  - A3 only when the expansion is both a high ratio AND large in absolute size.

Exit 2 blocks the write and feeds the message back to the model. Any parsing
problem exits 0 (fail-open): a guard must never wedge the toolchain.
"""

import sys, json, os, re

CODE_EXT = {
    '.py', '.sh', '.bash', '.js', '.jsx', '.ts', '.tsx', '.go', '.rb', '.rs',
    '.java', '.kt', '.c', '.h', '.cpp', '.hpp', '.cs', '.php', '.pl', '.lua',
    '.yml', '.yaml',  # CI / pipeline shape counts as code
}
UTILITY_DIR = re.compile(r'(^|/)(scripts|bin|helpers|utils|lib)/')

# A2: new utility file smaller than this many non-blank lines is suspect.
A2_MAX_LINES = 10
# A3: block only when replacement/anchor ratio AND absolute size both exceed these.
A3_RATIO = 4.0
A3_MIN_NEW_LINES = 20


def nonblank(s: str) -> int:
    return sum(1 for ln in s.splitlines() if ln.strip())


def is_code(path: str) -> bool:
    return os.path.splitext(path)[1].lower() in CODE_EXT


def block(code: str, msg: str):
    sys.stderr.write(
        f"BLOCKED by surgical-change-guard [{code}].\n{msg}\n\n"
        f"Before proceeding, answer:\n"
        f"  1. Does existing code already carry this value or behaviour?\n"
        f"  2. Is this the smallest change that works?\n"
        f"If this is genuinely the minimal change, add "
        f"JUSTIFICATION-{code}: <one-line reason> inside the content and re-run.\n"
    )
    sys.exit(2)


def main():
    try:
        data = json.load(sys.stdin)
    except Exception:
        sys.exit(0)  # fail-open

    tool = data.get('tool_name', '')
    ti = data.get('tool_input', {}) or {}
    path = ti.get('file_path', '') or ''

    if not path or not is_code(path):
        sys.exit(0)

    # ---- A2: tiny new file in a utility path ----
    if tool == 'Write':
        content = ti.get('content', '') or ''
        if (UTILITY_DIR.search(path)
                and not os.path.exists(path)
                and nonblank(content) < A2_MAX_LINES
                and 'JUSTIFICATION-A2' not in content):
            block('A2',
                  f"New file `{path}` has {nonblank(content)} non-blank lines in a "
                  f"utility path. A file this small can usually be inlined into its "
                  f"one caller instead of becoming a new module.")
        sys.exit(0)

    # ---- A3: bloated edit ----
    edits = []
    if tool == 'Edit':
        edits = [(ti.get('old_string', '') or '', ti.get('new_string', '') or '')]
    elif tool == 'MultiEdit':
        edits = [(e.get('old_string', '') or '', e.get('new_string', '') or '')
                 for e in (ti.get('edits', []) or [])]

    for old, new in edits:
        if 'JUSTIFICATION-A3' in new:
            continue
        old_n, new_n = nonblank(old), nonblank(new)
        if new_n >= A3_MIN_NEW_LINES and new_n >= A3_RATIO * max(old_n, 1):
            block('A3',
                  f"Edit to `{path}` replaces a {old_n}-line anchor with {new_n} "
                  f"lines (ratio {new_n / max(old_n, 1):.0f}x). That shape usually "
                  f"means new mechanism is being wrapped around a small change. "
                  f"Consider whether the anchor's actual change is smaller, or "
                  f"whether this belongs in an existing function.")

    sys.exit(0)


if __name__ == '__main__':
    main()
