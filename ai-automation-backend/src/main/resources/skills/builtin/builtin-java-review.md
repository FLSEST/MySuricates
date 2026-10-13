# Java review: code review of changes

Expertise for reviewing a diff (the commits since the last run, or a range such as a pull request).
Source: the skill `java-code-review` from decebals/claude-code-java (MIT), shortened and extended with experience
from MySuricates and the other Java audits (concurrency, persistence, idioms, readability).

## Procedure
1. **Understand the intent:** read the commit subjects, then the diff. What is the change meant to achieve?
2. **Check every changed location** (`changed_hunk`): read the location in the source with its surroundings, not only
   the diff. For new files, read the whole file.
3. **Candidates of the measurement** on changed lines (lost_cause, blocking_in_synchronized, repository_in_loop …):
   judge them as in the respective audits.
4. **Tests:** if logic changes without tests being added or changed (`changed_test_files`), that is a
   finding `java.review.missing-test`. Check whether existing tests already cover the new logic (Grep for the
   class name under `src/test`).

## Checklist per location
- **Correctness:** edge cases (null, empty, 0, maximum), inverted conditions, off-by-one, wrong order,
  incomplete renaming (the old name is still in use elsewhere).
- **Error handling:** swallowed exceptions, lost cause, a broad catch that also catches new errors,
  resources without try-with-resources, `InterruptedException` without `interrupt()`.
- **Concurrency:** new shared state, blocking under `synchronized` (Java < 24 with virtual threads:
  pinning), `@Async`/`@Transactional` self-invocation, ThreadLocal without `remove` in `finally`.
- **Persistence:** slow calls inside `@Transactional`, a read per loop iteration, `findAll` on growing
  tables, lazy access outside the transaction, a writing query without a transaction.
- **API contract:** entity exposed to the outside, `@RequestBody` without `@Valid`, changed response shape without
  notice (breaks clients), wrong verb or wrong status code.
- **Security:** input flowing into paths, commands, queries, logs; secrets in logs or `toString`; files holding
  keys not created with 0600; new public endpoints without checks.
- **Readability:** the name does not match the effect, new deep nesting, a copy instead of reusing
  an existing helper method.
- **Project rules:** whatever the README, CLAUDE.md or comments nearby state as a rule is the yardstick
  (e.g. "every new process spawn site must go through …"). A violation is a finding with a reference to the rule.

## What is not a finding
- Style questions that a formatter or linter settles; taste in names where nothing is misunderstood.
- Existing code the change does not touch: the audits of the whole code base check that. Exception: the change
  relies on an existing bug or makes it worse.

## Known findings of earlier reviews
An open review finding stays until the location is fixed, even if the file was not changed in the current range:
check it in the source and carry it on, or leave it out once it is fixed.

## Severity
| Severity | Criterion |
|---|---|
| CRITICAL | Security hole or data loss caused by the change |
| HIGH | Failure in normal operation likely, contract break for clients |
| MEDIUM | Failure in edge cases, missing tests for new logic with branches |
| LOW | Readability, small robustness gaps |