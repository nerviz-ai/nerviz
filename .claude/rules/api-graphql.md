---
paths:
  - "**/adapter/in/graphql/**"
  - "**/infrastructure/graphql/**"
  - "**/src/main/resources/graphql/**"
status: active
---

# GraphQL API — schema, resolvers, batching, connections, errors, limits

The inbound GraphQL adapter translates a document into calls to the use cases and their results
back into the shape the client selected. One endpoint answers every operation, the client chooses
how deep and how wide the answer goes, and a failure in one field leaves the rest of the answer
standing. Each of those properties removes a protection the HTTP layer gives a REST endpoint for
free — a path the filter chain can tell apart, a response size the server fixes, a status code a
dashboard counts — and Spring for GraphQL and graphql-java turn none of them back on by default.
This norm says what replaces each one.

Scope: queries and mutations over HTTP, schema first, on the servlet stack. Subscriptions, file
upload, federation, repositories exposed directly as resolvers, generated types and the reactive
stack are outside this norm, and code that needs one of them is a design decision recorded before
it is written. HTTP semantics shared with REST — statuses, Problem Details, `Idempotency-Key` —
are `@.claude/rules/api-rest.md`'s and do not apply here unless this file says so.

## Choosing the inbound protocol

The protocol is chosen per use case, and the choice is recorded with the case. REST stays the
default; GraphQL is chosen when one of these holds and none of the reasons against does:

- **For GraphQL:** clients that need very different shapes of the same data (a mobile screen and an
  admin console over one order); round trips or payload size that matter to the client; one
  answer that aggregates several aggregates or sources; a front end that changes faster than an
  API version can — a new field is additive and needs no version; a backend-for-frontend
- **Against GraphQL:** simple reads and writes every client consumes the same way; a public API
  that must be cached by HTTP or a CDN — the endpoint is POST only; file transfer; strict per-
  endpoint rate limiting; a public third-party API, where the operations clients send cannot be
  known in advance
- **Both:** two inbound adapters over the same input port — REST for the public, cacheable or
  partner-facing operations, GraphQL for the client that shapes its own answers. Each adapter
  enforces access on its own (§ Access); the use case and its rules exist once

Sources: graphql.org Learn (Thinking in Graphs, Performance, Security), AWS "GraphQL vs REST",
Azure Architecture Center "API design". A choice against one of these reasons carries its reason.

## Boundary

- **A resolver calls input ports, nothing else.** The same port a REST controller over the same
  case calls. No repository, no outbound port, no entity manager, and no repository exposed as a
  resolver: a resolver that reads storage skips the use case and its rules
- **The library stays in the adapter.** `graphql.` and `org.springframework.graphql.` appear only in
  the architecture's GraphQL package; a blueprint forbids both in every layer above the adapters
  (`@.claude/rules/architecture-ddd.md` § Adapters)
- **The Java types the schema reads belong to the adapter**, one record per schema type, named
  exactly as the type, mapped by hand from the port's read model. A domain object or a JPA entity
  is never returned to the schema: the default property fetcher reads any getter whose name matches
  a field, and a lazy association is loaded — or fails — after the transaction closed
- **No transaction in a resolver.** Each port call is its own transaction and returns a complete
  read model. A resolver method may run on another thread than the request's (virtual threads, an
  asynchronous return): nothing thread-bound — a transaction, a thread-local — is assumed to
  follow it. Spring Security's context does follow, through context propagation
- Business rules stay in the aggregate; the adapter validates shape only, with bean validation on
  the input record, the same split as `@.claude/rules/api-rest.md` § Errors — 400 family

## Schema

- **Schema first.** The `.graphqls` file under `src/main/resources/graphql/` is the contract,
  written before the resolver, one file per aggregate
- **Schema inspection fails the startup.** Spring Boot logs the inspection report at INFO and
  starts anyway; a field nothing answers then returns `null` in production. The project turns a
  non-empty report into a startup failure, naming each field
- **Nullability is decided per field.** A field that may be denied, or may fail on its own, is
  nullable: a `null` on a non-null field climbs to the nearest nullable parent and erases its
  siblings. A list of a parent's children is non-null with non-null items — empty, never `null`
- **A mutation takes one `input` argument and returns one payload type** named after it
  (`cancelOrder(input: CancelOrderInput!): CancelOrderPayload!`), carrying the changed object and
  `userErrors`
- Names: types and enums in PascalCase, fields and arguments in camelCase, enum values in
  UPPER_SNAKE_CASE, a mutation named verb first. An interface or union member's Java type carries
  the schema type's name, or the type resolver cannot find it
- Money is a decimal written as `String`; an instant is an ISO-8601 `String` in UTC. Never `Float`
  for money, never epoch numbers
- **A partial update distinguishes "absent" from "null".** An input whose fields are optional is
  bound with `ArgumentValue` per field; a plain `@Argument` record cannot tell "leave it" from
  "clear it", and the update erases what the client did not send
- Jackson annotations on an adapter record do not shape the schema's output — the field resolver
  reads accessors. The schema decides names; the record follows them

## Batching

- **A child field that costs I/O is resolved in batches**: `@BatchMapping`, or a loader registered
  in the batch loader registry, calling a batch input port with every parent key of the request at
  once. A `@SchemaMapping` that calls a port once per parent is the N+1 the batch exists to remove
- The batch port takes a set of keys and returns a map. The adapter answers **every** parent: a
  parent missing from the map gets the field's empty value (an empty list), never an absent key on
  a non-null field
- **The parent used as a key has value equality** — a record. A parent whose `equals` is identity
  matches nothing in the returned map
- **The batch size is bounded.** The loader's default is unlimited; the project sets a maximum, and
  the batch port's query is bounded by it
- A batch method takes no field arguments, and a loader caches per key for the whole request. A
  child field whose result depends on an argument is not batched by parent key alone

## Pagination

- **A list that grows is a connection** — Relay cursor connections: `edges { cursor node }` and
  `pageInfo`. The connection types are generated from the `Connection` suffix and not written by
  hand. A list that grows without bound is never a plain list field
- **The page size has a ceiling.** `first` defaults to the project's default page size and may not
  exceed its maximum; above it is a `BAD_REQUEST`, never a shorter page — the client would believe
  it saw everything. The same contract as `@.claude/rules/api-rest.md` § Pagination
- **Only the directions the client needs are declared.** Forward only is `first` and `after`;
  `last` and `before` are added when a client pages backwards, not by default
- **The cursor carries the ordering key**, never an offset, and the order ends with a unique key.
  Its encoding is the library's; the adapter converts it to the port's own cursor type, and the
  port never sees a connection type. No total count unless counting is cheap

## Errors

The taxonomy is `@.claude/rules/error-handling.md`; this section is how each family reaches a
GraphQL client.

- **Errors the client can correct in the same operation are data.** On a mutation, a business rule
  violation, a conflict and an unknown target come back in the payload's `userErrors`, each with
  the input path it concerns (`field`), the domain `errorCode` (`code`) and a message, and the
  payload's object is `null`. Never a top-level error for them
- **Everything else is a top-level error with a classification and an `errorCode` extension**, by
  one resolver in the adapter:

  | Family | Classification | Extensions |
  |---|---|---|
  | Not found, on a query | `NOT_FOUND` — the field is `null` | `errorCode` |
  | Validation, business rule, conflict reaching a query | `BAD_REQUEST` | `errorCode` |
  | Bean validation of an input | `BAD_REQUEST` | `errorCode: INVALID_INPUT`, one violation per field |
  | Integration families | `INTERNAL_ERROR` | `errorCode` of the dependency, `retryable` (only for an unavailable dependency), the announced wait when there is one |
  | Unauthenticated, denied | `UNAUTHORIZED`, `FORBIDDEN` | the security integration's own |
  | Anything else | `INTERNAL_ERROR` — the execution id as its only text | none |

- **The resolver resolves only what it owns and returns nothing for the rest.** A catch-all turns
  every access denial into an internal error, and is the one way the resolver leaks an exception's
  message. An unknown exception reaches the library's fallback, which hides the message and logs it
  with the stack trace
- Errors in the document itself — a parse error, an unknown field, a wrong argument type, a limit
  exceeded — are raised before any resolver runs; their shape is the library's
- **Every execution failure is HTTP 200.** HTTP 400 appears only for a request error to a client
  that accepts `application/graphql-response+json`. Monitoring counts the outcome of the operation
  (§ Observability), never the HTTP status

## Access

Where each check lives is `@.claude/rules/authorization.md`; GraphQL changes how it is applied.

- **The endpoint is authenticated in the filter chain, and every operation carries its own check.**
  The chain sees one `POST /graphql` and cannot tell operations apart: a role, a group or a scope is
  method security on the resolver method, the same named annotation a REST controller over the
  same port uses
- **A sensitive field is guarded on the field**, by its own resolver method carrying the check —
  not only on the query that usually reaches it. Every path through the graph to a sensitive type
  or field is listed with its check: a role on the root query does not protect the same object
  reached through another type's field
- A denied field answers `null` with a `FORBIDDEN` error and the rest of the answer stands — the
  field is nullable (§ Schema)
- A check on a batch method runs once for the batch, not per parent. "Only the owner" stays in the
  use case, per item
- An operation callable without authentication is not served by this endpoint while it is
  authenticated in the chain; opening the endpoint moves deny-by-default onto every resolver and is
  a design decision recorded first

## Demand limits

Each limit is a project decision written once, with its value and the reason for it, and changed
only with a recorded reason. None is on by default.

- **Depth**: a query nested deeper than the maximum is rejected before any field runs
- **Complexity**: a query whose cost exceeds the maximum is rejected before any field runs. A field
  that declares `first` multiplies its children's cost by the page it asks for; every other field
  costs one plus its children. An alias repeating a field costs it again
- **Time**: the whole request has a timeout; a slow port does not hold a request thread unbounded
- **Page size and batch size**: § Pagination and § Batching
- **Introspection is off outside development**, and the development tool page (GraphiQL) is on only
  in the development profile. Turning introspection off is JVM-wide in graphql-java: a test context
  that starts with it off leaves it off for every later context in the same JVM
- **CORS** allows `POST` and the origins the environment names — never `*`, and none by default
- **A quota per caller and a concurrency cap** exist only when the use case states them; the quota
  covers the whole endpoint and is checked before the document is executed —
  `@.claude/rules/inbound-admission.md`
- The parser's own limits (tokens, characters) stay at their defaults unless a document legitimately
  exceeds them

## Evolution

- **A field, argument, enum value or type leaves in two releases**: first `@deprecated(reason:)`
  naming the replacement, then removed in a later release. Never removed, or changed in a way a
  client cannot absorb — an argument or input field made required, an output field made nullable, a
  type changed — in the same change that deprecates it
- **A new field is additive and needs no version.** GraphQL has no `/v2`; a breaking redesign is a
  new field or type, and the old one is deprecated
- **The schema the application serves is compared with the last one shipped**, committed as a
  snapshot outside the test classpath: a removal or a change a client cannot absorb that skipped
  deprecation fails the build, and any change requires the snapshot to be refreshed in the same change

## Observability

- **Metrics are per operation type and outcome.** The library's observations carry
  `graphql.operation.type` and `graphql.outcome` as low-cardinality keys; the alarm reads the
  outcome, never the HTTP status (§ Errors)
- **`graphql.operation.name` is chosen by the client** — high cardinality, and spoofable. It may
  reach a trace; it is never a metric tag, `@.claude/rules/observability.md` § Metrics
- A batch loader's calls are observed by the library; a field resolver that calls a port outside a
  batch shows up as many short spans under one request — the visible shape of an N+1

## Personal data

Every output field of a type is walked through `@.claude/rules/logging.md` § Masking candidates, and
each match gets the decision `@.claude/rules/personal-data.md` § In transit requires — full value
with its receiver, reduced, or a reference — recorded with the design. A field that crosses in
clear to anyone who may run the operation also carries a field-level check (§ Access).

## Tests

- **A GraphQL slice test** proves the schema mapping and the errors: each field mapped, each family
  classified with its `errorCode`, `userErrors` on a mutation, invalid input never reaching the
  port, a page above the ceiling refused — and **one batch port call per request** for every
  batched field, with every parent key. The slice has no filter chain and no method security: a
  test there proves nothing about access
- **An HTTP test through the real server and filter chain** proves access and the limits: anonymous
  rejected by the chain, each sensitive field `null` with `FORBIDDEN` without the role and present
  with it, a query over the depth limit rejected before any port is called, introspection refused
  outside development. Each asserts the error's classification, not only that there are errors
- **A schema compatibility test** compares the served schema with the committed snapshot (§ Evolution)
- The statement count of a batch port's query is the persistence integration test's, not this
  adapter's
- Test levels, slices and names are `@.claude/rules/testing.md`

## How to verify

```bash
# The GraphQL library only in the GraphQL package. Zero lines is the expected result.
grep -rln "import graphql\.\|import org.springframework.graphql\." --include='*.java' src/main \
  | grep -v "/graphql/"

# No repository exposed as a resolver, no transaction in a resolver. Zero lines is the expected result.
grep -rlnE "@GraphQlRepository|@Transactional" --include='*.java' src/main | grep "/graphql/"

# Every child list resolved per parent is a candidate N+1. Review each hit against the batch rule.
grep -rn "@SchemaMapping" --include='*.java' src/main

# Introspection and GraphiQL: off in the base configuration. Review that each `true` sits in a dev profile.
grep -rnE "introspection:|graphiql:" -A1 src/main/resources | grep -E "enabled:\s*true"

# The demand limits exist. Each one is expected once.
grep -rlnE "MaxQueryDepthInstrumentation|MaxQueryComplexityInstrumentation|TimeoutWebGraphQlInterceptor|setMaxBatchSize" \
  --include='*.java' src/main

# The schema snapshot exists outside the test classpath.
ls src/test/graphql/schema.snapshot.graphqls
```
