#!/usr/bin/env python3
"""Installiert die Kette „TODO-Batch“ (TODO cc0eb3ba) in eine laufende Instanz – idempotent.

1. Beide Pool-Einträge installieren (`POST /pool/{id}/install`; eine vorhandene Installation wird an Ort und Stelle
   aktualisiert). 2. Kette „TODO-Batch“ anlegen oder aktualisieren – „Abarbeiten“ darf bis zu 20 h auf die Freigabe
   warten. 3. Zeitplan (täglich 01:30) anlegen; er bleibt aus, bis `--enable-schedule` gesetzt ist.
API-Schlüssel: `MYSURICATES_API_KEY` oder `~/.mysuricates/api-key`.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# Nie über einen Proxy aus der Umgebung (cf662c01): sonst bekäme der Proxy den X-API-Key.
LOCAL = urllib.request.build_opener(urllib.request.ProxyHandler({}))
POOL_IDS = ("mysuricates-todo-batch-einordnen", "mysuricates-todo-batch-abarbeiten")
CHAIN_NAME = "TODO-Batch"
CRON = "0 30 1 * * *"
WAIT_FOR_APPROVAL_MINUTES = 1200


def api(base: str, key: str, method: str, path: str, body=None):
    request = urllib.request.Request(base + path, method=method,
                                     data=json.dumps(body).encode() if body is not None else None,
                                     headers={"X-API-Key": key, "Content-Type": "application/json"})
    for attempt in range(7):
        try:
            with LOCAL.open(request, timeout=60) as response:
                raw = response.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == 6:
                raise SystemExit(f"{method} {path}: HTTP {error.code} {error.read()[:300]!r}")
            time.sleep(min(30, 2 ** attempt))
    return None


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api-base", default="http://localhost:9090/api/v1")
    parser.add_argument("--project", default="AutoPilot", help="Name des Projekts")
    parser.add_argument("--enable-schedule", action="store_true")
    args = parser.parse_args(argv)
    key = os.environ.get("MYSURICATES_API_KEY") or (Path.home() / ".mysuricates" / "api-key").read_text().strip()
    projects = [p for p in api(args.api_base, key, "GET", "/projects") if p.get("name") == args.project]
    if len(projects) != 1:
        raise SystemExit(f"Projekt '{args.project}' nicht eindeutig gefunden ({len(projects)} Treffer)")
    workflow_ids = []
    for pool_id in POOL_IDS:
        result = api(args.api_base, key, "POST", f"/pool/{pool_id}/install",
                     {"projectId": projects[0]["id"], "locale": "de"})
        workflow_ids.append(result["workflowId"])
        print(f"{'angelegt' if result.get('created') else 'aktualisiert'}: {result['workflowName']} "
              f"({result['workflowId'][:8]})")
    body = {"name": CHAIN_NAME, "icon": "🌙", "stopOnFailure": True,
            "description": "Nachts: TODOs einordnen, nacheinander abarbeiten, prüfen, reviewen, freigeben, als Branch übergeben",
            "entries": [{"workflowId": workflow_ids[0], "label": "Einordnen", "sortOrder": 0, "stopOnFailure": True},
                        {"workflowId": workflow_ids[1], "label": "Abarbeiten", "sortOrder": 1, "stopOnFailure": True,
                         "timeoutMinutes": WAIT_FOR_APPROVAL_MINUTES}]}
    existing = [c for c in api(args.api_base, key, "GET", "/chains") if c.get("name") == CHAIN_NAME]
    chain = (api(args.api_base, key, "PUT", f"/chains/{existing[0]['id']}", body) if existing
             else api(args.api_base, key, "POST", "/chains", body))
    print(f"Kette: {chain['name']} ({chain['id'][:8]})")
    schedules = api(args.api_base, key, "GET", f"/schedules/chain/{chain['id']}") or []
    schedule = schedules[0] if schedules else api(args.api_base, key, "POST", "/schedules", {
        "targetType": "CHAIN", "chainId": chain["id"], "cronExpression": CRON,
        "conflictBehavior": "SKIP", "description": "TODO-Batch nachts"})
    api(args.api_base, key, "POST", f"/schedules/{schedule['id']}/{'enable' if args.enable_schedule else 'disable'}")
    print(f"Zeitplan {CRON}: {'an' if args.enable_schedule else 'aus'} ({schedule['id'][:8]})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
