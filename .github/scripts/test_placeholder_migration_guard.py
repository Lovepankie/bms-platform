#!/usr/bin/env python3
"""Tests of placeholder_migration_guard.py: it has no escape, whatever the event or the pull request text says, and the deploy
workflow's first job holds the same rule and every other job waits for it."""
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "placeholder_migration_guard.py"
DEPLOY = HERE.parent / "workflows" / "deploy.yml"


def run(directory: pathlib.Path, **env: str) -> subprocess.CompletedProcess:
    # Only what the test sets: nothing of the caller's environment (a PR_BODY, an event name) can change the answer.
    clean = {"PATH": os.environ.get("PATH", ""), "MIGRATIONS": str(directory), **env}
    return subprocess.run([sys.executable, str(SCRIPT)], env=clean, capture_output=True, text=True, check=False)


class PlaceholderMigrationGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = pathlib.Path(self._tmp.name)
        for name in ("V1__foundation.sql", "V60__savings_deposit_by_gateway.sql", "V64__next_free_number.sql"):
            (self.dir / name).write_text("-- fabricated\n")

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def test_no_placeholder_passes(self) -> None:
        result = run(self.dir, GITHUB_EVENT_NAME="pull_request")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_a_placeholder_on_a_pull_request_fails(self) -> None:
        (self.dir / "V99__staff_password_reset.sql").write_text("-- placeholder\n")
        result = run(self.dir, GITHUB_EVENT_NAME="pull_request", PR_BODY="A normal description.")
        self.assertEqual(result.returncode, 1)
        self.assertIn("V99__staff_password_reset.sql", result.stdout)

    def test_a_placeholder_on_a_push_to_main_fails(self) -> None:
        (self.dir / "V99__staff_password_reset.sql").write_text("-- placeholder\n")
        result = run(self.dir, GITHUB_EVENT_NAME="push")
        self.assertEqual(result.returncode, 1)

    def test_the_old_escape_word_changes_nothing(self) -> None:
        (self.dir / "V99__staff_password_reset.sql").write_text("-- placeholder\n")
        for body in ("RENUMBERED", "not yet RENUMBERED", "renumbered", "This is RENUMBERED."):
            for event in ("pull_request", "push", ""):
                result = run(self.dir, GITHUB_EVENT_NAME=event, PR_BODY=body)
                self.assertEqual(result.returncode, 1, (body, event))

    def test_every_spelling_flyway_reads_as_version_99_fails(self) -> None:
        for name in (
            "V099__x.sql",
            "V0099__x.sql",
            "V99.0__x.sql",
            "V99.00__x.sql",
            "V099.0__x.sql",
            "V99_0__x.sql",
            "V99.0.0__x.sql",
            "V99_0_0__x.sql",
        ):
            (self.dir / name).write_text("-- placeholder\n")
            self.assertEqual(run(self.dir).returncode, 1, name)
            (self.dir / name).unlink()

    def test_a_placeholder_in_a_subfolder_fails(self) -> None:
        sub = self.dir / "tenant" / "extra"
        sub.mkdir(parents=True)
        (sub / "V99__deep.sql").write_text("-- placeholder\n")
        result = run(self.dir)
        self.assertEqual(result.returncode, 1)
        self.assertIn("V99__deep.sql", result.stdout)

    def test_a_java_migration_with_version_99_fails(self) -> None:
        java = pathlib.Path(self._tmp.name + "-java") / "db" / "migration"
        java.mkdir(parents=True)
        try:
            (java / "V99__Placeholder.java").write_text("// placeholder\n")
            result = run(self.dir, MIGRATIONS=os.pathsep.join([str(self.dir), str(java)]))
            self.assertEqual(result.returncode, 1)
            self.assertIn("V99__Placeholder.java", result.stdout)
            # A folder that does not exist (no Java migrations yet) is no failure.
            missing = run(self.dir, MIGRATIONS=os.pathsep.join([str(self.dir), str(self.dir / "no-such-folder")]))
            self.assertEqual(missing.returncode, 0)
        finally:
            (java / "V99__Placeholder.java").unlink(missing_ok=True)

    def test_only_version_99_is_refused(self) -> None:
        for name in (
            "V990__not_the_placeholder.sql",
            "V9__lending.sql",
            "V99.1__a_later_point_release.sql",
            "V99_1__another_point_release.sql",
            "V199__far_above.sql",
            "V98__just_below.sql",
            "V100__just_above.sql",
        ):
            (self.dir / name).write_text("-- a real one\n")
        self.assertEqual(run(self.dir).returncode, 0)


def deploy_jobs() -> dict[str, str]:
    """The text of each job of deploy.yml, by name, in order (no YAML library: the runner may not have one)."""
    text = DEPLOY.read_text()
    jobs_at = re.search(r"^jobs:\s*$", text, re.M)
    assert jobs_at, "deploy.yml has no jobs:"
    body = text[jobs_at.end():]
    names = list(re.finditer(r"^  ([a-z][a-z0-9-]*):\s*$", body, re.M))
    return {m.group(1): body[m.end(): names[i + 1].start() if i + 1 < len(names) else len(body)] for i, m in enumerate(names)}


def v99_pattern(job_text: str):
    """The pattern the V99 step greps the migration folder with (not the release tag check of the next step)."""
    step = job_text[job_text.index("Refuse a placeholder migration number"): job_text.index("id: tags")]
    return re.search(r"grep -Eq? '([^']+)'", step)


def v99_step_script(job_text: str) -> str:
    """The shell of the V99 step, dedented, to run for real in a scratch folder."""
    step = job_text[job_text.index("Refuse a placeholder migration number"): job_text.index("id: tags")]
    lines = step.split("run: |\n", 1)[1].splitlines()
    return "\n".join(line[10:] for line in lines)


def needs_of(job_text: str) -> list[str]:
    inline = re.search(r"^    needs:\s*\[([^\]]*)\]", job_text, re.M)
    if inline:
        return [n.strip() for n in inline.group(1).split(",") if n.strip()]
    single = re.search(r"^    needs:\s*([a-z][a-z0-9-]*)\s*$", job_text, re.M)
    return [single.group(1)] if single else []


class DeployWorkflowTest(unittest.TestCase):
    def run_step(self, *files: str) -> subprocess.CompletedProcess:
        """Runs the real shell of the deploy workflow's V99 step in a scratch checkout holding the given files."""
        with tempfile.TemporaryDirectory() as root:
            for folder in ("backend/src/main/resources/db/migration", "backend/src/main/java/db/migration"):
                pathlib.Path(root, folder).mkdir(parents=True)
            for name in files:
                target = pathlib.Path(root, name)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("-- fabricated\n")
            return subprocess.run(
                ["bash", "-e", "-o", "pipefail", "-c", v99_step_script(deploy_jobs()["meta"])],
                cwd=root, capture_output=True, text=True, check=False,
            )

    def test_the_real_step_passes_a_clean_tree_and_refuses_every_placeholder(self) -> None:
        base = "backend/src/main/resources/db/migration/"
        java = "backend/src/main/java/db/migration/"
        self.assertEqual(self.run_step(base + "V1__a.sql", base + "V64__b.sql").returncode, 0)
        for path in (
            base + "V99__a.sql",
            base + "V099__a.sql",
            base + "V99_0__a.sql",
            base + "V99.0.0__a.sql",
            base + "sub/folder/V99__a.sql",
            java + "V99__Placeholder.java",
        ):
            self.assertEqual(self.run_step(path).returncode, 1, path)
        for path in (base + "V990__a.sql", base + "V99.1__a.sql", java + "V100__Real.java"):
            self.assertEqual(self.run_step(path).returncode, 0, path)

    def test_the_first_job_holds_the_v99_step_with_the_same_pattern_as_the_script(self) -> None:
        jobs = deploy_jobs()
        first = next(iter(jobs))
        self.assertEqual(first, "meta")
        self.assertIn("migration", jobs["meta"])
        pattern = v99_pattern(jobs["meta"])
        self.assertIsNotNone(pattern, "the meta job has no V99 step")
        sys.path.insert(0, str(HERE))
        import placeholder_migration_guard

        self.assertEqual(pattern.group(1), placeholder_migration_guard.PATTERN)
        self.assertRegex(jobs["meta"], r"exit 1")

    def test_the_step_comes_before_anything_else_in_the_job_runs(self) -> None:
        meta = deploy_jobs()["meta"]
        self.assertLess(meta.index("Refuse a placeholder migration number"), meta.index("id: tags"))

    def test_every_other_job_reaches_meta_through_needs(self) -> None:
        jobs = deploy_jobs()
        graph = {name: needs_of(text) for name, text in jobs.items()}

        def reaches_meta(name: str, seen: frozenset = frozenset()) -> bool:
            if name == "meta":
                return True
            return name not in seen and any(reaches_meta(n, seen | {name}) for n in graph.get(name, []))

        for name in jobs:
            if name != "meta":
                self.assertTrue(reaches_meta(name), f"job {name} does not wait for meta (needs: {graph[name]})")

    def test_the_deploy_step_matches_the_spellings_with_the_real_shell(self) -> None:
        pattern = v99_pattern(deploy_jobs()["meta"]).group(1)
        for name, expected in (
            ("V99__a.sql", True),
            ("V099__a.sql", True),
            ("V99.0__a.sql", True),
            ("V99_0__a.sql", True),
            ("V99.0.0__a.sql", True),
            ("V199__a.sql", False),
            ("V99_1__a.sql", False),
            ("V64__a.sql", False),
            ("V990__a.sql", False),
            ("V99.1__a.sql", False),
        ):
            result = subprocess.run(["grep", "-Eq", pattern], input=name + "\n", text=True, check=False)
            self.assertEqual(result.returncode == 0, expected, name)


if __name__ == "__main__":
    unittest.main()
