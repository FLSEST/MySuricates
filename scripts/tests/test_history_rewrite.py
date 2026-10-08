"""Tests for scripts/history-rewrite/messages.py (TODO 0bf5eed4, history rewrite steps b and c).

Step b (TODO bracket -> trailer) is deterministic and rewrites ~1900 subjects, step c checks AI
translations before they reach the map that git filter-repo reads. Both run once over the whole
history, so every rule is pinned here: a wrong rule is not noticed commit by commit.

Run: python3 -m unittest discover -s scripts/tests -p 'test_history_rewrite.py'
"""
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "history-rewrite" / "messages.py"

_spec = importlib.util.spec_from_file_location("history_messages", SCRIPT)
hm = importlib.util.module_from_spec(_spec)
sys.modules["history_messages"] = hm
_spec.loader.exec_module(hm)

UUID_A = "ebc01f1a-6463-4218-9866-d5910aa82ce3"
UUID_B = "8edfae0d-0000-4000-8000-000000000001"
INDEX = {"ebc01f1a": (UUID_A, "Projekt umbenennen"), "8edfae0d": (UUID_B, "GoalDetail: eigene Kopie")}
CO = "Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"


class MoveTodoBracket(unittest.TestCase):
    def test_bracket_with_title_becomes_trailer_with_full_id_and_current_title(self):
        msg = f"fix(x): etwas reparieren (ebc01f1a alter Titel)\n\nText.\n\n{CO}\n"
        self.assertEqual(hm.move_todo_bracket(msg, INDEX),
                         f"fix(x): etwas reparieren\n\nText.\n\nTodo: {UUID_A} (Projekt umbenennen)\n{CO}\n")

    def test_unknown_todo_keeps_short_id_and_bracket_title(self):
        msg = "feat: neu (deadbee1 Titel mit (Klammer), Komma)\n"
        self.assertEqual(hm.move_todo_bracket(msg, INDEX),
                         "feat: neu\n\nTodo: deadbee1 (Titel mit (Klammer), Komma)\n")

    def test_todo_prefix_and_id_only_forms(self):
        self.assertEqual(hm.move_todo_bracket("test: x (TODO deadbee1)\n", {}), "test: x\n\nTodo: deadbee1\n")
        self.assertEqual(hm.move_todo_bracket("docs: y (deadbee1)\n", {}), "docs: y\n\nTodo: deadbee1\n")

    def test_several_todos_in_one_bracket_become_several_trailers(self):
        msg = "fix: z (ebc01f1a Erster, mit Komma, 8edfae0d Zweiter)\n"
        self.assertEqual(hm.move_todo_bracket(msg, INDEX),
                         f"fix: z\n\nTodo: {UUID_A} (Projekt umbenennen)\nTodo: {UUID_B} (GoalDetail: eigene Kopie)\n")

    def test_bracket_that_is_no_todo_reference_stays(self):
        for msg in ("chore: Paket (Paket 25)\n", "fix: Version (v0.7.7)\n", "feat: (deadbeef) mittendrin\n"):
            self.assertEqual(hm.move_todo_bracket(msg, INDEX), msg)

    def test_message_with_todo_trailer_already_is_untouched(self):
        msg = f"fix: x (ebc01f1a Titel)\n\nTodo: {UUID_A} (Projekt umbenennen)\n"
        self.assertEqual(hm.move_todo_bracket(msg, INDEX), msg)

    def test_other_trailers_stay_in_front_of_todo(self):
        msg = f"fix: x (ebc01f1a T)\n\nBody.\n\nClaude-Session: https://example.invalid/s\n{CO}\n"
        out = hm.move_todo_bracket(msg, INDEX)
        self.assertTrue(out.endswith(f"Claude-Session: https://example.invalid/s\nTodo: {UUID_A} (Projekt umbenennen)\n{CO}\n"))


class PrivateTrailers(unittest.TestCase):
    def test_claude_session_trailer_is_dropped_other_trailers_stay(self):
        msg = f"fix: x\n\nBody.\n\nClaude-Session: https://example.invalid/s\nTodo: deadbee1 (T)\n{CO}\n"
        self.assertEqual(hm.drop_private_trailers(msg), f"fix: x\n\nBody.\n\nTodo: deadbee1 (T)\n{CO}\n")

    def test_message_with_only_a_session_trailer_loses_the_trailer_block(self):
        self.assertEqual(hm.drop_private_trailers("docs: y\n\nClaude-Session: https://example.invalid/s\n"), "docs: y\n")

    def test_message_without_it_is_unchanged(self):
        msg = f"fix: x\n\n{CO}\n"
        self.assertIs(hm.drop_private_trailers(msg), msg)


class Callback(unittest.TestCase):
    def run_callback(self, map_lines, replacements, original_id, message):
        with tempfile.TemporaryDirectory() as tmp:
            mp, rp = Path(tmp) / "map.jsonl", Path(tmp) / "repl.txt"
            mp.write_text("".join(json.dumps(m) + "\n" for m in map_lines))
            rp.write_text(replacements)
            code = subprocess.run([sys.executable, str(SCRIPT), "callback", "--map", str(mp), "--replacements", str(rp)],
                                  capture_output=True, text=True, check=True).stdout

            class Commit:
                pass
            Commit.original_id, Commit.message = original_id, message
            exec(code, {"commit": Commit})  # noqa: S102 - as git filter-repo runs it
            return Commit.message.decode()

    def test_session_trailer_goes_even_without_a_map_entry(self):
        out = self.run_callback([], "", b"abc", b"fix: x\n\nClaude-Session: https://example.invalid/s\nCo-Authored-By: Bot <b@example.invalid>\n")
        self.assertEqual(out, "fix: x\n\nCo-Authored-By: Bot <b@example.invalid>\n")

    def test_replacements_apply_after_the_map_literal_and_regex(self):
        out = self.run_callback([{"hash": "abc", "message": "fix: path /home/dev/x and Secret\n"}],
                                "/home/dev==>~\nregex:(?i)secret==>[redacted]\n# comment\nbare\n", b"abc", b"old")
        self.assertEqual(out, "fix: path ~/x and [redacted]\n")


class Checks(unittest.TestCase):
    SRC = ("fix(staging): Hinweis für `foreach` in SealedSyncScope.java\n\n"
           "Die Frage beantwortet jetzt WorkflowStarters über workflow_id (V85, 3 Stellen, a0e85182).\n\n"
           f"Todo: {UUID_A} (Projekt umbenennen)\n{CO}\n")
    GOOD = ("fix(staging): hint for `foreach` in SealedSyncScope.java\n\n"
            "WorkflowStarters now answers the question via workflow_id (V85, 3 places, a0e85182).\n\n"
            f"Todo: {UUID_A} (Projekt umbenennen)\n{CO}\n")

    def test_faithful_translation_passes(self):
        self.assertEqual(hm.problems(self.SRC, self.GOOD, []), ([], []))

    def test_changed_trailer_is_an_error(self):
        bad = self.GOOD.replace("(Projekt umbenennen)", "(Rename project)")
        self.assertIn("trailers changed", hm.problems(self.SRC, bad, [])[0])

    def test_lost_identifier_number_or_code_span_is_an_error(self):
        for lost in ("`foreach`", "workflow_id", "V85", "a0e85182", "SealedSyncScope.java", "WorkflowStarters"):
            bad = self.GOOD.replace(lost, "it")
            errors = hm.problems(self.SRC, bad, [])[0]
            self.assertTrue(any(e.startswith("lost:") for e in errors), lost)

    def test_changed_conventional_prefix_is_an_error(self):
        bad = self.GOOD.replace("fix(staging):", "fix:")
        self.assertIn("prefix changed", hm.problems(self.SRC, bad, [])[0])

    def test_implausible_length_is_an_error(self):
        short = "fix(staging): x `foreach` workflow_id SealedSyncScope.java WorkflowStarters V85 3 a0e85182\n\n" \
                f"Todo: {UUID_A} (Projekt umbenennen)\n{CO}\n"
        self.assertTrue(any(e.startswith("length ratio") for e in hm.problems(self.SRC * 3, short, [])[0]))

    def test_german_left_and_long_subject_are_warnings_only(self):
        mixed = self.GOOD.replace("now answers the question", "beantwortet jetzt die Frage, weil der Lauf sonst abbricht")
        errors, warnings = hm.problems(self.SRC, mixed, [])
        self.assertEqual(errors, [])
        self.assertIn("German left", warnings)

    def test_new_email_or_private_term_is_an_error(self):
        mail = self.GOOD.replace("now answers", "now answers someone@example.org")
        self.assertIn("new email address", hm.problems(self.SRC, mail, [])[0])
        term = self.GOOD.replace("now answers", "now answers secretname")
        self.assertIn("new private term", hm.problems(self.SRC, term, ["secretname"])[0])

    def test_answer_json_is_found_inside_prose_and_code_fences(self):
        raw = 'Here you go:\n```json\n{"messages": [{"hash": "a", "message": "x {y}"}]}\n```\n'
        self.assertEqual(hm.json_object(raw), {"messages": [{"hash": "a", "message": "x {y}"}]})


class ProbeFindings(unittest.TestCase):
    """False alarms and one real slip from the 50-commit probe of 2026-10-07 (run 20261007-162524)."""

    def test_german_word_pairs_with_slashes_are_no_paths(self):
        for pair in ("Aktionen/Einstellungen", "Maven-/Spring-Logzeilen", "Records/Interfaces",
                     "erklaert/priorisiert", "Memory/Kontext/Action", "GitHub-/OpenAI-/AWS-/Slack-Tokens"):
            self.assertFalse(hm.is_path(pair), pair)

    def test_real_paths_still_have_to_survive(self):
        for path in ("scripts/tests", "docs/en/konzepte", "service/workflow/RunCompletionHandler.java",
                     "api/v1/todos", "./mvnw", "~/.config/mysuricates"):
            self.assertTrue(hm.is_path(path), path)
        src = "fix: Prüfung in scripts/tests und docs/plans ergänzt\n"
        self.assertTrue(any(e.startswith("lost:") for e in hm.problems(src, "fix: check added to tests and plans\n", [])[0]))

    def test_file_names_do_not_count_as_german(self):
        self.assertFalse(hm.is_german_text("docs: drop duplicates in entwicklung.md and docs/deployment.md"))

    def test_changed_trailers_are_restored_not_rejected(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        run = Path(tmp.name) / "runs" / "r" / "batches"
        run.mkdir(parents=True)
        src = "docs: Betriebsart beim Dev-Start erklaert\n\nTodo: deadbee1 (warum Dev laeuft)\n"
        (run / "001.source.json").write_text(json.dumps([{"hash": "h1", "source": src, "message": src}]))
        answer = json.dumps({"messages": [{"hash": "h1", "message": "docs: explain the operating mode at dev start\n\nTodo: deadbee1 (warum Dev läuft)\n"}]})
        out = subprocess.run([sys.executable, str(SCRIPT), "check", "--state", tmp.name, "--run", "r", "--batch", "001", "--probe"],
                             input=answer, capture_output=True, text=True, check=True).stdout
        self.assertIn("1 accepted, 0 rejected", out)
        accepted = json.loads((Path(tmp.name) / "runs/r/accepted.jsonl").read_text())
        self.assertTrue(accepted["message"].endswith("Todo: deadbee1 (warum Dev laeuft)\n"))
        self.assertIn("trailers restored", accepted["warnings"])
        # The raw answer is kept; a re-check replaces the verdict instead of adding a second one.
        subprocess.run([sys.executable, str(SCRIPT), "check", "--state", tmp.name, "--run", "r", "--batch", "001", "--probe", "--stored"],
                       capture_output=True, text=True, check=True)
        self.assertEqual(len((Path(tmp.name) / "runs/r/accepted.jsonl").read_text().splitlines()), 1)


class NeedsTranslation(unittest.TestCase):
    def test_english_message_with_german_todo_title_needs_none(self):
        msg = f"test(scripts): green again\n\nBody in English.\n\nTodo: {UUID_A} (Rote Shell-Suiten auf main beheben)\n"
        self.assertFalse(hm.needs_translation(msg))

    def test_german_body_needs_translation_quoted_ui_text_does_not(self):
        self.assertTrue(hm.needs_translation("fix: x\n\nDer Lauf bricht ab, weil die Datei fehlt.\n"))
        self.assertFalse(hm.needs_translation("fix(ui): label reads „Mit Fehlern abgeschlossen“\n"))


class ShortGermanSubjects(unittest.TestCase):
    """Real subjects from the history that a two-word rule missed (they would stay German in public)."""

    def test_short_german_subjects_are_found(self):
        for subject in ("refactor(executor): finally-Block gestrafft, Ratchet-Decke 540 haelt",
                        "docs(frontend): Hinweis bei gesperrter Wissens-Extraktion beschreiben",
                        "Merge audit/r28-pages: DialogImport.tsx und Workflows.tsx in Hooks zerlegt",
                        "chore(templates): UUID in mysuricates-* durch Template-Variablen ersetzen"):
            self.assertTrue(hm.needs_translation(subject + "\n"), subject)

    def test_english_subjects_stay_out(self):
        for subject in ("test: Add tests for AsyncConfig", "fix(dashboard): label every model in stats chart",
                        "Fix backup download: send API key in fetch requests", "Release v0.4.1",
                        "feat: what was missing in the es module build"):
            self.assertFalse(hm.needs_translation(subject + "\n"), subject)


class EndToEnd(unittest.TestCase):
    """extract -> check -> report on a throwaway repo; the map feeds the filter-repo callback."""

    def git(self, *args):
        subprocess.run(["git", "-C", self.repo, *args], check=True, capture_output=True)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.repo, self.state = str(base / "repo"), base / "state"
        i18n = base / "repo" / "ai-automation-frontend" / "src" / "i18n"
        i18n.mkdir(parents=True)
        (i18n / "de.ts").write_text("export const de = {\n  common: {\n    save: 'Speichern',\n  },\n};\n")
        (i18n / "en.ts").write_text("export const en = {\n  common: {\n    save: 'Save',\n  },\n};\n")
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.invalid")
        self.git("config", "user.name", "T")
        self.git("add", "-A")
        self.git("commit", "-q", "-m", "fix: Speichern bricht ab, weil die Datei fehlt (deadbee1 Titel)")
        self.git("commit", "-q", "--allow-empty", "-m", "docs: english only (deadbee2)")
        self.state.mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def run_cli(self, *args, stdin=""):
        return subprocess.run([sys.executable, str(SCRIPT), *args], input=stdin, capture_output=True,
                              text=True, check=True).stdout

    def test_full_cycle(self):
        out = self.run_cli("extract", "--repo", self.repo, "--state", str(self.state), "--run", "r1")
        self.assertIn("1 messages to translate in 1 batches", out)
        batch = json.loads((self.state / "runs/r1/batches/001.json").read_text())
        self.assertEqual(batch["glossary"], {"Speichern": "Save"})
        self.assertEqual(batch["messages"][0]["message"], "fix: Speichern bricht ab, weil die Datei fehlt\n\nTodo: deadbee1 (Titel)\n")
        # The English-only message went straight into the map with its trailer.
        mapped = [json.loads(l) for l in (self.state / "map.jsonl").read_text().splitlines()]
        self.assertEqual([m["message"] for m in mapped], ["docs: english only\n\nTodo: deadbee2\n"])
        sha = batch["messages"][0]["hash"]
        answer = json.dumps({"messages": [{"hash": sha, "message": "fix: saving aborts because the file is missing\n\nTodo: deadbee1 (Titel)\n"}]})
        self.assertIn("1 accepted, 0 rejected", self.run_cli("check", "--state", str(self.state), "--run", "r1", "--batch", "001", stdin=answer))
        report = self.run_cli("report", "--state", str(self.state), "--run", "r1")
        self.assertIn("gate_status: GREEN", report)
        self.assertTrue((self.state / "runs/r1/review.html").exists())
        # Second extract: nothing left - the map is the cache.
        self.assertIn("0 messages to translate", self.run_cli("extract", "--repo", self.repo, "--state", str(self.state), "--run", "r2"))
        # The callback rewrites by original hash.
        code = self.run_cli("callback", "--map", str(self.state / "map.jsonl"))

        class Commit:
            original_id = sha.encode()
            message = b"old"
        scope = {"commit": Commit}
        exec(code, scope)  # noqa: S102 - generated callback body, as git filter-repo runs it
        self.assertEqual(Commit.message.decode(), "fix: saving aborts because the file is missing\n\nTodo: deadbee1 (Titel)\n")

    def test_probe_keeps_the_map_untouched(self):
        self.run_cli("extract", "--repo", self.repo, "--state", str(self.state), "--run", "p", "--sample", "1")
        sha = json.loads((self.state / "runs/p/batches/001.json").read_text())["messages"][0]["hash"]
        answer = json.dumps({"messages": [{"hash": sha, "message": "fix: saving aborts because the file is missing\n\nTodo: deadbee1 (Titel)\n"}]})
        self.run_cli("check", "--state", str(self.state), "--run", "p", "--batch", "001", "--probe", stdin=answer)
        self.assertFalse((self.state / "map.jsonl").exists())


if __name__ == "__main__":
    unittest.main()
