#!/usr/bin/env python3
"""Dependency vulnerabilities as counts - no third-party packages (TODO 0f6d23b2).

    dep_vuln_check.py npm   <npm-audit.json>
    dep_vuln_check.py maven <mvn-dependency-list.txt>

npm:   reads the output of `npm audit --json` and prints one number per severity,
       plus the package names with high/critical.
maven: reads the output of `mvn dependency:list -DoutputFile=...`, queries the
       coordinates in bulk at the OSV.dev batch API (only public
       group:artifact:version, no contents) and fetches the severity for the
       hits. With OSV_FIXTURE=<file> it reads a response instead:
       {"batch": <querybatch response>, "vulns": {id: <vuln>}} - for tests without network.

Output: `key: value` lines for the report. If the source is unreadable or OSV
unreachable, `<source>: unavailable (...)` is printed and the exit code is 2 -
a failed check must not pass as clean.
"""
import json
import os
import re
import sys
import urllib.request

OSV_BATCH = "https://api.osv.dev/v1/querybatch"
OSV_VULN = "https://api.osv.dev/v1/vulns/"
ANSI = re.compile(r"\x1b\[[0-9;]*m")
SEVERE = ("HIGH", "CRITICAL")
MAX_DETAILS = 150


def npm(path):
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        counts = data["metadata"]["vulnerabilities"]
    except (OSError, ValueError, KeyError, TypeError) as e:
        print(f"npm_audit: unavailable ({type(e).__name__})")
        return 2
    for sev in ("low", "moderate", "high", "critical"):
        print(f"npm_audit_{sev}: {int(counts.get(sev, 0))}")
    severe = sorted(name for name, v in (data.get("vulnerabilities") or {}).items()
                    if v.get("severity") in ("high", "critical"))
    for name in severe[:50]:
        print(f"- {name}")
    return 0


def maven_coordinates(path):
    coords = set()
    with open(path, encoding="utf-8") as f:
        for line in f:
            token = ANSI.sub("", line).strip().split(" ")[0]
            parts = token.split(":")
            # group:artifact:type[:classifier]:version:scope
            if len(parts) >= 5 and parts[0] and parts[1]:
                coords.add((parts[0], parts[1], parts[-2]))
    return sorted(coords)


def post_json(url, payload):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def get_json(url):
    with urllib.request.urlopen(url, timeout=30) as r:
        return json.load(r)


def maven(path):
    try:
        coords = maven_coordinates(path)
    except OSError as e:
        print(f"osv_maven: unavailable ({type(e).__name__})")
        return 2
    if not coords:
        print("osv_maven: unavailable (no coordinates in dependency list)")
        return 2
    fixture = os.environ.get("OSV_FIXTURE")
    try:
        if fixture:
            with open(fixture, encoding="utf-8") as f:
                fx = json.load(f)
            batch, details = fx["batch"], fx.get("vulns", {})
            fetch = lambda vid: details.get(vid, {})  # noqa: E731
        else:
            queries = [{"package": {"ecosystem": "Maven", "name": f"{g}:{a}"}, "version": v}
                       for g, a, v in coords]
            batch = post_json(OSV_BATCH, {"queries": queries})
            fetch = lambda vid: get_json(OSV_VULN + vid)  # noqa: E731
        results = batch["results"]
        hits = []
        fetched = 0
        for (g, a, v), res in zip(coords, results):
            ids = [x["id"] for x in (res or {}).get("vulns", [])]
            if not ids:
                continue
            sevs = []
            for vid in ids:
                sev = "UNKNOWN"
                if fetched < MAX_DETAILS:
                    fetched += 1
                    sev = ((fetch(vid) or {}).get("database_specific") or {}).get("severity") or "UNKNOWN"
                sevs.append((vid, str(sev).upper()))
            hits.append((f"{g}:{a}:{v}", sevs))
    except (OSError, ValueError, KeyError, TypeError) as e:
        print(f"osv_maven: unavailable ({type(e).__name__})")
        return 2
    severe = [h for h in hits if any(s in SEVERE or s == "UNKNOWN" for _, s in h[1])]
    print(f"osv_maven_packages_checked: {len(coords)}")
    print(f"osv_maven_vulnerable_packages: {len(hits)}")
    print(f"osv_maven_high_or_unknown_packages: {len(severe)}")
    for coord, sevs in hits[:50]:
        print(f"- {coord}: " + ", ".join(f"{vid} ({s})" for vid, s in sevs))
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] not in ("npm", "maven"):
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        sys.exit(64)
    sys.exit(npm(sys.argv[2]) if sys.argv[1] == "npm" else maven(sys.argv[2]))
