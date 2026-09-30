#!/usr/bin/env python3
"""Tests fuer scripts/load-test/report.py — Kennzahlen, Verdict, Baseline-Wahl und Faktenblatt.

Run: python3 -m unittest discover -s scripts/tests -p 'test_load_test_report.py'
"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "load-test"))
import report  # noqa: E402



def read(path):
    with open(path) as f:
        return f.read()


SCENARIO = json.loads(read(os.path.join(HERE, "..", "load-test", "scenario.json")))


def req(route, ms, status=200, kind="read", wait=0, total=None, sql=2, size=100, phase="load"):
    return {"route": route, "ms": ms, "status": status, "kind": kind, "srv_wait": wait,
            "srv_total": ms if total is None else total, "sql": sql, "bytes": size, "phase": phase}


def raw(requests, runs=None, heap=None, commit="c" * 40, ai=0, host_load=None, cpus=8):
    return {"commit": commit, "scenario": SCENARIO, "load_start": 0, "load_end": 100,
            "started_at": "2026-09-29T10:00:00Z", "finished_at": "2026-09-29T10:03:00Z",
            "requests": requests, "runs": runs or [], "heap": heap or [40.0], "ai_cli_calls": ai,
            "seed": {"workflows": 30}, "host_load": [2.0, 2.4, 3.0] if host_load is None else host_load,
            "cpus": cpus}


def many(route, ms, n=40, **kw):
    return [req(route, ms, **kw) for _ in range(n)]


class StatsTest(unittest.TestCase):

    def test_nearest_rank_percentiles(self):
        values = sorted(range(1, 101))
        self.assertEqual(report.percentile(values, 50), 50)
        self.assertEqual(report.percentile(values, 95), 95)
        self.assertEqual(report.percentile([], 95), 0)

    def test_warmup_requests_are_not_measured(self):
        result = report.build_result(raw(many("GET /workflows", 10) + many("GET /workflows", 9000, phase="warmup")))
        self.assertEqual(result["requests"], 40)
        self.assertEqual(result["routes"]["GET /workflows"]["p95_ms"], 10)

    def test_wait_share_is_summed_server_time_not_mean_of_shares(self):
        # 1 langsamer Request, der fast nur wartet, gegen 39 schnelle ohne Warten: der Anteil ist
        # Summe Warten / Summe Gesamtzeit, damit zaehlt die langsame Anfrage mit ihrem Gewicht.
        reqs = many("GET /todos", 10, n=39) + [req("GET /todos", 1000, wait=900)]
        stats = report.route_stats(reqs)
        self.assertAlmostEqual(stats["GET /todos"]["wait_share"], round(900 / 1390, 4))

    def test_errors_do_not_count_into_server_figures(self):
        stats = report.route_stats([req("GET /x", 10, sql=4), req("GET /x", 10, status=500, sql=99)])
        self.assertEqual(stats["GET /x"]["errors"], 1)
        self.assertEqual(stats["GET /x"]["sql_max"], 4)


class VerdictTest(unittest.TestCase):

    def test_quiet_run_is_green(self):
        result = report.build_result(raw(many("GET /workflows", 80)))
        self.assertEqual(result["verdict"], "GREEN")
        self.assertEqual(result["reasons"], [])

    def test_error_rate_over_one_percent_is_red(self):
        reqs = many("GET /workflows", 20, n=97) + [req("GET /workflows", 20, status=500)] * 3
        self.assertEqual(report.build_result(raw(reqs))["verdict"], "RED")

    def test_slow_read_route_is_yellow_and_unusable_is_red(self):
        self.assertEqual(report.build_result(raw(many("GET /workflows", 1500)))["verdict"], "YELLOW")
        self.assertEqual(report.build_result(raw(many("GET /workflows", 6000)))["verdict"], "RED")

    def test_slow_write_below_red_is_not_a_finding(self):
        self.assertEqual(report.build_result(raw(many("POST /todos", 1500, kind="write")))["verdict"], "GREEN")

    def test_few_samples_are_never_judged(self):
        self.assertEqual(report.build_result(raw(many("GET /export/bpmn", 9000, n=3)))["verdict"], "GREEN")

    def test_waiting_alone_is_the_architecture_not_a_finding(self):
        # pool-size 1 unter Last: hoher Wartezeit-Anteil bei kurzen Zeiten ist die Bauart
        result = report.build_result(raw(many("GET /executions", 400, wait=300)))
        self.assertEqual(result["verdict"], "GREEN")
        self.assertEqual(result["wait_share"], 0.75)

    def test_waiting_explains_a_slow_route(self):
        result = report.build_result(raw(many("GET /executions", 1600, wait=1200)))
        self.assertEqual(result["verdict"], "YELLOW")
        self.assertEqual(result["reasons"][0], "GET /executions p95 1600 ms, davon 75 % Warten auf die DB-Connection")

    def test_missing_server_timing_is_stated(self):
        facts = report.facts(report.build_result(raw([req("GET /x", 10, total=None) for _ in range(3)])))
        self.assertIn("FAKT load_wait_share_percent: 0.0", facts)
        bare = [dict(req("GET /x", 10), srv_total=None, srv_wait=None) for _ in range(3)]
        self.assertIn("NICHT GEMESSEN: load_wait_share_percent", report.facts(report.build_result(raw(bare))))

    def test_failed_run_is_red_and_unfinished_run_yellow(self):
        failed = report.build_result(raw(many("GET /x", 10), runs=[{"status": "FAILED", "seconds": 3}]))
        self.assertEqual(failed["verdict"], "RED")
        open_run = report.build_result(raw(many("GET /x", 10), runs=[{"status": "RUNNING", "seconds": None}]))
        self.assertEqual(open_run["verdict"], "YELLOW")

    def test_ai_cli_call_makes_the_run_red(self):
        result = report.build_result(raw(many("GET /x", 10), ai=2))
        self.assertEqual(result["verdict"], "RED")
        self.assertIn("KI-CLI", result["reasons"][0])

    def test_no_requests_is_red(self):
        self.assertEqual(report.build_result(raw([]))["verdict"], "RED")


class HostLoadTest(unittest.TestCase):

    def test_quiet_host_is_stated_and_not_judged(self):
        result = report.build_result(raw(many("GET /x", 10)))
        self.assertEqual(result["host_load"], {"cpus": 8, "median_per_cpu": 0.3, "max_per_cpu": 0.38})
        self.assertEqual(result["verdict"], "GREEN")
        self.assertIn("FAKT load_host_load: Median 0.30 | Max 0.38 je CPU (8 CPUs", report.facts(result))

    def test_busy_host_asks_for_a_repeat_instead_of_reporting_regressions(self):
        busy = report.build_result(raw(many("GET /x", 10), host_load=[9.0, 12.0, 14.0]))
        self.assertEqual(busy["verdict"], "YELLOW")
        self.assertIn("Messung wiederholen", busy["reasons"][0])

    def test_missing_load_average_is_stated(self):
        result = report.build_result(raw(many("GET /x", 10), host_load=[None, None]))
        self.assertIsNone(result["host_load"])
        self.assertIn("NICHT GEMESSEN: load_host_load", report.facts(result))


class BaselineTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = os.path.join(self.tmp.name, "repo")
        self.results = os.path.join(self.tmp.name, "results")
        os.makedirs(self.results)
        git = lambda *a: subprocess.run(["git", "-C", self.repo, *a], check=True, capture_output=True, text=True).stdout.strip()
        os.makedirs(self.repo)
        subprocess.run(["git", "init", "-q", self.repo], check=True)
        git("config", "user.email", "t@example.com"); git("config", "user.name", "T")
        self.commits = []
        for i in range(4):
            with open(os.path.join(self.repo, "f"), "w") as f:
                f.write(str(i))
            git("add", "f"); git("commit", "-qm", "c%d" % i)
            self.commits.append(git("rev-parse", "HEAD"))
        git("tag", "-a", "v0.1.0", "-m", "r", self.commits[0])
        self.git = git

    def tearDown(self):
        self.tmp.cleanup()

    def store(self, name, commit, p95, finished, verdict="GREEN", tag=None, version=None):
        path = os.path.join(self.results, name)
        os.makedirs(path)
        with open(os.path.join(path, "result.json"), "w") as f:
            json.dump({"commit": commit, "tag": tag, "scenario_version": version or SCENARIO["version"],
                       "finished_at": finished, "verdict": verdict,
                       "routes": {"GET /workflows": {"n": 100, "p95_ms": p95}}}, f)

    def test_tagged_release_beats_a_newer_intermediate_run(self):
        self.store("a", self.commits[0], 100, "2026-09-01", tag="v0.1.0")
        self.store("b", self.commits[2], 400, "2026-09-20")
        result = report.build_result(raw(many("GET /workflows", 160, n=100), commit=self.commits[3]), self.results, self.repo)
        self.assertEqual(result["baseline"]["commit"], self.commits[0])
        self.assertEqual(result["regressions"][0]["factor"], 1.6)
        self.assertEqual(result["verdict"], "YELLOW")
        self.assertIn("gegen 100 ms in der Baseline", result["reasons"][0])

    def test_busy_host_lists_regressions_but_does_not_count_them(self):
        self.store("a", self.commits[0], 100, "2026-09-01", tag="v0.1.0")
        result = report.build_result(raw(many("GET /workflows", 160, n=100), commit=self.commits[3], host_load=[16.0]),
                                     self.results, self.repo)
        self.assertEqual(len(result["regressions"]), 1)
        self.assertEqual(len(result["reasons"]), 1)
        self.assertIn("Messung wiederholen", result["reasons"][0])
        self.assertIn("FAKT load_regression_1: GET /workflows", report.facts(result))

    def test_only_ancestors_same_scenario_and_not_red(self):
        self.store("other-version", self.commits[1], 10, "2026-09-10", version=999)
        self.store("red", self.commits[1], 10, "2026-09-11", verdict="RED")
        self.store("same-commit", self.commits[3], 10, "2026-09-12")
        result = report.build_result(raw(many("GET /workflows", 160, n=100), commit=self.commits[3]), self.results, self.repo)
        self.assertIsNone(result["baseline"])
        self.assertEqual(result["regressions"], [])

    def test_descendant_is_no_baseline(self):
        self.store("future", self.commits[3], 10, "2026-09-12")
        result = report.build_result(raw(many("GET /workflows", 160, n=100), commit=self.commits[1]), self.results, self.repo)
        self.assertIsNone(result["baseline"])

    def test_tag_of_the_measured_commit_is_recorded(self):
        result = report.build_result(raw(many("GET /x", 10), commit=self.commits[0]), self.results, self.repo)
        self.assertEqual(result["tag"], "v0.1.0")


class OutputTest(unittest.TestCase):

    def test_facts_and_summary_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            raw_path = os.path.join(tmp, "raw.json")
            with open(raw_path, "w") as f:
                json.dump(raw(many("GET /workflows", 80) + many("POST /todos", 20, kind="write"),
                              runs=[{"status": "COMPLETED", "seconds": 2.5}]), f)
            out = os.path.join(tmp, "out")
            self.assertEqual(report.main([raw_path, out]), 0)
            facts = read(os.path.join(out, "facts.txt"))
            self.assertIn("FAKT load_verdict: GREEN\n", facts)
            self.assertIn("FAKT load_route: GET /workflows | read | n 40 | Fehler 0 | p50 80 | p95 80 | p99 80 | max 80 ms | Wartezeit 0.0 % | SQL 2.0 (max 2) | Antwort 100 B\n", facts)
            self.assertIn("FAKT load_runs: 1 gestartet, 1 COMPLETED, 0 andere, 0 unfertig\n", facts)
            self.assertIn("NICHT GEMESSEN: load_baseline", facts)
            self.assertEqual(read(os.path.join(out, "summary.txt")),
                             "commit=%s verdict=GREEN scenario=%d\n" % ("c" * 40, SCENARIO["version"]))
            self.assertEqual(json.loads(read(os.path.join(out, "result.json")))["verdict"], "GREEN")

    def test_red_exits_with_three_and_broken_input_with_two(self):
        with tempfile.TemporaryDirectory() as tmp:
            raw_path = os.path.join(tmp, "raw.json")
            with open(raw_path, "w") as f:
                json.dump(raw([]), f)
            self.assertEqual(report.main([raw_path, os.path.join(tmp, "out")]), 3)
            with open(raw_path, "w") as f:
                f.write("{kaputt")
            self.assertEqual(report.main([raw_path, os.path.join(tmp, "out2")]), 2)


if __name__ == "__main__":
    unittest.main()
