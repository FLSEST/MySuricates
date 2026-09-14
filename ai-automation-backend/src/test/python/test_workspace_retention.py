import importlib.util
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[4] / 'scripts/workspace-isolation/prune-workspaces.py'
spec = importlib.util.spec_from_file_location('retention', SCRIPT)
retention = importlib.util.module_from_spec(spec)
spec.loader.exec_module(retention)


class RetentionTest(unittest.TestCase):
    def test_only_clean_successful_owned_runs_qualify(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            run = root / 'project' / 'run'
            repo = run / 'repo'
            repo.mkdir(parents=True)
            subprocess.run(['git', 'init', '-q', str(repo)], check=True)
            def git(*args):
                return subprocess.check_output(['git', '-C', str(repo), *args], text=True).strip()
            git('config', 'user.name', 'Retention Fixture')
            git('config', 'user.email', 'test@example.invalid')
            git('commit', '--allow-empty', '-qm', 'baseline')
            manifest = dict(projectId='project', runId='run', workspacePath=str(repo),
                            sourceMode='commit', branch=None, sourceCommit=git('rev-parse', 'HEAD'))
            path = run / 'workspace.json'
            path.write_text(json.dumps(manifest))
            execution = dict(id='run', status='COMPLETED', completedAt='2020-01-01T00:00:00Z',
                             metadata={'workspace': manifest})
            cutoff = datetime.now(timezone.utc) - timedelta(days=30)
            self.assertTrue(retention.eligible(root, path, execution, cutoff))
            for status in ['RUNNING', 'PAUSED', 'INTERRUPTED', 'FAILED', 'CANCELLED']:
                self.assertFalse(retention.eligible(root, path, dict(execution, status=status), cutoff))
            self.assertFalse(retention.eligible(root, path, dict(execution, metadata={}), cutoff))
            (repo / 'valuable-fix').write_text('keep')
            self.assertFalse(retention.eligible(root, path, execution, cutoff))
            git('add', '.')
            git('commit', '-qm', 'valuable committed fix')
            self.assertEqual('', git('status', '--porcelain'))
            self.assertFalse(retention.eligible(root, path, execution, cutoff))


if __name__ == '__main__':
    unittest.main()
