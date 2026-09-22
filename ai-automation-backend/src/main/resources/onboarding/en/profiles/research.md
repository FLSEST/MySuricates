## Research workflows

- **Data:** `shell` loads and prepares (costs no tokens), `simple` interprets, `foreach` goes over several sources.
- **Research:** `agentic` with a goal, limited by its budget (default: 10 iterations, 200,000 tokens); `while` for pagination.
- **Pipelines:** collect → clean → analyze → report as a chain (only with the `chains` module).
- **Writing files:** with provider `CODEX` blocks run read-only and write attempts fail silently; for reports written to files use `CLAUDE` or `PI`.
- **Build on the last report:** `{{ previous_report }}` gives a run the longest output of the last successful run.

### Compare results

- Runs of a workflow: `GET $MS/executions?workflowId=<id>&page=0&size=10`.
- Output of a run: `GET $MS/executions/<runId>` → `steps[].output`.

### Workflow memory (only with the `memory` module)

- Read: `GET $MS/workflows/<id>/memory`.
- Learn from runs: `POST $MS/workflows/<id>/memory/extract` with `{}` (the last 10 runs) or `{"runIds": ["…"]}`.
- The result stays inactive until `PUT $MS/workflows/<id>/memory/<memoryId>/activate`.
