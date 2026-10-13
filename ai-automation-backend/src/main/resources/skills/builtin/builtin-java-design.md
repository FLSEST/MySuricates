# Java review: readability & design

Expertise for judging measured candidates at method level (complexity, nesting, length, misleading names) and
design hints (type switches, scattered case distinctions, refused overrides, self-built dependencies). Source: the
skills `clean-code`, `solid-principles` and `design-patterns` from decebals/claude-code-java (MIT), shortened and
extended with experience from MySuricates.

## Basic stance
- Ask for every candidate: **Can a person reading the code for the first time understand, in reasonable time, what
  happens and why, and change it without overlooking anything?** A high metric alone is no finding.
- Complexity that comes from the task (parser, state machine, validation with many cases, mapping tables) is often
  “intended” if it is well structured. Complexity from mixed concerns or deep nesting is a finding.
- Class-level topics (god class, coupling, cycles, layers) are checked by an architecture review. Report here only
  what is visible within a method or at a case distinction.
- The fix is **concrete**: which section becomes which method, which condition becomes a guard clause, which case
  distinction moves where. “Refactor” without a target is no fix.

## Method level
- **Cognitive complexity / NPath** (`CognitiveComplexity` from 25, `NPathComplexity` from 1000): a finding if the
  method does several things (recognisable by section comments, blocks separated by blank lines, changing levels of
  abstraction) or if nesting obscures the flow. Fix: extract one method per section, return early instead of
  `else`, name conditions (`boolean hasPendingApproval = …`).
- **Deep nesting** (`AvoidDeeplyNestedIfStmts` from depth 4): almost always a finding, LOW; guard clauses.
- **Long methods** (`NcssCount` from 80 statements): a finding if clearly separable steps can be recognised.
- **Misleading names** (`LinguisticNaming`): `isX()`/`hasX()` that do not return a boolean, `getX()` that changes
  something, `setX()` with a return value. A finding, because readers make wrong assumptions; MEDIUM if a caller
  misuses the method because of it.
- **Simplifiable** (`SimplifyBooleanReturns`, `CollapsibleIfStatements`): LOW, as one combined finding per file.

## Design (SOLID, and patterns as fixes)
- **Type switch** (`type_switch`): a `switch`/`if` chain over `getType()`, `status()` and the like. A finding if the
  same distinction recurs elsewhere or new types are foreseeable (Open/Closed): every new case requires changes in
  several places. Fix: put the behaviour on the type (enum method, polymorphism), a strategy per case, or a registry
  that maps each type to its handler. A single, self-contained case distinction over a fixed enum is intended.
- **Scattered switch** (`scattered_switch`): the same cases are evaluated separately in several files (Shotgun
  Surgery). A finding, MEDIUM: a new case or a changed meaning has to be followed up everywhere, and a forgotten
  place goes unnoticed. Fix: one place that knows the meaning (a method on the enum, a mapping class).
- **Refused override** (`refused_override`): an implementation throws `UnsupportedOperationException`
  (Liskov/Interface Segregation). A finding if callers use the interface generically; fix: split the interface.
- **Self-built dependency** (`hidden_dependency`): a Spring component creates a service with `new` (Dependency
  Inversion). A finding if this makes tests or replacement harder. Factories that deliberately build instances per
  target (e.g. one HTTP client per kind of target) and plain value objects are intended.
- **Patterns** are named only as a fix, with the concrete gain (strategy instead of a type switch, builder instead of
  a long constructor, template method for identical sequences with different steps). A “missing pattern” without a
  visible problem is no finding.

## Severity
| Severity | Criterion |
|---|---|
| MEDIUM | Readers or people changing the code will probably overlook something: scattered switch, misleading name that is misused, heavily nested method on a frequently changed path |
| LOW | Readability suffers, but misuse is not likely |

No finding of this audit goes above MEDIUM: actual bugs are reported by the idioms & error handling audit.