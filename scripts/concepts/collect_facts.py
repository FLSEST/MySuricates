#!/usr/bin/env python3
"""Collect anonymous aggregate evidence before starting the concept-page workflow.

Run from an operator session, never from a workflow block. Raw API responses and
credentials stay in memory; only explicitly selected counters are written.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import urllib.request

CATALOG = Path(__file__).with_name('catalog.json')

# Never through a proxy from the environment (cf662c01): urllib sends localhost through http_proxy and
# all_proxy too — behind a proxy the calls failed, and the proxy got the X-API-Key.
LOCAL = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def collect(base, key):
    started = dt.datetime.now(dt.timezone.utc).isoformat()

    def get(route):
        request = urllib.request.Request(base.rstrip('/') + route,
                                         headers={'X-API-Key': key})
        with LOCAL.open(request, timeout=60) as response:
            return json.load(response)

    workflows = get('/workflows')
    stats = get('/learning/platform-stats')
    dashboard = get('/learning/dashboard?days=30')
    todos = []
    page = 0
    while True:
        batch = get(f'/todos?size=200&page={page}')
        todos.extend(batch['content'])
        page += 1
        if page >= batch['totalPages']:
            break
    if not workflows or not todos:
        raise ValueError('Empty workflow/TODO inventory; refuse misleading evidence')
    typed_steps = sum(row['stepCount'] for row in dashboard['blockTypeUsage'])
    if not typed_steps:
        raise ValueError('No typed steps in the dashboard window')
    metrics = {}

    def metric(name, value, denominator, source, definition):
        metrics[name] = dict(value=value, denominator=denominator,
                             source=source, definition=definition)

    total = len(workflows)
    metric('workflows', total, None, '/workflows', 'Non-deleted workflow definitions in active projects')
    for name, predicate in (
        ('workflows_never_run', lambda w: w['runCount'] == 0),
        ('workflows_explicit_provider', lambda w: bool(w.get('provider'))),
        ('workflows_explicit_effort', lambda w: bool(w.get('effort'))),
        ('workflows_from_template', lambda w: bool(w.get('templateId'))),
        ('workflows_sealed', lambda w: bool(w.get('sealed'))),
    ):
        metric(name, sum(predicate(w) for w in workflows), total, '/workflows',
               'Share of API-visible workflow definitions; definitions are not executions')
    metrics['workflows_never_run']['definition'] = (
        'Zero retained runs among visible definitions; retention means this does not prove a workflow never ran')
    metric('retained_runs', dashboard['totalRuns'], None, '/learning/dashboard?days=30',
           'All retained runs, not limited to the 30-day window; not lifetime usage')
    metric('todos', len(todos), None, '/todos (all pages)', 'API-visible TODOs across statuses')
    metric('todos_without_resolution_state', sum(not t.get('resolutionState') for t in todos),
           len(todos), '/todos (all pages)',
           'No state on the optional manual linked-workflow resolve path; NOT evidence of missing verification')
    session_todos = [t for t in todos if t.get('source') == 'AI' and t.get('sourceAgent')]
    metric('ai_todos_with_session', sum(bool(t.get('sessionId')) and
                                     t['sessionId'] != 'unknown-session' for t in session_todos),
           len(session_todos), '/todos (all pages)',
           'Nonempty, non-placeholder sessionId among source=AI TODOs with sourceAgent; not proof of resumability')
    for row in dashboard['blockTypeUsage']:
        metric('steps_' + row['blockType'].replace('-', '_'), row['stepCount'], typed_steps,
               '/learning/dashboard?days=30',
               'Last 30 days: retained step records with blockType metadata; wrappers counted once, not inner calls')
    # The dashboard lists only block types that have steps in the window. A step count the
    # concept catalog relies on is therefore measured as zero instead of missing.
    for key in sorted({key for item in json.loads(CATALOG.read_text()) for key in item['metrics']}):
        if key.startswith('steps_') and key not in metrics:
            metric(key, 0, typed_steps, '/learning/dashboard?days=30',
                   'Last 30 days: no retained step record of this block type in the window')
    for field in ('activeSchedules', 'totalSchedules', 'activeWebhooks', 'activeTodoTriggers',
                  'activeCompletionTriggers', 'activeMemoryEntries'):
        metric(field, stats[field], None, '/learning/platform-stats', 'Current configured inventory; not effectiveness')
    return dict(schema=1, collected_started_at=started,
                collected_at=dt.datetime.now(dt.timezone.utc).isoformat(),
                scope='One local development installation; sequential REST reads, not an atomic snapshot.',
                limitations=[
                    'Retention, soft deletion and changing metadata prevent comparison with older lifetime totals.',
                    'Dashboard block shares cover 30 days and only typed records; foreach may include AI calls.',
                    'No causal benefit, failure rate or verification coverage can be inferred from inventory alone.',
                    'No names, paths, IDs, descriptions, outputs or credentials are exported.'
                ], metrics=metrics)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api-base', default='http://localhost:9090/api/v1')
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    key = os.environ.get('MYSURICATES_API_KEY')
    if not key:
        parser.error('Set MYSURICATES_API_KEY in the operator session')
    result = collect(args.api_base, key)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'Wrote {len(result["metrics"])} aggregate measurements to {args.output}')


if __name__ == '__main__':
    main()
