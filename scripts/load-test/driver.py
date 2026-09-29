#!/usr/bin/env python3
"""Seed und Last gegen eine Wegwerf-Instanz — nur Python-Standardbibliothek.

  driver.py --base URL --key KEY --scenario scenario.json --project-dir DIR --commit SHA --out raw.json
            [--ai-log FILE]

1. Seed (synthetisch, aus scenario.json): ein Projekt, N Shell-Workflows (davon einige mit grossem
   Step-Output, wie ihn step_results in echten Instanzen tragen), M abgeschlossene Laeufe, K TODOs.
   Kein KI-Aufruf: nur Shell-Bloecke.
2. Last wie echte Benutzer: Leser rufen die Seiten der Oberflaeche mit genau deren API-Aufrufen ab
   (Dashboard, Workflows, Laeufe, TODOs, Lauf-Detail, Projekte, dazu das Layout-Polling),
   Lauf-Starter starten Workflows und pollen die Lauf-Seite bis zum Ende, ein TODO-Schreiber legt an
   und schaltet Status. Erst eine Aufwaermphase, dann die gemessene Phase.
3. Jede Anfrage mit Route (Template, nie die ID), Status, Client-Zeit und dem Server-Timing-Header
   der Instanz (Wartezeit auf die DB-Connection, Arbeitszeit, SQL-Statements) nach raw.json.

Die Auswertung macht report.py. Der Treiber misst nur, er urteilt nicht.
"""
import argparse
import http.client
import json
import random
import re
import sys
import threading
import time
import urllib.parse
from datetime import datetime, timezone

TERMINAL = {"COMPLETED", "COMPLETED_WITH_ERRORS", "FAILED", "CANCELLED", "INTERRUPTED"}
TIMING = re.compile(r"(wait|work|total);dur=(\d+)")
SQL = re.compile(r'sql;desc="n=(\d+)"')

# Seiten der Oberflaeche mit den API-Aufrufen, die sie beim Oeffnen absetzt (Hooks im Frontend),
# und ihrem Gewicht in der Mischung. {wf} und {run} setzt der Leser aus dem Seed ein.
PAGES = {
    "dashboard": (2, ["/learning/dashboard", "/dashboard/layouts?page=0&size=1"]),
    "workflows": (3, ["/workflows", "/projects"]),
    "runs": (3, ["/executions?page=0&size=50", "/workflows", "/projects"]),
    "todos": (2, ["/todos?status=OPEN&status=IN_PROGRESS&page=0&size=30", "/todos/count", "/projects", "/workflows"]),
    "run_detail": (4, ["/workflows/{wf}", "/workflows/{wf}/runs/{run}"]),
    "projects": (1, ["/projects", "/projects/archived"]),
}
LAYOUT_POLL = "/executions/pending-input"


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def template(path):
    """Route-Template aus einem Pfad: IDs werden zu Platzhaltern, die Query faellt weg."""
    parts = urllib.parse.urlsplit(path).path.split("/")
    out = []
    for i, part in enumerate(parts):
        if re.fullmatch(r"[0-9a-f]{8}-[0-9a-f-]{27,}", part):
            out.append("{runId}" if i > 0 and parts[i - 1] == "runs" else "{id}")
        else:
            out.append(part)
    return "/".join(out)


class Client:
    """Eine Keep-Alive-Verbindung je Thread, wie ein Browser-Tab."""

    def __init__(self, base, key, recorder):
        url = urllib.parse.urlsplit(base)
        self.host, self.port = url.hostname, url.port or 80
        self.prefix = url.path.rstrip("/")
        self.key = key
        self.recorder = recorder
        self.conn = None

    def call(self, method, path, body=None, kind="read", record=True):
        payload = None if body is None else json.dumps(body).encode()
        headers = {"X-API-Key": self.key, "Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        start = time.perf_counter()
        status, data, timing = 0, b"", ""
        for attempt in (1, 2):
            try:
                if self.conn is None:
                    self.conn = http.client.HTTPConnection(self.host, self.port, timeout=60)
                self.conn.request(method, self.prefix + path, body=payload, headers=headers)
                resp = self.conn.getresponse()
                data = resp.read()
                status, timing = resp.status, resp.getheader("Server-Timing") or ""
                break
            except (http.client.HTTPException, OSError):
                self.conn = None
                if attempt == 2:
                    status = 0
        ms = int((time.perf_counter() - start) * 1000)
        if record:
            self.recorder.record(method, path, status, ms, timing, len(data), kind)
        try:
            return status, json.loads(data) if data else None
        except ValueError:
            return status, None


class Recorder:
    def __init__(self):
        self.lock = threading.Lock()
        self.requests = []
        self.phase = "seed"

    def record(self, method, path, status, ms, timing, size, kind):
        values = dict(TIMING.findall(timing))
        sql = SQL.search(timing)
        entry = {"route": "%s %s" % (method, template(path)), "status": status, "ms": ms, "kind": kind,
                 "srv_total": int(values["total"]) if "total" in values else None,
                 "srv_wait": int(values["wait"]) if "wait" in values else None,
                 "sql": int(sql.group(1)) if sql else None, "bytes": size, "phase": self.phase}
        with self.lock:
            self.requests.append(entry)


def heavy_command(size):
    return "head -c %d /dev/zero | tr '\\0' 'x'; echo" % size


def seed(client, scenario, project_dir, log):
    s = scenario["seed"]
    status, project = client.call("POST", "/projects", {"name": "Lasttest", "type": "CODE", "path": project_dir}, "write")
    if status != 201 or not project:
        raise SystemExit("Seed: Projekt nicht angelegt (HTTP %s)" % status)
    workflows, heavy = [], []
    for i in range(s["workflows"]):
        is_heavy = i < s["heavy_workflows"]
        command = heavy_command(s["heavy_output_bytes"]) if is_heavy else "echo start; sleep 1; echo fertig"
        body = {"projectId": project["id"], "name": "Lasttest %02d%s" % (i, " schwer" if is_heavy else ""),
                "blocks": [{"type": "shell", "name": "s1", "config": {"command": command, "timeout_seconds": 60}}]}
        status, wf = client.call("POST", "/workflows", body, "write")
        if status != 201 or not wf:
            raise SystemExit("Seed: Workflow %d nicht angelegt (HTTP %s)" % (i, status))
        (heavy if is_heavy else workflows).append(wf["id"])
    log("Seed: Projekt + %d Workflows" % s["workflows"])

    finished = []
    lock = threading.Lock()
    todo = list(range(s["runs"]))

    def seed_runner():
        own = Client("http://%s:%d%s" % (client.host, client.port, client.prefix), client.key, client.recorder)
        while True:
            with lock:
                if not todo:
                    return
                n = todo.pop()
            wf = heavy[n % len(heavy)] if heavy and n % 5 == 0 else workflows[n % len(workflows)]
            run = start_and_wait(own, wf, poll_ms=500)
            with lock:
                finished.append((wf, run))

    threads = [threading.Thread(target=seed_runner) for _ in range(s["run_concurrency"])]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    failed = [r for _, r in finished if r["status"] != "COMPLETED"]
    if failed:
        raise SystemExit("Seed: %d von %d Laeufen nicht COMPLETED (%s)" % (len(failed), len(finished), failed[0]["status"]))
    log("Seed: %d Laeufe" % len(finished))

    todo_ids = []
    for i in range(s["todos"]):
        status, item = client.call("POST", "/todos", {"title": "Lasttest-TODO %03d" % i, "projectId": project["id"]}, "write")
        if status in (200, 201) and item:
            todo_ids.append(item["id"])
            if i % 3 == 0:
                client.call("PATCH", "/todos/%s/status" % item["id"], {"status": "DONE", "sessionId": None}, "write")
    log("Seed: %d TODOs" % len(todo_ids))
    runs = [(wf, r["id"]) for wf, r in finished if r.get("id")]
    return {"project": project["id"], "workflows": workflows, "heavy": heavy, "runs": runs,
            "counts": {"workflows": s["workflows"], "heavy_workflows": len(heavy), "runs": len(finished),
                       "todos": len(todo_ids)}}


def start_and_wait(client, wf, poll_ms, deadline_s=300):
    start = time.monotonic()
    status, run = client.call("POST", "/workflows/%s/execute" % wf, {"context": {}}, "write")
    if status != 200 or not run or "id" not in run:
        return {"id": None, "status": "START_FAILED_%s" % status, "seconds": None}
    run_id, state = run["id"], run.get("status")
    while state not in TERMINAL and time.monotonic() - start < deadline_s:
        time.sleep(poll_ms / 1000.0)
        status, detail = client.call("GET", "/workflows/%s/runs/%s" % (wf, run_id))
        if status == 200 and detail:
            state = detail.get("status")
    seconds = round(time.monotonic() - start, 2) if state in TERMINAL else None
    return {"id": run_id, "status": state, "seconds": seconds}


def load(client_factory, scenario, seeded, recorder, log):
    cfg = scenario["load"]
    stop = threading.Event()
    runs, heap = [], []
    runs_lock = threading.Lock()
    pages = [(name, weight, paths) for name, (weight, paths) in PAGES.items()]
    weights = [w for _, w, _ in pages]
    known_runs = list(seeded["runs"])

    def reader(seed_value):
        rnd = random.Random(seed_value)
        c = client_factory()
        while not stop.is_set():
            name, _, paths = rnd.choices(pages, weights=weights)[0]
            wf, run = rnd.choice(known_runs)
            for path in paths:
                c.call("GET", path.format(wf=wf, run=run))
            c.call("GET", LAYOUT_POLL)
            stop.wait(cfg["reader_think_ms"] / 1000.0)

    def run_starter(seed_value):
        rnd = random.Random(seed_value)
        c = client_factory()
        while not stop.is_set():
            pool = seeded["heavy"] if seeded["heavy"] and rnd.random() < 0.2 else seeded["workflows"]
            wf = rnd.choice(pool)
            measured = recorder.phase == "load"
            result = start_and_wait(c, wf, cfg["run_poll_ms"])
            if measured:
                with runs_lock:
                    runs.append(result)
            if result.get("id"):
                known_runs.append((wf, result["id"]))

    def todo_writer():
        c = client_factory()
        n = 0
        while not stop.is_set():
            status, item = c.call("POST", "/todos", {"title": "Last-TODO %05d" % n, "projectId": seeded["project"]}, "write")
            n += 1
            if status in (200, 201) and item:
                for state in ("IN_PROGRESS", "DONE"):
                    c.call("PATCH", "/todos/%s/status" % item["id"], {"status": state, "sessionId": None}, "write")
            stop.wait(cfg["todo_writer_pause_ms"] / 1000.0)

    def heap_sampler():
        c = client_factory()
        while not stop.is_set():
            status, body = c.call("GET", "/system/jvm-memory", record=False)
            if recorder.phase == "load" and status == 200 and isinstance(body, dict):
                value = body.get("jvm_heap_used_percent")
                heap.append(float(value) if value is not None else None)
            stop.wait(cfg["heap_sample_s"])

    threads = [threading.Thread(target=reader, args=(i,)) for i in range(cfg["readers"])]
    threads += [threading.Thread(target=run_starter, args=(100 + i,)) for i in range(cfg["run_starters"])]
    threads += [threading.Thread(target=todo_writer) for _ in range(cfg["todo_writers"])]
    threads += [threading.Thread(target=heap_sampler)]
    recorder.phase = "warmup"
    for t in threads:
        t.daemon = True
        t.start()
    log("Aufwaermen %d s" % cfg["warmup_s"])
    time.sleep(cfg["warmup_s"])
    recorder.phase = "load"
    load_start = time.time()
    log("Last %d s" % cfg["duration_s"])
    time.sleep(cfg["duration_s"])
    load_end = time.time()
    recorder.phase = "drain"
    stop.set()
    # Lauf-Starter pollen ihren letzten Lauf zu Ende; laenger als das Lauf-Limit wartet niemand.
    for t in threads:
        t.join(timeout=330)
    return load_start, load_end, runs, heap


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--project-dir", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--ai-log")
    args = parser.parse_args(argv)

    def log(message):
        print("[load-test] %s" % message, flush=True)

    with open(args.scenario) as f:
        scenario = json.load(f)
    recorder = Recorder()
    factory = lambda: Client(args.base, args.key, recorder)  # noqa: E731
    started_at = now_iso()
    seeded = seed(factory(), scenario, args.project_dir, log)
    load_start, load_end, runs, heap = load(factory, scenario, seeded, recorder, log)
    ai_calls = 0
    if args.ai_log:
        try:
            with open(args.ai_log) as f:
                ai_calls = sum(1 for line in f if line.strip())
        except OSError:
            ai_calls = 0
    raw = {"commit": args.commit, "scenario": scenario, "seed": seeded["counts"],
           "started_at": started_at, "finished_at": now_iso(), "load_start": load_start, "load_end": load_end,
           "requests": recorder.requests, "runs": runs, "heap": heap, "ai_cli_calls": ai_calls}
    with open(args.out, "w") as f:
        json.dump(raw, f)
    measured = sum(1 for r in recorder.requests if r["phase"] == "load")
    log("%d Anfragen gemessen, %d Laeufe in der Lastphase" % (measured, len(runs)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
