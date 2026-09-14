#!/usr/bin/env python3
"""Prepare the six CS2BIM migrations from their latest REST definitions."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re

spec = importlib.util.spec_from_file_location('migration', Path(__file__).with_name('prepare-migration.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)
IDS = {'47d94629', '47a44aa6', '48279673', '0bfed42c', '5a9c64b8', 'dcd1ba43'}
SOURCE = str(Path.home() / '.mysuricates/sources/cs2bim')
REVIEW = {'type': 'approval', 'config': {'question': 'Run-Branch und technische Ergebnisse prüfen. Übernahme und Push erfolgen separat.',
          'options': ['Review'], 'variable_name': 'review_decision', 'timeout_minutes': 120}}
RETAIN = {'type': 'shell', 'config': {'command': 'git status --short\ngit log --oneline "{{ workspace_source_sha }}..HEAD"\necho "Workspace zur Übernahme: {{ execution_path }}"'}}
SETUP = 'python3 -m venv .qa-venv\nsource .qa-venv/bin/activate\n'
VERIFY = SETUP + '''export PYTHONPATH=src
export CONFIG_PATH=tests/config/base_config.yml
python -m pip install -r requirements.txt
python -m pip install pytest flake8 mypy pip-audit
FAILED=0
run_check() {
  local label="$1"; shift
  if "$@" > "{{ workspace_logs }}/$label.log" 2>&1; then
    cat "{{ workspace_logs }}/$label.log"
  else
    local status=$?
    cat "{{ workspace_logs }}/$label.log"
    echo "FAIL: $label (exit $status)"
    FAILED=1
  fi
}
run_check dependency-resolution python -m pip install --dry-run -r requirements.txt
run_check security python -m pip_audit -r requirements.txt
run_check lint python -m flake8 src/ --max-line-length=120 --count
run_check types python -m mypy src/ --ignore-missing-imports
run_check tests python -m pytest tests/ -v --tb=short
exit "$FAILED"
'''


def rewrite(value):
    if isinstance(value, list): return [rewrite(v) for v in value]
    if isinstance(value, dict): return {k: rewrite(v) for k, v in value.items()}
    if not isinstance(value, str): return value
    value = value.replace('/tmp/cs2bim-ci-qa-venv', '{{ execution_path }}/.qa-venv')
    return value.replace('/tmp/cs2bim-ci', '{{ execution_path }}').replace('/tmp/cs2bim', '{{ execution_path }}')


def migrate(original):
    update = {k: copy.deepcopy(v) for k, v in original.items() if k in m.FIELDS}
    prefix = original['id'][:8]
    update['blocks'] = rewrite(update['blocks'])
    update['instructions'] = rewrite(update.get('instructions') or '') + m.POLICY_NOTE
    policy = dict(mode='isolated', source='working-tree' if prefix == '5a9c64b8' else 'commit',
                  source_path=SOURCE, branch=True, install_npm=False)
    update['metadata'] = {**(update.get('metadata') or {}), 'workspace': policy}
    blocks = update['blocks']
    if prefix in {'47d94629', '47a44aa6', '48279673'}:
        blocks[0] = m.check('source', 'git status --short\ngit log -1 --oneline', 'source')
        # Keep diagnostics and agent goals, but do not suppress preparation failures or truncate evidence.
        command = blocks[1]['config']['command']
        command = command.replace('python3 -m venv .qa-venv 2>/dev/null || true', 'python3 -m venv .qa-venv')
        command = re.sub(r'\s*\|\s*tail\s+-\d+', '', command)
        blocks[1]['config']['command'] = command
        blocks[4] = m.check('verify', VERIFY, 'cs2bim-verification')
        blocks[5] = copy.deepcopy(RETAIN)  # Keep failed fixes for review, never revert source history.
        blocks[6] = copy.deepcopy(REVIEW)
        blocks[7] = copy.deepcopy(RETAIN)
    elif prefix == '5a9c64b8':
        blocks[4] = m.check('generated_tests', SETUP + 'export PYTHONPATH=src\nexport CONFIG_PATH=tests/config/base_config.yml\n'
                             'python -m pip install -r requirements.txt pytest pytest-cov\npython -m pytest tests/ -v --tb=short', 'cs2bim-tests')
        blocks[6] = copy.deepcopy(RETAIN)
    elif prefix == 'dcd1ba43':
        blocks[0] = m.check('source', 'git status --short\ngit log -1 --oneline\ncat requirements.txt\n'
                            'find tests -name "*.py"\nfind .github -type f', 'cs2bim-source')
        blocks[2] = m.check('yaml', SETUP + 'python -m pip install PyYAML\npython - <<\'PYTHON\'\n'
            'from pathlib import Path\nimport yaml\n'
            'assert Path(".github/workflows/ci.yml").is_file(), "ci.yml missing"\n'
            'assert Path(".github/workflows/security.yml").is_file(), "security.yml missing"\n'
            'for path in Path(".github/workflows").glob("*.yml"):\n'
            '    with path.open() as stream: yaml.safe_load(stream)\n'
            '    print("VALID:", path)\nPYTHON', 'cs2bim-ci-yaml')
        blocks[3] = copy.deepcopy(RETAIN)
        blocks[4] = copy.deepcopy(REVIEW)
        blocks[5] = copy.deepcopy(RETAIN)
    else:
        # This workflow explicitly prepares an upstream merge, exclusively on its private run branch.
        update['instructions'] += '\nAusnahme für diesen Upstream-Sync: Merge von upstream/develop ausschließlich auf dem Run-Branch vorbereiten; keinen Ausgangsbranch ändern oder pushen.'
        blocks[0] = m.check('upstream_prepare', '''git remote add upstream https://github.com/idibau/cs2bim.git
git fetch upstream --no-tags
status=0
git merge upstream/develop --no-edit --no-ff --allow-unrelated-histories || status=$?
if [ "$status" -ne 0 ] && [ -z "$(git diff --name-only --diff-filter=U)" ]; then exit "$status"; fi
git diff --name-only --diff-filter=U
''', 'cs2bim-upstream')
        blocks[0]['config']['on_failure'] = 'stop'
        blocks[1]['config']['goal'] = 'Löse die tatsächlich vorhandenen Merge-Konflikte im isolierten Run-Branch und erstelle dort den Merge-Commit. Bei fehlenden Konflikten nur den Zustand prüfen; keinen Commit erzwingen.'
        blocks[1]['config']['instructions'] = ('Arbeite ausschließlich in {{ execution_path }}. Ermittle Konflikte mit git diff --name-only --diff-filter=U. '
            'Vergleiche Basis, lokale und Upstream-Version pro Datei; bewahre lokale Security-Fixes und Dependency-Pins. '
            'Keine fest angenommene Konfliktzahl. Commit nur nach vollständiger Konfliktlösung, kein Push und kein Wechsel des Branches.\n{{ previous_output }}')
        blocks[2] = m.check('verify', VERIFY, 'cs2bim-upstream-verification')
        blocks[3] = copy.deepcopy(REVIEW)
        blocks[4] = copy.deepcopy(RETAIN)
    for block in blocks:
        if block.get('config', {}).get('on_failure') == 'abort': block['config']['on_failure'] = 'stop'
    return dict(id=original['id'], name=original['name'], ready=True, before=original, update=update)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    proposals = [migrate(w) for w in json.loads(args.export.read_text()) if w['id'][:8] in IDS]
    for proposal in proposals:
        (args.output / (proposal['id'] + '.json')).write_text(json.dumps(proposal, indent=2, ensure_ascii=False) + '\n')
    print(len(proposals), 'CS2BIM proposals')


if __name__ == '__main__': main()
