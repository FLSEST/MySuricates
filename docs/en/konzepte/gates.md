<!-- last-sync: 2026-09-23 from: docs/konzepte/gates.md -->

<!-- concept-facts-sha256: 47f72cd8883f6032a246606f7af297a20af1c83a1a13ed6078d019d53951318a -->
<!-- concept-source: d7e7e28cf7209b4369ad33467bb4fe781a178baa -->

# Gates: a machine-readable verdict

```mermaid
flowchart LR
  A["Block output"] --> B["Read markers"]
  B --> C["Most severe verdict"]
  C --> D["GREEN / YELLOW / RED"]
  D --> E["Report and follow-up"]
```

## What is it useful for?

Gates are a machine-readable verdict at the end of a workflow run. Individual steps can write markers such as gate_status, gate_category, and gate_description in their output. On completion, all non-derived step outputs are searched; the most severe status found wins: GREEN, YELLOW, or RED. In addition, the found gates are stored in the run metadata as a summary with count and step position. If a valid marker is missing, the status can be derived from the technical run result: failed becomes RED, completed becomes GREEN, and completed with tolerated check failures becomes YELLOW. Paused or canceled runs do not produce a gate verdict.

## Benefits

The mechanism separates technical execution from domain-specific assessment. One useful application is a release or review workflow: tests, file checks, or content checks run as steps, write a gate when they find something, and downstream processes do not have to read free text, but only evaluate metadata. Because all gates are collected, it remains visible alongside the highest verdict whether there were multiple warnings or errors. The evaluation is deterministic enough for automation: permitted status values are limited, invalid markers are ignored, and copied derived outputs are not counted again.

## Drawbacks and costs

A gate does not prove quality; it only proves that a marker was recognized or a technical status was derived. Especially important: a COMPLETED run without a marker can become GREEN even though no domain-specific completeness check is documented. Data on causal benefit, error rate, and check coverage is missing. The operational finding only shows inventory and usage context: 1235 stored runs are not a lifetime value, and 144 of 223 typed step records from the last 30 days are shell blocks, not gate successes or domain-specific benefit.

## Operational evidence

Measurement: **2026-09-19 (UTC)**, one local development instance. Inventory counters refer to the records visible via REST; step counts count stored steps with type metadata in the 30-day window. Wrappers count once. This is not proof of effectiveness.

| Metric | Count / reference size |
|---|---:|
| Shell steps in the 30-day window | 144 / 223 |
| Stored runs (all periods) | 1235 |

[Definitions, measurement limits, and raw counters](../../konzepte/betriebsdaten.json) · [Measurement method and repetition](README.md)

## In the code

- [GateStatusResolver.java](../../../ai-automation-backend/src/main/java/com/automation/service/todo/GateStatusResolver.java)
- [GateStatusOutputParser.java](../../../ai-automation-backend/src/main/java/com/automation/service/runoutput/GateStatusOutputParser.java)
- [RunCompletionHandler.java](../../../ai-automation-backend/src/main/java/com/automation/service/workflow/RunCompletionHandler.java)
