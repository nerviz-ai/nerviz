# Block G: GraphQL (steps G1-G3, conditional)

**Runs only when the spec carries the GraphQL block** — `grep -m1 -E '^## [0-9.]+ GraphQL' <spec>`,
and the block is not `none`. No match, or `none` → skip to Block S. Read the GraphQL block of
spec.md, then `31-graphql.md`, then `.claude/rules/api-graphql.md`. It runs right after Block 3
because its resolvers call the same input ports the REST controllers call, and right before
Block S because the security annotations go on both. The exemplars are in
`.claude/skills/graphql-api-architect/templates/`: `schema.graphqls.example`,
`GraphQlController.java.example`, `GraphQlConfig.java.example`,
`GraphQlExceptionResolver.java.example`, `application-graphql.yml.example`.

- **G1 · Shared setup** — when § 6 of `31-graphql.md` is NEW: in `[graphql]`, `GraphQlLimits`,
  `GraphQlConfig` (depth, complexity with only `first`-declaring fields multiplying, timeout, the
  batch registry's max size, the schema inspection that fails the startup) and
  `GraphQlExceptionResolver` (the families of § 7, `null` for the rest — never a catch-all); the
  properties of § 12 in `application.yml`; the dependencies of § 11 in `pom.xml`, version empty.
  Inherited → nothing here but a new property § 12 adds. § 6's `Admission` rows not `none` → the
  admission pieces Block 3 lists, in `[graphql]`'s `admission` subpackage when the project has no
  REST adapter (Block 3 skipped): the `graphql` quota entry, `@Bulkhead(name = "inbound-<slug>")`
  on the root field's method, `SERVICE_BUSY` in `GraphQlExceptionResolver`, and the 429 handler in
  an `ApiExceptionHandler` — the quota is refused before GraphQL runs
- **G2 · Schema and resolvers** — the types of § 2 in `src/main/resources/graphql/<aggregate>.graphqls`,
  extending an existing type additively, never redeclaring it. In `[graphql]`: one record per
  schema type, named as the type; the mapper from the port's read model; the controller with one
  method per root field of § 1, calling only input ports; every child field of § 5 as a
  `@BatchMapping` over its batch port, answering every parent; every connection of § 4 through
  `ScrollSubrange` and `Window`, the page checked against § 6's ceiling; every mutation catching
  exactly the families § 7 lands in `userErrors`. Access annotations are Block S's
- **G3 · Snapshot** — `src/test/graphql/schema.snapshot.graphqls` refreshed from the schema the
  application serves: the schema test of Block 4 writes it to `target/graphql/` and fails until it
  is copied. Never under `src/test/resources/`

Rules: `.claude/rules/api-graphql.md`, `.claude/rules/error-handling.md`,
`.claude/rules/architecture-ddd.md` § Adapters, `.claude/rules/personal-data.md` § In transit,
`.claude/rules/logging.md`.

Validation, each item checkable:

- the greps of `.claude/rules/api-graphql.md` § How to verify return what that section says;
- no class outside `[graphql]` imports `graphql.` or `org.springframework.graphql.`, and no
  controller method calls anything but an input port;
- every schema list that grows is a `Connection`, every child field of § 5 has a `@BatchMapping`,
  and the context starts — the schema inspection fails it on a field nothing answers.

Compilation: `./mvnw -q test-compile` (or `-pl <module that holds [graphql]>` in multi-module) ✅

**Line in the final report:**
```
✅ Block G: GraphQL complete (steps G1-G3)
- Root fields: Query.order, Query.myOrders (connection, max 100), Mutation.cancelOrder (userErrors)
- Batched: Order.lines, Order.customer
- Shared: GraphQlLimits, GraphQlConfig, GraphQlExceptionResolver (NEW)
- Files: 9 classes, 1 schema file, 0 errors
```
