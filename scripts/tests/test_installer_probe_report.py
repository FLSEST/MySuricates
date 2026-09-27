"""Tests for scripts/lib/installer_probe_report.py — terminal replay and the report page.

Run: python3 -m unittest discover -s scripts/tests -p 'test_installer_probe_report.py'
"""
import base64
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "lib"))
import installer_probe_report as report  # noqa: E402


class AnsiToHtmlTest(unittest.TestCase):
    def test_colour_becomes_span_and_reset_closes_it(self):
        out = report.ansi_to_html("  \x1b[32m✓\x1b[0m git 2.43.0")
        self.assertEqual(out, '  <span class="f-g">✓</span> git 2.43.0')

    def test_bold_and_bright_colours(self):
        out = report.ansi_to_html("\x1b[1mSetup\x1b[0m \x1b[91mrot\x1b[39m")
        self.assertIn('<span class="bold">Setup</span>', out)
        self.assertIn('<span class="f-r2">rot</span>', out)

    def test_carriage_return_overwrites_like_a_spinner(self):
        out = report.ansi_to_html("⠋ installing\r⠙ installing\r\x1b[Kadded 916 packages")
        self.assertEqual(out, "added 916 packages")

    def test_cursor_to_column_one_then_erase(self):
        out = report.ansi_to_html("progress 10%\x1b[1G\x1b[0Kdone")
        self.assertEqual(out, "done")

    def test_crlf_from_a_pty_is_one_line_break(self):
        self.assertEqual(report.ansi_to_html("a\r\nb\r\n"), "a\nb")

    def test_html_in_the_transcript_is_escaped(self):
        out = report.ansi_to_html("<script>alert(1)</script> & co")
        self.assertNotIn("<script>", out)
        self.assertIn("&lt;script&gt;", out)
        self.assertIn("&amp; co", out)

    def test_other_escapes_are_dropped_not_printed(self):
        out = report.ansi_to_html("\x1b]0;title\x07\x1b(B\x1b[?25lok\x1b[?25h\x1b7\x1b8")
        self.assertEqual(out, "ok")

    def test_extended_colour_arguments_are_skipped(self):
        out = report.ansi_to_html("\x1b[38;5;208mx\x1b[38;2;1;2;3my\x1b[0m")
        self.assertEqual(out, "xy")

    def test_colour_carries_across_lines(self):
        out = report.ansi_to_html("\x1b[33ma\nb\x1b[0m")
        self.assertEqual(out, '<span class="f-y">a</span>\n<span class="f-y">b</span>')


class RenderTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        (self.dir / "logs").mkdir()
        (self.dir / "screens").mkdir()

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, phases, checks):
        (self.dir / "phases.tsv").write_text("".join("\t".join(r) + "\n" for r in phases))
        (self.dir / "checks.tsv").write_text("".join("\t".join(r) + "\n" for r in checks))

    def test_green_when_no_check_failed(self):
        self.write([["1-pruefen", "./scripts/setup.sh", "1", "2"]],
                   [["Backend meldet UP", "ok", ""], ["Screenshots", "skip", "kein Playwright"]])
        (self.dir / "logs" / "1-pruefen.log").write_text("\x1b[31m✗\x1b[0m curl fehlt\n")
        page = report.render(self.dir, {"short": "abc12345", "ref": "main"})
        self.assertIn("GRÜN", page)
        self.assertIn('<span class="f-r">✗</span> curl fehlt', page)
        self.assertIn("kein Playwright", page)

    def test_red_when_one_check_failed(self):
        self.write([], [["Klon nach dem Setup unverändert", "fail", "M package-lock.json"]])
        page = report.render(self.dir, {})
        self.assertIn("ROT", page)
        self.assertIn("M package-lock.json", page)

    def test_red_without_any_check(self):
        # A probe that died before its checks must not read as success.
        self.write([], [])
        self.assertIn("ROT", report.render(self.dir, {}))

    def test_screenshots_are_embedded(self):
        self.write([], [["x", "ok", ""]])
        png = b"\x89PNG\r\n\x1a\nfake"
        (self.dir / "screens" / "login.png").write_bytes(png)
        page = report.render(self.dir, {})
        self.assertIn("data:image/png;base64," + base64.b64encode(png).decode(), page)
        self.assertNotIn("Nach dem Login", page)

    def test_way_is_named_and_host_is_the_default(self):
        self.write([], [["x", "ok", ""]])
        page = report.render(self.dir, {})
        self.assertIn("<dt>Weg</dt><dd>eine Instanz auf dem Rechner</dd>", page)
        self.assertNotIn("Docker-Dienst", page)

    def test_container_ways_say_whose_docker_it_is(self):
        self.write([], [["x", "ok", ""]])
        page = report.render(self.dir, {"way": "staged"})
        self.assertIn("<dt>Weg</dt><dd>Dev · Test · Prod im Container</dd>", page)
        self.assertIn("Docker-Dienst ist der des Rechners", page)
        self.assertIn("eine Instanz im Container", report.render(self.dir, {"way": "container"}))

    def test_durations_are_human_readable(self):
        self.assertEqual(report._duration("410"), "6 min 50 s")
        self.assertEqual(report._duration("9"), "9 s")


if __name__ == "__main__":
    unittest.main()
