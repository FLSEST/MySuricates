"""Tests fuer scripts/public-readiness/docs_parity_check.py (TODO c58d7402).

Zwei der Faelle halten Fehler fest, die die Erstfassung des Skripts wirklich hatte und die
beide zu **Falschbefunden** gefuehrt haetten — der teuersten Sorte Fehler in einem Gate:

* `test_slug_keeps_each_space`   — Slug zog Leerzeichenfolgen zusammen, korrekte Anker
                                   galten als tot.
* `test_headings_skip_code_block` — `# Kommentar` in einem Shell-Beispiel zaehlte als
                                   Ueberschrift und erzeugte Phantom-Unterschiede.

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
        # `a` / `b` — c  ->  zwischen a und b stehen nach dem Entfernen von Backtick und
        # Schraegstrich zwei Leerzeichen, GitHub macht daraus zwei Bindestriche.
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
        # Eine Luecke, die in KNOWN_GAPS steht, meldet das Gate als bekannt und bleibt gruen.
        # KNOWN_GAPS ist seit TODO 47bd4d27 leer; geprueft wird der Mechanismus mit einem
        # Testeintrag — im selben Prozess, weil ein Unterprozess die Ersetzung nicht saehe.
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
        # README.md und docs/en/ sind der englische Pfad. Ein Verweis von dort auf eine
        # rein deutsche Seite ist ein Hinweis fuers Audit, das Gate bleibt gruen (TODO 1b84ecab).
        with tempfile.TemporaryDirectory() as tmp:
            # `plans/` ist kein gespiegelter Ordner (anders als konzepte/, siehe MIRRORED_SUBDIRS).
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
    """Unterordner wie docs/konzepte/ werden gespiegelt geprueft wie docs/ selbst (TODO b94beaed)."""

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
        # Messdaten (JSON) sind sprachneutral – nur Seiten (.md) zaehlen als "deutsch".
        with tempfile.TemporaryDirectory() as tmp:
            root = self.build(tmp, en_body="# K\n\n## One\n[Daten](../../konzepte/daten.json)\n")
            (root / "docs" / "konzepte" / "daten.json").write_text("{}\n")
            code, out = run_script(root)
            self.assertIn("en_links_to_german: 0", out)


class SectionPairingTest(unittest.TestCase):
    """Ein fehlender Abschnitt wird dem richtigen zugeschrieben, nicht dem letzten gleichrangigen.

    Die Erstfassung verglich nur die Ebenenfolge und meldete bei einem fehlenden mittleren
    "##"-Abschnitt immer den letzten -- so standen "## Bekannte Einschränkungen" (statt
    "## Diagramm-Seiten") und "## Completion Triggers (V39)" (statt des Audit Centers) als
    bekannte Luecken in KNOWN_SECTION_GAPS (TODO 2d553479).
    """

    def build(self, tmp, de_body, en_body, name="a.md"):
        root = Path(tmp)
        (root / "docs" / "en").mkdir(parents=True)
        (root / "docs" / name).write_text(de_body)
        (root / "docs" / "en" / name).write_text(en_body)
        return root

    def test_missing_middle_section_is_named_by_its_heading(self):
        # Nachbau von docs/architektur.md: Das Audit Center fehlte in der englischen Fassung.
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
        # Nachbau von docs/entwicklung.md: "## Diagramm-Seiten" fehlte, gemeldet wurde
        # "## Bekannte Einschränkungen". Die Ueberschriften teilen kein Wort -- der Abschnittstext
        # schon: Code, Pfade und Bezeichner stehen in beiden Sprachen gleich da.
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
        # Nachbau von docs/en/datenmodell.md: gleiche Zahl an Ueberschriften, aber `goals` stand
        # hinter den Enums statt bei den Tabellen. Frueher blieb das unsichtbar (gleiche Anzahl).
        de = "# D\n\n## Tabellen\n\n### `todos`\n\n### `goals`\n\n## Enums\n\n### RunStatus\n"
        en = "# D\n\n## Tables\n\n### `todos`\n\n## Enums\n\n### RunStatus\n\n### `goals`\n"
        with tempfile.TemporaryDirectory() as tmp:
            code, out = run_script(self.build(tmp, de, en))
            self.assertEqual(code, 1, out)
            self.assertIn("BEFUND: abschnitt-fehlt-en a.md '### `goals`' (Ueberschrift 4 von 6", out)
            self.assertIn("BEFUND: abschnitt-nur-en a.md '### `goals`' (Ueberschrift 6 von 6", out)

    def test_same_level_sequence_is_not_paired(self):
        # Gleiche Ebenenfolge: ob eine Ueberschrift uebersetzt oder ersetzt ist, sagt der Text
        # allein nicht sicher -- die Pruefung bleibt dort stumm, wie bisher.
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
        # Reine Prosa, ganz uebersetzte Ueberschriften, gleiche Gestalt: keine Aehnlichkeit, nur die
        # Ebenen. Dann bleibt es eine Vermutung, und sie faellt wie frueher auf den spaeteren Abschnitt.
        got = self.align("# D\n\n## Eins\nText.\n\n## Zwei\nTexte\n", "# D\n\n## One\nText.\n")
        self.assertEqual(got, (["## Zwei"], []))

    def test_shape_decides_between_prose_sections(self):
        # Gegenstueck: Nur die Gestalt (Laenge, Zeilen) unterscheidet die Abschnitte -- sie genuegt.
        # Ohne sie fiele die Vermutung auf "## Zwei" (TODO 3fbff015).
        de = ("# D\n\n## Eins\nEin langer Absatz, der den Mechanismus ausfuehrlich erklaert und dabei"
              " mehrere Saetze braucht, um alles zu sagen.\n\n## Zwei\nKurz.\n")
        en = "# D\n\n## One\nShort.\n"
        self.assertEqual(self.align(de, en), (["## Eins"], []))

    def test_concept_page_prose_sections(self):
        # Nachbau von docs/konzepte/*.md: feste Gliederung, reine Prosa, ganz uebersetzte
        # Ueberschriften. Fehlte "Vorteile", nannte die Pruefung "Nachteile und Kosten" -- der
        # Betriebsbeleg teilt mit ihm die Zahl `263` und zog ihn zu sich (TODO 3fbff015).
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
        # Nachbau von docs/api-referenz.md: zwei Endpunkte derselben Ressource mit gleichem Text.
        # Der Text passt voll auf beide; erst die Ueberschrift entscheidet (TODO 3fbff015).
        body = "Webhook schalten.\n\n**Response:** `WebhookTriggerResponse` (`tokenVisible: false`)\n\n"
        body_en = "Toggle the webhook.\n\n**Response:** `WebhookTriggerResponse` (`tokenVisible: false`)\n\n"
        de = ("# API\n\n### `POST /api/v1/webhooks/{id}/enable`\n" + body
              + "### `POST /api/v1/webhooks/{id}/disable`\n" + body)
        en = "# API\n\n### `POST /api/v1/webhooks/{id}/disable`\n" + body_en
        self.assertEqual(self.align(de, en), (["### `POST /api/v1/webhooks/{id}/enable`"], []))


class StockPairingTest(unittest.TestCase):
    """Die Luecken, die die Zuordnung am Bestand falsch benannte (TODO 3fbff015).

    Probe 2026-10-01: in jeder Datei mit gleicher Ebenenfolge jeden englischen Abschnitt einzeln
    entfernt -- die Fassung von TODO 2d553479 benannte 29 von 1213 Luecken falsch (dazu ein Fall,
    der waehrend der Arbeit kippte). Jeder Fall wird hier am echten Dateipaar nachgestellt. Faellt
    eine Ueberschrift spaeter weg oder weicht die Ebenenfolge ab, wird der Fall uebersprungen, nicht
    gruen gezaehlt.
    """

    CASES = (
        ("WORKFLOW-GUIDE.md", "### Welches Modell wann verwenden"),
        ("WORKFLOW-GUIDE.md", "### Provider-Regel: Codex ist read-only"),
        ("WORKFLOW-GUIDE.md", "### History-Komprimierung"),
        ("WORKFLOW-GUIDE.md", "#### Automatische Extraktion (Auto-Memory)"),
        ("architektur.md", "## WebSocket (Live-Logs + Status)"),
        ("belege.md", "## 2026-09-19 · Das Gate fand den Fehler des Agenten, der es erweiterte"),
        ("entwicklung.md", "## Abhängigkeits-Meldungen (Frontend)"),
        ("vision.md", "## Die Idee"),
        ("vision.md", "## Leitideen"),
        ("vision.md", "### Beruf"),
        ("workflow-memory-diagrams.md", "### workflow_memory"),
        ("konzepte/agentische-budgets.md", "## Wofür taugt das?"),
        ("konzepte/gates.md", "## Wofür taugt das?"),
        ("konzepte/haertung.md", "## Wofür taugt das?"),
        ("konzepte/haertung.md", "## Vorteile"),
        ("konzepte/haertung.md", "## Betriebsbeleg"),
        ("konzepte/messen-vor-bewerten.md", "## Wofür taugt das?"),
        ("konzepte/messen-vor-bewerten.md", "## Vorteile"),
        ("konzepte/persistenz-herkunft.md", "## Wofür taugt das?"),
        ("konzepte/persistenz-herkunft.md", "## Vorteile"),
        ("konzepte/selbstverbesserung.md", "## Wofür taugt das?"),
        ("konzepte/workflow-memory.md", "## Wofür taugt das?"),
        ("konzepte/workflow-memory.md", "## Vorteile"),
        ("konzepte/workflow-memory.md", "## Betriebsbeleg"),
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
sys.modules[_ref_spec.name] = ref  # dataclasses brauchen das Modul in sys.modules
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
    """scripts/docs-reference.py erzeugt API- und Block-Referenz aus dem Code (TODO 1ab3ad21).

    Kleine Fixtures statt des echten Repos: ein Controller, ein ErrorCode, ein Schema-Ausschnitt.
    Die Vorlagen enthalten keine Links, damit das Doku-Gate auf der Fixture nur das Erzeugte sieht.
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
            # Erster Satz des Javadoc; TODO-Kuerzel weg, "z. B." beendet keinen Satz, {@code} mit Klammern.
            self.assertIn("| `GET /api/v1/foos/{id}` | Liefert ein Foo, z. B. das unter `/{id}/bar` verlinkte."
                          " | `chains` |", page)
            # Methoden-Annotation schlaegt Klassen-Annotation; ohne Javadoc bleibt der Zweck leer.
            self.assertIn("| `POST /api/v1/foos` |  | `webhooks` |", page)
            # produces ist kein Pfad (wie in api-endpoint-diff.sh).
            self.assertIn("| `GET /api/v1/foos/{id}/text` |  | `chains` |", page)
            self.assertNotIn("text/markdown", page)
            self.assertIn("## /api/v1/foos\n\nQuelle: `FooController` – Foos verwalten: anlegen und lesen.\n", page)
            # Unbekannte HttpStatus-Konstante: Name statt geratener Zahl.
            self.assertIn("| `RESOURCE_NOT_FOUND` | 404 | Resource not found |", page)
            self.assertIn("| `TEAPOT` | I_AM_A_TEAPOT | Short and stout |", page)
            en = (root / "docs" / "en" / "api-referenz.md").read_text(encoding="utf-8")
            self.assertIn("| `POST /api/v1/foos` |  | `webhooks` |", en)
            self.assertIn("Source: `FooController`", en)
            self.assertIn("## Error codes", en)

    def test_api_page_satisfies_the_endpoint_diff(self):
        # api-endpoint-diff.sh sucht "METHODE /api/v1/..." in beiden Fassungen — die erzeugte Seite muss passen.
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
            # requiredOneOf, aufgeloester #-Verweis samt Altwert, onlyWith, maskierter Strich.
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
        # Die Fixtures der anderen Tests hier bauen kein Backend nach — dort prueft der Erzeuger nichts.
        with tempfile.TemporaryDirectory() as tmp:
            code, out, err = self.run_reference(Path(tmp), "--check")
            self.assertEqual(code, 0, out + err)

    def test_templates_are_not_link_checked(self):
        # Die Links einer Vorlage gelten am Ziel unter docs/, nicht unter scripts/docs-reference/.
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


class RepoTest(unittest.TestCase):
    def test_this_repository_is_green(self):
        code, out = run_script(REPO)
        self.assertEqual(code, 0, out)
        self.assertIn("gate_status: GREEN", out)


if __name__ == "__main__":
    unittest.main()
