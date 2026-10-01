#!/usr/bin/env python3
"""Conservative retention: delete only clean, successful, old run checkouts; keep logs/manifests."""
import argparse
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

# Never through a proxy from the environment (cf662c01): urllib sends localhost through http_proxy and
# all_proxy too — behind a proxy the calls failed, and the proxy got the X-API-Key.
LOCAL = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def data_home():
    """The instance's data directory — the rule of config/DataHome.java and scripts/lib/data-home.sh."""
    explicit = os.environ.get('AUTOMATION_DATA_HOME', '').strip()
    if explicit:
        return Path(explicit)
    legacy = Path.home() / '.autotrigger'
    return legacy if legacy.is_dir() else Path.home() / '.mysuricates'


def default_root():
    """automation.run-workspace.root: AUTOMATION_RUN_WORKSPACE_ROOT, else <data home>/workspaces (88781724)."""
    explicit = os.environ.get('AUTOMATION_RUN_WORKSPACE_ROOT', '').strip()
    return Path(explicit) if explicit else data_home() / 'workspaces'


def eligible(root, manifest_path, execution, cutoff):
    manifest = json.loads(manifest_path.read_text())
    run = manifest_path.parent
    repo = run / 'repo'
    if run.resolve() != run or repo.is_symlink() or not repo.is_dir():
        return False
    if run.parent.parent != root or manifest.get('workspacePath') != str(repo):
        return False
    if manifest.get('runId') != run.name or manifest.get('projectId') != run.parent.name:
        return False
    if manifest.get('branch') or manifest.get('sourceMode') != 'commit':
        return False  # Keep fixes and dirty-baseline snapshots for explicit review/export.
    if execution.get('id') != run.name or execution.get('status') != 'COMPLETED':
        return False
    completed = execution.get('completedAt')
    if not completed or datetime.fromisoformat(completed.replace('Z', '+00:00')) >= cutoff:
        return False
    saved = execution.get('metadata', {}).get('workspace', {})
    if saved != manifest:
        return False  # Ownership must be independently confirmed by the API.
    head = subprocess.run(['git', '-C', str(repo), 'rev-parse', 'HEAD'],
                          text=True, capture_output=True, timeout=30)
    if head.returncode != 0 or head.stdout.strip() != manifest.get('sourceCommit'):
        return False  # A committed fix can have a clean worktree; retain it too.
    result = subprocess.run(['git', '-C', str(repo), 'status', '--porcelain'],
                            text=True, capture_output=True, timeout=30)
    return result.returncode == 0 and not result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=default_root())
    parser.add_argument('--api-base', default='http://localhost:9090/api/v1')
    parser.add_argument('--retention-days', type=int, default=30)
    parser.add_argument('--apply', action='store_true', help='Actually remove eligible repo directories')
    args = parser.parse_args()
    if args.retention_days < 1:
        parser.error('retention-days must be positive')
    key = os.environ.get('MYSURICATES_API_KEY') or (data_home() / 'api-key').read_text().strip()
    root = args.root.expanduser().resolve()
    cutoff = datetime.now(timezone.utc) - timedelta(days=args.retention_days)
    for path in sorted(root.glob('*/*/workspace.json')):
        if path.is_symlink():
            continue
        try:
            request = urllib.request.Request(args.api_base + '/executions/' + path.parent.name,
                                             headers={'X-API-Key': key})
            with LOCAL.open(request, timeout=15) as response:
                execution = json.load(response)
            if not eligible(root, path, execution, cutoff):
                continue
            repo = path.parent / 'repo'
            print(('REMOVE ' if args.apply else 'WOULD REMOVE ') + str(repo))
            if args.apply:
                # The run is terminal and API-owned; retain its evidence beside repo/.
                shutil.rmtree(repo)
        except Exception as error:
            print(f'KEEP {path.parent.name}: {type(error).__name__}')


if __name__ == '__main__':
    main()
