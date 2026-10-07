"""Deterministic parts of the self-presentation workflow, in both languages; no AI, no backend.

The pool definition workflow-pool/mysuricates-presentation.json is executed block by block
against this repository: its shell commands run with the placeholders filled in the way the
platform does with shell_escape_variables (single-quoted values), the foreach items come from its
items_command, and a stub text per topic stands in for the one AI call.
"""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
REPO = SCRIPTS.parent
POOL_FILE = REPO / 'workflow-pool' / 'mysuricates-presentation.json'
PLACEHOLDER = re.compile(r'\{\{ ([A-Za-z0-9_.]+) \}\}')
SYSTEM_VARIABLES = {'workflow_run_id', 'block.story.output', 'theme'}


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / 'concepts' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


presentation = load('presentation')


def fill(command, values):
    """Fill placeholders like the platform does with shell_escape_variables: always single-quoted."""
    return PLACEHOLDER.sub(lambda m: shlex.quote(values[m.group(1)]), command)


def stub_story(work, quirks=None):
    """One answer per topic in the foreach output format, using only keys listed in that topic's packet."""
    facts = json.loads((Path(work) / 'facts.json').read_text(encoding='utf-8'))
    parts = []
    for theme in facts['themes']:
        packet = (Path(work) / 'packets' / (theme['id'] + '.txt')).read_text(encoding='utf-8')
        keys = re.findall(r'^([a-z_A-Z0-9]+) = ', packet, flags=re.M)
        slides = [
            {'layout': 'statement', 'title': 'Stub statement %s' % theme['id'], 'lead': 'Measured: [[%s]].' % keys[0], 'note': 'note'},
            {'layout': 'cards', 'title': 'Stub cards %s' % theme['id'], 'lead': 'Two cards.',
             'items': [{'head': 'One', 'text': 'about [[%s]]' % keys[-1]}, {'head': 'Two', 'text': 'plain text'}]},
        ]
        answer = {'theme': theme['id'], 'slides': slides, 'data_leads': dict((d, 'Look at [[%s]].' % keys[0]) for d in theme['data'])}
        if quirks and theme['id'] in quirks:
            answer = quirks[theme['id']](answer)
            if answer is None:
                continue
        parts.append('[%s]: Some prose before the JSON.\n%s' % (theme['id'], json.dumps(answer, ensure_ascii=False)))
    return '\n\n'.join(parts)


OPS_FIXTURE = {
    'collected_at': '2026-10-01T08:00:00+00:00',
    'metrics': {
        'workflows': {'value': 120, 'denominator': None, 'definition': 'Non-deleted workflow definitions'},
        'workflows_explicit_provider': {'value': 14, 'denominator': 120, 'definition': 'Workflows with their own provider'},
        'steps_shell': {'value': 1444, 'denominator': 2230, 'definition': 'Shell steps in 30 days'},
        'steps_simple': {'value': 500, 'denominator': 2230, 'definition': 'Simple steps in 30 days'},
        'steps_agentic': {'value': 86, 'denominator': 2230, 'definition': 'Agentic steps in 30 days'},
        'steps_foreach': {'value': 200, 'denominator': 2230, 'definition': 'Foreach steps in 30 days'},
    },
}


class PoolWorkflowRunTest(unittest.TestCase):
    """Runs the deterministic blocks of the pool definition for de and en."""

    @classmethod
    def setUpClass(cls):
        cls.pool = json.loads(POOL_FILE.read_text(encoding='utf-8'))
        cls.blocks = dict((b['name'], b) for b in cls.pool['blocks'])

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.tmp = Path(self.temp.name)
        # A PI that answers nothing keeps the host probe out of the measurement.
        bin_dir = self.tmp / 'bin'
        bin_dir.mkdir()
        (bin_dir / 'pi').write_text('#!/bin/sh\nexit 127\n')
        (bin_dir / 'pi').chmod(0o755)
        self.env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ.get('PATH', ''))
        (self.tmp / 'ops.json').write_text(json.dumps(OPS_FIXTURE))

    def values(self, language, **extra):
        values = dict(self.pool['defaultContext'])
        values.update({'language': language, 'repo_root': str(REPO), 'work_dir': str(self.tmp / 'work'),
                       'output_file': str(self.tmp / 'out' / (language + '.html')), 'ops_facts': str(self.tmp / 'ops.json'),
                       'workflow_run_id': 'run-' + language})
        values.update(extra)
        return values

    def shell(self, command, values):
        return subprocess.run(['bash', '-c', fill(command, values)], cwd=str(self.tmp), env=self.env,
                              capture_output=True, text=True, timeout=120)

    def run_workflow(self, language, story=None, **extra):
        values = self.values(language, **extra)
        facts = self.shell(self.blocks['facts']['config']['command'], values)
        self.assertEqual(0, facts.returncode, facts.stdout + facts.stderr)
        items = self.shell(self.blocks['story']['config']['items_command'], values)
        self.assertEqual(0, items.returncode, items.stderr)
        work = self.tmp / 'work' / values['workflow_run_id']
        values['block.story.output'] = stub_story(work) if story is None else story(work)
        render = self.shell(self.blocks['render']['config']['command'], values)
        return facts.stdout, items.stdout.split(), render, work, Path(values['output_file'])

    def assert_deck(self, language, other):
        facts_out, items, render, work, out = self.run_workflow(language)
        self.assertEqual([t['id'] for t in presentation.THEMES], items)
        self.assertEqual(0, render.returncode, render.stdout + render.stderr)
        lines = render.stdout.strip().split('\n')
        self.assertEqual('CHECK: OK', lines[-1], render.stdout)
        page = out.read_text(encoding='utf-8')
        text = presentation.load_text(language)
        self.assertIn('<html lang="%s">' % language, page)
        for fixed in (text['colony']['title'], text['hammer']['title'], text['cycle']['title'], text['closing']['title'],
                      text['data_titles']['numbers'], text['help']):
            self.assertIn(fixed, page)
        foreign = presentation.load_text(other)
        for fixed in (foreign['colony']['title'], foreign['hammer']['title'], foreign['closing']['title']):
            self.assertNotIn(fixed, page)
        self.assertIsNone(re.search(r'(?:src|href)\s*=\s*["\']?(?:https?:)?//', page))
        # Every stub slide made it in, with the measured value inserted instead of the reference.
        self.assertEqual(16, page.count('Stub '))
        self.assertNotIn('[[', page)
        self.assertIn('language %s' % language, facts_out)
        # Operational data from the fixture reached the cost slide, formatted for the language.
        self.assertIn('1%s444' % text['thousands'], page)
        story = (work / 'story-raw.txt').read_text(encoding='utf-8')
        self.assertIn('[wofuer]: ', story)
        return page, text

    def test_english_deck(self):
        page, text = self.assert_deck('en', 'de')
        self.assertIn('A research project', page)
        self.assertIn('Guiding idea 1', page)

    def test_german_deck(self):
        page, text = self.assert_deck('de', 'en')
        self.assertIn('Ein Forschungsprojekt', page)
        self.assertIn('Leitidee 1', page)

    def test_topics_follow_the_themes_context(self):
        _, items, render, _, out = self.run_workflow('en', themes='provider,kontrolle')
        self.assertEqual(['provider', 'kontrolle'], items)
        self.assertEqual(0, render.returncode, render.stdout)
        self.assertIn('Stub statement provider', out.read_text(encoding='utf-8'))

    def test_default_output_is_per_language_with_a_switch_link(self):
        for language, rel, sibling in (('en', 'docs/presentation/index.html', 'de.html'),
                                       ('de', 'docs/presentation/de.html', 'index.html')):
            self.assertEqual(rel, presentation.default_output(language))
            root = self.tmp / ('root-' + language)
            work = self.tmp / ('work-' + language)
            with contextlib.redirect_stdout(io.StringIO()):
                facts, packets = presentation.measure(str(REPO), language, host_probe=False)
                presentation.write_facts(str(work), facts, packets)
                (work / 'story.txt').write_text(stub_story(work), encoding='utf-8')
                code = presentation.main(['render', '--root', str(root), '--work', str(work), '--language', language,
                                          '--story-file', str(work / 'story.txt')])
            self.assertEqual(0, code)
            page = (root / rel).read_text(encoding='utf-8')
            self.assertIn('<a class="lang" href="%s"' % sibling, page)


class RenderCheckTest(unittest.TestCase):
    """What the check reports when the AI text is not as asked."""

    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.work = Path(cls.temp.name) / 'work'
        facts, packets = presentation.measure(str(REPO), 'en', host_probe=False)
        presentation.write_facts(str(cls.work), facts, packets)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def render(self, story, language='en'):
        out = Path(self.temp.name) / (self.id().rsplit('.', 1)[-1] + '.html')
        lines, code = presentation.render(str(self.work), language, story, str(out))
        return lines, code, out

    def test_unknown_key_drops_the_field_and_is_reported(self):
        def invent(answer):
            answer['slides'][0]['lead'] = 'Invented: [[made_up_key]].'
            return answer
        lines, code, out = self.render(stub_story(self.work, {'zugang': invent}))
        self.assertEqual(0, code)
        self.assertTrue(any('unknown fact reference [[made_up_key]]' in line for line in lines), lines)
        self.assertNotIn('Invented', out.read_text(encoding='utf-8'))
        self.assertTrue(lines[-1].startswith('CHECK: OK with '), lines[-1])

    def test_written_number_is_reported(self):
        def number(answer):
            answer['slides'][0]['lead'] = 'There are 987654 of them.'
            return answer
        lines, code, _ = self.render(stub_story(self.work, {'provider': number}))
        self.assertEqual(0, code)
        self.assertTrue(any('number "987654" is in no source' in line for line in lines), lines)

    def test_missing_topic_falls_back_to_a_deterministic_slide(self):
        lines, code, out = self.render(stub_story(self.work, {'erfahrung': lambda answer: None}))
        self.assertEqual(0, code)
        self.assertTrue(any('topic erfahrung: no valid AI answer' in line for line in lines), lines)
        self.assertIn(presentation.load_text('en')['themes']['erfahrung']['short'], out.read_text(encoding='utf-8'))

    def test_no_valid_answer_at_all_fails(self):
        lines, code, out = self.render('[wofuer]: no JSON here\n[zugang]: {"slides": []}')
        self.assertEqual(1, code)
        self.assertTrue(lines[-1].startswith('CHECK: ERROR'), lines)
        self.assertFalse(out.exists())

    def test_facts_of_the_other_language_are_refused(self):
        with self.assertRaises(ValueError):
            self.render(stub_story(self.work), language='de')


class TextDictionaryTest(unittest.TestCase):
    """presentation_text.json: both languages carry the same keys and the same format placeholders."""

    def test_languages_match(self):
        with open(presentation.TEXT_FILE, encoding='utf-8') as fh:
            texts = json.load(fh)
        self.assertEqual(set(presentation.LANGUAGES), set(texts))

        def walk(de, en, path):
            self.assertEqual(type(de), type(en), path)
            if isinstance(de, dict):
                self.assertEqual(sorted(de), sorted(en), path)
                for key in de:
                    walk(de[key], en[key], path + '.' + key)
            elif isinstance(de, list):
                self.assertEqual(len(de), len(en), path)
                for i, (a, b) in enumerate(zip(de, en)):
                    walk(a, b, '%s[%d]' % (path, i))
            else:
                self.assertTrue(de.strip() and en.strip(), path)
                self.assertEqual(re.findall(r'%[sd%]', de), re.findall(r'%[sd%]', en), path)

        walk(texts['de'], texts['en'], '')
        self.assertEqual(sorted(t['id'] for t in presentation.THEMES), sorted(texts['en']['themes']))

    def test_sources_exist_in_the_repository(self):
        for language in presentation.LANGUAGES:
            for rel in presentation.load_text(language)['sources'].values():
                self.assertTrue((REPO / rel).exists(), rel)

    def test_unknown_topic_fails(self):
        with self.assertRaises(ValueError):
            presentation.wanted_themes('wofuer,nonsense')


class PoolDefinitionTest(unittest.TestCase):
    """The pool file reads every placeholder from its context and hard-codes no provider or path."""

    def test_placeholders_and_context(self):
        pool = json.loads(POOL_FILE.read_text(encoding='utf-8'))
        context = set(pool['defaultContext'])
        self.assertIn(pool['defaultContext']['language'], presentation.LANGUAGES)
        for block in pool['blocks']:
            config = block['config']
            self.assertNotIn('provider', config)
            self.assertNotIn('model', config)
            for field in ('command', 'items_command'):
                for name in PLACEHOLDER.findall(config.get(field, '')):
                    self.assertIn(name, context | SYSTEM_VARIABLES, '%s.%s: {{ %s }}' % (block['name'], field, name))
            if block['type'] == 'shell' or config.get('items_command'):
                self.assertTrue(config.get('shell_escape_variables'), block['name'])
        shell = '\n'.join(b['config']['command'] for b in pool['blocks'] if b['type'] == 'shell')
        self.assertNotIn("'{{", shell)
        self.assertNotIn('"{{', shell)


if __name__ == '__main__':
    unittest.main()
