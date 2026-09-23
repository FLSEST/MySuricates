## Platform knowledge

### Gates

A block prints quality markers:

```
gate_status: GREEN|YELLOW|RED
gate_category: security
gate_description: No critical findings
```

- The worst rating of all blocks counts; it is stored in `metadata.gate_status` of the run.
- The gate does not change the status of the run: `COMPLETED` with gate `RED` is possible. `FAILED` means an execution error, not a bad result.

### AutoTodo and TodoTrigger

- AutoTodo on the workflow: `"metadata": {"autoTodo": {"enabled": true, "mode": "auto"}}`. Modes: `auto` (GREEN closes, YELLOW → MEDIUM, RED → HIGH), `track` (always create), `approval` (for confirmation).
- Single TODOs from the output: `todo_item: Title | HIGH | tag1,tag2 | Description`.
- TodoTrigger (`GET/POST $MS/todo-triggers`): a rule with `tagPattern` (all tags must match) and `targetWorkflowId` starts a fix workflow for new TODOs; at most `maxAttempts` attempts (default 3).
- Completion triggers (`$MS/completion-triggers`) start a workflow when another one ends — on success, on failure or always. The card "Gets triggered by" shows every trigger of a workflow.
- Resolve (`POST $MS/todos/<id>/resolve`) counts as successful when the run ends `COMPLETED` and the gate is `GREEN` or missing; the TODO then becomes `DONE`, otherwise it returns to its previous status.

### Providers and models

- AI-capable blocks (`simple`, `agentic`, `skill-capability` and the wrappers `foreach`, `while`, `conditional`, `switch`, `retry`) take `provider` (`CLAUDE`, `PI`, `CODEX`), `model` and `effort`. Precedence: block > workflow > instance default.
- `CODEX` is read-only; write attempts fail silently.
- Claude models as aliases that always point to the newest model: `opus` (analysis, audits), `sonnet` (routine, code), `haiku` (simple classification and extraction), `fable` (hardest tasks). Pi takes `<backend>/<model>`, Codex a slug.
- Default provider: `GET $MS/system/settings/default-ai-provider`, change it with `PUT` and `{"value": "CLAUDE"}`.

### Variables

- `{{ previous_output }}`, `{{ step_0_output }}`, `{{ block.<name>.output }}` – outputs of earlier steps.
- `{{ workflow_run_id }}`, `{{ project_path }}`, `{{ run_timestamp }}`, `{{ open_todos }}`, `{{ previous_report }}` are filled in by the platform.
- Your own variables via `context` at start: `{"context": {"branch": "main"}}` → `{{ branch }}`.

### Agentic budget

| Setting | Default |
|---|---|
| `max_iterations` | 10 |
| `iteration_timeout_seconds` | 300 |
| `total_timeout_seconds` | 1800 |
| `max_total_tokens` | 200,000 |
