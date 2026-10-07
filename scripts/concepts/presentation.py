#!/usr/bin/env python3
"""Self-presentation of MySuricates as one HTML deck: measured, written per topic, rendered.

Used by the pool workflow ``workflow-pool/mysuricates-presentation.json``; every step except the
text per topic is deterministic:

  facts    measure the repository and bundle one packet per topic (``<work>/packets/<topic>.txt``)
           plus ``facts.json`` and ``themes.txt``
  themes   print the topic ids of a work directory, one per line (the foreach items)
  render   set the deck from ``facts.json`` and the per-topic AI answers, check it and write
           one self-contained HTML file; the last line is ``CHECK: OK`` (exit 0) or the run stops
           with ``CHECK: ERROR`` lines (exit 1)
  output   print the default output path for a language

``--language de|en`` decides the sources (``README.de.md`` + ``docs/konzepte/`` or ``README.md`` +
``docs/en/konzepte/``), the slide labels (``presentation_text.json``) and the default output file
(``docs/presentation/de.html`` or ``docs/presentation/index.html``). Numbers on the slides only come
from the measurement: the AI references them as ``[[key]]``, the renderer inserts the value and
reports every other number.

Standard library only; no network, no platform API.
"""
import argparse
import base64
import collections
import datetime
import html
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
TEXT_FILE = os.path.join(HERE, 'presentation_text.json')
CATALOG_FILE = os.path.join(HERE, 'catalog.json')
LANGUAGES = ('de', 'en')
DEFAULT_OPS = os.path.join('reports', 'concepts', 'facts.json')
SKIP = {'node_modules', 'target', 'target-ide', 'dist', '.git', 'build', 'coverage'}
AI_TYPES = {'simple', 'agentic', 'skill-capability', 'compress-ai'}
HUMAN_TYPES = {'approval', 'human-input'}
FACT_WORDS = ('version', 'commit', 'measured_at', 'first_commit_month')

# Topics in deck order. ``idea`` > 0 takes guiding idea N from the README; labels come from
# presentation_text.json, everything here is language-neutral.
THEMES = [
    {'id': 'wofuer', 'idea': 0, 'concepts': [], 'diagram': '', 'data': [], 'material': 'questions',
     'facts': ['builtin_templates', 'pool_workflows', 'ops_workflows', 'ops_retained_runs', 'ops_activeSchedules', 'block_types']},
    {'id': 'deterministisch', 'idea': 1, 'concepts': ['messen-vor-bewerten', 'agentische-budgets'],
     'diagram': 'messen-vor-bewerten', 'data': ['cost'], 'extra': 'ladder',
     'facts': ['ops_steps_shell', 'ops_steps_simple', 'ops_steps_agentic', 'ops_steps_total', 'ops_steps_shell_percent',
               'shipped_blocks', 'shipped_blocks_deterministic']},
    {'id': 'wiederverwenden', 'idea': 2, 'concepts': ['selbstverbesserung', 'workflow-memory', 'persistenz-herkunft'],
     'diagram': 'selbstverbesserung', 'data': [],
     'facts': ['builtin_templates', 'pool_workflows', 'ops_todos', 'ops_retained_runs', 'ops_activeTodoTriggers',
               'ops_activeMemoryEntries', 'ops_workflows', 'ops_workflows_from_template']},
    {'id': 'zugang', 'idea': 3, 'concepts': ['zugaenge'], 'diagram': 'zugaenge', 'data': [],
     'facts': ['ui_pages', 'controllers', 'ops_activeSchedules', 'ops_totalSchedules', 'ops_activeWebhooks', 'ops_activeTodoTriggers']},
    {'id': 'stoepseln', 'idea': 4, 'concepts': [], 'diagram': '', 'data': ['blocks', 'connect'], 'extra': 'wiring',
     'facts': ['block_types', 'integration_providers', 'modules', 'config_keys', 'config_groups']},
    {'id': 'provider', 'idea': 5, 'concepts': [], 'diagram': '', 'data': ['providers'], 'extra': 'providers',
     'facts': ['direct_providers', 'pi_models', 'pi_backends', 'ops_workflows_explicit_provider', 'ops_workflows',
               'shipped_blocks_in_container', 'shipped_blocks']},
    {'id': 'kontrolle', 'idea': 0, 'concepts': ['gates', 'haertung'], 'diagram': 'haertung', 'data': ['guardrails', 'modules'],
     'facts': ['guard_tests', 'hardening_modules', 'ops_workflows_sealed', 'ops_workflows', 'backend_tests']},
    {'id': 'erfahrung', 'idea': 0, 'concepts': [], 'diagram': '', 'data': [], 'material': 'approaches', 'extra': 'guards',
     'facts': ['months_active', 'commits_total', 'commits_ai_coauthored', 'guard_tests', 'concept_pages', 'markdown_docs', 'backend_tests']},
]

# What a measured key means, for the AI only (the packet is read by the model, never shown).
DEFS = {
    'markdown_docs': 'all Markdown files under docs/ (both languages) — NOT concept pages',
    'concept_pages': 'concept pages under docs/konzepte/',
    'guard_tests': 'test classes that enforce architecture and hygiene rules (Ratchet/Gate/Allowlist/LayerRule)',
    'backend_tests': 'test methods in the backend (@Test and similar)',
    'commits_ai_coauthored': 'commits with an AI co-author line', 'commits_total': 'commits in the repository',
    'months_active': 'months with commits', 'builtin_templates': 'shipped workflow templates (examples)',
    'pool_workflows': 'workflows in the shipped pool (examples)', 'shipped_blocks': 'blocks in all shipped example workflows',
    'shipped_blocks_deterministic': 'of these without AI', 'shipped_blocks_in_container': 'example blocks with a container image',
    'ui_pages': 'pages of the web interface', 'controllers': 'REST controllers in the backend', 'block_types': 'registered block types',
    'integration_providers': 'connected external services', 'modules': 'switchable modules',
    'config_keys': 'settings under automation.*', 'config_groups': 'setting areas under automation.*',
    'hardening_modules': 'hardening modules', 'direct_providers': 'direct CLI adapters for AI providers',
    'pi_models': 'models reachable through PI on this host', 'pi_backends': 'backends connected through PI on this host',
}


def load_text(language):
    """The slide labels and source paths of one language from presentation_text.json."""
    if language not in LANGUAGES:
        raise ValueError('Unknown language %r (expected one of %s)' % (language, ', '.join(LANGUAGES)))
    with open(TEXT_FILE, encoding='utf-8') as fh:
        return json.load(fh)[language]


def default_output(language):
    """Output file relative to the repository root: docs/presentation/index.html (en) or de.html (de)."""
    return load_text(language)['output']


def wanted_themes(selection):
    """Topic ids from a comma list; empty or ``all`` selects every topic. Unknown ids fail."""
    wanted = [t for t in re.split(r'[,\s]+', selection or '') if t]
    if not wanted or wanted == ['all']:
        return list(THEMES)
    unknown = sorted(set(wanted) - {t['id'] for t in THEMES})
    if unknown:
        raise ValueError('Unknown topic(s): ' + ', '.join(unknown))
    return [t for t in THEMES if t['id'] in wanted]


# ---------------------------------------------------------------------------
# facts: measure the repository and bundle the topic packets
# ---------------------------------------------------------------------------

class Repo(object):
    def __init__(self, root):
        self.root = root

    def read(self, rel):
        try:
            with open(os.path.join(self.root, rel), encoding='utf-8', errors='replace') as fh:
                return fh.read()
        except OSError:
            return ''

    def files(self, rel, pred):
        for cur, dirs, names in os.walk(os.path.join(self.root, rel)):
            dirs[:] = sorted(d for d in dirs if d not in SKIP)
            for name in sorted(names):
                if pred(name):
                    yield os.path.join(cur, name)

    def git(self, *args):
        try:
            return subprocess.run(('git', '-C', self.root) + args, capture_output=True, text=True,
                                  timeout=60, check=True).stdout.strip()
        except (OSError, subprocess.SubprocessError):
            return ''


def count_lines(paths):
    total = 0
    for path in paths:
        with open(path, 'rb') as fh:
            total += sum(1 for _ in fh)
    return total


def md_section(doc, heading, level='##'):
    """Body of the first ``<level> <heading>`` (exact line) up to the next heading of the same level."""
    pattern = r'^' + level + ' ' + re.escape(heading) + r'[ \t]*\n(.*?)(?=^' + level + r' |\Z)'
    m = re.search(pattern, doc, re.M | re.S)
    return re.sub(r'\n-{3,}\s*$', '', m.group(1).strip()).strip() if m else ''


def doc_sections(text, pattern, limit):
    parts = re.split(r'(?m)^(?=#{2,4} )', text)
    hits = [p.strip() for p in parts if re.match(pattern, p.split('\n', 1)[0], flags=re.I)]
    return '\n\n'.join(hits)[:limit]


def guiding_ideas(readme, heading):
    """The numbered items of the README section ``## Leitideen`` / ``## Guiding ideas``."""
    section = md_section(readme, heading)
    return [' '.join(x.split()) for x in re.split(r'(?m)^\s*\d+\.\s+', section) if x.strip()]


def app_logo(repo):
    """The application logo (MeerkatIcon) translated from JSX into plain SVG, or None."""
    for rel in ('ai-automation-frontend/src/components/layout/MeerkatIcon.tsx',
                'ai-automation-frontend/src/components/layout/Sidebar.tsx'):
        m = re.search(r'function MeerkatIcon[\s\S]*?<svg viewBox="([\d. ]+)"[^>]*>([\s\S]*?)</svg>', repo.read(rel))
        if m:
            break
    else:
        return None
    inner = re.sub(r'\{/\*[\s\S]*?\*/\}', '', m.group(2))
    inner = re.sub(r'=\{bg\}', '="var(--bg)"', inner)
    for jsx, attr in (('strokeWidth', 'stroke-width'), ('strokeLinecap', 'stroke-linecap'), ('strokeLinejoin', 'stroke-linejoin')):
        inner = inner.replace(jsx + '=', attr + '=')
    if '{' in inner or '}' in inner:
        return None
    return {'viewBox': m.group(1), 'inner': re.sub(r'\s*\n\s*', '', inner).strip()}


def probe_pi():
    """Backends and models PI reaches on this host (``pi --offline --list-models``); empty without PI."""
    rows = []
    try:
        probe = subprocess.run(['pi', '--offline', '--list-models'], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return rows
    for line in (probe.stdout + '\n' + probe.stderr).split('\n'):
        cols = line.split()
        if len(cols) >= 2 and cols[0] != 'provider' and re.match(r'^[a-z0-9][a-z0-9._-]*$', cols[0]) and re.search(r'\d+K\b', line):
            rows.append((cols[0], cols[1]))
    return rows


def measure(root, language, themes='all', core_ideas='', big_idea='', audience='', ops_path='', host_probe=True):
    """Measure the repository at ``root``; returns (facts, packets) with packets = {topic id: text}."""
    T = load_text(language)
    src = T['sources']
    repo = Repo(root)
    be, fe = 'ai-automation-backend/src', 'ai-automation-frontend/src'
    java_main = list(repo.files(be + '/main/java', lambda n: n.endswith('.java')))
    java_test = list(repo.files(be + '/test/java', lambda n: n.endswith('.java')))
    is_fe_test = lambda n: re.search(r'\.(test|spec)\.tsx?$', n) is not None
    fe_all = list(repo.files(fe, lambda n: n.endswith(('.ts', '.tsx'))))
    fe_tests = [p for p in fe_all if is_fe_test(os.path.basename(p))]
    fe_main = [p for p in fe_all if not is_fe_test(os.path.basename(p))]

    test_methods = 0
    for path in java_test:
        with open(path, encoding='utf-8', errors='replace') as fh:
            test_methods += len(re.findall(r'@(?:Test|ParameterizedTest|RepeatedTest)\b', fh.read()))
    guard_re = re.compile(r'(Ratchet|Gate|Allowlist|LayerRule)Test\.java$')
    guards = sorted({os.path.basename(p)[:-5] for p in java_test if guard_re.search(os.path.basename(p))})

    m = re.search(r'<artifactId>ai-automation-backend</artifactId>\s*<version>([^<]+)</version>',
                  repo.read('ai-automation-backend/pom.xml'))
    version = (m.group(1) if m else '?').replace('-SNAPSHOT', '')

    # Block types: described in chapter 2 of the guide (in the deck language), registered by the executors.
    guide = repo.read(src['guide'])
    chapter = guide.split('\n## 2.', 1)[-1].split('\n## 3.', 1)[0]
    described = collections.OrderedDict()
    for head in re.findall(r'^### (`.+)$', chapter, flags=re.M):
        names, _, desc = head.partition(' — ')
        for name in re.findall(r'`([a-z-]+)`', names):
            described[name] = desc.strip()
    registered = []
    for path in java_main:
        if path.endswith('Executor.java'):
            with open(path, encoding='utf-8', errors='replace') as fh:
                for group in re.findall(r'supportedTypes\(\)\s*\{\s*return\s+Set\.of\(([^)]*)\)', fh.read()):
                    registered += re.findall(r'"([a-z-]+)"', group)
    order = list(described) + sorted(t for t in set(registered) if t not in described)
    fallback = {'integration': T['integration_desc']}
    block_types = [{'type': t, 'desc': described.get(t) or fallback.get(t, '')}
                   for t in order if t in registered or not registered]
    known_types = {b['type'] for b in block_types}

    modules = [{'name': a, 'tier': b.lower(), 'maturity': c.lower()} for a, b, c in re.findall(
        r'^\s*[A-Z_]+\("([a-z-]+)",\s*([A-Z]+),\s*([A-Z]+),',
        repo.read(be + '/main/java/com/automation/modules/ModuleCatalog.java'), flags=re.M)]
    prov_dir = os.path.join(root, be, 'main/java/com/automation/service/integration/providers')
    providers = sorted(d for d in os.listdir(prov_dir) if os.path.isdir(os.path.join(prov_dir, d))) if os.path.isdir(prov_dir) else []
    ai_providers = []
    for path in java_main:
        if path.endswith('AiProvider.java'):
            with open(path, encoding='utf-8', errors='replace') as fh:
                if 'implements AiProvider' in fh.read():
                    ai_providers.append(os.path.basename(path)[:-len('AiProvider.java')].lower())
    ai_providers.sort()
    # PI is not a provider but the gateway to any provider: count what this host reaches through it.
    pi_rows = probe_pi() if host_probe else []
    pi_backends = sorted({b for b, _ in pi_rows})
    direct_providers = [a for a in ai_providers if a != 'pi']

    def block_mix(rel):
        mix = {'files': 0, 'det': 0, 'ai': 0, 'human': 0, 'container': 0}

        def walk(node):
            if isinstance(node, dict):
                if node.get('type') in known_types and isinstance(node.get('config'), dict):
                    t = node['type']
                    mix['ai' if t in AI_TYPES else 'human' if t in HUMAN_TYPES else 'det'] += 1
                    if node['config'].get('container_image'):
                        mix['container'] += 1
                for value in node.values():
                    walk(value)
            elif isinstance(node, list):
                for value in node:
                    walk(value)

        for path in repo.files(rel, lambda n: n.endswith('.json')):
            try:
                with open(path, encoding='utf-8') as fh:
                    walk(json.load(fh))
                mix['files'] += 1
            except ValueError:
                pass
        mix['blocks'] = mix['det'] + mix['ai'] + mix['human']
        return mix

    mixes = {'builtin': block_mix(be + '/main/resources/templates/builtin'), 'pool': block_mix('workflow-pool')}
    shipped_blocks = sum(x['blocks'] for x in mixes.values())
    shipped_det = sum(x['det'] for x in mixes.values())

    months = collections.Counter(repo.git('log', '--format=%ad', '--date=format:%Y-%m').split())
    ordered = sorted(months)
    today = datetime.date.today()
    last12 = []
    y, mth = today.year, today.month
    for _ in range(12):
        key = '%04d-%02d' % (y, mth)
        last12.insert(0, [key, months.get(key, 0)])
        mth -= 1
        if mth == 0:
            y, mth = y - 1, 12

    concepts_de = os.path.join(root, 'docs', 'konzepte')
    concept_pages = len([n for n in os.listdir(concepts_de) if n.endswith('.md') and n != 'README.md']) if os.path.isdir(concepts_de) else 0
    numbers = {
        'backend_tests': test_methods,
        'backend_test_classes': len(java_test),
        'frontend_test_files': len(fe_tests),
        'e2e_specs': len(list(repo.files('ai-automation-frontend/playwright', lambda n: n.endswith('.spec.ts')))),
        'block_types': len(block_types),
        'modules': len(modules),
        'hardening_modules': sum(1 for x in modules if x['tier'] == 'hardening'),
        'integration_providers': len(providers),
        'controllers': sum(1 for p in java_main if p.endswith('Controller.java')),
        'migrations': len(list(repo.files(be + '/main/resources/db/migration', lambda n: n.endswith('.sql')))),
        'builtin_templates': len(list(repo.files(be + '/main/resources/templates/builtin', lambda n: n.endswith('.json')))),
        'pool_workflows': len(list(repo.files('workflow-pool', lambda n: n.endswith('.json')))),
        'markdown_docs': len(list(repo.files('docs', lambda n: n.endswith('.md')))),
        'concept_pages': concept_pages,
        'java_files': len(java_main),
        'frontend_files': len(fe_main),
        'loc_backend': count_lines(java_main),
        'loc_backend_tests': count_lines(java_test),
        'loc_frontend': count_lines(fe_main),
        'guard_tests': len(guards),
        'commits_total': sum(months.values()),
        'commits_ai_coauthored': int(repo.git('rev-list', '--count', 'HEAD', '-i', '--grep=Co-Authored-By: Claude') or 0),
        'commits_last_30_days': int(repo.git('rev-list', '--count', '--since=30.days', 'HEAD') or 0),
        'months_active': len(ordered),
        'ai_adapters': len(ai_providers),
        'direct_providers': len(direct_providers),
        'pi_backends': len(pi_backends),
        'pi_models': len(pi_rows),
        'ui_pages': len(list(repo.files(fe + '/pages', lambda n: n.endswith('.tsx') and '.test.' not in n))),
        'shipped_workflows': sum(x['files'] for x in mixes.values()),
        'shipped_blocks': shipped_blocks,
        'shipped_blocks_deterministic': shipped_det,
        'shipped_blocks_ai': sum(x['ai'] for x in mixes.values()),
        'shipped_deterministic_percent': round(100.0 * shipped_det / max(1, shipped_blocks)),
        'shipped_blocks_in_container': sum(x['container'] for x in mixes.values()),
    }
    numbers['loc_total'] = numbers['loc_backend'] + numbers['loc_backend_tests'] + numbers['loc_frontend']

    # Operational counters of one installation (scripts/concepts/collect_facts.py, gitignored reports/).
    ops_file = os.path.join(root, ops_path or DEFAULT_OPS)
    try:
        with open(ops_file, encoding='utf-8') as fh:
            ops_doc = json.load(fh)
    except (OSError, ValueError):
        ops_doc = {}
    ops = {}
    for key, val in (ops_doc.get('metrics') or {}).items():
        if isinstance(val, dict) and isinstance(val.get('value'), (int, float)):
            numbers['ops_' + key] = val['value']
            ops[key] = {'value': val['value'], 'denominator': val.get('denominator'), 'definition': val.get('definition', '')}
            if val.get('denominator'):
                numbers['ops_' + key + '_percent'] = round(100.0 * val['value'] / val['denominator'])
    step_total = max([v['denominator'] or 0 for k, v in ops.items() if k.startswith('steps_')] + [0])
    if step_total:
        numbers['ops_steps_total'] = step_total

    # Features from the configuration: leaf keys under automation.* per group.
    config_groups, stack = {}, []
    for line in repo.read(be + '/main/resources/application.yml').split('\n'):
        cm = re.match(r'^(\s*)([A-Za-z0-9_.-]+):\s*(.*)$', line)
        if not cm or line.lstrip().startswith('#'):
            continue
        indent = len(cm.group(1))
        while stack and stack[-1][0] >= indent:
            stack.pop()
        stack.append((indent, cm.group(2)))
        path = [k for _, k in stack]
        if len(path) > 2 and path[0] == 'automation' and cm.group(3) and not cm.group(3).startswith('#'):
            config_groups[path[1]] = config_groups.get(path[1], 0) + 1
    numbers['config_keys'] = sum(config_groups.values())
    numbers['config_groups'] = len(config_groups)

    try:
        with open(CATALOG_FILE, encoding='utf-8') as fh:
            catalog = {c['slug']: c for c in json.load(fh)}
    except (OSError, ValueError):
        catalog = {}

    def concept_text(slug):
        text = repo.read(os.path.join(src['concepts'], slug + '.md'))
        text = re.sub(r'<!--[\s\S]*?-->', '', text)
        text = re.sub(r'```[\s\S]*?```', '', text)
        text = re.sub(r'\]\([^)]*\)', ']', text)
        return re.sub(r'\n{3,}', '\n\n', text).strip()[:2600]

    def diagram(slug):
        entry = catalog.get(slug) or {}
        source = entry.get('diagram_' + language, '')
        nodes, ids = {}, []
        for ident, label in re.findall(r'(\w+)\["([^"]+)"\]', source):
            if ident not in nodes:
                nodes[ident] = label
                ids.append(ident)
        cycle = bool(ids) and re.search(r'-->\s*%s\b(?!\[)' % ids[0], source) is not None
        return {'steps': [nodes[i] for i in ids][:5], 'cycle': cycle, 'title': entry.get('title_' + language, slug)}

    readme = repo.read(src['readme'])
    heads = T['headings']
    paras = [p.strip() for p in readme.split('\n\n')]
    hero_lead = next((' '.join(p.split()) for p in paras if p.startswith(T['hero_prefix'])), '')
    ideas = [' '.join(x.split()) for x in re.split(r'(?m)^\s*\d+\.\s+', core_ideas) if x.strip()] if core_ideas.strip() \
        else guiding_ideas(readme, heads['guiding_ideas'])
    ladder = md_section(guide, heads['ladder'], '###')
    questions = md_section(readme, heads['questions'])
    research = md_section(readme, heads['research_questions']) or \
        repo.read(src['concept_index']).split('\n## ', 1)[0].split('\n', 1)[-1].strip()
    materials = {
        'questions': ('### ' + heads['questions'] + '\n\n' + questions).strip() if questions else '',
        'approaches': ('### ' + heads['approaches'] + '\n\n' + md_section(repo.read(src['baukasten']), heads['approaches'])
                       + '\n\n### ' + heads['research_questions'] + '\n\n' + research).strip(),
    }
    # Links and anchors mean nothing to the model; keep the link text only.
    materials = dict((k, re.sub(r'\]\([^)]*\)', ']', v)[:2200]) for k, v in materials.items())
    extras = {
        'ladder': ladder,
        'wiring': ('Block types:\n' + '\n'.join('- %s: %s' % (b['type'], b['desc']) for b in block_types)
                   + '\nIntegration providers: ' + ', '.join(providers)
                   + '\nConfigurable areas (automation.*, number of keys): '
                   + ', '.join('%s %d' % kv for kv in sorted(config_groups.items(), key=lambda kv: (-kv[1], kv[0])))),
        'providers': ('AI connection in the code: direct CLI adapters for ' + ', '.join(direct_providers)
                      + '. Plus PI: PI is NOT a provider but the gateway to ANY provider — the model is given as'
                      ' <backend>/<model>, new providers join without a platform change. On this installation '
                      + str(len(pi_rows)) + ' models from ' + str(len(pi_backends)) + ' backend(s) are reachable through it ('
                      + ', '.join(pi_backends) + '); that is the state of this host, not a limit.\n\n'
                      + doc_sections(repo.read('CLAUDE.md'), r'#+ (Architecture Overview|Execution Config)', 2600)
                      + '\n\n' + doc_sections(repo.read(src['architecture']), r'#+ .*(provider|container)', 2200)),
        'guards': 'Guardrail tests in which lessons are pinned down: ' + ', '.join(guards),
    }

    labels = T['themes']
    selected = wanted_themes(themes)
    packets, themes_out = collections.OrderedDict(), []
    for t in selected:
        lab = labels[t['id']]
        idea = lab.get('idea_text') or (ideas[t['idea'] - 1] if 0 < t['idea'] <= len(ideas) else lab['short'])
        lines = ['# TOPIC %s: %s' % (t['id'], lab['short']), '',
                 '## Language of the slides', T['language_rule'], '',
                 '## Audience', (audience or T['audience']).strip(), '',
                 '## The one idea behind all topics', (big_idea or T['big_idea']).strip(), '',
                 '## The cycle of the deck', T['cycle_names'], '',
                 '## Guiding idea (binding)', idea, '',
                 '## Measured values — reference them in the text ONLY as [[key]]']
        for key in t['facts']:
            if key in numbers:
                definition = ops.get(key[4:], {}).get('definition', '') if key.startswith('ops_') else DEFS.get(key, '')
                lines.append('%s = %s%s' % (key, numbers[key], '   (' + definition + ')' if definition else ''))
        if any(k.startswith('ops_') and k in numbers for k in t['facts']):
            lines.append('Operational values (ops_*): one single development installation, collected %s; '
                         'do not confuse the stock with the 30-day sample.' % str(ops_doc.get('collected_at', ''))[:10])
        for slug in t['concepts']:
            lines += ['', '## Concept page %s' % slug, concept_text(slug)]
            notes = (catalog.get(slug) or {}).get('notes')
            if notes:
                lines += ['', 'Background for the classification (do not list it, at most half a sentence): ' + notes]
        if t.get('extra') and extras[t['extra']]:
            lines += ['', '## Further source', extras[t['extra']]]
        if t.get('material') and materials[t['material']]:
            lines += ['', "## Material from the repository (the author's wording; take over the thoughts, do not quote)",
                      materials[t['material']]]
        body = '\n'.join(lines) + '\n'
        packets[t['id']] = body
        themes_out.append({'id': t['id'], 'short': lab['short'], 'idea': t['idea'], 'kicker': lab.get('kicker', ''),
                           'idea_text': idea, 'data': t['data'],
                           'diagram': diagram(t['diagram']) if t['diagram'] else None,
                           'packet_bytes': len(body.encode('utf-8'))})

    logo_raw = repo.read('ai-automation-frontend/public/icons/icon.svg')
    facts = {
        'language': language,
        'version': version,
        'measured_at': today.isoformat(),
        'commit': repo.git('rev-parse', '--short', 'HEAD'),
        'first_commit_month': ordered[0] if ordered else '',
        'numbers': numbers,
        'block_types': block_types,
        'modules': modules,
        'providers': providers,
        'guards': guards,
        'ai_providers': ai_providers,
        'direct_providers': direct_providers,
        'pi_backends': pi_backends,
        'has_pi': 'pi' in ai_providers,
        'mixes': mixes,
        'activity': last12,
        'app_logo': app_logo(repo),
        'logo_b64': base64.b64encode(logo_raw.encode('utf-8')).decode('ascii') if logo_raw else '',
        'config_groups': config_groups,
        'themes': themes_out,
        'ops': ops,
        'ops_collected': str(ops_doc.get('collected_at', ''))[:10],
        'hero_lead': hero_lead,
    }
    return facts, packets


def write_facts(work, facts, packets):
    os.makedirs(os.path.join(work, 'packets'), exist_ok=True)
    for theme_id, body in packets.items():
        with open(os.path.join(work, 'packets', theme_id + '.txt'), 'w', encoding='utf-8') as fh:
            fh.write(body)
    with open(os.path.join(work, 'facts.json'), 'w', encoding='utf-8') as fh:
        json.dump(facts, fh, ensure_ascii=False, indent=1)
    with open(os.path.join(work, 'themes.txt'), 'w', encoding='utf-8') as fh:
        fh.write(''.join(t['id'] + '\n' for t in facts['themes']))


def facts_summary(facts):
    numbers = facts['numbers']
    lines = ['FACTS: %d measured values (%d operational from %s) · language %s · commit %s' % (
        len(numbers), sum(1 for k in numbers if k.startswith('ops_')), facts['ops_collected'] or '—',
        facts['language'], facts['commit'] or '—')]
    for t in facts['themes']:
        lines.append('TOPIC %-16s packet %5d bytes · diagram %s · data slides %s' % (
            t['id'], t['packet_bytes'], 'yes' if t['diagram'] and t['diagram']['steps'] else 'no', ','.join(t['data']) or '—'))
    if not facts['ops']:
        lines.append('NOTE: no operational data found – run scripts/concepts/collect_facts.py first '
                     '(writes reports/concepts/facts.json); the deck uses repository values only')
    return lines


# ---------------------------------------------------------------------------
# render: set the deck and check it
# ---------------------------------------------------------------------------

def json_after(text, pos):
    """The first complete JSON object in ``text`` after ``pos``, or None."""
    start = text.find('{', pos)
    if start < 0:
        return None
    depth, in_str, esc = 0, False, False
    for i in range(start, len(text)):
        ch = text[i]
        if in_str:
            if esc:
                esc = False
            elif ch == '\\':
                esc = True
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                try:
                    return json.loads(text[start:i + 1])
                except ValueError:
                    return None
    return None


def parse_stories(raw, theme_ids):
    """Per-topic answers from the foreach output (``[<topic>]: <answer>`` per item)."""
    marks = sorted(pos for pos in (raw.find('[%s]: ' % t) for t in theme_ids) if pos >= 0)
    stories = {}
    for theme_id in theme_ids:
        marker = raw.find('[%s]: ' % theme_id)
        if marker < 0:
            continue
        nxt = min([m for m in marks if m > marker] + [len(raw)])
        obj = json_after(raw[:nxt], marker)
        if isinstance(obj, dict) and isinstance(obj.get('slides'), list) and obj['slides']:
            stories[theme_id] = obj
    return stories


class Umlauts(object):
    """German only: repair ae/oe/ue spellings the model copied from ASCII sources, guided by a word list.

    A word is replaced only when its ASCII form is unknown and the umlaut form is known; without a
    word list on this host the step is skipped and reported.
    """
    LISTS = ('/usr/share/dict/ngerman', '/usr/share/dict/german', '/usr/share/dict/ogerman')
    PAIRS = (('ae', 'ä'), ('oe', 'ö'), ('ue', 'ü'), ('Ae', 'Ä'), ('Oe', 'Ö'), ('Ue', 'Ü'))

    def __init__(self, enabled):
        self.words, self.fixed = set(), 0
        for cand in self.LISTS if enabled else ():
            if os.path.isfile(cand):
                with open(cand, encoding='utf-8', errors='replace') as fh:
                    self.words = {w.strip() for w in fh if w.strip()}
                break
        self.lower = {w.lower() for w in self.words}

    def exact(self, word):
        low = word.lower()
        if word in self.words or low in self.lower:
            return True
        return any(low.endswith(suf) and low[:-len(suf)] in self.lower for suf in ('en', 'er', 'es', 'em', 'n', 'e', 's'))

    def known(self, word):
        if self.exact(word):
            return True
        low = word.lower()
        return any(self.exact(low[:i]) and self.exact(low[i:]) for i in range(3, len(low) - 3))

    def word(self, match):
        word = match.group(0)
        if not self.words or self.exact(word):
            return word
        cand = word
        for a, b in self.PAIRS:
            cand = cand.replace(a, b)
        for option in (cand, cand.replace('ss', 'ß')):
            if option != word and self.known(option):
                self.fixed += 1
                return option
        return word

    def __call__(self, text):
        if not self.words:
            return text
        parts = re.split(r'(`[^`]*`|\[\[[^\]]*\]\])', text)
        return ''.join(p if i % 2 else re.sub(r'[A-Za-zÄÖÜäöüß]*(?:ae|oe|ue|Ae|Oe|Ue)[A-Za-zÄÖÜäöüß]*', self.word, p)
                       for i, p in enumerate(parts))


class Deck(object):
    def __init__(self, work, language, stories, switch=None):
        self.T = load_text(language)
        self.language = language
        with open(os.path.join(work, 'facts.json'), encoding='utf-8') as fh:
            self.F = json.load(fh)
        if self.F.get('language', language) != language:
            raise ValueError('facts.json was measured for language %r, not %r' % (self.F.get('language'), language))
        self.N = self.F['numbers']
        self.known_digits = set(re.findall(r'\d+', json.dumps(self.N).replace('.', '')))
        packets = os.path.join(work, 'packets')
        for name in sorted(os.listdir(packets)) if os.path.isdir(packets) else []:
            with open(os.path.join(packets, name), encoding='utf-8') as fh:
                self.known_digits |= set(re.findall(r'\d+', fh.read().replace('.', '')))
        self.stories = stories
        self.switch = switch
        self.errors, self.warnings = [], []
        self.skip_check = False
        self.umlauts = Umlauts(language == 'de')

    # -- text helpers ------------------------------------------------------

    def num(self, n):
        return '{:,}'.format(n).replace(',', self.T['thousands'])

    def fact(self, key):
        if key in self.N:
            return self.num(self.N[key])
        if key in FACT_WORDS:
            return str(self.F[key])
        self.errors.append('unknown fact reference [[%s]]' % key)
        return '?'

    def check_numbers(self, text, where):
        if self.skip_check:
            return
        plain = re.sub(r'\[\[[A-Za-z_0-9]+\]\]', '', text)
        dates = {self.F.get('measured_at'), self.F.get('ops_collected')}
        plain = re.sub(r'\b(\d{4})-(\d{2})-(\d{2})\b', lambda m: '' if m.group(0) in dates else m.group(0), plain)
        plain = re.sub(r'\b(\d{1,2})\.(\d{1,2})\.(\d{4})\b',
                       lambda m: '' if '%s-%02d-%02d' % (m.group(3), int(m.group(2)), int(m.group(1))) in dates else m.group(0), plain)
        for tok in re.findall(r'\d[\d.,]*\d', plain):
            digits = tok.replace('.', '').replace(',', '')
            if len(digits) >= 2 and digits not in self.known_digits:
                self.warnings.append('%s: number "%s" is in no source' % (where, tok))

    def fmt(self, text, where=''):
        text = self.umlauts(str(text or '').strip())
        unknown = [k for k in re.findall(r'\[\[([A-Za-z_0-9]+)\]\]', text) if k not in self.N and k not in FACT_WORDS]
        if unknown:
            self.warnings.append('%s: text field dropped, unknown fact reference [[%s]]' % (where, ']], [['.join(unknown)))
            return ''
        self.check_numbers(text, where)
        text = html.escape(text, quote=False)
        text = re.sub(r'\*\*(.+?)\*\*', r'<strong>\1</strong>', text)
        text = re.sub(r'`([^`]+)`', r'<code>\1</code>', text)
        return re.sub(r'\[\[([A-Za-z_0-9]+)\]\]', lambda m: '<span class="fact">%s</span>' % self.fact(m.group(1)), text)

    def items_of(self, slide, where, limit=6):
        items = slide.get('items') or []
        if len(items) > limit:
            self.warnings.append('%s: %d entries, cut to %d' % (where, len(items), limit))
        result = []
        for it in items[:limit]:
            if isinstance(it, str):
                it = {'text': it}
            if isinstance(it, dict):
                result.append((self.fmt(it.get('head'), where), self.fmt(it.get('text'), where)))
        return result

    def head(self, slide, where, default_title=''):
        parts = []
        if slide.get('kicker'):
            parts.append('<p class="kicker">%s</p>' % self.fmt(slide['kicker'], where))
        parts.append('<h2>%s</h2>' % self.fmt(slide.get('title') or default_title, where))
        if slide.get('lead'):
            parts.append('<p class="lead">%s</p>' % self.fmt(slide['lead'], where))
        return ''.join(parts)

    @staticmethod
    def kind(block_type):
        return 'ai' if block_type in AI_TYPES else 'human' if block_type in HUMAN_TYPES else 'det'

    def chips(self, values, cls=''):
        return ''.join('<span class="mod %s">%s</span>' % (cls, html.escape(str(v))) for v in values)

    # -- data slides -------------------------------------------------------

    def data_numbers(self):
        N, T = self.N, self.T['numbers']
        cells = ''.join('<div class="tile"><b>%s</b><span>%s</span></div>' % (self.num(N[k]), html.escape(label))
                        for k, label in T['tiles'].items())
        share = round(100.0 * N['commits_ai_coauthored'] / max(1, N['commits_total']))
        note = T['note'] % (share, self.num(N['loc_backend_tests']), self.num(N['loc_backend']))
        return '<div class="tiles">%s</div><p class="measure">%s</p>' % (cells, note)

    def data_blocks(self):
        K = self.T['kinds']
        chips = ''.join('<div class="block %s"><code>%s</code><span>%s</span></div>'
                        % (self.kind(b['type']), html.escape(b['type']), html.escape(self.umlauts(b['desc'])))
                        for b in self.F['block_types'])
        counts = dict((k, sum(1 for b in self.F['block_types'] if self.kind(b['type']) == k)) for k in ('det', 'ai', 'human'))
        legend = ('<p class="legend"><i class="det"></i>%s · %d<i class="ai"></i>%s · %d<i class="human"></i>%s · %d</p>'
                  % (K['det'], counts['det'], K['ai'], counts['ai'], K['human'], counts['human']))
        return '<div class="blocks">%s</div>%s' % (chips, legend)

    def data_modules(self):
        tiers = []
        for mod in self.F['modules']:
            if not tiers or tiers[-1][0] != mod['tier']:
                tiers.append((mod['tier'], []))
            tiers[-1][1].append(mod)
        cols = ''.join('<div class="tier"><h3>%s <small>%d</small></h3>%s</div>' % (
            html.escape(t), len(ms), ''.join('<span class="mod %s">%s</span>' % (
                html.escape(m['maturity']), html.escape(m['name'])) for m in ms)) for t, ms in tiers)
        legend = ('<p class="legend"><i class="stable"></i>stable<i class="experimental"></i>experimental'
                  '<i class="legacy"></i>legacy · %s</p>' % self.T['modules_note'])
        return '<div class="tiers">%s</div>%s' % (cols, legend)

    def data_activity(self):
        A = self.T['activity']
        data = self.F['activity']
        peak = max([v for _, v in data] + [1])
        w, h, gap = 1240.0, 330.0, 22.0
        bar = (w - gap * (len(data) - 1)) / len(data)
        parts = []
        for i, (month, value) in enumerate(data):
            bh = max(2.0, h * value / peak) if value else 2.0
            x, y = i * (bar + gap), h - bh
            parts.append('<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" rx="6"/>' % (x, y, bar, bh))
            if value:
                parts.append('<text class="v" x="%.1f" y="%.1f">%s</text>' % (x + bar / 2, y - 12, self.num(value)))
            parts.append('<text class="m" x="%.1f" y="%.1f">%s</text>' % (x + bar / 2, h + 36, month[5:] + '/' + month[2:4]))
        svg = ('<svg class="chart" viewBox="0 -40 %d %d" role="img" aria-label="%s">%s</svg>'
               % (w, h + 90, html.escape(A['label']), ''.join(parts)))
        note = A['note'] % (self.F['first_commit_month'], self.num(self.N['commits_last_30_days']))
        return svg + '<p class="measure">%s</p>' % note

    def data_guardrails(self):
        G = self.T['guardrails']
        chips = ''.join('<span class="mod stable">%s</span>' % html.escape(g) for g in self.F['guards'])
        hard = ''.join('<span class="mod experimental">%s</span>' % html.escape(m['name'])
                       for m in self.F['modules'] if m['tier'] == 'hardening')
        return ('<div class="guards"><div><h3>%s <small>%d</small></h3>%s</div>'
                '<div><h3>%s <small>%d</small></h3>%s</div></div>'
                % (G['tests'], len(self.F['guards']), chips, G['hardening'], self.N['hardening_modules'], hard))

    def data_cost(self):
        C, K = self.T['cost'], self.T['kinds']
        rungs = [('det', 'shell') + tuple(C['rungs'][0]), ('ai', 'simple') + tuple(C['rungs'][1]), ('ai', 'agentic') + tuple(C['rungs'][2])]
        ladder = ''.join('<div class="rung %s"><code>%s</code><b>%s</b><span>%s</span></div>' % rg for rg in rungs)
        bars = ''
        ops = self.F.get('ops') or {}
        groups = {'det': ('shell', 'conditional', 'compress', 'integration', 'document', 'switch'),
                  'ai': ('simple', 'agentic', 'skill_capability', 'compress_ai'),
                  'human': ('approval', 'human_input'), 'wrap': ('foreach', 'retry', 'while')}
        got = dict((g, sum(ops.get('steps_' + k, {}).get('value', 0) for k in keys)) for g, keys in groups.items())
        total = sum(got.values())
        if total:
            segs = ''.join('<i class="%s" style="width:%.2f%%"></i>' % (g, 100.0 * got[g] / total)
                           for g in ('det', 'ai', 'human', 'wrap') if got[g])
            shell = '<code>shell</code> %s' % self.num(ops.get('steps_shell', {}).get('value', 0))
            bars += ('<div class="mix main"><p><b>%s</b> · %s · <em>%s</em>, %s</p><div class="mixbar">%s</div></div>'
                     % (C['runs'], C['steps'] % self.num(total), C['share'] % round(100.0 * got['det'] / total),
                        C['of_shell'] % shell, segs))
        for label, key in ((C['builtin'], 'builtin'), (C['pool'], 'pool')):
            m = self.F['mixes'][key]
            total = max(1, m['blocks'])
            segs = ''.join('<i class="%s" style="width:%.2f%%"></i>' % (k, 100.0 * m[k] / total) for k in ('det', 'ai', 'human') if m[k])
            bars += ('<div class="mix"><p><b>%s</b> · %s · <em>%s</em></p><div class="mixbar">%s</div></div>'
                     % (label, C['mix'] % (m['files'], m['blocks']), C['share'] % round(100.0 * m['det'] / total), segs))
        notes = ([C['legend_runs'] % (self.F.get('ops_collected') or '—')] if ops else []) + [C['legend_examples']]
        legend = ('<p class="legend"><i class="det"></i>%s<i class="ai"></i>%s<i class="human"></i>%s<i class="wrap"></i>%s · %s</p>'
                  % (K['det'], K['ai'], K['human_short'], K['wrap'], ' · '.join(notes)))
        return '<div class="costgrid"><div class="ladder">%s</div><div>%s%s</div></div>' % (ladder, bars, legend)

    def data_connect(self):
        C = self.T['connect']
        wiring = [b['type'] for b in self.F['block_types'] if b['type'] in (
            'sub-workflow', 'skill-capability', 'integration', 'document', 'foreach', 'todo-create', 'todo-update', 'todo-close')]
        entry = C['entries']
        return ('<div class="tiers three"><div class="tier"><h3>%s <small>%d</small></h3>%s<p class="hint">%s</p></div>'
                '<div class="tier"><h3>%s <small>%d</small></h3>%s<p class="hint">%s</p></div>'
                '<div class="tier"><h3>%s <small>%d</small></h3>%s<p class="hint">%s</p></div></div>'
                % (C['triggers'], len(entry), self.chips(entry), C['triggers_hint'],
                   C['integrations'], len(self.F['providers']), self.chips(self.F['providers'], 'stable'), C['integrations_hint'],
                   C['wiring'], len(wiring), self.chips(wiring), C['wiring_hint']))

    def data_providers(self):
        P, N = self.T['providers'], self.N
        direct = self.F.get('direct_providers') or []
        tiles = [(self.num(len(direct)), P['direct'] % ', '.join(direct))]
        if self.F.get('has_pi'):
            tiles.append(('PI', P['pi']))
            if N.get('pi_models'):
                tiles.append((self.num(N['pi_models']), P['pi_models'] % (', '.join(self.F.get('pi_backends') or []) or '—')))
        if 'ops_workflows_explicit_provider' in N and N.get('ops_workflows'):
            tiles.append(('%s / %s' % (self.num(N['ops_workflows_explicit_provider']), self.num(N['ops_workflows'])), P['explicit']))
        cells = ''.join('<div class="tile"><b>%s</b><span>%s</span></div>' % t for t in tiles)
        return ('<div class="tiles wide">%s</div><p class="measure">%s</p>'
                % (cells, P['measure'] % (self.num(N['shipped_blocks_in_container']), self.num(N['shipped_blocks']))))

    def data_config(self):
        groups = sorted(self.F.get('config_groups', {}).items(), key=lambda kv: (-kv[1], kv[0]))
        cells = ''.join('<span class="mod">%s <small>%d</small></span>' % (html.escape(k), v) for k, v in groups)
        return ('<div class="cfg">%s</div><p class="measure">%s</p>'
                % (cells, self.T['config_note'] % (self.num(self.N['config_keys']), self.num(self.N['config_groups']))))

    # -- deck ----------------------------------------------------------------

    def slides(self):
        T, F, N = self.T, self.F, self.N

        def items(pairs):
            return [{'head': h, 'text': t} for h, t in pairs]

        hero_lead = F.get('hero_lead', '')
        slides = [
            {'layout': 'hero', 'kicker': T['hero']['kicker'], 'title': 'MySuricates', 'lead': hero_lead},
            {'layout': 'cards', 'kicker': T['colony']['kicker'], 'fixed': True, 'title': T['colony']['title'],
             'lead': T['colony']['lead'], 'items': items(T['colony']['items'])},
            {'layout': 'statement', 'kicker': T['hammer']['kicker'], 'fixed': True, 'title': T['hammer']['title'], 'lead': T['hammer']['lead']},
            {'layout': 'steps', 'kicker': T['cycle']['kicker'], 'title': T['cycle']['title'], 'cycle': True, 'fixed': True,
             'items': items(T['cycle']['items'])},
        ]
        for theme in F['themes']:
            kicker = (T['kicker_idea'] % theme['idea'] if theme['idea'] else theme.get('kicker') or T['kicker_topic']) + ' · ' + theme['short']
            got = self.stories.get(theme['id'])
            if got:
                for sl in got['slides'][:2]:
                    if isinstance(sl, dict):
                        sl = dict(sl, kicker=kicker)
                        sl.pop('fixed', None)
                        if sl.get('layout') in ('hero', 'closing', 'data'):
                            sl['layout'] = 'bullets'
                        slides.append(sl)
                if len(got['slides']) > 2:
                    self.warnings.append('topic %s: %d AI slides, cut to 2' % (theme['id'], len(got['slides'])))
            else:
                self.warnings.append('topic %s: no valid AI answer, deterministic slides only' % theme['id'])
                slides.append({'layout': 'statement', 'kicker': kicker, 'title': theme['short'], 'lead': theme['idea_text'], 'fixed': True})
            dia = theme.get('diagram')
            if dia and dia.get('steps'):
                slides.append({'layout': 'steps', 'kicker': kicker, 'title': dia['title'], 'cycle': dia['cycle'], 'fixed': True,
                               'items': [{'head': step} for step in dia['steps']]})
            leads = (got or {}).get('data_leads') or {}
            for source in theme['data']:
                slides.append({'layout': 'data', 'source': source, 'kicker': kicker,
                               'lead': leads.get(source) if isinstance(leads, dict) else None})
        for source in ('config', 'numbers', 'activity'):
            slides.append({'layout': 'data', 'source': source, 'kicker': T['kicker_measured'], 'fixed': True})
        share = round(100.0 * N['commits_ai_coauthored'] / max(1, N['commits_total']))
        closing_items = [list(x) for x in T['closing']['items']]
        # Without operational data every number comes from the repository; say that instead.
        closing_items[2][1] = closing_items[2][1] % F['ops_collected'] if F.get('ops') and F.get('ops_collected') \
            else T['closing']['context_repo'] % (F['commit'] or '—')
        slides.append({'layout': 'closing', 'kicker': T['closing']['kicker'], 'title': T['closing']['title'], 'fixed': True,
                       'lead': T['closing']['lead'] % (F['version'], self.num(N['months_active']), self.num(N['block_types']),
                                                       self.num(N['builtin_templates']), self.num(N['backend_tests']), share),
                       'items': items(closing_items)})
        return slides

    def sections(self):
        renderers = {'cost': self.data_cost, 'connect': self.data_connect, 'providers': self.data_providers,
                     'config': self.data_config, 'numbers': self.data_numbers, 'blocks': self.data_blocks,
                     'modules': self.data_modules, 'activity': self.data_activity, 'guardrails': self.data_guardrails}
        layouts = {'hero', 'statement', 'bullets', 'cards', 'steps', 'quote', 'data', 'closing'}
        logo = self.F.get('app_logo')
        out, seen_data = [], set()
        for idx, slide in enumerate(self.slides(), 1):
            where = 'slide %d' % idx
            if not isinstance(slide, dict):
                self.errors.append('%s is not an object' % where)
                continue
            self.skip_check = bool(slide.get('fixed'))
            layout = slide.get('layout')
            if layout not in layouts:
                self.warnings.append('%s: unknown layout "%s", rendered as bullets' % (where, layout))
                layout = 'bullets'
            if layout == 'hero':
                body = ('<p class="kicker">%s</p><h1>%s</h1><p class="lead">%s</p><p class="meta">%s</p>'
                        % (self.fmt(slide.get('kicker'), where), self.fmt(slide.get('title'), where), self.fmt(slide.get('lead'), where),
                           html.escape(self.T['hero']['meta'] % (self.F['version'], self.F['measured_at'], self.F['commit']))))
            elif layout == 'statement':
                body = self.head(slide, where)
            elif layout == 'quote':
                body = '<blockquote>%s</blockquote><p class="source">%s</p>' % (
                    self.fmt(slide.get('title') or slide.get('text'), where), self.fmt(slide.get('lead') or slide.get('source'), where))
            elif layout == 'data':
                source = slide.get('source')
                if source not in renderers:
                    self.errors.append('%s: unknown data source "%s"' % (where, source))
                    continue
                if source in seen_data:
                    self.warnings.append('%s: data source "%s" twice, skipped' % (where, source))
                    continue
                seen_data.add(source)
                body = self.head(slide, where, self.T['data_titles'][source]) + renderers[source]()
                layout = 'data l-data-' + source
            else:
                entries = self.items_of(slide, where, 5 if layout == 'steps' else 6)
                if not entries:
                    self.warnings.append('%s: layout %s without entries' % (where, layout))
                if layout == 'steps':
                    inner = ''.join('<li><b>%s</b><span>%s</span></li>' % it for it in entries)
                    cycle = ' cycle" data-again="%s' % html.escape(self.T['cycle']['again']) if slide.get('cycle') else ''
                    body = self.head(slide, where) + '<ol class="flow%s">%s</ol>' % (cycle, inner)
                elif layout == 'cards':
                    inner = ''.join('<div class="card"><b>%s</b><span>%s</span></div>' % it for it in entries)
                    body = self.head(slide, where) + '<div class="cards n%d">%s</div>' % (len(entries), inner)
                else:
                    inner = ''.join('<li>%s<span>%s</span></li>' % ('<b>%s</b>' % h if h else '', t) for h, t in entries)
                    body = self.head(slide, where) + '<ul class="points">%s</ul>' % inner
                    if layout == 'closing':
                        body += '<p class="meta">%s</p>' % html.escape(self.T['closing']['meta'])
            if '<h2></h2>' in body or '<blockquote></blockquote>' in body or '<h1></h1>' in body:
                self.warnings.append('%s: without a title, skipped' % where)
                continue
            density = len(re.sub(r'<[^>]+>', '', body))
            if density > 900 and not layout.startswith('data'):
                self.warnings.append('%s: a lot of text (%d characters)' % (where, density))
            note = '<aside class="note">%s</aside>' % self.fmt(slide.get('note'), where) if slide.get('note') else ''
            if logo:
                body += '<svg class="%s" viewBox="%s" fill="none" aria-hidden="true"><use href="#meerkats"/></svg>' % (
                    'brand' if layout == 'hero' else 'mark', logo['viewBox'])
            elif layout == 'hero' and self.F.get('logo_b64'):
                body += '<div class="tile-logo"></div>'
            out.append('<section class="slide l-%s%s">%s%s</section>' % (layout, ' dense' if density > 620 else '', body, note))
        return out

    def page(self):
        sections = self.sections()
        logo = self.F.get('app_logo')
        logo_css = (':root{--logo:url("data:image/svg+xml;base64,%s")}' % self.F['logo_b64']) if self.F.get('logo_b64') else ':root{--logo:none}'
        if not logo:
            self.warnings.append('app logo (MeerkatIcon) not readable%s' % (', favicon tile instead' if self.F.get('logo_b64') else ''))
        symbol = ('<svg width="0" height="0" style="position:absolute" aria-hidden="true"><symbol id="meerkats" viewBox="%s">%s</symbol></svg>'
                  % (logo['viewBox'], logo['inner'])) if logo else ''
        alternate = switch = ''
        if self.switch:
            alternate = '<link rel="alternate" hreflang="%s" href="%s">' % (self.switch['hreflang'], html.escape(self.switch['file']))
            switch = '<a class="lang" href="%s" hreflang="%s">%s</a>' % (
                html.escape(self.switch['file']), self.switch['hreflang'], html.escape(self.switch['label']))
        page = ('<!doctype html><html lang="%s"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
                '<title>MySuricates</title>%s<style>%s</style></head><body>%s<main class="stage">%s</main><div class="bar"></div><div class="count"></div>'
                '<div class="help">%s</div>%s<script>%s</script></body></html>'
                % (self.T['html_lang'], alternate, logo_css + CSS, symbol, '\n'.join(sections), html.escape(self.T['help']), switch, JS))
        if re.search(r'(?:src|href)\s*=\s*["\']?(?:https?:)?//|@import|url\(\s*["\']?(?:https?:)?//', page):
            self.errors.append('HTML contains external resources')
        return page, len(sections)


def render(work, language, story_raw, out, switch=None):
    """Render the deck; returns the list of report lines and the exit code (0 = CHECK: OK)."""
    with open(os.path.join(work, 'facts.json'), encoding='utf-8') as fh:
        theme_ids = [t['id'] for t in json.load(fh)['themes']]
    stories = parse_stories(story_raw, theme_ids)
    if not stories:
        return ['CHECK: ERROR — not a single topic answer with valid JSON (see story-raw.txt)'], 1
    deck = Deck(work, language, stories, switch)
    page, count = deck.page()
    if deck.errors:
        return ['CHECK: ERROR — ' + e for e in deck.errors], 1
    parent = os.path.dirname(os.path.abspath(out))
    os.makedirs(parent, exist_ok=True)
    with open(out, 'w', encoding='utf-8') as fh:
        fh.write(page)
    ai_slides = sum(min(2, len(s['slides'])) for s in stories.values())
    lines = ['PRESENTATION: ' + out,
             'SLIDES: %d · %d written by the AI (%d/%d topics), %d deterministic · %d bytes · 0 external resources'
             % (count, ai_slides, len(stories), len(theme_ids), count - ai_slides, len(page.encode('utf-8')))]
    if language == 'de':
        lines.append('UMLAUTS: %s' % ('%d words repaired from the word list' % deck.umlauts.fixed if deck.umlauts.words
                                      else 'no word list found, step skipped'))
    lines += ['CHECK: NOTE — ' + w for w in deck.warnings]
    lines.append('CHECK: %s' % ('OK with %d notes' % len(deck.warnings) if deck.warnings else 'OK'))
    return lines, 0


CSS = r'''
:root{--bg:#15130f;--ink:#f3ede2;--mute:#a99f8e;--line:#3a352c;--panel:#1f1c17;--accent:#e2a63d;--det:#8fa3a0;--ai:#b79cf0;--human:#f08a5d;--ok:#6fbf8a}
@media (prefers-color-scheme:light){:root{--bg:#f4efe6;--ink:#1d1a15;--mute:#6d6557;--line:#d9d0c0;--panel:#fbf8f2;--accent:#a96a06;--det:#4f6764;--ai:#6a45c4;--human:#c2501f;--ok:#2f7d4d}}
*{box-sizing:border-box}html,body{margin:0;height:100%;background:var(--bg);color:var(--ink);overflow:hidden;font-family:"Avenir Next","Segoe UI",system-ui,sans-serif}
.stage{position:fixed;left:50%;top:50%;width:1600px;height:900px;transform-origin:0 0}
.slide{position:absolute;inset:0;padding:90px 120px 100px;display:flex;flex-direction:column;justify-content:center;opacity:0;visibility:hidden;transition:opacity .35s ease,transform .35s ease;transform:translateY(14px)}
.slide.on{opacity:1;visibility:visible;transform:none}
h1,h2,blockquote{font-family:"Iowan Old Style","Palatino Linotype",Palatino,Georgia,serif;font-weight:600;letter-spacing:-.01em;margin:0;text-wrap:balance}
h1{font-size:104px;line-height:1.02}h2{font-size:60px;line-height:1.08;max-width:1250px}
h3{font-size:20px;text-transform:uppercase;letter-spacing:.12em;color:var(--mute);margin:0 0 16px;font-weight:600}h3 small{color:var(--accent);font-size:20px;margin-left:6px}
.kicker{font:600 19px/1 ui-monospace,"SF Mono",Menlo,Consolas,monospace;letter-spacing:.16em;text-transform:uppercase;color:var(--accent);margin:0 0 26px}
.lead{font-size:30px;line-height:1.4;color:var(--mute);max-width:1180px;margin:28px 0 0;text-wrap:pretty}
.meta,.measure,.legend,.source{font:500 18px/1.5 ui-monospace,"SF Mono",Menlo,Consolas,monospace;color:var(--mute);margin:40px 0 0}
strong{color:var(--ink);font-weight:650}code{font-family:ui-monospace,"SF Mono",Menlo,Consolas,monospace;font-size:.9em;color:var(--accent)}
.fact{font-variant-numeric:tabular-nums;color:var(--accent);font-weight:650;border-bottom:2px dotted var(--accent)}
.l-hero h1{font-size:128px}.brand{position:absolute;right:110px;top:50%;translate:0 -50%;width:560px;height:373px;color:var(--accent)}.l-hero h1,.l-hero .lead,.l-hero .kicker,.l-hero .meta{max-width:800px}
.mark{position:absolute;right:120px;top:58px;width:84px;height:56px;color:var(--accent);opacity:.9}.tile-logo{position:absolute;right:120px;top:50%;translate:0 -50%;width:430px;height:430px;border-radius:96px;background:var(--logo) center/cover no-repeat}
.l-statement h2{font-size:84px;max-width:1300px}.l-statement .lead{font-size:34px}
blockquote{font-size:66px;line-height:1.15;font-style:italic;max-width:1300px;border-left:6px solid var(--accent);padding-left:44px}.source{margin-left:50px}
.points{list-style:none;margin:44px 0 0;padding:0;display:grid;gap:22px;max-width:1300px;counter-reset:p}
.points li{counter-increment:p;display:grid;grid-template-columns:64px 1fr;font-size:28px;line-height:1.4;color:var(--mute)}
.points li::before{content:counter(p,decimal-leading-zero);font:600 18px/2.2 ui-monospace,Menlo,monospace;color:var(--accent);grid-row:span 2}
.points b{color:var(--ink);font-size:30px;font-weight:650}.points span{grid-column:2}
.cards{display:grid;gap:22px;margin-top:48px;grid-template-columns:repeat(3,1fr)}.cards.n2,.cards.n4{grid-template-columns:repeat(2,1fr)}
.card,.tile{background:var(--panel);border:1px solid var(--line);border-radius:14px;padding:30px 32px;display:flex;flex-direction:column;gap:12px}
.card b{font-size:27px}.card span{font-size:22px;line-height:1.45;color:var(--mute)}
.flow{list-style:none;display:flex;gap:46px;margin:56px 0 0;padding:0}
.flow li{flex:1;position:relative;background:var(--panel);border:1px solid var(--line);border-top:4px solid var(--accent);border-radius:12px;padding:26px 24px;display:flex;flex-direction:column;gap:10px}
.flow li+li::before{content:"→";position:absolute;left:-38px;top:50%;translate:0 -50%;font-size:30px;color:var(--accent)}
.flow b{font-size:25px}.flow span{font-size:20px;line-height:1.4;color:var(--mute)}
.flow.cycle{padding-bottom:58px;position:relative}.flow.cycle::after{content:attr(data-again);position:absolute;left:0;right:0;bottom:0;text-align:center;font:600 18px ui-monospace,Menlo,monospace;letter-spacing:.1em;color:var(--accent);border:1px dashed var(--line);border-top:0;border-radius:0 0 14px 14px;padding:12px}
.l-data h2{font-size:50px}.l-data .lead{font-size:25px;margin-top:16px}
.tiles{display:grid;grid-template-columns:repeat(4,1fr);gap:20px;margin-top:40px}.tile{padding:26px 28px;gap:6px}
.tile b{font:600 58px/1 "Iowan Old Style",Georgia,serif;font-variant-numeric:tabular-nums;color:var(--accent)}.tile span{font-size:20px;color:var(--mute)}
.blocks{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:34px}
.block{border:1px solid var(--line);border-left:5px solid var(--det);border-radius:10px;padding:12px 16px;background:var(--panel);display:flex;flex-direction:column;gap:3px}
.block.ai{border-left-color:var(--ai)}.block.human{border-left-color:var(--human)}.block code{font-size:21px;color:var(--ink)}.block span{font-size:16px;color:var(--mute);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.legend{margin-top:26px}.legend i{display:inline-block;width:12px;height:12px;border-radius:3px;margin:0 8px 0 22px;background:var(--det)}.legend i:first-child{margin-left:0}
i.ai{background:var(--ai)}i.human{background:var(--human)}i.stable{background:var(--ok)}i.experimental{background:var(--accent)}i.legacy{background:var(--mute)}
.tiers{display:grid;grid-template-columns:repeat(4,1fr);gap:28px;margin-top:40px}.guards{display:grid;grid-template-columns:2fr 1fr;gap:48px;margin-top:40px}
.mod{display:inline-block;font:500 18px/1 ui-monospace,Menlo,monospace;padding:9px 12px;margin:0 8px 9px 0;border:1px solid var(--line);border-radius:8px;background:var(--panel)}
.mod::before{content:"";display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:9px;background:var(--mute)}.mod.stable::before{background:var(--ok)}.mod.experimental::before{background:var(--accent)}
.costgrid{display:grid;grid-template-columns:5fr 6fr;gap:56px;margin-top:40px;align-items:start}.ladder{display:grid;gap:14px}
.rung{display:grid;grid-template-columns:150px 1fr;gap:2px 20px;align-items:baseline;background:var(--panel);border:1px solid var(--line);border-left:6px solid var(--det);border-radius:12px;padding:20px 24px}
.rung.ai{border-left-color:var(--ai)}.rung:nth-child(2){margin-left:36px}.rung:nth-child(3){margin-left:72px}.rung code{font-size:26px;color:var(--ink)}.rung b{font:600 34px/1 "Iowan Old Style",Georgia,serif;color:var(--accent)}.rung span{grid-column:2;font-size:18px;color:var(--mute)}
.mix{margin-bottom:30px}.mix p{font-size:21px;color:var(--mute);margin:0 0 12px}.mix b{color:var(--ink)}.mix em{font-style:normal;color:var(--accent);font-weight:650}
.mixbar{display:flex;height:42px;border-radius:10px;overflow:hidden;border:1px solid var(--line)}.mixbar i{display:block;height:100%;background:var(--det)}.mixbar i.ai{background:var(--ai)}.mixbar i.human{background:var(--human)}
.tiers.three{grid-template-columns:repeat(3,1fr)}.hint{font:500 17px/1.45 ui-monospace,Menlo,monospace;color:var(--mute);margin:14px 0 0}
.tiles.wide .tile b{font-size:50px}.tiles.wide .tile span{font-size:18px;line-height:1.4}.tiles.wide code{font-size:.95em}.tiles.three{grid-template-columns:repeat(3,1fr)}.tiles.three .tile b{font-size:50px}.tiles.three .tile span{font-size:19px;line-height:1.4}
.cfg{margin-top:36px;max-width:1300px}.cfg .mod{font-size:20px;padding:11px 14px}.cfg .mod::before{display:none}.cfg small{color:var(--accent);margin-left:6px;font-size:16px}
.mix.main p{font-size:24px}.mix.main .mixbar{height:58px}.mixbar i.wrap,i.wrap{background:var(--mute)}.flow b:only-child{font-size:27px}
.chart{width:100%;margin-top:30px}.chart rect{fill:var(--accent)}.chart text{text-anchor:middle;font:500 19px ui-monospace,Menlo,monospace;fill:var(--mute)}.chart .v{fill:var(--ink)}
.dense .lead{font-size:25px}.dense .points li{font-size:24px}.dense .points b{font-size:26px}.dense .card span{font-size:20px}.dense h2{font-size:52px}
.note{display:none;position:absolute;left:120px;right:120px;bottom:24px;font-size:18px;color:var(--mute);border-top:1px solid var(--line);padding-top:10px}.notes .note{display:block}
.bar{position:fixed;left:0;bottom:0;height:4px;background:var(--accent);transition:width .3s}.count{position:fixed;right:22px;bottom:14px;font:500 14px ui-monospace,Menlo,monospace;color:var(--mute)}
.lang{position:fixed;right:22px;top:14px;font:600 14px ui-monospace,Menlo,monospace;color:var(--accent);text-decoration:none;opacity:.8;z-index:2}@media print{.lang{display:none}}
.help{position:fixed;left:22px;bottom:14px;font:500 13px ui-monospace,Menlo,monospace;color:var(--mute);opacity:.55}
body.grid{overflow:auto}body.grid .stage{position:static;transform:none!important;width:auto;height:auto;display:grid;grid-template-columns:repeat(auto-fill,minmax(400px,1fr));gap:20px;padding:24px;margin:0}
body.grid .slide{position:relative;inset:auto;width:1600px;height:900px;zoom:.25;opacity:1;visibility:visible;transform:none;border:4px solid var(--line);border-radius:24px;cursor:pointer}body.grid .slide.on{border-color:var(--accent)}
@media print{@page{size:1600px 900px;margin:0}html,body{overflow:visible;height:auto}.stage{position:static;transform:none!important;margin:0}.slide{position:relative;opacity:1;visibility:visible;transform:none;height:900px;page-break-after:always}.bar,.count,.help{display:none}}
@media (prefers-reduced-motion:reduce){.slide{transition:none}}
'''

JS = r'''
(function(){var s=[].slice.call(document.querySelectorAll('.slide')),st=document.querySelector('.stage'),bar=document.querySelector('.bar'),cnt=document.querySelector('.count'),i=0;
function fit(){if(document.body.classList.contains('grid'))return;var k=Math.min(innerWidth/1600,innerHeight/900);st.style.transform='scale('+k+') translate(-50%,-50%)';}
function go(n){i=Math.max(0,Math.min(s.length-1,n));s.forEach(function(e,j){e.classList.toggle('on',j===i);});bar.style.width=((i+1)/s.length*100)+'%';cnt.textContent=(i+1)+' / '+s.length;try{history.replaceState(null,'','#'+(i+1));}catch(e){}}
function grid(on){document.body.classList.toggle('grid',on);if(on){st.style.transform='';}else{fit();}}
addEventListener('resize',fit);
addEventListener('keydown',function(e){var k=e.key;if(k==='ArrowRight'||k===' '||k==='PageDown'||k==='Enter'){go(i+1);e.preventDefault();}else if(k==='ArrowLeft'||k==='PageUp'||k==='Backspace'){go(i-1);e.preventDefault();}else if(k==='Home'){go(0);}else if(k==='End'){go(s.length-1);}else if(k==='o'||k==='Escape'){grid(k==='o'?!document.body.classList.contains('grid'):false);}else if(k==='n'){document.body.classList.toggle('notes');}else if(k==='f'){if(document.fullscreenElement){document.exitFullscreen();}else{document.documentElement.requestFullscreen();}}});
s.forEach(function(e,j){e.addEventListener('click',function(ev){if(document.body.classList.contains('grid')){grid(false);go(j);}else{go(i+(ev.clientX<innerWidth/3?-1:1));}});});
var x0=null;addEventListener('touchstart',function(e){x0=e.touches[0].clientX;},{passive:true});addEventListener('touchend',function(e){if(x0===null)return;var d=e.changedTouches[0].clientX-x0;if(Math.abs(d)>50)go(i+(d<0?1:-1));x0=null;});
fit();go((parseInt(location.hash.slice(1),10)||1)-1);})();
'''


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('action', choices=['facts', 'themes', 'render', 'output'])
    parser.add_argument('--root', default='.', help='repository root (default: current directory)')
    parser.add_argument('--work', help='work directory of this run (facts.json, packets/, themes.txt)')
    parser.add_argument('--language', default='en', choices=LANGUAGES)
    parser.add_argument('--themes', default='all', help='comma list of topic ids; empty or "all" = every topic')
    parser.add_argument('--core-ideas', default='', help='numbered guiding ideas; empty = the README section')
    parser.add_argument('--big-idea', default='', help='the one idea behind all topics; empty = presentation_text.json')
    parser.add_argument('--audience', default='', help='audience of the deck; empty = presentation_text.json')
    parser.add_argument('--ops-facts', default='', help='operational counters, relative to --root (default %s)' % DEFAULT_OPS)
    parser.add_argument('--no-host-probe', action='store_true', help='do not ask PI which models this host reaches')
    parser.add_argument('--story-file', help='render: the foreach output with one [topic]: answer per item')
    parser.add_argument('--out', default='', help='render: output file relative to --root; empty = the language default')
    args = parser.parse_args(argv)

    if args.action == 'output':
        print(default_output(args.language))
        return 0
    if not args.work:
        parser.error('--work is required for ' + args.action)
    try:
        if args.action == 'facts':
            facts, packets = measure(os.path.abspath(args.root), args.language, args.themes, args.core_ideas, args.big_idea,
                                     args.audience, args.ops_facts, not args.no_host_probe)
            write_facts(args.work, facts, packets)
            print('\n'.join(facts_summary(facts)))
            return 0
        if args.action == 'themes':
            with open(os.path.join(args.work, 'themes.txt'), encoding='utf-8') as fh:
                sys.stdout.write(fh.read())
            return 0
        if not args.story_file:
            parser.error('--story-file is required for render')
        with open(args.story_file, encoding='utf-8') as fh:
            raw = fh.read()
        target = args.out or default_output(args.language)
        out = os.path.join(os.path.abspath(args.root), target)
        text = load_text(args.language)
        # The language switch links the sibling file, so it only appears at the default location.
        switch = text['switch'] if not args.out or os.path.normpath(args.out) == os.path.normpath(text['output']) else None
        lines, code = render(args.work, args.language, raw, out, switch)
    except (OSError, ValueError) as exc:
        print('CHECK: ERROR — %s' % exc)
        return 1
    print('\n'.join(lines))
    return code


if __name__ == '__main__':
    sys.exit(main())
