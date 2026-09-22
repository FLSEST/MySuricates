## Operations workflows

- **Monitoring:** `shell` fetches measurements (external services, never the MySuricates API itself), `conditional` checks thresholds, `gate_status: RED` marks critical values; regularly via a schedule.
- **Logs:** `shell` filters, `simple` interprets, `agentic` looks for the root cause.
- **Deployments:** `approval` before production, `retry` for health-check polling; build → test → deploy as a chain (only with the `chains` module).
- **Findings as TODOs:** with the `todo-create` block or through `todo_item: Title | HIGH | tag1,tag2 | Description` in the output with AutoTodo switched on – not via REST from a shell block.

### Schedules

- List: `GET $MS/schedules`; what still runs today: `GET $MS/schedules/remaining-today`.
- Cron has six fields: `0 */15 * * * *` means every 15 minutes.
- Times that fall into standby or a restart are caught up once or skipped, depending on the setting.

### Finding errors

- Failed runs: `GET $MS/executions?status=FAILED&page=0&size=10` (`error` is an object).
- Activity log: `GET $MS/activity-log?level=ERROR&page=0&size=50` (filters `level` exactly this level, `category`, `workflowId`).

### Hardening

With `require-sealed-for-unattended` switched on, schedules, webhooks and TODO triggers only start sealed workflows; otherwise the API answers 403 `UNATTENDED_REQUIRES_SEAL`.
