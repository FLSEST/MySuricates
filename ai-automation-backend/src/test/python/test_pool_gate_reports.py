import json
from pathlib import Path
import shlex
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[4]
COMPLETE_FACTS = '\n'.join('FAKT %s: 0' % f for f in
                           ['backend_tests_exit', 'frontend_build_exit', 'frontend_lint_exit', 'backend_sources'])


class PoolGateReportTest(unittest.TestCase):
    """The gate blocks of the combined pool workflows rate a missing or unknown report as YELLOW."""

    def test_unknown_report_remains_yellow_in_combined_pool_gates(self):
        for name in ['build-gate', 'health-check', 'i18n-sync', 'release-readiness', 'test-sync']:
            workflow = json.loads((ROOT / 'workflow-pool' / ('mysuricates-'+name+'.json')).read_text())
            command = workflow['blocks'][-1]['config']['command']
            for report, status in [('', 'YELLOW'), ('gate_status: UNKNOWN', 'YELLOW'),
                                   ('gate_status: RED', 'RED'), ('gate_status: GREEN', 'GREEN')]:
                # The gates read the report as {{ previous_output }} or {{ block.report.output }}; the
                # health check checks its fact sheet beforehand – complete here, so that the verdict counts.
                rendered = (command.replace('{{ previous_output }}', shlex.quote(report))
                            .replace('{{ block.report.output }}', shlex.quote(report))
                            .replace('{{ block.facts.output }}', shlex.quote(COMPLETE_FACTS)))
                result = subprocess.run(['bash', '-c', rendered], text=True, capture_output=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn('gate_status: '+status, result.stdout, name)



if __name__ == '__main__': unittest.main()
