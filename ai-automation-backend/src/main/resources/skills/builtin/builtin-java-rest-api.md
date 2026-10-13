# Java review: REST API contract & documentation

Expertise for judging measured candidates from a controller inventory (Spring MVC). Source: the skills
`api-contract-review` and `spring-boot-patterns` from decebals/claude-code-java (MIT), shortened and extended
with experience from MySuricates.

## Basic stance
- The contract is what a client sees: path, verb, status code, shape of request and response, error format.
  A finding means: a client gets something wrong, unstable or unchecked, or an internal change breaks it
  without anyone noticing.
- Check exceptions deliberately: internal endpoints (plugin, UI-only), webhooks with a foreign format, upload/download.

## Entities exposed (`entity_exposed`)
- A JPA entity as response or `@RequestBody` couples the contract to the database schema: renaming a column
  breaks clients, lazy fields throw during serialisation (especially with OSIV off), internal fields (tokens,
  counters) leak, mass assignment via `@RequestBody` sets fields the client must not set.
- Finding MEDIUM (response) or HIGH (`@RequestBody` with writable security or owner fields).
  Fix: response/request DTO with a mapper.

## HTTP semantics
- **Verb does not fit** (`verb_mismatch`): GET with a side effect is a finding (caches, prefetch and crawlers
  trigger it). POST for reading is only a finding if it is not a complex search query.
- **Create without 201** (`post_without_created`): LOW; MEDIUM only if clients rely on 201/`Location`.
- An error with status 200 and an error object in the body is a finding if you see it while reading (as an observation).

## Input (`request_body_unvalidated`)
- `@RequestBody` without `@Valid`: the Bean Validation annotations on the DTO do not take effect. Finding if the DTO
  has constraints (`@NotBlank`, `@Size` …) or the values go unchecked into file paths, commands, queries or
  storage. Without constraints on the DTO and with a check in the service: no finding (an optional LOW note).

## Response shape (`untyped_response`)
- `Map<String, Object>`/`Object`/`JsonNode` as response has no fixed contract: keys change without anyone
  noticing, generated documentation and clients have to guess. Finding LOW; MEDIUM for central, frequently used
  endpoints. No finding for deliberately free shapes (passthrough of foreign JSON data, configuration objects
  with a free schema) if the Javadoc says so.

## Paths (`path_style`)
- Consistently kebab-case, nouns, resources in the plural. Upper-case letters or underscores: LOW. A segment
  such as `PI` (one provider among several) that really is a path parameter: point to `{providerId}`.

## Documentation (`handler_without_javadoc`)
- A handler without Javadoc is missing from the generated reference and does not explain its purpose. LOW.

## Error format
- `exception_advice_classes` = 0 with several controllers: errors arrive in Spring's default form and inconsistently.
  With exactly one `@RestControllerAdvice` the format is central: no finding.

## Severity
| Severity | Criterion |
|---|---|
| CRITICAL | Security hole through the contract (mass assignment on roles/ownership, data of other users) |
| HIGH | Client-visible error in normal operation, unchecked input into sensitive operations |
| MEDIUM | Contract coupled to the schema, GET with a side effect, central untyped response |
| LOW | Style, documentation, status code subtleties |