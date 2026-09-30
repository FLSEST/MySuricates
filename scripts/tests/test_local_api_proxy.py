"""The Python operator scripts ask the own instance directly, never through a proxy (cf662c01).

urllib sends localhost through http_proxy, https_proxy and all_proxy from the environment, like curl: behind
a proxy the scripts failed, and the proxy got the X-API-Key. The shell scripts go through lib/local-curl.sh
(bb4f60a5); the Python scripts open their requests with an opener without a proxy. Real HTTP for the real
scripts: fake_instance.py plays the instance, which notes who asked with which key, and a proxy that must
not get anything. Each script runs as its own process with the fake proxy in every variable urllib reads,
set before it starts — as in an operator's shell.

Run: python3 -m unittest discover -s scripts/tests -p 'test_local_api_proxy.py'
"""
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest

HERE = Path(__file__).resolve().parent
SCRIPTS = HERE.parent
KEY = 'key-cf662c01'
BUILD = 'fake-build'  # the buildTimestamp fake_instance.py reports
PROXY_VARIABLES = ('http_proxy', 'https_proxy', 'all_proxy', 'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY')


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def serve(env, *args):
    """fake_instance.py as its own process; returns it once its port answers."""
    server = subprocess.Popen([sys.executable, str(HERE / 'fake_instance.py'), *args], env=env,
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', int(args[1]))) == 0:
                return server
        time.sleep(0.05)
    server.kill()
    raise RuntimeError('fake_instance.py %s does not answer' % args[0])


class LocalApiWithoutProxyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temp.cleanup)
        cls.dir = Path(cls.temp.name)
        cls.api_log, cls.proxy_log = cls.dir / 'api.log', cls.dir / 'proxy.log'
        env = dict(os.environ, FAKE_INSTANCE_LOG=str(cls.api_log), FAKE_INSTANCE_TTL='300')
        api_port, proxy_port = free_port(), free_port()
        cls.base = 'http://127.0.0.1:%d/api/v1' % api_port
        cls.proxy = 'http://127.0.0.1:%d' % proxy_port
        for args in (('api', str(api_port), KEY, '0'), ('proxy', str(proxy_port), str(cls.proxy_log))):
            server = serve(env, *args)
            cls.addClassCleanup(server.wait)
            cls.addClassCleanup(server.kill)

    def setUp(self):
        self.api_log.write_text('')
        self.proxy_log.write_text('')
        self.work = Path(tempfile.mkdtemp(dir=self.dir))

    def proxied(self, *args):
        """python3 ARGS… with the fake proxy in every proxy variable and no exception for local hosts."""
        env = {name: value for name, value in os.environ.items() if name.lower() != 'no_proxy'}
        env.update({name: self.proxy for name in PROXY_VARIABLES})
        # the key from the environment; a home of its own, so no key file of this machine is read
        env.update(MYSURICATES_API_KEY=KEY, HOME=str(self.work))
        return subprocess.run([sys.executable, *args], env=env, capture_output=True, text=True, timeout=60)

    def assert_asked_directly(self, request):
        self.assertEqual(request + ' key=ok\n', self.api_log.read_text(), 'the instance got it, with the key')
        self.assertEqual('', self.proxy_log.read_text(), 'the proxy got nothing')

    def test_control_plain_urllib_goes_through_the_proxy(self):
        # Without this the other checks would prove nothing: urllib really asks the proxy for 127.0.0.1
        self.proxied('-c', 'import urllib.request\ntry:\n    urllib.request.urlopen(%r, timeout=5)\n'
                           'except Exception:\n    pass' % (self.base + '/system/health'))
        self.assertIn('GET %s/system/health' % self.base, self.proxy_log.read_text())
        self.assertEqual('', self.api_log.read_text())

    def test_apply_migration(self):
        proposals = self.work / 'proposals'
        proposals.mkdir()
        result = self.proxied(str(SCRIPTS / 'workspace-isolation/apply-migration.py'), str(proposals),
                              '--api-base', self.base, '--expected-build', BUILD,
                              '--backup-dir', str(self.work / 'backup'))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('0 selected; 0 updated', result.stdout)
        self.assert_asked_directly('GET /api/v1/system/info')

    def test_prune_workspaces(self):
        run = self.work / 'workspaces' / 'project' / 'run-1'
        run.mkdir(parents=True)
        (run / 'workspace.json').write_text('{}')
        result = self.proxied(str(SCRIPTS / 'workspace-isolation/prune-workspaces.py'),
                              '--root', str(self.work / 'workspaces'), '--api-base', self.base)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('KEEP', result.stdout)  # "KEEP run-1: HTTPError" — the question failed
        self.assert_asked_directly('GET /api/v1/executions/run-1')

    def test_collect_facts(self):
        result = self.proxied(str(SCRIPTS / 'concepts/collect_facts.py'), '--api-base', self.base,
                              '--output', str(self.work / 'facts.json'))
        # The stand-in knows no /workflows (404, from the proxy it would be 502): what counts is who got it
        self.assertIn('HTTP Error 404', result.stderr)
        self.assert_asked_directly('GET /api/v1/workflows')

    def test_every_script_sending_the_key_opens_without_a_proxy(self):
        # A new script that sends X-API-Key with urllib.request.urlopen would take the proxy again. Downloads
        # from the net (public-readiness/dep_vuln_check.py) need the proxy — and carry no key. Only urllib.request
        # reads the proxy variables: http.client (load-test/driver.py) connects where it is told, and a script
        # that merely names the header (public-readiness/doc_facts_check.py checks curl examples) sends nothing.
        listed = subprocess.run(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '--', '*.py'],
                                cwd=SCRIPTS, capture_output=True, text=True)
        names = listed.stdout.split() if listed.returncode == 0 else \
            [str(path.relative_to(SCRIPTS)) for path in SCRIPTS.rglob('*.py')]
        with_key = sorted(name for name in names if not name.startswith('tests/') and (SCRIPTS / name).is_file()
                          and 'X-API-Key' in (text := (SCRIPTS / name).read_text()) and 'urllib.request' in text)
        self.assertIn('workspace-isolation/apply-migration.py', with_key, 'the search saw nothing')
        through_proxy = [name for name in with_key if 'ProxyHandler({})' not in (SCRIPTS / name).read_text()
                         or 'urlopen(' in (SCRIPTS / name).read_text()]
        self.assertEqual([], through_proxy, 'X-API-Key without an opener without a proxy')


if __name__ == '__main__':
    unittest.main()
