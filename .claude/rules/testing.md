---
paths:
  - "**/src/test/**"
status: active
---

# Tests — pyramid, slices, data, coverage

A test exists to fail when behavior changes. A test that passes with the rule inverted
is a coverage line, not a test.

## Why this is a rule and not a `CLAUDE.md` section

It's a declarative fact with an identifiable territory — the `paths` glob above — and
not a universal fact about the repository. It auto-loads when someone touches test
code, and costs zero context in sessions that don't touch it. Decision recorded in
`@.claude/decisions/0008-testing-rule-and-design.md`.

## Pyramid

Four levels. Each answers a different question, and the one above never repeats the one
below.

| Level | Verifies | Starts Spring context | Touches I/O |
|---|---|---|---|
| Domain unit | Aggregate invariant, value object, calculation | No | No |
| Application unit | Use case orchestration, with ports replaced by doubles | No | No |
| Integration | That the adapter talks to the real world — database, outbound HTTP | Yes, the minimum | Yes |
| Architecture | Dependency direction, naming conventions, boundaries | No | No |

- The base is the domain. A business rule tested only through HTTP is a rule nobody can
  debug when it fails.
- One behavior has a test at **one** level. The same scenario repeated at three levels
  triples the maintenance cost and adds no information.
- An integration test exists for what only fails against the real world: mapping, SQL,
  serialization, transactions. A business rule isn't an integration case.

## Slices and context

- The smallest context that answers the question. A full `@SpringBootTest` is the last
  resort, not the first.
- Slice per layer: web for the inbound adapter, persistence for the outbound one.
  Outside the slice, the collaborator is a double.
- `@SpringBootTest` with a real `webEnvironment` is reserved for the startup test: that
  the graph assembles and the configuration is valid. One per application is enough, plus
  the transport proof of `@.claude/rules/transport-security.md` § Tests, which needs a socket
  for the same reason.
- Its own test profile, never the production one, **active on every `@SpringBootTest`**
  through `@ActiveProfiles("test")`. A profile no test activates never loads:
  `application-test.yml` is then a file of switches nothing reads, and every scheduled
  job it turns off runs under test anyway. Secrets never go into a versioned file
  (`@.claude/rules/secrets.md`).
- Zero `Thread.sleep` to wait for something async. Wait on a condition, with a declared
  timeout.

## Doubles

- A double only for what crosses the boundary: outbound port, clock, id generator,
  external system.
- **Never a double of what's under test.** An aggregate with its own method replaced is
  a test of the replacement.
- Value objects and aggregates are used for real, even in an application test. They're
  cheap to build and it's the real constructor that validates.
- Interaction verification (which method was called) only when the effect is the call.
  If there's observable state, assert the state.
- Time enters via an injected clock, with a fixed instant. A test that compares against
  the current instant fails at 3 a.m.

## Names and shape

- File: `<ClassUnderTest>Test`. Integration test: `<ClassUnderTest>IT` — it's the
  suffix failsafe picks up, and without it the test doesn't run in `verify`. A test of
  the application as a whole — the context loading, the module structure — has no single
  class under test and ends in `Tests` (`<Name>ApplicationTests`, `ModularityTests`):
  both build tools run that suffix as a unit test.
- The method name describes the expected behavior, not the method called:
  `rejectsEmailWithoutAtSign`, not `testValidate`.
- One test, one behavior assertion. Several assertions about the same result are
  acceptable; several scenarios in the same method are not.
- Visible structure: arrange, act, assert. Separated by a blank line, with no comment
  announcing them.
- Assert the exception **and** its stable code, never just the type. Two different
  rules that throw the same type pass a test that looks only at the type —
  `@.claude/rules/error-handling.md`.
- The lambda of a throw assertion — AssertJ's `assertThatThrownBy`, `isThrownBy`,
  `assertThatCode`, `catchThrowable`, `catchThrowableOfType`, `catchException`, JUnit's
  `assertThrows`, `assertThrowsExactly` — makes **exactly one call**, a method or a
  constructor: the call under test. Its arguments are built in the arrange block, and a
  chained call (`find(id).orElseThrow()`) is two. A builder or factory that throws inside
  the lambda passes the test for the wrong reason. `config/checkstyle/checkstyle-test.xml`
  fails the build on it, check `OneCallInThrowLambda`; its list of names and this one are
  the same.

## Assertions

- `allSatisfy`, `allMatch` and `noneMatch` are preceded by `isNotEmpty()` (or an exact
  `hasSize`) unless an empty collection is itself a valid pass. On an empty collection
  they pass vacuously — the misconfiguration the test exists to catch.
- One `assertThat` per subject, chained: `assertThat(x).isNotNull().contains(…)`, not two
  statements about the same `x`.
- The dedicated assertion over `isEqualTo` on a derived value: `hasToString(…)` not
  `assertThat(x.toString()).isEqualTo(…)`, `hasSize(n)` not `assertThat(list.size())`.
- AssertJ, Mockito and BDDMockito entry points are statically imported —
  `verify(…)`, never `org.mockito.Mockito.verify(…)` spelled out.
- Cases that differ only in their input are one `@ParameterizedTest`, not N copies of a
  method.
- A fixture method that exists only to carry an annotation says so in a comment inside
  its empty body.

## Asynchronous effects

- A test that waits for something asynchronous — a record on a topic, a row a scheduled
  pass writes, a relay's mark — waits with **Awaitility**
  (`await().atMost(…).untilAsserted(…)`), declared as a test dependency with no version:
  the Spring Boot parent manages it. Never a hand-rolled deadline loop over
  `System.currentTimeMillis()`, never `Thread.sleep`: the loop that returns from its first
  iteration, or sleeps a fixed guess, is how a test passes without waiting for anything.

## Test data

- Explicit construction in the test, with only the fields the scenario cares about
  being significant.
- Shared factory when construction is noisy; the factory returns the valid case and the
  test changes the field in question. Named `<Aggregate>Fixtures` and exposes methods,
  not loose constants.
- Zero dependency between tests: none reads what another wrote, no order is assumed.
- Shared state is cleaned up via reverted transaction or truncation, never by hope.
- Literal values in the test, not calculated constants: the test states what it expects,
  without the reader having to derive it.

## Database in integration tests

- **Against the real engine, on the same version as production**, with an ephemeral
  container per run. An in-memory database doesn't count: the dialect diverges and a
  migration that passes there breaks in production.
- Migrations run in the test, in the same order as in production. A schema created by
  auto-generation tests a database that doesn't exist —
  `@.claude/rules/persistence.md`.
- Container reused across classes within the same run; data reset between tests.
- The integration test never silently degrades to a different database. Without
  Docker, it either doesn't run, or it fails — never runs against a different engine.
- **Locally**, guarding the class with `@EnabledIf` over
  `DockerClientFactory.instance().isDockerAvailable()` is admitted: on a machine
  without Docker a red result says nothing about the code, and the daily false
  negative trains whoever sees it to ignore the build.
- **In CI, Docker is mandatory and the absence of a container is a failure.** The
  pipeline's `verify` runs with Docker, and the pipeline fails if any integration test
  was skipped, or if none ran. A class the guard turns off is reported as skipped, not
  as zero tests. A total alone misses it as soon as one integration test needs no
  container, and the guard then becomes the silent degradation the first point forbids.
  The count comes from the build tool's aggregate, never from one report per class:
  the enclosing class of `@Nested` tests reports zero while its nested classes run.

## Coverage

- A gate in the build, not a report to read: **80% lines and 70% branches**, measured
  on the project's aggregate. Below that the build fails.
- Branches count because they catch the condition whose false case wasn't tested. Lines
  alone pass with a test that walks through without asserting anything.
- Excluded from the calculation: the application's startup class and the configuration
  classes. Nothing else — DTOs and persistence entities count.
- The number is the minimum, not the target. Going from 80 to 95 by writing accessor
  tests is pretend work; the right question is which invariant still has no named test.
- High coverage with weak assertions is the worst possible state: it gives confidence
  without giving protection.

## Architecture tests

- Verify what the compiler doesn't verify when layers are packages within the same
  module: dependency direction, naming conventions, dependency fields.
- Live in the module that sees every class — the one that contains the startup class.
- One rule per boundary the project **declares**. An invented boundary is a rule nobody
  can fix when it fails.
- A rule that fails because it finds no class at all gets deleted, not disabled.
  Disabling the failure on an empty set makes the rule pass on the day the package
  disappears.
- What they verify lives in other rules: `@.claude/rules/architecture-ddd.md`,
  `@.claude/rules/naming.md`, `@.claude/rules/code-quality.md`. This rule doesn't repeat
  any of them.

## How to verify

```bash
# Unit and architecture tests.
./mvnw -q test

# Integration included, plus the coverage gate.
./mvnw -q verify

# verify exits 0 without running any integration test when the file suffix is wrong.
# Read the count, not just the exit code.
grep -rl "class .*IT" --include='*.java' src/test/
```
