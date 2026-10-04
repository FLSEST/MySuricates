#!/usr/bin/env python3
"""README-Sync DE->EN nur fuer geaenderte Abschnitte (TODO c13a942b).

`README.de.md` ist die Quelle, `README.md` die Uebersetzung. Bis 2026-09-30 uebersetzte der
Workflow "README DE<->EN Sync" bei jeder Aenderung die ganze Datei neu und aenderte dabei
festgelegten Wortlaut ("suricates" -> "meerkats"). Dieses Skript macht alles Deterministische
an dem Workflow; die KI uebersetzt nur noch die Abschnitte, die sich geaendert haben.

Sync-Stand: die letzte Zeile von README.md, `<!-- synced-from: <commit> -->`. Sie nennt den
Commit, dessen README.de.md die englische Fassung wiedergibt.

Abschnitt: ein durch Leerzeilen begrenzter Block (Absatz, Ueberschrift, Tabelle, Liste,
Code-Block samt seiner Leerzeilen, Zitat). Beide READMEs fuehren dieselbe Folge von Bloecken;
Block i der englischen Datei ist die Uebersetzung von Block i der deutschen am Sync-Stand.
`plan` vergleicht README.de.md am Sync-Stand mit HEAD Block fuer Block (Zeilenumbrueche
zaehlen nicht) und legt nur fuer die geaenderten Bloecke einen Uebersetzungsauftrag an.

Feste Bausteine (Sprachumschalter, Banner, Fussnote) uebersetzt keine KI: Sie bleiben in
README.md wie sie sind. Aendert sich einer davon in der deutschen Datei, meldet `apply` das als
Warnung — von Hand nachziehen.

Gate: `apply` setzt die Uebersetzungen ein, schreibt Doku-Links deterministisch auf die
englische Fassung um und prueft danach, dass README.md ausserhalb der geaenderten Bloecke
Byte fuer Byte dem Stand vor dem Lauf entspricht.

Befehle (Zustand unter `$(git rev-parse --git-path readme-sync)`, liegt in .git, nie committet):
    plan                      Auftrag fuer die KI auf stdout, Zustand ablegen
    has-work                  Exit 0, wenn es etwas zu uebersetzen gibt (fuer run_if)
    apply --translations F    Antwort der KI einsetzen, Gate-Zeilen ausgeben (F = Datei oder -)
    check                     Gleichlauf und Sync-Stand der beiden READMEs pruefen
    mark [--commit C]         Sync-Stand setzen (nach einer Uebersetzung von Hand)

Exit: 0 = ok (auch YELLOW), 1 = Gate RED, 2 = Vorbedingung verletzt (nichts geschrieben).
Nur Standardbibliothek.
"""
from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "public-readiness"))
from md_common import FENCE, headings, mirror_pair, slug  # noqa: E402

SOURCE = "README.de.md"
TARGET = "README.md"
MARKER_RE = re.compile(r"^<!-- synced-from: ([0-9a-f]{7,40}) -->$")
MARKER_FMT = "<!-- synced-from: {} -->"
GATE_CATEGORY = "readme-sync"

#: Feste Bausteine: Name -> Erkennung am ersten Block-Inhalt. Sie werden nie uebersetzt.
FIXED = {
    "switcher": re.compile(r"^(\*\*Deutsch\*\*|\[Deutsch\]\(README\.de\.md\))\s*\|"),
    "banner": re.compile(r"readme-banner-"),
    "footer": re.compile(r"^>\s*🌍"),
}

#: Festgelegter Wortlaut des Autors, gilt fuer jede Uebersetzung.
GLOSSARY = {
    "Erdmännchen": "suricates (never \"meerkats\")",
    "Regelkreis": "control loop",
    "Arbeits-Repo": "working repository",
    "Befund / Befunde": "finding / findings",
    "Blaupausen": "blueprints",
}

LINK = re.compile(r"\[([^\]]*)\]\(([^)\s]+)\)")
TRANSLATION_HEAD = re.compile(r"^\s*=== EN (\d+) ===\s*$")
TRANSLATION_END = re.compile(r"^\s*=== END ===\s*$")


class PreconditionError(Exception):
    """Vorbedingung verletzt — es wird nichts geschrieben (Exit 2)."""


# --------------------------------------------------------------------------- Bloecke


@dataclass
class Chunk:
    body: str  # Inhaltszeilen samt Zeilenenden
    gap: str  # die Leerzeilen danach

    @property
    def raw(self) -> str:
        return self.body + self.gap


@dataclass
class Doc:
    prefix: str
    chunks: list[Chunk]

    def render(self) -> str:
        return self.prefix + "".join(c.raw for c in self.chunks)


def parse(text: str) -> Doc:
    """Zerlegt Markdown verlustfrei in Bloecke: `parse(t).render() == t` fuer jedes t.

    Leerzeilen in einem Code-Block gehoeren zum Block, sonst trennen sie Bloecke.
    """
    prefix = ""
    chunks: list[Chunk] = []
    fence = False
    for line in text.splitlines(keepends=True):
        if not fence and line.strip() == "":
            if chunks:
                chunks[-1].gap += line
            else:
                prefix += line
            continue
        if FENCE.match(line):
            fence = not fence
        if not chunks or chunks[-1].gap:
            chunks.append(Chunk(body="", gap=""))
        chunks[-1].body += line
    return Doc(prefix, chunks)


def norm(body: str) -> str:
    """Vergleichsschluessel: Zeilenumbrueche und Leerraum zaehlen nicht."""
    return " ".join(body.split())


def kind(body: str) -> str:
    first = body.lstrip("\n").split("\n", 1)[0].strip()
    if FENCE.match(first):
        return "code"
    m = re.match(r"^(#{1,6})\s", first)
    if m:
        return f"h{len(m.group(1))}"
    if first.startswith("|"):
        return "table"
    if first.startswith(">"):
        return "quote"
    if first.startswith("<!--"):
        return "comment"
    if first.startswith("<"):
        return "html"
    if re.match(r"^(-{3,}|\*{3,}|_{3,})$", first):
        return "hr"
    if re.match(r"^([-*+]|\d+[.)])\s", first):
        return "list"
    return "para"


def table_shape(body: str) -> list[int]:
    """Spaltenzahl je Tabellenzeile (escapte `\\|` zaehlen nicht)."""
    return [len(re.findall(r"(?<!\\)\|", line)) for line in body.strip("\n").split("\n")]


def fixed_name(body: str) -> str | None:
    first = body.lstrip("\n").split("\n", 1)[0].strip()
    for name, pattern in FIXED.items():
        if pattern.search(first):
            return name
    return None


def split_marker(doc: Doc) -> tuple[Doc, str | None]:
    """Trennt den Sync-Stand (letzter Block) ab; liefert (Rumpf, Commit oder None)."""
    if doc.chunks:
        m = MARKER_RE.match(doc.chunks[-1].body.strip())
        if m:
            return Doc(doc.prefix, doc.chunks[:-1]), m.group(1)
    return doc, None


def with_marker(body: Doc, commit: str) -> str:
    chunks = list(body.chunks)
    if chunks and not chunks[-1].gap:
        last = chunks[-1]
        body_text = last.body if last.body.endswith("\n") else last.body + "\n"
        chunks[-1] = Chunk(body_text, "\n")
    return Doc(body.prefix, chunks).render() + MARKER_FMT.format(commit) + "\n"


def check_parallel(de: Doc, en: Doc, what: str) -> None:
    if len(de.chunks) != len(en.chunks):
        raise PreconditionError(
            f"{TARGET} passt nicht Block fuer Block zu {what}: {len(en.chunks)} gegen "
            f"{len(de.chunks)} Bloecke. Erst angleichen (oder von Hand uebersetzen und `mark`)."
        )
    for i, (d, e) in enumerate(zip(de.chunks, en.chunks)):
        if kind(d.body) != kind(e.body):
            raise PreconditionError(
                f"{TARGET} Block {i + 1} ist {kind(e.body)}, in {what} {kind(d.body)}: "
                f"{first_line(e.body)!r} / {first_line(d.body)!r}"
            )


def first_line(body: str) -> str:
    return body.strip().split("\n", 1)[0][:60]


# --------------------------------------------------------------------------- Plan


def diff_regions(old_de: Doc, new_de: Doc) -> list[list]:
    def keys(doc: Doc) -> list[str]:
        out = []
        for c in doc.chunks:
            name = fixed_name(c.body)
            out.append("\0fixed:" + name if name else norm(c.body))
        return out

    matcher = difflib.SequenceMatcher(None, keys(old_de), keys(new_de), autojunk=False)
    return [list(op) for op in matcher.get_opcodes()]


def fixed_changes(old_de: Doc, new_de: Doc) -> list[str]:
    def fixed(doc: Doc) -> dict[str, str]:
        return {n: norm(c.body) for c in doc.chunks if (n := fixed_name(c.body))}

    old, new = fixed(old_de), fixed(new_de)
    return sorted(n for n in old.keys() & new.keys() if old[n] != new[n])


def build_task(regions: list[list], old_de: Doc, new_de: Doc, en: Doc) -> str:
    out = [
        "Translate the changed blocks of the German README.de.md into English for README.md.",
        "Only these blocks are translated; every other line of README.md stays as it is.",
        "",
        "Rules:",
        "1. Where a block has a previous English version, change ONLY what corresponds to the "
        "change between the old and the new German text. Keep every other word, punctuation "
        "mark and line break of the previous English version exactly as it is — that wording "
        "is deliberate.",
        "2. Keep the Markdown structure of each block: same block type, a table keeps its rows "
        "and columns, a list its items. No blank lines inside a block (except inside a code "
        "fence).",
        "3. Keep code, commands, identifiers, file paths, HTML comment syntax and every link "
        "target (the part in parentheses) exactly as in the German "
        "text — the tool rewrites documentation links to the English docs itself. Numbers use "
        "English formatting (2 630 -> 2,630) like the previous English version.",
        "4. Fixed terms: " + "; ".join(f"{k} -> {v}" for k, v in GLOSSARY.items()) + ".",
        "5. Do not create, edit or delete any file. Answer ONLY with the blocks below, each "
        "introduced by its marker line, and end with the line `=== END ===`:",
        "",
    ]
    wanted = [j for tag, _i1, _i2, j1, j2 in regions if tag != "equal" for j in range(j1, j2)]
    for j in wanted:
        out += [f"=== EN {j + 1} ===", "<English text of block {}>".format(j + 1)]
    out += ["=== END ===", ""]
    n = 0
    for tag, i1, i2, j1, j2 in regions:
        if tag == "equal":
            continue
        n += 1
        out.append(f"## Change {n} ({tag}, README.de.md blocks {j1 + 1}-{j2})" if j2 > j1
                   else f"## Change {n} (German blocks removed — nothing to translate)")
        if i2 > i1:
            out += ["", "Old German version:", "<<<", "".join(old_de.chunks[i].raw for i in range(i1, i2)).rstrip("\n"), ">>>"]
            out += ["", "Previous English version (keep its wording wherever the German text did not change):", "<<<",
                    "".join(en.chunks[i].raw for i in range(i1, i2)).rstrip("\n"), ">>>"]
        for j in range(j1, j2):
            out += ["", f"New German block {j + 1} (answer with `=== EN {j + 1} ===`):", "<<<",
                    new_de.chunks[j].body.rstrip("\n"), ">>>"]
        out.append("")
    return "\n".join(out)


# --------------------------------------------------------------------------- Git / Zustand


def git(root: Path, *args: str, check: bool = True) -> str:
    proc = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, encoding="utf-8")
    if check and proc.returncode != 0:
        raise PreconditionError(f"git {' '.join(args)}: {proc.stderr.strip() or proc.returncode}")
    return proc.stdout


def state_dir(root: Path, explicit: str | None) -> Path:
    if explicit:
        return Path(explicit)
    return (root / git(root, "rev-parse", "--git-path", "readme-sync").strip()).resolve()


def repo_root(explicit: str | None) -> Path:
    if explicit:
        return Path(explicit).resolve()
    out = subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True, text=True)
    if out.returncode != 0:
        raise PreconditionError("kein Git-Repository (oder --root angeben)")
    return Path(out.stdout.strip())


def source_commit(root: Path) -> str:
    sha = git(root, "log", "-1", "--format=%H", "--", SOURCE).strip()
    if not sha:
        raise PreconditionError(f"{SOURCE} ist in keinem Commit enthalten")
    return git(root, "rev-parse", "--short=12", sha).strip()


def cmd_plan(root: Path, state: Path) -> int:
    if git(root, "status", "--porcelain", "--", SOURCE, TARGET).strip():
        raise PreconditionError(
            f"{SOURCE} oder {TARGET} hat uncommittete Aenderungen — erst committen (der "
            f"Sync-Stand nennt einen Commit, und das Gate vergleicht mit dem committeten Stand)."
        )
    en_text = (root / TARGET).read_text(encoding="utf-8")
    en, old_commit = split_marker(parse(en_text))
    if not old_commit:
        raise PreconditionError(
            f"{TARGET} endet nicht mit `{MARKER_FMT.format('<commit>')}` — Sync-Stand unbekannt. "
            f"Mit `mark --commit <c>` setzen (c = Commit, dessen {SOURCE} {TARGET} wiedergibt)."
        )
    proc = subprocess.run(["git", "show", f"{old_commit}:{SOURCE}"], cwd=root, capture_output=True,
                          text=True, encoding="utf-8")
    if proc.returncode != 0:
        raise PreconditionError(
            f"Sync-Stand {old_commit} ist in diesem Repository unbekannt (etwa nach einem "
            f"History-Rewrite): {proc.stderr.strip()}. Von Hand angleichen und `mark`."
        )
    old_de = parse(proc.stdout)
    new_commit = source_commit(root)
    new_de_text = (root / SOURCE).read_text(encoding="utf-8")
    new_de = parse(new_de_text)
    check_parallel(old_de, en, f"{SOURCE}@{old_commit}")

    regions = diff_regions(old_de, new_de)
    for tag, _i1, _i2, j1, j2 in regions:
        for j in range(j1, j2):
            if tag != "equal" and fixed_name(new_de.chunks[j].body):
                raise PreconditionError(
                    f"Fester Baustein `{fixed_name(new_de.chunks[j].body)}` in {SOURCE} neu oder "
                    f"verschoben (Block {j + 1}) — von Hand in {TARGET} nachziehen und `mark`."
                )
    changed = [r for r in regions if r[0] != "equal"]

    shutil.rmtree(state / "run", ignore_errors=True)
    run = state / "run"
    run.mkdir(parents=True, exist_ok=True)
    (run / "snapshot-README.md").write_text(en_text, encoding="utf-8")
    (run / "source-README.de.md").write_text(new_de_text, encoding="utf-8")
    (run / "old-README.de.md").write_text(proc.stdout, encoding="utf-8")
    plan = {
        "old_commit": old_commit,
        "new_commit": new_commit,
        "regions": regions,
        "fixed_changed": fixed_changes(old_de, new_de),
    }
    (run / "plan.json").write_text(json.dumps(plan, indent=1), encoding="utf-8")

    blocks = sum(r[4] - r[3] for r in changed)
    if not changed:
        print(f"README-Sync: nichts zu uebersetzen — {SOURCE} unveraendert seit {old_commit}"
              f" (aktuell {new_commit}).")
        return 0
    task = build_task(regions, old_de, new_de, en)
    (run / "task.md").write_text(task, encoding="utf-8")
    print(f"README-Sync: {len(changed)} Aenderung(en), {blocks} Block/Bloecke zu uebersetzen "
          f"({SOURCE} {old_commit} -> {new_commit}).")
    print()
    print(task)
    return 0


def cmd_has_work(state: Path) -> int:
    return 0 if (state / "run" / "task.md").is_file() else 1


# --------------------------------------------------------------------------- Apply


def parse_translations(text: str) -> dict[int, str]:
    """`=== EN n ===`-Bloecke bis zur naechsten Marke oder `=== END ===`; Rest ignoriert."""
    found: dict[int, list[str]] = {}
    dupes: list[int] = []
    current: int | None = None
    for line in text.splitlines():
        head = TRANSLATION_HEAD.match(line)
        if head:
            current = int(head.group(1))
            if current in found:
                dupes.append(current)
            found[current] = []
            continue
        if TRANSLATION_END.match(line):
            current = None
            continue
        if current is not None:
            found[current].append(line)
    if dupes:
        raise ValueError(f"Block(e) doppelt beantwortet: {sorted(set(dupes))}")
    out = {}
    for n, lines in found.items():
        while lines and not lines[0].strip():
            lines.pop(0)
        while lines and not lines[-1].strip():
            lines.pop()
        if (len(lines) >= 2 and re.match(r"^\s*```(markdown|md)?\s*$", lines[0])
                and re.match(r"^\s*```\s*$", lines[-1])):
            lines = lines[1:-1]  # die ganze Antwort in einen Markdown-Fence gepackt
        out[n] = "\n".join(lines)
    return out


def validate(translations: dict[int, str], expected: list[int], new_de: Doc) -> list[str]:
    errors = []
    missing = [j + 1 for j in expected if j + 1 not in translations]
    extra = sorted(set(translations) - {j + 1 for j in expected})
    if missing:
        errors.append(f"keine Uebersetzung fuer Block {missing}")
    if extra:
        errors.append(f"Uebersetzung fuer nicht verlangte Bloecke {extra}")
    for j in expected:
        text = translations.get(j + 1)
        if text is None:
            continue
        de_body = new_de.chunks[j].body
        if not text.strip():
            errors.append(f"Block {j + 1}: leer")
            continue
        parsed = parse(text + "\n")
        if len(parsed.chunks) != 1:
            errors.append(f"Block {j + 1}: {len(parsed.chunks)} Bloecke statt einem (Leerzeile im Block?)")
            continue
        if kind(text) != kind(de_body):
            errors.append(f"Block {j + 1}: {kind(text)} statt {kind(de_body)}")
        elif kind(de_body) == "table" and table_shape(text) != table_shape(de_body):
            errors.append(f"Block {j + 1}: Tabellenform {table_shape(text)} statt {table_shape(de_body)}")
        if "=== EN" in text:
            errors.append(f"Block {j + 1}: enthaelt eine Antwortmarke")
    return errors


def rewrite_links(text: str, root: Path, warnings: list[str]) -> str:
    """Doku-Links auf die englische Fassung: `docs/x.md` -> `docs/en/x.md`, `VISION.md` ->
    `docs/en/vision.md` — nur wenn die Zieldatei existiert. Ein Anker wird ueber die Position
    der Ueberschrift uebertragen (docs_parity_check haelt die Ueberschriftenfolge gleich);
    laesst er sich nicht zuordnen, faellt er weg — oben auf der richtigen Seite ist besser
    als nirgends."""

    def english(path: str) -> str | None:
        if path == "VISION.md":
            cand = "docs/en/vision.md"
        elif path.endswith(".md") and path.startswith("docs/") and not path.startswith("docs/en/"):
            cand = mirror_pair(path)
        else:
            return None
        return cand if cand and (root / cand).is_file() else None

    def anchor(de_path: str, en_path: str, frag: str) -> str | None:
        de_heads = headings(root / de_path) if (root / de_path).is_file() else []
        en_heads = headings(root / en_path)
        en_slugs = [slug(h) for h in en_heads]
        idx = next((i for i, h in enumerate(de_heads) if slug(h) == frag.lower()), None)
        if (idx is not None and len(de_heads) == len(en_heads)
                and de_heads[idx].split(" ", 1)[0] == en_heads[idx].split(" ", 1)[0]):
            return en_slugs[idx]
        if frag.lower() in en_slugs:
            return frag.lower()
        return None

    def repl(m: re.Match) -> str:
        label, target = m.group(1), m.group(2)
        if target.startswith(("http://", "https://", "mailto:", "#")):
            return m.group(0)
        path, sep, frag = target.partition("#")
        en_path = english(path)
        if not en_path:
            return m.group(0)
        new_target = en_path
        if sep and frag:
            mapped = anchor(path, en_path, frag)
            if mapped:
                new_target += "#" + mapped
            else:
                warnings.append(f"Anker `#{frag}` hat in {en_path} keine Entsprechung — weggelassen")
        if label.strip("`") in (path, target):
            label = label.replace(target, new_target) if target in label else label.replace(path, en_path)
        return f"[{label}]({new_target})"

    return LINK.sub(repl, text)


def verify(snapshot: Doc, regions: list[list], new_de: Doc, final_text: str, commit: str) -> list[str]:
    """Gate: ausserhalb der geaenderten Bloecke kein Byte anders; Sync-Stand gesetzt."""
    final, marker = split_marker(parse(final_text))
    if marker != commit:
        return [f"Sync-Stand in {TARGET} ist {marker!r}, erwartet {commit!r}"]
    if final.prefix != snapshot.prefix:
        return [f"{TARGET}: Text vor dem ersten Block geaendert"]
    pos = 0
    for tag, i1, i2, j1, j2 in regions:
        if tag == "equal":
            for i in range(i1, i2):
                if pos >= len(final.chunks) or final.chunks[pos].raw != snapshot.chunks[i].raw:
                    got = first_line(final.chunks[pos].body) if pos < len(final.chunks) else "(Dateiende)"
                    return [f"{TARGET} Block {pos + 1} weicht ausserhalb der geaenderten Abschnitte ab "
                            f"(erwartet {first_line(snapshot.chunks[i].body)!r}, gefunden {got!r})"]
                pos += 1
        else:
            for j in range(j1, j2):
                if pos >= len(final.chunks) or kind(final.chunks[pos].body) != kind(new_de.chunks[j].body):
                    return [f"{TARGET} Block {pos + 1}: erwartet die Uebersetzung von {SOURCE} Block {j + 1}"]
                pos += 1
    if pos != len(final.chunks):
        return [f"{TARGET} hat {len(final.chunks) - pos} Block/Bloecke mehr als erwartet"]
    return []


def gate(status: str, description: str) -> None:
    print(f"gate_status: {status}")
    print(f"gate_category: {GATE_CATEGORY}")
    print(f"gate_description: {description}")


def cmd_apply(root: Path, state: Path, translations_arg: str) -> int:
    run = state / "run"
    if not (run / "plan.json").is_file():
        raise PreconditionError("kein Plan — erst `plan` ausfuehren")
    plan = json.loads((run / "plan.json").read_text(encoding="utf-8"))
    snapshot_text = (run / "snapshot-README.md").read_text(encoding="utf-8")
    source_text = (run / "source-README.de.md").read_text(encoding="utf-8")
    regions, new_commit = plan["regions"], plan["new_commit"]
    snapshot, _ = split_marker(parse(snapshot_text))
    new_de = parse(source_text)
    warnings = [f"Fester Baustein `{n}` in {SOURCE} geaendert — in {TARGET} von Hand nachziehen"
                for n in plan["fixed_changed"]]

    if (root / SOURCE).read_text(encoding="utf-8") != source_text:
        gate("RED", f"{SOURCE} wurde waehrend des Laufs geaendert — {TARGET} nicht geschrieben")
        return 1
    current = (root / TARGET).read_text(encoding="utf-8")
    if current != snapshot_text:
        warnings.append(f"{TARGET} wurde waehrend des Laufs veraendert — verworfen, aus dem Stand vor dem Lauf neu aufgebaut")

    expected = [j for tag, _i1, _i2, j1, j2 in regions if tag != "equal" for j in range(j1, j2)]
    changed = [r for r in regions if r[0] != "equal"]
    if not changed:
        if current != snapshot_text:
            (root / TARGET).write_text(snapshot_text, encoding="utf-8")
        status = "YELLOW" if warnings else "GREEN"
        for w in warnings:
            print(f"WARNUNG: {w}")
        gate(status, "; ".join(warnings) or f"{TARGET} aktuell (Sync-Stand {plan['old_commit']})")
        return 0

    raw = sys.stdin.read() if translations_arg == "-" else Path(translations_arg).read_text(encoding="utf-8")
    try:
        translations = parse_translations(raw)
    except ValueError as exc:
        translations, errors = {}, [str(exc)]
    else:
        errors = validate(translations, expected, new_de)
    if errors:
        if current != snapshot_text:
            (root / TARGET).write_text(snapshot_text, encoding="utf-8")
        for e in errors:
            print(f"FEHLER: {e}")
        gate("RED", f"Uebersetzung unbrauchbar ({errors[0]}) — {TARGET} unveraendert")
        return 1

    chunks: list[Chunk] = []
    for tag, i1, i2, j1, j2 in regions:
        if tag == "equal":
            chunks.extend(snapshot.chunks[i1:i2])
            continue
        for k, j in enumerate(range(j1, j2)):
            text = rewrite_links(translations[j + 1], root, warnings)
            gap = snapshot.chunks[i1 + k].gap if i1 + k < i2 else "\n"
            chunks.append(Chunk(text + "\n", gap or "\n"))
    final_text = with_marker(Doc(snapshot.prefix, chunks), new_commit)
    problems = verify(snapshot, regions, new_de, final_text, new_commit)
    if problems:
        if current != snapshot_text:
            (root / TARGET).write_text(snapshot_text, encoding="utf-8")
        gate("RED", problems[0])
        return 1
    (root / TARGET).write_text(final_text, encoding="utf-8")
    (run / "task.md").unlink(missing_ok=True)

    print(f"README-Sync: {len(expected)} Block/Bloecke uebersetzt und eingesetzt, Sync-Stand "
          f"{plan['old_commit']} -> {new_commit}:")
    for tag, i1, i2, j1, j2 in changed:
        print(f"- {tag}: {SOURCE} Block {j1 + 1}-{j2} (vorher {TARGET} Block {i1 + 1}-{i2})")
    for w in warnings:
        print(f"WARNUNG: {w}")
    status = "YELLOW" if warnings else "GREEN"
    gate(status, "; ".join(warnings) if warnings else
         f"{len(expected)} Block/Bloecke uebersetzt, ausserhalb kein Byte geaendert")
    return 0


# --------------------------------------------------------------------------- check / mark


def cmd_check(root: Path) -> int:
    en, commit = split_marker(parse((root / TARGET).read_text(encoding="utf-8")))
    de = parse((root / SOURCE).read_text(encoding="utf-8"))
    if not commit:
        print(f"{TARGET}: kein Sync-Stand (`{MARKER_FMT.format('<commit>')}` als letzte Zeile)")
        return 1
    try:
        check_parallel(de, en, SOURCE)
    except PreconditionError as exc:
        print(str(exc))
        return 1
    print(f"ok: {TARGET} und {SOURCE} laufen Block fuer Block gleich ({len(de.chunks)} Bloecke), "
          f"Sync-Stand {commit}")
    return 0


def cmd_mark(root: Path, commit: str | None) -> int:
    sha = git(root, "rev-parse", "--short=12", f"{commit}^{{commit}}").strip() if commit else source_commit(root)
    en, _ = split_marker(parse((root / TARGET).read_text(encoding="utf-8")))
    (root / TARGET).write_text(with_marker(en, sha), encoding="utf-8")
    print(f"{TARGET}: Sync-Stand {sha}")
    return 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--root", help="Repository-Wurzel (Vorgabe: git rev-parse --show-toplevel)")
    ap.add_argument("--state", help="Zustandsordner (Vorgabe: git rev-parse --git-path readme-sync)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("plan")
    sub.add_parser("has-work")
    p_apply = sub.add_parser("apply")
    p_apply.add_argument("--translations", required=True, help="Antwort der KI (Datei oder -)")
    sub.add_parser("check")
    p_mark = sub.add_parser("mark")
    p_mark.add_argument("--commit")
    args = ap.parse_args(argv)
    try:
        root = repo_root(args.root)
        if args.cmd == "check":
            return cmd_check(root)
        if args.cmd == "mark":
            return cmd_mark(root, args.commit)
        state = state_dir(root, args.state)
        if args.cmd == "plan":
            return cmd_plan(root, state)
        if args.cmd == "has-work":
            return cmd_has_work(state)
        return cmd_apply(root, state, args.translations)
    except PreconditionError as exc:
        print(f"README-Sync abgebrochen: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
