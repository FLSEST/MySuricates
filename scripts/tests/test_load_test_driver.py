#!/usr/bin/env python3
"""Tests for scripts/load-test/driver.py — route templates, Server-Timing and the run polling.

Run: python3 -m unittest discover -s scripts/tests -p 'test_load_test_driver.py'
"""
import http.server
import json
import os
import sys
import threading
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "load-test"))
import driver  # noqa: E402

WF = "0b5c7e3a-1111-4222-8333-944455556666"
RUN = "7d2e9f10-aaaa-4bbb-8ccc-dddddddddddd"


class TemplateTest(unittest.TestCase):

    def test_ids_become_placeholders_and_query_is_dropped(self):
        self.assertEqual(driver.template("/workflows/%s/runs/%s" % (WF, RUN)), "/workflows/{id}/runs/{runId}")
        self.assertEqual(driver.template("/todos/%s/status" % RUN), "/todos/{id}/status")
        self.assertEqual(driver.template("/executions?page=0&size=50"), "/executions")
        self.assertEqual(driver.template("/todos/count"), "/todos/count")


class RecorderTest(unittest.TestCase):

    def test_server_timing_header_is_split_into_wait_total_and_sql(self):
        rec = driver.Recorder()
        rec.phase = "load"
        rec.record("GET", "/workflows", 200, 57, 'wait;dur=12, work;dur=30, total;dur=42, sql;desc="n=2"', 5279, "read")
        entry = rec.requests[0]
        self.assertEqual((entry["route"], entry["srv_wait"], entry["srv_total"], entry["sql"], entry["phase"]),
                         ("GET /workflows", 12, 42, 2, "load"))

    def test_missing_header_leaves_server_figures_empty(self):
        rec = driver.Recorder()
        rec.record("DELETE", "/todos/%s" % RUN, 204, 5, "", 0, "write")
        self.assertIsNone(rec.requests[0]["srv_total"])
        self.assertIsNone(rec.requests[0]["sql"])


class HostLoadSampleTest(unittest.TestCase):

    def test_load_average_or_none(self):
        value = driver.sample_host_load()
        self.assertTrue(value is None or isinstance(value, float))
        original = driver.os.getloadavg

        def broken():
            raise OSError("nicht verfuegbar")
        driver.os.getloadavg = broken
        try:
            self.assertIsNone(driver.sample_host_load())
        finally:
            driver.os.getloadavg = original


class FakeApi(http.server.BaseHTTPRequestHandler):
    """execute returns RUNNING, the second poll of the run COMPLETED; without a key 401."""
    polls = 0

    def log_message(self, *args):
        pass

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Server-Timing", 'wait;dur=1, work;dur=2, total;dur=3, sql;desc="n=4"')
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)
        if self.headers.get("X-API-Key") != "k":
            return self.reply(401, {"error": "key"})
        self.reply(200, {"id": RUN, "status": "RUNNING"})

    def do_GET(self):
        FakeApi.polls += 1
        self.reply(200, {"id": RUN, "status": "COMPLETED" if FakeApi.polls >= 2 else "RUNNING"})


class StartAndWaitTest(unittest.TestCase):

    def setUp(self):
        FakeApi.polls = 0
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), FakeApi)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:%d/api/v1" % self.server.server_address[1]

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_polls_the_run_page_until_terminal(self):
        rec = driver.Recorder()
        result = driver.start_and_wait(driver.Client(self.base, "k", rec), WF, poll_ms=10)
        self.assertEqual(result["status"], "COMPLETED")
        self.assertIsNotNone(result["seconds"])
        self.assertEqual([r["route"] for r in rec.requests],
                         ["POST /workflows/{id}/execute", "GET /workflows/{id}/runs/{runId}", "GET /workflows/{id}/runs/{runId}"])
        self.assertEqual(rec.requests[0]["kind"], "write")
        self.assertEqual(rec.requests[1]["sql"], 4)

    def test_refused_start_is_reported_not_polled(self):
        rec = driver.Recorder()
        result = driver.start_and_wait(driver.Client(self.base, "falsch", rec), WF, poll_ms=10)
        self.assertEqual(result, {"id": None, "status": "START_FAILED_401", "seconds": None})
        self.assertEqual(len(rec.requests), 1)

    def test_unreachable_instance_records_status_zero(self):
        rec = driver.Recorder()
        client = driver.Client("http://127.0.0.1:9/api/v1", "k", rec)
        status, body = client.call("GET", "/workflows")
        self.assertEqual((status, body), (0, None))
        self.assertEqual(rec.requests[0]["status"], 0)


if __name__ == "__main__":
    unittest.main()
