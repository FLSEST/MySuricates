#!/usr/bin/env python3
"""Code-Explainer Renderer: erklaeren-JSON + SVGs + state.json -> eine HTML-Seite + Index + State.

Aufruf: render_page.py <run_dir> <explainer_dir> <repo_root>
Persistenz-Reihenfolge = Fehlersicherheit: erst Seite + Index schreiben, DANN
edges.json/state.json fortschreiben — ein gescheiterter Lauf lässt den State unberührt.
"""
import html
import json
import os
import re
import sys
from datetime import date, datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common

STATIC_GRAPHS = [
    ("backend_deps", "Backend-Package-Abhängigkeiten",
     "Deterministisch aus den Java-Imports extrahiert (com.automation.*, Tiefe 2)."),
    ("hierarchies", "Backend-Familien (Erweiterungspunkte)",
     "Deterministisch: Interfaces/Basisklassen mit ≥3 Implementierungen — die Plugin-Muster des Systems."),
    ("entities", "Entity-Modell (JPA)",
     "Deterministisch aus den @Entity-Klassen extrahiert; Kanten von der Owning-Seite."),
    ("frontend_deps", "Frontend-Importstruktur",
     "Deterministisch aus den TypeScript-Imports extrahiert (pages/hooks → hooks/api/stores)."),
]

CSS = """
:root{color-scheme:light}
body{font-family:system-ui,-apple-system,'Segoe UI',sans-serif;margin:0;background:#f1f5f9;color:#1e293b;line-height:1.55}
main{max-width:1100px;margin:0 auto;padding:24px 20px 60px}
h1{font-size:1.6rem;margin:.2em 0}
h2{font-size:1.25rem;margin-top:2.2em;border-bottom:2px solid #e2e8f0;padding-bottom:.3em}
h3{font-size:1.05rem;margin-bottom:.4em}
.card{background:#fff;border:1px solid #e2e8f0;border-radius:10px;padding:16px 20px;margin:14px 0;box-shadow:0 1px 2px rgba(15,23,42,.05)}
.badge{display:inline-block;padding:2px 10px;border-radius:999px;font-size:.75rem;font-weight:600;vertical-align:middle}
.badge.delta{background:#dbeafe;color:#1d4ed8}
.badge.baseline{background:#dcfce7;color:#15803d}
.badge.updated{background:#fef3c7;color:#b45309;margin-left:8px}
.meta{color:#64748b;font-size:.85rem}
.svgbox{overflow-x:auto}
.svgbox svg{max-width:100%;height:auto}
details{margin:8px 0}
summary{cursor:pointer;font-weight:600}
details .answer{padding:8px 0 4px 16px;border-left:3px solid #e2e8f0;margin-top:8px}
pre{background:#0f172a;color:#e2e8f0;padding:12px;border-radius:8px;overflow-x:auto;font-size:.85rem}
code{font-family:ui-monospace,'SF Mono',monospace;font-size:.9em;background:#e2e8f0;border-radius:4px;padding:1px 5px}
pre code{background:none;padding:0}
.difftext{font-family:ui-monospace,monospace;font-size:.8rem;white-space:pre-wrap;color:#475569;background:#f8fafc;border-radius:6px;padding:10px;margin-top:10px}
.cites{font-size:.75rem;color:#94a3b8;margin-top:6px;word-break:break-all}
.note{background:#fef2f2;border:1px solid #fecaca;color:#b91c1c;border-radius:8px;padding:10px 14px;margin:10px 0;font-size:.85rem}
footer{margin-top:40px;color:#94a3b8;font-size:.8rem;text-align:center}
ul.plain{list-style:none;padding:0}
ul.plain li{padding:6px 0;border-bottom:1px solid #f1f5f9}
"""


def esc(s):
    return html.escape(str(s or ""), quote=True)


def load_ai_json(path):
    """(data, fehler) — strippt Markdown-Zäune und Text um das JSON herum."""
    try:
        text = open(path, encoding="utf-8").read().strip()
    except OSError as e:
        return None, "explain.raw fehlt (%s)" % e
    if text.startswith("```"):
        lines = text.splitlines()[1:]
        while lines and lines[-1].strip().startswith("```"):
            lines = lines[:-1]
        text = "\n".join(lines).strip()
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end <= start:
        return None, "kein JSON-Objekt in der Erklär-Ausgabe gefunden"
    try:
        return json.loads(text[start:end + 1]), None
    except json.JSONDecodeError as e:
        return None, "JSON kaputt: %s" % e


BOLD_RE = re.compile(r"\*\*(.+?)\*\*")
CODE_RE = re.compile(r"`([^`]+)`")


def inline_md(s):
    s = esc(s)
    s = CODE_RE.sub(r"<code>\1</code>", s)
    return BOLD_RE.sub(r"<strong>\1</strong>", s)


def md_to_html(md):
    """Kleines Markdown-Subset: ##/###, Listen, **fett**, `code`, fenced Code, Absätze."""
    out, para, items, code = [], [], [], None

    def flush_para():
        if para:
            out.append("<p>%s</p>" % inline_md(" ".join(para)))
            para.clear()

    def flush_list():
        if items:
            out.append("<ul>%s</ul>" % "".join("<li>%s</li>" % inline_md(i) for i in items))
            items.clear()

    for line in (md or "").splitlines():
        s = line.strip()
        if code is not None:
            if s.startswith("```"):
                out.append("<pre><code>%s</code></pre>" % esc("\n".join(code)))
                code = None
            else:
                code.append(line)
            continue
        if s.startswith("```"):
            flush_para(); flush_list(); code = []
        elif s.startswith("### "):
            flush_para(); flush_list(); out.append("<h4>%s</h4>" % inline_md(s[4:]))
        elif s.startswith("## "):
            flush_para(); flush_list(); out.append("<h3>%s</h3>" % inline_md(s[3:]))
        elif s.startswith("# "):
            flush_para(); flush_list(); out.append("<h3>%s</h3>" % inline_md(s[2:]))
        elif s.startswith(("- ", "* ")):
            flush_para(); items.append(s[2:])
        elif not s:
            flush_para(); flush_list()
        else:
            para.append(s)
    if code is not None:
        out.append("<pre><code>%s</code></pre>" % esc("\n".join(code)))
    flush_para()
    flush_list()
    return "\n".join(out)


def inline_svg(path):
    try:
        text = open(path, encoding="utf-8").read()
    except OSError:
        return None
    i = text.find("<svg")
    return text[i:] if i != -1 else None


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", str(s).lower()).strip("-")[:48] or "eintrag"


def merge_catalog(old, updates, remove_ids, head_sha, today):
    """Neue Katalog-Liste + Menge der in diesem Lauf aktualisierten IDs."""
    result = [dict(e) for e in old if e.get("id") not in set(remove_ids)]
    by_id = {e["id"]: e for e in result}
    updated = set()
    for u in updates:
        uid = u.get("id") or "qa-" + slug(u.get("frage", ""))
        entry = by_id.get(uid)
        if entry is None:
            entry = {"id": uid, "created": today}
            result.append(entry)
            by_id[uid] = entry
        entry.update({
            "frage": u.get("frage", entry.get("frage", "")),
            "antwort_md": u.get("antwort_md", ""),
            "cited_files": u.get("cited_files", []),
            "verified_sha": head_sha,
            "updated": today,
        })
        updated.add(uid)
    return result, updated


def merge_flows(old, updates, head_sha, run_dir, warnings):
    """Flows mergen; jede neue dot-Quelle wird validiert, invalide Updates verworfen."""
    result = [dict(f) for f in old]
    by_id = {f["id"]: f for f in result}
    updated, dropped = set(), 0
    probe = os.path.join(run_dir, "flow_probe.svg")
    for u in updates:
        fid = u.get("id") or "flow-" + slug(u.get("titel", ""))
        ok, err = common.render_svg(u.get("dot", ""), probe)
        if not ok:
            dropped += 1
            if fid in by_id:
                warnings.append("Flow %s: Update verworfen (alte Version bleibt) — dot-Fehler: %s" % (fid, err))
            else:
                warnings.append("Neuer Flow „%s“ verworfen — dot-Fehler: %s" % (u.get("titel", fid), err))
            continue
        entry = by_id.get(fid)
        if entry is None:
            entry = {"id": fid}
            result.append(entry)
            by_id[fid] = entry
        entry.update({
            "titel": u.get("titel", fid),
            "beschreibung": u.get("beschreibung", ""),
            "dot": u.get("dot", ""),
            "cited_files": u.get("cited_files", []),
            "verified_sha": head_sha,
        })
        updated.add(fid)
    return result, updated, dropped


def headlines(md, n=3):
    hs = [l.strip().lstrip("#").strip() for l in (md or "").splitlines() if l.strip().startswith("#")]
    if not hs:
        hs = [l.strip() for l in (md or "").splitlines() if l.strip()][:1]
    return hs[:n]


def render_index(archive_dir):
    entries = []
    for fn in os.listdir(archive_dir):
        m = re.match(r"^(\d{4})-(\d{2})-(\d{2})-code-explainer\.html$", fn)
        if m:
            entries.append((date(int(m.group(1)), int(m.group(2)), int(m.group(3))), fn))
    entries.sort(reverse=True)
    items = "\n".join(
        '<li><a href="archive/%s"><strong>KW %d/%d</strong></a> &mdash; Ausgabe vom %s</li>'
        % (fn, d.isocalendar()[1], d.year, d.strftime("%d.%m.%Y"))
        for d, fn in entries)
    body = ("<main><h1>Code-Explainer &middot; Archiv</h1>"
            '<p class="meta">Wöchentlicher Delta-Begleiter für die MySuricates-Codebase &middot; %d Ausgaben</p>'
            '<div class="card"><ul class="plain">%s</ul></div>'
            "<footer>MySuricates Code-Explainer</footer></main>" % (len(entries), items))
    return ('<!DOCTYPE html><html lang="de"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1">'
            "<title>Code-Explainer Archiv</title><style>%s</style></head><body>%s</body></html>"
            % (CSS, body))


def build_page(meta, data, catalog, flows, updated_qa, updated_flows, run_dir, warnings):
    d = date.fromisoformat(meta["report_date"])
    kw = d.isocalendar()[1]
    mode = meta.get("mode", "DELTA")
    badge = '<span class="badge %s">%s</span>' % (mode.lower(), mode)
    parts = ["<main>"]
    parts.append('<div class="card"><h1>Code-Explainer &middot; KW %d/%d %s</h1>'
                 '<p class="meta">Zeitraum: <code>%s</code> &middot; %d Commits &middot; Stand: <code>%s</code> &middot; %s</p></div>'
                 % (kw, d.year, badge, esc(meta.get("range", "?")), meta.get("commit_count", 0),
                    esc(meta.get("head_sha", "")[:7]), d.strftime("%d.%m.%Y")))

    for w in warnings:
        parts.append('<div class="note">%s</div>' % esc(w))

    parts.append("<h2>Was ist neu &amp; warum</h2>")
    parts.append('<div class="card">%s</div>' % md_to_html(data.get("erklaerung_md", "")))

    parts.append("<h2>Diagramme</h2>")
    for key, title, desc in STATIC_GRAPHS:
        svg = inline_svg(os.path.join(run_dir, key + ".svg"))
        parts.append('<div class="card"><h3>%s</h3><p class="meta">%s</p>' % (esc(title), esc(desc)))
        if svg:
            parts.append('<div class="svgbox">%s</div>' % svg)
        else:
            parts.append('<div class="note">Diagramm fehlt in diesem Lauf.</div>')
        try:
            diff = open(os.path.join(run_dir, key + ".diff.txt"), encoding="utf-8").read().strip()
            parts.append('<div class="difftext">%s</div>' % esc(diff))
        except OSError:
            pass
        parts.append("</div>")

    for f in flows:
        fid = f.get("id", "")
        svg_path = os.path.join(run_dir, "flow-%s.svg" % re.sub(r"[^\w-]", "_", fid))
        ok, err = common.render_svg(f.get("dot", ""), svg_path)
        upd = '<span class="badge updated">aktualisiert</span>' if fid in updated_flows else ""
        parts.append('<div class="card"><h3>Datenfluss: %s%s</h3><p class="meta">%s</p>'
                     % (esc(f.get("titel", fid)), upd, esc(f.get("beschreibung", ""))))
        if ok:
            svg = inline_svg(svg_path)
            parts.append('<div class="svgbox">%s</div>' % (svg or ""))
        else:
            parts.append('<div class="note">Diagramm nicht renderbar: %s</div>' % esc(err))
        parts.append('<div class="cites">%s &middot; verifiziert @ %s</div></div>'
                     % (esc(", ".join(f.get("cited_files", []))), esc(f.get("verified_sha", "")[:7])))

    parts.append("<h2>Q&amp;A-Katalog</h2>")
    parts.append('<div class="card">')
    for e in catalog:
        upd = '<span class="badge updated">aktualisiert</span>' if e["id"] in updated_qa else ""
        parts.append("<details><summary>%s%s</summary>"
                     '<div class="answer">%s<div class="cites">%s &middot; verifiziert @ %s (%s)</div></div>'
                     "</details>"
                     % (esc(e.get("frage", "")), upd, md_to_html(e.get("antwort_md", "")),
                        esc(", ".join(e.get("cited_files", []))),
                        esc(e.get("verified_sha", "")[:7]), esc(e.get("updated", e.get("created", "")))))
    if not catalog:
        parts.append('<p class="meta">Noch keine Katalog-Einträge.</p>')
    parts.append("</div>")

    quiz = data.get("quiz", [])
    parts.append("<h2>Quiz</h2>")
    for i, qz in enumerate(quiz, 1):
        parts.append('<div class="card"><p><strong>Frage %d:</strong> %s</p>'
                     '<div class="cites">Bezug: %s</div>'
                     '<details><summary>Antwort anzeigen</summary><div class="answer">%s</div></details></div>'
                     % (i, inline_md(qz.get("frage", "")), esc(qz.get("bezug", "")),
                        md_to_html(qz.get("antwort_md", ""))))
    if not quiz:
        parts.append('<div class="card"><p class="meta">Kein Quiz in diesem Lauf.</p></div>')

    parts.append("<footer>MySuricates Code-Explainer &middot; generiert %s &middot; %s</footer></main>"
                 % (datetime.now().strftime("%d.%m.%Y %H:%M"), esc(os.path.basename(run_dir))))
    body = "\n".join(parts)
    return ('<!DOCTYPE html><html lang="de"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1">'
            "<title>Code-Explainer KW %d/%d</title><style>%s</style></head><body>%s</body></html>"
            % (kw, d.year, CSS, body))


def main():
    run_dir, explainer_dir, repo_root = sys.argv[1], sys.argv[2], sys.argv[3]

    data, err = load_ai_json(os.path.join(run_dir, "explain.raw"))
    if err:
        print("FEHLER: %s" % err)
        sys.exit(1)
    with open(os.path.join(run_dir, "run-meta.json"), encoding="utf-8") as f:
        meta = json.load(f)

    state_path = os.path.join(explainer_dir, "state.json")
    try:
        with open(state_path, encoding="utf-8") as f:
            state = json.load(f)
    except (OSError, ValueError):
        state = {}

    today = meta["report_date"]
    head = meta["head_sha"]
    warnings = []
    catalog, updated_qa = merge_catalog(
        state.get("catalog", []), data.get("catalog_updates", []),
        data.get("catalog_remove_ids", []), head, today)
    flows, updated_flows, dropped_flows = merge_flows(
        state.get("flows", []), data.get("flows", []), head, run_dir, warnings)

    page = build_page(meta, data, catalog, flows, updated_qa, updated_flows, run_dir, warnings)

    archive_dir = os.path.join(explainer_dir, "archive")
    os.makedirs(archive_dir, exist_ok=True)
    report_path = os.path.join(archive_dir, "%s-code-explainer.html" % today)
    common.atomic_write(report_path, page)
    index_path = os.path.join(explainer_dir, "index.html")
    common.atomic_write(index_path, render_index(archive_dir))

    # Persistenz erst NACH erfolgreichem Seiten-Schreiben (Erst-nach-Erfolg-Semantik)
    graphs_dir = os.path.join(explainer_dir, "graphs")
    os.makedirs(graphs_dir, exist_ok=True)
    for key, _, _ in STATIC_GRAPHS:
        src = os.path.join(run_dir, key + ".edges.json")
        if os.path.exists(src):
            common.atomic_write(os.path.join(graphs_dir, key + ".edges.json"),
                                open(src, encoding="utf-8").read())
    new_state = {
        "schema_version": 1,
        "lastExplainedSha": head,
        "baselineDate": state.get("baselineDate") or today,
        "lastRunDate": today,
        "catalog": catalog,
        "flows": flows,
    }
    common.atomic_write(state_path, json.dumps(new_state, ensure_ascii=False, indent=1))

    print("Report: %s" % report_path)
    print("Index:  %s" % index_path)
    print("@@SUMMARY")
    print("Modus: %s | Zeitraum: %s | Commits: %d"
          % (meta.get("mode"), meta.get("range"), meta.get("commit_count", 0)))
    print("Q&A: %d aktualisiert / %d gesamt | Quiz: %d Fragen | Flows: %d gesamt, %d aktualisiert, %d verworfen"
          % (len(updated_qa), len(catalog), len(data.get("quiz", [])),
             len(flows), len(updated_flows), dropped_flows))
    for h in headlines(data.get("erklaerung_md", "")):
        print("- %s" % h)
    print("Report-Datei: %s" % report_path)
    for w in warnings:
        print("Hinweis: %s" % w)


if __name__ == "__main__":
    main()
