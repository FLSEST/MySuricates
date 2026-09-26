"""Tests fuer scripts/public-readiness/docs_parity_check.py (TODO c58d7402).

Zwei der Faelle halten Fehler fest, die die Erstfassung des Skripts wirklich hatte und die
beide zu **Falschbefunden** gefuehrt haetten — der teuersten Sorte Fehler in einem Gate:

* `test_slug_keeps_each_space`   — Slug zog Leerzeichenfolgen zusammen, korrekte Anker
                                   galten als tot.
* `test_headings_skip_code_block` — `# Kommentar` in einem Shell-Beispiel zaehlte als
                                   Ueberschrift und erzeugte Phantom-Unterschiede.

Run: python3 -m unittest discover -s scripts/tests -p 'test_docs_parity_check.py'
"""
import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "public-readiness" / "docs_parity_check.py"

_spec = importlib.util.spec_from_file_location("docs_parity_check", SCRIPT)
dpc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dpc)


def run_script(root: Path):
    proc = subprocess.run(
        [sys.executable, str(SCRIPT), "--root", str(root)],
        capture_output=True, text=True, check=False,
    )
    return proc.returncode, proc.stdout


class SlugTest(unittest.TestCase):
    def test_slug_keeps_each_space(self):
        # `a` / `b` — c  ->  zwischen a und b stehen nach dem Entfernen von Backtick und
        # Schraegstrich zwei Leerzeichen, GitHub macht daraus zwei Bindestriche.
        self.assertEqual(dpc.slug("### `a` / `b` — c"), "a--b--c")

    def test_slug_real_heading(self):
        heading = "### `checked_command` / `check_category` — Gate automatisch aus dem Exit-Code"
        self.assertEqual(
            dpc.slug(heading),
            "checked_command--check_category--gate-automatisch-aus-dem-exit-code",
        )

    def test_slug_umlauts_survive(self):
        self.assertEqual(dpc.slug("## Bekannte Einschränkungen"), "bekannte-einschränkungen")


class HeadingsTest(unittest.TestCase):
    def test_headings_skip_code_block(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "a.md"
            f.write_text("# Echt\n\n```bash\n# nur ein Kommentar\necho hi\n```\n\n## Auch echt\n")
            self.assertEqual(dpc.headings(f), ["# Echt", "## Auch echt"])

    def test_headings_skip_shebang(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "a.md"
            f.write_text("#!/usr/bin/env bash\n# Echt\n")
            self.assertEqual(dpc.headings(f), ["# Echt"])


class GateTest(unittest.TestCase):
    def build(self, tmp, *, de_extra="", en_extra="", link="", extra_de_file=None):
        root = Path(tmp)
        (root / "docs" / "en").mkdir(parents=True)
        (root / "docs" / "a.md").write_text(f"# A\n\n## Eins\n{de_extra}\n{link}\n")
        (root / "docs" / "en" / "a.md").write_text(f"# A\n\n## One\n{en_extra}\n")
        if extra_de_file:
            (root / "docs" / extra_de_file).write_text("# Extra\n")
        return root

    def test_clean_repo_is_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp))
            self.assertEqual(code, 0, out)
            self.assertIn("gate_status: GREEN", out)
            self.assertIn("findings: 0", out)

    def test_dead_target_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, link="[weg](nirgends.md)"))
            self.assertEqual(code, 1, out)
            self.assertIn("gate_status: YELLOW", out)
            self.assertIn("BEFUND: totes-ziel", out)

    def test_dead_anchor_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, link="[hin](a.md#gibtsnicht)"))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: toter-anker", out)

    def test_live_anchor_is_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, link="[hin](a.md#eins)"))
            self.assertEqual(code, 0, out)

    def test_commented_link_is_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, link="<!-- [weg](nirgends.md) -->"))
            self.assertEqual(code, 0, out)

    def test_placeholder_route_is_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, link="[Lauf](/workflows/<id>)"))
            self.assertEqual(code, 0, out)

    def test_missing_en_file_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, extra_de_file="b.md"))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: kein-en-gegenstueck docs/b.md", out)

    def test_missing_en_section_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de_extra="\n## Zwei\n"))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: abschnitt-fehlt-en", out)

    def test_known_gap_stays_green(self):
        # Eine Luecke, die in KNOWN_GAPS steht, meldet das Gate als bekannt und bleibt gruen.
        # KNOWN_GAPS ist seit TODO 47bd4d27 leer; geprueft wird der Mechanismus mit einem
        # Testeintrag — im selben Prozess, weil ein Unterprozess die Ersetzung nicht saehe.
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp, extra_de_file="diagramme.md")
            argv = ["docs_parity_check.py", "--root", str(root)]
            with mock.patch.dict(dpc.KNOWN_GAPS, {"docs/diagramme.md": "test"}), \
                    mock.patch.object(sys, "argv", argv), \
                    contextlib.redirect_stdout(io.StringIO()) as buffer:
                code = dpc.main()
            out = buffer.getvalue()
            self.assertEqual(code, 0, out)
            self.assertIn("bekannt: kein-en-gegenstueck docs/diagramme.md (bekannt, TODO test)", out)
            self.assertIn("gate_status: GREEN", out)

    def test_english_link_to_german_page_is_reported_not_failed(self):
        # README.md und docs/en/ sind der englische Pfad. Ein Verweis von dort auf eine
        # rein deutsche Seite ist ein Hinweis fuers Audit, das Gate bleibt gruen (TODO 1b84ecab).
        with tempfile.TemporaryDirectory() as tmp:
            # `plans/` ist kein gespiegelter Ordner (anders als konzepte/, siehe MIRRORED_SUBDIRS).
            root = self.build(tmp, en_extra="[Plan](../plans/README.md)\n[A](a.md)")
            (root / "docs" / "plans").mkdir()
            (root / "docs" / "plans" / "README.md").write_text("# Plan\n")
            (root / "README.md").write_text("# R\n\n[Diagramme](docs/a.md)\n[Guide](docs/en/a.md)\n")
            code, out = run_script(root)
            self.assertEqual(code, 0, out)
            self.assertIn("en_links_to_german: 2", out)
            self.assertIn("HINWEIS: en-verweist-auf-deutsch docs/en/a.md:4 -> docs/plans/README.md", out)
            self.assertIn("HINWEIS: en-verweist-auf-deutsch README.md:3 -> docs/a.md", out)
            self.assertNotIn("-> docs/en/a.md", out)

    def test_german_page_linking_german_is_not_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp, extra_de_file="b.md", link="[B](b.md)")
            (root / "docs" / "en" / "b.md").write_text("# Extra\n")
            code, out = run_script(root)
            self.assertIn("en_links_to_german: 0", out)


class MirroredSubdirTest(unittest.TestCase):
    """Unterordner wie docs/konzepte/ werden gespiegelt geprueft wie docs/ selbst (TODO b94beaed)."""

    def build(self, tmp, *, de_body="# K\n\n## Eins\n", en_body="# K\n\n## One\n", en_file=True):
        root = Path(tmp)
        (root / "docs" / "en" / "konzepte").mkdir(parents=True)
        (root / "docs" / "konzepte").mkdir(parents=True)
        (root / "docs" / "a.md").write_text("# A\n")
        (root / "docs" / "en" / "a.md").write_text("# A\n")
        (root / "docs" / "konzepte" / "k.md").write_text(de_body)
        if en_file:
            (root / "docs" / "en" / "konzepte" / "k.md").write_text(en_body)
        return root

    def test_mirrored_pair_is_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp))
            self.assertEqual(code, 0, out)

    def test_missing_en_file_in_subdir_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, en_file=False))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: kein-en-gegenstueck docs/konzepte/k.md", out)

    def test_missing_en_section_in_subdir_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de_body="# K\n\n## Eins\n\n## Zwei\n"))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: abschnitt-fehlt-en konzepte/k.md '## Zwei'", out)

    def test_english_link_to_data_file_is_not_reported(self):
        # Messdaten (JSON) sind sprachneutral – nur Seiten (.md) zaehlen als "deutsch".
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp, en_body="# K\n\n## One\n[Daten](../../konzepte/daten.json)\n")
            (root / "docs" / "konzepte" / "daten.json").write_text("{}\n")
            code, out = run_script(root)
            self.assertIn("en_links_to_german: 0", out)


class SectionPairingTest(unittest.TestCase):
    """Ein fehlender Abschnitt wird dem richtigen zugeschrieben, nicht dem letzten gleichrangigen.

    Die Erstfassung verglich nur die Ebenenfolge und meldete bei einem fehlenden mittleren
    "##"-Abschnitt immer den letzten -- so standen "## Bekannte Einschränkungen" (statt
    "## Diagramm-Seiten") und "## Completion Triggers (V39)" (statt des Audit Centers) als
    bekannte Luecken in KNOWN_SECTION_GAPS (TODO 2d553479).
    """

    def build(self, tmp, de_body, en_body, name="a.md"):
        root = Path(tmp)
        (root / "docs" / "en").mkdir(parents=True)
        (root / "docs" / name).write_text(de_body)
        (root / "docs" / "en" / name).write_text(en_body)
        return root

    def test_missing_middle_section_is_named_by_its_heading(self):
        # Nachbau von docs/architektur.md: Das Audit Center fehlte in der englischen Fassung.
        de = ("# Architektur\n\n## Workflow Chains\n\n## Completion Triggers (V39)\n\n"
              "## Audit Center — Findings, Akzeptanz und Verfall (V68/V69)\n\n"
              "## Todo Triggers — Self-Improvement Loop (V51/V52)\n\n## Notification System (V40)\n")
        en = ("# Architecture\n\n## Workflow Chains\n\n## Completion Triggers (V39)\n\n"
              "## Todo Triggers — Self-Improvement Loop (V51/V52)\n\n## Notification System (V40)\n")
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de, en))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: abschnitt-fehlt-en a.md '## Audit Center — Findings, Akzeptanz und Verfall"
                          " (V68/V69)' (Ueberschrift 4 von 6, nach '## Completion Triggers (V39)')", out)
            self.assertEqual(out.count("abschnitt-fehlt-en"), 1, out)
            self.assertNotIn("Notification System", out)

    def test_translated_headings_are_paired_through_the_section_text(self):
        # Nachbau von docs/entwicklung.md: "## Diagramm-Seiten" fehlte, gemeldet wurde
        # "## Bekannte Einschränkungen". Die Ueberschriften teilen kein Wort -- der Abschnittstext
        # schon: Code, Pfade und Bezeichner stehen in beiden Sprachen gleich da.
        de = ("# Entwicklung\n\n## Hygiene-Gate\n`scripts/ci/public-hygiene.sh`, `--tracked`\n\n"
              "## Diagramm-Seiten\nErzeugt von `scripts/docs-diagrams.py` mit `--add-missing` und `--check`.\n\n"
              "## Abhängigkeits-Meldungen (Frontend)\n`npm audit`, `dep_vuln_check.py`, `package-lock.json`\n\n"
              "## Bekannte Einschränkungen\n`pool-size=1`, `SQLITE_BUSY`, `HikariCP`\n")
        en = ("# Development\n\n## Hygiene gate\n`scripts/ci/public-hygiene.sh`, `--tracked`\n\n"
              "## Dependency advisories (frontend)\n`npm audit`, `dep_vuln_check.py`, `package-lock.json`\n\n"
              "## Known Limitations\n`pool-size=1`, `SQLITE_BUSY`, `HikariCP`\n")
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de, en))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: abschnitt-fehlt-en a.md '## Diagramm-Seiten'", out)
            self.assertNotIn("Bekannte Einschränkungen", out)
            self.assertNotIn("Abhängigkeits-Meldungen", out)

    def test_moved_section_is_reported_on_both_sides(self):
        # Nachbau von docs/en/datenmodell.md: gleiche Zahl an Ueberschriften, aber `goals` stand
        # hinter den Enums statt bei den Tabellen. Frueher blieb das unsichtbar (gleiche Anzahl).
        de = "# D\n\n## Tabellen\n\n### `todos`\n\n### `goals`\n\n## Enums\n\n### RunStatus\n"
        en = "# D\n\n## Tables\n\n### `todos`\n\n## Enums\n\n### RunStatus\n\n### `goals`\n"
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de, en))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: abschnitt-fehlt-en a.md '### `goals`' (Ueberschrift 4 von 6", out)
            self.assertIn("BEFUND: abschnitt-nur-en a.md '### `goals`' (Ueberschrift 6 von 6", out)

    def test_same_level_sequence_is_not_paired(self):
        # Gleiche Ebenenfolge: ob eine Ueberschrift uebersetzt oder ersetzt ist, sagt der Text
        # allein nicht sicher -- die Pruefung bleibt dort stumm, wie bisher.
        de = "# D\n\n## Eins\n\n## Zwei\n"
        en = "# D\n\n## One\n\n## Something else\n"
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de, en))
            self.assertEqual(code, 0, out)

    def test_without_any_signal_the_later_section_counts_as_missing(self):
        # Reine Prosa, ganz uebersetzte Ueberschriften: keine Aehnlichkeit, nur die Ebenen.
        # Dann bleibt es eine Vermutung, und sie faellt wie frueher auf den spaeteren Abschnitt.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            de_file, en_file = root / "de.md", root / "en.md"
            de_file.write_text("# D\n\n## Eins\nText.\n\n## Zwei\nMehr Text.\n")
            en_file.write_text("# D\n\n## One\nText.\n")
            only_de, only_en = dpc.align_sections(dpc.sections(de_file), dpc.sections(en_file))
            self.assertEqual((only_de, only_en), ([2], []))

    def test_sections_skip_code_blocks_like_headings(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "a.md"
            f.write_text("# Echt\nvorn\n```bash\n# Kommentar\n```\n## Auch echt\nhinten\n")
            got = dpc.sections(f)
            self.assertEqual([h for h, _ in got], dpc.headings(f))
            self.assertIn("# Kommentar", got[0][1])
            self.assertEqual(got[1][1], "hinten")


class RepoTest(unittest.TestCase):
    def test_this_repository_is_green(self):
        code, out = run_script(REPO)
        self.assertEqual(code, 0, out)
        self.assertIn("gate_status: GREEN", out)


if __name__ == "__main__":
    unittest.main()
