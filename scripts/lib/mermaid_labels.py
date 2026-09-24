#!/usr/bin/env python3
"""Beschriftungen in Mermaid-Quelltext finden (TODO 47bd4d27).

scripts/docs-diagrams.py erzeugt die englischen Diagramm-Seiten aus den deutschen, ohne
den Aufbau eines Diagramms anzufassen: ersetzt wird nur, was dieses Modul als Beschriftung
meldet. Knoten-IDs, Pfeile, Stile, Klassen- und Tabellenruempfe bleiben Zeichen fuer
Zeichen gleich — deshalb kann das Skript nach dem Uebersetzen pruefen, dass das Geruest
(`skeleton`) beider Fassungen identisch ist.

Was als Beschriftung zaehlt
---------------------------
    flowchart/graph  Knotentexte ID["..."], ID[...], ID(...), ID{...}, ID[(...)] usw.,
                     Kantenlabels |...|, -- Text -->, -. Text .->, == Text ==>,
                     subgraph-Titel subgraph ID["..."]
    sequenceDiagram  participant/actor X as <Text>, Nachrichten A->>B: <Text>,
                     Note over/left of/right of X: <Text>,
                     alt/else/opt/loop/par/and/critical/option/break <Text>
    stateDiagram     Uebergaenge A --> B : <Text>, Beschreibungen X : <Text>,
                     state "<Text>" as X, note left of/right of X : <Text> (auch mehrzeilig)
    classDiagram     Beziehungslabels A --> B : <Text>, note "<Text>", class X["<Text>"];
                     der Klassenrumpf ist Code
    erDiagram        Beziehungslabels A ||--o{ B : "<Text>", Attribut-Kommentare "<Text>"

IDs werden nie uebersetzt, auch wenn Mermaid sie anzeigt (`PENDING --> RUNNING`,
`subgraph Compose`, `participant Client`): was in beiden Sprachen verschieden heissen soll,
braucht eine Beschriftung.

Unbekanntes schlaegt fehl, statt durchzurutschen: ein Diagrammtyp ohne Regeln oder eine
Sequenz-, Zustands- bzw. ER-Zeile, die keine Regel kennt, ergibt MermaidError. Sonst liefe
deutscher Text unbemerkt in die englische Seite.
"""
import re


class MermaidError(ValueError):
    """Diagrammtyp oder Zeile, fuer die es keine Beschriftungsregel gibt."""


# ---------------------------------------------------------------------------
# flowchart / graph
# ---------------------------------------------------------------------------
FLOW_SKIP = re.compile(r"^\s*(?:$|%%|end\s*$|(?:style|classDef|class|linkStyle|click|direction)\s)")
# Ein Durchlauf von links nach rechts; was ein Token verbraucht, wird nicht noch einmal
# betrachtet — so zaehlt `run(x)` in einem Label nicht als Knoten `run` mit Text `x`.
FLOW_TOKEN = re.compile(r"""
    "(?P<quoted>[^"]*)"
  | \|(?P<pipe>[^|"]*)\|
  | (?<![-<=.])--(?![-.>])\s*(?P<dash>[^-"|>\s][^"|>]*?)\s*(?:-->|---)
  | (?<![-<=.])-\.(?![-.])\s*(?P<dot>[^."|>\s][^."|>]*?)\s*\.->
  | (?<![-<=.])==(?![=>])\s*(?P<thick>[^="|>\s][^="|>]*?)\s*(?:==>|===)
  | (?<![\w])(?P<node>[A-Za-z0-9_]+)(?=[\[({>])
""", re.VERBOSE)
# Knotenformen, laengste Oeffnung zuerst.
SHAPES = (("(((", ")))"), ("[[", "]]"), ("[(", ")]"), ("([", "])"), ("((", "))"), ("{{", "}}"),
          ("[/", "/]"), ("[\\", "\\]"), ("[/", "\\]"), ("[\\", "/]"),
          ("[", "]"), ("(", ")"), ("{", "}"), (">", "]"))


def _flow_line(line):
    if FLOW_SKIP.match(line):
        return []
    spans, pos = [], 0
    while True:
        match = FLOW_TOKEN.search(line, pos)
        if not match:
            return spans
        if match.lastgroup != "node":
            spans.append(match.span(match.lastgroup))
            pos = match.end()
            continue
        pos = match.end()
        for opener, closer in SHAPES:
            if not line.startswith(opener, pos):
                continue
            start = pos + len(opener)
            if line.startswith('"', start):
                pos = start                          # der Text in Anfuehrungszeichen folgt als Token
            else:
                end = line.find(closer, start)
                if end < 0:
                    raise MermaidError(f"Knotenform ohne Abschluss: {line.strip()!r}")
                spans.append((start, end))
                pos = end + len(closer)
            break


# ---------------------------------------------------------------------------
# sequenceDiagram
# ---------------------------------------------------------------------------
SEQ_SKIP = re.compile(r"^\s*(?:$|%%|end\s*$|autonumber\b|(?:activate|deactivate|destroy|rect)\s)")
SEQ_RULES = (
    re.compile(r"^\s*(?:create\s+)?(?:participant|actor)\s+\S+?(?:\s+as\s+(?P<t>.+?))?\s*$"),
    re.compile(r"^\s*[Nn]ote\s+(?:over|left of|right of)\s+[^:]+:\s*(?P<t>.*?)\s*$"),
    re.compile(r"^\s*(?:alt|else|opt|loop|par|and|critical|option|break)(?:\s+(?P<t>.*?))?\s*$"),
    re.compile(r"^\s*[^\s:]+?\s*(?:<<-->>|<<->>|-->>|->>|--x|-x|--\)|-\)|-->|->)\s*[+-]?\s*[^\s:]+\s*:"
               r"\s*(?P<t>.*?)\s*$"),
)

# ---------------------------------------------------------------------------
# stateDiagram
# ---------------------------------------------------------------------------
STATE_SKIP = re.compile(r"^\s*(?:$|%%|\}\s*$|--\s*$|(?:direction|classDef|class)\s"
                        r"|state\s+\S+\s*(?:\{|<<\w+>>)\s*$)")
STATE_RULES = (
    re.compile(r"^\s*\S+\s*-->\s*\S+?(?:\s*:\s*(?P<t>.*?))?\s*$"),
    re.compile(r'^\s*state\s+"(?P<t>[^"]*)"\s+as\s+\S+\s*$'),
    re.compile(r"^\s*note\s+(?:left|right)\s+of\s+\S+\s*:\s*(?P<t>.*?)\s*$"),
    re.compile(r"^\s*[\w-]+\s*:\s*(?P<t>.*?)\s*$"),
)
STATE_NOTE_START = re.compile(r"^\s*note\s+(?:left|right)\s+of\s+\S+\s*$")
STATE_NOTE_END = re.compile(r"^\s*end\s+note\s*$")

# ---------------------------------------------------------------------------
# classDiagram — alles ausser Beziehungslabels und Notizen ist Code
# ---------------------------------------------------------------------------
CLASS_RULES = (
    re.compile(r'^\s*\S+\s*(?:"[^"]*"\s*)?(?:<\|--|--\|>|\*--|--\*|o--|--o|<--|-->|<\.\.|\.\.>'
               r'|<\|\.\.|\.\.\|>|--|\.\.)\s*(?:"[^"]*"\s*)?\S+\s*:\s*(?P<t>.+?)\s*$'),
    re.compile(r'^\s*note\s+(?:for\s+\S+\s+)?"(?P<t>[^"]*)"\s*$'),
    re.compile(r'^\s*class\s+[^\s\[]+\["(?P<t>[^"]*)"\]'),
)

# ---------------------------------------------------------------------------
# erDiagram
# ---------------------------------------------------------------------------
ER_SKIP = re.compile(r"^\s*(?:$|%%|[\w-]+\s*$)")
ER_RELATION = re.compile(r'^\s*[\w-]+\s+[|}o][|o](?:--|\.\.)[|o][|{o]\s+[\w-]+\s*:\s*'
                         r'(?:"(?P<q>[^"]*)"|(?P<t>\S.*?))\s*$')
ER_ENTITY = re.compile(r"^\s*[\w-]+\s*\{\s*$")
ER_ATTRIBUTE = re.compile(r'^\s*\S+\s+\S+(?:\s+(?:PK|FK|UK)(?:\s*,\s*(?:PK|FK|UK))*)?'
                          r'(?:\s+"(?P<t>[^"]*)")?\s*$')


def _first_rule(rules, line, kind):
    for rule in rules:
        match = rule.match(line)
        if match:
            return [match.span("t")] if match.group("t") else []
    raise MermaidError(f"{kind}-Zeile ohne Beschriftungsregel: {line.strip()!r}")


class _Scanner:
    """Zeilenweise, mit dem wenigen Zustand, den Rumpf-Bloecke und mehrzeilige Notizen brauchen."""

    def __init__(self, kind):
        self.kind = kind
        self.block = False          # in einem Klassen- oder Entitaetsrumpf / einer mehrzeiligen Notiz

    def line(self, line):
        handler = getattr(self, "_" + self.kind)
        return handler(line)

    def _flowchart(self, line):
        return _flow_line(line)

    def _sequence(self, line):
        if SEQ_SKIP.match(line):
            return []
        return _first_rule(SEQ_RULES, line, "Sequenz")

    def _state(self, line):
        if self.block:
            if STATE_NOTE_END.match(line):
                self.block = False
                return []
            stripped = line.strip()
            return [(line.index(stripped), line.index(stripped) + len(stripped))] if stripped else []
        if STATE_NOTE_START.match(line):
            self.block = True
            return []
        if STATE_SKIP.match(line):
            return []
        return _first_rule(STATE_RULES, line, "Zustands")

    def _class(self, line):
        if self.block:
            self.block = line.strip() != "}"
            return []
        if line.rstrip().endswith("{"):
            self.block = True
            return []
        for rule in CLASS_RULES:
            match = rule.match(line)
            if match:
                return [match.span("t")]
        return []

    def _er(self, line):
        if self.block:
            if line.strip() == "}":
                self.block = False
                return []
            if not line.strip() or line.strip().startswith("%%"):
                return []
            match = ER_ATTRIBUTE.match(line)
            if not match:
                raise MermaidError(f"ER-Attributzeile ohne Beschriftungsregel: {line.strip()!r}")
            return [match.span("t")] if match.group("t") else []
        if ER_ENTITY.match(line):
            self.block = True
            return []
        if ER_SKIP.match(line):
            return []
        match = ER_RELATION.match(line)
        if not match:
            raise MermaidError(f"ER-Zeile ohne Beschriftungsregel: {line.strip()!r}")
        group = "q" if match.group("q") is not None else "t"
        return [match.span(group)] if match.group(group) else []


TYPES = {"flowchart": "flowchart", "graph": "flowchart", "sequenceDiagram": "sequence",
         "stateDiagram": "state", "stateDiagram-v2": "state", "classDiagram": "class",
         "erDiagram": "er"}


def spans(code):
    """(Anfang, Ende) jeder Beschriftung in `code`, in Lesereihenfolge; leere fallen weg."""
    result, offset, scanner = [], 0, None
    for line in code.split("\n"):
        stripped = line.strip()
        if scanner is None:
            if stripped and not stripped.startswith("%%"):
                head = stripped.split()[0]
                if head not in TYPES:
                    raise MermaidError(f"Diagrammtyp {head!r} hat keine Beschriftungsregeln"
                                       " (scripts/lib/mermaid_labels.py)")
                scanner = _Scanner(TYPES[head])
        else:
            result.extend((offset + start, offset + end) for start, end in scanner.line(line)
                          if end > start)
        offset += len(line) + 1
    if scanner is None:
        raise MermaidError("leeres Diagramm")
    return result


def labels(code):
    return [code[start:end] for start, end in spans(code)]


def translate(code, fn):
    """Jede Beschriftung durch fn(beschriftung) ersetzen, alles andere unveraendert lassen."""
    out, pos = [], 0
    for start, end in spans(code):
        out.append(code[pos:start])
        out.append(fn(code[start:end]))
        pos = end
    out.append(code[pos:])
    return "".join(out)


def skeleton(code):
    """Der Quelltext ohne Beschriftungen — muss fuer Deutsch und Englisch identisch sein."""
    return translate(code, lambda _: "\x00")
