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
