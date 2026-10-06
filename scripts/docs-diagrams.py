#!/usr/bin/env python3
"""Generates the diagram pages in docs/ from their Markdown source (TODO f54bb95d),
plus their English version in docs/en/ (TODO 47bd4d27).

Why this exists
---------------
The diagram documents existed only as HTML. GitHub does not render HTML files in the
repository — a visitor saw source text there instead of diagrams. Mermaid in
```mermaid fences, on the other hand, GitHub renders natively.

Therefore the **Markdown file is the source** (`docs/<name>-diagrams.md`) and the
HTML version is the **product** (`docs/<name>-diagrams.html`, for GitHub Pages).
Not only the diagrams but the whole page is generated: prose, tables,
cards, file trees. Only that way does it stay at one source. If the script merely
copied the diagrams over, the rest of the HTML page would be a second, hand-maintained
version — exactly what is to be avoided.

The shell (colours, layout, Mermaid embedding, navigation) lives as a template
further down in this file and is the same for all pages; before, it stood nine times
almost verbatim in the HTML files.

In addition `docs/diagramme.md` is generated as a table of contents — likewise
generated, not maintained.

English version: the same source plus a translation table per page
------------------------------------------------------------------
The German page remains the source for the structure *and* the German text. The
English one is generated from it and a table per page, following the pattern of de.ts/en.ts
of the UI:

    docs/i18n/<name>-diagrams.en.json    {"German text piece": "English text", ...}

Generated are docs/en/<name>-diagrams.md and .html as well as docs/en/diagramme.md, with
the same file names as German, so that the DE/EN parity check pairs them. There is no
second, hand-maintained English version.

Text pieces (= keys) are titles, subtitles, headings including the epithet,
paragraphs, list items, table cells and the Mermaid labels — which these are per
diagram type is in scripts/lib/mermaid_labels.py — plus, in file trees and
code blocks, the comment after "←" (without marker, 08e6fa4d). Unchanged remain
node IDs, arrows and the rest of the code blocks; the markers [NEU]/[ENTFERNT] are called
[NEW]/[REMOVED] in English (TREE_MARKERS_EN), without a table. A text piece without letters
outside inline code (`mvn test`, "9090", "—") needs no entry; a value
equal to the key is allowed (class names, already English labels).

The tables have no reading-order requirement; --add-missing writes them in
the order of the page, though, with an empty value for each new key.

Markdown conventions (the template can do no more, and needs no more)
--------------------------------------------------------------------
    # Title                     -> header line of the page
    <paragraph after it>        -> subtitle of the header
    ## Section — *epithet*      -> <section> with <h2>; the italic epithet after
                                   the dash becomes the small caption
                                   (may be omitted). The navigation is built from the
                                   section headings.
    ### Subsection              -> <h3>; two or more consecutive ones that
                                   contain only paragraphs/lists become a
                                   two-column card grid.
    ```mermaid                  -> <pre class="mermaid">
    ``` (any other language)    -> <pre class="tree"> (file trees, consoles)
    Tables, lists, `code`, **bold**, *italic*, [text](target)

Checking
--------
--check compares all products of both languages with what would be generated now, and
reports products without a source. It is also red if a text piece has no
translation (key missing or value empty), if a key is orphaned,
if a value names inline code or numbers differently from the original, or if the
translation changes the page structure or the skeleton of a diagram. That way the
German page cannot silently carry on without the English one following. The
English files of a page are written only from a consistent table.

GitHub Pages (--site DIR, TODO ee5a4a04)
----------------------------------------
Writes the pages for GitHub Pages into an empty or new directory, built from
the Markdown source (not from the HTML files in the repo) — docs/ stays untouched:

    DIR/index.html                  index in German (same table as diagramme.md)
    DIR/de/<name>-diagrams.html     German pages
    DIR/en/index.html               index in English
    DIR/en/<name>-diagrams.html     English pages

Each page gets in its navigation a link to the index and to the other language.
If a translation is open, nothing is generated (exit 1): Pages shows either both languages
completely or stays at the last state. Used by .github/workflows/pages.yml.

Usage
-----
    python3 scripts/docs-diagrams.py                # HTML, English pages, indexes
    python3 scripts/docs-diagrams.py --check        # only check, write nothing
    python3 scripts/docs-diagrams.py --add-missing  # enter missing keys empty
    python3 scripts/docs-diagrams.py --site DIR     # pages for GitHub Pages into DIR
    python3 scripts/docs-diagrams.py --root DIR     # different repo root

Exit: 0 = everything congruent, 1 = drift (--check) or open translation,
      2 = source missing/broken, table broken, Mermaid syntax without rule or
          --site directory not empty
"""
import json
import os
import re
import sys

# mermaid_labels lives in scripts/lib; tests load this script via
# spec_from_file_location, in which case no script directory is on sys.path.
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "lib"))
import mermaid_labels  # noqa: E402

DOCS_DIRNAME = "docs"
EN_DIRNAME = "en"
I18N_DIRNAME = "i18n"
SOURCE_SUFFIX = "-diagrams.md"
TARGET_SUFFIX = "-diagrams.html"
TABLE_SUFFIX = "-diagrams.en.json"
INDEX_NAME = "diagramme.md"

GENERATED_NOTE = ("Erzeugt aus {source} von scripts/docs-diagrams.py — "
                  "nicht von Hand bearbeiten.")
GENERATED_NOTE_EN = ("Generated from {source} and {table} by scripts/docs-diagrams.py — "
                     "do not edit by hand.")

# The index shell per language; the lines in it come from the pages.
INDEX_TEXT = {
    "de": {
        "note": ("Erzeugt aus docs/*-diagrams.md von scripts/docs-diagrams.py — "
                 "nicht von Hand bearbeiten."),
        "title": "Diagramme",
        "intro": ("Ablauf- und Architekturdiagramme als Mermaid-Zäune: GitHub rendert sie direkt"
                  " in der Markdown-Fassung. Jede Seite gibt es zusätzlich als HTML"
                  " (`<name>-diagrams.html`) — die wird aus der Markdown-Datei erzeugt und nicht"
                  " von Hand gepflegt. Die englische Fassung unter `docs/en/` entsteht aus derselben"
                  " Quelle und je Seite einer Übersetzungstabelle"
                  " (`docs/i18n/<name>-diagrams.en.json`)."),
        "head": "| Seite | Inhalt | Diagramme |",
        "total": "Zusammen {total} Diagramme in {pages} Seiten.",
        "howto": "Erzeugen und prüfen",
        "commands": (
            "python3 scripts/docs-diagrams.py                # HTML-Fassungen und englische Seiten neu erzeugen",
            "python3 scripts/docs-diagrams.py --check        # meldet Drift und Übersetzungslücken, Exit-Code 1",
            "python3 scripts/docs-diagrams.py --add-missing  # fehlende Übersetzungsschlüssel leer eintragen",
        ),
    },
    "en": {
        "note": ("Generated from docs/*-diagrams.md and docs/i18n/*-diagrams.en.json by"
                 " scripts/docs-diagrams.py — do not edit by hand."),
        "title": "Diagrams",
        "intro": ("Flow and architecture diagrams as Mermaid fences: GitHub renders them directly"
                  " in the Markdown version. Each page also exists as HTML"
                  " (`<name>-diagrams.html`), generated from the Markdown file rather than"
                  " maintained by hand. The English pages are generated from the German source"
                  " pages plus one translation table per page (`docs/i18n/<name>-diagrams.en.json`)."),
        "head": "| Page | Content | Diagrams |",
        "total": "In total, {total} diagrams across {pages} pages.",
        "howto": "Generating and checking",
        "commands": (
            "python3 scripts/docs-diagrams.py                # regenerate the HTML versions and the English pages",
            "python3 scripts/docs-diagrams.py --check        # reports drift and translation gaps, exit code 1",
            "python3 scripts/docs-diagrams.py --add-missing  # add missing translation keys with empty values",
        ),
    },
}

# Texts of the Pages version (--site) per language; table header and sum come from INDEX_TEXT.
SITE_TEXT = {
    "de": {
        "intro": ("Ablauf- und Architekturdiagramme, bei jedem Deploy frisch erzeugt aus den"
                  " Markdown-Quellen im Repository (`docs/*-diagrams.md`)."),
        "section": "Seiten",
        "overview": "Übersicht",
        "other": "English",
    },
    "en": {
        "intro": ("Flow and architecture diagrams, generated afresh on every deploy from the"
                  " Markdown sources in the repository (`docs/*-diagrams.md`)."),
        "section": "Pages",
        "overview": "Overview",
        "other": "Deutsch",
    },
}
SITE_NOTE = "Erzeugt von scripts/docs-diagrams.py --site aus docs/*-diagrams.md — nicht von Hand bearbeiten."

# ---------------------------------------------------------------------------
# HTML shell
#
# Collects the styles of all nine former single pages (which differed only in
# which classes the respective page happened to need). The
# Mermaid embedding and the themeVariables are taken over unchanged.
# ---------------------------------------------------------------------------
TEMPLATE = """<!DOCTYPE html>
<!-- @@NOTE@@ -->
<html lang="@@LANG@@">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>@@TITLE@@</title>
  <script src="https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
  <style>
    :root {
      --bg: #0d1117;
      --surface: #161b22;
      --border: #30363d;
      --text: #e6edf3;
      --text-muted: #8b949e;
      --accent: #58a6ff;
      --green: #3fb950;
      --purple: #bc8cff;
      --orange: #d29922;
      --red: #f85149;
    }
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif;
      background: var(--bg);
      color: var(--text);
      line-height: 1.6;
    }
    header {
      background: var(--surface);
      border-bottom: 1px solid var(--border);
      padding: 1.5rem 2rem;
      text-align: center;
    }
    header h1 { font-size: 1.8rem; font-weight: 600; }
    header p { color: var(--text-muted); margin-top: 0.3rem; }
    nav {
      background: var(--surface);
      border-bottom: 1px solid var(--border);
      padding: 0.8rem 2rem;
      display: flex;
      gap: 0.5rem;
      flex-wrap: wrap;
      justify-content: center;
      position: sticky;
      top: 0;
      z-index: 100;
    }
    nav a {
      color: var(--accent);
      text-decoration: none;
      padding: 0.4rem 1rem;
      border-radius: 6px;
      font-size: 0.9rem;
      transition: background 0.2s;
    }
    nav a:hover { background: rgba(88,166,255,0.1); }
    main { max-width: 1200px; margin: 0 auto; padding: 2rem; }
    section {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 2rem;
      margin-bottom: 2rem;
    }
    section h2 {
      font-size: 1.4rem;
      margin-bottom: 0.5rem;
      color: var(--accent);
    }
    section h2 span { font-size: 0.85rem; color: var(--text-muted); font-weight: 400; margin-left: 0.5rem; }
    section h3 { font-size: 1.05rem; margin-top: 1.5rem; margin-bottom: 0.8rem; }
    section > p { color: var(--text-muted); margin-bottom: 1.5rem; font-size: 0.95rem; }
    section > ul { color: var(--text); line-height: 1.6; padding-left: 1.2rem; margin-bottom: 1.5rem; }
    section > p a, .card a { color: var(--accent); }
    .mermaid {
      display: flex;
      justify-content: center;
      margin: 1rem 0;
    }
    .mermaid svg { max-width: 100%; height: auto; }
    table {
      width: 100%;
      border-collapse: collapse;
      margin: 1rem 0;
      font-size: 0.9rem;
    }
    th, td {
      padding: 0.6rem 1rem;
      text-align: left;
      border-bottom: 1px solid var(--border);
    }
    th { color: var(--text-muted); font-weight: 600; font-size: 0.8rem; text-transform: uppercase; }
    td code {
      background: rgba(88,166,255,0.1);
      color: var(--accent);
      padding: 0.15rem 0.4rem;
      border-radius: 4px;
      font-size: 0.85rem;
    }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 1.5rem; margin: 1rem 0; }
    @media (max-width: 800px) { .grid { grid-template-columns: 1fr; } }
    .card {
      background: var(--bg);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 1.2rem;
    }
    .card h3 { font-size: 1rem; margin-top: 0; margin-bottom: 0.5rem; }
    .card p, .card li { font-size: 0.85rem; color: var(--text-muted); }
    .card ul { padding-left: 1.1rem; }
    .card code {
      background: rgba(88,166,255,0.1);
      color: var(--accent);
      padding: 0.15rem 0.4rem;
      border-radius: 4px;
      font-size: 0.85rem;
    }
    .tree {
      font-family: 'SF Mono', 'Fira Code', monospace;
      font-size: 0.85rem;
      line-height: 1.8;
      background: var(--bg);
      padding: 1.5rem;
      border-radius: 8px;
      border: 1px solid var(--border);
      overflow-x: auto;
      margin: 1rem 0;
    }
    .tree .dir { color: var(--accent); font-weight: 600; }
    .tree .comment { color: var(--text-muted); }
    .badge {
      display: inline-block;
      padding: 0.15rem 0.5rem;
      border-radius: 10px;
      font-size: 0.75rem;
      font-weight: 600;
    }
    .badge-green { background: rgba(63,185,80,0.15); color: var(--green); }
    .badge-red { background: rgba(248,81,73,0.15); color: var(--red); }
    .badge-blue { background: rgba(88,166,255,0.15); color: var(--accent); }
    .badge-purple { background: rgba(188,140,255,0.15); color: var(--purple); }
  </style>
</head>
<body>

<header>
  <h1>@@HEADING@@</h1>
  <p>@@SUBTITLE@@</p>
</header>

<nav>
@@NAV@@
</nav>

<main>

@@BODY@@
</main>

<script>
  mermaid.initialize({
    startOnLoad: true,
    theme: 'dark',
    themeVariables: {
      primaryColor: '#1a2e3e',
      primaryTextColor: '#e6edf3',
      primaryBorderColor: '#58a6ff',
      lineColor: '#8b949e',
      secondaryColor: '#2e1a2e',
      tertiaryColor: '#1a2e1a',
      background: '#0d1117',
      mainBkg: '#161b22',
      nodeBorder: '#30363d',
      clusterBkg: '#161b22',
      clusterBorder: '#30363d',
      titleColor: '#e6edf3',
      edgeLabelBackground: '#161b22'
    },
    flowchart: { curve: 'basis', padding: 20 },
    sequence: { actorMargin: 50, messageMargin: 40 },
    er: { layoutDirection: 'LR' }
  });
</script>

</body>
</html>
"""


class DocError(Exception):
    """The Markdown source does not follow the conventions."""


# ---------------------------------------------------------------------------
# Reading
# ---------------------------------------------------------------------------
FENCE = re.compile(r"^```(\w*)\s*$")
HEADING2 = re.compile(r"^##\s+(.*)$")
HEADING3 = re.compile(r"^###\s+(.*)$")
# "Abschnitt — *Beiwort*": only an italic suffix at the end of the line counts, so that a
# dash in the middle of the title cuts nothing off.
BADGE = re.compile(r"^(.*?)\s+[—–-]\s+\*([^*]+)\*$")
BULLET = re.compile(r"^-\s+(.*)$")
TABLE_DIVIDER = re.compile(r"^\|[\s:|-]+\|$")


def slugify(text, taken):
    """Anchor from the heading — the same rule as on GitHub, without a second source."""
    slug = re.sub(r"[^\w\s-]", "", text.lower(), flags=re.UNICODE)
    slug = re.sub(r"[\s_]+", "-", slug).strip("-") or "abschnitt"
    candidate, n = slug, 1
    while candidate in taken:
        n += 1
        candidate = f"{slug}-{n}"
    taken.add(candidate)
    return candidate


def split_row(line):
    """Split a table row into cells; '\\|' is a bar, not a separator."""
    inner = line.strip()
    if inner.startswith("|"):
        inner = inner[1:]
    if inner.endswith("|") and not inner.endswith("\\|"):
        inner = inner[:-1]
    cells = re.split(r"(?<!\\)\|", inner)
    return [c.strip().replace("\\|", "|") for c in cells]


def parse_blocks(lines, start, stop_at):
    """Read blocks up to the next heading from stop_at.

    A block is (kind, content): paragraph -> text, list -> [items],
    table -> ([header row, rows...], [separator cells]), mermaid -> source text,
    code -> (language, text). Separator cells and language are needed only by the Markdown output.
    """
    blocks, i = [], start
    while i < len(lines):
        line = lines[i]
        if any(pattern.match(line) for pattern in stop_at):
            break
        if not line.strip():
            i += 1
            continue
        fence = FENCE.match(line)
        if fence:
            lang, i = fence.group(1), i + 1
            body = []
            while i < len(lines) and not lines[i].startswith("```"):
                body.append(lines[i])
                i += 1
            if i >= len(lines):
                raise DocError(f"Zeile {start + 1}: Codezaun ohne Abschluss")
            i += 1
            blocks.append(("mermaid", "\n".join(body)) if lang == "mermaid"
                          else ("code", (lang, "\n".join(body))))
            continue
        if line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(lines[i])
                i += 1
            if len(rows) < 2 or not TABLE_DIVIDER.match(rows[1].strip()):
                raise DocError(f"Zeile {i}: Tabelle ohne Trennzeile")
            blocks.append(("table", ([split_row(rows[0])] + [split_row(r) for r in rows[2:]],
                                     split_row(rows[1]))))
            continue
        if BULLET.match(line):
            items = []
            while i < len(lines) and BULLET.match(lines[i]):
                items.append(BULLET.match(lines[i]).group(1))
                i += 1
            blocks.append(("list", items))
            continue
        para = []
        while i < len(lines) and lines[i].strip() and not lines[i].startswith(("|", "```", "#", "- ")):
            para.append(lines[i].strip())
            i += 1
        blocks.append(("paragraph", " ".join(para)))
    return blocks, i


def parse(text):
    """Markdown source -> {title, subtitle, sections}."""
    lines = text.replace("\r\n", "\n").split("\n")
    i = 0
    while i < len(lines) and not lines[i].strip():
        i += 1
    if i >= len(lines) or not lines[i].startswith("# "):
        raise DocError("Erste Zeile muss '# Titel' sein")
    title = lines[i][2:].strip()
    i += 1
    subtitle_lines = []
    while i < len(lines) and not lines[i].strip():
        i += 1
    while i < len(lines) and lines[i].strip() and not lines[i].startswith("#"):
        subtitle_lines.append(lines[i].strip())
        i += 1
    subtitle = " ".join(subtitle_lines)
    if not subtitle:
        raise DocError("Nach dem Titel fehlt der Untertitel-Absatz")

    sections, taken = [], set()
    while i < len(lines):
        if not lines[i].strip():
            i += 1
            continue
        head = HEADING2.match(lines[i])
        if not head:
            raise DocError(f"Zeile {i + 1}: '## Abschnitt' erwartet, gefunden: {lines[i][:60]!r}")
        raw = head.group(1).strip()
        badge_match = BADGE.match(raw)
        heading = badge_match.group(1).strip() if badge_match else raw
        badge = badge_match.group(2).strip() if badge_match else ""
        blocks, i = parse_blocks(lines, i + 1, [HEADING2, HEADING3])
        subs = []
        while i < len(lines) and HEADING3.match(lines[i]):
            sub_head = HEADING3.match(lines[i]).group(1).strip()
            sub_blocks, i = parse_blocks(lines, i + 1, [HEADING2, HEADING3])
            subs.append((sub_head, sub_blocks))
        sections.append({"heading": heading, "badge": badge,
                         "id": slugify(heading, taken),
                         "blocks": blocks, "subs": subs})
    if not sections:
        raise DocError("Keine '## '-Abschnitte gefunden")
    return {"title": title, "subtitle": subtitle, "sections": sections}


# ---------------------------------------------------------------------------
# Writing
# ---------------------------------------------------------------------------
def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def inline(text):
    """Inline Markdown -> HTML. Code first, so that nothing else is evaluated inside it."""
    stash = []

    def keep(html):
        stash.append(html)
        return f"\x00{len(stash) - 1}\x00"

    text = re.sub(r"`([^`]+)`", lambda m: keep("<code>" + esc(m.group(1)) + "</code>"), text)
    text = esc(text)
    text = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)",
                  lambda m: keep(f'<a href="{m.group(2)}">{m.group(1)}</a>'), text)
    text = re.sub(r"\*\*([^*\n]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<!\*)\*([^*\n]+)\*(?!\*)", r"<em>\1</em>", text)
    while "\x00" in text:
        text = re.sub(r"\x00(\d+)\x00", lambda m: stash[int(m.group(1))], text)
    return text


# The only tags Mermaid itself evaluates in labels. They stay
# real markup, everything else is masked (see mermaid_body).
MERMAID_MARKUP = re.compile(r"&lt;(/?)(br\s*/?|i|b)&gt;")


def mermaid_body(code):
    """Make Mermaid source safe for <pre>.

    Masking is necessary: in the former HTML files `List<WorkflowRun>`
    stood unmasked in the <pre> — the browser took it for a tag and swallowed it,
    the label was gone. Where it mattered, it was already masked by hand
    (`&lt;&lt;interface&gt;&gt;`), just not everywhere.

    Excluded are the tags Mermaid itself evaluates in labels:
    `<br/>` for the line break, `<i>`/`<b>` for the markup. For the
    arrows (`-->`) both are equivalent: the browser returns `--&gt;` via innerHTML
    anyway, no matter how it stands in the file.
    """
    return MERMAID_MARKUP.sub(lambda m: f"<{m.group(1)}{m.group(2)}>", esc(code))


TREE_BADGES = (("[NEU]", "green"), ("[ENTFERNT]", "red"), ("[NEW]", "green"), ("[REMOVED]", "red"))
# Markers per language (08e6fa4d): the German source writes [NEU]/[ENTFERNT], the English page
# gets [NEW]/[REMOVED] — fixed words, no entries in the translation table.
TREE_MARKERS_EN = {"[NEU]": "[NEW]", "[ENTFERNT]": "[REMOVED]"}
TREE_MARKER = re.compile(r"\s*\[(?:NEU|ENTFERNT|NEW|REMOVED)\]")
TREE_COMMENT = re.compile(r"^(\s*)(.*?)(\s*)$")


def tree_text(code, fn, markers=None):
    """Code block with translated comments after "←" and markers after markers (08e6fa4d).

    fn(comment) gets the comment without markers and without whitespace at the edge; the markers
    stand after the comment again. Path and tree lines stay as they are.
    """
    markers = markers or {}
    out = []
    for line in code.split("\n"):
        if "←" in line:
            head, _, tail = line.partition("←")
            found = "".join(TREE_MARKER.findall(tail))
            lead, comment, trail = TREE_COMMENT.match(TREE_MARKER.sub("", tail)).groups()
            line = f"{head}←{lead}{fn(comment) if comment else comment}{trail}{found}"
        for german, other in markers.items():
            line = line.replace(german, other)
        out.append(line)
    return "\n".join(out)
TREE_DIR = re.compile(r"(?<![\w./~-])([\w.~@-]+(?:/[\w.~@-]+)*/)(?=\s|$)")


def tree_body(code):
    """Colour a file tree: directories, comments after the arrow, markers."""
    out = []
    for line in esc(code).split("\n"):
        badges = ""
        for marker, colour in TREE_BADGES:
            if marker in line:
                line = line.replace(marker, "").rstrip()
                badges += f' <span class="badge badge-{colour}">{marker[1:-1]}</span>'
        line = TREE_DIR.sub(r'<span class="dir">\1</span>', line)
        if "←" in line:
            head, _, tail = line.partition("←")
            line = f'{head}<span class="comment">←{tail}</span>'
        out.append(line + badges)
    return "\n".join(out)


def render_blocks(blocks, indent):
    html = []
    pad = " " * indent
    for kind, payload in blocks:
        if kind == "paragraph":
            html.append(f"{pad}<p>{inline(payload)}</p>")
        elif kind == "list":
            html.append(f"{pad}<ul>")
            html.extend(f"{pad}  <li>{inline(item)}</li>" for item in payload)
            html.append(f"{pad}</ul>")
        elif kind == "table":
            head, rows = payload[0][0], payload[0][1:]
            html.append(f"{pad}<table>")
            html.append(f"{pad}  <thead><tr>" +
                        "".join(f"<th>{inline(c)}</th>" for c in head) + "</tr></thead>")
            html.append(f"{pad}  <tbody>")
            for row in rows:
                html.append(f"{pad}    <tr>" +
                            "".join(f"<td>{inline(c)}</td>" for c in row) + "</tr>")
            html.append(f"{pad}  </tbody>")
            html.append(f"{pad}</table>")
        elif kind == "mermaid":
            html.append(f'{pad}<pre class="mermaid">')
            html.append(mermaid_body(payload))
            html.append(f"{pad}</pre>")
        elif kind == "code":
            html.append(f'{pad}<pre class="tree">')
            html.append(tree_body(payload[1]))
            html.append(f"{pad}</pre>")
        else:                                                  # pragma: no cover
            raise DocError(f"Unbekannter Block: {kind}")
    return html


def is_card(sub):
    """Only paragraphs and lists fit into a card — tables and diagrams do not."""
    return bool(sub[1]) and all(kind in ("paragraph", "list") for kind, _ in sub[1])


def render_subs(subs):
    """Bundle consecutive card subsections, the rest as <h3>."""
    html, i = [], 0
    while i < len(subs):
        run = i
        while run < len(subs) and is_card(subs[run]):
            run += 1
        if run - i >= 2:
            html.append('  <div class="grid">')
            for heading, blocks in subs[i:run]:
                html.append('    <div class="card">')
                html.append(f"      <h3>{inline(heading)}</h3>")
                html.extend(render_blocks(blocks, 6))
                html.append("    </div>")
            html.append("  </div>")
            i = run
            continue
        heading, blocks = subs[i]
        html.append(f"  <h3>{inline(heading)}</h3>")
        html.extend(render_blocks(blocks, 2))
        i += 1
    return html


def render_html(doc, note, lang):
    nav = "\n".join(f'  <a href="#{s["id"]}">{inline(s["heading"])}</a>'
                    for s in doc["sections"])
    body = []
    for section in doc["sections"]:
        badge = f' <span>{inline(section["badge"])}</span>' if section["badge"] else ""
        body.append(f'<section id="{section["id"]}">')
        body.append(f'  <h2>{inline(section["heading"])}{badge}</h2>')
        body.extend(render_blocks(section["blocks"], 2))
        body.extend(render_subs(section["subs"]))
        body.append("</section>")
        body.append("")
    return (TEMPLATE
            .replace("@@NOTE@@", note)
            .replace("@@LANG@@", lang)
            .replace("@@TITLE@@", esc(doc["title"]))
            .replace("@@HEADING@@", inline(doc["title"]))
            .replace("@@SUBTITLE@@", inline(doc["subtitle"]))
            .replace("@@NAV@@", nav)
            .replace("@@BODY@@", "\n".join(body)))


def shared_prefix(titles):
    """Common name prefix of the pages ("Product — "), if there is one.

    Otherwise it stands in every line of the index. Measured instead of
    hard-wired: if the product is called differently one day, the line follows.
    """
    if len(titles) < 2:
        return ""
    prefix = os.path.commonprefix(titles)
    match = re.match(r"^(.*[—–-]\s)", prefix)
    return match.group(1) if match else ""


def render_index(entries, lang):
    """Table of contents from the sources — so that the index, too, is not a second source."""
    text = INDEX_TEXT[lang]
    prefix = shared_prefix([doc["title"] for _, doc, _ in entries])
    lines = [f"<!-- {text['note']} -->",
             "",
             f"# {text['title']}",
             "",
             text["intro"],
             "",
             text["head"],
             "|---|---|---:|"]
    for stem, doc, count in entries:
        title = doc["title"][len(prefix):] if prefix else doc["title"]
        lines.append(f"| [{title}]({stem}{SOURCE_SUFFIX}) | {doc['subtitle']} | {count} |")
    total = sum(count for _, _, count in entries)
    lines += ["",
              text["total"].format(total=total, pages=len(entries)),
              "",
              f"## {text['howto']}",
              "",
              "```bash",
              *text["commands"],
              "```",
              ""]
    return "\n".join(lines)


def render_markdown(doc, note=None):
    """Structure -> Markdown in the form of the German sources.

    For a source in this form the result is congruent with it; the English
    page thereby has the same structure as the German one, line for line.
    """
    lines = [f"<!-- {note} -->", ""] if note else []
    lines += [f"# {doc['title']}", "", doc["subtitle"], ""]
    for section in doc["sections"]:
        badge = f" — *{section['badge']}*" if section["badge"] else ""
        lines += [f"## {section['heading']}{badge}", ""]
        lines += markdown_blocks(section["blocks"])
        for heading, blocks in section["subs"]:
            lines += [f"### {heading}", ""]
            lines += markdown_blocks(blocks)
    return "\n".join(lines).rstrip("\n") + "\n"


def markdown_blocks(blocks):
    def row(cells):
        return "| " + " | ".join(cell.replace("|", "\\|") for cell in cells) + " |"

    lines = []
    for kind, payload in blocks:
        if kind == "paragraph":
            lines.append(payload)
        elif kind == "list":
            lines.extend(f"- {item}" for item in payload)
        elif kind == "table":
            rows, divider = payload
            lines += [row(rows[0]), "|" + "|".join(divider) + "|"]
            lines.extend(row(cells) for cells in rows[1:])
        elif kind == "mermaid":
            lines += ["```mermaid", payload, "```"]
        else:
            lang, body = payload
            lines += [f"```{lang}", body, "```"]
        lines.append("")
    return lines


# ---------------------------------------------------------------------------
# English version
# ---------------------------------------------------------------------------
LETTER = re.compile(r"[^\W\d_]")
CODE_SPAN = re.compile(r"`[^`]*`")
DIGITS = re.compile(r"\d+")


def needs_translation(text, markdown=True):
    """Does the text piece have anything to translate? Count letters, not inline code.

    Mermaid labels know no inline code (backticks mean something different
    there), there every letter counts.
    """
    return bool(LETTER.search(CODE_SPAN.sub("", text) if markdown else text))


def map_text(doc, fn, markers=None):
    """New page in which every text piece is replaced by fn(text, markdown).

    The order is the reading order. segments() and translate() both go this
    way — so the table knows exactly the places the page has, no more and no
    fewer. Diagrams get only new labels, code blocks only new comments
    after "←" and the markers after markers (tree_text).
    """
    def blocks(items):
        out = []
        for kind, payload in items:
            if kind == "paragraph":
                payload = fn(payload, True)
            elif kind == "list":
                payload = [fn(item, True) for item in payload]
            elif kind == "table":
                payload = ([[fn(cell, True) for cell in cells] for cells in payload[0]], payload[1])
            elif kind == "mermaid":
                payload = mermaid_labels.translate(payload, lambda label: fn(label, False))
            elif kind == "code":
                payload = (payload[0], tree_text(payload[1], lambda c: fn(c, False), markers))
            out.append((kind, payload))
        return out

    title = fn(doc["title"], True)
    subtitle = fn(doc["subtitle"], True)
    sections, taken = [], set()
    for section in doc["sections"]:
        heading = fn(section["heading"], True)
        badge = fn(section["badge"], True) if section["badge"] else ""
        body = blocks(section["blocks"])
        subs = []
        for sub_heading, sub_blocks in section["subs"]:
            sub_title = fn(sub_heading, True)
            subs.append((sub_title, blocks(sub_blocks)))
        sections.append({"heading": heading, "badge": badge, "id": slugify(heading, taken),
                         "blocks": body, "subs": subs})
    return {"title": title, "subtitle": subtitle, "sections": sections}


def segments(doc):
    """The text pieces of a page that need an entry in its table (without duplicates)."""
    found = {}

    def record(text, markdown):
        if needs_translation(text, markdown):
            found.setdefault(text, None)
        return text

    map_text(doc, record)
    return list(found)


def value_problem(key, value):
    """Why a value does not match its key — or None."""
    if "\n" in value or "\r" in value:
        return "Zeilenumbruch im Wert"
    if value != value.strip():
        return "Leerraum am Anfang oder Ende"
    if sorted(CODE_SPAN.findall(key)) != sorted(CODE_SPAN.findall(value)):
        return "Inline-Code weicht vom Original ab"
    if sorted(DIGITS.findall(key)) != sorted(DIGITS.findall(value)):
        return "Zahlen weichen vom Original ab"
    return None


def translate(doc, table):
    """English page from German page and table.

    Returns (page, missing, invalid, orphaned). If a value is missing, the German text stands in its place
    — such a page is not written, see plan().
    """
    missing, invalid, used = [], [], set()

    def lookup(text, markdown):
        if not needs_translation(text, markdown):
            return text
        used.add(text)
        value = table.get(text, "")
        if not value.strip():
            if text not in missing:
                missing.append(text)
            return text
        reason = value_problem(text, value)
        if reason and (text, reason) not in invalid:
            invalid.append((text, reason))
        return value

    english = map_text(doc, lookup, TREE_MARKERS_EN)
    orphans = [key for key in table if key not in used]
    return english, missing, invalid, orphans


def shape(doc):
    """Structure of a page without its texts — identical for German and English.

    Code blocks go in verbatim except for the comments after "←" and the language of the
    markers, diagrams as a skeleton without labels, lists and tables with their size,
    paragraphs only as a kind.
    """
    neutral = {other: german for german, other in TREE_MARKERS_EN.items()}

    def blocks(items):
        out = []
        for kind, payload in items:
            if kind == "list":
                out.append((kind, len(payload)))
            elif kind == "table":
                out.append((kind, tuple(payload[1]), tuple(len(cells) for cells in payload[0])))
            elif kind == "mermaid":
                out.append((kind, mermaid_labels.skeleton(payload)))
            elif kind == "code":
                out.append((kind, payload[0], tree_text(payload[1], lambda c: "…", neutral)))
            else:
                out.append((kind,))
        return out

    return [(bool(section["badge"]), blocks(section["blocks"]),
             [blocks(sub_blocks) for _, sub_blocks in section["subs"]])
            for section in doc["sections"]]


def mermaid_blocks(doc):
    for section in doc["sections"]:
        for blocks in [section["blocks"]] + [sub_blocks for _, sub_blocks in section["subs"]]:
            for kind, payload in blocks:
                if kind == "mermaid":
                    yield section["heading"], payload


def structure_problem(german, english):
    """Does the translation change the structure? Description of the first place or None.

    Two stages: each diagram on its own (then the line can be named), after that the
    whole page as it would be written as Markdown and read again — a value
    that starts with "## " would otherwise silently open a new section there.
    """
    for (heading, de_code), (_, en_code) in zip(mermaid_blocks(german), mermaid_blocks(english)):
        try:
            en_lines = mermaid_labels.skeleton(en_code).split("\n")
        except mermaid_labels.MermaidError as exc:
            return f"Diagramm in '{heading}': {exc}"
        de_lines = mermaid_labels.skeleton(de_code).split("\n")
        for number, (want, got) in enumerate(zip(de_lines, en_lines)):
            if want != got:
                return f"Diagramm in '{heading}': {en_code.split(chr(10))[number].strip()!r}"
    try:
        got = shape(parse(render_markdown(english)))
    except (DocError, mermaid_labels.MermaidError) as exc:
        return f"englische Seite nicht mehr lesbar ({exc})"
    want = shape(german)
    if got == want:
        return None
    for number, (section_want, section_got) in enumerate(zip(want, got), 1):
        if section_want != section_got:
            return f"Abschnitt {number} ('{german['sections'][number - 1]['heading']}')"
    return f"{len(got)} statt {len(want)} Abschnitte"


def quote(text):
    """Output the key as it stands in the JSON table."""
    return json.dumps(text, ensure_ascii=False)


def load_table(path, rel):
    """Read the table; None if there is none. Broken JSON is a hard error."""
    if not os.path.exists(path):
        return None

    def pairs(items):
        table = {}
        for key, value in items:
            if key in table:
                raise DocError(f"{rel}: Schluessel doppelt: {quote(key)}")
            table[key] = value
        return table

    try:
        with open(path, encoding="utf-8") as fh:
            table = json.load(fh, object_pairs_hook=pairs)
    except json.JSONDecodeError as exc:
        raise DocError(f"{rel}: kein gueltiges JSON ({exc})") from exc
    if not isinstance(table, dict) or not all(isinstance(v, str) for v in table.values()):
        raise DocError(f"{rel}: erwartet wird ein Objekt aus Text-Paaren")
    return table


def dump_table(table):
    return json.dumps(table, ensure_ascii=False, indent=2) + "\n"


# ---------------------------------------------------------------------------
# Flow
# ---------------------------------------------------------------------------
def collect(docs_dir):
    stems = sorted(name[:-len(SOURCE_SUFFIX)] for name in os.listdir(docs_dir)
                   if name.endswith(SOURCE_SUFFIX))
    entries = []
    for stem in stems:
        path = os.path.join(docs_dir, stem + SOURCE_SUFFIX)
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        try:
            doc = parse(text)
            # Reads each diagram once with the label rules: unknown
            # Mermaid syntax is noticed here, with the page name, not only when translating.
            segments(doc)
        except (DocError, mermaid_labels.MermaidError) as exc:
            raise DocError(f"{stem}{SOURCE_SUFFIX}: {exc}") from exc
        count = sum(1 for section in doc["sections"]
                    for kind, _ in section["blocks"] if kind == "mermaid")
        count += sum(1 for section in doc["sections"] for _, blocks in section["subs"]
                     for kind, _ in blocks if kind == "mermaid")
        entries.append((stem, doc, count))
    return entries


def stale_outputs(docs_dir, en_dir, stems):
    """Products whose German source no longer exists."""
    stale = []
    for folder, suffixes in ((docs_dir, (TARGET_SUFFIX,)), (en_dir, (SOURCE_SUFFIX, TARGET_SUFFIX))):
        if not os.path.isdir(folder):
            continue
        for name in sorted(os.listdir(folder)):
            for suffix in suffixes:
                if name.endswith(suffix) and name[:-len(suffix)] not in stems:
                    stale.append(os.path.join(folder, name))
    return stale


def plan(root):
    """(path -> expected content, orphaned products, translation findings).

    The same computation for both modes. The English files of a page are in it only
    if their table is consistent; the English index only if that holds for
    all pages.
    """
    docs_dir = os.path.join(root, DOCS_DIRNAME)
    en_dir = os.path.join(docs_dir, EN_DIRNAME)
    i18n_dir = os.path.join(docs_dir, I18N_DIRNAME)
    entries = collect(docs_dir)
    if not entries:
        raise DocError(f"Keine {SOURCE_SUFFIX}-Quellen unter {docs_dir}")

    files, problems, english_entries = {}, [], []
    for stem, doc, count in entries:
        source = f"{DOCS_DIRNAME}/{stem}{SOURCE_SUFFIX}"
        files[os.path.join(docs_dir, stem + TARGET_SUFFIX)] = render_html(
            doc, GENERATED_NOTE.format(source=source), "de")

        table_rel = f"{DOCS_DIRNAME}/{I18N_DIRNAME}/{stem}{TABLE_SUFFIX}"
        table = load_table(os.path.join(i18n_dir, stem + TABLE_SUFFIX), table_rel)
        if table is None:
            problems.append(f"FEHLT {table_rel} — die Tabelle gibt es nicht;"
                            " 'python3 scripts/docs-diagrams.py --add-missing' legt sie an")
            continue
        english, missing, invalid, orphans = translate(doc, table)
        problems += [f"FEHLT {table_rel}: {quote(key)}" for key in missing]
        problems += [f"UNGUELTIG {table_rel}: {quote(key)} — {reason}" for key, reason in invalid]
        problems += [f"VERWAIST {table_rel}: {quote(key)}" for key in orphans]
        if missing or invalid or orphans:
            continue
        problem = structure_problem(doc, english)
        if problem:
            problems.append(f"STRUKTUR {DOCS_DIRNAME}/{EN_DIRNAME}/{stem}{SOURCE_SUFFIX}:"
                            f" die Uebersetzung veraendert den Aufbau — {problem}")
            continue
        note = GENERATED_NOTE_EN.format(source=source, table=table_rel)
        files[os.path.join(en_dir, stem + SOURCE_SUFFIX)] = render_markdown(english, note)
        files[os.path.join(en_dir, stem + TARGET_SUFFIX)] = render_html(english, note, "en")
        english_entries.append((stem, english, count))

    files[os.path.join(docs_dir, INDEX_NAME)] = render_index(entries, "de")
    if len(english_entries) == len(entries):
        files[os.path.join(en_dir, INDEX_NAME)] = render_index(english_entries, "en")

    stems = {stem for stem, _, _ in entries}
    if os.path.isdir(i18n_dir):
        for name in sorted(os.listdir(i18n_dir)):
            if name.endswith(TABLE_SUFFIX) and name[:-len(TABLE_SUFFIX)] not in stems:
                problems.append(f"VERWAIST {DOCS_DIRNAME}/{I18N_DIRNAME}/{name} — keine deutsche Seite"
                                f" {DOCS_DIRNAME}/{name[:-len(TABLE_SUFFIX)]}{SOURCE_SUFFIX}")
    return files, stale_outputs(docs_dir, en_dir, stems), problems, entries, english_entries


def run(root, check):
    docs_dir = os.path.join(root, DOCS_DIRNAME)
    if not os.path.isdir(docs_dir):
        print(f"docs-diagrams: {docs_dir} fehlt", file=sys.stderr)
        return 2
    try:
        files, stale, problems, _, _ = plan(root)
    except DocError as exc:
        print(f"docs-diagrams: {exc}", file=sys.stderr)
        return 2

    drift = []
    for path, content in sorted(files.items()):
        rel = os.path.relpath(path, root)
        current = None
        if os.path.exists(path):
            with open(path, encoding="utf-8") as fh:
                current = fh.read()
        if current == content:
            continue
        if check:
            reason = "fehlt" if current is None else "weicht von Quelle bzw. Uebersetzung ab"
            drift.append(f"DRIFT {rel} {reason}")
            continue
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
        print(f"geschrieben: {rel}")
    for path in stale:
        rel = os.path.relpath(path, root)
        if check:
            drift.append(f"DRIFT {rel} hat keine Quelle mehr (verwaistes Erzeugnis)")
            continue
        os.remove(path)
        print(f"entfernt: {rel} (keine Quelle mehr)")

    for line in problems + drift:
        print(line)
    sys.stdout.flush()      # summary (stderr) after the list, also in 2>&1 recordings
    if problems:
        print(f"docs-diagrams: {len(problems)} Uebersetzungsbefund(e) — die betroffenen englischen"
              " Seiten werden nicht geschrieben. Tabellen unter docs/i18n/ nachziehen;"
              " 'python3 scripts/docs-diagrams.py --add-missing' traegt fehlende Schluessel"
              " mit leerem Wert ein.", file=sys.stderr)
    if drift:
        print(f"docs-diagrams: {len(drift)} Datei(en) nicht deckungsgleich —"
              " 'python3 scripts/docs-diagrams.py' laufen lassen und die"
              " Aenderungen mitcommitten.", file=sys.stderr)
    if problems or drift:
        return 1
    if check:
        print(f"Diagramm-Seiten OK — {len(files)} erzeugte Dateien decken sich mit"
              f" docs/*{SOURCE_SUFFIX} und docs/{I18N_DIRNAME}/*{TABLE_SUFFIX}.")
    else:
        print(f"Diagramm-Seiten erzeugt — {len(files)} Dateien geprueft/geschrieben.")
    return 0


def add_missing(root):
    """Bring every table up to the state of its page without inventing a value.

    New keys get an empty value (stays red until someone translates),
    existing values stay, orphaned keys move to the end instead of disappearing —
    often their text is the translation of the new key, only slightly changed.
    """
    docs_dir = os.path.join(root, DOCS_DIRNAME)
    i18n_dir = os.path.join(docs_dir, I18N_DIRNAME)
    open_items = 0
    for stem, doc, _ in collect(docs_dir):
        rel = f"{DOCS_DIRNAME}/{I18N_DIRNAME}/{stem}{TABLE_SUFFIX}"
        path = os.path.join(i18n_dir, stem + TABLE_SUFFIX)
        table = load_table(path, rel)
        existing = table or {}
        wanted = segments(doc)
        updated = {key: existing.get(key, "") for key in wanted}
        orphans = [key for key in existing if key not in updated]
        updated.update((key, existing[key]) for key in orphans)
        if table is None or list(updated.items()) != list(existing.items()):
            added = sum(1 for key in wanted if key not in existing)
            os.makedirs(i18n_dir, exist_ok=True)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(dump_table(updated))
            print(f"geschrieben: {rel} — {added} neu (leer), {len(orphans)} verwaist")
        open_items += sum(1 for key in wanted if not updated[key].strip()) + len(orphans)
    sys.stdout.flush()
    if open_items:
        print(f"docs-diagrams: {open_items} Eintrag/Eintraege offen — leere Werte uebersetzen,"
              " verwaiste Schluessel loeschen oder ihren Text fuer den neuen Schluessel uebernehmen.",
              file=sys.stderr)
        return 1
    print("Uebersetzungstabellen vollstaendig.")
    return 0


def site_page(html, links):
    """Pages version of a page: index and other language at the front of the navigation.

    Only for --site; the HTML files in the repo stay as they are.
    """
    extra = "".join(f'  <a href="{href}">{esc(label)}</a>\n' for href, label in links)
    if html.count("<nav>\n") != 1:                          # pragma: no cover
        raise DocError("Vorlage ohne eindeutiges <nav>")
    return html.replace("<nav>\n", "<nav>\n" + extra, 1)


def render_site_index(entries, lang, page_dir, other_index):
    """Index as an HTML page, from the same entries as diagramme.md."""
    text, site_text = INDEX_TEXT[lang], SITE_TEXT[lang]
    prefix = shared_prefix([doc["title"] for _, doc, _ in entries])
    rows = [split_row(text["head"])]
    for stem, doc, count in entries:
        title = doc["title"][len(prefix):] if prefix else doc["title"]
        rows.append([f"[{title}]({page_dir}{stem}{TARGET_SUFFIX})", doc["subtitle"], str(count)])
    total = sum(count for _, _, count in entries)
    doc = {"title": f"{prefix}{text['title']}",
           "subtitle": f"{site_text['intro']} · [{site_text['other']}]({other_index})",
           "sections": [{"heading": site_text["section"], "badge": "", "id": "pages",
                         "blocks": [("table", (rows, None)),
                                    ("paragraph", text["total"].format(total=total, pages=len(entries)))],
                         "subs": []}]}
    return render_html(doc, SITE_NOTE, lang)


def site(root, out_dir):
    """Pages for GitHub Pages into out_dir — only from a consistent source (see header)."""
    docs_dir = os.path.join(root, DOCS_DIRNAME)
    if not os.path.isdir(docs_dir):
        print(f"docs-diagrams: {docs_dir} fehlt", file=sys.stderr)
        return 2
    if os.path.exists(out_dir) and (not os.path.isdir(out_dir) or os.listdir(out_dir)):
        print(f"docs-diagrams: --site braucht ein leeres oder neues Verzeichnis: {out_dir}",
              file=sys.stderr)
        return 2
    try:
        files, _, problems, entries, english_entries = plan(root)
    except DocError as exc:
        print(f"docs-diagrams: {exc}", file=sys.stderr)
        return 2
    if problems:
        for line in problems:
            print(line)
        sys.stdout.flush()
        print(f"docs-diagrams: {len(problems)} Uebersetzungsbefund(e) — keine Pages-Seiten erzeugt.",
              file=sys.stderr)
        return 1

    en_dir = os.path.join(docs_dir, EN_DIRNAME)
    other = {"de": "en", "en": "de"}
    site_files = {
        "index.html": render_site_index(entries, "de", "de/", "en/index.html"),
        "en/index.html": render_site_index(english_entries, "en", "", "../index.html"),
    }
    for stem, _, _ in entries:
        name = stem + TARGET_SUFFIX
        for lang, source_dir, overview in (("de", docs_dir, "../index.html"), ("en", en_dir, "index.html")):
            site_files[f"{lang}/{name}"] = site_page(
                files[os.path.join(source_dir, name)],
                [(overview, f"← {SITE_TEXT[lang]['overview']}"),
                 (f"../{other[lang]}/{name}", SITE_TEXT[lang]["other"])])
    for rel, content in sorted(site_files.items()):
        path = os.path.join(out_dir, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
    print(f"Pages-Seiten erzeugt — {len(site_files)} Dateien unter {out_dir}.")
    return 0


def main(argv):
    root, check, stubs, site_dir = None, False, False, None
    args = list(argv)
    while args:
        arg = args.pop(0)
        if arg == "--check":
            check = True
        elif arg == "--add-missing":
            stubs = True
        elif arg == "--site":
            if not args:
                print("docs-diagrams: --site braucht ein Verzeichnis", file=sys.stderr)
                return 2
            site_dir = args.pop(0)
        elif arg == "--root":
            if not args:
                print("docs-diagrams: --root braucht ein Verzeichnis", file=sys.stderr)
                return 2
            root = args.pop(0)
        elif arg in ("-h", "--help"):
            print(__doc__)
            return 0
        else:
            print(f"docs-diagrams: unbekanntes Argument {arg!r}", file=sys.stderr)
            return 2
    if check + stubs + (site_dir is not None) > 1:
        print("docs-diagrams: --check, --add-missing und --site schliessen sich aus", file=sys.stderr)
        return 2
    if root is None:
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    root = os.path.abspath(root)
    if site_dir is not None:
        return site(root, os.path.abspath(site_dir))
    if not stubs:
        return run(root, check)
    if not os.path.isdir(os.path.join(root, DOCS_DIRNAME)):
        print(f"docs-diagrams: {os.path.join(root, DOCS_DIRNAME)} fehlt", file=sys.stderr)
        return 2
    try:
        return add_missing(root)
    except DocError as exc:
        print(f"docs-diagrams: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
