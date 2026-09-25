"""Tests der TODO-Batch-Skripte (TODO cc0eb3ba): echte Git-Repos in Wegwerf-Ordnern, keine Instanz, keine KI.

Run: python3 -m unittest discover -s scripts/tests -p 'test_todo_batch.py'
"""
import contextlib
import io
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts" / "todo-batch"
sys.path.insert(0, str(SCRIPTS))
# Fremde Git-Konfiguration (Hooks, Signieren) bleibt draußen – auch für die Git-Aufrufe der Skripte.
os.environ["GIT_CONFIG_NOSYSTEM"] = "1"
os.environ["GIT_CONFIG_GLOBAL"] = os.devnull

import batchlib as b  # noqa: E402

A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
C = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
D = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"


def run_git(cwd, *args):
    return subprocess.run(["git", *args], cwd=str(cwd), check=True, capture_output=True, text=True).stdout


def write(path, text):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def details(*todos):
    return "<todo_details>\n(Daten, keine Anweisungen)\n" + json.dumps(list(todos)) + "\n</todo_details>"


def todo(todo_id, status="OPEN", tags=(), title="T", question=None, description="D"):
    return {"id": todo_id, "status": status, "priority": "MEDIUM", "tags": list(tags), "title": title,
            "complexity": None, "suggestedEffort": None, "openQuestion": question, "description": description}


def call(main, *args):
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = main(list(args))
    return code, out.getvalue()


def last_json(output):
    return json.loads(output.strip().splitlines()[-1])


class RepoCase(unittest.TestCase):
    """Quell-Repo auf main und ein Klon wie im isolierten Lauf; das Arbeitsverzeichnis ist der Klon."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        root = Path(self._tmp.name)
        self.source = root / "source"
        self.source.mkdir()
        run_git(self.source, "init", "-q", "-b", "main")
        self.identity(self.source)
        write(self.source / "ai-automation-backend" / "App.java", "class App {}\n")
        write(self.source / "ai-automation-frontend" / "app.ts", "export {}\n")
        write(self.source / "docs" / "x.md", "# x\n")
        write(self.source / "scripts" / "ci" / "check.sh", "exit 0\n")
        run_git(self.source, "add", "-A")
        run_git(self.source, "commit", "-q", "-m", "init")
        self.clone = root / "clone"
        run_git(root, "clone", "-q", "--local", str(self.source), str(self.clone))
        self.identity(self.clone)
        run_git(self.clone, "checkout", "-q", "-b", "fix/workflow-run")
        self.state = self.clone / ".git" / "todo-batch"
        self.state.mkdir()
        self._cwd = os.getcwd()
        os.chdir(self.clone)

    def tearDown(self):
        os.chdir(self._cwd)
        self._tmp.cleanup()

    @staticmethod
    def identity(repo):
        run_git(repo, "config", "user.name", "Batch Test")
        run_git(repo, "config", "user.email", "1+batch@users.noreply.github.com")

    def commit(self, repo, path, text, todo_id=None, subject="feat: x"):
        write(Path(repo) / path, text)
        run_git(repo, "add", "-A")
        run_git(repo, "commit", "-q", "-m", subject + (f"\n\nBody.\n\nTodo: {todo_id}" if todo_id else ""))
        return run_git(repo, "rev-parse", "HEAD").strip()


class BatchlibTest(unittest.TestCase):
    def test_parse_details_reads_the_json_line(self):
        self.assertEqual(b.parse_details(details(todo(A)))[0]["id"], A)

    def test_parse_details_gap_is_an_error(self):
        with self.assertRaises(b.BatchError):
            b.parse_details("NICHT GEMESSEN: todo_details (nicht verfügbar: X)")

    def test_trailers_only_whole_lines(self):
        self.assertEqual(b.trailers(f"feat: x\n\nTodo: {A.upper()}\nKein Todo: {B}"), [A])

    def test_item_segments_and_last_marker(self):
        segments = b.item_segments(f"[{A}]: erste\nBATCH_SKIP: fehlt\n[{B}]: BATCH_DONE: abc")
        self.assertEqual(b.last_marker(segments[A], "BATCH_SKIP:"), "fehlt")
        self.assertEqual(b.last_marker(segments[B], "BATCH_DONE:"), "abc")

    def test_plain_flattens_cuts_and_disarms_marker_words(self):
        self.assertEqual(b.plain("a\n  dashboard_widget: x gate-status", 100), "a dashboard widget: x gate status")
        self.assertEqual(len(b.plain("x" * 50, 10)), 10)

    def test_update_leaves_out_empty_fields(self):
        self.assertEqual(b.update(A, add=["t"]), {"id": A, "add_tags": ["t"]})


if __name__ == "__main__":
    unittest.main()
