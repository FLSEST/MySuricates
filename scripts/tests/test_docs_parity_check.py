"""Tests fuer scripts/public-readiness/docs_parity_check.py (TODO c58d7402).

Zwei der Faelle halten Fehler fest, die die Erstfassung des Skripts wirklich hatte und die
beide zu **Falschbefunden** gefuehrt haetten — der teuersten Sorte Fehler in einem Gate:

* `test_slug_keeps_each_space`   — Slug zog Leerzeichenfolgen zusammen, korrekte Anker
                                   galten als tot.
* `test_headings_skip_code_block` — `# Kommentar` in einem Shell-Beispiel zaehlte als
                                   Ueberschrift und erzeugte Phantom-Unterschiede.

Run: python3 -m unittest discover -s scripts/tests -p 'test_docs_parity_check.py'
"""
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

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
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp, extra_de_file="diagramme.md")
            code, out = run_script(root)
            self.assertEqual(code, 0, out)
            self.assertIn("bekannt: kein-en-gegenstueck docs/diagramme.md", out)
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


class RepoTest(unittest.TestCase):
    def test_this_repository_is_green(self):
        code, out = run_script(REPO)
        self.assertEqual(code, 0, out)
        self.assertIn("gate_status: GREEN", out)


if __name__ == "__main__":
    unittest.main()
