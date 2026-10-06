#!/usr/bin/env python3
"""Erzeugt die Referenzseiten der Doku aus dem Code, statt sie von Hand zu pflegen (TODO 1ab3ad21).

Was entsteht, woraus
--------------------
    docs/api-referenz.md   + docs/en/   aus den REST-Controllern
                                        (ai-automation-backend/src/main/java/com/automation/controller/**),
                                        dem Enum ErrorCode und ModuleCatalog (Modulnamen)
    docs/block-referenz.md + docs/en/   aus ai-automation-frontend/src/data/blockConfigSchema.json

Jede Seite beginnt mit dem Kommentar MARKER und der von Hand gepflegten Einleitung aus
scripts/docs-reference/<seite>.<de|en>.md (Titel, Lesehilfe, was der Code nicht hergibt). Alles
danach ist erzeugt. Wer etwas aendern will, aendert die Quelle oder die Vorlage und ruft --write.

API: je Bereich (erstes Pfadsegment nach /api/v1) ein Abschnitt mit der Tabelle Endpunkt | Zweck | Modul.
  - Endpunkte erkennt das Skript wie scripts/public-readiness/api-endpoint-diff.sh: Klassen-@RequestMapping
    als Basis, dazu @Get/Post/Put/Patch/DeleteMapping und @RequestMapping(method = ...), zeilenweise.
    Beide Gates sehen so dieselbe Menge. Methode und Pfad stehen in EINER Zelle ("GET /api/v1/..."),
    weil das Diff-Skript genau diese Schreibweise in der Doku sucht.
  - Zweck ist der erste Satz des Javadoc der Handler-Methode. Fehlt das Javadoc, bleibt die Zelle leer —
    erfunden wird nichts. Kuerzel wie "(TODO 1a2b3c4d)" fallen weg, {@code x} wird zu `x`.
  - Hinweise: Was im Javadoc der Handler-Methode nach dem ersten Satz steht (bis zum ersten Block-Tag wie
    @param/@return/@throws), erscheint unter der Tabelle des Bereichs als Liste "Hinweise" (EN "Notes"),
    eine Zeile je Endpunkt: "- `METHODE /pfad` — Text". So bleiben Regeln (Statuscodes, Grenzen,
    Nebenwirkungen) sichtbar, die nicht in den einen Satz der Tabelle passen.
  - Modul: @RequiresModule an der Methode, sonst an der Klasse (dieselbe Reihenfolge wie
    ModuleGuardInterceptor), sonst Kern.
  - Verlinkbar ist ein Bereich ("api-referenz.md#apiv1schedules"), kein einzelner Endpunkt: Tabellenzeilen
    haben keinen Anker.

Bloecke: die gemeinsamen Felder (on_failure, commonFields), die KI-Felder (ai) und je Block-Typ eine
Tabelle Schluessel | Typ | Pflicht | Standard | Werte | Bedeutung; "#name" in einer Feldliste verweist
auf sharedFields.

Run:  python3 scripts/docs-reference.py --write     Seiten neu schreiben
      python3 scripts/docs-reference.py --check     nur vergleichen, nichts schreiben
      ... --root <dir>                              andere Repo-Wurzel (Tests)
Exit: 0 = aktuell bzw. geschrieben, 1 = (--check) mindestens eine Seite veraltet,
      2 = Quelle oder Vorlage fehlt, obwohl die Seite existiert

Eine Seite, von der weder Quelle noch Erzeugnis existiert, wird uebersprungen: So laeuft die Pruefung
auch in Fixture-Verzeichnissen der Tests, die nur einen Teil des Repos nachbauen.
Nur Python-Standardbibliothek.
"""
from __future__ import annotations

import glob
import json
import os
import re
import sys
from dataclasses import dataclass

MARKER = "<!-- Erzeugt von scripts/docs-reference.py – nicht von Hand ändern -->"
LANGS = ("de", "en")

CONTROLLER_DIR = "ai-automation-backend/src/main/java/com/automation/controller"
ERROR_CODE = "ai-automation-backend/src/main/java/com/automation/exception/ErrorCode.java"
MODULE_CATALOG = "ai-automation-backend/src/main/java/com/automation/modules/ModuleCatalog.java"
BLOCK_SCHEMA = "ai-automation-frontend/src/data/blockConfigSchema.json"
TEMPLATE_DIR = "scripts/docs-reference"


class SourceError(Exception):
    """Eine Quelle ist da, aber nicht lesbar — oder fehlt, obwohl die Seite existiert."""


# ---------------------------------------------------------------------------
# Texte der erzeugten Teile, je Sprache. Alles Inhaltliche kommt aus dem Code.
# ---------------------------------------------------------------------------
TEXT = {
    "de": {
        "errors_heading": "Fehlercodes",
        "errors_intro": "Aus `ErrorCode` (`{path}`). Nennt die Wurfstelle keinen eigenen Text, steht der "
                        "Standardtext im Feld `error`.",
        "errors_head": "| Code | HTTP-Status | Standardtext |",
        "source": "Quelle",
        "sources": "Quellen",
        "api_head": "| Endpunkt | Zweck | Modul |",
        "notes": "Hinweise",
        "core": "Kern",
        "common_heading": "Gemeinsame Felder",
        "common_intro": "Gelten für jeden Block-Typ.",
        "ai_heading": "KI-Felder",
        "ai_intro": "Gelten zusätzlich für {types}.",
        "field_head": "| Schlüssel | Typ | Pflicht | Standard | Werte | Bedeutung |",
        "yes": "ja",
        "one_of": "einer von {keys}",
        "also_common": "Dazu die [gemeinsamen Felder](#gemeinsame-felder).",
        "also_ai": "Dazu die [gemeinsamen Felder](#gemeinsame-felder) und die [KI-Felder](#ki-felder).",
        "legacy_value": "alt, noch angenommen: {value}",
        "ignore_case": "Groß-/Kleinschreibung egal",
        "only_with": "Gelesen nur mit {cond}.",
        "stages_heading": "Stufen",
        "stages_head": "| Stufe | Wirkung |",
        "no_fields": "Keine eigenen Schlüssel.",
    },
    "en": {
        "errors_heading": "Error codes",
        "errors_intro": "From `ErrorCode` (`{path}`). If the throw site gives no text of its own, the "
                        "default text is in the field `error`.",
        "errors_head": "| Code | HTTP status | Default text |",
        "source": "Source",
        "sources": "Sources",
        "api_head": "| Endpoint | Purpose | Module |",
        "notes": "Notes",
        "core": "core",
        "common_heading": "Common fields",
        "common_intro": "Apply to every block type.",
        "ai_heading": "AI fields",
        "ai_intro": "Apply in addition to {types}.",
        "field_head": "| Key | Type | Required | Default | Values | Meaning |",
        "yes": "yes",
        "one_of": "one of {keys}",
        "also_common": "Plus the [common fields](#common-fields).",
        "also_ai": "Plus the [common fields](#common-fields) and the [AI fields](#ai-fields).",
        "legacy_value": "legacy, still accepted: {value}",
        "ignore_case": "case-insensitive",
        "only_with": "Read only with {cond}.",
        "stages_heading": "Stages",
        "stages_head": "| Stage | Effect |",
        "no_fields": "No keys of its own.",
    },
}

# Statuscodes der Konstanten von org.springframework.http.HttpStatus, die vorkommen koennen.
# Eine unbekannte Konstante erscheint unter ihrem Namen statt als Zahl — nichts wird geraten.
HTTP_STATUS = {
    "OK": 200, "CREATED": 201, "ACCEPTED": 202, "NO_CONTENT": 204,
    "BAD_REQUEST": 400, "UNAUTHORIZED": 401, "PAYMENT_REQUIRED": 402, "FORBIDDEN": 403,
    "NOT_FOUND": 404, "METHOD_NOT_ALLOWED": 405, "NOT_ACCEPTABLE": 406, "REQUEST_TIMEOUT": 408,
    "CONFLICT": 409, "GONE": 410, "PRECONDITION_FAILED": 412, "PAYLOAD_TOO_LARGE": 413,
    "CONTENT_TOO_LARGE": 413, "UNSUPPORTED_MEDIA_TYPE": 415, "UNPROCESSABLE_ENTITY": 422,
    "LOCKED": 423, "TOO_MANY_REQUESTS": 429, "INTERNAL_SERVER_ERROR": 500, "NOT_IMPLEMENTED": 501,
    "BAD_GATEWAY": 502, "SERVICE_UNAVAILABLE": 503, "GATEWAY_TIMEOUT": 504,
}


# ---------------------------------------------------------------------------
# Hilfen
# ---------------------------------------------------------------------------
def cell(text: str) -> str:
    """Text fuer eine Tabellenzelle: einzeilig, senkrechte Striche maskiert."""
    return re.sub(r"\s+", " ", text).strip().replace("|", "\\|")


def read(path: str) -> str:
    with open(path, encoding="utf-8") as fh:
        return fh.read()


# ---------------------------------------------------------------------------
# Javadoc
# ---------------------------------------------------------------------------
# Abkuerzungen, deren Punkt keinen Satz beendet.
ABBREVIATIONS = ("z. B.", "z.B.", "d. h.", "d.h.", "u. a.", "e.g.", "i.e.", "bzw.", "ca.", "vs.",
                 "etc.", "inkl.", "ggf.", "evtl.", "usw.", "max.", "min.", "Nr.", "resp.", "approx.")
_HIDE = "\u2063"  # unsichtbares Trennzeichen, haelt Abkuerzungspunkte waehrend der Satzsuche fest
# Herkunftskuerzel: "(TODO 1a2b3c4d)", "(TODO 1a2b3c4d Ziele Stufe 3)", "(c2cda5ed)", "– TODO 1a2b3c4d".
_TODO_REF = re.compile(r"\s*\((?:TODO\s+)?[0-9a-f]{8}\b[^()]*\)|\s*[–—-]\s*TODO\s+[0-9a-f]{8}\b")
_HTML_CODE = re.compile(r"<code>(.*?)</code>", re.S)
_HTML_TAG = re.compile(r"</?[a-zA-Z][^>]*>")
_PARAGRAPH = re.compile(r"</?p\b[^>]*>")
_SENTENCE_END = re.compile(r"[.!?](?=\s|$)")


def _inline_tags(text: str) -> str:
    """{@code x} -> `x`, {@link A#b label} -> label bzw. `A#b`, {@literal x} -> x; Klammern balanciert."""
    out: list[str] = []
    i = 0
    while i < len(text):
        if text.startswith("{@", i):
            depth, j = 0, i
            while j < len(text):
                if text[j] == "{":
                    depth += 1
                elif text[j] == "}":
                    depth -= 1
                    if depth == 0:
                        break
                j += 1
            inner = text[i + 2:j]
            tag, _, body = inner.partition(" ")
            body = body.strip()
            if tag in ("code", "value"):
                out.append(f"`{body}`")
            elif tag in ("link", "linkplain"):
                target, _, label = body.partition(" ")
                out.append(label.strip() if label.strip() else f"`{target}`")
            else:
                out.append(body)
            i = j + 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def _plain(text: str) -> str:
    """Javadoc-Text als einzeiliges Markdown: Inline-Tags, HTML, Entities und Herkunftskuerzel aufgeloest."""
    text = _inline_tags(text)
    text = _HTML_CODE.sub(lambda m: f"`{m.group(1)}`", text)
    text = _PARAGRAPH.sub(" ", text)
    text = _HTML_TAG.sub("", text)
    text = text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    text = _TODO_REF.sub("", text)
    return re.sub(r"\s+", " ", text).strip()


def describe(javadoc_lines: list[str]) -> tuple[str, str]:
    """(erster Satz, Rest) der Beschreibung eines Javadoc-Blocks (Zeilen ohne /** */ und Sternchen).

    Die Beschreibung endet am ersten Block-Tag (@param, @return, @throws, ...). Der erste Satz endet am
    ersten Satzzeichen vor Leerraum oder am ersten <p>; der Rest ist alles danach, ebenfalls einzeilig.
    """
    text_lines: list[str] = []
    for line in javadoc_lines:
        if line.startswith("@"):  # Block-Tag: dort endet die Beschreibung
            break
        text_lines.append(line)
    parts = _PARAGRAPH.split(" ".join(text_lines), maxsplit=1)
    head = _plain(parts[0])
    tail = _plain(parts[1]) if len(parts) > 1 else ""
    hidden = head
    for abbr in ABBREVIATIONS:
        hidden = hidden.replace(abbr, abbr.replace(".", _HIDE))
    match = _SENTENCE_END.search(hidden)
    first, rest = (hidden[:match.end()], hidden[match.end():]) if match else (hidden, "")
    first = first.replace(_HIDE, ".").strip()
    rest = " ".join(x for x in (rest.replace(_HIDE, ".").strip(), tail) if x)
    return first, rest


def first_sentence(javadoc_lines: list[str]) -> str:
    """Erster Satz eines Javadoc-Blocks (Zeilen ohne /** */ und Sternchen)."""
    return describe(javadoc_lines)[0]


def javadoc_above(lines: list[str], index: int) -> list[str] | None:
    """Javadoc ueber der Annotationsgruppe, zu der Zeile `index` gehoert; None, wenn keins da ist."""
    j = index - 1
    while j >= 0 and (lines[j].strip().startswith("@") or not lines[j].strip()):
        j -= 1
    if j < 0 or not lines[j].strip().endswith("*/"):
        return None
    end = j
    while j >= 0 and "/**" not in lines[j]:
        if "/*" in lines[j] and "/**" not in lines[j]:
            return None  # gewoehnlicher Blockkommentar, kein Javadoc
        j -= 1
    if j < 0:
        return None
    body: list[str] = []
    for raw in lines[j:end + 1]:
        line = raw.strip()
        line = line.removeprefix("/**").removesuffix("*/").strip()
        line = line.removeprefix("*").strip()
        body.append(line)
    return body


# ---------------------------------------------------------------------------
# Controller lesen (Erkennung wie api-endpoint-diff.sh)
# ---------------------------------------------------------------------------
ANN_METHOD = {"Get": "GET", "Post": "POST", "Put": "PUT", "Patch": "PATCH", "Delete": "DELETE"}
METHOD_ORDER = ("GET", "POST", "PUT", "PATCH", "DELETE")
MAPPING_RE = re.compile(r"@(Get|Post|Put|Patch|Delete|Request)Mapping(?:\s*\((.*?)\))?")
CLASS_RE = re.compile(r"\b(?:class|interface)\s+\w+")
CLASS_NAME_RE = re.compile(r"\b(?:class|interface)\s+(\w+)")
REQUIRES_RE = re.compile(r"@RequiresModule\s*\(\s*(?:value\s*=\s*)?(?:ModuleCatalog\.)?([A-Z_]+)\s*\)")


def extract_path_literals(args: str) -> list[str]:
    """Nur value/path liefert Pfade — nicht produces/consumes/headers/params (wie im Diff-Skript)."""
    if not args:
        return []
    m = re.search(r'\b(?:value|path)\s*=\s*(\{[^}]*\}|"[^"]*")', args)
    if m:
        blob = m.group(1)
    else:
        stripped = args.strip()
        m2 = re.match(r'(\{[^}]*\}|"[^"]*")', stripped) if stripped[:1] in ('"', "{") else None
        blob = m2.group(1) if m2 else None
    if not blob:
        return []
    return re.findall(r'"([^"]*)"', blob)


def clean_path(p: str) -> str:
    """Pfad wie im Code, Schraegstriche bereinigt; Pfadvariablen behalten ihren Namen."""
    if not p:
        p = "/"
    if not p.startswith("/"):
        p = "/" + p
    p = re.sub(r"/+", "/", p)
    p = re.sub(r"\{([^}:]*):[^}]*\}", r"{\1}", p)  # {name:regex} -> {name}
    if len(p) > 1:
        p = p.rstrip("/")
    return p


@dataclass
class Endpoint:
    method: str
    path: str
    purpose: str
    module: str | None
    controller: str
    notes: str = ""


@dataclass
class Controller:
    name: str
    purpose: str


def module_keys(root: str) -> dict[str, str]:
    """ModuleCatalog-Konstante -> Modulname (CHAINS -> chains)."""
    path = os.path.join(root, MODULE_CATALOG)
    if not os.path.isfile(path):
        return {}
    return dict(re.findall(r'^\s*([A-Z_]+)\("([a-z0-9-]+)"', read(path), flags=re.M))


def extract_endpoints(root: str) -> tuple[list[Endpoint], dict[str, Controller]]:
    """Alle Endpunkte und je Controller der erste Satz seines Klassen-Javadoc."""
    modules = module_keys(root)
    endpoints: list[Endpoint] = []
    controllers: dict[str, Controller] = {}
    for path in sorted(glob.glob(os.path.join(root, CONTROLLER_DIR, "**", "*.java"), recursive=True)):
        lines = read(path).splitlines()
        class_idx = next((i for i, line in enumerate(lines) if CLASS_RE.search(line)), len(lines))
        name_match = CLASS_NAME_RE.search(lines[class_idx]) if class_idx < len(lines) else None
        controller = name_match.group(1) if name_match else os.path.basename(path)[:-5]
        class_doc = javadoc_above(lines, class_idx) if class_idx < len(lines) else None
        controllers[controller] = Controller(controller, first_sentence(class_doc) if class_doc else "")
        base = ""
        class_module = None
        for i in range(class_idx):
            m = re.search(r"@RequestMapping\s*\(([^)]*)\)", lines[i])
            if m:
                quoted = extract_path_literals(m.group(1))
                if quoted:
                    base = quoted[0]
            r = REQUIRES_RE.search(lines[i])
            if r:
                class_module = r.group(1)
        for i in range(class_idx, len(lines)):
            for m in MAPPING_RE.finditer(lines[i]):
                ann, args = m.group(1), m.group(2) or ""
                if ann in ANN_METHOD:
                    methods = [ANN_METHOD[ann]]
                else:
                    methods = re.findall(r"RequestMethod\.(\w+)", args)
                    if not methods:
                        continue
                quoted = extract_path_literals(args)
                paths = quoted if quoted else [""]
                doc = javadoc_above(lines, i)
                purpose, notes = describe(doc) if doc else ("", "")
                method_module = _method_module(lines, i)
                module = method_module or class_module
                for method in methods:
                    for p in paths:
                        full = (base.rstrip("/") + "/" + p.lstrip("/")) if p else base
                        endpoints.append(Endpoint(method.upper(), clean_path(full), purpose,
                                                  modules.get(module, module.lower()) if module else None,
                                                  controller, notes))
    return endpoints, controllers


def _method_module(lines: list[str], index: int) -> str | None:
    """@RequiresModule in der Annotationsgruppe der Zeile `index` (darueber und darunter)."""
    j = index
    while j >= 0 and (lines[j].strip().startswith("@") or not lines[j].strip()):
        r = REQUIRES_RE.search(lines[j])
        if r:
            return r.group(1)
        j -= 1
    j = index + 1
    while j < len(lines) and lines[j].strip().startswith("@"):
        r = REQUIRES_RE.search(lines[j])
        if r:
            return r.group(1)
        j += 1
    return None


def area_of(path: str) -> str:
    parts = path.split("/")
    if len(parts) >= 4 and parts[1] == "api" and parts[2] == "v1":
        return "/".join(parts[:4])
    return path


def error_codes(root: str) -> list[tuple[str, str, str]]:
    text = read(os.path.join(root, ERROR_CODE))
    rows = re.findall(r'^\s*([A-Z_]+)\(HttpStatus\.([A-Z_]+),\s*"((?:[^"\\]|\\.)*)"\)', text, flags=re.M)
    if not rows:
        raise SourceError(f"{ERROR_CODE}: keine Konstanten gefunden")
    return rows


def render_api(root: str, lang: str, intro: str) -> str:
    t = TEXT[lang]
    out = [MARKER, "", intro.rstrip("\n"), ""]

    out += [f"## {t['errors_heading']}", "", t["errors_intro"].format(path=ERROR_CODE), "",
            t["errors_head"], "|---|---|---|"]
    for code, status, message in error_codes(root):
        number = HTTP_STATUS.get(status, status)
        out.append(f"| `{code}` | {number} | {cell(message)} |")
    out.append("")

    endpoints, controllers = extract_endpoints(root)
    if not endpoints:
        raise SourceError(f"{CONTROLLER_DIR}: keine Endpunkte gefunden")
    rows: dict[tuple[str, str], Endpoint] = {}
    for ep in endpoints:
        key = (ep.method, ep.path)
        known = rows.get(key)
        if known is None:
            rows[key] = ep
        else:
            if ep.purpose and ep.purpose not in known.purpose:
                known.purpose = f"{known.purpose} · {ep.purpose}" if known.purpose else ep.purpose
            if ep.notes and ep.notes not in known.notes:
                known.notes = f"{known.notes} · {ep.notes}" if known.notes else ep.notes
    by_area: dict[str, list[Endpoint]] = {}
    for ep in rows.values():
        by_area.setdefault(area_of(ep.path), []).append(ep)
    for area in sorted(by_area):
        eps = sorted(by_area[area], key=lambda e: (e.path, METHOD_ORDER.index(e.method)
                                                   if e.method in METHOD_ORDER else 99))
        out += [f"## {area}", ""]
        names = sorted({e.controller for e in eps})
        described = [f"`{n}`" + (f" – {controllers[n].purpose}" if controllers[n].purpose else "") for n in names]
        if len(described) == 1:
            out.append(f"{t['source']}: {described[0]}")
        else:
            out += [f"{t['sources']}:", ""] + [f"- {d}" for d in described]
        out += ["", t["api_head"], "|---|---|---|"]
        for ep in eps:
            module = f"`{ep.module}`" if ep.module else t["core"]
            out.append(f"| `{ep.method} {ep.path}` | {cell(ep.purpose)} | {module} |")
        out.append("")
        noted = [ep for ep in eps if ep.notes]
        if noted:
            out += [f"{t['notes']}:", ""]
            out += [f"- `{ep.method} {ep.path}` — {ep.notes}" for ep in noted]
            out.append("")
    return "\n".join(out).rstrip("\n") + "\n"


# ---------------------------------------------------------------------------
# Block-Config
# ---------------------------------------------------------------------------
def _code(value) -> str:
    if isinstance(value, str):
        return f"`{value}`"
    return f"`{json.dumps(value, ensure_ascii=False)}`"


def _type(spec: dict) -> str:
    kind = spec.get("type", "")
    if isinstance(kind, list):
        return " \\| ".join(kind)
    if kind == "array":
        items = spec.get("items") or {}
        if isinstance(items, dict) and isinstance(items.get("type"), str):
            return f"{items['type']}[]"
    return kind


def _field_row(key: str, spec: dict, required: str, lang: str) -> str:
    t = TEXT[lang]
    values: list[str] = []
    for entry in spec.get("values") or []:
        values.append(f"{_code(entry['value'])}: {entry['doc'][lang]}")
    if "legacy" in spec:
        values.append(t["legacy_value"].format(value=_code(spec["legacy"])))
    if spec.get("ignoreCase"):
        values.append(t["ignore_case"])
    if "minimum" in spec:
        values.append(f"≥ {spec['minimum']}")
    meaning = spec["doc"][lang]
    only_with = spec.get("onlyWith")
    if only_with:
        conds = [f"`{k}`" if v == "*" else f"`{k}: {v}`" for k, v in only_with.items()]
        meaning = f"{meaning} {t['only_with'].format(cond=', '.join(conds))}"
    default = _code(spec["default"]) if "default" in spec else ""
    return (f"| `{key}` | {_type(spec)} | {required} | {default} | {cell(' · '.join(values))} "
            f"| {cell(meaning)} |")


def render_blocks(root: str, lang: str, intro: str) -> str:
    t = TEXT[lang]
    try:
        schema = json.loads(read(os.path.join(root, BLOCK_SCHEMA)))
    except json.JSONDecodeError as exc:
        raise SourceError(f"{BLOCK_SCHEMA}: kein gueltiges JSON ({exc})") from exc
    shared = schema.get("sharedFields", {})

    def resolve(ref):
        if isinstance(ref, str):
            if not ref.startswith("#") or ref[1:] not in shared:
                raise SourceError(f"{BLOCK_SCHEMA}: unbekannter Verweis {ref!r}")
            return shared[ref[1:]]
        return ref

    head = [t["field_head"], "|---|---|---|---|---|---|"]
    out = [MARKER, "", intro.rstrip("\n"), ""]
    out += [f"## {t['common_heading']}", "", t["common_intro"], ""] + head
    out.append(_field_row("on_failure", schema["onFailure"], "", lang))
    for key, spec in schema.get("commonFields", {}).items():
        out.append(_field_row(key, spec, "", lang))
    out.append("")

    ai = schema.get("ai", {})
    ai_types = ai.get("blockTypes", [])
    out += [f"## {t['ai_heading']}", "",
            t["ai_intro"].format(types=", ".join(f"`{x}`" for x in ai_types)), ""] + head
    for key, spec in ai.get("fields", {}).items():
        out.append(_field_row(key, spec, "", lang))
    out.append("")

    for block_type, spec in schema["blockTypes"].items():
        required = spec.get("required") or []
        one_of = spec.get("requiredOneOf") or []
        out += [f"## `{block_type}`", "", t["also_ai"] if block_type in ai_types else t["also_common"], ""]
        fields = spec.get("fields") or {}
        if fields:
            out += head
            for key, ref in fields.items():
                if key in required:
                    req = t["yes"]
                elif key in one_of:
                    req = t["one_of"].format(keys=", ".join(f"`{k}`" for k in one_of))
                else:
                    req = ""
                out.append(_field_row(key, resolve(ref), req, lang))
        else:
            out.append(t["no_fields"])
        out.append("")
        if block_type == "compress" and schema.get("compressOps"):
            out += [f"### {t['stages_heading']}", "", t["stages_head"], "|---|---|"]
            for op in schema["compressOps"]:
                out.append(f"| `{op['op']}` | {cell(op['doc'][lang])} |")
            out.append("")
    return "\n".join(out).rstrip("\n") + "\n"


# ---------------------------------------------------------------------------
# Seiten
# ---------------------------------------------------------------------------
@dataclass
class Page:
    name: str
    sources: list[str]
    render: object

    def target(self, lang: str) -> str:
        return f"docs/{self.name}.md" if lang == "de" else f"docs/en/{self.name}.md"

    def template(self, lang: str) -> str:
        return f"{TEMPLATE_DIR}/{self.name}.{lang}.md"


PAGES = (
    Page("api-referenz", [CONTROLLER_DIR, ERROR_CODE], render_api),
    Page("block-referenz", [BLOCK_SCHEMA], render_blocks),
)


def build(root: str) -> tuple[dict[str, str], list[str]]:
    """Erzeugt alle Seiten: ({Zielpfad: Inhalt}, [Fehler]). Seiten ohne Quelle und ohne Erzeugnis entfallen."""
    result: dict[str, str] = {}
    errors: list[str] = []
    for page in PAGES:
        missing = [s for s in page.sources if not os.path.exists(os.path.join(root, s))]
        targets_exist = any(os.path.exists(os.path.join(root, page.target(lang))) for lang in LANGS)
        if missing:
            if targets_exist:
                errors.append(f"{page.target('de')}: Quelle fehlt: {', '.join(missing)}")
            continue
        for lang in LANGS:
            template = os.path.join(root, page.template(lang))
            if not os.path.isfile(template):
                errors.append(f"{page.target(lang)}: Vorlage fehlt: {page.template(lang)}")
                continue
            try:
                result[page.target(lang)] = page.render(root, lang, read(template))
            except (SourceError, OSError, KeyError) as exc:
                errors.append(f"{page.target(lang)}: {exc}")
    return result, errors


def main(argv: list[str]) -> int:
    mode, root = None, None
    args = list(argv)
    while args:
        arg = args.pop(0)
        if arg in ("--write", "--check"):
            if mode:
                print("docs-reference: --write und --check schliessen sich aus", file=sys.stderr)
                return 2
            mode = arg
        elif arg == "--root":
            if not args:
                print("docs-reference: --root braucht ein Verzeichnis", file=sys.stderr)
                return 2
            root = args.pop(0)
        elif arg in ("-h", "--help"):
            print(__doc__)
            return 0
        else:
            print(f"docs-reference: unbekanntes Argument {arg!r}", file=sys.stderr)
            return 2
    if not mode:
        print("docs-reference: --write oder --check angeben", file=sys.stderr)
        return 2
    root = os.path.abspath(root or os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

    pages, errors = build(root)
    for error in errors:
        print(f"fehler: {error}", file=sys.stderr)
    if errors:
        return 2

    stale: list[str] = []
    for rel, content in sorted(pages.items()):
        path = os.path.join(root, rel)
        current = read(path) if os.path.isfile(path) else None
        if current == content:
            continue
        if mode == "--check":
            stale.append(rel)
            continue
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(content)
        print(f"geschrieben: {rel}")
    if mode == "--check":
        for rel in stale:
            print(f"veraltet: {rel}")
        if stale:
            print("docs-reference: erzeugte Seiten weichen von ihrer Quelle ab — "
                  "python3 scripts/docs-reference.py --write", file=sys.stderr)
            return 1
        print(f"Erzeugte Referenzen OK — {len(pages)} Seiten decken sich mit ihren Quellen.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
