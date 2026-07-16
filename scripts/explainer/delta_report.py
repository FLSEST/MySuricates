#!/usr/bin/env python3
"""Delta-Report für den Code-Explainer: Modus (BASELINE/DELTA), Git-Delta, Staleness.

Aufruf: delta_report.py <repo_root> <run_dir>
Output: @@-Sektionen auf stdout (Input für den Erklär-Block) + run-meta.json im run_dir.
"""
import json
import os
import subprocess
import sys
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common

MAX_COMMITS = 50
MAX_FILE_LINES = 80
MAX_FILES_PER_AREA = 8
JAVA_PREFIX = "ai-automation-backend/src/main/java/com/automation/"


def git(repo_root, *args):
    return subprocess.run(["git"] + list(args), cwd=repo_root,
                          capture_output=True, text=True).stdout.strip()


def area_of(path):
    if path.startswith(JAVA_PREFIX):
        pkg_dir = os.path.dirname(path[len(JAVA_PREFIX):]).replace("/", ".")
        pkg = ".".join(pkg_dir.split(".")[:2]) if pkg_dir else "(root)"
        return "backend:" + pkg
    if path.startswith("ai-automation-backend/"):
        return "backend:sonstiges"
    if path.startswith("ai-automation-frontend/src/"):
        parts = path.split("/")
        return "frontend:" + (parts[2] if len(parts) > 3 else parts[-1])
    if path.startswith("ai-automation-frontend/"):
        return "frontend:sonstiges"
    for prefix in ("docs/", "workflow-pool/", "scripts/"):
        if path.startswith(prefix):
            return prefix.rstrip("/")
    return "sonstiges"


def grouped_files(changed):
    areas = {}
    for f in changed:
        areas.setdefault(area_of(f), []).append(f)
    lines = []
    for area in sorted(areas, key=lambda a: -len(areas[a])):
        files = areas[area]
        lines.append("[%s] (%d Dateien)" % (area, len(files)))
        for f in files[:MAX_FILES_PER_AREA]:
            lines.append("  " + f)
        if len(files) > MAX_FILES_PER_AREA:
            lines.append("  … +%d weitere" % (len(files) - MAX_FILES_PER_AREA))
        if len(lines) >= MAX_FILE_LINES:
            rest = sum(len(v) for a, v in areas.items()) - sum(
                1 for l in lines if l.startswith("  ") and not l.startswith("  …"))
            lines.append("… (Ausgabe gekappt, insgesamt %d geänderte Dateien)" % len(changed))
            break
    return lines


def is_stale(entry, changed_set):
    for cited in entry.get("cited_files", []):
        cited = cited.strip().rstrip("/")
        if not cited:
            continue
        for f in changed_set:
            if f == cited or f.startswith(cited + "/"):
                return True
    return False


def baseline_overview(repo_root):
    lines = []
    java_root = os.path.join(repo_root, JAVA_PREFIX)
    pkg_counts = {}
    entity_count = 0
    for dirpath, _, filenames in os.walk(java_root):
        java_files = [f for f in filenames if f.endswith(".java")]
        if not java_files:
            continue
        pkg_dir = os.path.relpath(dirpath, java_root).replace(os.sep, ".")
        pkg = ".".join(pkg_dir.split(".")[:2]) if pkg_dir != "." else "(root)"
        pkg_counts[pkg] = pkg_counts.get(pkg, 0) + len(java_files)
        for f in java_files:
            with open(os.path.join(dirpath, f), encoding="utf-8", errors="replace") as fh:
                if "@Entity" in fh.read():
                    entity_count += 1
    lines.append("Backend-Packages (com.automation.*, Klassen je Tiefe-2-Package):")
    for pkg in sorted(pkg_counts, key=lambda p: -pkg_counts[p])[:15]:
        lines.append("  %-32s %d" % (pkg, pkg_counts[pkg]))
    lines.append("  Gesamt: %d Klassen, davon ~%d @Entity" % (sum(pkg_counts.values()), entity_count))
    fe_root = os.path.join(repo_root, "ai-automation-frontend", "src")
    if os.path.isdir(fe_root):
        lines.append("Frontend (src/, Dateien je Ordner):")
        for d in sorted(os.listdir(fe_root)):
            full = os.path.join(fe_root, d)
            if os.path.isdir(full):
                n = sum(len(fs) for _, _, fs in os.walk(full))
                lines.append("  %-32s %d" % (d, n))
    return lines


def main():
    repo_root, run_dir = sys.argv[1], sys.argv[2]
    state_path = os.path.join(repo_root, "explainer", "state.json")
    head = common.head_sha(repo_root)
    today = date.today().isoformat()
    hints = []

    state = None
    if os.path.exists(state_path):
        try:
            with open(state_path, encoding="utf-8") as f:
                state = json.load(f)
        except ValueError:
            hints.append("state.json unlesbar — behandle den Lauf als BASELINE")

    out = []
    if state is None:
        mode, range_str, commit_count = "BASELINE", "BASELINE..%s" % head[:7], 0
        stale_qa, stale_flows = [], []
        out += ["@@MODE", "BASELINE", "@@HEAD_SHA", head, "@@STRUKTUR"]
        out += baseline_overview(repo_root)
    else:
        mode = "DELTA"
        last = state.get("lastExplainedSha", "")
        valid = last and subprocess.run(
            ["git", "cat-file", "-e", last + "^{commit}"], cwd=repo_root,
            capture_output=True).returncode == 0
        if valid:
            range_str = "%s..%s" % (last[:7], head[:7])
            log = git(repo_root, "log", "--oneline", "%s..%s" % (last, head))
            changed = [f for f in git(repo_root, "diff", "--name-only",
                                      "%s..%s" % (last, head)).splitlines() if f]
            stat = git(repo_root, "diff", "--stat", "%s..%s" % (last, head)).splitlines()
            stat_line = stat[-1].strip() if stat else ""
        else:
            hints.append("lastExplainedSha %s nicht mehr auffindbar (History-Rewrite?) — "
                         "Fallback: letzte 7 Tage" % (last[:7] or "(leer)"))
            range_str = "letzte-7-Tage..%s" % head[:7]
            log = git(repo_root, "log", "--oneline", "--since=7 days ago")
            changed = sorted({f for f in git(
                repo_root, "log", "--since=7 days ago", "--name-only",
                "--pretty=format:").splitlines() if f})
            stat_line = ""
        commits = log.splitlines()
        commit_count = len(commits)
        changed_set = set(changed)
        stale_qa = [e for e in state.get("catalog", []) if is_stale(e, changed_set)]
        stale_flows = [e for e in state.get("flows", []) if is_stale(e, changed_set)]

        out += ["@@MODE", "DELTA", "@@RANGE", range_str, "@@HEAD_SHA", head, "@@COMMITS"]
        out += commits[:MAX_COMMITS]
        if commit_count > MAX_COMMITS:
            out.append("… +%d weitere Commits" % (commit_count - MAX_COMMITS))
        if not commits:
            out.append("(keine neuen Commits seit dem letzten Lauf)")
        out.append("@@DATEIEN")
        if stat_line:
            out.append(stat_line)
        out += grouped_files(changed)
        out.append("@@STALE_QA")
        for e in stale_qa:
            out.append("- %s | %s | cited: %s"
                       % (e["id"], e.get("frage", ""), ", ".join(e.get("cited_files", []))))
        if not stale_qa:
            out.append("(keine veralteten Q&A-Einträge)")
        out.append("(%d von %d Katalog-Einträgen sind frisch — NICHT neu beantworten)"
                   % (len(state.get("catalog", [])) - len(stale_qa), len(state.get("catalog", []))))
        out.append("@@STALE_FLOWS")
        for e in stale_flows:
            out.append("- %s | %s | cited: %s"
                       % (e["id"], e.get("titel", ""), ", ".join(e.get("cited_files", []))))
        if not stale_flows:
            out.append("(keine veralteten Flow-Diagramme)")

    if hints:
        out.append("@@HINWEISE")
        out += hints

    meta = {
        "mode": mode,
        "head_sha": head,
        "last_sha": (state or {}).get("lastExplainedSha", ""),
        "range": range_str,
        "commit_count": commit_count,
        "stale_qa_ids": [e["id"] for e in stale_qa],
        "stale_flow_ids": [e["id"] for e in stale_flows],
        "report_date": today,
    }
    with open(os.path.join(run_dir, "run-meta.json"), "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=1)

    print("\n".join(out))


if __name__ == "__main__":
    main()
