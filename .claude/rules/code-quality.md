---
paths:
  - "**/src/**/*.java"
status: active
---

# Code quality — SOLID and Clean Code

Covers only what no other rule already covers. Layer SRP and DIP live in
`@.claude/rules/architecture-ddd.md`; names in `@.claude/rules/naming.md`; exceptions in
`@.claude/rules/error-handling.md`. Don't repeat them here.

## SOLID — what's missing

- **OCP** — behavior that varies by case enters as a new implementation of an existing
  abstraction, not as a new `if`/`switch` inside the existing one. Applies once the axis
  has already varied once; before that it's speculation.
- **LSP** — a subtype doesn't tighten a precondition, doesn't relax a postcondition,
  doesn't throw where the base doesn't throw. An inherited method that throws
  `UnsupportedOperationException` is a violation: the hierarchy is wrong, not the
  caller.
- **ISP** — the client depends only on what it uses. An interface with methods that half
  the implementations leave empty splits in two.

## Clean Code — hard limits

- Function: one level of abstraction, ≤ 20 lines, ≤ 3 parameters, cyclomatic complexity
  ≤ 10. A boolean parameter is forbidden — that's two functions with different names.
- Class: ≤ 200 lines. Fields: `private final` by default.
- Guard clause first; no `else` after `return`; nesting ≤ 2 levels.
- No magic numbers or strings — named constant or value object.
- No `null` crossing a boundary: `Optional` on a query's return only, never on a field,
  parameter, or collection. Empty collection, never `null`.
- Mutability is an explicit decision: `record` or immutable class by default.
- A hidden side effect is a bug — a function whose name says it queries doesn't write.

## Branching on a closed set

A closed set is an enum you own or a sealed type. A branch over one goes to exactly one of
three places, the first that fits:

1. **The type itself** — the behavior needs nothing outside the value. Data that differs per
   constant is a constructor field; behavior is a method, with a body per constant or an
   interface the enum implements. A question about the value is a method of the value
   (`status.isFinal()`): asked in two places, or answered by comparing more than one
   constant, it never stays an `==` at the call site. `@.claude/rules/value-objects.md`
   § The criterion 4 is the same idea for a value object.
2. **An exhaustive `switch` expression where the behavior lives** — the behavior needs a
   collaborator (a port, a clock, a repository), or belongs to a layer the type must not
   know: an adapter mapping a domain type to its own shape, a use case reacting to a result.
   The domain type never learns about its adapters. The same `switch` in two adapters that
   map to different shapes is not duplication; the same one twice in one layer, computing
   the same thing, moves into the type.
3. **Strategy** — an interface with one implementation per variant, chosen at run time —
   once the variants grow, each brings its own dependencies, or a new one arrives without
   editing the set (OCP above). Before the axis has varied once, it is speculation.

How the `switch` is written:

- A `switch` that computes a value is an **expression**: each arm yields the value or
  builds the result. Locals declared before it, `null` or a placeholder, and assigned in its
  arms are forbidden — that is a computed value written as mutation, and the compiler no
  longer checks every arm assigns. The statement form stays only when every arm performs a
  side effect and nothing is computed.
- Over a closed set, a `switch` expression — or a statement with pattern labels — lists
  every constant or permitted subtype and has **no `default`**: exhaustiveness is the
  compiler's job, and a `default` switches it off — the next constant or subtype compiles
  and falls into the `default` in silence. Checkstyle's `MissingSwitchDefault` skips both
  shapes; it never requires that `default`. A statement over an enum with constant labels
  is the one shape the compiler does not check: Checkstyle requires its `default`, and that
  `default` throws `IllegalStateException`. A switch over an open type (`String`, an enum
  you don't own, a non-sealed interface) keeps its `default`.

## Comments

Javadoc is the only comment. Code that needs a comment to be understood has the wrong
name — rename it, or extract a method whose name says what the comment said. A reason
that must survive the next edit goes in the Javadoc of the type or method it explains. No
`//` and no `/* */`, on a line of their own or at the end of one, and no `/** */` inside a
method body.

Two exceptions, and nothing else:

- **An otherwise empty body** — one `//` line saying why it is empty. An empty block with
  no text is a violation of its own (Sonar S108, S1186). Where a test fixture must carry
  one, `@.claude/rules/testing.md` says so. Never a `catch`: a comment is not a handler —
  `@.claude/rules/error-handling.md` § A caught exception is never silent.
- **A tool directive** — `// NOSONAR`, `// CHECKSTYLE:OFF`/`ON`, `// spotless:off`/`on`,
  `// @formatter:off`/`on`. It addresses a tool, not a reader.

Forbidden as well: commented-out code, a `TODO` in any form — the work it names belongs in
the issue tracker — and Javadoc that repeats the signature.

## How to verify

A rule in markdown doesn't enforce anything. What's mechanical lives in the generated
project's build:

- **Checkstyle** (`config/checkstyle/checkstyle.xml`, `validate` phase) — length,
  parameters, complexity, nesting, magic numbers, a string literal of 5 or more characters
  repeated 3 or more times in one file (outside annotations), generic `catch`, an empty
  `catch` even with a comment (`error-handling.md`'s check), and § Comments: a `//`
  or `/* */` outside its two exceptions, a `/** */` inside a body, a `TODO`. The numbers
  and the exceptions in that file and in this rule are the same: changing one side
  without the other is a bug. Test code has its own light set,
  `config/checkstyle/checkstyle-test.xml`, at `verify`: these size limits and the comment
  check apply to production code only — in test code § Comments is held by review. It
  holds the restricted identifiers and the one-call lambda of
  `@.claude/rules/testing.md` § Names and shape
- **Spotless** (`spotless:check` at `verify`; part of `check` in Gradle) — formatting and
  unused imports, main and test alike
- **ArchUnit** (`ArchitectureTest.java` in the main module) — boundaries, ISP, `private
  final` fields in the domain, generic `throw`, field injection
- Human review only for OCP, LSP, abstraction level, and the boolean parameter
