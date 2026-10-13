# Java review: concurrency

Expertise for judging measured candidates (synchronized, locks, virtual threads, @Async,
CompletableFuture, shared state). Source: skill `concurrency-review` from
decebals/claude-code-java (MIT), shortened and extended with lessons learned in MySuricates.

## Basic stance
- A candidate only becomes a finding when you can show in the source **which sequence** goes wrong:
  which thread, which shared state, which wait. "Could be unsafe" is not a finding.
- Check **who runs the code**: request thread, scheduler, own executor, virtual thread.
  Code that runs only once at startup or only in a single thread carries hardly any concurrency risk.
- Also read the called methods when the candidate names a detour ("via x()").

## Blocking under a lock
- A short, pure memory access under `synchronized` is fine.
- Finding: I/O, process start, Docker, HTTP, database or file access, `sleep`, `wait`, `join`,
  `Future.get` inside `synchronized` or while a lock is held. All other threads that want the same
  lock wait for the whole duration of the I/O.
- **Virtual threads on Java 21–23** (JEP 491 only fixes this in Java 24): a virtual thread that blocks
  inside `synchronized` or **waits for an occupied monitor** occupies its carrier thread
  (pinning). There are as many carriers as CPU cores. If enough virtual threads wait for the same
  monitor, the whole scheduler stalls, including unrelated work such as requests or DB access.
  Severity: HIGH if `carrier_pinning_relevant: yes` and the code path can run on virtual threads
  (web requests with `spring.threads.virtual.enabled=true`, tasks in `newVirtualThreadPerTaskExecutor`,
  `Thread.ofVirtual`). Otherwise MEDIUM (the lock hold time serializes, throughput).
- Fixes in this order: move the I/O out of the critical section; `ReentrantLock` instead of
  `synchronized` (does not pin); Java ≥ 24. For "once per key", a `ConcurrentHashMap`
  with `computeIfAbsent` on a `CompletableFuture` can replace the lock.

## Lock scope and lock object
- `synchronized` methods lock `this`; if the instance is reachable from outside, foreign code can take
  the same lock. A finding only if the locked region becomes needlessly broad or blocks as a result.
- A lock on a non-final field, on strings or on boxed values is a finding.
- Lock maps (`locks.computeIfAbsent(key, k -> new Object())`) from which nothing is ever removed grow
  with every key. LOW if the set of keys is small and bounded (e.g. projects); MEDIUM if the keys come
  from user input or runtime IDs.

## ThreadLocal
- With `set` and `remove` in the `finally` of the same flow: fine, also on virtual threads.
- Finding: `remove` is missing or not in `finally` (leak on pool threads, state jumps to the next
  task); ThreadLocal as a **cache** for expensive objects when the code runs on virtual threads
  (every virtual thread rebuilds the cache).
- A value that is meant to reach child threads does not arrive there with `ThreadLocal`.
- `ScopedValue` is a preview feature in Java 21: do not recommend it as a fix for production code.

## Spring @Async (and @Transactional by the same principle)
- A call from the same class bypasses the proxy: the method runs **synchronously** in the caller's
  thread (or without a transaction). A finding if the caller relies on asynchrony.
- `@Async` on non-public methods has no effect.
- Return type `void`: exceptions only end up in the `AsyncUncaughtExceptionHandler`.
- `SecurityContext`, MDC and other ThreadLocals do not arrive in the async thread.

## CompletableFuture
- `supplyAsync`/`runAsync` without an executor runs in `ForkJoinPool.commonPool` (size = cores − 1).
  Blocking work there starves the other users of the pool (parallel streams). A finding if the work
  blocks (I/O, process, Docker); pure computation is fine.
- Without `exceptionally`/`handle`/`whenComplete` and without a later `join`/`get`, an exception is
  lost silently. Before reporting it, check whether the future is stored and evaluated later.
- Waiting without a timeout (`join()`, `get()`) on external work can hang forever; set `orTimeout`.

## Executors and threads
- Self-created executor services without `shutdown`/`close` leave threads behind as soon as the
  instance is replaced. For a single Spring bean that lives as long as the app, this is LOW.
- `@Bean` methods that return an executor are closed by Spring itself (inferred destroy method).

## Shared mutable state
- Static `HashMap`/`ArrayList` etc. that are still modified after class loading are a finding when
  several threads access them. Filled only in the static initializer and only read afterwards: fine
  (better `Map.of`/`List.copyOf`, then LOW as a hint).
- Check-then-act (`containsKey` + `put`) on a **shared** map is a finding; on a local variable or a
  method parameter it is not. Fix: `computeIfAbsent`/`putIfAbsent`/`merge`.
- `ConcurrentHashMap.compute*` lambdas must not modify the same map.
- Double-checked locking needs `volatile`.
- `SimpleDateFormat`, `DecimalFormat` and similar are not thread-safe; `DateTimeFormatter` is.

## Not a finding
- `volatile` flags for stop signals; `AtomicInteger`/`AtomicLong` counters.
- A local `HashMap` inside a method.
- `synchronized` around a few memory operations without calls to the outside.
- Deliberate one-time startup runs (seeder, migration, `ApplicationReadyEvent`).

## Severity
| Severity | Criterion |
|---|---|
| CRITICAL | Data loss or deadlock that occurs in normal operation |
| HIGH | Stall or misbehavior under realistic load (e.g. pinning on virtual threads) |
| MEDIUM | Throughput or robustness problem that only appears under pressure or on errors |
| LOW | Hygiene: bounded growth, missing `shutdown` on a long-lived bean, matter of style |