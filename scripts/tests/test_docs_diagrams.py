"""Tests fuer scripts/docs-diagrams.py (TODO f54bb95d).

Geprueft wird auf einem Miniatur-docs/ mit bekannten Inhalten, damit die Tests
nicht mitwandern, wenn die echten Diagramm-Seiten wachsen. Der Schwerpunkt liegt
auf den Fehlerwegen: Ein Gate, das Drift nicht meldet, ist wertlos.

Run: python3 -m unittest discover -s scripts/tests -p 'test_docs_diagrams.py'
"""
import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


def load():
    spec = importlib.util.spec_from_file_location("docs_diagrams", SCRIPTS / "docs-diagrams.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


tool = load()

SOURCE = """# Produkt — Beispielseite

Untertitel der Seite

## Erster Abschnitt — *Beiwort*

Ein Absatz mit `Code` und **fett**.

```mermaid
flowchart LR
    A["Start<br/>zweizeilig"] --> B["List<Ding>"]
```

### Linke Karte

Text links.

### Rechte Karte

- Ein Punkt
- Noch einer

## Zweiter Abschnitt

| Kopf | Wert |
|---|---|
| `a` | b \\| c |

```text
projekt/
└── datei.java   ← Kommentar [NEU]
```

### Einzelner Unterabschnitt

| Auch | Tabelle |
|---|---|
| x | y |
"""

SECOND = """# Produkt — Zweite Seite

Noch ein Untertitel

## Nur ein Abschnitt

```mermaid
graph LR
    A --> B
```
"""


class DocsDiagramsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.docs = self.root / "docs"
        self.docs.mkdir()
        (self.docs / "beispiel-diagrams.md").write_text(SOURCE, encoding="utf-8")
        (self.docs / "zweite-diagrams.md").write_text(SECOND, encoding="utf-8")
        self.target = self.docs / "beispiel-diagrams.html"

    def run_tool(self, *args):
        with contextlib.redirect_stdout(io.StringIO()) as out, \
                contextlib.redirect_stderr(io.StringIO()) as err:
            code = tool.main(["--root", str(self.root), *args])
        return code, out.getvalue() + err.getvalue()

    def build(self):
        code, _ = self.run_tool()
        self.assertEqual(code, 0)
        return self.target.read_text(encoding="utf-8")

    # -- Erzeugen ----------------------------------------------------------
    def test_build_writes_html_and_index(self):
        html = self.build()
        self.assertIn("<h1>Produkt — Beispielseite</h1>", html)
        self.assertIn("<p>Untertitel der Seite</p>", html)
        self.assertIn('<section id="erster-abschnitt">', html)
        self.assertIn("<h2>Erster Abschnitt <span>Beiwort</span></h2>", html)
        self.assertIn('<a href="#zweiter-abschnitt">Zweiter Abschnitt</a>', html)
        self.assertTrue((self.docs / "diagramme.md").exists())

    def test_generated_marker_names_the_source(self):
        self.assertIn("docs/beispiel-diagrams.md", self.build())

    def test_mermaid_angle_brackets_are_escaped_but_br_survives(self):
        """Der Fehler, der den Umbau ausgeloest hat: <Ding> verschwand im Browser."""
        html = self.build()
        self.assertIn("List&lt;Ding&gt;", html)
        self.assertNotIn("List<Ding>", html)
        self.assertIn('A["Start<br/>zweizeilig"]', html)

    def test_two_paragraph_subsections_become_a_card_grid(self):
        html = self.build()
        self.assertIn('<div class="grid">', html)
        self.assertIn("<h3>Linke Karte</h3>", html)
        # Ein einzelner Unterabschnitt mit Tabelle passt in keine Karte.
        self.assertNotIn('<div class="card">\n      <h3>Einzelner', html)
        self.assertIn("<h3>Einzelner Unterabschnitt</h3>", html)

    def test_table_escaped_pipe_stays_one_cell(self):
        self.assertIn("<td>b | c</td>", self.build())

    def test_tree_gets_directory_comment_and_badge(self):
        html = self.build()
        self.assertIn('<span class="dir">projekt/</span>', html)
        self.assertIn('<span class="comment">← Kommentar</span>', html)
        self.assertIn('<span class="badge badge-green">NEU</span>', html)

    def test_index_counts_every_diagram(self):
        self.build()
        index = (self.docs / "diagramme.md").read_text(encoding="utf-8")
        self.assertIn("[Beispielseite](beispiel-diagrams.md)", index)   # Praefix gekuerzt
        self.assertIn("Zusammen 2 Diagramme in 2 Seiten.", index)

    # -- Pruefen -----------------------------------------------------------
    def test_check_is_green_right_after_build(self):
        self.build()
        code, output = self.run_tool("--check")
        self.assertEqual(code, 0)
        self.assertIn("OK", output)

    def test_check_reports_hand_edited_html(self):
        self.build()
        self.target.write_text("von Hand geaendert", encoding="utf-8")
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("DRIFT docs/beispiel-diagrams.html", output)
        # Der Pruefmodus schreibt nichts zurueck.
        self.assertEqual(self.target.read_text(encoding="utf-8"), "von Hand geaendert")

    def test_check_reports_changed_source(self):
        self.build()
        path = self.docs / "beispiel-diagrams.md"
        path.write_text(SOURCE.replace("Text links.", "Text rechts."), encoding="utf-8")
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("DRIFT", output)

    def test_check_reports_missing_html(self):
        self.build()
        self.target.unlink()
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("fehlt", output)

    # -- Kaputte Quellen ---------------------------------------------------
    def test_source_without_title_fails_without_writing(self):
        (self.docs / "beispiel-diagrams.md").write_text("Kein Titel\n", encoding="utf-8")
        code, output = self.run_tool()
        self.assertEqual(code, 2)
        self.assertIn("Titel", output)
        self.assertFalse(self.target.exists())

    def test_source_without_sections_fails(self):
        (self.docs / "beispiel-diagrams.md").write_text(
            "# Titel\n\nUntertitel\n", encoding="utf-8")
        code, output = self.run_tool()
        self.assertEqual(code, 2)
        self.assertIn("Abschnitt", output)

    def test_unterminated_fence_fails(self):
        (self.docs / "beispiel-diagrams.md").write_text(
            "# Titel\n\nUntertitel\n\n## A\n\n```mermaid\ngraph LR\n", encoding="utf-8")
        code, output = self.run_tool()
        self.assertEqual(code, 2)
        self.assertIn("Codezaun", output)

    def test_missing_docs_directory_fails(self):
        code, output = self.run_tool("--root", str(self.root / "gibtsnicht"))
        self.assertEqual(code, 2)
        self.assertIn("fehlt", output)

    # -- Echte Seiten ------------------------------------------------------
    def test_repository_pages_are_in_sync(self):
        """Das eigentliche Gate: docs/ im Repo muss deckungsgleich sein."""
        repo = SCRIPTS.parent
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            code = tool.main(["--root", str(repo), "--check"])
        self.assertEqual(code, 0, "docs/*-diagrams.html weicht von der Markdown-Quelle ab —"
                                  " 'python3 scripts/docs-diagrams.py' laufen lassen")


if __name__ == "__main__":
    unittest.main()
