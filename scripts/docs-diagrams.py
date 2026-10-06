#!/usr/bin/env python3
"""Erzeugt die Diagramm-Seiten in docs/ aus ihrer Markdown-Quelle (TODO f54bb95d),
dazu ihre englische Fassung in docs/en/ (TODO 47bd4d27).

Warum es das gibt
-----------------
Die Diagramm-Dokumente lagen nur als HTML vor. GitHub rendert HTML-Dateien im
Repository nicht — ein Besucher sah dort Quelltext statt Diagramme. Mermaid in
```mermaid-Zaeunen rendert GitHub dagegen nativ.

Deshalb ist die **Markdown-Datei die Quelle** (`docs/<name>-diagrams.md`) und die
HTML-Fassung das **Erzeugnis** (`docs/<name>-diagrams.html`, fuer GitHub Pages).
Nicht nur die Diagramme, sondern die ganze Seite wird erzeugt: Prosa, Tabellen,
Karten, Dateibaeume. Nur so bleibt es bei einer Quelle. Wuerde das Skript bloss
die Diagramme umkopieren, waere der Rest der HTML-Seite eine zweite, von Hand
gepflegte Fassung — genau das, was vermieden werden soll.

Die Huelle (Farben, Layout, Mermaid-Einbindung, Navigation) steckt als Vorlage
unten in dieser Datei und ist fuer alle Seiten dieselbe; vorher stand sie neunmal
fast wortgleich in den HTML-Dateien.

Zusaetzlich entsteht `docs/diagramme.md` als Inhaltsverzeichnis — ebenfalls
erzeugt, nicht gepflegt.

Englische Fassung: dieselbe Quelle plus eine Uebersetzungstabelle je Seite
-------------------------------------------------------------------------
Die deutsche Seite bleibt die Quelle fuer den Aufbau *und* den deutschen Text. Die
englische entsteht aus ihr und einer Tabelle je Seite, nach dem Muster von de.ts/en.ts
der Oberflaeche:

    docs/i18n/<name>-diagrams.en.json    {"deutsches Textstueck": "English text", ...}

Erzeugt werden docs/en/<name>-diagrams.md und .html sowie docs/en/diagramme.md, mit
denselben Dateinamen wie deutsch, damit die DE/EN-Paritaetspruefung sie paart. Eine
zweite, von Hand gepflegte englische Fassung gibt es nicht.

Textstuecke (= Schluessel) sind Titel, Untertitel, Ueberschriften samt Beiwort,
Absaetze, Listenpunkte, Tabellenzellen und die Mermaid-Beschriftungen — welche das je
Diagrammtyp sind, steht in scripts/lib/mermaid_labels.py — dazu in Dateibaeumen und
Codebloecken der Kommentar hinter "←" (ohne Marke, 08e6fa4d). Unveraendert bleiben
Knoten-IDs, Pfeile und der Rest der Codebloecke; die Marken [NEU]/[ENTFERNT] heissen
englisch [NEW]/[REMOVED] (TREE_MARKERS_EN), ohne Tabelle. Ein Textstueck ohne Buchstaben
ausserhalb von Inline-Code (`mvn test`, "9090", "—") braucht keinen Eintrag; ein Wert
gleich dem Schluessel ist erlaubt (Klassennamen, schon englische Beschriftungen).

Die Tabellen haben keine Lesereihenfolge-Pflicht; --add-missing schreibt sie aber in
der Reihenfolge der Seite, mit leerem Wert fuer jeden neuen Schluessel.

Markdown-Konventionen (mehr kann die Vorlage nicht, mehr braucht sie nicht)
--------------------------------------------------------------------------
    # Titel                     -> Kopfzeile der Seite
    <Absatz danach>             -> Untertitel der Kopfzeile
    ## Abschnitt — *Beiwort*    -> <section> mit <h2>; das kursive Beiwort hinter
                                   dem Gedankenstrich wird zur kleinen Beischrift
                                   (weglassbar). Die Navigation entsteht aus den
                                   Abschnittsueberschriften.
    ### Unterabschnitt          -> <h3>; zwei oder mehr aufeinanderfolgende, die
                                   nur Absaetze/Listen enthalten, werden zum
                                   zweispaltigen Kartenraster.
    ```mermaid                  -> <pre class="mermaid">
    ``` (jede andere Sprache)   -> <pre class="tree"> (Dateibaeume, Konsolen)
    Tabellen, Listen, `Code`, **fett**, *kursiv*, [Text](Ziel)

Pruefen
-------
--check vergleicht alle Erzeugnisse beider Sprachen mit dem, was jetzt entstuende, und
meldet Erzeugnisse ohne Quelle. Rot ist es ausserdem, wenn ein Textstueck keine
Uebersetzung hat (Schluessel fehlt oder Wert leer), wenn ein Schluessel verwaist ist,
wenn ein Wert Inline-Code oder Zahlen anders nennt als das Original, oder wenn die
Uebersetzung den Seitenaufbau oder das Geruest eines Diagramms veraendert. So kann die
deutsche Seite nicht still weiterlaufen, ohne dass die englische nachzieht. Die
englischen Dateien einer Seite werden nur aus einer stimmigen Tabelle geschrieben.

GitHub Pages (--site DIR, TODO ee5a4a04)
----------------------------------------
Schreibt die Seiten fuer GitHub Pages in ein leeres oder neues Verzeichnis, gebaut aus
der Markdown-Quelle (nicht aus den HTML-Dateien im Repo) — docs/ bleibt unberuehrt:

    DIR/index.html                  Verzeichnis deutsch (dieselbe Tabelle wie diagramme.md)
    DIR/de/<name>-diagrams.html     deutsche Seiten
    DIR/en/index.html               Verzeichnis englisch
    DIR/en/<name>-diagrams.html     englische Seiten

Jede Seite bekommt in der Navigation einen Link zum Verzeichnis und zur anderen Sprache.
Ist eine Uebersetzung offen, entsteht nichts (Exit 1): Pages zeigt entweder beide Sprachen
vollstaendig oder bleibt beim letzten Stand. Benutzt von .github/workflows/pages.yml.

Aufruf
------
    python3 scripts/docs-diagrams.py                # HTML, englische Seiten, Verzeichnisse
    python3 scripts/docs-diagrams.py --check        # nur pruefen, nichts schreiben
    python3 scripts/docs-diagrams.py --add-missing  # fehlende Schluessel leer eintragen
    python3 scripts/docs-diagrams.py --site DIR     # Seiten fuer GitHub Pages nach DIR
    python3 scripts/docs-diagrams.py --root DIR     # andere Repo-Wurzel

Exit: 0 = alles deckungsgleich, 1 = Drift (--check) oder offene Uebersetzung,
      2 = Quelle fehlt/kaputt, Tabelle kaputt, Mermaid-Syntax ohne Regel oder
          --site-Verzeichnis nicht leer
"""
import json
import os
import re
import sys

# mermaid_labels liegt in scripts/lib; Tests laden dieses Skript ueber
# spec_from_file_location, dabei liegt kein Skriptverzeichnis auf sys.path.
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

# Die Huelle des Verzeichnisses je Sprache; die Zeilen darin kommen aus den Seiten.
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

# Texte der Pages-Fassung (--site) je Sprache; Tabellenkopf und Summe kommen aus INDEX_TEXT.
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
# HTML-Huelle
#
# Sammelt die Stile aller neun frueheren Einzelseiten (die sich nur darin
# unterschieden, welche Klassen die jeweilige Seite gerade brauchte). Die
# Mermaid-Einbindung und die themeVariables sind unveraendert uebernommen.
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
    """Die Markdown-Quelle haelt die Konventionen nicht ein."""


# ---------------------------------------------------------------------------
# Lesen
# ---------------------------------------------------------------------------
FENCE = re.compile(r"^```(\w*)\s*$")
HEADING2 = re.compile(r"^##\s+(.*)$")
HEADING3 = re.compile(r"^###\s+(.*)$")
# "Abschnitt — *Beiwort*": nur ein kursiver Zusatz am Zeilenende zaehlt, damit ein
# Gedankenstrich mitten im Titel nichts abschneidet.
BADGE = re.compile(r"^(.*?)\s+[—–-]\s+\*([^*]+)\*$")
BULLET = re.compile(r"^-\s+(.*)$")
TABLE_DIVIDER = re.compile(r"^\|[\s:|-]+\|$")


def slugify(text, taken):
    """Anker aus der Ueberschrift — dieselbe Regel wie bei GitHub, ohne Zweitquelle."""
    slug = re.sub(r"[^\w\s-]", "", text.lower(), flags=re.UNICODE)
    slug = re.sub(r"[\s_]+", "-", slug).strip("-") or "abschnitt"
    candidate, n = slug, 1
    while candidate in taken:
        n += 1
        candidate = f"{slug}-{n}"
    taken.add(candidate)
    return candidate


def split_row(line):
    """Tabellenzeile in Zellen zerlegen; '\\|' ist ein Balken, kein Trenner."""
    inner = line.strip()
    if inner.startswith("|"):
        inner = inner[1:]
    if inner.endswith("|") and not inner.endswith("\\|"):
        inner = inner[:-1]
    cells = re.split(r"(?<!\\)\|", inner)
    return [c.strip().replace("\\|", "|") for c in cells]


def parse_blocks(lines, start, stop_at):
    """Bloecke bis zur naechsten Ueberschrift aus stop_at lesen.

    Ein Block ist (Art, Inhalt): paragraph -> Text, list -> [Punkte],
    table -> ([Kopfzeile, Zeilen...], [Trennzellen]), mermaid -> Quelltext,
    code -> (Sprache, Text). Trennzellen und Sprache braucht nur die Markdown-Ausgabe.
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
    """Markdown-Quelle -> {title, subtitle, sections}."""
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
# Schreiben
# ---------------------------------------------------------------------------
def esc(text):
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def inline(text):
    """Inline-Markdown -> HTML. Code zuerst, damit darin nichts weiter ausgewertet wird."""
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


# Die einzigen Tags, die Mermaid in Beschriftungen selbst auswertet. Sie bleiben
# echtes Markup, alles andere wird maskiert (siehe mermaid_body).
MERMAID_MARKUP = re.compile(r"&lt;(/?)(br\s*/?|i|b)&gt;")


def mermaid_body(code):
    """Mermaid-Quelltext fuer <pre> absichern.

    Maskieren ist noetig: In den frueheren HTML-Dateien stand `List<WorkflowRun>`
    unmaskiert im <pre> — der Browser hielt das fuer ein Tag und verschluckte es,
    die Beschriftung war weg. Wo es darauf ankam, war es schon von Hand maskiert
    (`&lt;&lt;interface&gt;&gt;`), nur eben nicht ueberall.

    Ausgenommen bleiben die Tags, die Mermaid in Beschriftungen selbst auswertet:
    `<br/>` fuer den Zeilenumbruch, `<i>`/`<b>` fuer die Auszeichnung. Fuer die
    Pfeile (`-->`) ist beides gleichwertig: der Browser gibt ueber innerHTML
    ohnehin `--&gt;` zurueck, egal wie es in der Datei steht.
    """
    return MERMAID_MARKUP.sub(lambda m: f"<{m.group(1)}{m.group(2)}>", esc(code))


TREE_BADGES = (("[NEU]", "green"), ("[ENTFERNT]", "red"), ("[NEW]", "green"), ("[REMOVED]", "red"))
# Marken je Sprache (08e6fa4d): die deutsche Quelle schreibt [NEU]/[ENTFERNT], die englische Seite
# bekommt [NEW]/[REMOVED] — feste Woerter, keine Eintraege in der Uebersetzungstabelle.
TREE_MARKERS_EN = {"[NEU]": "[NEW]", "[ENTFERNT]": "[REMOVED]"}
TREE_MARKER = re.compile(r"\s*\[(?:NEU|ENTFERNT|NEW|REMOVED)\]")
TREE_COMMENT = re.compile(r"^(\s*)(.*?)(\s*)$")


def tree_text(code, fn, markers=None):
    """Codeblock mit uebersetzten Kommentaren hinter "←" und Marken nach markers (08e6fa4d).

    fn(kommentar) bekommt den Kommentar ohne Marken und ohne Leerraum am Rand; die Marken
    stehen danach wieder hinter dem Kommentar. Pfad und Baumstriche bleiben, wie sie sind.
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
    """Dateibaum einfaerben: Verzeichnisse, Kommentare hinter dem Pfeil, Marken."""
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
    """Nur Absaetze und Listen passen in eine Karte — Tabellen und Diagramme nicht."""
    return bool(sub[1]) and all(kind in ("paragraph", "list") for kind, _ in sub[1])


def render_subs(subs):
    """Aufeinanderfolgende Karten-Unterabschnitte buendeln, den Rest als <h3>."""
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
    """Gemeinsames Namenspraefix der Seiten ("Produkt — "), sofern es eines gibt.

    Im Verzeichnis steht es sonst in jeder Zeile. Gemessen statt fest
    verdrahtet: heisst das Produkt eines Tages anders, wandert die Zeile mit.
    """
    if len(titles) < 2:
        return ""
    prefix = os.path.commonprefix(titles)
    match = re.match(r"^(.*[—–-]\s)", prefix)
    return match.group(1) if match else ""


def render_index(entries, lang):
    """Inhaltsverzeichnis aus den Quellen — damit auch der Index keine Zweitquelle ist."""
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
    """Struktur -> Markdown in der Form der deutschen Quellen.

    Fuer eine Quelle in dieser Form ist das Ergebnis deckungsgleich mit ihr; die englische
    Seite hat damit Zeile fuer Zeile denselben Aufbau wie die deutsche.
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
# Englische Fassung
# ---------------------------------------------------------------------------
LETTER = re.compile(r"[^\W\d_]")
CODE_SPAN = re.compile(r"`[^`]*`")
DIGITS = re.compile(r"\d+")


def needs_translation(text, markdown=True):
    """Hat das Textstueck etwas zu uebersetzen? Buchstaben zaehlen, Inline-Code nicht.

    Mermaid-Beschriftungen kennen keinen Inline-Code (Backticks bedeuten dort etwas
    anderes), dort zaehlt jeder Buchstabe.
    """
    return bool(LETTER.search(CODE_SPAN.sub("", text) if markdown else text))


def map_text(doc, fn, markers=None):
    """Neue Seite, in der jedes Textstueck durch fn(text, markdown) ersetzt ist.

    Die Reihenfolge ist die Lesereihenfolge. segments() und translate() gehen beide diesen
    Weg — so kennt die Tabelle genau die Stellen, die die Seite hat, nicht mehr und nicht
    weniger. Diagramme bekommen nur neue Beschriftungen, Codebloecke nur neue Kommentare
    hinter "←" und die Marken nach markers (tree_text).
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
    """Die Textstuecke einer Seite, die einen Eintrag in ihrer Tabelle brauchen (ohne Dubletten)."""
    found = {}

    def record(text, markdown):
        if needs_translation(text, markdown):
            found.setdefault(text, None)
        return text

    map_text(doc, record)
    return list(found)


def value_problem(key, value):
    """Warum ein Wert nicht zu seinem Schluessel passt — oder None."""
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
    """Englische Seite aus deutscher Seite und Tabelle.

    Liefert (seite, fehlend, ungueltig, verwaist). Fehlt ein Wert, steht an seiner Stelle
    der deutsche Text — geschrieben wird eine solche Seite nicht, siehe plan().
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
    """Aufbau einer Seite ohne ihre Texte — fuer Deutsch und Englisch identisch.

    Codebloecke gehen woertlich ein bis auf die Kommentare hinter "←" und die Sprache der
    Marken, Diagramme als Geruest ohne Beschriftungen, Listen und Tabellen mit ihrer Groesse,
    Absaetze nur als Art.
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
    """Veraendert die Uebersetzung den Aufbau? Beschreibung der ersten Stelle oder None.

    Zwei Stufen: jedes Diagramm fuer sich (dann laesst sich die Zeile nennen), danach die
    ganze Seite so, wie sie als Markdown geschrieben und wieder gelesen wuerde — ein Wert,
    der mit "## " beginnt, eroeffnete dort sonst still einen neuen Abschnitt.
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
    """Schluessel so ausgeben, wie er in der JSON-Tabelle steht."""
    return json.dumps(text, ensure_ascii=False)


def load_table(path, rel):
    """Tabelle lesen; None, wenn es sie nicht gibt. Kaputtes JSON ist ein harter Fehler."""
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
# Ablauf
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
            # Liest jedes Diagramm einmal mit den Beschriftungsregeln: unbekannte
            # Mermaid-Syntax faellt hier auf, mit Seitenname, nicht erst beim Uebersetzen.
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
    """Erzeugnisse, deren deutsche Quelle es nicht mehr gibt."""
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
    """(Pfad -> erwarteter Inhalt, verwaiste Erzeugnisse, Uebersetzungsbefunde).

    Fuer beide Modi dieselbe Berechnung. Die englischen Dateien einer Seite stehen nur
    darin, wenn ihre Tabelle stimmig ist; das englische Verzeichnis nur, wenn das fuer
    alle Seiten gilt.
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
    sys.stdout.flush()      # Zusammenfassung (stderr) nach der Liste, auch in 2>&1-Mitschnitten
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
    """Jede Tabelle auf den Stand ihrer Seite bringen, ohne einen Wert zu erfinden.

    Neue Schluessel bekommen einen leeren Wert (bleibt rot, bis jemand uebersetzt),
    vorhandene Werte bleiben, verwaiste Schluessel wandern ans Ende statt zu verschwinden —
    oft ist ihr Text die Uebersetzung des neuen Schluessels, nur leicht veraendert.
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
    """Pages-Fassung einer Seite: Verzeichnis und andere Sprache vorn in die Navigation.

    Nur fuer --site; die HTML-Dateien im Repo bleiben, wie sie sind.
    """
    extra = "".join(f'  <a href="{href}">{esc(label)}</a>\n' for href, label in links)
    if html.count("<nav>\n") != 1:                          # pragma: no cover
        raise DocError("Vorlage ohne eindeutiges <nav>")
    return html.replace("<nav>\n", "<nav>\n" + extra, 1)


def render_site_index(entries, lang, page_dir, other_index):
    """Verzeichnis als HTML-Seite, aus denselben Eintraegen wie diagramme.md."""
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
    """Seiten fuer GitHub Pages nach out_dir — nur aus einer stimmigen Quelle (siehe Kopf)."""
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
