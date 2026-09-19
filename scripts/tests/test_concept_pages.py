"""Failure-path checks for the concept-page publication gate; no AI or backend needed."""
import contextlib
import datetime as dt
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / 'concepts' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


pages = load('pages')
collector = load('collect_facts')


class PublicationGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.work = self.root / 'run'
        (self.work / 'drafts').mkdir(parents=True)
        self.catalog = pages.read_json(pages.CATALOG)
        keys = {key for item in self.catalog for key in item['metrics']}
        self.facts = self.root / 'facts.json'
        self.facts.write_text(json.dumps({'schema': 1,
            'collected_at': dt.datetime.now(dt.timezone.utc).isoformat(),
            'limitations': ['Test fixture'],
            'metrics': {key: {'value': 2, 'denominator': 7} for key in keys}}))
        (self.work / 'manifest.json').write_text(json.dumps({'source_commit': 'a' * 40,
            'facts_sha256': pages.digest(self.facts)}))
        for item in self.catalog:
            for source in item['sources']:
                target = self.root / source
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('source fixture')
            body = '\n\n'.join('## ' + heading + '\n\n' +
                ('Ein nachvollziehbarer Satz über Nutzen Grenzen Aufwand und Belege. ' * 6)
                for heading in pages.HEADINGS['de'])
            (self.work / 'drafts' / (item['slug'] + '.md')).write_text(body)
        with contextlib.redirect_stdout(io.StringIO()):
            pages.assemble(self.root, self.work, self.facts)

    def gate(self, language='de', directory=None):
        with contextlib.redirect_stdout(io.StringIO()):
            pages.check(self.root, directory or self.work / 'de', self.facts, language)

    def test_complete_pages_pass(self):
        self.gate()

    def test_missing_page_does_not_pass(self):
        (self.work / 'de/gates.md').unlink()
        with self.assertRaisesRegex(ValueError, 'gates'):
            self.gate()

    def test_edited_diagram_does_not_pass(self):
        path = self.work / 'de/gates.md'
        path.write_text(path.read_text().replace('flowchart LR', 'flowchart TB'))
        with self.assertRaisesRegex(ValueError, 'diagram'):
            self.gate()

    def test_changed_measurement_does_not_pass(self):
        path = self.work / 'de/gates.md'
        path.write_text(path.read_text().replace('| 2 / 7 |', '| 20 / 7 |'))
        with self.assertRaisesRegex(ValueError, 'measurement'):
            self.gate()

    def test_snapshot_swap_does_not_pass(self):
        self.facts.write_text(self.facts.read_text() + '\n')
        with self.assertRaisesRegex(ValueError, 'provenance'):
            self.gate()

    def test_missing_source_does_not_pass(self):
        (self.root / self.catalog[0]['sources'][0]).unlink()
        with self.assertRaisesRegex(ValueError, 'source link'):
            self.gate()

    def test_private_markers_do_not_pass(self):
        path = self.work / 'de/gates.md'
        path.write_text(path.read_text() + '\n/home/example/private\n')
        with self.assertRaisesRegex(ValueError, 'Private data'):
            self.gate()

    def test_old_measurements_rejected_before_ai_call(self):
        data = pages.read_json(self.facts)
        data['collected_at'] = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=2)).isoformat()
        self.facts.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, '24 hours'):
            pages.prepare(self.root, self.root / 'new-run', self.facts)

    def test_english_assembly_fixes_source_links_and_diagrams(self):
        target = self.root / 'docs/en/konzepte'
        target.mkdir(parents=True)
        for item in self.catalog:
            text = (self.work / 'de' / (item['slug'] + '.md')).read_text()
            for german, english in zip(pages.HEADINGS['de'], pages.HEADINGS['en']):
                text = text.replace('## ' + german, '## ' + english)
            (target / (item['slug'] + '.md')).write_text('<!-- last-sync: fixture -->\n\n' + text)
        with contextlib.redirect_stdout(io.StringIO()):
            pages.finish_english(self.root, target, self.facts)
        self.gate('en', target)


class CollectorTest(unittest.TestCase):
    def test_pagination_and_data_minimization(self):
        responses = iter([
            [{'name': 'PRIVATE', 'runCount': 0, 'provider': None}],
            {field: 0 for field in ('activeSchedules', 'totalSchedules', 'activeWebhooks',
                'activeTodoTriggers', 'activeCompletionTriggers', 'activeMemoryEntries')},
            {'totalRuns': 3, 'blockTypeUsage': [{'blockType': 'shell', 'stepCount': 2}]},
            {'totalPages': 2, 'content': [{'description': 'PRIVATE', 'resolutionState': None}]},
            {'totalPages': 2, 'content': [{'description': 'PRIVATE', 'resolutionState': 'DONE'}]},
        ])
        def response(*args, **kwargs):
            return io.BytesIO(json.dumps(next(responses)).encode())
        with patch.object(collector.urllib.request, 'urlopen', side_effect=response):
            result = collector.collect('http://localhost/api/v1', 'secret-fixture')
        self.assertEqual(result['metrics']['todos']['value'], 2)
        self.assertEqual(result['metrics']['todos_without_resolution_state']['value'], 1)
        self.assertNotIn('PRIVATE', json.dumps(result))
        self.assertNotIn('secret-fixture', json.dumps(result))


if __name__ == '__main__':
    unittest.main()
