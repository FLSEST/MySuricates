#!/usr/bin/env python3
"""Prepare code-evidence packets, assemble concept pages, and fail closed on bad output.

The pages describe mechanisms, not an installation: they carry no operational measurement.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess

CATALOG = Path(__file__).with_name('catalog.json')
HEADINGS = {
    'de': ['Wofür taugt das?', 'Vorteile', 'Nachteile und Kosten'],
    'en': ['What is it useful for?', 'Benefits', 'Drawbacks and costs'],
}
CODE_HEADING = {'de': 'Im Code', 'en': 'In the code'}
INDEX_DE = Path('docs/konzepte/README.md')


def read_json(path):
    return json.loads(path.read_text())


def slugs(only):
    return [slug for slug in re.split(r'[,\s]+', only or '') if slug]


def select(only=None):
    """Catalog entries named in ``only`` (comma or whitespace separated), in catalog order.

    Empty or missing ``only`` selects the whole catalog. Unknown slugs fail instead of
    silently shrinking the run.
    """
    catalog = read_json(CATALOG)
    wanted = slugs(only)
    if not wanted:
        return catalog
    unknown = sorted(set(wanted) - {item['slug'] for item in catalog})
    if unknown:
        raise ValueError('Unknown concept slug(s): ' + ', '.join(unknown))
    return [item for item in catalog if item['slug'] in wanted]


def add_index_rows(text, items, language):
    """Append overview-table rows for pages the index does not link yet; existing rows stay as they are."""
    lines = text.split('\n')
    for start in range(len(lines) - 1):
        if lines[start].startswith('|') and lines[start + 1].startswith('|---'):
            break
    else:
        raise ValueError('Concept index has no overview table')
    end = start + 2
    while end < len(lines) and lines[end].startswith('|'):
        end += 1
    rows = [f'| [{item["label_" + language]}]({item["slug"]}.md) | {item["question_" + language]} |'
            for item in items if f']({item["slug"]}.md)' not in text]
    lines[end:end] = rows
    return '\n'.join(lines)


def private_markers(text):
    return re.search(r'/home/[\w.-]+/|/Users/[\w.-]+/|'
                     r'\b[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\b|'
                     r'[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}', text)


def prepare(root, work, items=None):
    items = select() if items is None else items
    work.mkdir(parents=True, exist_ok=False)
    (work / 'drafts').mkdir()
    (work / 'packets').mkdir()
    commit = subprocess.check_output(['git', '-C', str(root), 'rev-parse', 'HEAD'], text=True).strip()
    (work / 'manifest.json').write_text(json.dumps({'source_commit': commit}, indent=2) + '\n')
    for item in items:
        packet = [f'# {item["title_de"]}', item['notes'], '## Quellcode-Belege']
        for source in item['sources']:
            content = (root / source).read_text()
            packet += [f'### {source}', content[:18000]]
            if len(content) > 18000:
                packet.append('[Auszug; restliche Datei bei Bedarf gezielt im Repository lesen.]')
        (work / 'packets' / (item['slug'] + '.txt')).write_text('\n\n'.join(packet) + '\n')
    print(f'Prepared {len(items)} evidence packets')


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


def assemble(root, work, items=None):
    items = select() if items is None else items
    manifest = read_json(work / 'manifest.json')
    bodies = {}
    for item in items:
        body = (work / 'drafts' / (item['slug'] + '.md')).read_text().strip()
        check_body(body)
        bodies[item['slug']] = body
    target = work / 'de'
    target.mkdir(exist_ok=True)
    for item in items:
        sources = '\n'.join(f'- [{Path(p).name}](../../{p})' for p in item['sources'])
        text = (f'<!-- concept-source: {manifest["source_commit"]} -->\n\n'
                f'# {item["title_de"]}\n\n```mermaid\n{item["diagram_de"]}\n```\n\n'
                f'{bodies[item["slug"]]}\n\n## {CODE_HEADING["de"]}\n\n{sources}\n')
        (target / (item['slug'] + '.md')).write_text(text)
    # New pages need a row in the overview; the copy goes next to the pages for the same review.
    index = root / INDEX_DE
    if index.is_file():
        current = index.read_text()
        updated = add_index_rows(current, items, 'de')
        if updated != current:
            (target / 'README.md').write_text(updated)
            print('Wrote README.md with overview rows for the new pages')
    print(f'Assembled {len(items)} German pages with fixed diagrams and source links')


def finish_english(root, directory, items=None):
    """Repair language-dependent assembly parts after the existing docs translator."""
    items = select() if items is None else items
    for item in items:
        path = directory / (item['slug'] + '.md')
        text = path.read_text()
        diagrams = re.findall(r'```mermaid\n(.*?)\n```', text, re.S)
        if diagrams not in ([item['diagram_de']], [item['diagram_en']]):
            raise ValueError(f'{path.name}: translator changed the fixed source diagram')
        text = text.replace('```mermaid\n' + item['diagram_de'] + '\n```',
                            '```mermaid\n' + item['diagram_en'] + '\n```')
        text = text.replace('(../../ai-automation-backend/', '(../../../ai-automation-backend/')
        path.write_text(text)
    index = directory / 'README.md'
    if index.exists():
        text = add_index_rows(index.read_text(), items, 'en')
        for folder in ('scripts', 'workflow-pool'):
            text = text.replace('(../../' + folder + '/', '(../../../' + folder + '/')
        index.write_text(text)
    check(root, directory, 'en', items)


def check(root, directory, language, items=None):
    items = select() if items is None else items
    failures = []
    for item in items:
        path = directory / (item['slug'] + '.md')
        try:
            text = path.read_text()
            if not re.search(r'^<!-- concept-source: [0-9a-f]{40} -->$', text, re.M):
                raise ValueError('Missing source-commit provenance')
            if re.findall(r'```mermaid\n(.*?)\n```', text, re.S) != [item['diagram_' + language]]:
                raise ValueError('Missing or changed deterministic diagram')
            if private_markers(text) or '{{' in text or '[TODO]' in text:
                raise ValueError('Private data or unresolved placeholder')
            headings = re.findall(r'^## (.+)$', text, re.M)
            if headings != HEADINGS[language] + [CODE_HEADING[language]]:
                raise ValueError('Missing or extra sections')
            # Resolve source links against their final destination, including before installation.
            final_parent = root / ('docs/konzepte' if language == 'de' else 'docs/en/konzepte')
            for link in re.findall(r'\]\(([^)]+)\)', text):
                if '://' in link or not (final_parent / link.split('#')[0]).resolve().is_file():
                    raise ValueError('Unverified source link: ' + link)
            if language == 'en' and not text.startswith('<!-- last-sync:'):
                raise ValueError('Missing docs-sync provenance header')
        except (OSError, ValueError) as exc:
            failures.append(f'{item["slug"]}: {exc}')
    if failures:
        print('gate_status: RED\ngate_category: concept-pages')
        raise ValueError('\n'.join(failures))
    print(f'gate_status: GREEN\ngate_category: concept-pages\ngate_description: {len(items)} {language} pages verified')


def list_items(items, paths, filtered):
    """One line per selected page: its slug, or with ``paths`` its path below docs/.

    ``paths`` adds the overview only for a full run; a filtered run leaves the translated
    overview alone and ``finish-english`` adds the rows for the selected pages.
    """
    lines = [('konzepte/' + item['slug'] + '.md') if paths else item['slug'] for item in items]
    if paths and not filtered:
        lines.append('konzepte/README.md')
    print('\n'.join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['prepare', 'assemble', 'check', 'finish-english', 'list'])
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--work', type=Path)
    parser.add_argument('--language', choices=['de', 'en'], default='de')
    parser.add_argument('--only', default='',
                        help='Comma-separated catalog slugs; empty = all pages')
    parser.add_argument('--paths', action='store_true',
                        help='list: print docs-relative paths instead of slugs')
    args = parser.parse_args()
    items = select(args.only)
    if args.action == 'list':
        list_items(items, args.paths, bool(slugs(args.only)))
        return
    if args.work is None:
        parser.error('--work is required for ' + args.action)
    actions = {'prepare': prepare, 'assemble': assemble, 'finish-english': finish_english}
    if args.action == 'check':
        check(args.root, args.work, args.language, items)
    else:
        actions[args.action](args.root, args.work, items)


if __name__ == '__main__':
    main()
