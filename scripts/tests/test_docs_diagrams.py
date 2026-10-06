"""Tests for scripts/docs-diagrams.py (TODO f54bb95d, English version: TODO 47bd4d27).

Checked on a miniature docs/ with known contents so that the tests
do not move along when the real diagram pages grow. The focus is
on the error paths: a gate that does not report drift is worthless — and so is one
that lets a German change through without the English follow-up.

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
├── alt.java     ← Weg damit [ENTFERNT]
├── zahl.java    ← 400/422
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

# Translation tables of the miniature pages: key = German text piece.
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
    "Weg damit": "Gone",
    "Kommentar": "Comment",
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
    """The generated header line <!-- ... --> is no part of the page structure."""
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

    # -- Generating ----------------------------------------------------------
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
        """The error that triggered the rebuild: <Ding> vanished in the browser."""
        html = self.build()
        self.assertIn("List&lt;Ding&gt;", html)
        self.assertNotIn("List<Ding>", html)
        self.assertIn('A["Start<br/>zweizeilig"]', html)

    def test_two_paragraph_subsections_become_a_card_grid(self):
        html = self.build()
        self.assertIn('<div class="grid">', html)
        self.assertIn("<h3>Linke Karte</h3>", html)
        # A single subsection with a table fits into no card.
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
        self.assertIn("[Beispielseite](beispiel-diagrams.md)", index)   # prefix shortened
        self.assertIn("Zusammen 2 Diagramme in 2 Seiten.", index)

    def test_markdown_renderer_reproduces_the_german_source(self):
        """The English Markdown page arises with the same renderer from the same structure."""
        self.assertEqual(tool.render_markdown(tool.parse(SOURCE)), SOURCE)

    # -- English version -------------------------------------------------
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

    def test_english_tree_translates_comments_and_markers_only(self):
        """Paths stay, comments after ← come from the table, marks per language (08e6fa4d)."""
        self.build()
        page = (self.en / "beispiel-diagrams.md").read_text(encoding="utf-8")
        self.assertIn("```text\nprojekt/\n├── alt.java     ← Gone [REMOVED]\n"
                      "├── zahl.java    ← 400/422\n└── datei.java   ← Comment [NEW]\n```", page)

    def test_english_tree_html_shows_english_badges(self):
        self.build()
        html = (self.en / "beispiel-diagrams.html").read_text(encoding="utf-8")
        self.assertIn('<span class="comment">← Comment</span>', html)
        self.assertIn('<span class="badge badge-green">NEW</span>', html)
        self.assertIn('<span class="badge badge-red">REMOVED</span>', html)
        self.assertNotIn("Kommentar", html)
        self.assertNotIn(">NEU<", html)

    def test_untranslated_tree_comment_is_red(self):
        table = dict(TABLE)
        del table["Kommentar"]
        self.write_table("beispiel", table)
        code, output = self.run_tool("--check")
        self.assertEqual(code, 1, output)
        self.assertIn('"Kommentar"', output)

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

    # -- GitHub Pages (--site, TODO ee5a4a04) -------------------------------
    def site(self, *extra):
        out = self.root / "_site"
        code, output = self.run_tool("--site", str(out), *extra)
        return code, output, out

    def test_site_writes_both_languages_and_an_index(self):
        code, output, out = self.site()
        self.assertEqual(code, 0, output)
        for lang in ("de", "en"):
            for stem in ("beispiel", "zweite"):
                self.assertTrue((out / lang / f"{stem}-diagrams.html").is_file(), f"{lang}/{stem}")
        self.assertIn('<html lang="de">', (out / "de" / "beispiel-diagrams.html").read_text(encoding="utf-8"))
        self.assertIn("<h1>Product — Sample page</h1>",
                      (out / "en" / "beispiel-diagrams.html").read_text(encoding="utf-8"))
        self.assertEqual(sorted(p.name for p in out.iterdir()), ["de", "en", "index.html"])

    def test_site_index_comes_from_the_same_table_as_diagramme_md(self):
        _, _, out = self.site()
        index = (out / "index.html").read_text(encoding="utf-8")
        self.assertIn('<html lang="de">', index)
        self.assertIn('<a href="de/beispiel-diagrams.html">Beispielseite</a>', index)
        self.assertIn("<td>Untertitel der Seite</td><td>1</td>", index)
        self.assertIn("Zusammen 2 Diagramme in 2 Seiten.", index)
        self.assertIn('<a href="en/index.html">English</a>', index)
        english = (out / "en" / "index.html").read_text(encoding="utf-8")
        self.assertIn('<html lang="en">', english)
        self.assertIn('<a href="beispiel-diagrams.html">Sample page</a>', english)
        self.assertIn("In total, 2 diagrams across 2 pages.", english)
        self.assertIn('<a href="../index.html">Deutsch</a>', english)

    def test_site_pages_link_to_the_index_and_the_other_language(self):
        _, _, out = self.site()
        german = (out / "de" / "beispiel-diagrams.html").read_text(encoding="utf-8")
        self.assertIn('<a href="../index.html">', german)
        self.assertIn('<a href="../en/beispiel-diagrams.html">English</a>', german)
        english = (out / "en" / "beispiel-diagrams.html").read_text(encoding="utf-8")
        self.assertIn('<a href="index.html">', english)
        self.assertIn('<a href="../de/beispiel-diagrams.html">Deutsch</a>', english)

    def test_site_is_built_from_the_source_and_leaves_docs_alone(self):
        """The build is made from the Markdown source, not from the HTML files in the repo."""
        code, output, _ = self.site()
        self.assertEqual(code, 0, output)
        self.assertFalse((self.docs / "beispiel-diagrams.html").exists())
        self.assertFalse((self.docs / "en").exists())

    def test_site_with_open_translation_fails_without_writing(self):
        table = dict(TABLE)
        del table["Ein Punkt"]
        self.write_table("beispiel", table)
        code, output, out = self.site()
        self.assertEqual(code, 1, output)
        self.assertIn('FEHLT docs/i18n/beispiel-diagrams.en.json: "Ein Punkt"', output)
        self.assertFalse(out.exists())

    def test_site_refuses_a_non_empty_directory(self):
        out = self.root / "_site"
        out.mkdir()
        (out / "alt.html").write_text("frueher", encoding="utf-8")
        code, output, _ = self.site()
        self.assertEqual(code, 2, output)
        self.assertEqual(sorted(p.name for p in out.iterdir()), ["alt.html"])

    def test_site_excludes_check_and_add_missing(self):
        self.assertEqual(self.site("--check")[0], 2)
        self.assertEqual(self.site("--add-missing")[0], 2)

    def test_site_needs_a_directory(self):
        self.assertEqual(self.run_tool("--site")[0], 2)

    # -- Checking -----------------------------------------------------------
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
        # The check mode writes nothing back.
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
        # The write run clears away the orphaned product, afterwards --check is green.
        code, _ = self.run_tool()
        self.assertEqual(code, 0)
        self.assertFalse(stale.exists())
        self.assertEqual(self.run_tool("--check")[0], 0)

    # -- Translation tables ---------------------------------------------
    def test_changed_german_text_without_translation_is_red(self):
        """The core of the rule: German cannot silently continue without English following."""
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
        # The German page does not depend on the translation.
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
        # A value with "## " at the start would begin a new section in the English page.
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
        self.assertEqual(code, 1, output)          # still open: empty value and orphaned key
        data = self.read_table("beispiel")
        keys = list(data)
        self.assertEqual(data["Ein Punkt"], "")
        self.assertEqual(keys.index("Ein Punkt"), keys.index("Rechte Karte") + 1)
        self.assertEqual(keys[-1], "Alt")           # orphaned ones stay, at the end
        self.assertEqual(data["Linke Karte"], "Left card")
        # --add-missing creates no pages.
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

    # -- Broken sources ---------------------------------------------------
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
    """Which text pieces a page has — exactly these keys its table needs."""

    def setUp(self):
        self.segments = tool.segments(tool.parse(SOURCE))

    def test_reading_order(self):
        self.assertEqual(self.segments[:4], ["Produkt — Beispielseite", "Untertitel der Seite",
                                             "Erster Abschnitt", "Beiwort"])
        self.assertLess(self.segments.index("Start<br/>zweizeilig"), self.segments.index("Linke Karte"))

    def test_code_ids_and_code_blocks_are_not_segments(self):
        self.assertNotIn("`a`", self.segments)                   # only inline code
        self.assertNotIn("A", self.segments)                     # node ID
        self.assertFalse(any("datei.java" in s for s in self.segments))

    def test_tree_comments_are_segments_without_markers(self):
        """The comment after ← is a text piece, without mark; without letters none (08e6fa4d)."""
        self.assertIn("Kommentar", self.segments)
        self.assertIn("Weg damit", self.segments)
        self.assertNotIn("400/422", self.segments)
        self.assertFalse(any("[NEU]" in s or "[ENTFERNT]" in s for s in self.segments))

    def test_no_duplicates(self):
        self.assertEqual(len(self.segments), len(set(self.segments)))

    def test_fixture_table_matches_the_segments(self):
        self.assertEqual(sorted(self.segments), sorted(TABLE))


class MermaidLabelsTest(unittest.TestCase):
    """Labels per diagram type: only they are translated, the rest stays character by character."""

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
    """The actual gate: docs/ in the repo must be congruent, in both languages."""

    repo = SCRIPTS.parent

    def test_repository_pages_are_in_sync(self):
        with contextlib.redirect_stdout(io.StringIO()) as out, \
                contextlib.redirect_stderr(io.StringIO()) as err:
            code = tool.main(["--root", str(self.repo), "--check"])
        self.assertEqual(code, 0, "Diagramm-Seiten weichen von Quelle oder Uebersetzung ab —"
                                  " 'python3 scripts/docs-diagrams.py' laufen lassen\n"
                                  + out.getvalue() + err.getvalue())

    def test_repository_site_has_every_page_in_both_languages(self):
        with tempfile.TemporaryDirectory() as temp:
            out = Path(temp) / "_site"
            with contextlib.redirect_stdout(io.StringIO()) as stdout, \
                    contextlib.redirect_stderr(io.StringIO()) as stderr:
                code = tool.main(["--root", str(self.repo), "--site", str(out)])
            self.assertEqual(code, 0, stdout.getvalue() + stderr.getvalue())
            stems = sorted(p.name[:-len("-diagrams.md")] for p in (self.repo / "docs").glob("*-diagrams.md"))
            for lang in ("de", "en"):
                self.assertEqual(sorted(p.name[:-len("-diagrams.html")]
                                        for p in (out / lang).glob("*-diagrams.html")), stems)
            self.assertTrue((out / "index.html").is_file())
            self.assertTrue((out / "en" / "index.html").is_file())

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
