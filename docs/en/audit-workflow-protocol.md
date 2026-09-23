<!-- last-sync: 2026-09-12 from: docs/audit-workflow-protocol.md -->

# Audit Workflow Protocol

The Audit Center does not perform any domain-specific checks itself. Rules, data collection,
evaluation, and report generation remain entirely within normal workflows. The platform
only provides an opt-in protocol for versioned reports, stable findings, and TODO links.

## Activating a Workflow

```json
{
  "metadata": {
    "audit": {
      "enabled": true,
      "key": "project.security",
      "category": "security",
      "reportBlock": "report",
      "schemaVersion": 1
    }
  }
}
```

The workflow block named in `reportBlock` must have the same `name`. Audit keys and
categories are freely selectable; the backend does not know any project-specific keys or rules.
An audit workflow does not use `metadata.autoTodo` at the same time: TODOs are managed
from the structured findings.

## Result Format v1

The report block outputs normal Markdown and closes with exactly one hidden
JSON envelope:

```text
<!-- mysuricates:audit-result:v1
{"snapshotComplete":true,"findings":[
  {
    "ruleKey":"architecture.god-class",
    "subject":"src/main/java/example/WorkflowExecutor.java",
    "severity":"HIGH",
    "title":"Klasse hat zu viele Verantwortlichkeiten",
    "description":"Konkrete Bewertung und empfohlene Behebung",
    "evidence":"WorkflowExecutor.java:120-510",
    "tags":["backend","architecture"]
  }
]}
-->
```

`findings` is always the complete current snapshot, not just the delta. `ruleKey` and
`subject` form the stable identity of a finding and must remain the same between runs.
Title and description may evolve. An empty snapshot is output as
`"findings": []`.

A prompt that leaves the `subject` to the model gets drift: between two runs `docs/deployment.md`
became `host-reaching-endpoints`, and the finding came back as a repeat finding without its
disposition. A prompt therefore fixes the `subject` per `ruleKey` — a fixed value for rules that
concern the repository or a module as a whole (files and numbers then go into
`description`/`evidence`), otherwise the path relative to the repository root, one finding per
file. The model to follow is the ruleKey list in the "Public Readiness" evidence prompt
(`subject "<value>"` or `subject = Pfad` per entry); `AuditRuleKeyCatalogTest` makes sure no entry
there leaves it out (TODO `3a9e275b` — evidence prompt: fix the subject per ruleKey).

### Optional: `expectedFindings`

An audit whose measurement block knows the target count (such as `ERWARTETE FINDINGS` in the test
coverage audit) may write it into the envelope as `"expectedFindings": <N>` next to
`snapshotComplete`. If it does not match the number of findings delivered, the **entire report is
discarded**: it is recorded as INVALID, no findings are created, and — crucially — none are marked
RESOLVED.

That is the point: a truncated envelope with `snapshotComplete: true` used to resolve every finding
it left out — exactly the failure the snapshot mode is meant to prevent. The workflow's gate block
does report it as RED, but ingestion carried on regardless; a report without a consequence is not a
safeguard. If the field is absent, behaviour is unchanged (TODO `e39379a9`).

### ruleKey catalogue

When a prompt fixes the `ruleKey`s its model must use, they are listed in
`ai-automation-backend/src/main/resources/audit/rule-keys.json` under their `auditKey`. Ingestion
then normalizes spelling drift (lower case, `_` → `-`, plural before `-count`:
`god-classes-count` → `god-class-count`). A key that still does not match is ingested anyway — with
**discarded — and the whole report with it** (TODO `f57945e2`): it is recorded as INVALID, no
findings are created, and none are marked RESOLVED. A line in the activity log names the unknown
keys and the permitted ones. An `auditKey` without a catalogue entry is not checked; its prompt
assigns keys freely.

Why so strict: an envelope whose keys have drifted uniformly carries the *right number* of findings
and therefore passes the counting gate block. Ingesting it anyway creates a set of fresh identities
and resolves the entire previous generation — you get just as many repeat findings and start over on
the real ones. The earlier mark on the finding (`unknownRuleKey`, V75) was the intermediate step
while there was no harder consequence; it is no longer set.

### Evidence check: `evidence` must name a measurement of this run

The prompt requires a supporting line for every finding — for a long time nobody verified it, and a
finding with an invented or recycled reference became a todo unchanged (TODO `a94b56a0`). Since V77
`EvidenceVerification` holds every `evidence` against the run's fact sheet at ingestion time: it
collects the names of all `FAKT <name>:` and `TREFFER <name>:` lines, and anything naming none of
them is marked `unverifiedEvidence`.

Such a finding is **stored and stays visible** — nothing is discarded, so a false alarm of the check
cannot swallow a real result. But it no longer creates a managed todo, not even at `severity: HIGH`,
and a line in the activity log names it. Like `unknownRuleKey` the mark is set afresh on every
ingestion, in both directions.

The limit: what is checked is the **origin** of the reference, not whether it carries the
conclusion. Quoting a real FAKT line and inferring something about an entirely different place still
passes. A run without a fact sheet is not checked — no fact sheet is no suspicion.

## Fact Sheet Pattern: the shell measures, the AI interprets

An audit workflow does **not** let the AI count for itself. A shell block in front of the
report block measures deterministically and emits a fact sheet:

```text
## FAKTENBLATT
FAKT files_over_300: 19
FAKT backend_tests_failures: 0
NICHT GEMESSEN: step_results_bytes (shell blocks must not read the SQLite database)
TREFFER repository_in_loop: mapper/WishMapper.java:36 workflowRepository.find (loop from line 34)

## PFLICHTFELDER
PFLICHT-FAKTEN: database_bytes source_files_scanned transactional_slow_call_count
```

Rules:

- `FAKT <name>: <value>` is a measurement, `NICHT GEMESSEN: <name> (<reason>)` a deliberate
  gap, `TREFFER <name>: <file>:<line> …` a candidate with its location. An error path in the
  measuring block always produces a NICHT GEMESSEN line — never a silent zero: "no cycles
  found" and "cycle detection failed" must not look the same.
- The report prompt carries the measuring rule "evaluate the fact sheet only; do not measure
  or count anything yourself", names the responsible FAKT lines per dimension, and requires
  every number in the report to cite its FAKT line. Raw logs are included only truncated
  (last 80 lines per measuring block) — to classify a failure, not as counting material.
- The gate block deterministically checks that the mandatory FAKT lines are present and
  otherwise sets `gate_status: RED` with `gate_category: audit-invalid`. Without that check
  an AI report could be green although the measuring block delivered nothing.
- Fact sheet blocks read block outputs only as `set -- {{ block.<name>.output }}` and set
  `"shell_escape_variables": true` (hygiene gate `TemplateHygieneGateTest`). Simple values
  without line breaks — `BASE={{ project_path }}`, `BASE={{ execution_path }}` — are written
  **unquoted** as well: with `shell_escape_variables` the resolver adds the single quotes
  itself, so your own quotes (`BASE="{{ project_path }}"`) would end up inside the value.
  They neither call the REST API nor open the SQLite database.

Converted audits in `workflow-pool/`:

| Workflow | Measurement | Self-test |
|---|---|---|
| Architecture review | block `arch_metrics` → `scripts/public-readiness/arch-metrics.sh` (jdeps, lines, churn) | `scripts/tests/test-arch-metrics.sh` |
| Architecture review (frontend) | block `frontend_metrics` → `scripts/public-readiness/frontend-metrics.sh` (source imports, lines, module cycles) | `scripts/tests/test-frontend-metrics.sh` |
| Health check | block `facts` (Surefire totals, exit codes, lint/tsc, bundle, code and docs sizes) | `scripts/tests/test-audit-facts.sh` |
| Performance audit | block `facts` (actuator, DB and bundle files, log lines, source patterns) | `scripts/tests/test-audit-facts.sh` |
| Test coverage | block `metrics` with the target size `ERWARTETE FINDINGS`, gate counts the envelope against it | `scripts/tests/test-coverage-snapshot.sh` |
| Documentation weight check | block `dossier` → `scripts/public-readiness/docs_orphan_scan.py` (reachability, references, maintenance, topic overlap) | `scripts/tests/test_docs_orphan_scan.py` |

### When `expectedFindings` does **not** fit

The documentation weight check deliberately omits it, and the reason applies to any audit whose
findings are a judgement rather than a measurement. For test coverage the target number *is* a
measurement — every untested class must be reported, leaving one out is an error. The weight check
only measures *candidates*; which of them is a finding is decided by the report. If the gate counted
the candidates against the envelope, the AI would have to report every one and could never say "this
document belongs in the repository as it is" — the division of roles would be gone.

Instead the gate there checks that the **mandatory FAKT lines are present** (`PFLICHT-FAKTEN:` in the
fact sheet). That catches what `expectedFindings` protects against — a snapshot built on a failed
measurement — without forcing the judgement. The report prompt additionally carries the rule to
report an unchanged finding from the previous run again: otherwise a mere change of mind would close
it as RESOLVED.

## Lifecycle

- Failed runs, missing report blocks, and invalid envelopes are historized as
  `INVALID`. They do not change any findings.
- A valid complete snapshot updates existing findings and creates new ones.
- A previously open finding that is absent from the next valid snapshot becomes `RESOLVED`.
- A re-appearing `RESOLVED` finding is reopened as a regression.
- `CRITICAL` and `HIGH` automatically generate managed TODOs. `MEDIUM` can be confirmed in the Audit
  Center; `LOW` remains a finding for the time being.
- A managed TODO set to `DONE` moves the finding to
  `AWAITING_VERIFICATION`. Only the next valid audit snapshot confirms the fix.
- Manually linked TODOs are never automatically changed in status.
