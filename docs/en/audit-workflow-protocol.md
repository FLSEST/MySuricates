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
- Fact sheet blocks read placeholders only as `set -- {{ block.<name>.output }}` and set
  `"shell_escape_variables": true` (hygiene gate `TemplateHygieneGateTest`). They neither
  call the REST API nor open the SQLite database.

Converted audits in `workflow-pool/`:

| Workflow | Measurement | Self-test |
|---|---|---|
| Architecture review | block `arch_metrics` → `scripts/public-readiness/arch-metrics.sh` (jdeps, lines, churn) | `scripts/tests/test-arch-metrics.sh` |
| Health check | block `facts` (Surefire totals, exit codes, lint/tsc, bundle, code and docs sizes) | `scripts/tests/test-audit-facts.sh` |
| Performance audit | block `facts` (actuator, DB and bundle files, log lines, source patterns) | `scripts/tests/test-audit-facts.sh` |
| Test coverage | block `metrics` with the target size `ERWARTETE FINDINGS`, gate counts the envelope against it | `scripts/tests/test-coverage-snapshot.sh` |

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
