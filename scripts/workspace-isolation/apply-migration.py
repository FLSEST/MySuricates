#!/usr/bin/env python3
"""Apply reviewed REST proposals, preserving workflow IDs, schedules and triggers."""
import argparse
from datetime import datetime, timezone
import json
import os
import time
from pathlib import Path
import urllib.request
import urllib.error


def normalized_blocks(blocks):
    return [dict(type=b.get("type"), name=b.get("name"), config=b.get("config") or {},
                 requiredCapabilities=b.get("requiredCapabilities") or [],
                 skillIds=b.get("skillIds") or []) for b in blocks]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('proposals', nargs='+', type=Path)
    parser.add_argument('--api-base', default='http://localhost:9090/api/v1')
    parser.add_argument('--expected-build', required=True, help='buildTimestamp of the validated runtime')
    parser.add_argument('--prefix', action='append', default=[])
    parser.add_argument('--backup-dir', required=True, type=Path)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    key = os.environ.get('MYSURICATES_API_KEY') or (Path.home() / '.mysuricates/api-key').read_text().strip()

    def api(path, body=None):
        request = urllib.request.Request(args.api_base + path,
            data=json.dumps(body).encode() if body is not None else None,
            headers={'X-API-Key': key, 'Content-Type': 'application/json'},
            method='PUT' if body is not None else 'GET')
        for attempt in range(7):
            try:
                with urllib.request.urlopen(request, timeout=30) as response:
                    return json.load(response)
            except urllib.error.HTTPError as error:
                if error.code != 429 or attempt == 6:
                    raise
                time.sleep(min(30, 2 ** attempt))

    info = api('/system/info')
    if info.get('runtimeMode') != 'jar' or info.get('buildTimestamp') != args.expected_build:
        raise SystemExit('Runtime does not match the validated JAR build; no changes made')
    proposals = []
    for folder in args.proposals:
        for path in folder.glob('*.json'):
            proposal = json.loads(path.read_text())
            if not proposal.get('ready'): continue
            if args.prefix and not any(proposal['id'].startswith(prefix) for prefix in args.prefix): continue
            proposals.append(proposal)
    priority = {'ed839b57': 0, '47a410c1': 1, 'e4225f6a': 2, 'caf831c2': 3}
    proposals.sort(key=lambda p: (priority.get(p['id'][:8], 4), p['id']))
    args.backup_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    # Preflight the entire selected set before the first PUT. Compare content, not just the
    # user-editable version label; execution/test timestamps may legitimately have changed.
    for proposal in proposals:
        current = api('/workflows/' + proposal['id'])
        changed = [key for key in proposal['update'] if current.get(key) != proposal['before'].get(key)]
        if changed:
            raise SystemExit(f'Workflow changed since review: {proposal["id"]}: {changed}; regenerate/review proposal')
        backup = args.backup_dir / (proposal['id'] + '.json')
        with backup.open('x') as output:
            json.dump(current, output, indent=2, ensure_ascii=False)
        backup.chmod(0o600)
    applied = []
    for proposal in proposals:
        if not args.apply:
            print('WOULD UPDATE', proposal['id'], proposal['name'])
            continue
        current = api('/workflows/' + proposal['id'])
        if any(current.get(key) != proposal['before'].get(key) for key in proposal['update']):
            raise SystemExit('Concurrent workflow edit detected; remaining updates stopped: ' + proposal['id'])
        payload = dict(proposal['update'], changeSource='api',
            changeMessage='TODO 77fc07e3: isolated run workspace and deterministic checks')
        result = api('/workflows/' + proposal['id'], payload)
        if result['id'] != proposal['id'] or normalized_blocks(result.get('blocks', [])) != normalized_blocks(payload['blocks']):
            raise SystemExit('Workflow update verification failed: ' + proposal['id'])
        applied.append({'id': result['id'], 'name': result['name'], 'at': datetime.now(timezone.utc).isoformat()})
        (args.backup_dir / 'applied.json').write_text(json.dumps(applied, indent=2, ensure_ascii=False))
        print('UPDATED', result['id'], result['name'])
    print(len(proposals), 'selected;', len(applied), 'updated')


if __name__ == '__main__': main()
