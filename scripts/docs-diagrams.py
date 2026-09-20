#!/usr/bin/env python3
"""Erzeugt die Diagramm-Seiten in docs/ aus ihrer Markdown-Quelle (TODO f54bb95d).

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

Aufruf
------
    python3 scripts/docs-diagrams.py            # HTML + Index erzeugen
    python3 scripts/docs-diagrams.py --check    # nur pruefen, nichts schreiben
    python3 scripts/docs-diagrams.py --root DIR # andere Repo-Wurzel

Exit: 0 = alles deckungsgleich, 1 = Drift (nur --check), 2 = Quelle fehlt/kaputt
"""
import os
import re
import sys

DOCS_DIRNAME = "docs"
SOURCE_SUFFIX = "-diagrams.md"
TARGET_SUFFIX = "-diagrams.html"
INDEX_NAME = "diagramme.md"

GENERATED_NOTE = ("Erzeugt aus {source} von scripts/docs-diagrams.py — "
                  "nicht von Hand bearbeiten.")
INDEX_NOTE = ("Erzeugt aus docs/*-diagrams.md von scripts/docs-diagrams.py — "
              "nicht von Hand bearbeiten.")

# ---------------------------------------------------------------------------
# HTML-Huelle
#
# Sammelt die Stile aller neun frueheren Einzelseiten (die sich nur darin
# unterschieden, welche Klassen die jeweilige Seite gerade brauchte). Die
# Mermaid-Einbindung und die themeVariables sind unveraendert uebernommen.
# ---------------------------------------------------------------------------
TEMPLATE = """<!DOCTYPE html>
<!-- @@NOTE@@ -->
<html lang="de">
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
    """Bloecke bis zur naechsten Ueberschrift aus stop_at lesen."""
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
            blocks.append(("mermaid" if lang == "mermaid" else "code",
                           "\n".join(body)))
            continue
        if line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                rows.append(lines[i])
                i += 1
            if len(rows) < 2 or not TABLE_DIVIDER.match(rows[1].strip()):
                raise DocError(f"Zeile {i}: Tabelle ohne Trennzeile")
            blocks.append(("table", [split_row(rows[0])] +
                           [split_row(r) for r in rows[2:]]))
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


TREE_BADGES = (("[NEU]", "green"), ("[ENTFERNT]", "red"))
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
            head, rows = payload[0], payload[1:]
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
            html.append(tree_body(payload))
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


def render_html(doc, source_name):
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
            .replace("@@NOTE@@", GENERATED_NOTE.format(source=source_name))
            .replace("@@TITLE@@", esc(doc["title"]))
            .replace("@@HEADING@@", inline(doc["title"]))
            .replace("@@SUBTITLE@@", inline(doc["subtitle"]))
            .replace("@@NAV@@", nav)
            .replace("@@BODY@@", "\n".join(body)))


def shared_prefix(titles):
    """Gemeinsames Namenspraefix der Seiten ("Produkt — "), sofern es eines gibt.

    Im Verzeichnis steht es sonst neunmal untereinander. Gemessen statt fest
    verdrahtet: heisst das Produkt eines Tages anders, wandert die Zeile mit.
    """
    if len(titles) < 2:
        return ""
    prefix = os.path.commonprefix(titles)
    match = re.match(r"^(.*[—–-]\s)", prefix)
    return match.group(1) if match else ""


def render_index(entries):
    """Inhaltsverzeichnis aus den Quellen — damit auch der Index keine Zweitquelle ist."""
    prefix = shared_prefix([doc["title"] for _, doc, _ in entries])
    lines = [f"<!-- {INDEX_NOTE} -->",
             "",
             "# Diagramme",
             "",
             "Ablauf- und Architekturdiagramme als Mermaid-Zaeune: GitHub rendert sie direkt"
             " in der Markdown-Fassung. Jede Seite gibt es zusaetzlich als HTML"
             " (`<name>-diagrams.html`) — die wird aus der Markdown-Datei erzeugt und nicht"
             " von Hand gepflegt.",
             "",
             "| Seite | Inhalt | Diagramme |",
             "|---|---|---:|"]
    for stem, doc, count in entries:
        title = doc["title"][len(prefix):] if prefix else doc["title"]
        lines.append(f"| [{title}]({stem}{SOURCE_SUFFIX}) | {doc['subtitle']} | {count} |")
    total = sum(count for _, _, count in entries)
    lines += ["",
              f"Zusammen {total} Diagramme in {len(entries)} Seiten.",
              "",
              "## Erzeugen und pruefen",
              "",
              "```bash",
              "python3 scripts/docs-diagrams.py          # HTML-Fassungen neu erzeugen",
              "python3 scripts/docs-diagrams.py --check  # meldet Drift, Exit-Code 1",
              "```",
              ""]
    return "\n".join(lines)


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
        except DocError as exc:
            raise DocError(f"{stem}{SOURCE_SUFFIX}: {exc}") from exc
        count = sum(1 for section in doc["sections"]
                    for kind, _ in section["blocks"] if kind == "mermaid")
        count += sum(1 for section in doc["sections"] for _, blocks in section["subs"]
                     for kind, _ in blocks if kind == "mermaid")
        entries.append((stem, doc, count))
    return entries


def wanted_files(docs_dir):
    """Pfad -> erwarteter Inhalt, fuer beide Modi dieselbe Berechnung."""
    entries = collect(docs_dir)
    if not entries:
        raise DocError(f"Keine {SOURCE_SUFFIX}-Quellen unter {docs_dir}")
    files = {}
    for stem, doc, _ in entries:
        files[os.path.join(docs_dir, stem + TARGET_SUFFIX)] = render_html(
            doc, f"{DOCS_DIRNAME}/{stem}{SOURCE_SUFFIX}")
    files[os.path.join(docs_dir, INDEX_NAME)] = render_index(entries)
    return files


def run(root, check):
    docs_dir = os.path.join(root, DOCS_DIRNAME)
    if not os.path.isdir(docs_dir):
        print(f"docs-diagrams: {docs_dir} fehlt", file=sys.stderr)
        return 2
    try:
        files = wanted_files(docs_dir)
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
            reason = "fehlt" if current is None else "weicht von der Markdown-Quelle ab"
            drift.append(f"DRIFT {rel} {reason}")
            continue
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
        print(f"geschrieben: {rel}")

    if check:
        for line in drift:
            print(line)
        if drift:
            print(f"docs-diagrams: {len(drift)} Datei(en) nicht deckungsgleich —"
                  " 'python3 scripts/docs-diagrams.py' laufen lassen und die"
                  " Aenderungen mitcommitten.", file=sys.stderr)
            return 1
        print(f"Diagramm-Seiten OK — {len(files)} erzeugte Dateien decken sich mit docs/*{SOURCE_SUFFIX}.")
    else:
        print(f"Diagramm-Seiten erzeugt — {len(files)} Dateien geprueft/geschrieben.")
    return 0


def main(argv):
    root, check = None, False
    args = list(argv)
    while args:
        arg = args.pop(0)
        if arg == "--check":
            check = True
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
    if root is None:
        root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    return run(os.path.abspath(root), check)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
