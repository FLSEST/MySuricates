"""Gemeinsame Helfer der TODO-Batch-Skripte (TODO cc0eb3ba).

Die Skripte laufen im isolierten Klon eines Laufs. Sie reden über Dateien im Zustandsordner
`.git/todo-batch/` (liegt in `.git`, wird nie committet) und über ihre Ausgabe miteinander.
Nur Standardbibliothek.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import unicodedata
from datetime import datetime, timedelta, timezone
from pathlib import Path

SELECTED = "batch:selected"
STARTED = "batch:started"
HANDED_OVER = "batch:handed-over"
FAILED = "batch:failed"
REJECTED = "batch:rejected"
BATCH_PREFIX = "batch:"
ISOLATED_MARKER = "isolated.ok"
STALE_HOURS = 30

_TRAILER = re.compile(r"^Todo:[ \t]*([0-9a-fA-F-]{36})[ \t]*$", re.MULTILINE)
_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
_ITEM = re.compile(r"^\[([0-9a-fA-F-]{36})\]: ?(.*)$")
_MARKER_WORDS = re.compile(r"(?i)(dashboard|gate|todo)[_-](widget|status|category|description|item)")


class BatchError(Exception):
    """Ein Zustand, in dem der Schritt nicht weiterarbeiten darf; die Meldung ist für Menschen."""


def parse_details(text: str) -> list[dict]:
    """Die JSON-Liste aus dem `<todo_details>`-Block; eine Lücke (`NICHT GEMESSEN …`) ist ein Fehler."""
    for line in (text or "").splitlines():
        stripped = line.strip()
        if stripped.startswith("["):
            data = json.loads(stripped)
            if not isinstance(data, list):
                raise BatchError("todo_details: keine Liste")
            return data
    raise BatchError("todo_details ohne Liste: " + " ".join((text or "").split())[:160])


def is_uuid(value) -> bool:
    return isinstance(value, str) and bool(_UUID.match(value))


def trailers(message: str) -> list[str]:
    """Alle `Todo: <uuid>`-Trailer einer Commit-Nachricht, klein geschrieben."""
    return [found.lower() for found in _TRAILER.findall(message or "")]


def git(*args: str, cwd: str | Path = ".") -> str:
    result = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True)
    if result.returncode != 0:
        raise BatchError(f"git {' '.join(args)}: {result.stderr.strip()[:300]}")
    return result.stdout


def git_ok(*args: str, cwd: str | Path = ".") -> bool:
    return subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True).returncode == 0


def update(todo_id: str, *, status: str | None = None, add=(), remove=(), note: str | None = None,
           require_tags=(), require_status: str | None = None) -> dict:
    """Ein Eintrag für `todo-update` mit `updates` (Vertrag: docs/WORKFLOW-GUIDE.md)."""
    entry: dict = {"id": todo_id}
    if status:
        entry["status"] = status
    if add:
        entry["add_tags"] = list(add)
    if remove:
        entry["remove_tags"] = list(remove)
    if note:
        entry["note"] = note
    if require_tags:
        entry["require_tags"] = list(require_tags)
    if require_status:
        entry["require_status"] = require_status
    return entry


def emit_updates(entries: list[dict]) -> None:
    """Letzte Zeile der Ausgabe: die Liste für den folgenden `todo-update`-Block."""
    print(json.dumps(entries, ensure_ascii=False, separators=(",", ":")))


def state(path: str | Path) -> Path:
    directory = Path(path)
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def read_json(path: Path, default):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


def write_json(path: Path, data) -> None:
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def short(todo_id: str) -> str:
    return (todo_id or "")[:8]


def item_segments(foreach_output: str) -> dict[str, str]:
    """Zerlegt die Sammelausgabe eines foreach (`[<item>]: <ausgabe>` je Element) nach Element-ID."""
    segments: dict[str, list[str]] = {}
    current = None
    for line in (foreach_output or "").splitlines():
        match = _ITEM.match(line)
        if match:
            current = match.group(1).lower()
            segments.setdefault(current, []).append(match.group(2))
        elif current is not None:
            segments[current].append(line)
    return {key: "\n".join(lines) for key, lines in segments.items()}


def last_marker(text: str, marker: str) -> str | None:
    """Der Rest der letzten Zeile, die (nach Leerraum) mit `marker` beginnt, sonst None."""
    found = None
    for line in (text or "").splitlines():
        stripped = line.strip()
        if stripped.startswith(marker):
            found = stripped[len(marker):].strip()
    return found


def plain(text, limit: int = 200) -> str:
    """Fremder Text für Bericht und Kachel: eine Zeile, gekürzt, ohne Wörter, die ein Marker-Parser zählen würde.

    Unsichtbare Formatzeichen (Unicode Cf: Zero-Width, Soft Hyphen, BOM …) fallen vorher weg – sonst trüge
    `gate\u200b_status` ein Marker-Wort an der Entschärfung vorbei."""
    visible = "".join(ch for ch in str(text or "") if unicodedata.category(ch) != "Cf")
    flat = " ".join(visible.split())
    flat = _MARKER_WORDS.sub(lambda m: f"{m.group(1)} {m.group(2)}", flat)
    return flat if len(flat) <= limit else flat[: limit - 1] + "…"


# Review-Urteile, mit denen ein Review stattgefunden hat; alles andere (z. B. `not_reviewed`) ist keins
VERDICTS = ("ok", "findings")


def reviewed(review: dict | None) -> bool:
    return bool(review) and str(review.get("verdict", "")).strip().lower() in VERDICTS


def merge_preview(target: str) -> dict:
    """Merge-Vorschau des Klon-HEAD gegen `origin/<target>`: sauber, Konflikt (mit Dateien) oder unbekannt.

    `git merge-tree --write-tree` endet mit 0 (sauber) oder 1 (Konflikt); jeder andere Exit-Code ist ein Fehler
    des Befehls und sagt über den Merge nichts."""
    if not git_ok("fetch", "-q", "origin", f"+refs/heads/{target}:refs/remotes/origin/{target}"):
        return {"state": "unbekannt", "files": []}
    result = subprocess.run(["git", "merge-tree", "--write-tree", "--name-only", f"origin/{target}", "HEAD"],
                            capture_output=True, text=True)
    if result.returncode == 0:
        return {"state": "sauber", "files": []}
    if result.returncode != 1:
        return {"state": "unbekannt", "files": []}
    files = []
    for line in result.stdout.splitlines()[1:]:
        if not line.strip():
            break
        files.append(line.strip())
    return {"state": "Konflikt", "files": files[:10]}


def ensure_isolated(project_path: str) -> None:
    """Bricht ab, wenn das Arbeitsverzeichnis der echte Checkout ist statt eines Klons (Workflow ohne Isolation)."""
    toplevel = os.path.realpath(git("rev-parse", "--show-toplevel").strip())
    if toplevel == os.path.realpath(project_path):
        raise BatchError("kein isolierter Klon – der Workflow braucht metadata.workspace.mode isolated")


def require_isolated(state: Path) -> None:
    """Die späteren Schritte arbeiten nur in einem Klon, den prepare als isoliert bestätigt hat."""
    if not (Path(state) / ISOLATED_MARKER).exists():
        raise BatchError("kein bestätigter isolierter Klon (prepare lief nicht oder nicht isoliert)")


def clean_tree() -> list[str]:
    """Verwirft alles Nicht-Committete im Klon und nennt es; geprüft wird nur, was committet ist."""
    leftovers = [line[3:].strip() for line in git("status", "--porcelain", "--untracked-files=all").splitlines()
                 if line.strip()]
    git("reset", "-q", "--hard", "HEAD")
    git("clean", "-fdq")
    return leftovers


def parse_time(value) -> datetime | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except ValueError:
        return None


def now_utc(value: str | None = None) -> datetime:
    return parse_time(value) or datetime.now(timezone.utc)


def is_stale(todo: dict, hours: int, now: datetime) -> bool:
    """Ein gestarteter Batch-TODO, der seit `hours` Stunden nicht verändert wurde – sein Lauf ist verloren."""
    updated = parse_time(todo.get("updatedAt"))
    return updated is not None and now - updated > timedelta(hours=hours)


def active_batch(todo: dict, hours: int, now: datetime) -> bool:
    tags = todo.get("tags") or []
    return (todo.get("status") == "IN_PROGRESS" and SELECTED in tags and STARTED in tags
            and not is_stale(todo, hours, now))

