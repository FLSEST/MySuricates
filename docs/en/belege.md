# Evidence: what the platform found in operation

This file collects cases where a mechanism of this system found something the normal
development process had not seen. No demo examples — verifiable incidents with a date, a run
id or test name, and a number.

**Why this is written down instead of queried.** Runs are deleted after 90 days
(`run-retention-days`, as of 2026-09-19). Evidence that lives only in the database is gone in
three months. This is the exception to the rule of not freezing queryable things into
Markdown: nothing is frozen here, something is preserved.

**Format per case.** Date · which mechanism · what was found · what the normal process had
missed · how to verify it. The last point matters most: a case without verifiability does not
belong in this list.

**Honesty rule.** We name *what* exposed the problem. If a run found something because it ran
at all, that is what it says — not "the AI found the bug". The weaker phrasing is often the
stronger claim, because it holds regardless of the model.

---

## 2026-09-18 · The run beats the test suite

**Mechanism:** performance audit workflow, run `cd335b5c` (2026-09-18 21:27 UTC).

**Found:** a change to the fact sheet existed only in the pool file and had never reached the
live workflow in the database.

**Missed by:** 9059 backend tests, 1321 frontend tests and the audit self-test with 100 of
100 checks — all green. All three checked the **artifact** (the file in the repository); only
the run checked **reality** (the definition in the database).

**Verify:** run `cd335b5c`, raw output of step 0.

**It was not the AI analysis that exposed it** — that did not name the problem. What exposed
it was that the real workflow ran and someone looked at its raw output.

## 2026-09-18 · Three N+1 patterns on the ratchet's first run

**Mechanism:** `RequestCostRatchetTest` — counts SQL statements and response bytes per route
against a pinned upper bound.

**Found:** three real N+1 patterns on the very first run, two of them unnoticed for years: a
`select containers` per workflow in `/workflows`, `/search` and `/dashboard`
(TODO `13d1e2a2`), the unboundedly growing response of `/executions` (TODO `a05c9504`), and
the owner resolution of the run lists (fixed in `9db34f3b`).

**Missed by:** the entire existing test suite. No test had ever measured what a route *costs*
— only that it returns the right thing.

**Verify:** `RequestCostRatchetTest`, the `BASELINE` constant; TODOs `13d1e2a2`, `a05c9504`.

## 2026-09-18 · 111 SQL statements for one menu view

**Mechanism:** operational measurement `GET /api/v1/system/request-metrics` — the backend
counts along while running.

**Found:** `GET /api/v1/schedules` needs **111 SQL statements** per call (p95 154 ms) — the
highest statement count in the system, well above the 33 of `/workflows`.

**Missed by:** the ratchet itself. Its seed is small (3 projects, 30 workflows); a route whose
cost grows with the row count does not stand out there. Only the measurement on real data
showed it — and the same holds for `/projects`, green in the ratchet at 4 statements and
really needing 37 (TODO `6d0ab09f`).

**Verify:** TODO `172a6a67`; call the route again and read
`GET /api/v1/system/request-metrics`.

## 2026-09-19 · A gate was red without anyone noticing

**Mechanism:** documentation gate `scripts/public-readiness/doc-facts-check.sh` — compares
the sizes named in the docs against the code.

**Found:** the docs said 43 REST controllers (really 45) and V73 as the highest migration <!-- doc-facts-check: ignore — historical number, this IS the finding -->
(really V74), spread over 11 files in both languages. Two controllers were missing not just
as a number but as a row: `DashboardWorkflowController` and `SystemRequestMetricsController` —
both from the performance work of the same week.

**Missed by:** the review of the changes that introduced those controllers. The gate itself
did not run in CI, so it was red without anyone looking — a gate that is not measured is
green.

**Verify:** `bash scripts/public-readiness/doc-facts-check.sh` on the commit before
`a0a85490`; the suite `test-doc-facts-check.sh` stood at 71 ok / 1 red there.

## 2026-09-19 · Six green tests over a variable that was never set

**Mechanism:** writing the test that TODO `843be7a5` asked for.

**Found:** `injectPlatformData` **never** set the context variable `request_metrics`. The six
existing `injectPlatformData` tests were green because the fact sheet was missing as a Mockito
mock, so it was `null` — the NullPointerException was swallowed by a `catch (Exception)` at
the end of the method, and the two variables set before it still looked correct.

**Missed by:** `scripts/tests/test-audit-facts.sh`, which renders the shell block with a
fixture value. That proves the template, not the injection.

**Verify:** commit `4e3c3717`; the six new tests in `WorkflowContextDataServiceTest`.

## 2026-09-19 · 25 different rule ids for 26 findings

**Mechanism:** counting the `ruleKey` values actually assigned, via
`GET /api/v1/audit-findings`.

**Found:** because five of the six audit prompts only gave the placeholder
`stabile-regel-id`, the model invented new keys each run. Health audit: **26 findings, 25
different keys** — among them `god-class-count`, `god-classes-count` and
`complexity.god-class-count` for the same thing. Performance: 28 findings, 27 keys. Security:
35 findings, 35 keys. Every deviating key produces a repeat finding instead of a repeat
sighting on the next run, because the fingerprint hangs on the spelling.

**Missed by:** the ruleKey catalog that was built for exactly this — it covered 1 of 6 audits.
And the Audit Center, to which a repeat finding looks like a new one.

**Verify:** TODO `a17e2d19`; `GET /api/v1/audit-findings?size=500`, count keys per `auditKey`.
Counter-check: `AuditRuleKeyCatalogTest`.

## 2026-09-19 · The gate found the mistake of the agent extending it

**Mechanism:** the new completeness check for migration tables in the documentation gate.

**Found:** while adding V75, a faulty insertion had **cut the V74 row apart** — the table
jumped from V73 to V75. The existing check on the highest number stayed green, because the
highest number was right.

**Missed by:** the check that should have found it, and the agent who was extending it at that
very moment.

**Verify:** commit `adf6b90d`; remove a V row from `docs/datenmodell.md` and run
`doc-facts-check.sh` — reported as `migration-table-luecke`.

---

## What this list does not claim

It does not count findings an AI *reported* — there are many, and their worth depends on the
model. It counts cases where a **deterministic** measurement or a real run showed something
nobody had seen before. That is the narrower and the more defensible set.

If you add a case: keep the format, verifiability first. A case whose evidence can no longer
be retrieved gets a note saying what proved it — not deleted, but not presented as
retrievable either.
