#!/usr/bin/env python3
"""Private, run-owned Git snapshots. No checkout/reset/clean in the source repository."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def git(root, *args, data=None):
    result = subprocess.run(["git", "-C", str(root), *args], input=data,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    if result.returncode:
        raise RuntimeError("git " + args[0] + " failed: " + result.stderr.decode(errors="replace")[-1500:])
    return result.stdout


def excluded(path):
    parts = path.parts
    return (any(p in {"target", "node_modules", "dist", "build", ".venv", "venv",
                      ".git", ".cache", ".output"} for p in parts)
            or path.suffix.lower() in {".db", ".sqlite", ".sqlite3", ".pem", ".key"}
            or (path.name.startswith(".env") and path.name not in {".env.example", ".env.template"}))


def untracked(root):
    files = {}
    for raw in git(root, "ls-files", "--others", "--exclude-standard", "-z").split(b"\0"):
        if not raw:
            continue
        relative = Path(os.fsdecode(raw))
        if excluded(relative):
            continue
        source = root / relative
        if source.is_symlink():
            target = os.readlink(source)
            if not source.resolve().is_relative_to(root):
                raise RuntimeError("Untracked symlink escapes the source repository: " + str(relative))
            files[str(relative)] = ("link", target.encode(), 0)
        elif source.is_file():
            files[str(relative)] = ("file", source.read_bytes(), source.stat().st_mode & 0o777)
    return files


def snapshot(root):
    head = git(root, "rev-parse", "HEAD").decode().strip()
    omit = [":(exclude,literal)" + os.fsdecode(raw)
            for raw in git(root, "ls-files", "-z").split(b"\0")
            if raw and excluded(Path(os.fsdecode(raw)))]
    diff = git(root, "diff", "--binary", "HEAD", "--", ".", *omit)
    files = untracked(root)
    digest = hashlib.sha256(head.encode() + diff)
    for name, (kind, data, mode) in sorted(files.items()):
        digest.update(name.encode() + b"\0" + kind.encode() + str(mode).encode() + b"\0" + data)
    return head, diff, files, digest.hexdigest()


def validate_repo_links(repo):
    # A tracked absolute symlink can otherwise reconnect an isolated build to its source.
    for directory, dirs, files in os.walk(repo, followlinks=False):
        dirs[:] = [d for d in dirs if d != ".git"]
        for name in dirs + files:
            path = Path(directory) / name
            if path.is_symlink() and not path.resolve().is_relative_to(repo):
                raise RuntimeError("Workspace symlink escapes the snapshot: " + str(path.relative_to(repo)))


def prepare(args):
    for value in (args.project, args.run):
        if not re.fullmatch(r"[A-Za-z0-9_-]+", value):
            raise RuntimeError("Invalid project/run identifier")
    source = Path(args.source).resolve(strict=True)
    root = Path(args.root).expanduser().resolve()
    source_root = Path(git(source, "rev-parse", "--show-toplevel").decode().strip()).resolve()
    if root.is_relative_to(source_root) or source_root.is_relative_to(root):
        raise RuntimeError("Workspace storage and source checkout must be separate")
    run_dir = root / args.project / args.run
    slot = getattr(args, "slot", None)
    if slot:
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*", slot):
            raise RuntimeError("Invalid repository slot")
        run_dir = run_dir / "slots" / slot
    repo = run_dir / "repo"
    manifest_path = run_dir / "workspace.json"
    expected = {"projectId": args.project, "runId": args.run, "sourcePath": str(source)}
    if slot:
        expected["slot"] = slot
    if args.resume:
        if (run_dir.resolve() != run_dir or manifest_path.is_symlink()
                or not manifest_path.is_file() or repo.is_symlink()):
            raise RuntimeError("Cannot resume: original workspace is missing or invalid")
        manifest = json.loads(manifest_path.read_text())
        if any(manifest.get(k) != v for k, v in expected.items()):
            raise RuntimeError("Workspace ownership mismatch")
        if manifest.get("workspacePath") != str(repo) or not (repo / ".git").is_dir():
            raise RuntimeError("Cannot resume: original Git snapshot is missing")
        if not Path(manifest["executionPath"]).resolve().is_relative_to(repo):
            raise RuntimeError("Invalid execution path")
        validate_repo_links(repo)
        return manifest

    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    parent = run_dir.parent
    parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if parent.resolve() != parent:
        raise RuntimeError("Workspace parent must not be a symlink")
    run_dir.mkdir(mode=0o700)  # Never reuse/reset an existing run's checkout.
    logs = run_dir / "logs"
    logs.mkdir(mode=0o700)
    initial = snapshot(source_root) if args.mode == "working-tree" else None
    sha = initial[0] if initial else (getattr(args, "commit", None) or git(source_root, "rev-parse", "HEAD").decode().strip())
    with (logs / "prepare.log").open("wb") as log:
        subprocess.run(["git", "clone", "--local", "--no-hardlinks", "--no-checkout",
                        str(source_root), str(repo)], check=True, stdout=log, stderr=log, timeout=180)
        git(repo, "checkout", "--detach", sha)
        # A local clone must never push agent changes back into the source checkout.
        git(repo, "config", "remote.origin.pushurl", "disabled://explicit-export-required")
        # Preserve author identity without importing checkout-local hooks or credentials.
        for key in ("user.name", "user.email"):
            result = subprocess.run(["git", "-C", str(source_root), "config", "--get", key],
                                    capture_output=True, timeout=10)
            if result.returncode == 0:
                git(repo, "config", key, result.stdout.decode().strip())
        if initial:
            if initial[1]:
                git(repo, "apply", "--binary", "-", data=initial[1])
            for name, (kind, content, mode) in initial[2].items():
                target = repo / name
                if not target.parent.resolve().is_relative_to(repo):
                    raise RuntimeError("Untracked path escapes snapshot")
                target.parent.mkdir(parents=True, exist_ok=True)
                if kind == "link":
                    target.symlink_to(content.decode())
                else:
                    target.write_bytes(content)
                    target.chmod(mode)
            if snapshot(source_root)[3] != initial[3]:
                raise RuntimeError("Source changed during working-tree snapshot; retry with a stable source")
        validate_repo_links(repo)
        if args.branch:
            git(repo, "checkout", "-b", "fix/workflow-" + args.run)
        if args.npm:
            for relative in (".", "ai-automation-frontend"):
                frontend = repo / relative
                if (frontend / "package-lock.json").is_file():
                    subprocess.run(["npm", "ci", "--no-audit", "--no-fund"], cwd=frontend,
                                   stdout=log, stderr=log, check=True, timeout=900)
    execution = repo / source.relative_to(source_root)
    if not execution.is_dir():
        raise RuntimeError("Project directory does not exist in selected snapshot")
    manifest = {**expected, "sourceRepository": str(source_root), "sourceCommit": sha,
                "sourceMode": args.mode, "snapshotHash": initial[3] if initial else sha,
                "workspacePath": str(repo), "executionPath": str(execution),
                "logsPath": str(logs), "branch": "fix/workflow-" + args.run if args.branch else None}
    temporary = run_dir / "workspace.json.tmp"
    temporary.write_text(json.dumps(manifest, indent=2) + "\n")
    temporary.replace(manifest_path)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True)
    parser.add_argument("--root", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--run", required=True)
    parser.add_argument("--mode", choices=["commit", "working-tree"], default="commit")
    parser.add_argument("--slot", help="Named additional repository of the same run")
    parser.add_argument("--commit", help="Commit captured at run start, before waiting for an execution slot")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--branch", action="store_true")
    parser.add_argument("--npm", action="store_true")
    args = parser.parse_args()
    try:
        print(json.dumps(prepare(args)))
    except Exception as error:
        print("Workspace preparation failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
