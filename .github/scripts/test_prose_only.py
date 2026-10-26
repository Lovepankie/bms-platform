#!/usr/bin/env python3
"""Tests of prose_only.py: it runs against a scratch git repository whose HEAD is a merge commit, as a pull request's
checkout is, so the diff it reads is a real one. Only a prose-only pull request skips; a push always runs."""
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "prose_only.py"


class ProseOnlyTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = pathlib.Path(self._tmp.name)
        self.env = {
            "PATH": os.environ.get("PATH", ""),
            "HOME": self._tmp.name,
            "GIT_AUTHOR_NAME": "Test Author",
            "GIT_AUTHOR_EMAIL": "author@example.invalid",
            "GIT_COMMITTER_NAME": "Test Author",
            "GIT_COMMITTER_EMAIL": "author@example.invalid",
        }
        self.git("init", "-q", "-b", "main")
        self.write("README.md", "base\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "base")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def git(self, *args: str) -> None:
        subprocess.run(["git", *args], cwd=self.repo, env=self.env, check=True)

    def write(self, path: str, text: str) -> None:
        f = self.repo / path
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text)

    def merge_pr(self, *paths: str) -> None:
        """A pull request branch that changes these paths, merged onto a main that moved on meanwhile."""
        self.git("checkout", "-q", "-b", "pr")
        for p in paths:
            self.write(p, "changed\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "pr")
        self.git("checkout", "-q", "main")
        self.write("backend/Moved.java", "main moved on\n")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "main")
        self.git("merge", "-q", "--no-ff", "-m", "merge", "pr")

    def run_script(self, event: str) -> str:
        out = self.repo / "github_output"
        out.write_text("")
        env = dict(self.env, GITHUB_EVENT_NAME=event, GITHUB_OUTPUT=str(out))
        subprocess.run([sys.executable, str(SCRIPT)], cwd=self.repo, env=env, check=True, capture_output=True)
        return out.read_text().strip()

    def test_a_pull_request_of_specs_runbooks_ui_and_context_only_skips(self) -> None:
        self.merge_pr("docs/specs/a.md", "docs/runbooks/b.md", "docs/ui/c.png", "CONTEXT.md")
        self.assertEqual(self.run_script("pull_request"), "run=false")

    def test_a_pull_request_with_one_other_file_runs(self) -> None:
        self.merge_pr("docs/specs/a.md", "AGENTS.md")
        self.assertEqual(self.run_script("pull_request"), "run=true")

    def test_docs_read_by_tests_are_not_prose(self) -> None:
        for path in ("docs/sdd/08-security.md", "docs/adr/ADR-001-x.md", "docs/api/openapi.json", "docs/specsx/a.md"):
            with self.subTest(path=path):
                self.tearDown()
                self.setUp()
                self.merge_pr(path)
                self.assertEqual(self.run_script("pull_request"), "run=true")

    def test_code_moved_into_a_prose_folder_runs(self) -> None:
        self.write("backend/X.java", "class X {}\n" * 20)
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "code")
        self.git("checkout", "-q", "-b", "pr")
        (self.repo / "docs/specs").mkdir(parents=True)
        self.git("mv", "backend/X.java", "docs/specs/X.java")
        self.git("commit", "-q", "-m", "move")
        self.git("checkout", "-q", "main")
        self.git("merge", "-q", "--no-ff", "-m", "merge", "pr")
        self.assertEqual(self.run_script("pull_request"), "run=true")

    def test_a_push_to_main_always_runs(self) -> None:
        self.merge_pr("docs/specs/a.md")
        self.assertEqual(self.run_script("push"), "run=true")
        self.assertEqual(self.run_script("workflow_dispatch"), "run=true")


if __name__ == "__main__":
    unittest.main()
