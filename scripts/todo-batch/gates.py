#!/usr/bin/env python3
"""TODO-Batch (TODO cc0eb3ba): Gates nach Bereich – Backend, Frontend, gemeinsam – mit einer Wiederholung bei Rot.

Die Befehle sind Anpassungspunkte des Workflows (`defaultContext`). `{range}` im gemeinsamen Befehl wird durch
den geprüften Commit-Bereich ersetzt. Die Ausgabe jedes Gates geht in eine Logdatei; zurück kommen nur Name,
Ergebnis und bei Rot die letzten Zeilen ohne Stacktrace-Zeilen.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from dataclasses import dataclass
from itertools import count
from pathlib import Path

import batchlib as b

LOCKFILES = ("package-lock.json", "yarn.lock", "pnpm-lock.yaml")
_SEQUENCE = count(1)


@dataclass
class GateConfig:
    backend_dir: str
    frontend_dir: str
    backend_cmd: str
    frontend_cmd: str
    common_cmd: str
    logs: Path

    @staticmethod
    def add_arguments(parser: argparse.ArgumentParser) -> None:
        parser.add_argument("--backend-dir", default="ai-automation-backend")
        parser.add_argument("--frontend-dir", default="ai-automation-frontend")
        parser.add_argument("--backend-cmd", default="")
        parser.add_argument("--frontend-cmd", default="")
        parser.add_argument("--common-cmd", default="")
        parser.add_argument("--logs", default=".git/todo-batch/logs")

    @staticmethod
    def from_args(args) -> "GateConfig":
        return GateConfig(args.backend_dir.rstrip("/"), args.frontend_dir.rstrip("/"), args.backend_cmd,
                          args.frontend_cmd, args.common_cmd, Path(args.logs))


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
        code = subprocess.run(["bash", "-c", command], cwd=cwd, stdout=out, stderr=subprocess.STDOUT).returncode
    lines = [l for l in log.read_text(encoding="utf-8", errors="replace").splitlines() if not l.lstrip().startswith("at ")]
    return code == 0, "\n".join(lines[-15:])


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
            lines.append(f"{name}: grün" + (" im zweiten Versuch (instabil?)" if retried else ""))
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
