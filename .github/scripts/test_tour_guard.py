#!/usr/bin/env python3
"""Tests of tour_guard.py: it runs against a scratch git repository with a base commit and a head commit, so the diff it reads is
a real one. A new or renamed route needs a change under frontend/src/tour/ or a TOUR-EXEMPT line; nothing else does."""
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
SCRIPT = HERE / "tour_guard.py"
sys.path.insert(0, str(HERE))
import tour_guard  # noqa: E402

PAGE = "export const Route = createLazyRoute('/staff/retail/stock')({ component: StockPage });\n"
ROUTER = "const a = retailScreen('/stock', 'Stock').lazy(() => import('../areas/staff/retail/stock'));\n"


class TourGuardTest(unittest.TestCase):
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
        self.write("frontend/src/areas/staff/retail/stock.tsx", PAGE)
        self.write("frontend/src/app/router.tsx", ROUTER)
        self.write("frontend/src/tour/tours.ts", "export const TOURS = [];\n")
        self.commit("base")
        self.base = self.git("rev-parse", "HEAD").strip()

    def tearDown(self) -> None:
        self._tmp.cleanup()

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.repo, env=self.env, capture_output=True, text=True, check=True).stdout

    def write(self, name: str, text: str) -> None:
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)

    def commit(self, message: str) -> None:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)

    def run_guard(self, body: str = "") -> subprocess.CompletedProcess:
        head = self.git("rev-parse", "HEAD").strip()
        env = {**self.env, "BASE_SHA": self.base, "HEAD_SHA": head, "PR_BODY": body}
        return subprocess.run([sys.executable, str(SCRIPT)], cwd=self.repo, env=env, capture_output=True, text=True, check=False)

    def test_no_route_change_passes(self) -> None:
        self.write("frontend/src/areas/staff/retail/stock.tsx", PAGE + "// a comment\n")
        self.commit("tweak")
        self.assertEqual(self.run_guard().returncode, 0)

    def test_new_route_without_a_tour_fails(self) -> None:
        self.write("frontend/src/areas/staff/retail/usage.tsx", PAGE.replace("stock", "usage"))
        self.commit("add a page")
        result = self.run_guard()
        self.assertEqual(result.returncode, 1)
        self.assertIn("usage.tsx", result.stdout)

    def test_renamed_route_without_a_tour_fails(self) -> None:
        self.write("frontend/src/app/router.tsx", ROUTER.replace("/stock", "/stock-list"))
        self.commit("rename a route")
        self.assertEqual(self.run_guard().returncode, 1)

    def test_new_route_with_a_tour_change_passes(self) -> None:
        self.write("frontend/src/areas/staff/retail/usage.tsx", PAGE.replace("stock", "usage"))
        self.write("frontend/src/tour/tours-retail-reports.ts", "export const X = [];\n")
        self.commit("add a page and its tour")
        self.assertEqual(self.run_guard().returncode, 0)

    def test_exempt_line_with_a_reason_passes(self) -> None:
        self.write("frontend/src/areas/staff/retail/usage.tsx", PAGE.replace("stock", "usage"))
        self.commit("add a redirect")
        self.assertEqual(self.run_guard("Closes #1\n\nTOUR-EXEMPT: a redirect with no controls\n").returncode, 0)

    def test_exempt_line_without_a_reason_fails(self) -> None:
        self.write("frontend/src/areas/staff/retail/usage.tsx", PAGE.replace("stock", "usage"))
        self.commit("add a page")
        self.assertEqual(self.run_guard("TOUR-EXEMPT:\n").returncode, 1)
        self.assertEqual(self.run_guard("TOUR-EXEMPT: no\n").returncode, 1)

    def test_exempt_must_start_its_line(self) -> None:
        self.write("frontend/src/areas/staff/retail/usage.tsx", PAGE.replace("stock", "usage"))
        self.commit("add a page")
        self.assertEqual(self.run_guard("we will not say TOUR-EXEMPT: later maybe\n").returncode, 1)

    def test_a_test_file_declaring_a_route_is_not_a_route(self) -> None:
        self.write("frontend/src/areas/staff/retail/stock.dom.test.tsx", "const r = createRoute({ path: '/x' });\n")
        self.commit("add a test")
        self.assertEqual(self.run_guard().returncode, 0)

    def test_a_removed_route_needs_no_tour(self) -> None:
        self.write("frontend/src/app/router.tsx", "")
        self.commit("remove a route")
        self.assertEqual(self.run_guard().returncode, 0)

    def test_a_route_outside_the_frontend_areas_is_ignored(self) -> None:
        self.write("backend/Notes.java", "retailScreen('/x');\n")
        self.commit("elsewhere")
        self.assertEqual(self.run_guard().returncode, 0)

    def test_windows_line_endings_in_the_body(self) -> None:
        self.write("frontend/src/areas/staff/retail/usage.tsx", PAGE.replace("stock", "usage"))
        self.commit("add a redirect")
        self.assertEqual(self.run_guard("Closes #1\r\n\r\nTOUR-EXEMPT: a redirect with no controls\r\n").returncode, 0)

    def test_pure_helpers(self) -> None:
        self.assertIsNone(tour_guard.exemption(""))
        self.assertEqual(tour_guard.exemption("a\nTOUR-EXEMPT: only a redirect\nb"), "only a redirect")
        self.assertTrue(tour_guard.tour_changed(["x", "frontend/src/tour/model.ts"]))
        self.assertFalse(tour_guard.tour_changed(["frontend/src/areas/a.tsx"]))


if __name__ == "__main__":
    unittest.main()
