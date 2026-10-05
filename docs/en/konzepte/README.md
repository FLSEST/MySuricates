<!-- last-sync: 2026-09-23 from: docs/konzepte/README.md -->

# Concepts to adopt

These pages explain mechanisms of MySuricates: when they help, what they
cost, and where their limits are. Each page combines a flow diagram with code
evidence and a dated operational measurement. The numbers describe a single
development installation; they do not prove any general benefit.

Four of them – gates, self-improvement, workflow memory and agentic budgets – were built to investigate
one research question each. Built means: they run. It does not mean the question has been answered.

| Mechanism | Guiding question |
|---|---|
| [Gates](gates.md) | How does output become a machine-readable judgment? |
| [Self-improvement](selbstverbesserung.md) | How do findings become limited correction tasks? |
| [Persistence and provenance](persistenz-herkunft.md) | Which definition was executed for which trigger? |
| [Agentic budgets](agentische-budgets.md) | How can an open-ended AI task be bounded? |
| [Access paths](zugaenge.md) | How do UI, plugin, schedule, and event lead to the same flow? |
| [Measure before evaluating](messen-vor-bewerten.md) | Which work is done by measurement tools, and which by AI? |
| [Workflow memory](workflow-memory.md) | How does condensed run knowledge reach later calls? |
| [Hardening](haertung.md) | What do seals, admission checks, and taint actually protect? |

## Measurement method and limits

The [facts snapshot](../../konzepte/betriebsdaten.json) was collected on **19.09.2026 (UTC)** via
the REST API. It contains only selected counters and their definitions. Names,
task descriptions, outputs, instance IDs, private paths, and credentials are not
included. The queries are performed one after another; ongoing work can change the
inventory during collection.

- `/workflows` returns non-deleted workflows **in active projects**. The 263
  visible definitions are therefore not a total count including archived projects.
  A `runCount` of zero means **no stored run**; after retention, this does not
  allow concluding that a workflow never ran.
- `/todos` is read across all pages. An empty `resolutionState` concerns the
  optional manual resolve path. It measures neither general verification coverage
  nor the success of automatic fixes. A populated `sessionId` does not guarantee
  that a transcript still exists and can be resumed.
- `/learning/dashboard?days=30` returns, among other things, **step counts for the
  last 30 days**. Received records with `blockType` metadata are counted;
  loop wrappers count once. A `foreach` can call AI and must not be counted
  categorically as deterministic work. By contrast, `totalRuns` from the same
  endpoint covers **all retained runs**, regardless of the window.
- `/learning/platform-stats` returns current configuration inventories. Active
  schedules or memory entries do not prove use or effectiveness. The six active
  memory entries are an installation-wide counter; dividing them by the workflows
  of active projects does not produce a reliable coverage rate.

The earlier numbers from planning tickets are not carried forward: retention,
archiving, and different reference bases prevent a direct time comparison.
Measure again before publication or regeneration.

## Regenerate pages

Prerequisites are this repository, Python 3, a running MySuricates instance, and a
configured AI provider. The supplied workflow uses PI with the model from the
existing docs sync path. The provider is set on the AI block so that it is
preserved even after installation from the pool.

1. In an operator session, provide the API key via `MYSURICATES_API_KEY` and
   collect the anonymous snapshot:

   ```bash
   python3 scripts/concepts/collect_facts.py --output docs/konzepte/betriebsdaten.json
   ```

   Use `--api-base` for a different instance. This script belongs **before** the
   workflow start: workflow blocks call neither their own REST API nor the DB.

2. Install and manually start the [concept pages workflow](../../../workflow-pool/mysuricates-concept-pages.json).
   Set `concept_root` to the absolute path of the desired checkout;
   set `concept_facts` to its snapshot file. `concept_output` determines the
   output base directory, by default `/tmp/mysuricates-concept-pages`.

   The flow is `shell → foreach → shell → shell`: check facts packages, exactly
   one AI call per mechanism, add fixed diagrams and measurement tables, then
   check deterministically at the end. The snapshot may be at most 24 hours old
   at startup. Each run has its own output directory.

   `concept_only` limits the run to single pages: a comma-separated list of slugs
   from the catalog, for example `zusammenstoepseln,anbieter-unabhaengig`. Empty
   (the default) means all pages. The loop takes its entries from `pages.py list`;
   an unknown slug stops the run in the first step.

3. Read the generated pages under `<concept_output>/<run-id>/de/` for subject-matter
   correctness. The gate checks completeness, sections, source links,
   measurements, snapshot hash, unchanged diagrams, and obvious private markers.
   It does not replace a content review and does not guarantee complete
   anonymization. Only after a successful check, copy the pages to
   `docs/konzepte/`. If a page has no row in the overview above yet, a `README.md`
   with the additional rows (label and guiding question from the catalog) lies next
   to the pages; existing rows stay unchanged. Copy it along.

   With `concept_only` the gate checks only the selected pages. The other pages keep
   the hash and measurements of their snapshot. If a new snapshot for the filtered
   run is copied to `docs/konzepte/`, the other pages no longer match it; the check
   without a filter reports this until all pages have been regenerated.

4. Start the [limited variant of the Docs DE→EN sync](../../../workflow-pool/mysuricates-concept-pages-en.json).
   `translation_root` denotes the same absolute checkout path. It uses the
   translation task from the existing delta sync for all pages plus the
   index. Each file is translated individually; afterward the script sets the
   English diagram labels and corrects the source links changed by the additional
   directory level. Target: `docs/en/konzepte/`. Existing translations in this
   directory are replaced. With the same `concept_only` as in the German run, only
   these pages are translated; the AI then leaves the index alone, and the script
   only adds the missing rows from the catalog there.

The workflow definitions depend on this repository and are therefore marked as
`scope: repo` in the pool. They do not create commits or pushes. The German
version remains the source; English corrections go through the same translation
path.

## Local check

```bash
python3 -m unittest discover -s scripts/tests -p 'test_concept_pages.py'
python3 scripts/concepts/pages.py check --root . --work docs/konzepte --facts docs/konzepte/betriebsdaten.json
python3 scripts/concepts/pages.py check --root . --work docs/en/konzepte --facts docs/konzepte/betriebsdaten.json --language en
# single pages only:
python3 scripts/concepts/pages.py check --root . --work docs/konzepte --facts docs/konzepte/betriebsdaten.json --only gates,haertung
```

The [catalog](../../../scripts/concepts/catalog.json) is the shared source for
topics, code evidence, both language versions of the Mermaid diagrams, and the
rows of the overview. The
comments on each page contain the checked source commit and the SHA-256 of the
facts snapshot.
