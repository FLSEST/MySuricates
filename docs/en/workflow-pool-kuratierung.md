# Curation plan for the public workflow pool

The pool under `workflow-pool/` ships with the repository. Until this plan, nothing said what
belongs in it: the entries were the result of individual exports rather than a selection —
which is how home paths, instance UUIDs and personal data ended up there.

This document answers four questions. Section 3 is at the same time the rule set that
`scripts/ci/pool-neutrality.sh` enforces mechanically: the plan defines the criteria, the gate
guards them.

---

## 1. What the pool is for — as opposed to the built-in templates

Two mechanisms exist side by side, and they answer different questions.

| | Built-in templates | Workflow pool |
|---|---|---|
| Location | `ai-automation-backend/src/main/resources/templates/builtin/*.json` (66) | `workflow-pool/*.json` |
| Way into an instance | `WorkflowTemplateSeeder` seeds them into the database at startup, comparing semver | manual: `GET /api/v1/pool` → `POST /api/v1/pool/{id}/install` |
| Visible as | a template in the catalogue, present in every instance | an entry in the pool browser, a workflow only after a deliberate install |
| Updates | the seeder pulls in a higher version | none; an installed workflow is a copy and lives on by itself |
| Directory configurable | no | yes, `AUTOMATION_POOL_DIR` (default `workflow-pool`) |

**The distinction in one sentence:** a built-in template is part of the platform and arrives
unasked; a pool entry is an offer a reader takes up deliberately.

Hence the decision rule for a new workflow:

- It belongs to what the platform should be able to do on its own (platform quality gates,
  assistant building blocks, anything other code relies on) → **built-in template**.
- It is a useful example or a tool not everyone needs → **pool**.
- It is tailored to a person, a household or someone else's project → **neither**; it stays in
  the instance and is not tracked.

The rules against the platform's own REST API, credentials and home or user paths (`SELF-API`,
`CREDENTIAL`, `PATH-HOME`, `PATH-ABS` in §3) apply to built-ins just the same. There,
`BuiltinTemplateGateTest` checks them in the backend build — with the pool gate's patterns, plus
the key file (`…/api-key`), `$HOME` and the address `localhost:9090`.

## 2. The categories: `scope`

Every entry carries a mandatory `scope` field with exactly one of two values:

| Value | Meaning | How to recognise it |
|---|---|---|
| `repo` | relies on the layout of **this** repository | mentions `ai-automation-backend`, `ai-automation-frontend`, `mvnw`, `scripts/` or `workflow-pool/` |
| `portable` | parameterised via `project_path` (or similar context variables) | runs in whichever project the installer picks |

Both kinds are legitimate, but they are useful in different ways: installing a `repo` entry
into your own project gets you a workflow with nothing to work on. That is why `scope` is a
field, not a comment:

- in the definition (`"scope": "repo"`),
- in the pool browser as a small badge next to the version (`repo` in the warning colour,
  `portable` in the info colour, with an explanation in the tooltip),
- on export as a query parameter: `POST /api/v1/pool/export/{workflowId}?scope=repo`
  (without it, `portable` applies — the weaker promise needs no justification, the stronger
  binding to this repository does).

`scope` is **pool metadata**, not a field of the workflow definition: an installed workflow
does not carry it on, and the database does not know it.

### Where the inventory lives

It is deliberately **not** listed here. A list inside the document ages with every export —
and this one already had: it said 13 `repo` and 7 `portable` and was out of date a few
entries later.

The directory itself gives the reliable answer, because every definition carries its `scope`
as a field:

```bash
grep -h '"scope"' workflow-pool/*.json | tr -d ' ",' | sort | uniq -c  # the split
grep -l '"scope": "portable"' workflow-pool/*.json                     # the portable ones
```

`bash scripts/ci/pool-neutrality.sh` prints the checked total at the end, and the pool
browser shows the `scope` badge next to each version.

## 3. Criteria per entry — the gate's rule set

Every rule is phrased so it can be checked, and carries the tag under which
`scripts/ci/pool-neutrality.sh` reports it. The lists behind them (allowed `scope` values,
block types, repo markers, instance keys) live in `scripts/lib/hygiene-rules.sh`.

| Tag | Rule | Check |
|---|---|---|
| `SCOPE` | `scope` is present and one of `repo`, `portable` | field comparison |
| `NAME` | `name` is a `{de, en}` object with at least one non-empty text | without it the entry does not even load |
| `DESCRIPTION` | `description` is a `{de, en}` object with at least one non-empty text | field comparison |
| `TAGS` | `tags` is a non-empty list of non-empty strings | field comparison |
| `PROJECT-ID` | no `projectId`/`project_id` key with a concrete value — neither as a field nor in `defaultContext`, metadata or a block config | recursive key walk; a placeholder (`{{ project_id }}`) or an empty value is allowed, that is the portable parameterisation |
| `INSTANCE-REF` | the same for `workflowId`, `runId`, `scheduleId`, `chainId`, `executionId` (and snake_case spellings) | as above |
| `UUID` | no instance UUID anywhere in the text | regex `[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}` |
| `PATH-ABS` | no absolute user/system paths (`/home/…`, `/Users/…`, `/opt/…`, `/var/log|lib|opt/…`) | regex |
| `PATH-HOME` | no tilde paths (`~/project`) | regex |
| `PATH-WIN` | no Windows path with a drive letter | regex |
| `BINARY` | no hard-wired tool path (`/usr/bin/dot`); `#!/usr/bin/env` stays allowed | regex |
| `HOST-LAN` | no LAN addresses (192.168.x, 10.x, 172.16–31.x) | regex |
| `EMAIL` | no mail address | regex |
| `CONTEXT-PII` | no personal key in `defaultContext` (children, address, IBAN, CV …) | token comparison; the key is the signal — even with a placeholder as its value |
| `BLOCK-TYPE` | every block type is in the registry, including those nested inside a `switch` (`cases[].blocks` and `default` — the only places where a block contains a block; the other wrappers run strings) | list comparison |
| `REPO-DEP` | an entry with `scope: portable` mentions none of the repo markers (`ai-automation-backend`, `ai-automation-frontend`, `scripts/`, `mvnw`, `workflow-pool/`) | substring search |
| `SELF-API` | the definition does not call our own REST API | an `/api/v1/<endpoint>` together with a caller (`curl`, `wget`, `requests.`, `urllib`, `fetch(`, `http.client`) or an HTTP verb in front of it; allowed are the auth-free `/api/v1/system/{health,info,auth-status}` as a liveness probe and `/api/v1/system/jvm-memory` (three heap numbers) |
| `CREDENTIAL` | the definition neither reads nor sets credentials | substring `X-API-Key` or `app_settings` — no exceptions |
| `DB-DIRECT` | the definition does not read the database directly | `sqlite3` anywhere, or `automation.db` together with a read access (`SELECT`, `INSERT INTO`, `.tables`, …); looking at the file size (`stat`, `du`) stays allowed |
| `PARSE` | the file is valid JSON and an object | `json.load` |

**Bilingual steps.** A pool file with the marker `"i18n": ["de", "en"]` may also carry step names, questions, prompts and `instructions` as `{de, en}`. It is then installed in the UI language (`locale` in the install body). Which fields must stay single-language (shell fields, compared values, `metadata`) is described in [WORKFLOW-GUIDE → Bilingual templates](WORKFLOW-GUIDE.md#bilingual-templates). `LocalizedDefinitionGateTest` checks this in the backend build, not `pool-neutrality.sh`.

### The block type registry

The source is `BlockTypesIndexSection.BLOCK_TYPES` in the backend, itself pinned against the
existing `BlockExecutor` classes (`BlockTypeListsGuardTest`). For the scripts the list is
**generated** from it — `scripts/lib/block-types.generated.sh`, written by
`BlockTypesGateListTest`. No hand maintains it, so it cannot drift.

The current list therefore does not live here; it comes from the file:

```bash
. scripts/lib/block-types.generated.sh && printf '%s\n' "${HYGIENE_POOL_BLOCK_TYPES[@]}"
```

An enumeration at this point would be a third copy — and was one: it said 19 types and did not
know about `dashboard-report` once that was added.

### Own REST API, credentials, database — and why the patterns are two-stage

The house rule is in `CLAUDE.md`: workflow blocks call neither our own REST API nor the
database. Everything a workflow needs in terms of platform data is injected by the
`WorkflowExecutor` as a context variable (`{{ open_todos }}`, `{{ previous_report }}`,
`{{ workflow_run_id }}`, …). In a **published** definition a second problem comes on top: a
prompt explaining how to fetch the API key from `app_settings` is a set of instructions for
extracting credentials — every reader installs them along with the entry.

Two of the three patterns would be useless as a single stage, so they require a second marker:

- `/api/v1/` on its own is not a call. `mocking-gap-audit` carries the string as a **search
  pattern** in a list of network markers, right next to `fetch(` and `axios.`. What gets
  reported is therefore a concrete endpoint (`/api/v1/todos`, not the bare prefix) together
  with a caller nearby.
- `automation.db` on its own is not database access. `mysuricates-performance-audit` only looks
  at the **file size** via `stat -c %s` and says so in a comment. What is forbidden is reading
  the contents, which bypasses Hibernate and the single connection (pool size 1).

To write TODOs, use the block types `todo-create`, `todo-update` and `todo-close`. To merely
react to a finding, print `gate_status:` at the end and set `metadata.autoTodo.enabled` — then
the platform creates the TODO, keeps it current across runs and closes it again on GREEN. That
is exactly how `system-performance-analysis` was reworked (TODO 1ccdc6d5): the agentic block
that called `POST /api/v1/todos` via curl and was told to fetch the key from `app_settings` is
gone.

### What the gate cannot check

Two criteria remain a reading task and belong to the release step (section 4):

- **No prompt tailored to a person.** `CONTEXT-PII` catches the key in `defaultContext`, not a
  phrasing inside the prompt itself.
- **No dependency on scripts outside the repository.** `PATH-ABS` and `PATH-HOME` catch the
  obvious case; a tool that only happens to be on one machine's `PATH` is invisible to the gate.

## 4. How entries come about and are maintained

### Export

`POST /api/v1/pool/export/{workflowId}?scope=repo|portable` writes the workflow as a pool
definition. The export no longer writes **any instance bindings**
(`service/pool/PoolExportSanitizer`):

- keys such as `projectId`, `workflowId`, `runId`, `scheduleId`, `chainId`, `executionId` are
  dropped unless their value is empty or a template variable,
- a value that is exactly a UUID is dropped,
- a UUID in the middle of a text is replaced with `REDACTED-UUID` so the text stays intact,
- factory-internal metadata (`factoryGenerated`, `factoryTopic`, `factoryCategory`) is dropped
  as before.

What was removed is logged as a warning **and returned**: `ExportToPoolResponse` lists the
cleaned-up spots by path in `sanitizedPaths` (e.g. `blocks[0].config.workflowId`). The export
cleans up, it does not quietly truncate.

**Important for `sub-workflow` blocks:** their `config.workflowId` points at a workflow of the
exporting instance and therefore falls under the rule. After the export, a workflow with a
`sub-workflow` block is **not an installable pool entry** until the `workflowId` has been filled
in by hand or replaced with a context variable — `sanitizedPaths` names the exact spot.

### Release step before the commit

An exported entry is a draft. Before committing:

1. `bash scripts/ci/pool-neutrality.sh` — all rules from section 3 across the tracked
   definitions. The pre-commit hook and CI run the same check through
   `scripts/ci/public-hygiene.sh` (check 3); a violation blocks the commit.
2. Read the two unverifiable criteria yourself: the prompt for personal references, and
   dependencies on tools outside the repository.
3. Re-read `scope`: does the promise hold? A `portable` entry that only works with one
   particular project layout is a `repo` entry.

An entry that does not pass is not tracked. `git rm --cached` is enough: the directory stays
the working surface of the running instance, only the selection is tracked.

### Recurring check

The built-in template `pool-neutrality-audit` calls `scripts/ci/pool-neutrality.sh --all` and
thereby checks **every** `.json` in a running instance's pool directory, including untracked
ones. That way an entry that would be tracked at the next release shows up beforehand. The
pattern catalogue lives only in the script, not a second time in a JSON field of the template.

---

Related: [WORKFLOW-GUIDE.md](WORKFLOW-GUIDE.md) (block types and patterns),
[architektur.md](architektur.md) (templates, seeder), [deployment.md](deployment.md)
(trust model).
