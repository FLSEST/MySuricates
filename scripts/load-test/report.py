#!/usr/bin/env python3
"""Auswertung eines Lasttests: Rohdaten rein, Kennzahlen, Verdict und Baseline-Vergleich raus.

  report.py RAW_JSON OUT_DIR [--results-root DIR] [--repo DIR]

Liest die Rohdaten von driver.py (jede Anfrage mit Route, Status, Client-Zeit und Server-Timing),
schreibt nach OUT_DIR:
  result.json  maschinenlesbar: Kennzahlen je Route, Gesamtzahlen, Verdict, Baseline
  facts.txt    dasselbe im Audit-Format ("FAKT name: wert" / "NICHT GEMESSEN: name (grund)") —
               das liest der Pool-Workflow "Lasttest-Auswertung", die KI deutet nur
  summary.txt  eine Zeile "commit=<sha> verdict=<GREEN|YELLOW|RED> scenario=<version>" fuer release.sh

Die Baseline ist der juengste fruehere Lauf desselben Szenarios auf einem Vorfahren-Commit, ein
getaggter Release-Stand vor allen anderen — damit vergleicht der Lasttest vor v0.8.0 gegen den vor
v0.7.8 und nicht gegen einen Zwischenlauf von gestern.

Nur Python-Standardbibliothek. Perzentile nach Nearest-Rank wie RequestMetricsCollector.
Exit 0 bei GREEN/YELLOW, 3 bei RED, 2 bei kaputten Eingaben.
"""
import argparse
import json
import math
import os
import subprocess
import sys

# Schwellen des Verdicts. Absichtlich wenige und grobe: Der Lasttest soll Einbrueche zeigen,
# keine Millisekunden-Schwankungen zwischen zwei Laeufen.
RED_ERROR_RATE = 0.01          # mehr als 1 % fehlgeschlagene Anfragen
RED_P95_MS = 5000              # eine Route braucht im p95 mehr als 5 s
YELLOW_P95_MS = 1000           # eine Leseroute braucht im p95 mehr als 1 s
# Der Wartezeit-Anteil auf die einzige DB-Connection ist unter Last bei pool-size 1 immer hoch (im
# ersten Lauf 50-79 % auf fast jeder Route, bei p95 unter 450 ms) — das ist die Bauart, kein Befund.
# Er erklaert deshalb nur eine Route, die ohnehin zu langsam ist, und steht als Gesamtwert im Blatt.
YELLOW_HEAP_PERCENT = 85.0
REGRESSION_FACTOR = 1.5        # p95 einer Route gegen die Baseline
# Rechnerlast je CPU (1-Minuten-Load, Median der Lastphase), ab der die Messung nicht belastbar ist.
# Ein Probelauf neben parallelen Builds war 5- bis 20-mal langsamer als der echte Lauf kurz danach —
# eine Regression gegen die Baseline waere dann ein Fehlalarm (TODO b8e1de5e).
HOST_BUSY_PER_CPU = 1.0
MIN_SAMPLES = 30               # darunter ist ein p95 kein Befund
MIN_MEANINGFUL_MS = 25         # 3 ms gegen 1 ms ist Faktor drei und bedeutet nichts


def percentile(sorted_values, pct):
    if not sorted_values:
        return 0
    rank = int(math.ceil(pct / 100.0 * len(sorted_values)))
    return sorted_values[min(max(rank - 1, 0), len(sorted_values) - 1)]


def route_stats(requests):
    """Kennzahlen je Route. requests: Liste von dicts mit route, status, ms, srv_total, srv_wait, sql, bytes, kind."""
    by_route = {}
    for req in requests:
        by_route.setdefault(req["route"], []).append(req)
    stats = {}
    for route, reqs in sorted(by_route.items()):
        ok = [r for r in reqs if 200 <= r["status"] < 400]
        times = sorted(r["ms"] for r in reqs)
        srv_total = sum(r.get("srv_total") or 0 for r in ok)
        srv_wait = sum(r.get("srv_wait") or 0 for r in ok)
        sqls = [r["sql"] for r in ok if r.get("sql") is not None]
        stats[route] = {
            "kind": reqs[0].get("kind", "read"),
            "n": len(reqs),
            "errors": len(reqs) - len(ok),
            "p50_ms": percentile(times, 50),
            "p95_ms": percentile(times, 95),
            "p99_ms": percentile(times, 99),
            "max_ms": times[-1] if times else 0,
            "wait_share": round(srv_wait / srv_total, 4) if srv_total > 0 else None,
            "sql_avg": round(sum(sqls) / len(sqls), 2) if sqls else None,
            "sql_max": max(sqls) if sqls else None,
            "bytes_avg": int(sum(r.get("bytes") or 0 for r in ok) / len(ok)) if ok else 0,
        }
    return stats


def run_stats(runs):
    done = [r for r in runs if r.get("status") in ("COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED")]
    durations = sorted(r["seconds"] for r in done if r.get("seconds") is not None)
    return {
        "started": len(runs),
        "completed": sum(1 for r in done if r["status"] == "COMPLETED"),
        "failed": sum(1 for r in done if r["status"] != "COMPLETED"),
        "unfinished": len(runs) - len(done),
        "p50_s": round(percentile(durations, 50), 2) if durations else None,
        "p95_s": round(percentile(durations, 95), 2) if durations else None,
    }


def host_load(raw):
    """Median und Maximum der Rechnerlast je CPU waehrend der Lastphase, oder None."""
    samples = sorted(x for x in raw.get("host_load") or [] if x is not None)
    cpus = raw.get("cpus") or 0
    if not samples or cpus <= 0:
        return None
    mid = len(samples) // 2
    median = samples[mid] if len(samples) % 2 else (samples[mid - 1] + samples[mid]) / 2
    return {"cpus": cpus, "median_per_cpu": round(median / cpus, 2), "max_per_cpu": round(samples[-1] / cpus, 2)}


def verdict(stats, runs, heap_max, total, errors, regressions, host=None):
    """(Verdict, Gruende). RED: kaputt oder unbenutzbar langsam, YELLOW: auffaellig, sonst GREEN."""
    red, yellow = [], []
    host_busy = host is not None and host["median_per_cpu"] > HOST_BUSY_PER_CPU
    if host_busy:
        yellow.append("Rechner ausgelastet (Load je CPU im Median %.1f) — Messung wiederholen, Vergleich mit der Baseline nicht belastbar"
                      % host["median_per_cpu"])
    if total == 0:
        red.append("keine einzige gemessene Anfrage")
    elif errors / total > RED_ERROR_RATE:
        red.append("Fehlerquote %.1f %% ueber %.0f %%" % (100.0 * errors / total, 100 * RED_ERROR_RATE))
    if runs["failed"] > 0:
        red.append("%d Lauf/Laeufe nicht COMPLETED" % runs["failed"])
    if runs["unfinished"] > 0:
        yellow.append("%d Lauf/Laeufe am Ende noch nicht fertig" % runs["unfinished"])
    for route, s in stats.items():
        if s["n"] < MIN_SAMPLES:
            continue
        cause = "" if s["wait_share"] is None else ", davon %.0f %% Warten auf die DB-Connection" % (100 * s["wait_share"])
        if s["p95_ms"] > RED_P95_MS:
            red.append("%s p95 %d ms%s" % (route, s["p95_ms"], cause))
        elif s["kind"] == "read" and s["p95_ms"] > YELLOW_P95_MS:
            yellow.append("%s p95 %d ms%s" % (route, s["p95_ms"], cause))
    if heap_max is not None and heap_max > YELLOW_HEAP_PERCENT:
        yellow.append("Heap bis %.0f %%" % heap_max)
    for reg in [] if host_busy else regressions:
        yellow.append("%s p95 %d ms gegen %d ms in der Baseline (x%.1f)" % (reg["route"], reg["p95_ms"], reg["baseline_p95_ms"], reg["factor"]))
    if red:
        return "RED", red + yellow
    if yellow:
        return "YELLOW", yellow
    return "GREEN", []


def is_ancestor(repo, older, newer):
    if not repo or not older or not newer:
        return False
    if older == newer:
        return False
    rc = subprocess.run(["git", "-C", repo, "merge-base", "--is-ancestor", older, newer],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode
    return rc == 0


def find_baseline(results_root, current, repo):
    """Juengster frueherer Lauf desselben Szenarios auf einem Vorfahren; getaggte Staende zuerst."""
    if not results_root or not os.path.isdir(results_root):
        return None
    candidates = []
    for name in os.listdir(results_root):
        path = os.path.join(results_root, name, "result.json")
        if not os.path.isfile(path):
            continue
        try:
            with open(path) as f:
                other = json.load(f)
        except (OSError, ValueError):
            continue
        if other.get("scenario_version") != current["scenario_version"]:
            continue
        if other.get("verdict") == "RED" or not is_ancestor(repo, other.get("commit"), current["commit"]):
            continue
        candidates.append((1 if other.get("tag") else 0, other.get("finished_at", ""), name, other))
    if not candidates:
        return None
    candidates.sort(key=lambda c: (c[0], c[1]), reverse=True)
    _, _, name, other = candidates[0]
    other["_dir"] = name
    return other


def regressions_against(stats, baseline):
    if not baseline:
        return []
    found = []
    for route, s in stats.items():
        b = baseline.get("routes", {}).get(route)
        if not b or s["n"] < MIN_SAMPLES or b.get("n", 0) < MIN_SAMPLES:
            continue
        if b["p95_ms"] < MIN_MEANINGFUL_MS:
            continue
        factor = s["p95_ms"] / b["p95_ms"]
        if factor >= REGRESSION_FACTOR:
            found.append({"route": route, "p95_ms": s["p95_ms"], "baseline_p95_ms": b["p95_ms"], "factor": round(factor, 2)})
    found.sort(key=lambda r: r["factor"], reverse=True)
    return found


def git_tag(repo, commit):
    if not repo or not commit:
        return None
    out = subprocess.run(["git", "-C", repo, "tag", "--points-at", commit, "--list", "v[0-9]*"],
                         capture_output=True, text=True).stdout.split()
    return out[0] if out else None


def build_result(raw, results_root=None, repo=None):
    requests = [r for r in raw["requests"] if r.get("phase") == "load"]
    stats = route_stats(requests)
    runs = run_stats(raw.get("runs", []))
    heap = [h for h in raw.get("heap", []) if h is not None]
    heap_max = max(heap) if heap else None
    total = len(requests)
    errors = sum(s["errors"] for s in stats.values())
    duration = max(raw["load_end"] - raw["load_start"], 0.001)
    ok = [r for r in requests if 200 <= r["status"] < 400]
    srv_total = sum(r.get("srv_total") or 0 for r in ok)
    wait_share = round(sum(r.get("srv_wait") or 0 for r in ok) / srv_total, 4) if srv_total > 0 else None
    result = {
        "commit": raw["commit"],
        "tag": raw.get("tag") or git_tag(repo, raw["commit"]),
        "scenario_version": raw["scenario"]["version"],
        "scenario": raw["scenario"],
        "started_at": raw.get("started_at"),
        "finished_at": raw.get("finished_at"),
        "duration_s": round(duration, 1),
        "requests": total,
        "errors": errors,
        "throughput_rps": round(total / duration, 1),
        "wait_share": wait_share,
        "heap_max_percent": heap_max,
        "ai_cli_calls": raw.get("ai_cli_calls"),
        "host_load": host_load(raw),
        "seed": raw.get("seed", {}),
        "runs": runs,
        "routes": stats,
    }
    baseline = find_baseline(results_root, result, repo)
    regressions = regressions_against(stats, baseline)
    result["baseline"] = None if not baseline else {
        "dir": baseline["_dir"], "commit": baseline["commit"], "tag": baseline.get("tag"),
        "finished_at": baseline.get("finished_at")}
    result["regressions"] = regressions
    result["verdict"], result["reasons"] = verdict(stats, runs, heap_max, total, errors, regressions, result["host_load"])
    if raw.get("ai_cli_calls"):
        # Ein KI-Aufruf im Lasttest kostet Tokens und verfaelscht die Messung — der Stand ist nicht vergleichbar.
        result["verdict"] = "RED"
        result["reasons"].insert(0, "%d KI-CLI-Aufruf(e) waehrend des Tests" % raw["ai_cli_calls"])
    return result


def facts(result):
    lines = ["## LASTTEST-FAKTENBLATT",
             "# Deterministisch gemessen (scripts/load-test.sh). FAKT = Messwert, NICHT GEMESSEN = Luecke."]

    def fact(name, value):
        lines.append("FAKT %s: %s" % (name, value))

    fact("load_commit", result["commit"] + (" (%s)" % result["tag"] if result.get("tag") else ""))
    fact("load_scenario_version", result["scenario_version"])
    sc = result["scenario"]
    fact("load_scenario", "%d s Last nach %d s Aufwaermen | %d Leser | %d Lauf-Starter | %d TODO-Schreiber"
         % (sc["load"]["duration_s"], sc["load"]["warmup_s"], sc["load"]["readers"], sc["load"]["run_starters"],
            sc["load"]["todo_writers"]))
    seed = result.get("seed") or {}
    if seed:
        fact("load_seed", ", ".join("%s=%s" % (k, seed[k]) for k in sorted(seed)))
    fact("load_requests", result["requests"])
    fact("load_throughput_rps", result["throughput_rps"])
    fact("load_errors", "%d (%.2f %%)" % (result["errors"], 100.0 * result["errors"] / result["requests"] if result["requests"] else 0))
    if result.get("wait_share") is not None:
        fact("load_wait_share_percent", "%.1f (Anteil der Serverzeit aller Anfragen, der auf die einzige DB-Connection wartet)" % (100 * result["wait_share"]))
    else:
        lines.append("NICHT GEMESSEN: load_wait_share_percent (kein Server-Timing-Header — server-timing-enabled aus?)")
    runs = result["runs"]
    fact("load_runs", "%d gestartet, %d COMPLETED, %d andere, %d unfertig" % (runs["started"], runs["completed"], runs["failed"], runs["unfinished"]))
    if runs["p95_s"] is not None:
        fact("load_run_duration_s", "p50 %s | p95 %s (Start bis Ende, inkl. Warteschlange)" % (runs["p50_s"], runs["p95_s"]))
    if result["heap_max_percent"] is not None:
        fact("load_heap_max_percent", "%.1f" % result["heap_max_percent"])
    else:
        lines.append("NICHT GEMESSEN: load_heap_max_percent (/system/jvm-memory lieferte keine Werte)")
    fact("load_ai_cli_calls", result.get("ai_cli_calls") or 0)
    host = result.get("host_load")
    if host:
        fact("load_host_load", "Median %.2f | Max %.2f je CPU (%d CPUs, 1-Minuten-Load waehrend der Lastphase, Instanz und Treiber eingeschlossen)"
             % (host["median_per_cpu"], host["max_per_cpu"], host["cpus"]))
    else:
        lines.append("NICHT GEMESSEN: load_host_load (os.getloadavg auf diesem System nicht verfuegbar)")
    for route, s in sorted(result["routes"].items(), key=lambda kv: kv[1]["p95_ms"], reverse=True):
        wait = "-" if s["wait_share"] is None else "%.1f %%" % (100 * s["wait_share"])
        sql = "-" if s["sql_avg"] is None else "%.1f (max %s)" % (s["sql_avg"], s["sql_max"])
        fact("load_route", "%s | %s | n %d | Fehler %d | p50 %d | p95 %d | p99 %d | max %d ms | Wartezeit %s | SQL %s | Antwort %d B"
             % (route, s["kind"], s["n"], s["errors"], s["p50_ms"], s["p95_ms"], s["p99_ms"], s["max_ms"], wait, sql, s["bytes_avg"]))
    base = result.get("baseline")
    if base:
        fact("load_baseline", "%s%s vom %s" % (base["commit"][:12], " (%s)" % base["tag"] if base.get("tag") else "", base.get("finished_at")))
        fact("load_regressions", len(result["regressions"]))
        for i, reg in enumerate(result["regressions"], 1):
            fact("load_regression_%d" % i, "%s p95 %d ms gegen %d ms (x%.1f)" % (reg["route"], reg["p95_ms"], reg["baseline_p95_ms"], reg["factor"]))
    else:
        lines.append("NICHT GEMESSEN: load_baseline (kein frueherer Lauf dieses Szenarios auf einem Vorfahren-Commit)")
    fact("load_verdict", result["verdict"])
    for i, reason in enumerate(result["reasons"], 1):
        fact("load_reason_%d" % i, reason)
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("raw")
    parser.add_argument("out_dir")
    parser.add_argument("--results-root")
    parser.add_argument("--repo")
    args = parser.parse_args(argv)
    try:
        with open(args.raw) as f:
            raw = json.load(f)
    except (OSError, ValueError) as e:
        print("Rohdaten unlesbar: %s" % e, file=sys.stderr)
        return 2
    result = build_result(raw, args.results_root, args.repo)
    os.makedirs(args.out_dir, exist_ok=True)
    with open(os.path.join(args.out_dir, "result.json"), "w") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)
    with open(os.path.join(args.out_dir, "facts.txt"), "w") as f:
        f.write(facts(result))
    with open(os.path.join(args.out_dir, "summary.txt"), "w") as f:
        f.write("commit=%s verdict=%s scenario=%s\n" % (result["commit"], result["verdict"], result["scenario_version"]))
    return 3 if result["verdict"] == "RED" else 0


if __name__ == "__main__":
    sys.exit(main())
