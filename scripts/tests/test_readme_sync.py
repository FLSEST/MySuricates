"""Tests for scripts/readme-sync/readme_sync.py (TODO c13a942b).

The occasion: for four changed paragraphs the sync retranslated the whole
README.md on 2026-09-30 and changed fixed wording. The tests pin that only the
changed blocks go to translation and that the gate guards every byte outside
of them.

Run: python3 -m unittest discover -s scripts/tests -p 'test_readme_sync.py'
"""
import hashlib
import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "readme-sync" / "readme_sync.py"

_spec = importlib.util.spec_from_file_location("readme_sync", SCRIPT)
rs = importlib.util.module_from_spec(_spec)
sys.modules["readme_sync"] = rs  # dataclasses need the module in sys.modules
_spec.loader.exec_module(rs)

DE = """**Deutsch** | [English](README.md)

<p align="center"><img src="docs/assets/readme-banner-de.svg" alt="Baue Workflows mit deinen Erdmännchen."></p>

# Titel

Erster Absatz im
Wortlaut.

Zweiter Absatz, siehe [Architektur](docs/arch.md#datenmodell).

| A | B |
|---|---|
| 1 Commit | 2 Tests |

```bash
echo hallo

echo welt
```

---

> 🌍 **README.de.md ist die Quelle.**
"""

EN = """[Deutsch](README.de.md) | **English**

<p align="center"><img src="docs/assets/readme-banner-en.svg" alt="Build workflows with your suricates."></p>

# Title

First paragraph in the author's
wording.

Second paragraph, see [architecture](docs/en/arch.md#data-model).

| A | B |
|---|---|
| 1 commit | 2 tests |

```bash
echo hallo

echo welt
```

---

> 🌍 **README.de.md is the source.**

<!-- synced-from: {commit} -->
"""

ARCH_DE = "# Architektur\n\n## Datenmodell\n\n## Ablauf\n"
ARCH_EN = "# Architecture\n\n## Data model\n\n## Flow\n"


def sha(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def git(root: Path, *args: str) -> str:
    return subprocess.run(["git", *args], cwd=root, capture_output=True, text=True, check=True).stdout


def run(root: Path, *args: str, stdin: str = "") -> tuple[int, str]:
    proc = subprocess.run([sys.executable, str(SCRIPT), "--root", str(root), *args],
                          input=stdin, capture_output=True, text=True)
    return proc.returncode, proc.stdout + proc.stderr


class Repo:
    """Temporary git repo with a synchronous README pair at the first commit."""

    def __init__(self, tmp: str):
        self.root = Path(tmp)
        git(self.root, "init", "-q")
        git(self.root, "config", "user.email", "t@users.noreply.github.com")
        git(self.root, "config", "user.name", "t")
        (self.root / "docs" / "en").mkdir(parents=True)
        (self.root / "docs" / "arch.md").write_text(ARCH_DE, encoding="utf-8")
        (self.root / "docs" / "en" / "arch.md").write_text(ARCH_EN, encoding="utf-8")
        (self.root / "docs" / "nur-de.md").write_text("# Nur deutsch\n", encoding="utf-8")
        self.write_de(DE)
        self.base = self.commit("de")
        self.marker = f"{self.base[:12]} sha256:{sha(DE)}"
        self.write_en(EN.format(commit=self.marker))
        self.commit("en")

    def write_de(self, text: str) -> None:
        (self.root / "README.de.md").write_text(text, encoding="utf-8")

    def write_en(self, text: str) -> None:
        (self.root / "README.md").write_text(text, encoding="utf-8")

    def en(self) -> str:
        return (self.root / "README.md").read_text(encoding="utf-8")

    def de(self) -> str:
        return (self.root / "README.de.md").read_text(encoding="utf-8")

    def body(self) -> str:
        return self.en().rsplit("<!-- synced-from", 1)[0]

    def commit(self, msg: str) -> str:
        git(self.root, "add", "-A")
        git(self.root, "commit", "-qm", msg)
        return git(self.root, "rev-parse", "HEAD").strip()

    def change_de(self, old: str, new: str) -> str:
        text = (self.root / "README.de.md").read_text(encoding="utf-8")
        assert old in text, old
        self.write_de(text.replace(old, new))
        return self.commit("de change")[:12]


class ParseTest(unittest.TestCase):
    def test_round_trip_is_lossless(self):
        for text in (DE, EN.format(commit="abc1234"), "", "\n\n", "a", "a\n\n\nb  \n \n", "```\n\n```"):
            self.assertEqual(rs.parse(text).render(), text)

    def test_blank_lines_in_code_fence_stay_in_one_block(self):
        doc = rs.parse(DE)
        code = [c for c in doc.chunks if rs.kind(c.body) == "code"]
        self.assertEqual(len(code), 1)
        self.assertIn("echo welt", code[0].body)

    def test_current_readmes_run_in_parallel(self):
        # Parity and sync state of the real READMEs: pins a manual change that
        # splits or merges a block and would thereby block every later sync.
        rc, out = run(REPO, "check")
        self.assertEqual(rc, 0, out)


class PlanApplyTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(self._tmp.name)
        self.state = Path(self._tmp.name) / ".git" / "readme-sync" / "run"

    def tearDown(self):
        self._tmp.cleanup()

    def test_only_the_changed_paragraph_is_translated(self):
        new = self.repo.change_de("Erster Absatz im\nWortlaut.", "Erster Absatz im\nneuem Wortlaut.")
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 0, out)
        self.assertIn("=== EN 4 ===", out)
        self.assertNotIn("=== EN 5 ===", out)
        self.assertIn("First paragraph in the author's", out)  # previous version as template
        self.assertEqual(run(self.repo.root, "has-work")[0], 0)

        before = self.repo.en()
        rc, out = run(self.repo.root, "apply", "--translations", "-",
                      stdin="Sure!\n=== EN 4 ===\nFirst paragraph in the author's\nnew wording.\n=== END ===\n")
        self.assertEqual(rc, 0, out)
        self.assertIn("gate_status: GREEN", out)
        after = self.repo.en()
        self.assertEqual(after, before.replace("wording.", "new wording.", 1)
                         .replace(self.repo.marker, f"{new} sha256:{sha(self.repo.de())}"))
        self.assertEqual(run(self.repo.root, "has-work")[0], 1)

    def test_rewrap_only_needs_no_translation(self):
        self.repo.change_de("Erster Absatz im\nWortlaut.", "Erster Absatz\nim Wortlaut.")
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 0, out)
        self.assertIn("Kein KI-Auftrag", out)
        self.assertEqual(run(self.repo.root, "has-work")[0], 1)
        before = self.repo.body()
        rc, out = run(self.repo.root, "apply", "--translations", "-", stdin="ignored")
        self.assertEqual(rc, 0, out)
        self.assertIn("gate_status: GREEN", out)
        self.assertEqual(self.repo.body(), before)
        self.assertIn(f"sha256:{sha(self.repo.de())}", self.repo.en())  # sync state advances

    def test_inserted_and_deleted_blocks(self):
        text = (self.repo.root / "README.de.md").read_text(encoding="utf-8")
        text = text.replace("# Titel\n\n", "# Titel\n\nNeuer Absatz.\n\n")
        text = text.replace("Zweiter Absatz, siehe [Architektur](docs/arch.md#datenmodell).\n\n", "")
        self.repo.write_de(text)
        new = self.repo.commit("ins+del")[:12]
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 0, out)
        rc, out = run(self.repo.root, "apply", "--translations", "-",
                      stdin="=== EN 4 ===\nNew paragraph.\n=== END ===\n")
        self.assertEqual(rc, 0, out)
        en = self.repo.en()
        self.assertIn("# Title\n\nNew paragraph.\n\nFirst paragraph in the author's\nwording.\n\n| A | B |", en)
        self.assertNotIn("Second paragraph", en)
        self.assertTrue(en.endswith(f"<!-- synced-from: {new} sha256:{sha(self.repo.de())} -->\n"))

    def test_links_are_rewritten_deterministically(self):
        self.repo.change_de("Zweiter Absatz, siehe [Architektur](docs/arch.md#datenmodell).",
                            "Zweiter Absatz, siehe [Architektur](docs/arch.md#ablauf), "
                            "[docs/arch.md](docs/arch.md), [Nur DE](docs/nur-de.md) und [Vision](VISION.md).")
        run(self.repo.root, "plan")
        answer = ("=== EN 5 ===\nSecond paragraph, see [architecture](docs/arch.md#ablauf), "
                  "[docs/arch.md](docs/arch.md), [German only](docs/nur-de.md) and [vision](VISION.md).\n=== END ===")
        rc, out = run(self.repo.root, "apply", "--translations", "-", stdin=answer)
        self.assertEqual(rc, 0, out)
        en = self.repo.en()
        self.assertIn("[architecture](docs/en/arch.md#flow)", en)
        self.assertIn("[docs/en/arch.md](docs/en/arch.md)", en)
        self.assertIn("[German only](docs/nur-de.md)", en)  # no English version: stays
        self.assertIn("[vision](VISION.md)", en)  # docs/en/vision.md is missing here: stays

    def test_unmappable_anchor_is_dropped_with_warning(self):
        self.repo.change_de("docs/arch.md#datenmodell", "docs/arch.md#gibt-es-nicht")
        run(self.repo.root, "plan")
        rc, out = run(self.repo.root, "apply", "--translations", "-",
                      stdin="=== EN 5 ===\nSecond paragraph, see [architecture](docs/arch.md#gibt-es-nicht).\n=== END ===")
        self.assertEqual(rc, 0, out)
        self.assertIn("gate_status: YELLOW", out)
        self.assertIn("[architecture](docs/en/arch.md)", self.repo.en())

    def test_fixed_blocks_are_never_translated(self):
        self.repo.change_de("mit deinen Erdmännchen.", "mit deinen Erdmännchen, jetzt neu.")
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 0, out)
        self.assertIn("Kein KI-Auftrag", out)
        before = self.repo.body()
        rc, out = run(self.repo.root, "apply", "--translations", "-", stdin="")
        self.assertEqual(rc, 0, out)
        self.assertIn("gate_status: YELLOW", out)
        self.assertIn("`banner`", out)
        self.assertEqual(self.repo.body(), before)

    def test_task_carries_the_glossary(self):
        self.repo.change_de("Erster Absatz", "Erster Absatz der Erdmännchen")
        _rc, out = run(self.repo.root, "plan")
        self.assertIn('Erdmännchen -> suricates (never "meerkats")', out)

    def test_missing_or_malformed_translation_is_red_and_writes_nothing(self):
        self.repo.change_de("| 1 Commit | 2 Tests |", "| 3 Commits | 4 Tests |")
        run(self.repo.root, "plan")
        before = self.repo.en()
        for answer, reason in (
            ("nothing useful", "keine Uebersetzung"),
            ("=== EN 6 ===\n| A | B |\n|---|---|\n| 3 commits | 4 tests | extra |\n=== END ===", "Tabellenform"),
            ("=== EN 6 ===\nA paragraph.\n=== END ===", "para statt table"),
            ("=== EN 6 ===\n| A | B |\n\n|---|---|\n=== END ===", "Bloecke statt einem"),
            ("=== EN 6 ===\n| A | B |\n|---|---|\n| 3 | 4 |\n=== EN 7 ===\nx\n=== END ===", "nicht verlangte"),
        ):
            rc, out = run(self.repo.root, "apply", "--translations", "-", stdin=answer)
            self.assertEqual(rc, 1, out)
            self.assertIn("gate_status: RED", out)
            self.assertIn(reason, out)
            self.assertEqual(self.repo.en(), before)

    def test_answer_wrapped_in_markdown_fence_is_accepted(self):
        self.repo.change_de("| 1 Commit | 2 Tests |", "| 3 Commits | 4 Tests |")
        run(self.repo.root, "plan")
        rc, out = run(self.repo.root, "apply", "--translations", "-",
                      stdin="=== EN 6 ===\n```markdown\n| A | B |\n|---|---|\n| 3 commits | 4 tests |\n```\n=== END ===")
        self.assertEqual(rc, 0, out)
        self.assertIn("| 3 commits | 4 tests |\n\n```bash", self.repo.en())

    def test_direct_edit_of_readme_during_run_is_discarded(self):
        self.repo.change_de("Erster Absatz im", "Erster geaenderter Absatz im")
        run(self.repo.root, "plan")
        # the AI rewrites the whole file despite the prohibition ("meerkats")
        self.repo.write_en(self.repo.en().replace("suricates", "meerkats"))
        rc, out = run(self.repo.root, "apply", "--translations", "-",
                      stdin="=== EN 4 ===\nFirst changed paragraph in the author's\nwording.\n=== END ===")
        self.assertEqual(rc, 0, out)
        self.assertIn("gate_status: YELLOW", out)
        self.assertIn("suricates", self.repo.en())
        self.assertNotIn("meerkats", self.repo.en())

    def test_source_changed_during_run_is_red(self):
        self.repo.change_de("Erster Absatz", "Erster neuer Absatz")
        run(self.repo.root, "plan")
        before = self.repo.en()
        self.repo.write_de(DE + "\nNachtrag.\n")
        rc, out = run(self.repo.root, "apply", "--translations", "-", stdin="=== EN 4 ===\nx\n=== END ===")
        self.assertEqual(rc, 1, out)
        self.assertIn("gate_status: RED", out)
        self.assertEqual(self.repo.en(), before)


class PreconditionTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_missing_marker(self):
        self.repo.write_en(self.repo.en().rsplit("\n<!-- synced-from", 1)[0])
        self.repo.commit("no marker")
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 2)
        self.assertIn("Sync-Stand unbekannt", out)

    def test_unknown_marker_commit(self):
        self.repo.write_en(self.repo.en().replace(self.repo.marker, "deadbeefdead"))  # without hash
        self.repo.commit("bad marker")
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 2)
        self.assertIn("unbekannt", out)

    def test_uncommitted_source(self):
        self.repo.write_de(DE.replace("Titel", "Neuer Titel"))
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 2)
        self.assertIn("uncommittete", out)

    def test_non_parallel_readmes(self):
        self.repo.write_en(self.repo.en().replace("First paragraph in the author's\nwording.\n\n",
                                                  "First paragraph in the author's\n\nwording.\n\n"))
        self.repo.commit("split block")
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 2)
        self.assertIn("Block fuer Block", out)
        self.assertEqual(run(self.repo.root, "check")[0], 1)

    def test_mark_sets_marker_to_last_source_commit(self):
        new = self.repo.change_de("Erster Absatz", "Erster neuer Absatz")
        rc, out = run(self.repo.root, "mark")
        self.assertEqual(rc, 0, out)
        self.assertTrue(self.repo.en().endswith(f"\n\n<!-- synced-from: {new} sha256:{sha(self.repo.de())} -->\n"))
        self.assertEqual(self.repo.en().count("synced-from"), 1)


class GateTest(unittest.TestCase):
    """verify() independent of apply: every deviation outside the translated blocks stands out."""

    OLD = rs.Marker("abc1234", None)
    NEW = rs.Marker("abc9999", "f" * 64)

    def setUp(self):
        self.snapshot, _ = rs.split_marker(rs.parse(EN.format(commit=self.OLD.commit)))
        self.de = rs.parse(DE)
        self.items = [{"keep": i} for i in range(9)]
        self.items[3] = {"translate": True, "template": 3}

    def final(self, mutate=lambda t: t) -> str:
        text = EN.format(commit=f"{self.NEW.commit} sha256:{self.NEW.hash}").replace("wording.", "new wording.", 1)
        return mutate(text)

    def test_change_inside_translated_block_passes(self):
        self.assertEqual(rs.verify(self.snapshot, self.items, self.de, self.final(), self.NEW), [])

    def test_byte_outside_translated_blocks_fails(self):
        for mutate in (
            lambda t: t.replace("suricates", "meerkats"),
            lambda t: t.replace("| 1 commit |", "| 1 commit  |"),
            lambda t: t.replace("# Title\n\n", "# Title\n\n\n"),
            lambda t: t.replace("source.**\n", "source.**\n\nExtra.\n"),
            lambda t: t.replace("echo welt", "  echo welt"),
        ):
            problems = rs.verify(self.snapshot, self.items, self.de, self.final(mutate), self.NEW)
            self.assertTrue(problems, mutate)

    def test_wrong_marker_fails(self):
        self.assertTrue(rs.verify(self.snapshot, self.items, self.de, self.final(), rs.Marker("fff0000", None)))


class ReviewFixTest(unittest.TestCase):
    """Follow-ups from the review (code blocks exact, moves, content hash, delete-only)."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def de(self) -> str:
        return self.repo.de()

    def test_code_block_indentation_counts_as_change(self):
        self.repo.change_de("echo welt\n```", "  echo welt\n```")
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 0, out)
        self.assertIn("=== EN 7 ===", out)

    def test_moved_block_keeps_its_english_text_without_ai(self):
        para = "Zweiter Absatz, siehe [Architektur](docs/arch.md#datenmodell).\n\n"
        text = self.de().replace(para, "").replace("---\n", para + "---\n")
        self.repo.write_de(text)
        new = self.repo.commit("move")[:12]
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 0, out)
        self.assertNotIn("=== EN", out)
        self.assertEqual(run(self.repo.root, "has-work")[0], 1)
        rc, out = run(self.repo.root, "apply", "--translations", "-", stdin="")
        self.assertEqual(rc, 0, out)
        self.assertIn("gate_status: GREEN", out)
        en = self.repo.en()
        self.assertIn("echo welt\n```\n\nSecond paragraph, see [architecture](docs/en/arch.md#data-model).\n\n---\n", en)
        self.assertEqual(en.count("Second paragraph"), 1)
        self.assertIn(f"synced-from: {new} sha256:{sha(text)}", en)

    def test_moved_and_edited_block_gets_its_english_template(self):
        para = "Zweiter Absatz, siehe [Architektur](docs/arch.md#datenmodell).\n\n"
        text = self.de().replace(para, "").replace("---\n", para.replace("Zweiter", "Zweiter, neuer") + "---\n")
        self.repo.write_de(text)
        self.repo.commit("move+edit")
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 0, out)
        self.assertIn("=== EN 7 ===", out)
        self.assertIn("Second paragraph, see [architecture]", out)

    def test_delete_only_run_needs_no_ai_and_advances_the_marker(self):
        para = "Zweiter Absatz, siehe [Architektur](docs/arch.md#datenmodell).\n\n"
        text = self.de().replace(para, "")
        self.repo.write_de(text)
        new = self.repo.commit("delete")[:12]
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 0, out)
        self.assertEqual(run(self.repo.root, "has-work")[0], 1)
        rc, out = run(self.repo.root, "apply", "--translations", "-", stdin="")
        self.assertEqual(rc, 0, out)
        self.assertIn("gate_status: GREEN", out)
        self.assertNotIn("Second paragraph", self.repo.en())
        self.assertTrue(self.repo.en().endswith(f"<!-- synced-from: {new} sha256:{sha(text)} -->\n"))

    def test_marker_carries_the_content_hash(self):
        rc, out = run(self.repo.root, "mark")
        self.assertEqual(rc, 0, out)
        self.assertTrue(self.repo.en().endswith(f"<!-- synced-from: {self.repo.base[:12]} sha256:{sha(DE)} -->\n"))

    def test_unknown_commit_with_matching_hash_continues(self):
        # Release handover/history rewrite: the commit no longer exists, the content still does
        en = self.repo.en().rsplit("<!-- synced-from", 1)[0] + f"<!-- synced-from: deadbeefdead sha256:{sha(DE)} -->\n"
        self.repo.write_en(en)
        self.repo.commit("rewritten")
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 0, out)
        self.assertIn("Kein KI-Auftrag", out)
        self.assertEqual(run(self.repo.root, "check", "--current")[0], 0)

    def test_unknown_commit_and_unknown_hash_asks_for_mark(self):
        en = self.repo.en().rsplit("<!-- synced-from", 1)[0] + f"<!-- synced-from: deadbeefdead sha256:{'0' * 64} -->\n"
        self.repo.write_en(en)
        self.repo.commit("lost")
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 2)
        self.assertIn("mark", out)

    def test_hash_contradicting_the_commit_is_refused(self):
        en = self.repo.en().rsplit("<!-- synced-from", 1)[0] + f"<!-- synced-from: {self.repo.base[:12]} sha256:{'0' * 64} -->\n"
        self.repo.write_en(en)
        self.repo.commit("contradiction")
        rc, out = run(self.repo.root, "plan")
        self.assertEqual(rc, 2)
        self.assertIn("mark", out)

    def test_check_current_reports_pending_sync(self):
        self.assertEqual(run(self.repo.root, "check", "--current")[0], 0)
        self.repo.change_de("Erster Absatz", "Erster neuer Absatz")
        rc, out = run(self.repo.root, "check", "--current")
        self.assertEqual(rc, 1, out)
        self.assertEqual(run(self.repo.root, "check")[0], 0)  # parity with the sync state stays


if __name__ == "__main__":
    unittest.main()
