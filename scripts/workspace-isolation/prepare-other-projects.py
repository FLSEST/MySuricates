#!/usr/bin/env python3
"""Prepare external-project workspace migrations using the established local Git sources."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
import re

spec = importlib.util.spec_from_file_location('migration', Path(__file__).with_name('prepare-migration.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

SOURCES = {
    '16ca3b62': str(Path.home() / 'Entwicklung/tools/swiss_job_automation'),
    'fc22eb24': str(Path.home() / 'Entwicklung/tools/swiss_job_automation'),
    '2d1510a0': str(Path.home() / 'Entwicklung/tools/swiss_job_automation'),
    'ed43db60': str(Path.home() / 'Entwicklung/Test-externe-Anbieter/Mainzelliste/mainzelliste'),
    '1f983bd4': str(Path.home() / 'Entwicklung/Test-externe-Anbieter/Mainzelliste/mainzelliste'),
    '24f76503': str(Path.home() / 'Entwicklung/Test-externe-Anbieter/Mainzelliste/mainzelliste'),
    '52d9877a': str(Path.home() / 'Entwicklung/IdeaProjects/treasure_hunt_web'),
    '6d8628b8': str(Path.home() / 'Entwicklung/IdeaProjects/treasure_hunt_web'),
    '2ce37430': str(Path.home() / 'Entwicklung/IdeaProjects/treasure_hunt_web'),
    '9f9b1b78': str(Path.home() / 'Entwicklung/IdeaProjects/treasure_hunt_web'),
    '5bcced92': str(Path.home() / 'Entwicklung/IdeaProjects/treasure_hunt_app'),
    '221fa01c': str(Path.home() / 'Innofuse/{{ repo }}'),
    '083d4648': str(Path.home() / 'Entwicklung/IdeaProjects/treasure_hunt_app'),
    '6f8ff410': str(Path.home() / 'Entwicklung/IdeaProjects/treasure_hunt_backend'),
    '65aafc3d': str(Path.home() / 'IdeaProjects/LeviPapaEins'),
}
cs_spec = importlib.util.spec_from_file_location('cs2bim', Path(__file__).with_name('prepare-cs2bim.py'))
cs = importlib.util.module_from_spec(cs_spec)
cs_spec.loader.exec_module(cs)
WRITERS = {'ed43db60', '1f983bd4', '24f76503', '221fa01c'}


def rewrite(value, source):
    if isinstance(value, list): return [rewrite(v, source) for v in value]
    if isinstance(value, dict): return {k: rewrite(v, source) for k, v in value.items()}
    if not isinstance(value, str): return value
    value = value.replace(source.rstrip('/'), '{{ execution_path }}').replace('{{ project_path }}', '{{ execution_path }}')
    for name in ['cv_config_run.json', 'mainzelliste-bulk-test-sync.env', 'mainzelliste-targeted-test-sync.env']:
        value = value.replace('/tmp/' + name, '{{ workspace_logs }}/' + name)
    value = value.replace('JOB_ARCHIVE_DIR=' + str(Path.home() / 'dokumente'), 'JOB_ARCHIVE_DIR="{{ workspace_logs }}/archive"')
    value = value.replace('$HOME/dokumente/CVs_', '{{ workspace_logs }}/archive/CVs_')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('export', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    count = 0
    for original in json.loads(args.export.read_text()):
        prefix = original['id'][:8]
        if prefix in cs.IDS:
            proposal = cs.migrate(original)
        elif prefix in SOURCES:
            source = SOURCES[prefix]
            update = {k: copy.deepcopy(v) for k, v in original.items() if k in m.FIELDS}
            update['blocks'] = rewrite(update['blocks'], source)
            update['instructions'] = rewrite(update.get('instructions') or '', source) + m.POLICY_NOTE
            policy = dict(mode='isolated', source='working-tree' if prefix in WRITERS else 'commit',
                          source_path=source, branch=prefix in WRITERS, install_npm=True)
            update['metadata'] = {**(update.get('metadata') or {}), 'workspace': policy}
            blocks = update['blocks']
            if prefix == '6f8ff410':
                policy['install_npm'] = False
                blocks[:] = [m.check('backend_build', 'mvn -q clean package -DskipTests', 'backend-build')]
                blocks[0]['config']['on_failure'] = 'stop'
            elif prefix in {'16ca3b62', 'fc22eb24', '2d1510a0'}:
                blocks[0]['config']['command'] = 'mkdir -p "{{ workspace_logs }}/archive"\n' + blocks[0]['config']['command']
                for block in blocks:
                    cfg = block['config']
                    if block['type'] == 'shell' and 'mvn exec:java' in cfg.get('command', ''):
                        cfg['command'] = 'cd "{{ execution_path }}"\nmvn -q compile\n' + cfg['command']
                        cfg['checked_command'] = True
                        cfg['timeout_seconds'] = max(cfg.get('timeout_seconds', 600), 1800)
            elif prefix == 'ed43db60':
                cmd = blocks[0]['config']['command']
                boundary = cmd.find("echo '=== Findings ==='")
                blocks[0]['config']['command'] = 'cd "{{ execution_path }}"\n' + cmd[boundary:]
                blocks[7] = m.check('compile', 'mvn -q compile', 'mainzelliste-compile')
            elif prefix in {'1f983bd4', '24f76503'}:
                blocks.append(m.check('compile-tests', 'mvn -q test-compile', 'mainzelliste-test-compile'))
            elif prefix == '221fa01c':
                blocks[0]['config']['command'] = 'git status --short\ngit log -1 --oneline'
            elif prefix == '6d8628b8':
                # Preview explicitly consumes the generated, not-yet-committed website directory.
                policy['source'] = 'working-tree'
                policy['source_path'] = source + '/zeitreise-touren'
                policy['install_npm'] = False
                blocks[0] = m.check('preview_build', 'npm ci --no-audit --no-fund\nnpm run build', 'web-preview')
            elif prefix == '083d4648':
                policy['additional_sources'] = {
                    'schweizerbahn': str(Path.home() / 'Entwicklung/IdeaProjects/Schweizerbahn_hunts'),
                    'deutschland': str(Path.home() / 'Entwicklung/IdeaProjects/Test_Deutsche_Geschichte_Deutschlandticket')}
                blocks[:] = [m.check('flutter_' + label,
                    'cd "{{ ' + variable + ' }}"\nflutter pub get\nflutter test', 'flutter-' + label)
                    for label, variable in [('treasure_hunt', 'execution_path'),
                        ('schweizerbahn', 'execution_path_schweizerbahn'),
                        ('deutschland', 'execution_path_deutschland')]]
            elif prefix == '65aafc3d':
                command = blocks[2]['config']['command']
                command = command.replace("    checks.append({", "    (report_dir / (label + '.log')).write_text(result.stdout + result.stderr)\n    checks.append({", 1)
                command = command.replace("print(json.dumps({'report_file': str(path), 'check_count': len(checks)}, ensure_ascii=False))",
                    "print(json.dumps({'report_file': str(path), 'check_count': len(checks)}, ensure_ascii=False))\n"
                    "failed = any(c.get('status') == 'failed' for c in checks)\n"
                    "ran = any('exit_code' in c for c in checks)\n"
                    "print('gate_status: ' + ('RED' if failed else 'GREEN' if ran else 'YELLOW'))\n"
                    "print('gate_category: codestand-checks')\nraise SystemExit(1 if failed else 0)")
                blocks[2]['config']['command'] = command
                blocks[2]['config']['on_failure'] = 'continue'
            elif prefix == '5bcced92':
                blocks[0] = m.check('flutter_checks', 'flutter pub get\nflutter analyze\nflutter test', 'flutter-checks')
            elif prefix == '9f9b1b78':
                template = Path(__file__).resolve().parents[2] / 'ai-automation-backend/src/main/resources/templates/builtin/test-matrix.json'
                blocks[:] = json.loads(template.read_text())['blocks']
            else:
                for block in blocks:
                    cfg = block['config']
                    if block['type'] == 'shell' and re.search(r'\b(npm|npx)\b', cfg.get('command', '')):
                        cfg['command'] = re.sub(r'\s*\|\s*tail\s+-\d+', '', cfg['command'])
                        cfg['checked_command'] = True
                if prefix == '2ce37430':
                    blocks[0] = m.check('web_checks', 'npm run lint\nnpm run build', 'web-build')
            proposal = dict(id=original['id'], name=original['name'], expectedVersion=original.get('version'),
                            ready=True, before=original, update=update)
        else:
            continue
        if proposal.get('ready'):
            for block in proposal['update']['blocks']:
                if block.get('config', {}).get('on_failure') == 'abort':
                    block['config']['on_failure'] = 'stop'
        (args.output / (original['id'] + '.json')).write_text(json.dumps(proposal, indent=2, ensure_ascii=False) + '\n')
        count += 1
    print(count, 'external proposals')


if __name__ == '__main__': main()
