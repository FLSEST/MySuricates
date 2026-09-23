<!-- last-sync: 2026-09-23 from: docs/konzepte/persistenz-herkunft.md -->

<!-- concept-facts-sha256: 47f72cd8883f6032a246606f7af297a20af1c83a1a13ed6078d019d53951318a -->
<!-- concept-source: d7e7e28cf7209b4369ad33467bb4fe781a178baa -->

# Persistence and provenance

```mermaid
flowchart LR
  A["Definition change"] --> B["Version and hash"]
  B --> C["Run with trigger"]
  C --> D["Steps and results"]
  D --> E["Reconstruct"]
```

## What is it useful for?

For a workflow change, the platform stores a version snapshot, a sequential version number, a change source, a message, and a definition hash. Before a run, the same workflow goes through admission: if provenance tracking is active, the executable definition is canonically hashed and, where possible, assigned to the matching stored version. The run can therefore later be compared with the definition known at the time and its trigger type. This is useful, for example, when unattended automations must be explainable after the fact: you can see not only that a run took place, but which definition was executed and whether it matches a versioned change.

## Benefits

The mechanism separates readable metadata from the executable definition: name, description, or tags do not change the hash, while block order and relevant execution parameters do. Sorted, canonical JSON generation makes identical definitions stably comparable. For sealed workflows, the hash is always checked; if it does not match, the run is denied. Optionally, it can also be required that unattended runs only start sealed workflows. This does not create complete audit proof, but it does provide a practical reconstruction trail for changes, runs, and security decisions.

## Drawbacks and costs

Provenance can be disabled; in that case, the hash and version reference remain empty. Legacy data may be missing. The definition hash does not prove the origin of an imported file and is not a snapshot of the environment, input data, or external services. Retention limits the history, while soft deletion and changed metadata make time-based comparisons harder. Currently, 1235 runs are retained in the dataset; this is not a lifetime value and is not limited to 30 days. 856 of 1253 AI TODOs have a non-empty session ID, but that does not prove resumable transcripts. From this inventory data, there are no reliable statements about usefulness, error rate, or test coverage.

## Operational evidence

Measurement: **2026-09-19 (UTC)**, a local development instance. Inventory counters refer to the datasets visible via REST; step counts count stored steps with type metadata in the 30-day window. Wrappers are counted once. This is not evidence of effectiveness.

| Metric | Count / reference size |
|---|---:|
| Stored runs (all periods) | 1235 |
| AI TODOs with filled session ID | 856 / 1253 |

[Definitions, measurement limits, and raw counters](../../konzepte/betriebsdaten.json) · [Measurement method and repetition](README.md)

## In the code

- [WorkflowVersionService.java](../../../ai-automation-backend/src/main/java/com/automation/service/workflow/WorkflowVersionService.java)
- [WorkflowDefinitionHasher.java](../../../ai-automation-backend/src/main/java/com/automation/service/workflow/WorkflowDefinitionHasher.java)
- [RunAdmission.java](../../../ai-automation-backend/src/main/java/com/automation/service/workflow/RunAdmission.java)
