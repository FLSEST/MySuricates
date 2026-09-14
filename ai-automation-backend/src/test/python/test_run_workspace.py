"""Behavioral tests using actual disposable Git repositories, never the developer checkout."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / 'main/resources/scripts/run-workspace.py'
spec = importlib.util.spec_from_file_location('workspace', SCRIPT)
workspace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(workspace)


class RunWorkspaceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.source = self.base / 'source'
        self.source.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.email', 'test@example.invalid')
        self.git('config', 'user.name', 'Workspace Test')
        (self.source / 'code.txt').write_text('committed\n')
        self.git('add', '.')
        self.git('commit', '-qm', 'initial')
        self.sha = self.git('rev-parse', 'HEAD').strip()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.source), *args], text=True)

    def args(self, run='run-1', mode='commit', **kwargs):
        return argparse.Namespace(source=str(self.source), root=str(self.base / 'workspaces'),
                                  project='project-1', run=run, mode=mode,
                                  resume=kwargs.get('resume', False),
                                  branch=kwargs.get('branch', False), npm=False)

    def test_parallel_runs_share_neither_source_nor_build_outputs(self):
        (self.source / 'code.txt').write_text('dirty\n')
        with ThreadPoolExecutor(max_workers=2) as pool:
            manifests = list(pool.map(workspace.prepare, [self.args('a'), self.args('b')]))
        repos = [Path(m['workspacePath']) for m in manifests]
        self.assertNotEqual(*repos)
        for repo in repos:
            self.assertEqual('committed\n', (repo / 'code.txt').read_text())
            self.assertTrue((repo / '.git').is_dir())
        (repos[0] / 'target').mkdir()
        (repos[0] / 'target/output').write_text('one run only')
        self.assertFalse((repos[1] / 'target').exists())
        self.assertFalse((self.source / 'target').exists())
        self.assertEqual('dirty\n', (self.source / 'code.txt').read_text())
        self.assertEqual(self.sha, manifests[0]['sourceCommit'])

    def test_working_tree_freezes_staged_unstaged_new_binary_and_deleted_files(self):
        (self.source / 'binary').write_bytes(b'\0first')
        self.git('add', 'binary')
        (self.source / 'binary').write_bytes(b'\0second')
        (self.source / 'code.txt').unlink()
        (self.source / 'new.txt').write_text('untracked')
        (self.source / '.env').write_text('private')
        self.git('add', '.env')
        (self.source / 'runtime.db').write_bytes(b'private db')
        (self.source / 'target').mkdir()
        (self.source / 'target/classes').write_text('generated')
        manifest = workspace.prepare(self.args(mode='working-tree', branch=True))
        repo = Path(manifest['workspacePath'])
        self.assertEqual(b'\0second', (repo / 'binary').read_bytes())
        self.assertFalse((repo / 'code.txt').exists())
        self.assertEqual('untracked', (repo / 'new.txt').read_text())
        for name in ['.env', 'runtime.db', 'target']:
            self.assertFalse((repo / name).exists())
        (self.source / 'new.txt').write_text('later edit')
        self.assertEqual('untracked', (repo / 'new.txt').read_text())
        self.assertEqual('fix/workflow-run-1', manifest['branch'])

    def test_resume_keeps_snapshot_and_changes_made_by_run(self):
        manifest = workspace.prepare(self.args())
        repo = Path(manifest['workspacePath'])
        (repo / 'code.txt').write_text('run edit')
        (self.source / 'code.txt').write_text('new commit')
        self.git('commit', '-qam', 'next')
        resumed = workspace.prepare(self.args(resume=True))
        self.assertEqual(manifest, resumed)
        self.assertEqual('run edit', (repo / 'code.txt').read_text())

    def test_additional_repository_slot_has_same_run_but_separate_checkout(self):
        primary = workspace.prepare(self.args())
        args = self.args()
        args.slot = 'second'
        additional = workspace.prepare(args)
        self.assertEqual(primary['runId'], additional['runId'])
        self.assertNotEqual(primary['workspacePath'], additional['workspacePath'])
        self.assertEqual('second', additional['slot'])
        args.resume = True
        self.assertEqual(additional, workspace.prepare(args))

    def test_pinned_commit_survives_new_commit_before_preparation(self):
        args = self.args()
        args.commit = self.sha
        (self.source / 'code.txt').write_text('next commit')
        self.git('commit', '-qam', 'next')
        manifest = workspace.prepare(args)
        self.assertEqual(self.sha, manifest['sourceCommit'])
        self.assertEqual('committed\n', (Path(manifest['workspacePath']) / 'code.txt').read_text())

    def test_duplicate_preparation_fails_without_reset(self):
        manifest = workspace.prepare(self.args())
        marker = Path(manifest['workspacePath']) / 'keep'
        marker.write_text('keep')
        with self.assertRaises(FileExistsError):
            workspace.prepare(self.args())
        self.assertEqual('keep', marker.read_text())

    def test_missing_resume_never_falls_back_to_source(self):
        with self.assertRaisesRegex(RuntimeError, 'Cannot resume'):
            workspace.prepare(self.args(resume=True))

    def test_escape_symlink_rejected(self):
        (self.source / 'escape').symlink_to(self.base)
        self.git('add', 'escape')
        self.git('commit', '-qm', 'unsafe link')
        with self.assertRaisesRegex(RuntimeError, 'symlink escapes'):
            workspace.prepare(self.args())

    def test_storage_inside_source_rejected(self):
        args = self.args()
        args.root = str(self.source / 'workspaces')
        with self.assertRaisesRegex(RuntimeError, 'must be separate'):
            workspace.prepare(args)

    def test_project_subdirectory_preserved(self):
        (self.source / 'backend').mkdir()
        (self.source / 'backend/pom.xml').write_text('example')
        self.git('add', '.')
        self.git('commit', '-qm', 'subdirectory')
        args = self.args()
        args.source = str(self.source / 'backend')
        manifest = workspace.prepare(args)
        self.assertEqual(Path(manifest['workspacePath']) / 'backend', Path(manifest['executionPath']))


if __name__ == '__main__':
    unittest.main()
