# Workflow pool

Each `*.json` here is a workflow **offer**, not a part of the platform. Nothing in this
directory runs until someone installs it deliberately:

```bash
curl -s --noproxy '*' localhost:9090/api/v1/pool -H "X-API-Key: $KEY"            # list
curl --noproxy '*' -X POST localhost:9090/api/v1/pool/<id>/install -H "X-API-Key: $KEY"
```

An installed workflow is a **copy**. It lives on independently — changing the file here does
not change it, and there is no update mechanism. That is the difference from the
54 built-in templates under `ai-automation-backend/src/main/resources/templates/builtin/`,
which every instance gets at startup whether it wants them or not.

## Read `scope` before you install

Every definition carries a `scope` field with one of three values:

| `scope` | means | for you |
|---|---|---|
| `portable` | parameterised via `project_path` and other context variables | runs in your project |
| `repo` | reaches into *this* repository's layout (`ai-automation-backend`, `mvnw`, `scripts/`) | will do nothing useful in yours |
| `external` | needs a tool that is neither in this repository nor in your project; the description names it | runs once you have that tool |

All three are legitimate. The `repo` entries are the self-audits this project runs on itself —
they are here as worked examples of the mechanism, not as tools for your codebase. The
`external` entries (`saas-*`) drive a SaaS factory with its own `factory` CLI, which is not part
of this repository. (The `saas-feature-loop` entry that ran that factory over a whole feature
list is out for now — no block type can start a sub-workflow per foreach entry yet; it returns
once one can.) The pool
browser shows the scope as a badge; from the shell:

```bash
grep -h '"scope"' workflow-pool/*.json | tr -d ' ",' | sort | uniq -c
```

## Before adding one

`bash scripts/ci/pool-neutrality.sh` enforces the rules — no instance UUIDs, no absolute
paths, no credentials, no direct database access, no calls to the platform's own REST API.
It runs in the pre-commit hook and in CI.

The full reasoning — what belongs here versus in a built-in template, every rule and how
entries are maintained — is in
[docs/workflow-pool-kuratierung.md](../docs/workflow-pool-kuratierung.md)
([English](../docs/en/workflow-pool-kuratierung.md)).

---

**Deutsch:** Jede Datei hier ist ein Angebot, kein Plattformbestandteil — sie wird erst durch
bewusstes Installieren zu einem Workflow, und zwar als eigenständige Kopie. Das Feld `scope`
sagt, ob ein Eintrag in einem fremden Projekt überhaupt etwas tut (`portable`) oder an den
Aufbau dieses Repositories gebunden ist (`repo`). Regelwerk und Begründung:
[docs/workflow-pool-kuratierung.md](../docs/workflow-pool-kuratierung.md).
