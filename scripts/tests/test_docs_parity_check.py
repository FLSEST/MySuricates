"""Tests for scripts/public-readiness/docs_parity_check.py (TODO c58d7402).

Two of the cases pin errors that the first version of the script really had and that
would both have led to **false findings** — the most expensive kind of error in a gate:

* `test_slug_keeps_each_space`   — slug collapsed runs of spaces, correct anchors
                                                                      counted as dead.
* `test_headings_skip_code_block` — `# Kommentar` in a shell example counted as
                                                                      a heading and produced phantom differences.

Run: python3 -m unittest discover -s scripts/tests -p 'test_docs_parity_check.py'
"""
import contextlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "public-readiness" / "docs_parity_check.py"

_spec = importlib.util.spec_from_file_location("docs_parity_check", SCRIPT)
dpc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(dpc)


def run_script(root: Path):
    proc = subprocess.run(
        [sys.executable, str(SCRIPT), "--root", str(root)],
        capture_output=True, text=True, check=False,
    )
    return proc.returncode, proc.stdout


class SlugTest(unittest.TestCase):
    def test_slug_keeps_each_space(self):
        # `a` / `b` — c  ->  after removing the backtick and
        # slash there are two spaces between a and b, GitHub turns them into two hyphens.
        self.assertEqual(dpc.slug("### `a` / `b` — c"), "a--b--c")

    def test_slug_real_heading(self):
        heading = "### `checked_command` / `check_category` — Gate automatisch aus dem Exit-Code"
        self.assertEqual(
            dpc.slug(heading),
            "checked_command--check_category--gate-automatisch-aus-dem-exit-code",
        )

    def test_slug_umlauts_survive(self):
        self.assertEqual(dpc.slug("## Bekannte Einschränkungen"), "bekannte-einschränkungen")


class HeadingsTest(unittest.TestCase):
    def test_headings_skip_code_block(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "a.md"
            f.write_text("# Echt\n\n```bash\n# nur ein Kommentar\necho hi\n```\n\n## Auch echt\n")
            self.assertEqual(dpc.headings(f), ["# Echt", "## Auch echt"])

    def test_headings_skip_shebang(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "a.md"
            f.write_text("#!/usr/bin/env bash\n# Echt\n")
            self.assertEqual(dpc.headings(f), ["# Echt"])


class GateTest(unittest.TestCase):
    def build(self, tmp, *, de_extra="", en_extra="", link="", extra_de_file=None):
        root = Path(tmp)
        (root / "docs" / "en").mkdir(parents=True)
        (root / "docs" / "a.md").write_text(f"# A\n\n## Eins\n{de_extra}\n{link}\n")
        (root / "docs" / "en" / "a.md").write_text(f"# A\n\n## One\n{en_extra}\n")
        if extra_de_file:
            (root / "docs" / extra_de_file).write_text("# Extra\n")
        return root

    def test_clean_repo_is_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp))
            self.assertEqual(code, 0, out)
            self.assertIn("gate_status: GREEN", out)
            self.assertIn("findings: 0", out)

    def test_dead_target_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, link="[weg](nirgends.md)"))
            self.assertEqual(code, 1, out)
            self.assertIn("gate_status: YELLOW", out)
            self.assertIn("BEFUND: totes-ziel", out)

    def test_dead_anchor_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, link="[hin](a.md#gibtsnicht)"))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: toter-anker", out)

    def test_live_anchor_is_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, link="[hin](a.md#eins)"))
            self.assertEqual(code, 0, out)

    def test_commented_link_is_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, link="<!-- [weg](nirgends.md) -->"))
            self.assertEqual(code, 0, out)

    def test_placeholder_route_is_ignored(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, link="[Lauf](/workflows/<id>)"))
            self.assertEqual(code, 0, out)

    def test_missing_en_file_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, extra_de_file="b.md"))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: kein-en-gegenstueck docs/b.md", out)

    def test_missing_en_section_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de_extra="\n## Zwei\n"))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: abschnitt-fehlt-en", out)

    def test_known_gap_stays_green(self):
        # A gap listed in KNOWN_GAPS is reported by the gate as known and stays green.
        # KNOWN_GAPS has been empty since TODO 47bd4d27; the mechanism is tested with a
        # test entry — in the same process, because a subprocess would not see the replacement.
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp, extra_de_file="diagramme.md")
            argv = ["docs_parity_check.py", "--root", str(root)]
            with mock.patch.dict(dpc.KNOWN_GAPS, {"docs/diagramme.md": "test"}), \
                    mock.patch.object(sys, "argv", argv), \
                    contextlib.redirect_stdout(io.StringIO()) as buffer:
                code = dpc.main()
            out = buffer.getvalue()
            self.assertEqual(code, 0, out)
            self.assertIn("bekannt: kein-en-gegenstueck docs/diagramme.md (bekannt, TODO test)", out)
            self.assertIn("gate_status: GREEN", out)

    def test_english_link_to_german_page_is_reported_not_failed(self):
        # README.md and docs/en/ are the English path. A link from there to a
        # purely German page is a hint for the audit, the gate stays green (TODO 1b84ecab).
        with tempfile.TemporaryDirectory() as tmp:
            # `plans/` is not a mirrored folder (unlike konzepte/, see MIRRORED_SUBDIRS).
            root = self.build(tmp, en_extra="[Plan](../plans/README.md)\n[A](a.md)")
            (root / "docs" / "plans").mkdir()
            (root / "docs" / "plans" / "README.md").write_text("# Plan\n")
            (root / "README.md").write_text("# R\n\n[Diagramme](docs/a.md)\n[Guide](docs/en/a.md)\n")
            code, out = run_script(root)
            self.assertEqual(code, 0, out)
            self.assertIn("en_links_to_german: 2", out)
            self.assertIn("HINWEIS: en-verweist-auf-deutsch docs/en/a.md:4 -> docs/plans/README.md", out)
            self.assertIn("HINWEIS: en-verweist-auf-deutsch README.md:3 -> docs/a.md", out)
            self.assertNotIn("-> docs/en/a.md", out)

    def test_german_page_linking_german_is_not_reported(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp, extra_de_file="b.md", link="[B](b.md)")
            (root / "docs" / "en" / "b.md").write_text("# Extra\n")
            code, out = run_script(root)
            self.assertIn("en_links_to_german: 0", out)


class MirroredSubdirTest(unittest.TestCase):
    """Subfolders like docs/konzepte/ are checked as mirrored like docs/ itself (TODO b94beaed)."""

    def build(self, tmp, *, de_body="# K\n\n## Eins\n", en_body="# K\n\n## One\n", en_file=True):
        root = Path(tmp)
        (root / "docs" / "en" / "konzepte").mkdir(parents=True)
        (root / "docs" / "konzepte").mkdir(parents=True)
        (root / "docs" / "a.md").write_text("# A\n")
        (root / "docs" / "en" / "a.md").write_text("# A\n")
        (root / "docs" / "konzepte" / "k.md").write_text(de_body)
        if en_file:
            (root / "docs" / "en" / "konzepte" / "k.md").write_text(en_body)
        return root

    def test_mirrored_pair_is_green(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp))
            self.assertEqual(code, 0, out)

    def test_missing_en_file_in_subdir_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, en_file=False))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: kein-en-gegenstueck docs/konzepte/k.md", out)

    def test_missing_en_section_in_subdir_is_yellow(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de_body="# K\n\n## Eins\n\n## Zwei\n"))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: abschnitt-fehlt-en konzepte/k.md '## Zwei'", out)

    def test_english_link_to_data_file_is_not_reported(self):
        # Measurement data (JSON) is language-neutral – only pages (.md) count as "German".
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp, en_body="# K\n\n## One\n[Daten](../../konzepte/daten.json)\n")
            (root / "docs" / "konzepte" / "daten.json").write_text("{}\n")
            code, out = run_script(root)
            self.assertIn("en_links_to_german: 0", out)


class SectionPairingTest(unittest.TestCase):
    """A missing section is attributed to the right one, not to the last one of equal rank.

    The first version compared only the level sequence and, for a missing middle
    "##" section, always reported the last one -- so "## Bekannte Einschränkungen" (instead of
    "## Diagramm-Seiten") and "## Completion Triggers (V39)" (instead of the Audit Center) stood
    as known gaps in KNOWN_SECTION_GAPS (TODO 2d553479).
    """

    def build(self, tmp, de_body, en_body, name="a.md"):
        root = Path(tmp)
        (root / "docs" / "en").mkdir(parents=True)
        (root / "docs" / name).write_text(de_body)
        (root / "docs" / "en" / name).write_text(en_body)
        return root

    def test_missing_middle_section_is_named_by_its_heading(self):
        # Replica of docs/architektur.md: the Audit Center was missing in the English version.
        de = ("# Architektur\n\n## Workflow Chains\n\n## Completion Triggers (V39)\n\n"
              "## Audit Center — Findings, Akzeptanz und Verfall (V68/V69)\n\n"
              "## Todo Triggers — Self-Improvement Loop (V51/V52)\n\n## Notification System (V40)\n")
        en = ("# Architecture\n\n## Workflow Chains\n\n## Completion Triggers (V39)\n\n"
              "## Todo Triggers — Self-Improvement Loop (V51/V52)\n\n## Notification System (V40)\n")
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de, en))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: abschnitt-fehlt-en a.md '## Audit Center — Findings, Akzeptanz und Verfall"
                          " (V68/V69)' (Ueberschrift 4 von 6, nach '## Completion Triggers (V39)')", out)
            self.assertEqual(out.count("abschnitt-fehlt-en"), 1, out)
            self.assertNotIn("Notification System", out)

    def test_translated_headings_are_paired_through_the_section_text(self):
        # Replica of docs/entwicklung.md: "## Diagramm-Seiten" was missing, what was reported was
        # "## Bekannte Einschränkungen". The headings share no word -- the section text
        # does: code, paths and identifiers stand the same in both languages.
        de = ("# Entwicklung\n\n## Hygiene-Gate\n`scripts/ci/public-hygiene.sh`, `--tracked`\n\n"
              "## Diagramm-Seiten\nErzeugt von `scripts/docs-diagrams.py` mit `--add-missing` und `--check`.\n\n"
              "## Abhängigkeits-Meldungen (Frontend)\n`npm audit`, `dep_vuln_check.py`, `package-lock.json`\n\n"
              "## Bekannte Einschränkungen\n`pool-size=1`, `SQLITE_BUSY`, `HikariCP`\n")
        en = ("# Development\n\n## Hygiene gate\n`scripts/ci/public-hygiene.sh`, `--tracked`\n\n"
              "## Dependency advisories (frontend)\n`npm audit`, `dep_vuln_check.py`, `package-lock.json`\n\n"
              "## Known Limitations\n`pool-size=1`, `SQLITE_BUSY`, `HikariCP`\n")
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de, en))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: abschnitt-fehlt-en a.md '## Diagramm-Seiten'", out)
            self.assertNotIn("Bekannte Einschränkungen", out)
            self.assertNotIn("Abhängigkeits-Meldungen", out)

    def test_moved_section_is_reported_on_both_sides(self):
        # Replica of docs/en/datenmodell.md: same number of headings, but `goals` stood
        # behind the enums instead of at the tables. Before, this stayed invisible (same count).
        de = "# D\n\n## Tabellen\n\n### `todos`\n\n### `goals`\n\n## Enums\n\n### RunStatus\n"
        en = "# D\n\n## Tables\n\n### `todos`\n\n## Enums\n\n### RunStatus\n\n### `goals`\n"
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de, en))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: abschnitt-fehlt-en a.md '### `goals`' (Ueberschrift 4 von 6", out)
            self.assertIn("BEFUND: abschnitt-nur-en a.md '### `goals`' (Ueberschrift 6 von 6", out)

    def test_same_level_sequence_is_not_paired(self):
        # Same level sequence: whether a heading is translated or replaced, the text
        # alone does not say for sure -- the check stays silent there, as before.
        de = "# D\n\n## Eins\n\n## Zwei\n"
        en = "# D\n\n## One\n\n## Something else\n"
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de, en))
            self.assertEqual(code, 0, out)

    def align(self, de_text, en_text):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            de_file, en_file = root / "de.md", root / "en.md"
            de_file.write_text(de_text)
            en_file.write_text(en_text)
            de, en = dpc.sections(de_file), dpc.sections(en_file)
            only_de, only_en = dpc.align_sections(de, en)
            return [de[i][0] for i in only_de], [en[j][0] for j in only_en]

    def test_without_any_signal_the_later_section_counts_as_missing(self):
        # Pure prose, fully translated headings, same shape: no similarity, only the
        # levels. Then it stays a guess, and it falls on the later section as before.
        got = self.align("# D\n\n## Eins\nText.\n\n## Zwei\nTexte\n", "# D\n\n## One\nText.\n")
        self.assertEqual(got, (["## Zwei"], []))

    def test_shape_decides_between_prose_sections(self):
        # Counterpart: only the shape (length, lines) distinguishes the sections -- it suffices.
        # Without it the guess would fall on "## Zwei" (TODO 3fbff015).
        de = ("# D\n\n## Eins\nEin langer Absatz, der den Mechanismus ausfuehrlich erklaert und dabei"
              " mehrere Saetze braucht, um alles zu sagen.\n\n## Zwei\nKurz.\n")
        en = "# D\n\n## One\nShort.\n"
        self.assertEqual(self.align(de, en), (["## Eins"], []))

    def test_concept_page_prose_sections(self):
        # Replica of docs/konzepte/*.md as they were then (with a measurement section, which the pages
        # no longer have since TODO 1ab3ad21): fixed outline, pure prose, fully translated
        # headings. With "Vorteile" missing, the check named "Nachteile und Kosten" -- the
        # Betriebsbeleg shares the number `263` with it and pulled it to itself (TODO 3fbff015).
        de = ("# Haertung\n\n## Wofür taugt das?\n" + "Ein Absatz ueber den Zweck. " * 28 + "\n\n"
              "## Vorteile\n" + "Ein Absatz ueber den Nutzen. " * 21 + "\n\n"
              "## Nachteile und Kosten\n" + "Ein Absatz ueber die Kosten. " * 20 + "Seit 263 Laeufen.\n\n"
              "## Betriebsbeleg\nMessung 2026-09-19, siehe [Daten](betriebsdaten.json).\n\n"
              "| Groesse | Wert |\n|---|---:|\n| Laeufe | 263 |\n| Siegel | 4 |\n\n"
              "## Im Code\n- [RunAdmission.java](../RunAdmission.java)\n- [OutputTaint.java](../OutputTaint.java)\n")
        en = ("# Hardening\n\n## What is it useful for?\n" + "A paragraph about the purpose. " * 25 + "\n\n"
              "## Drawbacks and costs\n" + "A paragraph about the costs. " * 20 + "Since 263 runs.\n\n"
              "## Operational evidence\nMeasured 2026-09-19, see [data](betriebsdaten.json).\n\n"
              "| Measure | Value |\n|---|---:|\n| Runs | 263 |\n| Seals | 4 |\n\n"
              "## In the code\n- [RunAdmission.java](../RunAdmission.java)\n- [OutputTaint.java](../OutputTaint.java)\n")
        self.assertEqual(self.align(de, en), (["## Vorteile"], []))

    def test_near_identical_neighbours_are_told_apart_by_heading(self):
        # Replica of docs/api-referenz.md: two endpoints of the same resource with the same text.
        # The text fits fully on both; only the heading decides (TODO 3fbff015).
        body = "Webhook schalten.\n\n**Response:** `WebhookTriggerResponse` (`tokenVisible: false`)\n\n"
        body_en = "Toggle the webhook.\n\n**Response:** `WebhookTriggerResponse` (`tokenVisible: false`)\n\n"
        de = ("# API\n\n### `POST /api/v1/webhooks/{id}/enable`\n" + body
              + "### `POST /api/v1/webhooks/{id}/disable`\n" + body)
        en = "# API\n\n### `POST /api/v1/webhooks/{id}/disable`\n" + body_en
        self.assertEqual(self.align(de, en), (["### `POST /api/v1/webhooks/{id}/enable`"], []))


class StockPairingTest(unittest.TestCase):
    """The gaps that the pairing named wrongly on the real inventory (TODO 3fbff015).

    Probe 2026-10-01: in every file with the same level sequence each English section removed
    individually -- the version of TODO 2d553479 named 29 of 1213 gaps wrongly (plus one case
    that flipped during the work). Each case is replayed here on the real file pair. If
    a heading drops out later or the level sequence deviates, the case is skipped, not
    counted as green.
    """

    CASES = (
        ("WORKFLOW-GUIDE.md", "### Welches Modell wann verwenden"),
        ("WORKFLOW-GUIDE.md", "### Provider-Regel: Codex ist read-only"),
        ("WORKFLOW-GUIDE.md", "### History-Komprimierung"),
        ("WORKFLOW-GUIDE.md", "#### Automatische Extraktion (Auto-Memory)"),
        ("architektur.md", "## WebSocket (Live-Logs + Status)"),
        ("entwicklung.md", "## Abhängigkeits-Meldungen (Frontend)"),
        ("workflow-memory-diagrams.md", "### workflow_memory"),
        ("konzepte/agentische-budgets.md", "## Wofür taugt das?"),
        ("konzepte/gates.md", "## Wofür taugt das?"),
        ("konzepte/haertung.md", "## Wofür taugt das?"),
        ("konzepte/haertung.md", "## Vorteile"),
        ("konzepte/messen-vor-bewerten.md", "## Wofür taugt das?"),
        ("konzepte/messen-vor-bewerten.md", "## Vorteile"),
        ("konzepte/persistenz-herkunft.md", "## Wofür taugt das?"),
        ("konzepte/persistenz-herkunft.md", "## Vorteile"),
        ("konzepte/selbstverbesserung.md", "## Wofür taugt das?"),
        ("konzepte/workflow-memory.md", "## Wofür taugt das?"),
        ("konzepte/workflow-memory.md", "## Vorteile"),
        ("konzepte/zugaenge.md", "## Wofür taugt das?"),
        ("konzepte/zugaenge.md", "## Vorteile"),
    )

    def test_previously_misnamed_gaps_are_named_by_their_heading(self):
        by_file: dict[str, list[str]] = {}
        for rel, heading in self.CASES:
            by_file.setdefault(rel, []).append(heading)
        for rel, wanted in by_file.items():
            de_file, en_file = REPO / "docs" / rel, REPO / "docs" / "en" / rel
            if not de_file.exists() or not en_file.exists():
                for heading in wanted:
                    with self.subTest(rel=rel, heading=heading):
                        self.skipTest("Dateipaar fehlt")
                continue
            de, en = dpc.sections(de_file), dpc.sections(en_file)
            same_levels = [dpc._level(h) for h, _ in de] == [dpc._level(h) for h, _ in en]
            score = dpc.pair_scores(de, en) if same_levels else None
            for heading in wanted:
                with self.subTest(rel=rel, heading=heading):
                    names = [h for h, _ in de]
                    if score is None or heading not in names:
                        self.skipTest("Ebenenfolge weicht ab oder Ueberschrift fehlt")
                    k = names.index(heading)
                    only_de, only_en = dpc.align_scores([row[:k] + row[k + 1:] for row in score])
                    self.assertEqual(([names[i] for i in only_de], only_en), ([heading], []))

    def test_sections_skip_code_blocks_like_headings(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = Path(tmp) / "a.md"
            f.write_text("# Echt\nvorn\n```bash\n# Kommentar\n```\n## Auch echt\nhinten\n")
            got = dpc.sections(f)
            self.assertEqual([h for h, _ in got], dpc.headings(f))
            self.assertIn("# Kommentar", got[0][1])
            self.assertEqual(got[1][1], "hinten")


REFERENCE = REPO / "scripts" / "docs-reference.py"
_ref_spec = importlib.util.spec_from_file_location("docs_reference", REFERENCE)
ref = importlib.util.module_from_spec(_ref_spec)
sys.modules[_ref_spec.name] = ref  # dataclasses need the module in sys.modules
_ref_spec.loader.exec_module(ref)

FIXTURE_CONTROLLER = """package com.automation.controller;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;

/** Foos verwalten: anlegen und lesen (TODO 1a2b3c4d). Zweiter Satz faellt weg. */
@RestController
@RequestMapping("/api/v1/foos")
@RequiresModule(ModuleCatalog.CHAINS)
public class FooController {

    /**
     * Liefert ein Foo, z. B. das unter {@code /{id}/bar} verlinkte (TODO 5e6f7a8b). Dieser Satz
     * gehoert nicht mehr dazu.
     *
     * @param id die ID
     */
    @GetMapping("/{id}")
    public Foo get(@PathVariable String id) { return null; }

    /**
     * Ersetzt ein Foo. Antwortet mit {@code 409}, solange es benutzt wird (TODO 9a8b7c6d).
     * <p>Siehe {@link FooService#replace}.
     *
     * @return das Foo
     */
    @PutMapping("/{id}")
    public Foo replace(@PathVariable String id) { return null; }

    @PostMapping
    @RequiresModule(ModuleCatalog.WEBHOOKS)
    public Foo create() { return null; }

    // Kein Javadoc, nur ein Kommentar.
    @GetMapping(value = "/{id}/text", produces = "text/markdown")
    public String text(@PathVariable String id) { return ""; }
}
"""

FIXTURE_ERROR_CODE = """public enum ErrorCode {
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    TEAPOT(HttpStatus.I_AM_A_TEAPOT, "Short and stout");
}
"""

FIXTURE_MODULES = """public enum ModuleCatalog {
    CHAINS("chains", RESEARCH, STABLE, false, Set.of(), List.of(), "Chains."),
    WEBHOOKS("webhooks", ADVANCED, STABLE, false, Set.of(), List.of(), "Webhooks.");
}
"""


def _doc(de, en):
    return {"de": de, "en": en}


FIXTURE_SCHEMA = {
    "onFailure": {"type": "string", "doc": _doc("Bei Fehler.", "On failure."), "default": "stop",
                  "values": [{"value": "stop", "doc": _doc("Stoppen.", "Stop.")},
                             {"value": "continue", "doc": _doc("Weiter.", "Go on.")}]},
    "commonFields": {"label": {"type": "string", "doc": _doc("Anzeigename.", "Label.")}},
    "ai": {"blockTypes": ["simple"], "fields": {"effort": {"type": "string", "doc": _doc("Aufwand.", "Effort."),
                                                            "values": [{"value": "low", "doc": _doc("Wenig.", "Little.")}],
                                                            "ignoreCase": True}}},
    "sharedFields": {"execution_mode": {"type": "string", "doc": _doc("Modus.", "Mode."), "legacy": "claude",
                                        "default": "ai",
                                        "values": [{"value": "ai", "doc": _doc("KI.", "AI.")},
                                                   {"value": "shell", "doc": _doc("Shell.", "Shell.")}]},
                     "wrapper_mounts": {"type": "array", "items": {"type": "string"}, "doc": _doc("Mounts a|b.", "Mounts a|b."),
                                        "onlyWith": {"execution_mode": "shell"}}},
    "blockTypes": {
        "simple": {"required": ["command"], "fields": {
            "command": {"type": "string", "doc": _doc("Der Prompt.", "The prompt.")},
            "timeout_seconds": {"type": "integer", "doc": _doc("Timeout.", "Timeout."), "minimum": 1}}},
        "foreach": {"requiredOneOf": ["command", "workflow_id"], "fields": {
            "command": {"type": "string", "doc": _doc("Befehl.", "Command.")},
            "workflow_id": {"type": "string", "doc": _doc("Workflow.", "Workflow.")},
            "execution_mode": "#execution_mode",
            "mounts": "#wrapper_mounts"}},
        "compress": {"required": [], "fields": {}},
    },
    "compressOps": [{"op": "text.normalize", "doc": _doc("Normalisieren.", "Normalise.")}],
}


class GeneratedReferenceTest(unittest.TestCase):
    """scripts/docs-reference.py generates the API and block reference from the code (TODO 1ab3ad21).

    Small fixtures instead of the real repo: one controller, one ErrorCode, an excerpt of the schema.
    The templates contain no links, so the docs gate sees only the generated part on the fixture.
    """

    def build(self, tmp) -> Path:
        root = Path(tmp)
        files = {
            ref.CONTROLLER_DIR + "/FooController.java": FIXTURE_CONTROLLER,
            ref.ERROR_CODE: FIXTURE_ERROR_CODE,
            ref.MODULE_CATALOG: FIXTURE_MODULES,
            ref.BLOCK_SCHEMA: json.dumps(FIXTURE_SCHEMA, ensure_ascii=False),
            "scripts/docs-reference/api-referenz.de.md": "# API-Referenz\n\nEinleitung DE.\n",
            "scripts/docs-reference/api-referenz.en.md": "# API Reference\n\nIntro EN.\n",
            "scripts/docs-reference/block-referenz.de.md": "# Block-Referenz\n",
            "scripts/docs-reference/block-referenz.en.md": "# Block Reference\n",
        }
        for rel, text in files.items():
            (root / rel).parent.mkdir(parents=True, exist_ok=True)
            (root / rel).write_text(text, encoding="utf-8")
        (root / "docs" / "en").mkdir(parents=True)
        return root

    def run_reference(self, root: Path, mode: str):
        proc = subprocess.run([sys.executable, str(REFERENCE), mode, "--root", str(root)],
                              capture_output=True, text=True, check=False)
        return proc.returncode, proc.stdout, proc.stderr

    def test_api_page_lists_endpoints_with_purpose_and_module(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp)
            code, out, err = self.run_reference(root, "--write")
            self.assertEqual(code, 0, err)
            page = (root / "docs" / "api-referenz.md").read_text(encoding="utf-8")
            self.assertTrue(page.startswith(ref.MARKER + "\n\n# API-Referenz\n\nEinleitung DE.\n"), page)
            # First sentence of the Javadoc; TODO tag gone, "z. B." ends no sentence, {@code} with braces.
            self.assertIn("| `GET /api/v1/foos/{id}` | Liefert ein Foo, z. B. das unter `/{id}/bar` verlinkte."
                          " | `chains` |", page)
            # Method annotation beats class annotation; without Javadoc the purpose stays empty.
            self.assertIn("| `POST /api/v1/foos` |  | `webhooks` |", page)
            # produces is not a path (as in api-endpoint-diff.sh).
            self.assertIn("| `GET /api/v1/foos/{id}/text` |  | `chains` |", page)
            self.assertNotIn("text/markdown", page)
            self.assertIn("## /api/v1/foos\n\nQuelle: `FooController` – Foos verwalten: anlegen und lesen.\n", page)
            # Unknown HttpStatus constant: name instead of a guessed number.
            self.assertIn("| `RESOURCE_NOT_FOUND` | 404 | Resource not found |", page)
            self.assertIn("| `TEAPOT` | I_AM_A_TEAPOT | Short and stout |", page)
            en = (root / "docs" / "en" / "api-referenz.md").read_text(encoding="utf-8")
            self.assertIn("| `POST /api/v1/foos` |  | `webhooks` |", en)
            self.assertIn("Source: `FooController`", en)
            self.assertIn("## Error codes", en)

    def test_api_page_lists_the_rest_of_the_javadoc_as_notes(self):
        # What follows the first sentence does not belong in the table, but is not lost either:
        # a list below the table, without block tags, origin tags and inline tags; endpoints without
        # further text are missing from the list.
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp)
            code, _, err = self.run_reference(root, "--write")
            self.assertEqual(code, 0, err)
            notes = ("- `GET /api/v1/foos/{id}` — Dieser Satz gehoert nicht mehr dazu.\n"
                     "- `PUT /api/v1/foos/{id}` — Antwortet mit `409`, solange es benutzt wird."
                     " Siehe `FooService#replace`.\n")
            page = (root / "docs" / "api-referenz.md").read_text(encoding="utf-8")
            self.assertIn("| `PUT /api/v1/foos/{id}` | Ersetzt ein Foo. | `chains` |\n"
                          "| `GET /api/v1/foos/{id}/text` |  | `chains` |\n\nHinweise:\n\n" + notes, page)
            self.assertNotIn("@param", page)
            self.assertNotIn("das Foo", page)
            en = (root / "docs" / "en" / "api-referenz.md").read_text(encoding="utf-8")
            self.assertIn("\n\nNotes:\n\n" + notes, en)
            self.assertNotIn("Hinweise", en)

    def test_api_page_satisfies_the_endpoint_diff(self):
        # api-endpoint-diff.sh looks for "METHOD /api/v1/..." in both versions — the generated page must match.
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp)
            self.run_reference(root, "--write")
            proc = subprocess.run(
                ["bash", str(REPO / "scripts" / "public-readiness" / "api-endpoint-diff.sh"),
                 str(root / ref.CONTROLLER_DIR), str(root / "docs" / "api-referenz.md"),
                 str(root / "docs" / "en" / "api-referenz.md")],
                capture_output=True, text=True, check=False)
            self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)

    def test_block_page_renders_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp)
            self.run_reference(root, "--write")
            page = (root / "docs" / "block-referenz.md").read_text(encoding="utf-8")
            self.assertIn("| `on_failure` | string |  | `stop` | `stop`: Stoppen. · `continue`: Weiter. | Bei Fehler. |",
                          page)
            self.assertIn("| `effort` | string |  |  | `low`: Wenig. · Groß-/Kleinschreibung egal | Aufwand. |", page)
            self.assertIn("## `simple`\n\nDazu die [gemeinsamen Felder](#gemeinsame-felder) und die"
                          " [KI-Felder](#ki-felder).", page)
            self.assertIn("| `command` | string | ja |  |  | Der Prompt. |", page)
            self.assertIn("| `timeout_seconds` | integer |  |  | ≥ 1 | Timeout. |", page)
            # requiredOneOf, resolved #-reference with its legacy value, onlyWith, escaped bar.
            self.assertIn("| `command` | string | einer von `command`, `workflow_id` |  |  | Befehl. |", page)
            self.assertIn("| `execution_mode` | string |  | `ai` | `ai`: KI. · `shell`: Shell. ·"
                          " alt, noch angenommen: `claude` | Modus. |", page)
            self.assertIn("| `mounts` | string[] |  |  |  | Mounts a\\|b. Gelesen nur mit `execution_mode: shell`. |",
                          page)
            self.assertIn("## `compress`\n\nDazu die [gemeinsamen Felder](#gemeinsame-felder).\n\n"
                          "Keine eigenen Schlüssel.\n\n### Stufen", page)
            self.assertIn("| `text.normalize` | Normalisieren. |", page)
            en = (root / "docs" / "en" / "block-referenz.md").read_text(encoding="utf-8")
            self.assertIn("| `command` | string | yes |  |  | The prompt. |", en)
            self.assertIn("### Stages", en)

    def test_check_names_a_stale_page(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp)
            self.run_reference(root, "--write")
            code, out, _ = self.run_reference(root, "--check")
            self.assertEqual(code, 0, out)
            page = root / "docs" / "en" / "block-referenz.md"
            page.write_text(page.read_text(encoding="utf-8") + "von Hand\n", encoding="utf-8")
            code, out, _ = self.run_reference(root, "--check")
            self.assertEqual(code, 1, out)
            self.assertEqual(out.strip(), "veraltet: docs/en/block-referenz.md")
            self.assertIn("von Hand", page.read_text(encoding="utf-8"), "--check darf nichts schreiben")

    def test_check_sees_a_new_endpoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp)
            self.run_reference(root, "--write")
            controller = root / ref.CONTROLLER_DIR / "FooController.java"
            controller.write_text(controller.read_text(encoding="utf-8").replace(
                "    @PostMapping\n", "    @DeleteMapping(\"/{id}\")\n    public void drop() {}\n\n    @PostMapping\n"),
                encoding="utf-8")
            code, out, _ = self.run_reference(root, "--check")
            self.assertEqual(code, 1, out)
            self.assertIn("veraltet: docs/api-referenz.md", out)
            self.assertIn("veraltet: docs/en/api-referenz.md", out)

    def test_parity_gate_reports_a_stale_page(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp)
            self.run_reference(root, "--write")
            code, out = run_script(root)
            self.assertEqual(code, 0, out)
            page = root / "docs" / "api-referenz.md"
            page.write_text(page.read_text(encoding="utf-8").replace("Einleitung DE.", "Von Hand."), encoding="utf-8")
            code, out = run_script(root)
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: veraltet-erzeugt docs/api-referenz.md (python3 scripts/docs-reference.py --write)",
                          out)

    def test_missing_source_of_an_existing_page_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp)
            self.run_reference(root, "--write")
            (root / ref.BLOCK_SCHEMA).unlink()
            code, _, err = self.run_reference(root, "--check")
            self.assertEqual(code, 2, err)
            self.assertIn("docs/block-referenz.md: Quelle fehlt", err)
            code, out = run_script(root)
            self.assertIn("BEFUND: referenz-fehler docs/block-referenz.md: Quelle fehlt", out)

    def test_root_without_sources_and_pages_is_skipped(self):
        # The fixtures of the other tests here rebuild no backend — the generator checks nothing there.
        with tempfile.TemporaryDirectory() as tmp:
            code, out, err = self.run_reference(Path(tmp), "--check")
            self.assertEqual(code, 0, out + err)

    def test_templates_are_not_link_checked(self):
        # A template's links hold at the target under docs/, not under scripts/docs-reference/.
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp)
            template = root / "scripts" / "docs-reference" / "api-referenz.de.md"
            template.write_text("# API-Referenz\n\n[Entwicklung](entwicklung.md)\n", encoding="utf-8")
            (root / "docs" / "entwicklung.md").write_text("# E\n", encoding="utf-8")
            (root / "docs" / "en" / "entwicklung.md").write_text("# E\n", encoding="utf-8")
            self.run_reference(root, "--write")
            code, out = run_script(root)
            self.assertEqual(code, 0, out)


class FirstSentenceTest(unittest.TestCase):
    def sentence(self, *lines):
        return ref.first_sentence(list(lines))

    def test_stops_at_the_first_period_followed_by_space(self):
        self.assertEqual(self.sentence("Erster Satz. Zweiter Satz."), "Erster Satz.")

    def test_abbreviations_do_not_end_the_sentence(self):
        self.assertEqual(self.sentence("Liest z. B. Dateien, d. h. alles. Rest."), "Liest z. B. Dateien, d. h. alles.")
        self.assertEqual(self.sentence("Uses e.g. files. Rest."), "Uses e.g. files.")

    def test_inline_tags_and_html(self):
        self.assertEqual(self.sentence("Ruft {@link Foo#bar(String) bar} und {@code /{id}/x} <b>fett</b>."),
                         "Ruft bar und `/{id}/x` fett.")
        self.assertEqual(self.sentence("Nutzt {@link WorkflowController}."), "Nutzt `WorkflowController`.")

    def test_origin_references_are_dropped(self):
        self.assertEqual(self.sentence("Zaehlt Runs (TODO 86777fb4) und mehr (c2cda5ed)."), "Zaehlt Runs und mehr.")
        self.assertEqual(self.sentence("Ziel und Rang – TODO dc0566f8."), "Ziel und Rang.")

    def test_block_tags_and_paragraphs_end_the_description(self):
        self.assertEqual(self.sentence("Ohne Punkt", "@param x egal"), "Ohne Punkt")
        self.assertEqual(self.sentence("Kopf ohne Punkt <p>Absatz."), "Kopf ohne Punkt")


class DescribeTest(unittest.TestCase):
    """Rest of the description after the first sentence — source of the notes below an API table."""

    def describe(self, *lines):
        return ref.describe(list(lines))

    def test_rest_follows_the_first_sentence(self):
        self.assertEqual(self.describe("Erster Satz. Zweiter, z. B. mit", "Umbruch. Dritter."),
                         ("Erster Satz.", "Zweiter, z. B. mit Umbruch. Dritter."))

    def test_no_rest(self):
        self.assertEqual(self.describe("Nur ein Satz.", "", "@return x"), ("Nur ein Satz.", ""))

    def test_paragraphs_join_with_a_space(self):
        self.assertEqual(self.describe("Kopf. Mitte.<p>Absatz eins.</p>", "<p>Absatz zwei."),
                         ("Kopf.", "Mitte. Absatz eins. Absatz zwei."))
        self.assertEqual(self.describe("Kopf ohne Punkt <p>Absatz."), ("Kopf ohne Punkt", "Absatz."))

    def test_block_tags_end_the_rest(self):
        self.assertEqual(self.describe("Kopf. Regel.", "@param x egal", "@throws Foo nie"), ("Kopf.", "Regel."))

    def test_inline_tags_and_origin_references_in_the_rest(self):
        self.assertEqual(self.describe("Kopf. Mit {@code 409} (TODO 1a2b3c4d), siehe {@link Foo#bar}",
                                       "und {@link Foo#baz baz} &lt;x&gt;."),
                         ("Kopf.", "Mit `409`, siehe `Foo#bar` und baz <x>."))


class RepoTest(unittest.TestCase):
    def test_this_repository_is_green(self):
        code, out = run_script(REPO)
        self.assertEqual(code, 0, out)
        self.assertIn("gate_status: GREEN", out)


if __name__ == "__main__":
    unittest.main()
