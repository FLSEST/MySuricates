## MySuricates API

This project is connected to the MySuricates instance at {{BASE_URL}}, project id `{{PROJECT_ID}}`.
Through the REST API you create TODOs, start workflows and follow their runs.

### Connect

```bash
MS={{BASE_URL}}/api/v1
API_KEY="${MYSURICATES_API_KEY:-$({{API_KEY_COMMAND}})}"
curl -s "$MS/system/health"                                 # no key needed
curl -s "$MS/system/capabilities" -H "X-API-Key: $API_KEY"  # preset, modules, block types
```

What this instance can do is listed in `/system/capabilities`: enabled modules and block types.
Endpoints of a disabled module answer 404 `MODULE_DISABLED`, so look there instead of guessing.

### TODOs – with every code change

1. Look for a matching TODO: `GET $MS/todos?projectId={{PROJECT_ID}}&status=OPEN&status=IN_PROGRESS`
   (response `{content, totalElements, …}`; more filters `priority`, `tag`, `q`, `page`, `size`).
2. If there is none, create one: `POST $MS/todos` with the body below.
3. Before the first change `PATCH $MS/todos/<id>/status` with `{"status": "IN_PROGRESS"}`, afterwards `{"status": "DONE"}` (full id).
4. Always name TODOs with their title, e.g. `82ca537f (Title)`, never the id alone.

```json
{
  "title": "Short title",
  "description": "Context in Markdown",
  "priority": "LOW | MEDIUM | HIGH | CRITICAL",
  "tags": ["bug"],
  "projectId": "{{PROJECT_ID}}",
  "source": "AI",
  "sourceAgent": "<your agent>",
  "agentSessionRef": "<id of your session, to resume it later>"
}
```

- `PUT $MS/todos/<id>` changes only the fields sent.
- `GET $MS/todos/similar?projectId={{PROJECT_ID}}&title=<title>` finds duplicates.
- `POST $MS/todos/<id>/resolve` starts the workflow linked to the TODO (`linkedWorkflowId`).
- A new OPEN TODO can start a fix workflow by itself through a TodoTrigger rule.

### Run workflows, follow runs

```bash
curl -s -X POST "$MS/workflows/<id>/execute" -H "Content-Type: application/json" \
  -H "X-API-Key: $API_KEY" -d '{"context": {"branch": "main"}}'
```

- Variables go in `context`, not `variables`. The response is the run with `id` and `status`.
- `GET $MS/executions/<runId>` returns `status`, `steps[]` (`blockName`, `blockType`, `status`, `output`), `error` and the gate in `metadata.gate_status`.
- Statuses: PENDING, RUNNING, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, FAILED, CANCELLED, INTERRUPTED (after a restart of the instance).

| Action | Endpoint |
|---|---|
| Is the run waiting for an approval? | `GET $MS/executions/<id>/approval` → `pending`, `options`, `rejectOptions` |
| Approve | `POST $MS/executions/<id>/approve` with `{"answer": "<one of the options>"}` |
| Everything waiting right now | `GET $MS/executions/pending-input` |
| Pause, resume, cancel | `POST $MS/executions/<id>/pause`, `/resume`, `/cancel` |

### Rules

- Never access the SQLite database directly, neither reading nor writing – always go through the API.
- Shell blocks of a workflow don't call the MySuricates API; what they need, such as `{{ open_todos }}`, the platform fills in.
- Cron has six fields, seconds first: `0 0 8 * * MON-FRI`.
- Templates and blueprints to start from: `GET $MS/workflow-templates?locale=en`.
