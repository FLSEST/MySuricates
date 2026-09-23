"""Tests fuer scripts/public-readiness/docs_orphan_scan.py (TODO 05f633c2).

Jeder Fall haelt einen Fehlalarm fest, der beim Bau dieses Skripts wirklich auftrat —
gemessen am echten Bestand, nicht ausgedacht:

* `test_wave_commit_is_not_maintenance` — eine Datei, die 15-mal von Sync-Wellen
  angefasst und einmal inhaltlich gepflegt wurde, galt als frisch. Genau der Fall
  `docs/onboarding/GLOSSARY.md`.
* `test_small_edit_is_not_maintenance`  — ohne Substanzschwelle faerbte ein einziger
  Dreizeiler die Datei jung; das Pflegesignal fand daraufhin **niemanden**.
* `test_container_consumer_downgrades`  — `docs/workflow-memory-architecture.md` ist vom
  README nicht erreichbar, wird aber vom DocsController ausgeliefert. Ein reiner
  Linkgraph meldet es faelschlich als verwaist.
* `test_mirror_is_not_a_second_finding` — ohne die DE/EN-Faltung zaehlt jeder Befund doppelt.
* `test_template_pages_do_not_overlap`  — vorlagenerzeugte Seiten teilen per Konstruktion
  ihre Ueberschriften; roh gemessen ergab das Dutzende Falschpaare.

Run: python3 -m unittest discover -s scripts/tests -p 'test_docs_orphan_scan.py'
"""
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "public-readiness" / "docs_orphan_scan.py"

_spec = importlib.util.spec_from_file_location("docs_orphan_scan", SCRIPT)
scan = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(scan)


def run_script(root: Path):
    proc = subprocess.run(
        [sys.executable, str(SCRIPT), "--root", str(root)],
        capture_output=True, text=True, check=False,
    )
    return proc.returncode, proc.stdout


def git(root: Path, *args: str, env_date: str | None = None):
    env = None
    if env_date:
        import os
        env = dict(os.environ, GIT_AUTHOR_DATE=env_date, GIT_COMMITTER_DATE=env_date)
    subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, env=env)


def make_repo(tmp: Path) -> Path:
    root = tmp / "repo"
    (root / "docs").mkdir(parents=True)
    git(root.parent, "init", "repo")
    git(root, "config", "user.email", "t@example.com")
    git(root, "config", "user.name", "Test")
    return root


def commit(root: Path, message: str, date: str | None = None):
    git(root, "add", "-A")
    git(root, "commit", "-m", message, "--no-verify", env_date=date)


class ZoneTest(unittest.TestCase):
    def test_backend_resources_are_code_owned(self):
        scope, code_owned = scan.zones([
            "docs/architektur.md",
            "README.md",
            "ai-automation-backend/src/main/resources/skills/builtin/x.md",
        ])
        self.assertIn("docs/architektur.md", scope)
        self.assertIn("README.md", scope)
        # Ressourcen laedt der Code per Pfad — sie gehoeren in keinen Doku-Index und
        # duerfen nie als verwaist gelten.
        self.assertIn("ai-automation-backend/src/main/resources/skills/builtin/x.md", code_owned)


class MaintenanceTest(unittest.TestCase):
    def test_wave_commit_is_not_maintenance(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            target = root / "docs" / "glossar.md"
            target.write_text("# Glossar\n" + "\n".join(f"Begriff {i}" for i in range(60)))
            commit(root, "init", "2026-01-01T10:00:00")
            # Welle: 12 Dokumente im selben Commit, an dieser Datei nur zwei Zeilen.
            for i in range(12):
                (root / "docs" / f"w{i}.md").write_text(f"# W{i}\nText\n")
            target.write_text(target.read_text() + "\nNachtrag\n")
            commit(root, "i18n-Welle", "2026-06-01T10:00:00")

            last, content, wave = scan.maintenance(root, "docs/glossar.md")
            self.assertEqual(wave, 1, "der breite Kleinst-Commit muss als Welle zaehlen")
            self.assertEqual(content, 1, "nur der Ursprungscommit ist inhaltlich")
            self.assertTrue(last.startswith("2026-01"), f"letzte Pflege war Januar, nicht {last}")

    def test_small_edit_is_not_maintenance(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            target = root / "docs" / "a.md"
            target.write_text("# A\n" + "\n".join(f"Zeile {i}" for i in range(50)))
            commit(root, "init", "2026-01-01T10:00:00")
            # Schmaler Commit, aber nur drei Zeilen: ein Tippfehler ist keine Pflege.
            target.write_text(target.read_text() + "\nTippfehler behoben\n")
            commit(root, "typo", "2026-08-01T10:00:00")

            last, content, _ = scan.maintenance(root, "docs/a.md")
            self.assertEqual(content, 1)
            self.assertTrue(last.startswith("2026-01"),
                            f"ein Dreizeiler darf die Datei nicht jung faerben (last={last})")

    def test_substantial_edit_counts_even_in_a_wave(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            target = root / "docs" / "b.md"
            target.write_text("# B\n" + "\n".join(f"Zeile {i}" for i in range(40)))
            commit(root, "init", "2026-01-01T10:00:00")
            for i in range(12):
                (root / "docs" / f"w{i}.md").write_text(f"# W{i}\nText\n")
            # Breiter Commit, aber an dieser Datei 60 neue Zeilen: echte Ueberarbeitung.
            target.write_text(target.read_text() + "\n".join(f"Absatz {i}" for i in range(60)))
            commit(root, "breite Ueberarbeitung", "2026-06-01T10:00:00")

            last, content, _ = scan.maintenance(root, "docs/b.md")
            self.assertEqual(content, 2)
            self.assertTrue(last.startswith("2026-06"),
                            "eine grosse Aenderung darf nicht als Welle verschwinden")

    def test_small_document_never_counts_as_neglected(self):
        """Eine Datei, die nie gross genug geaendert wurde, hat kein Pflegedatum.

        Gewollt konservativ: ein Zehnzeiler, der seit jeher so dasteht, ist fertig und
        nicht vernachlaessigt. Ohne diese Grenze meldete das Signal jede kleine Datei.
        """
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            (root / "docs" / "klein.md").write_text("# Klein\nzwei Zeilen\n")
            commit(root, "init", "2026-01-01T10:00:00")

            last, content, _ = scan.maintenance(root, "docs/klein.md")
            self.assertIsNone(last)
            self.assertEqual(content, 0)


class OverlapTest(unittest.TestCase):
    def test_template_pages_do_not_overlap(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            # Acht vorlagenerzeugte Seiten mit identischen Abschnitten. Ohne den
            # Haeufigkeitsfilter ergaebe das 28 Falschpaare.
            body = "\n".join(["## Problem", "x", "## Loesung", "y", "## Vorteile", "z",
                              "## Nachteile", "w", "## Grenzen", "v", "## Belege", "u"])
            for i in range(8):
                (root / "docs" / f"k{i}.md").write_text(f"# K{i}\n{body}\n")
            commit(root, "konzepte", "2026-01-01T10:00:00")

            pairs = scan.rare_slug_overlap(root, [f"docs/k{i}.md" for i in range(8)])
            self.assertEqual(pairs, [], "vorlagengleiche Seiten sind keine Duplikate")

    def test_genuinely_shared_topic_is_found(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            shared = "\n".join(["## Siegel pruefen", "a", "## Herkunft belegen", "b",
                                "## Schluessel ablegen", "c", "## Lauf zulassen", "d",
                                "## Ausnahme begruenden", "e"])
            (root / "docs" / "haertung.md").write_text(f"# Haertung\n{shared}\n")
            (root / "docs" / "sicherheit.md").write_text(f"# Sicherheit\n{shared}\n")
            for i in range(4):
                (root / "docs" / f"f{i}.md").write_text(f"# F{i}\n## Anderes {i}\ntext\n")
            commit(root, "init", "2026-01-01T10:00:00")

            files = ["docs/haertung.md", "docs/sicherheit.md"] + [f"docs/f{i}.md" for i in range(4)]
            pairs = scan.rare_slug_overlap(root, files)
            self.assertEqual(len(pairs), 1, f"echte Dopplung muss anspringen: {pairs}")
            self.assertEqual({pairs[0][0], pairs[0][1]},
                             {"docs/haertung.md", "docs/sicherheit.md"})


class ScriptRunTest(unittest.TestCase):
    def test_container_consumer_downgrades_orphan(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            (root / "README.md").write_text("# R\n[A](docs/a.md)\n")
            (root / "docs" / "a.md").write_text("# A\ntext\n")
            (root / "docs" / "serviert.md").write_text("# Serviert\ntext\n")
            consumer = root / "ai-automation-backend/src/main/java/com/automation/controller"
            consumer.mkdir(parents=True)
            (consumer / "DocsController.java").write_text('class X { String p = "/api/v1/docs"; }')
            commit(root, "init", "2026-01-01T10:00:00")

            code, out = run_script(root)
            self.assertEqual(code, 0)
            line = [l for l in out.splitlines() if "serviert.md" in l and "docs_candidate" in l]
            self.assertTrue(line, f"unverlinktes Dokument muss Kandidat sein:\n{out}")
            self.assertIn("container=DocsController", line[0],
                          "der Konsument muss als Beleg an der Zeile stehen")
            self.assertIn("FAKT docs_container_consumers_verified: 1", out)

    def test_missing_consumer_is_reported_not_silently_dropped(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            (root / "README.md").write_text("# R\n[A](docs/a.md)\n")
            (root / "docs" / "a.md").write_text("# A\ntext\n")
            commit(root, "init", "2026-01-01T10:00:00")

            code, out = run_script(root)
            self.assertEqual(code, 0)
            # Kein DocsController im Fixture: das MUSS auffallen, sonst sieht ein
            # umbenannter Konsument wie "keine Kante" aus und erzeugt Falschbefunde.
            self.assertIn("NICHT GEMESSEN: container_edge", out)
            self.assertIn("FAKT docs_container_consumers_verified: 0", out)

    def test_mirror_is_not_a_second_finding(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            (root / "docs" / "en").mkdir()
            (root / "README.md").write_text("# R\nkein Verweis auf das Paar\n")
            (root / "docs" / "waise.md").write_text("# Waise\ntext\n")
            (root / "docs" / "en" / "waise.md").write_text("# Orphan\ntext\n")
            commit(root, "init", "2026-01-01T10:00:00")

            code, out = run_script(root)
            self.assertEqual(code, 0)
            hits = [l for l in out.splitlines() if "docs_candidate" in l and "waise.md" in l]
            self.assertEqual(len(hits), 1, f"der Spiegel darf nicht zweimal zaehlen:\n{out}")
            self.assertNotIn("docs/en/waise.md:1", hits[0])

    def test_roots_are_never_candidates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            (root / "README.md").write_text("# R\ntext\n")
            (root / "CONTRIBUTING.md").write_text("# C\ntext\n")
            commit(root, "init", "2026-01-01T10:00:00")

            code, out = run_script(root)
            self.assertEqual(code, 0)
            self.assertNotIn("CONTRIBUTING.md:1", out,
                             "auf Einstiege verweist niemand — sie sind der Anfang der Kette")

    def test_known_unlinked_is_listed_but_not_a_candidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            (root / "workflow-pool").mkdir()
            (root / "README.md").write_text("# R\ntext\n")
            (root / "workflow-pool" / "README.md").write_text("# Pool\ntext\n")
            commit(root, "init", "2026-01-01T10:00:00")

            code, out = run_script(root)
            self.assertEqual(code, 0)
            self.assertIn("bekannt: workflow-pool/README.md", out)
            pool_hits = [l for l in out.splitlines()
                         if "docs_candidate" in l and "workflow-pool/README.md" in l]
            self.assertEqual(pool_hits, [])

    def test_mandatory_facts_are_present(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            (root / "README.md").write_text("# R\n[A](docs/a.md)\n")
            (root / "docs" / "a.md").write_text("# A\ntext\n")
            commit(root, "init", "2026-01-01T10:00:00")

            code, out = run_script(root)
            self.assertEqual(code, 0)
            required = [l for l in out.splitlines() if l.startswith("PFLICHT-FAKTEN:")]
            self.assertTrue(required, "das Blatt muss seine Pflichtfelder nennen")
            for name in required[0].split(":", 1)[1].split():
                self.assertIn(f"FAKT {name}:", out, f"Pflichtfeld {name} fehlt im Blatt")

    def test_two_runs_are_identical(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            (root / "README.md").write_text("# R\n[A](docs/a.md)\n")
            (root / "docs" / "a.md").write_text("# A\ntext\n")
            (root / "docs" / "b.md").write_text("# B\ntext\n")
            commit(root, "init", "2026-01-01T10:00:00")

            first = run_script(root)[1]
            second = run_script(root)[1]
            self.assertEqual(first, second, "das Blatt muss zwischen Laeufen stabil sein")


class ExitCodeTest(unittest.TestCase):
    def test_missing_root_exits_two(self):
        with tempfile.TemporaryDirectory() as tmp:
            proc = subprocess.run(
                [sys.executable, str(SCRIPT), "--root", str(Path(tmp) / "gibtsnicht")],
                capture_output=True, text=True, check=False,
            )
            self.assertEqual(proc.returncode, 2)

    def test_repo_without_markdown_exits_two(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = make_repo(Path(tmp))
            (root / "code.txt").write_text("kein Markdown\n")
            commit(root, "init", "2026-01-01T10:00:00")

            code, _ = run_script(root)
            # "nichts gemessen" und "nichts gefunden" duerfen nicht gleich aussehen.
            self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main()
