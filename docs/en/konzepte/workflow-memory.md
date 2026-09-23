<!-- last-sync: 2026-09-23 from: docs/konzepte/workflow-memory.md -->

<!-- concept-facts-sha256: 47f72cd8883f6032a246606f7af297a20af1c83a1a13ed6078d019d53951318a -->
<!-- concept-source: d7e7e28cf7209b4369ad33467bb4fe781a178baa -->

# Workflow Memory

```mermaid
flowchart LR
  A["Past runs"] --> B["Extract knowledge"]
  B --> C["Memory version"]
  C --> D["Active memory"]
  D --> E["Context of the next run"]
```

## What is it useful for?

Workflow memory stores condensed experience from earlier workflow runs as a versioned memory per workflow. During extraction, selected runs, or, without a selection, up to the ten newest runs, are read, their step results are preprocessed, and an AI summarizer summarizes them into a title, content, and tags. The new memory is initially inactive; when extraction runs automatically after successful runs, it is activated directly and replaces the previously active version. Before a later run, the platform writes exactly the active memory to the project as a context file so that the started AI CLI calls can read it. A useful application is a recurring maintenance workflow that learns from past fixes which project conventions, check steps, or typical pitfalls need to be considered.

## Benefits

The mechanism makes implicit run knowledge reusable without permanently maintaining every workflow manually in the prompt. Since only one active version applies, it remains controllable which summary reaches subsequent calls; older versions can remain without automatically taking part. Reading the runs, AI summarization, and saving are deliberately separated into short database phases and an external AI call so that a slow summarizer does not unnecessarily block database connections. If no active memory exists, an old context file is removed; this prevents outdated content from being intentionally passed on.

## Drawbacks and costs

The memory is an AI summary, not verified truth. Errors, omissions, or outdated assumptions can propagate into later runs, especially when automatic activation is used. Each extraction and each time it is sent along increases effort, latency, and prompt size; local storage also does not mean that the AI calls involved remain local. Operationally, 6 active memory entries in the instance and 263 visible workflow definitions in active projects are currently recorded. This describes only inventory and configuration, not usefulness. Data on causal success, error rate, retention comparability, or domain-specific verification coverage is missing.

## Operational evidence

Measurement: **2026-09-19 (UTC)**, a local development instance. Inventory counters refer to the records visible via REST; step counts count stored steps with type metadata in the 30-day window. Wrappers are counted once. This is not evidence of effectiveness.

| Metric | Count / reference value |
|---|---:|
| Active memory entries | 6 |
| Visible workflows in active projects | 263 |

[Definitions, measurement limits, and raw counters](../../konzepte/betriebsdaten.json) · [Measurement method and repetition](README.md)

## In the code

- [WorkflowMemoryService.java](../../../ai-automation-backend/src/main/java/com/automation/service/memory/WorkflowMemoryService.java)
- [ExecutionContextFactory.java](../../../ai-automation-backend/src/main/java/com/automation/service/workflow/ExecutionContextFactory.java)
