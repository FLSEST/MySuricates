#!/usr/bin/env python3
"""TODO batch (TODO cc0eb3ba): gates by area – backend, frontend, common – with one retry on red.

The commands are customization points of the workflow (`defaultContext`). `{range}` in the common command is replaced by
the checked commit range. The output of each gate goes to a log file; only name,
result and, on red, the last lines without stacktrace lines come back. `first_error` extracts the first
meaningful error line from that for the note on the TODO; `flaky` names the gates that only turned green on the second attempt.
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
# Summary and closing lines of the tools: they say "red" but not why
_NOISE = re.compile(r"(?i)^(failures:|errors:|tests run:|build failure|failed to execute goal|-> \[help|"
                    r"re-run maven|to see the full stack trace|for more information about the errors|please refer to|"
                    r"\[help \d+\]|npm err!|error: process completed with exit code|error: command failed)")
_ERRORISH = re.compile(r"(?i)(error|fail|assert|expected|exception|fehler|✗|×|zeitüberschreitung)")
_ANSI = re.compile(r"\x1b\[[0-9;?]*[A-Za-z]")   # colour sequences (Maven colours even when writing to a file)
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
        # own process group: on timeout the JVM/node process under bash dies too
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
    """The first meaningful error line of the gate output, without `[ERROR]` prefix; otherwise the last line."""
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
    """Per red gate `<name>: <first error line>` from the lines of `run_gates`."""
    reasons = []
    for entry in lines:
        head, _, tail = entry.partition("\n")
        if head.endswith(": ROT"):
            name = head.removesuffix(": ROT")
            reasons.append(f"{name}: {first_error(tail) or 'ROT'}")
    return reasons


def flaky(lines: list[str]) -> list[str]:
    """The gates that only turned green on the second attempt."""
    return [entry.split(":", 1)[0] for entry in lines if RETRIED in entry.split("\n", 1)[0]]


def run_gates(scope: list[str], rng: str, cfg: GateConfig, label: str) -> tuple[bool, list[str]]:
    """All gates of the area; a red one is rerun once (unstable tests). Returns: green?, lines."""
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
