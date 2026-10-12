"""Tests for scripts/release-overview.py and scripts/release-probe.sh with throwaway repos.

Run: python3 -m unittest discover -s scripts/tests -p 'test_release_overview.py'
"""
import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "release-overview.py"
PROBE = ROOT / "scripts" / "release-probe.sh"
_spec = importlib.util.spec_from_file_location("release_overview", SCRIPT)
ro = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ro)

NOREPLY = "12345+tester@users.noreply.github.com"
ENV = {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}
ENV.update(GIT_AUTHOR_NAME="Tester", GIT_AUTHOR_EMAIL=NOREPLY, GIT_COMMITTER_NAME="Tester",
           GIT_COMMITTER_EMAIL=NOREPLY, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, check=True, env=ENV).stdout.strip()


def make_repo(path, commits):
    git(path.parent, "init", "--quiet", "--initial-branch=main", str(path))
    git(path, "config", "user.name", "Tester")
    git(path, "config", "user.email", NOREPLY)
    for i in range(commits):
        (path / f"f{i}.txt").write_text(f"{i}\n")
        git(path, "add", ".")
        git(path, "commit", "--quiet", "-m", f"feat: change {i}")
    return path


REWRITE_OUT = """[rewrite] Gegenprobe in /tmp/w
  ok     Pfad entfernt: (12 Zeichen)
  FEHLER 2 Claude-Session-Trailer
  ok     gitleaks: keine Funde
[rewrite] Gegenprobe: 1 Befund(e)
"""
HANDOVER_OUT = """▶ Public-Hygiene (--tracked)
## Gates
- Treue zum Tag: OK
- Public-Hygiene (--tracked): OK (2 Warnung(en))
- README-Sync-Stand: übersprungen (Skript fehlt im Tag)
- Doku-Gates (pre-push-checks --tree): FEHLER (exit 1)

## Änderungen gegenüber v0.1.0
handover_status: FAILED
"""
GATE_OUT = """Pre-Push-Gate (range)
  – 536 Commit(s) geprueft
  – Hygiene-Gate auf origin/main..main: OK
  – gitleaks auf origin/main..main: keine Funde
  ✗ Bereich origin/main..main aendert Ausnahmedateien (.gitleaksignore)
  – Frontend: OK
"""


class ParseChecks(unittest.TestCase):
    def test_rewrite_verification_lines(self):
        checks = ro.parse_checks(REWRITE_OUT)
        self.assertEqual([c["result"] for c in checks], ["ok", "failed", "ok"])
        self.assertEqual(checks[1]["name"], "2 Claude-Session-Trailer")

    def test_handover_gate_list(self):
        checks = ro.parse_checks(HANDOVER_OUT)
        self.assertEqual([(c["name"], c["result"]) for c in checks], [
            ("Treue zum Tag", "ok"), ("Public-Hygiene (--tracked)", "ok"),
            ("README-Sync-Stand", "skipped"), ("Doku-Gates (pre-push-checks --tree)", "failed")])
        self.assertEqual(checks[1]["detail"], "2 Warnung(en)")

    def test_skipped_checks_and_warning_details_are_kept(self):
        checks = ro.parse_checks("  hinweis gitleaks nicht installiert - Geheimnis-Scan uebersprungen\n"
                                 "  – Build-Pruefung uebersprungen (MYSURICATES_PREPUSH_SKIP=build)\n"
                                 "  – Hygiene-Gate auf a..b: OK — 2 Warnung(en)\n")
        self.assertEqual([c["result"] for c in checks], ["skipped", "skipped", "ok"])
        self.assertEqual(checks[2]["detail"], "2 Warnung(en)")

    def test_indented_log_tail_of_a_gate_adds_no_checks(self):
        out = ("▶ Doku-Gates (pre-push-checks --tree)\n    Pre-Push-Gate (tree)\n    ▶ Bestands-Gates\n"
               "      – Doku-Zahlen gegen den Code: OK\n      ✗ Doku-Verweise: 2 tote Links\n"
               "## Gates\n- Doku-Gates (pre-push-checks --tree): FEHLER (exit 1)\n")
        self.assertEqual([(c["name"], c["result"]) for c in ro.parse_checks(out)],
                         [("Doku-Gates (pre-push-checks --tree)", "failed")])

    def test_pre_push_gate_lines(self):
        checks = ro.parse_checks(GATE_OUT)
        self.assertIn({"name": "Hygiene-Gate auf origin/main..main", "result": "ok"}, checks)
        self.assertIn({"name": "Frontend", "result": "ok"}, checks)
        self.assertTrue(any(c["result"] == "failed" and "Ausnahmedateien" in c["name"] for c in checks))
        self.assertFalse(any("Commit(s) geprueft" in c["name"] for c in checks))  # a count, no check


class Events(unittest.TestCase):
    def test_event_is_appended_with_checks_and_commit(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out.txt"
            out.write_text(HANDOVER_OUT + "handover_commit: abc1234\n")
            log = Path(tmp) / "sub" / "events.jsonl"
            for _ in range(2):
                ro.main(["event", "--log", str(log), "--kind", "handover", "--tag", "v0.1.1",
                         "--status", "failed", "--output", str(out), "--note", "Probe"])
            lines = log.read_text().splitlines()
            self.assertEqual(len(lines), 2)
            event = json.loads(lines[0])
            self.assertEqual((event["kind"], event["tag"], event["status"], event["commit"]),
                             ("handover", "v0.1.1", "failed", "abc1234"))
            self.assertEqual(len(event["checks"]), 4)

    def test_foreign_lines_and_broken_bytes_are_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "events.jsonl"
            log.write_bytes(b'123\n{"kind": "unknown"}\n{"kind": "cut"}\n\xff\xfe{"kind": "hand\n')
            events = ro.read_events(log)
            self.assertEqual([e["kind"] for e in events], ["cut"])
            self.assertEqual((events[0]["time"], events[0]["status"], events[0]["checks"]), ("", "ok", []))
            ro.render(None, None, events, "live", "jetzt")  # must not crash on missing fields

    def test_torn_line_does_not_hide_the_rest(self):
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "events.jsonl"
            log.write_text('{"kind": "cut", "status": "ok", "time": "2026-10-12T10:00:00+00:00"}\n{"kind": "hand\n')
            self.assertEqual(len(ro.read_events(log)), 1)


class Lines(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.arch = make_repo(self.root / "arch", 6)
        git(self.arch, "tag", "-a", "v0.1.0", "-m", "r", "HEAD~3")
        git(self.arch, "tag", "-a", "v0.1.1", "-m", "r", "HEAD~1")
        git(self.arch, "tag", "-a", "stable-1", "-m", "r", "HEAD")  # no v* tag: ignored
        self.bare = self.root / "arch.git"
        git(self.root, "init", "--quiet", "--bare", str(self.bare))
        git(self.arch, "remote", "add", "origin", str(self.bare))
        git(self.arch, "push", "--quiet", "origin", "HEAD~2:refs/heads/main")
        git(self.arch, "fetch", "--quiet", "origin")

    def test_archive_line_reads_tags_positions_and_unpushed(self):
        line = ro.read_line(self.arch, "main", "origin/main")
        self.assertEqual(line["count"], 6)
        self.assertEqual([(t["name"], t["pos"]) for t in line["tags"]], [("v0.1.0", 2), ("v0.1.1", 4)])
        self.assertEqual((line["unpushed"], line["since_tag"]), (2, 1))

    def test_unreadable_archive_is_red_and_missing_public_is_yellow(self):
        self.assertEqual(ro.overall(None, None, [], None), ("RED", "Arbeits-Repo nicht lesbar"))
        git(self.arch, "push", "--quiet", "origin", "main")
        git(self.arch, "fetch", "--quiet", "origin")
        archive = ro.read_line(self.arch, "main", "origin/main")
        status, summary = ro.overall(archive, None, [], ro.pending_stat(archive, None))
        self.assertEqual(status, "YELLOW")
        self.assertIn("Erstschnitt steht aus", summary)
        self.assertEqual(ro.pending_stat(archive, None)["open_tags"], [])  # before the cut nothing is open

    def test_tag_on_a_merged_side_branch_sits_at_its_merge(self):
        git(self.arch, "checkout", "--quiet", "-b", "side", "HEAD~2")
        (self.arch / "side.txt").write_text("s")
        git(self.arch, "add", ".")
        git(self.arch, "commit", "--quiet", "-m", "feat: side")
        git(self.arch, "tag", "-a", "v0.2.0", "-m", "r")
        git(self.arch, "checkout", "--quiet", "main")
        git(self.arch, "merge", "--quiet", "--no-ff", "-m", "Merge side", "side")
        line = ro.read_line(self.arch, "main")
        side = next(t for t in line["tags"] if t["name"] == "v0.2.0")
        self.assertEqual(side["pos"], line["span"] - 1)  # the merge commit, inside the drawn span
        ro.render(line, None, [], "live", "jetzt")

    def test_cut_is_the_newest_rewritten_tag_not_the_oldest(self):
        git(self.arch, "tag", "-a", "v0.0.9", "-m", "old", "HEAD~5")  # an older release in the history
        pub = make_repo(self.root / "pub", 4)
        git(pub, "tag", "-a", "v0.0.9", "-m", "r", "HEAD~3")
        git(pub, "tag", "-a", "v0.1.0", "-m", "r", "HEAD~1")
        (pub / "r.txt").write_text("r")
        git(pub, "add", ".")
        git(pub, "commit", "--quiet", "-m", "Release v0.1.1")
        git(pub, "tag", "-a", "v0.1.1", "-m", "r")
        public = ro.read_line(pub, "main")
        self.assertEqual(ro.cut_tag(public)["name"], "v0.1.0")
        page = ro.render(ro.read_line(self.arch, "main", "origin/main"), public, [], "probe", "jetzt")
        self.assertEqual(page.count('class="flow cut"'), 1)
        self.assertEqual(page.count('class="flow handover"'), 1)
        self.assertIn('class="tick public"', page)

    def test_missing_repo_is_none(self):
        self.assertIsNone(ro.read_line(self.root / "nope", "main"))
        empty = self.root / "empty.git"
        git(self.root, "init", "--quiet", "--bare", str(empty))
        self.assertIsNone(ro.read_line(empty, "main"))

    def public_with(self, *tags):
        pub = make_repo(self.root / "pub", 3)
        for i, tag in enumerate(tags):
            git(pub, "tag", "-a", tag, "-m", "r", f"HEAD~{len(tags) - 1 - i}")
        return pub

    def test_page_without_public_repo(self):
        page = ro.render(ro.read_line(self.arch, "main", "origin/main"), None, [], "probe", "jetzt")
        for text in ("Release-Lagebild", "noch nicht angelegt", "Prüfungen auf dem Weg", "Erstschnitt",
                     "Rückweg", "noch keine Läufe", "Probe (lokale Bare-Repos statt GitHub)"):
            self.assertIn(text, page)

    def test_open_tag_is_yellow_and_drawn_as_pending(self):
        archive = ro.read_line(self.arch, "main", "origin/main")
        public = ro.read_line(self.public_with("v0.1.0"), "main")
        pending = ro.pending_stat(archive, public)
        self.assertEqual((pending["base"], pending["open_tags"]), ("v0.1.0", ["v0.1.1"]))
        status, summary = ro.overall(archive, public, [], pending)
        self.assertEqual(status, "YELLOW")
        self.assertIn("offen: v0.1.1", summary)
        page = ro.render(archive, public, [], "probe", "jetzt")
        self.assertEqual(page.count('class="flow cut"'), 1)
        self.assertIn('class="flow pending"', page)

    def test_everything_handed_over_is_green_and_failed_run_is_red(self):
        git(self.arch, "push", "--quiet", "origin", "main")
        git(self.arch, "fetch", "--quiet", "origin")
        git(self.arch, "tag", "-d", "v0.1.1")
        git(self.arch, "tag", "-a", "v0.1.1", "-m", "r", "HEAD")
        archive = ro.read_line(self.arch, "main", "origin/main")
        public = ro.read_line(self.public_with("v0.1.0", "v0.1.1"), "main")
        pending = ro.pending_stat(archive, public)
        self.assertEqual(ro.overall(archive, public, [], pending)[0], "GREEN")
        failed = [{"time": "2026-10-12T10:00:00+00:00", "kind": "handover", "tag": "v0.1.2", "status": "failed",
                   "checks": ro.parse_checks(HANDOVER_OUT)}]
        self.assertEqual(ro.overall(archive, public, failed, pending)[0], "RED")
        page = ro.render(archive, public, failed, "live", "jetzt")
        self.assertIn('2/4 grün · 1 übersprungen · 1 <b class="bad">Fehler</b>', page)
        self.assertIn("Doku-Gates (pre-push-checks --tree)", page)

    def test_render_command_writes_page_and_status(self):
        out, status = self.root / "page" / "index.html", self.root / "status.txt"
        rc = ro.main(["render", "--archive", str(self.arch), "--archive-upstream", "origin/main",
                      "--public", str(self.root / "nope"), "--out", str(out), "--status-out", str(status),
                      "--mode", "probe"])
        self.assertEqual(rc, 0)
        self.assertTrue(out.read_text().startswith("<!doctype html>"))
        self.assertIn(status.read_text().splitlines()[0], ("GREEN", "YELLOW", "RED"))

    def test_page_escapes_commit_subjects(self):
        (self.arch / "x.txt").write_text("x")
        git(self.arch, "add", ".")
        git(self.arch, "commit", "--quiet", "-m", "fix: <script>alert(1)</script>")
        page = ro.render(ro.read_line(self.arch, "main"), None, [], "live", "jetzt")
        self.assertNotIn("<script>alert", page)


class ReleasePaths(unittest.TestCase):
    LIB = ROOT / "scripts" / "lib" / "release-paths.sh"

    def paths(self, **env):
        script = (f'. "{self.LIB}"; release_paths || exit $?; '
                  'echo "$ARCHIVE|$PUBLIC|$PUBLIC_REF|$PUBLIC_CLONE|$EVENTS|$PAGE|$STATUS_FILE"')
        with tempfile.TemporaryDirectory() as home:
            r = subprocess.run(["bash", "-c", script], capture_output=True, text=True,
                               env={**ENV, "HOME": home, "XDG_CONFIG_HOME": f"{home}/.config", **env})
            return r.returncode, r.stdout.strip().replace(home, "~")

    def test_probe_points_at_the_bare_repos(self):
        rc, out = self.paths(MODE="probe", ARCHIVE_REPO="/repo")
        self.assertEqual(rc, 0)
        self.assertEqual(out.split("|"), [
            "~/.local/state/mysuricates/release-probe/work", "~/.local/state/mysuricates/release-probe/public.git",
            "main", "~/.local/state/mysuricates/release-probe/public",
            "~/.local/state/mysuricates/release-probe/events.jsonl", "~/Documents/MySuricates/release-lagebild-probe.html",
            "~/.local/state/mysuricates/release-probe/lagebild-status.txt"])

    def test_live_counts_only_what_is_pushed(self):
        rc, out = self.paths(MODE="live", ARCHIVE_REPO="/repo", PUBLIC_CLONE="/pub")
        self.assertEqual(rc, 0)
        archive, public, ref, _, events, page, _ = out.split("|")
        self.assertEqual((archive, public, ref), ("/repo", "/pub", "origin/main"))
        self.assertEqual(page, "~/Documents/MySuricates/release-lagebild.html")  # no repo, no hidden folder
        self.assertEqual(self.paths(MODE="live", ARCHIVE_REPO="/repo", PUBLIC_CLONE="/pub", PAGE_DIR="/docs/x")[1]
                         .split("|")[5], "/docs/x/release-lagebild.html")
        self.assertEqual(events, "~/.local/state/mysuricates/release/events.jsonl")

    def test_missing_context_or_unknown_mode_fails(self):
        self.assertEqual(self.paths(MODE="live", ARCHIVE_REPO="/repo")[0], 2)
        self.assertEqual(self.paths(MODE="probe")[0], 2)
        self.assertEqual(self.paths(MODE="staging", ARCHIVE_REPO="/repo")[0], 2)


class Probe(unittest.TestCase):
    def run_probe(self, *args, check=True):
        return subprocess.run(["bash", str(PROBE), *args], capture_output=True, text=True, env=ENV, check=check)

    def test_reset_and_advance(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_repo(Path(tmp) / "src", 5)
            first, last = git(src, "rev-parse", "HEAD~3"), git(src, "rev-parse", "HEAD")
            probe = Path(tmp) / "probe"
            self.run_probe("reset", "--dir", str(probe), "--source", str(src), "--cut", first, "--tag", "v0.1.0")
            work = probe / "work"
            self.assertEqual(git(work, "rev-parse", "main"), first)
            self.assertEqual(git(probe / "archive.git", "tag", "-l"), "v0.1.0")
            self.assertEqual(git(probe / "public.git", "for-each-ref"), "")
            self.assertEqual(git(work, "for-each-ref", "--format=%(taggeremail)", "refs/tags"), f"<{NOREPLY}>")
            self.run_probe("advance", "--dir", str(probe), "--to", last, "--tag", "v0.1.1")
            self.assertEqual(git(probe / "archive.git", "rev-parse", "main"), last)
            self.assertEqual(git(probe / "archive.git", "tag", "-l").split(), ["v0.1.0", "v0.1.1"])
            # A second reset rebuilds the probe from scratch.
            self.run_probe("reset", "--dir", str(probe), "--source", str(src), "--cut", first, "--tag", "v0.1.0")
            self.assertEqual(git(probe / "archive.git", "tag", "-l"), "v0.1.0")

    def test_refuses_a_directory_that_is_no_probe(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_repo(Path(tmp) / "src", 2)
            other = Path(tmp) / "other"
            other.mkdir()
            (other / "keep.txt").write_text("keep")
            r = self.run_probe("reset", "--dir", str(other), "--source", str(src), "--cut", "HEAD", check=False)
            self.assertNotEqual(r.returncode, 0)
            self.assertTrue((other / "keep.txt").exists())

    def test_relative_dir_invalid_cut_and_untouched_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_repo(Path(tmp) / "src", 3)
            before = git(src, "for-each-ref"), git(src, "remote", "-v"), git(src, "status", "--porcelain")
            r = subprocess.run(["bash", str(PROBE), "reset", "--dir", "probe", "--source", str(src), "--cut", "HEAD~1",
                                "--tag", "v0.1.0"], capture_output=True, text=True, env=ENV, cwd=tmp)
            self.assertEqual(r.returncode, 0, r.stderr)
            self.assertEqual(git(Path(tmp) / "probe" / "archive.git", "tag", "-l"), "v0.1.0")
            r = self.run_probe("reset", "--dir", str(Path(tmp) / "probe"), "--source", str(src), "--cut", "nope", check=False)
            self.assertNotEqual(r.returncode, 0)
            self.assertTrue((Path(tmp) / "probe" / "archive.git").is_dir())  # a typo deletes nothing
            self.assertEqual((git(src, "for-each-ref"), git(src, "remote", "-v"), git(src, "status", "--porcelain")), before)

    def test_directory_with_only_workflow_files_is_accepted_but_home_is_not(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_repo(Path(tmp) / "src", 2)
            probe = Path(tmp) / "probe"
            probe.mkdir()
            (probe / "events.jsonl").write_text("")
            (probe / "lagebild-status.txt").write_text("GREEN\n")
            self.run_probe("reset", "--dir", str(probe), "--source", str(src), "--cut", "HEAD")
            self.assertTrue((probe / ".release-probe").exists())
            home = Path(tmp) / "home"
            home.mkdir()
            (home / ".release-probe").write_text("")
            r = subprocess.run(["bash", str(PROBE), "reset", "--dir", str(home), "--source", str(src), "--cut", "HEAD"],
                               capture_output=True, text=True, env={**ENV, "HOME": str(home)})
            self.assertNotEqual(r.returncode, 0)
            self.assertTrue(home.is_dir())

    def test_advance_only_goes_forward(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_repo(Path(tmp) / "src", 4)
            probe = Path(tmp) / "probe"
            self.run_probe("reset", "--dir", str(probe), "--source", str(src), "--cut", git(src, "rev-parse", "HEAD~1"))
            r = self.run_probe("advance", "--dir", str(probe), "--to", git(src, "rev-parse", "HEAD~3"), "--tag", "v0.0.1", check=False)
            self.assertNotEqual(r.returncode, 0)
            self.assertEqual(git(probe / "work", "tag", "-l"), "")

    def test_advance_refuses_a_commit_outside_the_source_history(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = make_repo(Path(tmp) / "src", 3)
            probe = Path(tmp) / "probe"
            self.run_probe("reset", "--dir", str(probe), "--source", str(src), "--cut", "HEAD~1")
            r = self.run_probe("advance", "--dir", str(probe), "--to", "0" * 40, check=False)
            self.assertNotEqual(r.returncode, 0)


if __name__ == "__main__":
    unittest.main()
