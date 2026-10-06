#!/usr/bin/env python3
"""Find labels in Mermaid source text (TODO 47bd4d27).

scripts/docs-diagrams.py produces the English diagram pages from the German ones without
touching the structure of a diagram: only what this module reports as a label is replaced.
Node IDs, arrows, styles, class and table bodies stay character for
character the same — so after translating, the script can check that the skeleton
(`skeleton`) of both versions is identical.

What counts as a label
----------------------
    flowchart/graph  node texts ID["..."], ID[...], ID(...), ID{...}, ID[(...)] etc.,
                     edge labels |...|, -- text -->, -. text .->, == text ==>,
                     subgraph titles subgraph ID["..."]
    sequenceDiagram  participant/actor X as <text>, messages A->>B: <text>,
                     Note over/left of/right of X: <text>,
                     alt/else/opt/loop/par/and/critical/option/break <text>
    stateDiagram     transitions A --> B : <text>, descriptions X : <text>,
                     state "<text>" as X, note left of/right of X : <text> (also multi-line)
    classDiagram     relation labels A --> B : <text>, note "<text>", class X["<text>"];
                     the class body is code
    erDiagram        relation labels A ||--o{ B : "<text>", attribute comments "<text>"

IDs are never translated, even if Mermaid displays them (`PENDING --> RUNNING`,
`subgraph Compose`, `participant Client`): what is supposed to be named differently in both languages
needs a label.

Unknown input fails instead of slipping through: a diagram type without rules or a
sequence, state or ER line that no rule knows results in MermaidError. Otherwise
German text would run unnoticed into the English page.
"""
import re


class MermaidError(ValueError):
    """Diagram type or line for which there is no label rule."""


# ---------------------------------------------------------------------------
# flowchart / graph
# ---------------------------------------------------------------------------
FLOW_SKIP = re.compile(r"^\s*(?:$|%%|end\s*$|(?:style|classDef|class|linkStyle|click|direction)\s)")
# One pass from left to right; what a token consumes is not considered again —
# so `run(x)` in a label does not count as node `run` with text `x`.
FLOW_TOKEN = re.compile(r"""
    "(?P<quoted>[^"]*)"
  | \|(?P<pipe>[^|"]*)\|
  | (?<![-<=.])--(?![-.>])\s*(?P<dash>[^-"|>\s][^"|>]*?)\s*(?:-->|---)
  | (?<![-<=.])-\.(?![-.])\s*(?P<dot>[^."|>\s][^."|>]*?)\s*\.->
  | (?<![-<=.])==(?![=>])\s*(?P<thick>[^="|>\s][^="|>]*?)\s*(?:==>|===)
  | (?<![\w])(?P<node>[A-Za-z0-9_]+)(?=[\[({>])
""", re.VERBOSE)
# Node shapes, longest opening first.
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
                pos = start                          # the text in quotes follows as a token
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
# classDiagram — everything except relation labels and notes is code
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
    """Line by line, with the little state that body blocks and multi-line notes need."""

    def __init__(self, kind):
        self.kind = kind
        self.block = False          # inside a class or entity body / a multi-line note

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
    """(start, end) of every label in `code`, in reading order; empty ones are dropped."""
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
    """Replace every label by fn(label), leave everything else unchanged."""
    out, pos = [], 0
    for start, end in spans(code):
        out.append(code[pos:start])
        out.append(fn(code[start:end]))
        pos = end
    out.append(code[pos:])
    return "".join(out)


def skeleton(code):
    """The source text without labels — must be identical for German and English."""
    return translate(code, lambda _: "\x00")
