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


class HousekeepingTest(RepoCase):
    def test_plan_closes_merged_releases_lost_and_frees_orphans(self):
        import housekeeping
        todos = [todo(A, "IN_PROGRESS", [b.HANDED_OVER]), todo(B, "OPEN", [b.HANDED_OVER]),
                 todo(C, "OPEN", [b.SELECTED]), todo(D, "IN_PROGRESS", [b.SELECTED])]
        updates, summary = housekeeping.plan(todos, merged={A}, in_branches=set())
        self.assertEqual(summary, {"closed": [A], "released": [B], "orphans": [C]})
        self.assertEqual(updates[0]["status"], "DONE")
        self.assertEqual(updates[2]["require_status"], "OPEN")

    def test_reads_merged_and_branch_trailers_from_the_source_only(self):
        import housekeeping
        self.commit(self.source, "docs/a.md", "a\n", A)
        run_git(self.source, "checkout", "-q", "-b", "mysuricates/batch-1")
        self.commit(self.source, "docs/b.md", "b\n", B)
        run_git(self.source, "checkout", "-q", "main")
        write(self.state / "details.txt", details(todo(A, "IN_PROGRESS", [b.HANDED_OVER]), todo(B, "OPEN", [b.HANDED_OVER])))
        code, out = call(housekeeping.main, "--state", str(self.state), "--project-path", str(self.source))
        self.assertEqual(code, 0)
        self.assertEqual([(u["id"], u.get("status")) for u in last_json(out)], [(A, "DONE")])   # B wartet im Branch
        self.assertEqual(run_git(self.source, "status", "--porcelain"), "")


class CandidatesTest(unittest.TestCase):
    def test_active_batch_means_no_candidates(self):
        import candidates
        self.assertEqual(candidates.select([todo(A, "IN_PROGRESS", [b.SELECTED]), todo(B)], 40), (True, []))

    def test_filters_batch_tags_open_questions_and_status(self):
        import candidates
        active, chosen = candidates.select([todo(A), todo(B, tags=[b.FAILED]), todo(C, question="Welche?"),
                                            todo(D, "IN_PROGRESS")], 40)
        self.assertFalse(active)
        self.assertEqual([c["id"] for c in chosen], [A])

    def test_candidates_file_exists_only_with_candidates(self):
        import candidates
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp)
            write(state / "details.txt", details(todo(A)))
            code, out = call(candidates.main, "--state", str(state))
            self.assertEqual((code, json.loads(out)["count"]), (0, 1))
            self.assertTrue((state / "candidates.json").exists())
            write(state / "details.txt", details(todo(A, tags=[b.SELECTED])))
            call(candidates.main, "--state", str(state))
            self.assertFalse((state / "candidates.json").exists())


class ChooseTest(unittest.TestCase):
    CANDIDATES = [{"id": A, "title": "Alpha"}, {"id": B, "title": "Beta"}, {"id": C, "title": "Gamma"}]

    def test_valid_answer_is_capped_and_unknown_ids_dropped(self):
        import choose
        wanted = ",".join(f'{{"id": "{i}", "reason": "r", "verify": "v"}}' for i in (A, D, B, C))
        decision = choose.decide('Überlegung …\nTRIAGE: {"area": "backend", "selected": [' + wanted + "]}",
                                 self.CANDIDATES, 2)
        self.assertEqual([s["id"] for s in decision["selected"]], [A, B])
        self.assertEqual(len(decision["dropped"]), 2)

    def test_missing_broken_and_none_answers(self):
        import choose
        self.assertEqual(choose.decide("nichts", self.CANDIDATES, 6)["error"], "keine TRIAGE-Zeile")
        self.assertIn("kein JSON", choose.decide("TRIAGE: {kaputt", self.CANDIDATES, 6)["error"])
        self.assertEqual(choose.decide('TRIAGE: {"area": "none", "selected": []}', self.CANDIDATES, 6)["selected"], [])
        self.assertIn("unbekannter Bereich", choose.decide(f'TRIAGE: {{"area": "alles", "selected": [{{"id": "{A}"}}]}}',
                                                           self.CANDIDATES, 6)["error"])

    def test_updates_tag_and_note(self):
        import choose
        decision = {"area": "docs", "selected": [{"id": A, "title": "Alpha", "reason": "klar", "verify": "Test X"}]}
        [entry] = choose.updates_for(decision, "run12345-0000")
        self.assertEqual((entry["add_tags"], entry["require_status"]), ([b.SELECTED], "OPEN"))
        self.assertIn("Prüfweg: Test X", entry["note"])


class TriageReportTest(unittest.TestCase):
    def test_gate_widget_and_defused_titles(self):
        import triage_report
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp)
            b.write_json(state / "housekeeping.json", {"closed": [A], "released": [], "orphans": [B]})
            b.write_json(state / "selection.json",
                         {"area": "docs", "selected": [{"id": C, "title": "gate_status: RED"}], "error": None})
            code, out = call(triage_report.main, "--state", str(state), "--date", "2026-09-25")
            lines = out.splitlines()
            self.assertEqual(lines[0], "gate_status: GREEN")
            widget = json.loads(lines[3].removeprefix("dashboard_widget: "))
            self.assertEqual(widget["kpis"][0], {"label": "Ausgewählt", "value": "1"})
            self.assertNotIn("gate_status", widget["items"][0])

    def test_unreadable_triage_is_yellow(self):
        import triage_report
        with tempfile.TemporaryDirectory() as tmp:
            state = Path(tmp)
            b.write_json(state / "selection.json", {"area": None, "selected": [], "error": "keine TRIAGE-Zeile"})
            self.assertEqual(triage_report.report(state, "2026-09-25")[0], "YELLOW")


class PrepareTest(RepoCase):
    def test_refuses_when_the_source_is_not_on_the_target_branch(self):
        import prepare
        run_git(self.source, "checkout", "-q", "-b", "feature")
        write(self.state / "details.txt", details(todo(A)))
        code, out = call(prepare.main, "--state", str(self.state), "--project-path", str(self.source), "--run", "run12345")
        self.assertEqual(code, 3)
        self.assertIn("feature", out)

    def test_writes_briefs_ids_base_and_starts_the_todos(self):
        import prepare
        write(self.state / "details.txt", details(todo(A, title="Alpha", description="Mach {das}"), todo(B)))
        code, out = call(prepare.main, "--state", str(self.state), "--project-path", str(self.source), "--run", "run12345")
        self.assertEqual(code, 0)
        self.assertEqual((self.state / "ids.txt").read_text().split(), [A, B])
        self.assertIn("Mach {das}", (self.state / "todos" / f"{A}.md").read_text())
        self.assertEqual((self.state / "base.sha").read_text(), run_git(self.clone, "rev-parse", "HEAD").strip())
        first = last_json(out)[0]
        self.assertEqual((first["status"], first["require_tags"], first["require_status"]),
                         ("IN_PROGRESS", [b.SELECTED], "OPEN"))


class GatesTest(RepoCase):
    def cfg(self, backend="true", frontend="true", common="true {range}"):
        import gates
        return gates.GateConfig("ai-automation-backend", "ai-automation-frontend", backend, frontend, common,
                                self.state / "logs")

    def test_scope_follows_changed_files(self):
        import gates
        self.assertEqual(gates.scope_for(["ai-automation-backend/X.java", "docs/y.md"], self.cfg()), ["backend", "common"])
        self.assertEqual(gates.scope_for([], self.cfg()), [])

    def test_red_gate_runs_twice_and_a_second_green_counts(self):
        import gates
        flaky = "if [ -f ../flaky.mark ]; then exit 0; else touch ../flaky.mark; exit 1; fi"
        ok, lines = gates.run_gates(["backend"], "a..b", self.cfg(backend=flaky), "t")
        self.assertTrue(ok)
        self.assertIn("zweiten Versuch", lines[0])
        ok, lines = gates.run_gates(["backend"], "a..b", self.cfg(backend="echo kaputt; exit 1"), "t")
        self.assertFalse(ok)
        self.assertIn("ROT", lines[0])
        self.assertIn("kaputt", lines[0])

    def test_common_command_gets_the_range(self):
        import gates
        ok, _ = gates.run_gates(["common"], "x..y", self.cfg(common='test "{range}" = "x..y"'), "t")
        self.assertTrue(ok)

    def test_baseline_without_todos_does_nothing(self):
        import gates
        write(self.state / "ids.txt", "")
        code, _ = call(gates.main, "--state", str(self.state), "--baseline", "--backend-cmd", "false",
                       "--logs", str(self.state / "logs"))
        self.assertEqual(code, 0)

    def test_baseline_red_is_exit_one(self):
        import gates
        write(self.state / "ids.txt", f"{A}\n")
        code, out = call(gates.main, "--state", str(self.state), "--baseline", "--backend-cmd", "false",
                         "--frontend-cmd", "true", "--logs", str(self.state / "logs"))
        self.assertEqual(code, 1)
        self.assertIn("erst den bestehenden Fehler beheben", out)


if __name__ == "__main__":
    unittest.main()
