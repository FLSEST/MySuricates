# Java review: idioms, error handling & logging

Expertise for judging measured candidates from PMD and source patterns. Source: the skills
`java-code-review`, `clean-code`, `logging-patterns` and `performance-smell-detection` from
decebals/claude-code-java (MIT), shortened and extended with lessons learned in MySuricates.

## Basic stance
- A PMD hit is a candidate. It only becomes a finding once a **consequence** is visible in the source:
  a lost error cause, a swallowed error, a resource leak, wrong behaviour, misleading logs.
- Many rules have known false alarms (see each section). Read the location before you judge.
- Hits of the same kind in one file form **one** finding; name the lines in the evidence.

## Error handling
- **Lost cause** (`lost_cause`, script): `throw new X(...)` in a `catch`, without the caught exception as the
  cause, without its message and without logging it. The information is completely gone. Finding MEDIUM; HIGH
  where errors have to be analysed later (execution, integration, security). False alarm: the cause contains
  secrets (usually a comment then says so). Validation idioms (parse error → user-facing message) are already
  filtered out. `AvoidLosingExceptionInformation` (PMD) is the rare case "a call on the exception that has no
  effect".
- **Swallowed** (`empty_catch`, script): an empty `catch` without a comment (also with a variable named `ignored`),
  and an empty `catch` with a comment that catches **broadly** (`Exception`, `Throwable`, `RuntimeException`). A
  comment names the intended case ("not JSON"), but a broad type also swallows every other one, for example an NPE
  from the `try` block. So check what else in the `try` can throw besides the intended case, and what happens
  afterwards. Finding LOW; MEDIUM if a swallowed error turns into wrong behaviour (input is passed on as data,
  state is left half-changed). The script does not measure narrowly typed empty `catch` blocks with a comment.
- **Interrupt lost** (`interrupt_lost`, script): `catch (InterruptedException)` without
  `Thread.currentThread().interrupt()` and without rethrowing; converting it into another exception also loses
  the flag. Finding MEDIUM (cancellation from outside has no effect).
- **Too broad** (`AvoidCatchingThrowable`, `AvoidCatchingNPE`): `catch (Throwable)` is justifiable only at thread
  boundaries with a log; elsewhere it also catches `OutOfMemoryError`. `catch (NullPointerException)` almost
  always hides a bug. `catch (Exception)` at boundaries (scheduler loop, background job, request boundary) is
  deliberate and is not measured here.
- **Raw types** (`AvoidThrowingRawExceptionTypes`, `SignatureDeclareThrowsException`): `throw new RuntimeException`
  or `throws Exception` on a public API. Finding LOW–MEDIUM; fix: a domain-specific type.
- **Exception as flow control** (`ExceptionAsFlowControl`, `AvoidRethrowingException`,
  `AvoidThrowingNewInstanceOfSameException`): catching and immediately rethrowing without added value; throwing
  and catching an exception within the same `try`. Mostly LOW.
- **finally** (`ReturnFromFinallyBlock`, `DoNotThrowExceptionInFinally`): swallows the actual exception.
  Finding MEDIUM.

## Logging
- **Wrong placeholders** (`InvalidLogMessageFormat`): the number of `{}` does not match the arguments. A real
  bug (MEDIUM). Exception: a last argument of type Throwable is allowed and is logged as a stack trace.
- **Log and rethrow** (`log_and_rethrow`): the same error appears twice in the log, often with a stack trace.
  Finding LOW if the boundary further up logs anyway. No finding if this place adds context that is missing
  further up, or if a different exception is thrown with the cause and is not logged further up.
- **System.out / printStackTrace** in server code: finding LOW (bypasses logging, no level, no context).
  In CLI tools or `main` methods: no finding.
- **Secrets in the log** (`sensitive_log_arg`): tokens, keys, passwords, Authorization headers, whole request
  bodies. Finding HIGH if the value itself is logged; no finding for a file path, length, hash or
  "present: yes/no".
- Several loggers or the wrong class in the logger (`ProperLogger`, `MoreThanOneLogger`): LOW.

## Null and Optional
- **`Optional.get()` without a check** (`optional_get`): a finding if no `isPresent`/`isEmpty` guards the call;
  fix: `orElseThrow` with a meaningful exception.

## Resources
- **Not closed** (`CloseResource`, `UseTryWithResources`): streams, readers, `Process` streams, connections,
  `Files.lines`/`Files.list`/`Files.walk` (these return streams that must be closed!).
  Finding MEDIUM; HIGH if it happens in a loop or on every request.
  False alarm: the resource is returned and closed by the caller, or a framework closes it.

## Dead code
- **Unused** (`UnusedPrivateMethod`, `UnusedPrivateField`, `UnusedLocalVariable`, `UnusedAssignment`,
  `UnusedFormalParameter`): before reporting, check whether reflection or a framework uses the location:
  `@EventListener`, `@Scheduled`, `@PostConstruct`, `@PreDestroy`, `@JsonCreator`, `@JsonProperty`, JPA callbacks
  (`@PrePersist` …), MapStruct methods, Lombok accessors, tests via package visibility. Real dead code:
  LOW. An overwritten, unused assignment can be a logic error: then MEDIUM.

## Equality and comparisons
- `equals` without `hashCode` (or the other way round) on classes that end up in sets/maps: MEDIUM.
- `==` on objects or strings (`CompareObjectsWithEquals`, `UseEqualsToCompareStrings`): a finding, except for
  enums, a deliberate identity check (comment) or sentinel objects.
- A fall-through `switch` without a comment (`ImplicitSwitchFallThrough`): MEDIUM.

## API shape
- **Boolean parameters** (`boolean_parameter`): public methods with a flag whose meaning is not readable at the
  call site (`execute(req, true)`). Check the callers: if literals are passed, it is a finding LOW with a
  suggestion (enum, two methods, parameter object). A flag that always comes from a variable of the same name,
  or an established name (`readOnly`, `force`), is no finding.
- **Long parameter lists** (`long_parameter_list`): from 6 parameters on a public method, from 8 otherwise.
  Finding **at most LOW**. MEDIUM only if you prove an actual mix-up at a concrete caller, or a bug that resulted
  from one (file:line). Parameters of the same type next to each other are not enough on their own.
  Report the same group on several methods as one finding (missing parameter object). Factory methods of
  DTOs/records are no finding.

## Minor performance issues
- **Regex per loop iteration** (`regex_in_loop`): `String.matches`/`replaceAll`/`split` with a regex and
  `Pattern.compile` compile anew on every call. Finding LOW, MEDIUM in hot paths (per line of a large output,
  per request). Fix: `private static final Pattern`.
- **String concatenation in loops** (`UseStringBufferForStringAppends`): LOW, MEDIUM for large amounts of data.

## Severity
| Severity | Criterion |
|---|---|
| CRITICAL | Security vulnerability or data loss |
| HIGH | Bug likely, secret in the log, resource leak per request |
| MEDIUM | Error hidden or hard to analyse (lost cause, swallowed exception) |
| LOW | Maintainability and style (dead code, raw types, parameter shape) |