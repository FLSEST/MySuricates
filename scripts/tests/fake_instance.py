#!/usr/bin/env python3
"""Stand-ins with real HTTP for the script tests — Python standard library only.

  fake_instance.py api PORT KEY RUNS   a MySuricates instance: health and info answer 200 (info as a JAR
                                       build BUILD), the query for running workflow runs counts RUNS and
                                       a single run is RUNNING — both only with X-API-Key: KEY
  fake_instance.py proxy PORT LOG      a proxy that must not get anything (bb4f60a5): it writes every
                                       request it gets, CONNECT included, with its headers to LOG and
                                       answers 502

With FAKE_INSTANCE_LOG set, the instance writes each request to that file as "GET PATH key=ok|wrong|none"
— who got the request, and with which key (cf662c01). FAKE_INSTANCE_HOST is the IPv4 address to listen
on (default 127.0.0.1), e.g. the SERVER_ADDRESS an instance bound with --bind gets (3d002903).

Both accept every connection — a bare listen() without accept() fills its queue after two probes, and
the next /dev/tcp probe hangs until the kernel gives up. Each ends by itself after FAKE_INSTANCE_TTL
seconds (default 600), so an aborted test leaves nothing running.

Used by test-instance.sh, test-start-autotrigger-dev.sh, test-setup.sh and test_local_api_proxy.py.
"""
import http.server
import json
import os
import sys
import threading

BUILD = "fake-build"  # buildTimestamp in /system/info


class Api(http.server.BaseHTTPRequestHandler):
    key = ""
    runs = 0
    log = os.environ.get("FAKE_INSTANCE_LOG", "")

    def do_GET(self):
        given = self.headers.get("X-API-Key")
        if self.log:
            with open(self.log, "a") as f:
                f.write("%s %s key=%s\n" % (self.command, self.path,
                                            "none" if given is None else "ok" if given == self.key else "wrong"))
        if self.path == "/api/v1/system/health":
            return self.answer(200, {"status": "UP"})
        if self.path == "/api/v1/system/info":
            return self.answer(200, {"status": "UP", "runtimeMode": "jar", "buildTimestamp": BUILD})
        if not self.path.startswith("/api/v1/executions"):
            return self.answer(404, {})
        if given != self.key:
            return self.answer(401, {"error": "Unauthorized"})
        if self.path.startswith("/api/v1/executions/"):  # one run (workspace-isolation/prune-workspaces.py)
            return self.answer(200, {"id": self.path.split("?")[0].split("/")[4], "status": "RUNNING"})
        if not self.path.startswith("/api/v1/executions?"):
            return self.answer(404, {})
        if "status=RUNNING" not in self.path:
            return self.answer(400, {})
        # A workflow name that looks like the field must not change the count (scripts/lib/instance-runs.sh)
        content = [{"id": "r1", "workflowName": 'x "totalElements":99', "status": "RUNNING"}] if self.runs else []
        self.answer(200, {"content": content, "page": 0, "size": 1,
                          "totalElements": self.runs, "totalPages": self.runs})

    def answer(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


class Proxy(http.server.BaseHTTPRequestHandler):
    log = ""

    def record(self):
        with open(self.log, "a") as f:
            f.write(self.command + " " + self.path + "\n")
            for name, value in self.headers.items():
                f.write("  " + name + ": " + value + "\n")
        self.send_response(502)
        self.send_header("Content-Length", "0")
        self.end_headers()

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_CONNECT = record

    def log_message(self, *args):
        pass


def main(argv):
    if len(argv) == 5 and argv[1] == "api":
        Api.key, Api.runs = argv[3], int(argv[4])
        handler = Api
    elif len(argv) == 4 and argv[1] == "proxy":
        Proxy.log = argv[3]
        handler = Proxy
    else:
        sys.exit(__doc__)
    server = http.server.HTTPServer((os.environ.get("FAKE_INSTANCE_HOST", "127.0.0.1"), int(argv[2])), handler)
    lifetime = threading.Timer(float(os.environ.get("FAKE_INSTANCE_TTL", "600")), os._exit, args=(0,))
    lifetime.daemon = True
    lifetime.start()
    server.serve_forever()


if __name__ == "__main__":
    main(sys.argv)
