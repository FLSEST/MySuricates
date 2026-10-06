#!/usr/bin/env python3
"""README sync DE->EN for changed sections only (TODO c13a942b).

`README.de.md` is the source, `README.md` the translation. Until 2026-09-30 the workflow
"README DE<->EN Sync" translated the whole file anew on every change and thereby altered
fixed wording ("suricates" -> "meerkats"). This script does everything deterministic
about that workflow; the AI only translates the sections that have changed.

Sync state: the last line of README.md, `<!-- synced-from: <commit> sha256:<hash> -->`.
It names the commit whose README.de.md the English version reproduces, and the
SHA-256 of that content. The commit no longer exists after a history rewrite or in the public
repo (release handover: one commit per release) — then `plan` looks for the state of
README.de.md with this hash in the history. If none is found: align by hand, `mark`.

Section: a block delimited by blank lines (paragraph, heading, table, list,
code block including its blank lines, quote). Both READMEs carry the same sequence of blocks;
block i of the English file is the translation of block i of the German one at the sync state.
`plan` compares README.de.md at the sync state with HEAD block by block (line breaks
do not count, in code blocks every character counts):
- unchanged and merely moved blocks: the English version stays byte for byte,
- deleted blocks: dropped,
- new or changed blocks: go to the AI, with the previous English version of the
  most similar dropped block as a template.
If there is nothing to translate (only deleting/moving), no job is created; `apply`
writes the result without AI and advances the sync state.

Fixed building blocks (language switcher, banner, footnote) are not translated by any AI: they stay in
README.md as they are. If one of them changes in the German file, `apply` reports that as a
warning — follow up by hand.

Gate: `apply` inserts the translations, deterministically rewrites docs links to the
English version and then checks that every untranslated block matches, byte for byte, the
state before the run.

Commands (state under `$(git rev-parse --git-path readme-sync)`, lives in .git, never committed):
    plan                      job for the AI on stdout, store state
    has-work                  exit 0 if there is something to translate (for run_if)
    apply --translations F    insert the AI's answer, print gate lines (F = file or -)
    check [--current]         check parity with the sync state; --current: and the sync state
                              is the current README.de.md (gate of the release handover)
    mark [--commit C]         set the sync state (after a translation by hand)

Exit: 0 = ok (also YELLOW), 1 = gate RED / check failed, 2 = precondition violated
(nothing written). Standard library only.
"""
from __future__ import annotations

import argparse
import difflib
import hashlib
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
MARKER_RE = re.compile(r"^<!-- synced-from: ([0-9a-f]{7,40})(?: sha256:([0-9a-f]{64}))? -->$")
GATE_CATEGORY = "readme-sync"
#: Minimum similarity from which a dropped block counts as a template for a new one.
TEMPLATE_MIN_RATIO = 0.5

#: Fixed building blocks: name -> detection by the first block content. They are never translated.
FIXED = {
    "switcher": re.compile(r"^(\*\*Deutsch\*\*|\[Deutsch\]\(README\.de\.md\))\s*\|"),
    "banner": re.compile(r"readme-banner-"),
    "footer": re.compile(r"^>\s*🌍"),
}

#: Fixed wording of the author, valid for every translation.
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
MARK_HINT = "README.md von Hand angleichen und `readme_sync.py mark` ausfuehren"


class PreconditionError(Exception):
    """Precondition violated — nothing is written (exit 2)."""


# --------------------------------------------------------------------------- Blocks


@dataclass
class Chunk:
    body: str  # content lines including line endings
    gap: str  # the blank lines after it

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
    """Losslessly splits Markdown into blocks: `parse(t).render() == t` for every t.

    Blank lines inside a code block belong to the block, otherwise they separate blocks.
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
    """Comparison key: line breaks and whitespace do not count."""
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


def key(body: str) -> str:
    """Comparison key of a block: fixed building blocks by name, code exactly, otherwise norm()."""
    name = fixed_name(body)
    if name:
        return "\0fixed:" + name
    if kind(body) == "code":
        return "\0code:" + body  # indentation is content in code
    return norm(body)


def table_shape(body: str) -> list[int]:
    """Column count per table row (escaped `\\|` do not count)."""
    return [len(re.findall(r"(?<!\\)\|", line)) for line in body.strip("\n").split("\n")]


def fixed_name(body: str) -> str | None:
    first = body.lstrip("\n").split("\n", 1)[0].strip()
    for name, pattern in FIXED.items():
        if pattern.search(first):
            return name
    return None


def first_line(body: str) -> str:
    return body.strip().split("\n", 1)[0][:60]


def sha256(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


# --------------------------------------------------------------------------- Sync state


@dataclass
class Marker:
    commit: str
    hash: str | None

    def line(self) -> str:
        return f"<!-- synced-from: {self.commit}" + (f" sha256:{self.hash}" if self.hash else "") + " -->"


def split_marker(doc: Doc) -> tuple[Doc, Marker | None]:
    """Splits off the sync state (last block); returns (body, marker or None)."""
    if doc.chunks:
        m = MARKER_RE.match(doc.chunks[-1].body.strip())
        if m:
            return Doc(doc.prefix, doc.chunks[:-1]), Marker(m.group(1), m.group(2))
    return doc, None


def with_marker(body: Doc, marker: Marker) -> str:
    chunks = list(body.chunks)
    if chunks and not chunks[-1].gap:
        last = chunks[-1]
        body_text = last.body if last.body.endswith("\n") else last.body + "\n"
        chunks[-1] = Chunk(body_text, "\n")
    return Doc(body.prefix, chunks).render() + marker.line() + "\n"


def check_parallel(de: Doc, en: Doc, what: str) -> None:
    if len(de.chunks) != len(en.chunks):
        raise PreconditionError(
            f"{TARGET} passt nicht Block fuer Block zu {what}: {len(en.chunks)} gegen "
            f"{len(de.chunks)} Bloecke. {MARK_HINT}."
        )
    for i, (d, e) in enumerate(zip(de.chunks, en.chunks)):
        if kind(d.body) != kind(e.body):
            raise PreconditionError(
                f"{TARGET} Block {i + 1} ist {kind(e.body)}, in {what} {kind(d.body)}: "
                f"{first_line(e.body)!r} / {first_line(d.body)!r}. {MARK_HINT}."
            )


# --------------------------------------------------------------------------- Git


def git(root: Path, *args: str, check: bool = True) -> str:
    proc = subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, encoding="utf-8")
    if check and proc.returncode != 0:
        raise PreconditionError(f"git {' '.join(args)}: {proc.stderr.strip() or proc.returncode}")
    return proc.stdout


def show_source(root: Path, commit: str) -> str | None:
    proc = subprocess.run(["git", "show", f"{commit}:{SOURCE}"], cwd=root, capture_output=True,
                          text=True, encoding="utf-8")
    return proc.stdout if proc.returncode == 0 else None


def short(root: Path, commit: str) -> str:
    return git(root, "rev-parse", "--short=12", commit).strip()


def resolve_synced_source(root: Path, marker: Marker) -> tuple[str, str]:
    """README.de.md at the sync state as (commit, text).

    First via the commit; if it does not exist (history rewrite, release handover), via the
    content hash in the history of README.de.md. If commit and hash contradict each other or
    nothing matches, the sync state is not reliable — exit 2.
    """
    text = show_source(root, marker.commit)
    if text is not None:
        if marker.hash and sha256(text) != marker.hash:
            raise PreconditionError(
                f"Sync-Stand widerspruechlich: {SOURCE} in {marker.commit} hat nicht den Hash aus "
                f"dem Marker. {MARK_HINT}."
            )
        return marker.commit, text
    if marker.hash:
        for commit in git(root, "log", "--format=%H", "--", SOURCE, check=False).split():
            candidate = show_source(root, commit)
            if candidate is not None and sha256(candidate) == marker.hash:
                return short(root, commit), candidate
    raise PreconditionError(
        f"Sync-Stand {marker.commit} ist in diesem Repository unbekannt"
        + (" und kein Stand von README.de.md hat den Hash aus dem Marker" if marker.hash else
           " und der Marker traegt keinen Inhalts-Hash")
        + f". {MARK_HINT}."
    )


def source_commit(root: Path) -> str:
    sha = git(root, "log", "-1", "--format=%H", "--", SOURCE).strip()
    if not sha:
        raise PreconditionError(f"{SOURCE} ist in keinem Commit enthalten")
    return short(root, sha)


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


# --------------------------------------------------------------------------- Plan


def build_items(old_de: Doc, new_de: Doc) -> list[dict]:
    """Per block of the new German version: {"keep": i} (English block i stays byte for
    byte) or {"translate": True, "template": i | None} (template: previous version of i)."""
    old_keys = [key(c.body) for c in old_de.chunks]
    new_keys = [key(c.body) for c in new_de.chunks]
    items: list[dict | None] = [None] * len(new_keys)
    unmatched_old: list[int] = []
    region_of: dict[int, list[int]] = {}
    matcher = difflib.SequenceMatcher(None, old_keys, new_keys, autojunk=False)
    for tag, i1, i2, j1, j2 in matcher.get_opcodes():
        if tag == "equal":
            for k in range(i2 - i1):
                items[j1 + k] = {"keep": i1 + k}
            continue
        unmatched_old.extend(range(i1, i2))
        for j in range(j1, j2):
            region_of[j] = list(range(i1, i2))
    # Move: deleted and re-inserted elsewhere under the same key
    for j in sorted(region_of):
        match = next((i for i in unmatched_old if old_keys[i] == new_keys[j]), None)
        if match is not None:
            items[j] = {"keep": match}
            unmatched_old.remove(match)
    for j in sorted(region_of):
        if items[j] is not None:
            continue
        if fixed_name(new_de.chunks[j].body):
            raise PreconditionError(
                f"Fester Baustein `{fixed_name(new_de.chunks[j].body)}` in {SOURCE} neu (Block {j + 1}) "
                f"— {MARK_HINT}."
            )
        items[j] = {"translate": True, "template": best_template(j, region_of[j], unmatched_old, old_de, new_de)}
    return items  # type: ignore[return-value]


def best_template(j: int, region: list[int], unmatched_old: list[int], old_de: Doc, new_de: Doc) -> int | None:
    """Most similar dropped block: in its own replacement region without threshold, otherwise from
    TEMPLATE_MIN_RATIO (a block that was moved and changed at the same time)."""
    target = norm(new_de.chunks[j].body)

    def ratio(i: int) -> float:
        return difflib.SequenceMatcher(None, norm(old_de.chunks[i].body), target, autojunk=False).ratio()

    same_kind = [i for i in unmatched_old if kind(old_de.chunks[i].body) == kind(new_de.chunks[j].body)]
    local = [i for i in same_kind if i in region]
    if local:
        return max(local, key=ratio)
    scored = [(ratio(i), i) for i in same_kind]
    best = max(scored, default=None)
    return best[1] if best and best[0] >= TEMPLATE_MIN_RATIO else None


def fixed_changes(old_de: Doc, new_de: Doc) -> list[str]:
    def fixed(doc: Doc) -> dict[str, str]:
        return {n: norm(c.body) for c in doc.chunks if (n := fixed_name(c.body))}

    old, new = fixed(old_de), fixed(new_de)
    return sorted(n for n in old.keys() & new.keys() if old[n] != new[n])


def build_task(items: list[dict], old_de: Doc, new_de: Doc, en: Doc) -> str:
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
        "target (the part in parentheses) exactly as in the German text — the tool rewrites "
        "documentation links to the English docs itself. Numbers use English formatting "
        "(2 630 -> 2,630) like the previous English version.",
        "4. Fixed terms: " + "; ".join(f"{k} -> {v}" for k, v in GLOSSARY.items()) + ".",
        "5. Do not create, edit or delete any file. Answer ONLY with the blocks below, each "
        "introduced by its marker line, and end with the line `=== END ===`:",
        "",
    ]
    wanted = [j for j, item in enumerate(items) if item.get("translate")]
    for j in wanted:
        out += [f"=== EN {j + 1} ===", f"<English text of block {j + 1}>"]
    out += ["=== END ===", ""]
    for j in wanted:
        out += [f"## Block {j + 1}", "", "New German text:", "<<<", new_de.chunks[j].body.rstrip("\n"), ">>>"]
        i = items[j]["template"]
        if i is not None:
            out += ["", "Old German text:", "<<<", old_de.chunks[i].body.rstrip("\n"), ">>>",
                    "", "Previous English text (keep its wording wherever the German text did not change):",
                    "<<<", en.chunks[i].body.rstrip("\n"), ">>>"]
        else:
            out += ["", "(new block — no previous English version)"]
        out.append("")
    return "\n".join(out)


def cmd_plan(root: Path, state: Path) -> int:
    if git(root, "status", "--porcelain", "--", SOURCE, TARGET).strip():
        raise PreconditionError(
            f"{SOURCE} oder {TARGET} hat uncommittete Aenderungen — erst committen (der "
            f"Sync-Stand nennt einen Commit, und das Gate vergleicht mit dem committeten Stand)."
        )
    en_text = (root / TARGET).read_text(encoding="utf-8")
    en, marker = split_marker(parse(en_text))
    if not marker:
        raise PreconditionError(f"{TARGET} endet nicht mit `<!-- synced-from: … -->` — Sync-Stand unbekannt. {MARK_HINT}.")
    old_commit, old_text = resolve_synced_source(root, marker)
    old_de = parse(old_text)
    new_de_text = (root / SOURCE).read_text(encoding="utf-8")
    new_de = parse(new_de_text)
    check_parallel(old_de, en, f"{SOURCE}@{old_commit}")
    items = build_items(old_de, new_de)

    shutil.rmtree(state / "run", ignore_errors=True)
    run = state / "run"
    run.mkdir(parents=True, exist_ok=True)
    (run / "snapshot-README.md").write_text(en_text, encoding="utf-8")
    (run / "source-README.de.md").write_text(new_de_text, encoding="utf-8")
    new_marker = Marker(source_commit(root), sha256(new_de_text))
    plan = {
        "old_commit": old_commit,
        "new_marker": {"commit": new_marker.commit, "hash": new_marker.hash},
        "items": items,
        "fixed_changed": fixed_changes(old_de, new_de),
    }
    (run / "plan.json").write_text(json.dumps(plan, indent=1), encoding="utf-8")

    kept = sum(1 for it in items if "keep" in it)
    todo = sum(1 for it in items if it.get("translate"))
    summary = (f"README-Sync ({SOURCE} {old_commit} -> {new_marker.commit}): {todo} Block/Bloecke zu "
               f"uebersetzen, {kept} unveraendert uebernommen, {len(en.chunks) - kept} ersetzt oder geloescht.")
    if not todo:
        print(summary + " Kein KI-Auftrag; apply schreibt ohne KI.")
        return 0
    task = build_task(items, old_de, new_de, en)
    (run / "task.md").write_text(task, encoding="utf-8")
    print(summary)
    print()
    print(task)
    return 0


def cmd_has_work(state: Path) -> int:
    return 0 if (state / "run" / "task.md").is_file() else 1


# --------------------------------------------------------------------------- Apply


def parse_translations(text: str) -> dict[int, str]:
    """`=== EN n ===` blocks up to the next marker or `=== END ===`; rest ignored."""
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
            lines = lines[1:-1]  # the whole answer wrapped in a Markdown fence
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
    """Docs links to the English version: `docs/x.md` -> `docs/en/x.md`, `VISION.md` ->
    `docs/en/vision.md` — only if the target file exists. An anchor is carried over via the position
    of the heading (docs_parity_check keeps the heading sequence equal);
    if it cannot be mapped, it is dropped — the top of the right page is better
    than nowhere."""

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


def verify(snapshot: Doc, items: list[dict], new_de: Doc, final_text: str, marker: Marker) -> list[str]:
    """Gate: every untranslated block byte for byte as before; sync state set."""
    final, found = split_marker(parse(final_text))
    if found != marker:
        return [f"Sync-Stand in {TARGET} ist {found.line() if found else None!r}, erwartet {marker.line()!r}"]
    if final.prefix != snapshot.prefix:
        return [f"{TARGET}: Text vor dem ersten Block geaendert"]
    if len(final.chunks) != len(items):
        return [f"{TARGET} hat {len(final.chunks)} Bloecke, erwartet {len(items)}"]
    for k, item in enumerate(items):
        got = final.chunks[k]
        if "keep" in item:
            want = snapshot.chunks[item["keep"]]
            if got.raw != want.raw:
                return [f"{TARGET} Block {k + 1} weicht ausserhalb der geaenderten Abschnitte ab "
                        f"(erwartet {first_line(want.body)!r}, gefunden {first_line(got.body)!r})"]
        elif kind(got.body) != kind(new_de.chunks[k].body):
            return [f"{TARGET} Block {k + 1}: erwartet die Uebersetzung von {SOURCE} Block {k + 1}"]
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
    items = plan["items"]
    new_marker = Marker(plan["new_marker"]["commit"], plan["new_marker"]["hash"])
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

    def restore() -> None:
        if current != snapshot_text:
            (root / TARGET).write_text(snapshot_text, encoding="utf-8")

    expected = [j for j, item in enumerate(items) if item.get("translate")]
    translations: dict[int, str] = {}
    if expected:
        raw = sys.stdin.read() if translations_arg == "-" else Path(translations_arg).read_text(encoding="utf-8")
        try:
            translations = parse_translations(raw)
        except ValueError as exc:
            errors = [str(exc)]
        else:
            errors = validate(translations, expected, new_de)
        if errors:
            restore()
            for e in errors:
                print(f"FEHLER: {e}")
            gate("RED", f"Uebersetzung unbrauchbar ({errors[0]}) — {TARGET} unveraendert")
            return 1

    chunks: list[Chunk] = []
    for j, item in enumerate(items):
        if "keep" in item:
            chunks.append(snapshot.chunks[item["keep"]])
        else:
            text = rewrite_links(translations[j + 1], root, warnings)
            template = item.get("template")
            gap = snapshot.chunks[template].gap if template is not None else "\n"
            chunks.append(Chunk(text + "\n", gap or "\n"))
    final_text = with_marker(Doc(snapshot.prefix, chunks), new_marker)
    problems = verify(snapshot, items, new_de, final_text, new_marker)
    if problems:
        restore()
        gate("RED", problems[0])
        return 1
    if final_text != current:
        (root / TARGET).write_text(final_text, encoding="utf-8")
    (run / "task.md").unlink(missing_ok=True)

    kept = sum(1 for it in items if "keep" in it)
    print(f"README-Sync: {len(expected)} Block/Bloecke uebersetzt, {kept} unveraendert uebernommen, "
          f"{len(snapshot.chunks) - kept} ersetzt oder geloescht; Sync-Stand {plan['old_commit']} -> {new_marker.commit}")
    for j in expected:
        print(f"- uebersetzt: {SOURCE} Block {j + 1}")
    for w in warnings:
        print(f"WARNUNG: {w}")
    gate("YELLOW" if warnings else "GREEN", "; ".join(warnings) if warnings else
         f"{len(expected)} Block/Bloecke uebersetzt, jeder andere Block Byte fuer Byte wie vorher")
    return 0


# --------------------------------------------------------------------------- check / mark


def cmd_check(root: Path, current: bool) -> int:
    en, marker = split_marker(parse((root / TARGET).read_text(encoding="utf-8")))
    if not marker:
        print(f"{TARGET}: kein Sync-Stand (`<!-- synced-from: … -->` als letzte Zeile). {MARK_HINT}.")
        return 1
    source_text = (root / SOURCE).read_text(encoding="utf-8")
    try:
        commit, synced_text = resolve_synced_source(root, marker)
        check_parallel(parse(synced_text), en, f"{SOURCE}@{commit}")
    except PreconditionError as exc:
        print(str(exc))
        return 1
    if current and sha256(synced_text) != sha256(source_text):
        print(f"{TARGET} gibt {SOURCE}@{commit} wieder, {SOURCE} hat sich seitdem geaendert — "
              f"erst den Workflow \"README DE<->EN Sync\" laufen lassen (oder von Hand angleichen und `mark`).")
        return 1
    print(f"ok: {TARGET} laeuft Block fuer Block mit {SOURCE}@{commit} gleich ({len(en.chunks)} Bloecke)"
          + ("" if sha256(synced_text) == sha256(source_text) else f"; {SOURCE} seitdem geaendert"))
    return 0


def cmd_mark(root: Path, commit: str | None) -> int:
    sha = short(root, f"{commit}^{{commit}}") if commit else source_commit(root)
    text = show_source(root, sha)
    if text is None:
        raise PreconditionError(f"{SOURCE} fehlt in {sha}")
    en, _ = split_marker(parse((root / TARGET).read_text(encoding="utf-8")))
    marker = Marker(sha, sha256(text))
    (root / TARGET).write_text(with_marker(en, marker), encoding="utf-8")
    print(f"{TARGET}: {marker.line()}")
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
    p_check = sub.add_parser("check")
    p_check.add_argument("--current", action="store_true",
                         help="zusaetzlich: der Sync-Stand ist die aktuelle README.de.md")
    p_mark = sub.add_parser("mark")
    p_mark.add_argument("--commit")
    args = ap.parse_args(argv)
    try:
        root = repo_root(args.root)
        if args.cmd == "check":
            return cmd_check(root, args.current)
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
