"""Tests fuer scripts/docs-diagrams.py (TODO f54bb95d, englische Fassung: TODO 47bd4d27).

Geprueft wird auf einem Miniatur-docs/ mit bekannten Inhalten, damit die Tests
nicht mitwandern, wenn die echten Diagramm-Seiten wachsen. Der Schwerpunkt liegt
auf den Fehlerwegen: Ein Gate, das Drift nicht meldet, ist wertlos — und eines,
das eine deutsche Aenderung ohne englische Nachfuehrung durchlaesst, ebenso.

Run: python3 -m unittest discover -s scripts/tests -p 'test_docs_diagrams.py'
"""
import contextlib
import importlib.util
import io
import json
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

# Uebersetzungstabellen der Miniatur-Seiten: Schluessel = deutsches Textstueck.
TABLE = {
    "Produkt — Beispielseite": "Product — Sample page",
    "Untertitel der Seite": "Page subtitle",
    "Erster Abschnitt": "First section",
    "Beiwort": "Tagline",
    "Ein Absatz mit `Code` und **fett**.": "A paragraph with `Code` and **bold**.",
    "Start<br/>zweizeilig": "Start<br/>two lines",
    "List<Ding>": "List<Ding>",
    "Linke Karte": "Left card",
    "Text links.": "Text on the left.",
    "Rechte Karte": "Right card",
    "Ein Punkt": "One point",
    "Noch einer": "Another one",
    "Zweiter Abschnitt": "Second section",
    "Kopf": "Head",
    "Wert": "Value",
    "b | c": "b | c",
    "Einzelner Unterabschnitt": "Single subsection",
    "Auch": "Also",
    "Tabelle": "Table",
    "x": "x",
    "y": "y",
}

TABLE_SECOND = {
    "Produkt — Zweite Seite": "Product — Second page",
    "Noch ein Untertitel": "Another subtitle",
    "Nur ein Abschnitt": "Only one section",
}


def strip_note(text):
    """Die erzeugte Kopfzeile <!-- ... --> ist kein Teil der Seitenstruktur."""
    lines = text.split("\n")
    if lines and lines[0].startswith("<!--"):
        lines = lines[1:]
    return "\n".join(lines)


class DocsDiagramsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.docs = self.root / "docs"
        self.docs.mkdir()
        (self.docs / "beispiel-diagrams.md").write_text(SOURCE, encoding="utf-8")
        (self.docs / "zweite-diagrams.md").write_text(SECOND, encoding="utf-8")
        self.write_table("beispiel", TABLE)
        self.write_table("zweite", TABLE_SECOND)
        self.target = self.docs / "beispiel-diagrams.html"
        self.en = self.docs / "en"

    def table_path(self, stem):
        return self.docs / "i18n" / f"{stem}-diagrams.en.json"

    def write_table(self, stem, table):
        path = self.table_path(stem)
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(table, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def read_table(self, stem):
        return json.loads(self.table_path(stem).read_text(encoding="utf-8"))

    def run_tool(self, *args):
        with contextlib.redirect_stdout(io.StringIO()) as out, \
                contextlib.redirect_stderr(io.StringIO()) as err:
            code = tool.main(["--root", str(self.root), *args])
        return code, out.getvalue() + err.getvalue()

    def build(self):
        code, output = self.run_tool()
        self.assertEqual(code, 0, output)
        return self.target.read_text(encoding="utf-8")

    # -- Erzeugen ----------------------------------------------------------
    def test_build_writes_html_and_index(self):
        html = self.build()
        self.assertIn('<html lang="de">', html)
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

    def test_markdown_renderer_reproduces_the_german_source(self):
        """Die englische Markdown-Seite entsteht mit demselben Renderer aus derselben Struktur."""
        self.assertEqual(tool.render_markdown(tool.parse(SOURCE)), SOURCE)

    # -- Englische Fassung -------------------------------------------------
    def test_build_writes_english_pages_and_index(self):
        self.build()
        for name in ("beispiel-diagrams.md", "beispiel-diagrams.html", "zweite-diagrams.md",
                     "zweite-diagrams.html", "diagramme.md"):
            self.assertTrue((self.en / name).exists(), name)

    def test_english_markdown_is_translated_and_marked_as_generated(self):
        self.build()
        page = (self.en / "beispiel-diagrams.md").read_text(encoding="utf-8")
        self.assertTrue(page.startswith(
            "<!-- Generated from docs/beispiel-diagrams.md and"
            " docs/i18n/beispiel-diagrams.en.json by scripts/docs-diagrams.py"), page[:200])
        self.assertIn("# Product — Sample page\n", page)
        self.assertIn("## First section — *Tagline*\n", page)
        self.assertIn("- One point\n", page)
        self.assertIn("| `a` | b \\| c |", page)

    def test_english_mermaid_keeps_ids_and_arrows(self):
        self.build()
        page = (self.en / "beispiel-diagrams.md").read_text(encoding="utf-8")
        self.assertIn('    A["Start<br/>two lines"] --> B["List<Ding>"]', page)

    def test_english_page_keeps_code_blocks_unchanged(self):
        self.build()
        page = (self.en / "beispiel-diagrams.md").read_text(encoding="utf-8")
        self.assertIn("```text\nprojekt/\n└── datei.java   ← Kommentar [NEU]\n```", page)

    def test_english_page_keeps_structure_and_mermaid_skeleton(self):
        self.build()
        page = strip_note((self.en / "beispiel-diagrams.md").read_text(encoding="utf-8"))
        self.assertEqual(tool.shape(tool.parse(page)), tool.shape(tool.parse(SOURCE)))

    def test_english_html_is_english(self):
        self.build()
        html = (self.en / "beispiel-diagrams.html").read_text(encoding="utf-8")
        self.assertIn('<html lang="en">', html)
        self.assertIn("<h1>Product — Sample page</h1>", html)
        self.assertIn('<section id="first-section">', html)
        self.assertIn("docs/i18n/beispiel-diagrams.en.json", html)
        self.assertNotIn("Beispielseite", html)

    def test_english_index_lists_the_english_titles(self):
        self.build()
        index = (self.en / "diagramme.md").read_text(encoding="utf-8")
        self.assertIn("# Diagrams", index)
        self.assertIn("| [Sample page](beispiel-diagrams.md) | Page subtitle | 1 |", index)
        self.assertIn("In total, 2 diagrams across 2 pages.", index)

    # -- Pruefen -----------------------------------------------------------
    def test_check_is_green_right_after_build(self):
        self.build()
        code, output = self.run_tool("--check")
        self.assertEqual(code, 0, output)
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

    def test_check_reports_hand_edited_english_page(self):
        self.build()
        (self.en / "beispiel-diagrams.md").write_text("von Hand", encoding="utf-8")
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("DRIFT docs/en/beispiel-diagrams.md", output)

    def test_check_reports_generated_page_without_source(self):
        self.build()
        stale = self.en / "alt-diagrams.html"
        stale.write_text("frueher erzeugt", encoding="utf-8")
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("DRIFT docs/en/alt-diagrams.html", output)
        self.assertTrue(stale.exists())
        # Der Schreiblauf raeumt das verwaiste Erzeugnis weg, danach ist --check gruen.
        code, _ = self.run_tool()
        self.assertEqual(code, 0)
        self.assertFalse(stale.exists())
        self.assertEqual(self.run_tool("--check")[0], 0)

    # -- Uebersetzungstabellen ---------------------------------------------
    def test_changed_german_text_without_translation_is_red(self):
        """Der Kern der Regel: Deutsch kann nicht still weiterlaufen, ohne dass Englisch nachzieht."""
        self.build()
        (self.docs / "beispiel-diagrams.md").write_text(
            SOURCE.replace("- Ein Punkt", "- Ein neuer Punkt"), encoding="utf-8")
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1, output)
        self.assertIn('FEHLT docs/i18n/beispiel-diagrams.en.json: "Ein neuer Punkt"', output)
        self.assertIn('VERWAIST docs/i18n/beispiel-diagrams.en.json: "Ein Punkt"', output)

    def test_missing_translation_is_red_and_english_page_not_written(self):
        table = dict(TABLE)
        del table["Ein Punkt"]
        self.write_table("beispiel", table)
        code, output = self.run_tool()
        self.assertEqual(code, 1, output)
        self.assertIn('FEHLT docs/i18n/beispiel-diagrams.en.json: "Ein Punkt"', output)
        self.assertFalse((self.en / "beispiel-diagrams.md").exists())
        self.assertFalse((self.en / "diagramme.md").exists())
        # Die deutsche Seite haengt nicht an der Uebersetzung.
        self.assertTrue(self.target.exists())
        self.assertEqual(self.run_tool("--check")[0], 1)

    def test_empty_translation_counts_as_missing(self):
        self.write_table("beispiel", dict(TABLE, **{"Ein Punkt": ""}))
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn('FEHLT docs/i18n/beispiel-diagrams.en.json: "Ein Punkt"', output)

    def test_orphaned_key_is_red(self):
        self.write_table("beispiel", dict(TABLE, **{"Gibt es nicht": "Does not exist"}))
        code, output = self.run_tool()
        self.assertEqual(code, 1)
        self.assertIn('VERWAIST docs/i18n/beispiel-diagrams.en.json: "Gibt es nicht"', output)

    def test_missing_table_is_red(self):
        self.table_path("zweite").unlink()
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("FEHLT docs/i18n/zweite-diagrams.en.json", output)
        self.assertIn("--add-missing", output)

    def test_table_without_german_page_is_red(self):
        self.build()
        self.write_table("weg", {"Alt": "Old"})
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("VERWAIST docs/i18n/weg-diagrams.en.json", output)

    def test_translation_must_keep_inline_code(self):
        key = "Ein Absatz mit `Code` und **fett**."
        self.write_table("beispiel", dict(TABLE, **{key: "A paragraph with `code` and **bold**."}))
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("UNGUELTIG docs/i18n/beispiel-diagrams.en.json", output)
        self.assertIn("Inline-Code", output)

    def test_translation_must_keep_numbers(self):
        (self.docs / "dritte-diagrams.md").write_text(
            "# Produkt — Dritte\n\nUntertitel\n\n## Abschnitt\n\nGenau 3 Versuche.\n", encoding="utf-8")
        self.write_table("dritte", {"Produkt — Dritte": "Product — Third", "Untertitel": "Subtitle",
                                    "Abschnitt": "Section", "Genau 3 Versuche.": "Exactly 4 attempts."})
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn('UNGUELTIG docs/i18n/dritte-diagrams.en.json: "Genau 3 Versuche."', output)
        self.assertIn("Zahlen", output)

    def test_translation_breaking_the_mermaid_syntax_is_red(self):
        self.write_table("beispiel", dict(TABLE, **{"List<Ding>": 'List"Ding'}))
        code, output = self.run_tool()
        self.assertEqual(code, 1, output)
        self.assertIn("STRUKTUR docs/en/beispiel-diagrams.md", output)
        self.assertFalse((self.en / "beispiel-diagrams.md").exists())

    def test_translation_breaking_the_page_structure_is_red(self):
        # Ein Wert mit "## " am Anfang wuerde in der englischen Seite einen neuen Abschnitt beginnen.
        self.write_table("beispiel", dict(TABLE, **{"Text links.": "## Text on the left."}))
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1)
        self.assertIn("STRUKTUR docs/en/beispiel-diagrams.md", output)

    def test_broken_table_fails_hard(self):
        self.table_path("beispiel").write_text("{kaputt", encoding="utf-8")
        code, output = self.run_tool("--check")
        self.assertEqual(code, 2)
        self.assertIn("beispiel-diagrams.en.json", output)

    def test_duplicate_key_fails_hard(self):
        self.table_path("zweite").write_text(
            '{"Nur ein Abschnitt": "Only", "Nur ein Abschnitt": "Only one section"}', encoding="utf-8")
        code, output = self.run_tool("--check")
        self.assertEqual(code, 2)
        self.assertIn("doppelt", output)

    def test_add_missing_writes_empty_stubs_in_source_order(self):
        table = dict(TABLE)
        del table["Ein Punkt"]
        table["Alt"] = "Old"
        self.write_table("beispiel", table)
        code, output = self.run_tool("--add-missing")
        self.assertEqual(code, 1, output)          # noch offen: leerer Wert und verwaister Schluessel
        data = self.read_table("beispiel")
        keys = list(data)
        self.assertEqual(data["Ein Punkt"], "")
        self.assertEqual(keys.index("Ein Punkt"), keys.index("Rechte Karte") + 1)
        self.assertEqual(keys[-1], "Alt")           # verwaiste bleiben stehen, am Ende
        self.assertEqual(data["Linke Karte"], "Left card")
        # --add-missing erzeugt keine Seiten.
        self.assertFalse(self.target.exists())

    def test_add_missing_creates_a_missing_table(self):
        self.table_path("zweite").unlink()
        code, _ = self.run_tool("--add-missing")
        self.assertEqual(code, 1)
        self.assertEqual(self.read_table("zweite"), dict.fromkeys(TABLE_SECOND, ""))

    def test_add_missing_on_complete_tables_is_green(self):
        code, output = self.run_tool("--add-missing")
        self.assertEqual(code, 0, output)
        self.assertEqual(self.read_table("beispiel"), TABLE)

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

    def test_mermaid_type_without_label_rules_fails(self):
        (self.docs / "beispiel-diagrams.md").write_text(
            "# Titel\n\nUntertitel\n\n## A\n\n```mermaid\npie title Anteile\n    \"Eins\" : 1\n```\n",
            encoding="utf-8")
        code, output = self.run_tool()
        self.assertEqual(code, 2)
        self.assertIn("pie", output)
        self.assertIn("beispiel-diagrams.md", output)

    def test_missing_docs_directory_fails(self):
        code, output = self.run_tool("--root", str(self.root / "gibtsnicht"))
        self.assertEqual(code, 2)
        self.assertIn("fehlt", output)


class SegmentTest(unittest.TestCase):
    """Welche Textstuecke eine Seite hat — genau diese Schluessel braucht ihre Tabelle."""

    def setUp(self):
        self.segments = tool.segments(tool.parse(SOURCE))

    def test_reading_order(self):
        self.assertEqual(self.segments[:4], ["Produkt — Beispielseite", "Untertitel der Seite",
                                             "Erster Abschnitt", "Beiwort"])
        self.assertLess(self.segments.index("Start<br/>zweizeilig"), self.segments.index("Linke Karte"))

    def test_code_ids_and_code_blocks_are_not_segments(self):
        self.assertNotIn("`a`", self.segments)                   # nur Inline-Code
        self.assertNotIn("A", self.segments)                     # Knoten-ID
        self.assertFalse(any("datei.java" in s for s in self.segments))

    def test_no_duplicates(self):
        self.assertEqual(len(self.segments), len(set(self.segments)))

    def test_fixture_table_matches_the_segments(self):
        self.assertEqual(sorted(self.segments), sorted(TABLE))


class MermaidLabelsTest(unittest.TestCase):
    """Beschriftungen je Diagrammtyp: nur sie werden uebersetzt, der Rest bleibt Zeichen fuer Zeichen."""

    labels = staticmethod(lambda code: tool.mermaid_labels.labels(code))

    def test_flowchart(self):
        code = "\n".join([
            "flowchart LR",
            '    subgraph Ablauf["Mein Ablauf"]',
            '        A["Start"] -->|"weiter"| B{"Frage?"}',
            "        B -->|ja| C[Ende]",
            "        B -.vielleicht.-> D(Rund)",
            "        C -- Text --> D",
            "        D == dick ==> E[(Speicher)]",
            "    end",
            "    subgraph Compose",
            "    end",
            "    PENDING --> RUNNING",
            "    style A fill:#fff,color:#000",
        ])
        self.assertEqual(self.labels(code), ["Mein Ablauf", "Start", "weiter", "Frage?", "ja", "Ende",
                                             "vielleicht", "Rund", "Text", "dick", "Speicher"])

    def test_flowchart_plain_arrows_carry_no_label(self):
        self.assertEqual(self.labels("graph TB\n    A --> B\n    B --- C\n    C -.-> D\n    D ==> A"), [])

    def test_sequence(self):
        code = "\n".join([
            "sequenceDiagram",
            "    participant A as Anfrager",
            "    participant B",
            "    A->>B: Hallo: Welt",
            "    alt vorhanden",
            "        B-->>A: ok",
            "    else nicht da",
            "        B--)A: spaeter",
            "    else",
            "    end",
            "    Note over A,B: Notiz<br/>zweite",
            "    loop jede Minute",
            "        A->>+B: frage",
            "    end",
        ])
        self.assertEqual(self.labels(code), ["Anfrager", "Hallo: Welt", "vorhanden", "ok", "nicht da",
                                             "spaeter", "Notiz<br/>zweite", "jede Minute", "frage"])

    def test_state(self):
        code = "stateDiagram-v2\n    [*] --> A : los\n    A --> B\n    B --> [*]\n    A : beschrieben"
        self.assertEqual(self.labels(code), ["los", "beschrieben"])

    def test_class_body_is_code(self):
        code = "\n".join([
            "classDiagram",
            "    class Foo {",
            "        <<interface>>",
            "        +bar(x: String) Baz",
            "    }",
            "    Foo --> Baz : benutzt",
            "    Foo <|.. Qux",
        ])
        self.assertEqual(self.labels(code), ["benutzt"])

    def test_er(self):
        code = "\n".join([
            "erDiagram",
            '    A ||--o{ B : "hat viele"',
            "    A }o--o{ C : nutzt",
            "    A {",
            "        text id PK",
            '        text name "Anzeigename"',
            "    }",
        ])
        self.assertEqual(self.labels(code), ["hat viele", "nutzt", "Anzeigename"])

    def test_unknown_diagram_type_fails(self):
        with self.assertRaises(tool.mermaid_labels.MermaidError):
            self.labels('pie title Anteile\n    "Eins" : 1')

    def test_unknown_sequence_line_fails(self):
        with self.assertRaises(tool.mermaid_labels.MermaidError):
            self.labels("sequenceDiagram\n    box Aqua Gruppe\n    end")

    def test_translate_replaces_only_labels(self):
        code = 'flowchart LR\n    A["eins"] -->|zwei| B{"drei"}\n    style A fill:#fff'
        translated = tool.mermaid_labels.translate(code, str.upper)
        self.assertEqual(translated, 'flowchart LR\n    A["EINS"] -->|ZWEI| B{"DREI"}\n    style A fill:#fff')
        self.assertEqual(tool.mermaid_labels.skeleton(translated), tool.mermaid_labels.skeleton(code))


class RepositoryTest(unittest.TestCase):
    """Das eigentliche Gate: docs/ im Repo muss deckungsgleich sein, in beiden Sprachen."""

    repo = SCRIPTS.parent

    def test_repository_pages_are_in_sync(self):
        with contextlib.redirect_stdout(io.StringIO()) as out, \
                contextlib.redirect_stderr(io.StringIO()) as err:
            code = tool.main(["--root", str(self.repo), "--check"])
        self.assertEqual(code, 0, "Diagramm-Seiten weichen von Quelle oder Uebersetzung ab —"
                                  " 'python3 scripts/docs-diagrams.py' laufen lassen\n"
                                  + out.getvalue() + err.getvalue())

    def test_repository_english_pages_keep_the_german_structure(self):
        sources = sorted((self.repo / "docs").glob("*-diagrams.md"))
        self.assertTrue(sources)
        for source in sources:
            english = self.repo / "docs" / "en" / source.name
            with self.subTest(page=source.name):
                self.assertTrue(english.exists())
                de = tool.parse(source.read_text(encoding="utf-8"))
                en = tool.parse(strip_note(english.read_text(encoding="utf-8")))
                self.assertEqual(tool.shape(en), tool.shape(de))


if __name__ == "__main__":
    unittest.main()
