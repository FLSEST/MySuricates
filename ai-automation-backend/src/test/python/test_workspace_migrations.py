import importlib.util
import json
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[4]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts/workspace-isolation' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class MigrationTest(unittest.TestCase):
    def test_cs2bim_replaces_shared_checkout_push_and_revert_steps(self):
        migration = load('prepare-cs2bim')
        for prefix in migration.IDS:
            count = 5 if prefix == '0bfed42c' else 6 if prefix == 'dcd1ba43' else 7 if prefix == '5a9c64b8' else 8
            original = dict(id=prefix+'-fixture', name='fixture', blocks=[
                dict(type='shell', config=dict(command='cd /tmp/cs2bim-ci\necho diagnostic')) for _ in range(count)])
            proposal = migration.migrate(original)
            self.assertEqual(original, proposal['before'])
            update = proposal['update']
            self.assertTrue(update['metadata']['workspace']['branch'])
            text = json.dumps(update)
            for unsafe in ['/tmp/cs2bim', 'git push ', 'git reset --hard', 'git revert HEAD', '--break-system-packages']:
                self.assertNotIn(unsafe, text)
            for block in update['blocks']:
                if block['type'] != 'shell': continue
                command = re.sub(r'\{\{.*?\}\}', 'fixture_value', block['config'].get('command', ''))
                result = subprocess.run(['bash', '-n'], input=command, text=True, capture_output=True)
                self.assertEqual(0, result.returncode, result.stderr)


if __name__ == '__main__': unittest.main()
