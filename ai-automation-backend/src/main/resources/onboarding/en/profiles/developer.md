## Developer workflows

Building blocks for code projects, no fixed order:

- **Review:** `shell` fetches `git diff`, `simple` judges it; for deeper analysis `agentic` with a goal.
- **Tests:** `shell` with `"checked_command": true` derives a gate from the exit code; `retry` (default: 3 attempts) catches flaky tests.
- **Auto-fix:** `agentic` with a goal such as "Fix failing tests", preceded by an `approval` when a person should agree.
- **Quality gate:** a block prints `gate_status: GREEN|YELLOW|RED` (plus `gate_category`, `gate_description`). The gate does not change the status of the run.
<!-- module: chains -->
- **Build → test → deploy** as a chain.
<!-- /module -->

### Workflows via the API

- Create: `POST $MS/workflows` with `{"projectId": "{{PROJECT_ID}}", "name": "Tests", "blocks": [{"type": "shell", "name": "tests", "config": {"command": "npm test"}}]}` – the blocks go straight into the request.
- Check for build errors without running: `GET $MS/workflows/<id>/analyze`.
- Versions: `GET $MS/workflows/<id>/versions`, bring one back with `POST $MS/workflows/<id>/versions/<versionId>/restore`.
- Workflows of this project: `GET $MS/projects/{{PROJECT_ID}}/workflows`.
- Runs: `GET $MS/executions?workflowId=<id>&status=FAILED&page=0&size=10` (without steps; details via `/executions/<runId>`).

### Schedules

- Schedule: `POST $MS/schedules` with `{"workflowId": "<id>", "cronExpression": "0 0 8 * * MON-FRI", "description": "Weekdays at 8"}`.
  More fields: `maxRuns`, `endDate`, `conflictBehavior` (`SKIP` by default, or `OVERLAP`), `inputContext`.
  Switch on/off: `POST $MS/schedules/<id>/enable` or `/disable`.

### After another workflow finishes

- Create: `POST $MS/completion-triggers` with `{"sourceWorkflowId": "<a>", "targetWorkflowId": "<b>", "triggerOnSuccess": true}` (also `triggerOnFailure`, `triggerAlways`, `contextVariables`).
- What A triggers: `GET $MS/completion-triggers/workflow/<id>/as-source`; what triggers B: `…/as-target`.

### What starts workflow X?

The card "Gets triggered by" on the workflow page collects all of it. Via the API: `GET $MS/schedules/workflow/<id>`, `GET $MS/todo-triggers/workflow/<id>`, `GET $MS/completion-triggers/workflow/<id>/as-target` and, if the module is on, `GET $MS/webhooks/workflow/<id>`. Chains and `sub-workflow` blocks that call it appear in none of these lists.
<!-- module: webhooks -->

### Webhooks

- Create: `POST $MS/webhooks` with `{"workflowId": "<id>", "description": "Push"}`.
  Token and URL appear in full only in this response.
- Call it without a key: `POST {{BASE_URL}}/api/v1/webhook/trigger/<token>`; the fields of the JSON body become variables of the run.
<!-- /module -->
