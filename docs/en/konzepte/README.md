<!-- last-sync: 2026-09-23 from: docs/konzepte/README.md -->

# Concepts to adopt

These pages explain mechanisms of MySuricates: when they help, what they
cost, and where their limits are. Each page combines a flow diagram with code
evidence.

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

## Regenerate pages

Prerequisites are this repository, Python 3, a running MySuricates instance, and a
configured AI provider. The supplied workflow uses PI with the model from the
existing docs sync path. The provider is set on the AI block so that it is
preserved even after installation from the pool.

1. Install and manually start the [concept pages workflow](../../../workflow-pool/mysuricates-concept-pages.json).
   Set `concept_root` to the absolute path of the desired checkout.
   `concept_output` determines the output base directory, by default
   `/tmp/mysuricates-concept-pages`.

   The flow is `shell → foreach → shell → shell`: assemble evidence packets from the
   catalog and the source code, exactly one AI call per mechanism, add fixed diagrams
   and source links, then check deterministically at the end. Each run has its own
   output directory.

   `concept_only` limits the run to single pages: a comma-separated list of slugs
   from the catalog, for example `zusammenstoepseln,anbieter-unabhaengig`. Empty
   (the default) means all pages. The loop takes its entries from `pages.py list`;
   an unknown slug stops the run in the first step.

2. Read the generated pages under `<concept_output>/<run-id>/de/` for subject-matter
   correctness. The gate checks completeness, sections, source links,
   source commit, unchanged diagrams, and obvious private markers.
   It does not replace a content review and does not guarantee complete
   anonymization. Only after a successful check, copy the pages to
   `docs/konzepte/`. If a page has no row in the overview above yet, a `README.md`
   with the additional rows (label and guiding question from the catalog) lies next
   to the pages; existing rows stay unchanged. Copy it along.

   With `concept_only` the gate checks only the selected pages; the other pages stay
   as they are.

3. Start the [limited variant of the Docs DE→EN sync](../../../workflow-pool/mysuricates-concept-pages-en.json).
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
python3 scripts/concepts/pages.py check --root . --work docs/konzepte
python3 scripts/concepts/pages.py check --root . --work docs/en/konzepte --language en
# single pages only:
python3 scripts/concepts/pages.py check --root . --work docs/konzepte --only gates,haertung
```

The [catalog](../../../scripts/concepts/catalog.json) is the shared source for
topics, code evidence, both language versions of the Mermaid diagrams, and the
rows of the overview. The comment on each page contains the checked source commit.
