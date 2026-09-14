#!/usr/bin/env python3
"""Prepare reviewable workflow updates from a REST export; never changes the live instance."""
import argparse
import copy
import json
from pathlib import Path
import re

SOURCE = '/home/flo/Entwicklung/tools/autotrigger'
COMMIT = dict(mode='isolated', source='commit', install_npm=True)
WRITERS = {'47a410c1', 'd29a75fa', 'aad88aa3', 'c8e19e46', 'adac163d', 'a3357beb',
           '24f8fce0', '954a01bd', '8eaa2a16', '4b3d1bb8', '1e728887'}
IDS = WRITERS | {'ed839b57', 'caf831c2', 'e4225f6a', '86a0fcb5', '51697b90', '91815451',
                 'afc4c244', 'aa5c3e48', 'e39ec196', 'efe47b88', '58dfacc9', '76aa05c4',
                 '9eaa38fc', '2950bffc', 'c79fb6bf'}
AUDIT_ONLY = {'81a8232a', '97ffce72', '70287c7b', 'a5a6df86', '175deef1'}
IDS |= AUDIT_ONLY
FIELDS = {'name', 'description', 'icon', 'blocks', 'instructions', 'model', 'provider', 'effort',
          'metadata', 'defaultContext', 'tags', 'category'}
POLICY_NOTE = ('\n\nRun-Isolation: Arbeite ausschliesslich in {{ execution_path }}. '
               'Der Originalpfad {{ project_path }} identifiziert das Projekt und darf nicht beschrieben werden. '
               'Keine automatischen Merges, Pushes oder Wechsel auf main/master. '
               'Aenderungen bleiben auf dem Run-Branch zur expliziten Uebernahme. '
               'Bei einem fehlgeschlagenen technischen Check darf ein Report keinen Erfolg behaupten. '
               'Gepruefter Commit: {{ workspace_source_sha }}; Logs: {{ workspace_logs }}.')


def rewrite(value):
    if isinstance(value, dict):
        return {key: rewrite(item) for key, item in value.items()}
    if isinstance(value, list):
        return [rewrite(item) for item in value]
    if isinstance(value, str):
        # Supersede the interim shared QA checkout with the run-owned snapshot.
        value = re.sub(r'^WT=.*qa-worktree\.sh (?:prepare|path).*$',
                       'WT="{{ execution_path }}"', value, flags=re.MULTILINE)
        value = value.replace(SOURCE, '{{ execution_path }}').replace('{{ project_path }}', '{{ execution_path }}')
        value = value.replace('/tmp/ms_changed.txt', '{{ workspace_logs }}/ms_changed.txt')
        value = value.replace('/tmp/mvn-warnings.txt', '{{ workspace_logs }}/mvn-warnings.txt')
        value = re.sub(r'/tmp/mvn-(main|test)-audit\.log', r'{{ workspace_logs }}/mvn-\1-audit.log', value)
    return value


def check(name, command, category, timeout=1800):
    return {'type': 'shell', 'name': name, 'config': dict(description=name, command=command,
            checked_command=True, check_category=category, timeout_seconds=timeout, on_failure='continue')}


def platform_checks():
    return [check('backend_tests', 'cd "{{ execution_path }}/ai-automation-backend"\nmvn -q test', 'backend-test-failure'),
            check('frontend_build', 'cd "{{ execution_path }}/ai-automation-frontend"\nnpm run build', 'frontend-build-failure'),
            check('frontend_lint', 'cd "{{ execution_path }}/ai-automation-frontend"\nnpm run lint', 'frontend-lint-failure')]


def migrate(workflow):
    before = copy.deepcopy(workflow)
    result = copy.deepcopy(workflow)
    prefix = workflow['id'][:8]
    result['blocks'] = rewrite(result['blocks'])
    result['instructions'] = rewrite(result.get('instructions') or '') + POLICY_NOTE
    policy = dict(COMMIT)
    if prefix in WRITERS:
        policy.update(source='working-tree', branch=True)
    if prefix == '76aa05c4':
        policy['allow_working_tree_target'] = True
    result.setdefault('metadata', {})
    result['metadata'] = {**(result['metadata'] or {}), 'workspace': policy}
    if prefix == 'ed839b57':
        report = result['blocks'][2]
        report['config']['command'] = ('Analysiere die separat ausgefuehrten Checks. Uebernimm PASS/FAIL aus den '
            'technischen gate_status-Markern, nicht aus Vermutungen.\n'
            'Backend:\n{{ step_0_output }}\nFrontend Build:\n{{ step_1_output }}\n'
            'Frontend Lint:\n{{ step_2_output }}\nNenne Fehler und den geprueften Commit '
            '{{ workspace_source_sha }}. Keine eigenen Gate-Marker ausgeben.')
        result['blocks'] = platform_checks() + [report]
    elif prefix == 'caf831c2':
        result['blocks'] = platform_checks() + [check('dependency_tree',
            'cd "{{ execution_path }}/ai-automation-backend"\nmvn -q dependency:tree -DoutputType=dot',
            'backend-dependency-tree', 600)]
    elif prefix in {'47a410c1', 'a3357beb', 'adac163d'}:
        result['blocks'][2] = check('backend_tests',
            'cd "{{ execution_path }}/ai-automation-backend"\nmvn -q test', 'test-sync')
        # Highest technical gate severity is authoritative; no shell parsing of an AI summary.
        result['blocks'] = result['blocks'][:4]
    elif prefix == 'e4225f6a':
        original = result['blocks'][0]['config']['command']
        boundary = original.index("echo '  CODE-METRIKEN'")
        metrics = 'BASE="{{ execution_path }}"\n' + original[boundary:]
        result['blocks'][0]['config']['command'] = metrics
        result['blocks'][1]['config']['command'] = result['blocks'][1]['config']['command'].replace(
            '{{ previous_output }}', 'Backend: {{ step_0_output }}\nFrontend Build: {{ step_1_output }}\n'
            'Frontend Lint: {{ step_2_output }}\nMetriken: {{ step_3_output }}')
        result['blocks'] = platform_checks() + result['blocks'][:3]
    elif prefix == '86a0fcb5':
        result['blocks'][0] = check('compiler_warnings',
            'cd "{{ execution_path }}/ai-automation-backend"\nmvn clean test-compile -B '
            '-Dmaven.compiler.showWarnings=true -Dmaven.compiler.showDeprecation=true '
            '-Dmaven.compiler.compilerArgument=-Xlint:all', 'compiler-check')
    elif prefix == 'd29a75fa':
        for index in (0, 4, 7):
            result['blocks'][index] = check('backend_tests_' + str(index),
                'cd "{{ execution_path }}/ai-automation-backend"\nmvn -q test', 'clean-code-tests')
            result['blocks'][index]['config']['on_failure'] = 'stop'
        result['blocks'][0]['config']['command'] += (
            '\nfind src/main/java/com/automation -name "*.java" -exec wc -l {} + | sort -rn')
        result['blocks'][8]['config']['command'] = (
            'Erstelle einen Clean-Code-Report aus Analyse {{ step_1_output }}, '
            'Tests {{ step_4_output }}, Refactoring {{ step_6_output }} und Regression {{ step_7_output }}. '
            'Aenderungen bleiben zur Uebernahme im Run-Workspace; kein Merge wurde ausgefuehrt.')
    elif prefix in {'51697b90', '91815451', '8eaa2a16', '76aa05c4'}:
        pool_name = {'51697b90': 'mysuricates-build-gate', '91815451': 'mysuricates-build-gate',
                     '8eaa2a16': 'mysuricates-i18n-sync', '76aa05c4': 'master-commit-sanity-check'}[prefix]
        pool = Path(__file__).resolve().parents[2] / 'workflow-pool' / (pool_name + '.json')
        result['blocks'] = json.loads(pool.read_text())['blocks']
    elif prefix in {'c8e19e46', '24f8fce0'}:
        result['blocks'][0]['config']['command'] = (
            'cd "{{ execution_path }}"\ngit status --short\ngit log -1 --oneline')
        result['blocks'][4] = check('verify_fixes',
            'cd "{{ execution_path }}/ai-automation-backend"\nmvn -q clean test'
            if prefix == 'c8e19e46' else
            'cd "{{ execution_path }}/ai-automation-frontend"\nnpm run build\nnpm run lint', 'fix-verification')
        result['blocks'][5] = {'type': 'shell', 'config': {
            'command': 'git diff --stat\ngit log --oneline "{{ workspace_source_sha }}..HEAD"',
            'description': 'Run-Aenderungen zur manuellen Uebernahme'}}
        result['blocks'][6]['config'].update(options=['Review'],
            question='Aenderungen und technische Ergebnisse im Run-Workspace pruefen. '
                     'Review bestaetigt nur die Sichtung; Uebernahme und Push erfolgen separat.')
        result['blocks'][7]['config']['command'] = (
            'echo "Workspace zur Uebernahme: {{ execution_path }}"\ngit status --short')
    elif prefix in AUDIT_ONLY:
        pass  # Preserve security-audit diagnostics; only move their execution directory.
    else:
        # Preserve report/analysis steps and their indices, then independently verify the
        # resulting snapshot. Existing narrative markers cannot hide a failing final check.
        checks = platform_checks()
        if prefix in {'9eaa38fc', '2950bffc', 'c79fb6bf', '954a01bd'}: checks = checks[1:]
        if prefix == 'aad88aa3': checks = checks[:1]
        result['blocks'].extend(checks)
    for block in result["blocks"]:
        if block.get("config", {}).get("on_failure") == "abort":
            block["config"]["on_failure"] = "stop"
    ready = True
    return dict(id=workflow['id'], name=workflow['name'], ready=ready,
                expectedVersion=before.get('version'), before=before,
                update={k: v for k, v in result.items() if k in FIELDS})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    workflows = json.loads(args.export.read_text())
    proposals = [migrate(w) for w in workflows if w['id'][:8] in IDS]
    args.output.mkdir(parents=True, exist_ok=True)
    for proposal in proposals:
        (args.output / (proposal['id'] + '.json')).write_text(json.dumps(proposal, indent=2, ensure_ascii=False) + '\n')
    print(f'{len(proposals)} proposals; {sum(p["ready"] for p in proposals)} ready for validation')


if __name__ == '__main__':
    main()
