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


class OnlyFilterTest(unittest.TestCase):
    """--only regenerates single pages without touching the others or rewriting index rows."""

    def setUp(self):
        base = PublicationGateTest()
        base.setUp()
        self.addCleanup(base.temp.cleanup)
        self.root, self.facts, self.catalog = base.root, base.facts, base.catalog
        self.items = pages.select('zusammenstoepseln,anbieter-unabhaengig')
        self.work = self.root / 'only-run'
        (self.work / 'drafts').mkdir(parents=True)
        (self.work / 'manifest.json').write_text((base.work / 'manifest.json').read_text())
        for item in self.items:
            (self.work / 'drafts' / (item['slug'] + '.md')).write_text(
                (base.work / 'drafts' / (item['slug'] + '.md')).read_text())
        self.index = self.root / 'docs/konzepte/README.md'
        self.index.parent.mkdir(parents=True, exist_ok=True)
        self.index.write_text('# Konzepte\n\n| Mechanismus | Leitfrage |\n|---|---|\n'
                              '| [Gates](gates.md) | Handgeschriebene Frage? |\n\n## Weiter\n')

    def run_quietly(self, function, *args):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            function(*args)
        return out.getvalue()

    def test_selection_keeps_catalog_order_and_rejects_unknown_slugs(self):
        self.assertEqual([i['slug'] for i in pages.select(' anbieter-unabhaengig, gates ')],
                         ['gates', 'anbieter-unabhaengig'])
        self.assertEqual(len(pages.select('')), len(self.catalog))
        with self.assertRaisesRegex(ValueError, 'nope'):
            pages.select('gates,nope')

    def test_filtered_prepare_writes_only_selected_packets(self):
        with patch.object(pages.subprocess, 'check_output', return_value='a' * 40):
            self.run_quietly(pages.prepare, self.root, self.root / 'prep', self.facts, self.items)
        self.assertEqual(sorted(p.name for p in (self.root / 'prep/packets').iterdir()),
                         ['anbieter-unabhaengig.txt', 'zusammenstoepseln.txt'])

    def test_missing_metric_named_before_ai_call(self):
        data = pages.read_json(self.facts)
        del data['metrics']['steps_integration']
        self.facts.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'steps_integration'):
            pages.prepare(self.root, self.root / 'prep', self.facts, self.items)

    def test_filtered_assembly_adds_index_rows_and_checks_only_selection(self):
        self.run_quietly(pages.assemble, self.root, self.work, self.facts, self.items)
        target = self.work / 'de'
        self.assertEqual(sorted(p.name for p in target.iterdir()),
                         ['README.md', 'anbieter-unabhaengig.md', 'zusammenstoepseln.md'])
        index = (target / 'README.md').read_text()
        self.assertIn('| [Gates](gates.md) | Handgeschriebene Frage? |', index)
        self.assertEqual(index.count('](zusammenstoepseln.md)'), 1)
        self.assertLess(index.index('](anbieter-unabhaengig.md)'), index.index('## Weiter'))
        self.run_quietly(pages.check, self.root, target, self.facts, 'de', self.items)
        with self.assertRaisesRegex(ValueError, 'gates'):
            self.run_quietly(pages.check, self.root, target, self.facts, 'de')

    def test_index_untouched_when_rows_exist(self):
        self.index.write_text(pages.add_index_rows(self.index.read_text(), self.items, 'de'))
        self.run_quietly(pages.assemble, self.root, self.work, self.facts, self.items)
        self.assertFalse((self.work / 'de/README.md').exists())

    def test_filtered_english_finish_adds_rows_to_existing_translation(self):
        self.run_quietly(pages.assemble, self.root, self.work, self.facts, self.items)
        target = self.root / 'docs/en/konzepte'
        target.mkdir(parents=True)
        for item in self.items:
            text = (self.work / 'de' / (item['slug'] + '.md')).read_text()
            for german, english in zip(pages.HEADINGS['de'], pages.HEADINGS['en']):
                text = text.replace('## ' + german, '## ' + english)
            (target / (item['slug'] + '.md')).write_text('<!-- last-sync: fixture -->\n\n' + text)
        (target / 'README.md').write_text('| Mechanism | Guiding question |\n|---|---|\n'
                                          '| [Gates](gates.md) | Hand-written question? |\n')
        self.run_quietly(pages.finish_english, self.root, target, self.facts, self.items)
        index = (target / 'README.md').read_text()
        self.assertIn('| [Gates](gates.md) | Hand-written question? |', index)
        self.assertIn('[Provider independence](anbieter-unabhaengig.md)', index)
        self.assertIn('Next workflow in the chain', (target / 'zusammenstoepseln.md').read_text())

    def test_list_prints_items_for_the_workflow(self):
        full = io.StringIO()
        with contextlib.redirect_stdout(full):
            pages.list_items(pages.select(''), True, False)
        self.assertEqual(full.getvalue().split()[-1], 'konzepte/README.md')
        only = io.StringIO()
        with contextlib.redirect_stdout(only):
            pages.list_items(self.items, True, True)
        self.assertEqual(only.getvalue().split(),
                         ['konzepte/zusammenstoepseln.md', 'konzepte/anbieter-unabhaengig.md'])


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
        # the opener without a proxy (cf662c01); that it asks directly: test_local_api_proxy.py
        with patch.object(collector.LOCAL, 'open', side_effect=response):
            result = collector.collect('http://localhost/api/v1', 'secret-fixture')
        self.assertEqual(result['metrics']['todos']['value'], 2)
        self.assertEqual(result['metrics']['todos_without_resolution_state']['value'], 1)
        self.assertNotIn('PRIVATE', json.dumps(result))
        self.assertNotIn('secret-fixture', json.dumps(result))
        # A step count the catalog needs is measured as zero, not left out (prepare would stop).
        self.assertEqual(result['metrics']['steps_integration']['value'], 0)
        self.assertEqual(result['metrics']['steps_shell']['value'], 2)


if __name__ == '__main__':
    unittest.main()
