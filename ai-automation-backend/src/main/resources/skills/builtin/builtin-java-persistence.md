# Java review: persistence & transactions

Expertise for judging measured candidates in JPA/Hibernate and Spring Data code. Source: the skills
`jpa-patterns` and `spring-boot-patterns` from decebals/claude-code-java (MIT), shortened and extended with
lessons learned from running a Spring application on SQLite with a single connection and OSIV disabled.

## Basic stance
- A finding means: you can name a sequence that leads to wrong data, to an exception at runtime or to
  measurably more database work. "Could be inefficient" is not enough.
- Read the context too: does the code run per request, per run, in a loop or once at startup? How large is
  the table realistically (configuration vs. run-time data)?
- The FAKT lines `open_in_view` and `connection_pool_max` co-determine the severity.

## Slow calls inside transactions (`transactional_slow_call`)
- HTTP, process start, Docker, AI/CLI calls, `sleep`, waiting on futures inside `@Transactional` or inside
  a `TransactionTemplate` callback hold the connection and the locks for the whole duration.
- Severity: HIGH with a small pool (`connection_pool_max` ≤ 2, e.g. SQLite) or when the path runs per request;
  otherwise MEDIUM. Fix: short DB sections via `TransactionTemplate`, slow work before or after them.

## Proxy pitfalls (`transactional_self_call`, `transactional_ineffective`)
- A call from within the same class bypasses the proxy: the target's annotation has no effect. A finding only
  if the caller itself has **no** transaction and the target needs one (it writes, needs several reads to be
  consistent, or `readOnly` for lazy loading). If the target only reads one row via a repository (which is
  transactional itself), it is not a finding.
- `@Transactional` on `private`/`final`/`static` methods has no effect.

## N+1 (`repository_in_loop`)
- A read access per loop iteration: finding MEDIUM if the loop runs over run-time data or user input;
  LOW for small fixed sets. Not a finding: one-off startup runs (seeders, migrations), wait loops with sleep,
  loops that stop as soon as something is found.
- Fix: a batch query (`findAllById`, `WHERE x IN`), `JOIN FETCH`, `@EntityGraph`, `@BatchSize`.

## Loading whole tables (`find_all_unbounded`)
- `findAll()` without a Pageable loads every row including EAGER relations. A finding if the table grows
  with use (runs, logs, events, results) **and** the call lies on a frequent path. Configuration tables
  (projects, switches, templates) are not a finding.

## Queries built by string concatenation (`query_string_concat`)
- Concatenated JPQL/SQL with values from inputs: finding HIGH (injection). Only fixed building blocks
  (column names from an allowlist): not a finding, but a hint to use parameters.

## Modifying queries without a transaction (`modifying_without_tx`)
- `@Modifying` methods need a running transaction, otherwise `TransactionRequiredException`. Check the
  callers: if every one of them has a transaction, it is not a finding (at most a LOW hint to put the
  annotation on the repository method). One caller without a transaction: MEDIUM.

## Entities
- **Lombok** (`entity_lombok`): `@Data`/`@EqualsAndHashCode` over all fields includes relations and lazy fields:
  `LazyInitializationException`, endless recursion with bidirectional relations, the hash changes after
  `persist` (the entity vanishes from a `HashSet`). `@ToString` over lazy fields loads them or throws.
  Finding MEDIUM; not a finding with `onlyExplicitlyIncluded` on the ID, or for entities without relations
  and with an immutable business key.
- **EAGER** (`eager_fetch`): every query loads the relation along with it, lists included. A finding if the
  relation is a collection or drags along large blobs; `@ManyToOne` EAGER to a small master-data entity is
  usually intended, especially with OSIV disabled (then LAZY outside the transaction would be an error).
- **Cascade REMOVE/ALL towards the parent** (`cascade_remove_to_parent`): deleting a child deletes the parent
  along with its siblings. Finding HIGH.
- **Optimistic locking**: `entities_with_version` = 0 is a context fact, not a finding on its own. A finding
  only if you find a concrete read-modify-write sequence with concurrent writers.

## OSIV
- `open_in_view` not set (default true): the connection stays occupied for the whole request; with a small
  pool this is a bottleneck. Finding MEDIUM if `connection_pool_max` is small at the same time.

## Severity
| Severity | Criterion |
|---|---|
| CRITICAL | Data loss (cascade deletes other data), injection with user input |
| HIGH | Exception or standstill in normal operation (connection held with a small pool, transaction missing) |
| MEDIUM | Measurably more DB work on a frequent path, Lombok pitfalls on entities |
| LOW | Hygiene, small fixed sets, hints |