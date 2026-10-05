"""Failure-path checks for the concept-page publication gate; no AI or backend needed."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
REPO = SCRIPTS.parent


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
        (self.work / 'manifest.json').write_text(json.dumps({'source_commit': 'a' * 40}))
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
            pages.assemble(self.root, self.work)

    def gate(self, language='de', directory=None):
        with contextlib.redirect_stdout(io.StringIO()):
            pages.check(self.root, directory or self.work / 'de', language)

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

    def test_assembled_page_has_the_fixed_sections_only(self):
        text = (self.work / 'de/gates.md').read_text()
        self.assertTrue(text.startswith('<!-- concept-source: ' + 'a' * 40 + ' -->\n'))
        self.assertEqual(pages.re.findall(r'^## (.+)$', text, pages.re.M),
                         pages.HEADINGS['de'] + [pages.CODE_HEADING['de']])

    def test_extra_section_does_not_pass(self):
        # The pages carry no measurement any more; a dated evidence section must not creep back in.
        path = self.work / 'de/gates.md'
        path.write_text(path.read_text().replace(
            '## Im Code', '## Betriebsbeleg\n\n| Messgröße | 2 / 7 |\n\n## Im Code'))
        with self.assertRaisesRegex(ValueError, 'sections'):
            self.gate()

    def test_renamed_code_section_does_not_pass(self):
        path = self.work / 'de/gates.md'
        path.write_text(path.read_text().replace('## Im Code', '## Quellen'))
        with self.assertRaisesRegex(ValueError, 'sections'):
            self.gate()

    def test_missing_source_commit_does_not_pass(self):
        path = self.work / 'de/gates.md'
        path.write_text(pages.re.sub(r'<!-- concept-source: [0-9a-f]+ -->\n', '', path.read_text()))
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

    def test_prepare_packs_notes_and_sources_without_measurement(self):
        with patch.object(pages.subprocess, 'check_output', return_value='b' * 40 + '\n'), \
                contextlib.redirect_stdout(io.StringIO()):
            pages.prepare(self.root, self.root / 'new-run')
        self.assertEqual(pages.read_json(self.root / 'new-run/manifest.json'), {'source_commit': 'b' * 40})
        packet = (self.root / 'new-run/packets/gates.txt').read_text()
        self.assertIn(self.catalog[0]['notes'], packet)
        self.assertIn('### ' + self.catalog[0]['sources'][0], packet)
        self.assertNotIn('Betriebsdaten', packet)
        self.assertFalse((self.root / 'new-run/facts.json').exists())

    def test_english_assembly_fixes_source_links_and_diagrams(self):
        target = self.root / 'docs/en/konzepte'
        target.mkdir(parents=True)
        for item in self.catalog:
            text = (self.work / 'de' / (item['slug'] + '.md')).read_text()
            text = translate_headings(text)
            (target / (item['slug'] + '.md')).write_text('<!-- last-sync: fixture -->\n\n' + text)
        with contextlib.redirect_stdout(io.StringIO()):
            pages.finish_english(self.root, target)
        self.gate('en', target)

    def test_english_page_with_german_code_heading_does_not_pass(self):
        target = self.root / 'docs/en/konzepte'
        target.mkdir(parents=True)
        for item in self.catalog:
            text = (self.work / 'de' / (item['slug'] + '.md')).read_text()
            text = translate_headings(text).replace('## ' + pages.CODE_HEADING['en'], '## Im Code')
            (target / (item['slug'] + '.md')).write_text('<!-- last-sync: fixture -->\n\n' + text)
        with self.assertRaisesRegex(ValueError, 'sections'), contextlib.redirect_stdout(io.StringIO()):
            pages.finish_english(self.root, target)


def translate_headings(text):
    """Stand-in for the translator: German section headings to the agreed English ones."""
    for german, english in zip(pages.HEADINGS['de'] + [pages.CODE_HEADING['de']],
                               pages.HEADINGS['en'] + [pages.CODE_HEADING['en']]):
        text = text.replace('## ' + german, '## ' + english)
    return text


class OnlyFilterTest(unittest.TestCase):
    """--only regenerates single pages without touching the others or rewriting index rows."""

    def setUp(self):
        base = PublicationGateTest()
        base.setUp()
        self.addCleanup(base.temp.cleanup)
        self.root, self.catalog = base.root, base.catalog
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
            self.run_quietly(pages.prepare, self.root, self.root / 'prep', self.items)
        self.assertEqual(sorted(p.name for p in (self.root / 'prep/packets').iterdir()),
                         ['anbieter-unabhaengig.txt', 'zusammenstoepseln.txt'])

    def test_filtered_assembly_adds_index_rows_and_checks_only_selection(self):
        self.run_quietly(pages.assemble, self.root, self.work, self.items)
        target = self.work / 'de'
        self.assertEqual(sorted(p.name for p in target.iterdir()),
                         ['README.md', 'anbieter-unabhaengig.md', 'zusammenstoepseln.md'])
        index = (target / 'README.md').read_text()
        self.assertIn('| [Gates](gates.md) | Handgeschriebene Frage? |', index)
        self.assertEqual(index.count('](zusammenstoepseln.md)'), 1)
        self.assertLess(index.index('](anbieter-unabhaengig.md)'), index.index('## Weiter'))
        self.run_quietly(pages.check, self.root, target, 'de', self.items)
        with self.assertRaisesRegex(ValueError, 'gates'):
            self.run_quietly(pages.check, self.root, target, 'de')

    def test_index_untouched_when_rows_exist(self):
        self.index.write_text(pages.add_index_rows(self.index.read_text(), self.items, 'de'))
        self.run_quietly(pages.assemble, self.root, self.work, self.items)
        self.assertFalse((self.work / 'de/README.md').exists())

    def test_filtered_english_finish_adds_rows_to_existing_translation(self):
        self.run_quietly(pages.assemble, self.root, self.work, self.items)
        target = self.root / 'docs/en/konzepte'
        target.mkdir(parents=True)
        for item in self.items:
            text = translate_headings((self.work / 'de' / (item['slug'] + '.md')).read_text())
            (target / (item['slug'] + '.md')).write_text('<!-- last-sync: fixture -->\n\n' + text)
        (target / 'README.md').write_text('| Mechanism | Guiding question |\n|---|---|\n'
                                          '| [Gates](gates.md) | Hand-written question? |\n')
        self.run_quietly(pages.finish_english, self.root, target, self.items)
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


class CatalogTest(unittest.TestCase):
    def test_catalog_carries_no_measurement(self):
        # Operational measurements left the concept pages (1ab3ad21); the catalog names none.
        for item in pages.read_json(pages.CATALOG):
            with self.subTest(slug=item['slug']):
                self.assertNotIn('metrics', item)


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
        self.assertEqual(result['metrics']['steps_shell']['value'], 2)

    def test_default_output_stays_out_of_the_docs(self):
        # A measurement is a report of one installation, not documentation: gitignored reports/.
        self.assertEqual(collector.DEFAULT_OUTPUT.relative_to(REPO).parts[0], 'reports')


if __name__ == '__main__':
    unittest.main()
