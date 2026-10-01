#!/usr/bin/env python3
"""TODO-Batch (TODO cc0eb3ba): Gates nach Bereich – Backend, Frontend, gemeinsam – mit einer Wiederholung bei Rot.

Die Befehle sind Anpassungspunkte des Workflows (`defaultContext`). `{range}` im gemeinsamen Befehl wird durch
den geprüften Commit-Bereich ersetzt. Die Ausgabe jedes Gates geht in eine Logdatei; zurück kommen nur Name,
Ergebnis und bei Rot die letzten Zeilen ohne Stacktrace-Zeilen. `first_error` holt daraus die erste
aussagekräftige Fehlerzeile für den Vermerk am TODO; `flaky` nennt die Gates, die erst im zweiten Versuch grün wurden.
"""
from __future__ import annotations

import argparse
import os
import re
import signal
import subprocess
import sys
from dataclasses import dataclass
from itertools import count
from pathlib import Path

import batchlib as b

LOCKFILES = ("package-lock.json", "yarn.lock", "pnpm-lock.yaml")
_SEQUENCE = count(1)
RETRIED = "im zweiten Versuch"
# Sammel- und Abschlusszeilen der Werkzeuge: sagen „rot“, aber nicht warum
_NOISE = re.compile(r"(?i)^(failures:|errors:|tests run:|build failure|failed to execute goal|-> \[help|"
                    r"re-run maven|to see the full stack trace|for more information about the errors|please refer to|"
                    r"\[help \d+\]|npm err!|error: process completed with exit code|error: command failed)")
_ERRORISH = re.compile(r"(?i)(error|fail|assert|expected|exception|fehler|✗|×|zeitüberschreitung)")
_ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")   # Farbfolgen (Maven färbt auch in eine Datei)
_LEVEL = re.compile(r"^\s*\[(ERROR|WARNING|WARN|INFO)\]\s*")


@dataclass
class GateConfig:
    backend_dir: str
    frontend_dir: str
    backend_cmd: str
    frontend_cmd: str
    common_cmd: str
    logs: Path
    timeout: int = 3600

    @staticmethod
    def add_arguments(parser: argparse.ArgumentParser) -> None:
        parser.add_argument("--backend-dir", default="ai-automation-backend")
        parser.add_argument("--frontend-dir", default="ai-automation-frontend")
        parser.add_argument("--backend-cmd", default="")
        parser.add_argument("--frontend-cmd", default="")
        parser.add_argument("--common-cmd", default="")
        parser.add_argument("--logs", default=".git/todo-batch/logs")
        parser.add_argument("--gate-timeout", type=int, default=3600, help="Sekunden je Gate-Lauf")

    @staticmethod
    def from_args(args) -> "GateConfig":
        return GateConfig(args.backend_dir.rstrip("/"), args.frontend_dir.rstrip("/"), args.backend_cmd,
                          args.frontend_cmd, args.common_cmd, Path(args.logs), args.gate_timeout)


def changed_files(base: str, head: str = "HEAD") -> list[str]:
    return [f for f in b.git("diff", "--name-only", f"{base}..{head}").splitlines() if f]


def scope_for(files: list[str], cfg: GateConfig) -> list[str]:
    scope = []
    if cfg.backend_cmd and any(f.startswith(cfg.backend_dir + "/") for f in files):
        scope.append("backend")
    if cfg.frontend_cmd and any(f.startswith(cfg.frontend_dir + "/") for f in files):
        scope.append("frontend")
    if cfg.common_cmd and files:
        scope.append("common")
    return scope


def _run(name: str, command: str, cwd: str, cfg: GateConfig, label: str) -> tuple[bool, str]:
    cfg.logs.mkdir(parents=True, exist_ok=True)
    log = cfg.logs / f"{label}-{name}-{next(_SEQUENCE)}.log"
    with log.open("w", encoding="utf-8") as out:
        # eigene Prozessgruppe: bei Zeitüberschreitung stirbt auch die JVM/der Node-Prozess unter bash
        process = subprocess.Popen(["bash", "-c", command], cwd=cwd, stdout=out, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            code = process.wait(timeout=cfg.timeout)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            code = None
    text = _ANSI.sub("", log.read_text(encoding="utf-8", errors="replace"))
    lines = [l for l in text.splitlines() if not l.lstrip().startswith("at ")]
    if code is None:
        lines.append(f"Zeitüberschreitung nach {cfg.timeout} s")
    return code == 0, "\n".join(lines[-15:])


def first_error(tail: str) -> str:
    """Die erste aussagekräftige Fehlerzeile der Gate-Ausgabe, ohne `[ERROR]`-Präfix; sonst die letzte Zeile."""
    fallback = ""
    for raw in _ANSI.sub("", tail or "").splitlines():
        line = _LEVEL.sub("", raw).strip()
        if not line or _NOISE.match(line) or "Time elapsed" in line:
            continue
        if _ERRORISH.search(raw):
            return b.plain(line, 200)
        fallback = line
    return b.plain(fallback, 200)


def red_reasons(lines: list[str]) -> list[str]:
    """Je rotem Gate `<name>: <erste Fehlerzeile>` aus den Zeilen von `run_gates`."""
    reasons = []
    for entry in lines:
        head, _, tail = entry.partition("\n")
        if head.endswith(": ROT"):
            name = head.removesuffix(": ROT")
            reasons.append(f"{name}: {first_error(tail) or 'ROT'}")
    return reasons


def flaky(lines: list[str]) -> list[str]:
    """Die Gates, die erst im zweiten Versuch grün wurden."""
    return [entry.split(":", 1)[0] for entry in lines if RETRIED in entry.split("\n", 1)[0]]


def run_gates(scope: list[str], rng: str, cfg: GateConfig, label: str) -> tuple[bool, list[str]]:
    """Alle Gates des Bereichs; ein rotes läuft einmal nach (instabile Tests). Rückgabe: grün?, Zeilen."""
    commands = {"backend": (cfg.backend_cmd, cfg.backend_dir), "frontend": (cfg.frontend_cmd, cfg.frontend_dir),
                "common": (cfg.common_cmd.replace("{range}", rng), ".")}
    ok_all, lines = True, []
    for name in scope:
        command, cwd = commands[name]
        ok, tail = _run(name, command, cwd, cfg, label)
        retried = not ok
        if retried:
            ok, tail = _run(name, command, cwd, cfg, label)
        if ok:
            lines.append(f"{name}: grün" + (f" {RETRIED} (instabil?)" if retried else ""))
        else:
            ok_all = False
            lines.append(f"{name}: ROT\n" + "\n".join("    " + l for l in tail.splitlines()))
    return ok_all, lines


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    parser.add_argument("--baseline", action="store_true", required=True)
    GateConfig.add_arguments(parser)
    args = parser.parse_args(argv)
    cfg = GateConfig.from_args(args)
    if not b.read_text(Path(args.state) / "ids.txt").strip():
        print("Keine TODOs – keine Ausgangsprüfung nötig.")
        return 0
    scope = [name for name, command in (("backend", cfg.backend_cmd), ("frontend", cfg.frontend_cmd)) if command]
    ok, lines = run_gates(scope, "", cfg, "ausgangslage")
    print("Ausgangslage " + ("grün" if ok else "ROT – erst den bestehenden Fehler beheben"))
    print("\n".join(lines))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
