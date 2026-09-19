#!/usr/bin/env python3
"""Prepare evidence packets, assemble concept pages, and fail closed on bad output."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import subprocess

CATALOG = Path(__file__).with_name('catalog.json')
HEADINGS = {
    'de': ['Wofür taugt das?', 'Vorteile', 'Nachteile und Kosten'],
    'en': ['What is it useful for?', 'Benefits', 'Drawbacks and costs'],
}
LABELS = {
    'steps_shell': 'Shell-Schritte im 30-Tage-Fenster',
    'steps_simple': 'Simple-Schritte im 30-Tage-Fenster',
    'steps_agentic': 'Agentische Schritte im 30-Tage-Fenster',
    'retained_runs': 'Gespeicherte Runs (alle Zeiträume)',
    'todos_without_resolution_state': 'TODOs ohne Zustand im optionalen Resolve-Pfad',
    'activeTodoTriggers': 'Aktive TODO-Trigger',
    'ai_todos_with_session': 'KI-TODOs mit ausgefüllter Session-ID',
    'activeSchedules': 'Aktive Zeitpläne',
    'totalSchedules': 'Zeitpläne insgesamt',
    'activeWebhooks': 'Aktive Webhooks',
    'activeCompletionTriggers': 'Aktive Abschluss-Trigger',
    'workflows_never_run': 'Workflows ohne gespeicherten Run',
    'activeMemoryEntries': 'Aktive Memory-Einträge',
    'workflows': 'Sichtbare Workflows in aktiven Projekten',
    'workflows_sealed': 'Versiegelte sichtbare Workflows',
}


def read_json(path):
    return json.loads(path.read_text())


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def private_markers(text):
    return re.search(r'/home/[\w.-]+/|/Users/[\w.-]+/|'
                     r'\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\b|'
                     r'[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}', text)


def prepare(root, work, facts_path):
    facts = read_json(facts_path)
    if facts.get('schema') != 1 or not facts.get('metrics'):
        raise ValueError('Missing/unsupported measurement snapshot')
    measured = dt.datetime.fromisoformat(facts['collected_at'])
    age = dt.datetime.now(dt.timezone.utc) - measured
    if not dt.timedelta(0) <= age <= dt.timedelta(days=1):
        raise ValueError('Collect measurements again: snapshot must be at most 24 hours old')
    if private_markers(json.dumps(facts)):
        raise ValueError('Private markers in measurement snapshot')
    work.mkdir(parents=True, exist_ok=False)
    (work / 'drafts').mkdir()
    (work / 'packets').mkdir()
    (work / 'facts.json').write_bytes(facts_path.read_bytes())
    commit = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
    (work / 'manifest.json').write_text(json.dumps({'source_commit': commit,
        'facts_sha256': digest(facts_path)}, indent=2) + '\n')
    for item in read_json(CATALOG):
        selected = {key: facts['metrics'][key] for key in item['metrics']}
        packet = [f'# {item["title_de"]}', item['notes'],
                  '## Aktuelle Betriebsdaten', facts['collected_at'],
                  json.dumps(selected, ensure_ascii=False, indent=2),
                  '\n'.join(facts['limitations']), '## Quellcode-Belege']
        for source in item['sources']:
            content = (root / source).read_text()
            packet += [f'### {source}', content[:18000]]
            if len(content) > 18000:
                packet.append('[Auszug; restliche Datei bei Bedarf gezielt im Repository lesen.]')
        (work / 'packets' / (item['slug'] + '.txt')).write_text('\n\n'.join(packet) + '\n')
    print('Prepared 8 evidence packets; measurement snapshot verified')


def check_body(text, language='de'):
    if re.findall(r'^## (.+)$', text, re.M) != HEADINGS[language]:
        raise ValueError('Expected exactly the three agreed section headings')
    words = len(text.split())
    if not 140 <= words <= 400:
        raise ValueError(f'Expected 140–400 words, got {words}')
    if '```' in text or re.search(r'^# ', text, re.M) or '](' in text:
        raise ValueError('Title, diagrams and links belong to the deterministic assembly')
    if private_markers(text) or '{{' in text or '[TODO]' in text:
        raise ValueError('Private data or unresolved placeholder')


def assemble(root, work, facts_path):
    catalog = read_json(CATALOG)
    manifest = read_json(work / 'manifest.json')
    if digest(facts_path) != manifest['facts_sha256']:
        raise ValueError('Measurement snapshot changed during generation')
    facts = read_json(facts_path)
    bodies = {}
    for item in catalog:
        body = (work / 'drafts' / (item['slug'] + '.md')).read_text().strip()
        check_body(body)
        bodies[item['slug']] = body
    target = work / 'de'
    target.mkdir(exist_ok=True)
    for item in catalog:
        measurements = []
        for key in item['metrics']:
            metric = facts['metrics'][key]
            value = str(metric['value'])
            if metric['denominator'] is not None:
                value += ' / ' + str(metric['denominator'])
            measurements.append(f'| {LABELS[key]} | {value} |')
        sources = '\n'.join(f'- [{Path(p).name}](../../{p})' for p in item['sources'])
        text = (f'<!-- concept-facts-sha256: {manifest["facts_sha256"]} -->\n'
                f'<!-- concept-source: {manifest["source_commit"]} -->\n\n'
                f'# {item["title_de"]}\n\n```mermaid\n{item["diagram_de"]}\n```\n\n'
                f'{bodies[item["slug"]]}\n\n## Betriebsbeleg\n\n'
                f'Messung: **{facts["collected_at"][:10]} (UTC)**, eine lokale Entwicklungsinstanz. '
                'Bestandszähler beziehen sich auf die über REST sichtbaren Datensätze; '
                'Schrittzahlen zählen gespeicherte Schritte mit Typ-Metadaten im 30-Tage-Fenster. '
                'Wrapper zählen einmal. Dies ist kein Wirksamkeitsnachweis.\n\n'
                '| Messgröße | Anzahl / Bezugsgröße |\n|---|---:|\n' + '\n'.join(measurements) +
                '\n\n[Definitionen, Messgrenzen und Rohzähler](betriebsdaten.json) · '
                '[Messmethode und Wiederholung](README.md)\n\n## Im Code\n\n' + sources + '\n')
        (target / (item['slug'] + '.md')).write_text(text)
    print('Assembled 8 German pages with fixed diagrams, measurements and source links')


def finish_english(root, directory, facts_path):
    """Repair language-dependent assembly parts after the existing docs translator."""
    for item in read_json(CATALOG):
        path = directory / (item['slug'] + '.md')
        text = path.read_text()
        diagrams = re.findall(r'```mermaid\n(.*?)\n```', text, re.S)
        if diagrams not in ([item['diagram_de']], [item['diagram_en']]):
            raise ValueError(f'{path.name}: translator changed the fixed source diagram')
        text = text.replace('```mermaid\n' + item['diagram_de'] + '\n```',
                            '```mermaid\n' + item['diagram_en'] + '\n```')
        text = text.replace('(../../ai-automation-backend/', '(../../../ai-automation-backend/')
        text = text.replace('(betriebsdaten.json)', '(../../konzepte/betriebsdaten.json)')
        path.write_text(text)
    index = directory / 'README.md'
    if index.exists():
        text = index.read_text().replace('(betriebsdaten.json)', '(../../konzepte/betriebsdaten.json)')
        for folder in ('scripts', 'workflow-pool'):
            text = text.replace('(../../' + folder + '/', '(../../../' + folder + '/')
        index.write_text(text)
    check(root, directory, facts_path, 'en')


def check(root, directory, facts_path, language):
    facts = read_json(facts_path)
    failures = []
    for item in read_json(CATALOG):
        path = directory / (item['slug'] + '.md')
        try:
            text = path.read_text()
            if f'concept-facts-sha256: {digest(facts_path)}' not in text:
                raise ValueError('Missing/mismatched measurement provenance')
            if re.findall(r'```mermaid\n(.*?)\n```', text, re.S) != [item['diagram_' + language]]:
                raise ValueError('Missing or changed deterministic diagram')
            if private_markers(text) or '{{' in text or '[TODO]' in text:
                raise ValueError('Private data or unresolved placeholder')
            headings = re.findall(r'^## (.+)$', text, re.M)
            if headings[:3] != HEADINGS[language] or len(headings) != 5:
                raise ValueError('Missing sections')
            rows = re.findall(r'^\| [^|]+ \| ([\d /]+) \|$', text, re.M)
            expected_rows = []
            for key in item['metrics']:
                value = str(facts['metrics'][key]['value'])
                denominator = facts['metrics'][key]['denominator']
                if denominator is not None:
                    value += ' / ' + str(denominator)
                expected_rows.append(value)
            if rows != expected_rows:
                raise ValueError('Changed/missing measurement rows')
            # Resolve source links against their final destination, including before installation.
            final_parent = root / ('docs/konzepte' if language == 'de' else 'docs/en/konzepte')
            for link in re.findall(r'\]\(([^)]+)\)', text):
                if link in ('README.md', 'betriebsdaten.json', '../../konzepte/betriebsdaten.json'):
                    continue
                if '://' in link or not (final_parent / link.split('#')[0]).resolve().is_file():
                    raise ValueError('Unverified source link: ' + link)
            if language == 'en' and not text.startswith('<!-- last-sync:'):
                raise ValueError('Missing docs-sync provenance header')
        except (OSError, ValueError) as exc:
            failures.append(f'{item["slug"]}: {exc}')
    if failures:
        print('gate_status: RED\ngate_category: concept-pages')
        raise ValueError('\n'.join(failures))
    print(f'gate_status: GREEN\ngate_category: concept-pages\ngate_description: 8 {language} pages verified')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'assemble', 'check', 'finish-english'])
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--facts', type=Path, required=True)
    parser.add_argument('--language', choices=['de', 'en'], default='de')
    args = parser.parse_args()
    actions = {'prepare': prepare, 'assemble': assemble, 'finish-english': finish_english}
    if args.action == 'check':
        check(args.root, args.work, args.facts, args.language)
    else:
        actions[args.action](args.root, args.work, args.facts)


if __name__ == '__main__':
    main()
