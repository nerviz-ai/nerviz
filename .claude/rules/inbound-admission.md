---
paths:
  - "**/adapter/in/rest/**"
  - "**/adapter/in/graphql/**"
  - "**/infrastructure/graphql/**"
status: active
---

# Inbound admission — a quota per caller and a concurrency cap, at the entry boundary

Code that refuses a request before the use case runs, because the caller has spent its quota or
too many requests are already in flight. Both protect the same scarce resource — whatever the use
case holds while it runs — and both fail silently when the defaults are kept: a limiter that
waits holds what it was bought to free, a limiter per caller in an
unbounded registry is a memory leak keyed by the client, a cap named like an outbound one shares
its permits, and a rejection without `Retry-After` makes every client retry at once.

## When a limit exists

- **Only a limit the business stated for the use case**: a quota per caller (so many requests per
  window) or a cap (so many at the same time), or both. No stated limit, no limiter — a number
  nobody stated is a guess that later reads as a requirement
- **Where the quota is enforced is a project fact, recorded once**: in the application, or at the
  edge (an ingress, an API gateway). At the edge the application writes no limiter and records the
  number and the key the edge enforces; the cap stays in the application, since only the
  application knows what a request holds
- A limit applies per use case. A case with no limit is never covered by another case's limiter

## The caller

- **The key is who the caller is**, never what the caller says: the authenticated principal's name
  when the endpoint requires authentication; the API key's identifier when the caller authenticates
  by key; the client address only for a public endpoint — read through the forwarded headers only
  when the trusted proxies are configured (`@.claude/rules/transport-security.md`), the remote
  address otherwise. Never a request header or parameter the client sets freely
- **The quota is checked after authentication and before the handler.** An unauthenticated request
  is refused first, so a 401 never spends a caller's quota and an anonymous flood never exhausts an
  authenticated one's

## Quota

- **One limiter per limiter name and caller**, never one shared by every caller — a shared limiter
  is a cap measured in time, and one noisy caller then exhausts everyone's window
- **The limiters live in a bounded cache**: a maximum number of callers, and an expiry after last
  access no shorter than the longest window. A shorter expiry hands an evicted caller a fresh
  quota early; no bound turns every distinct client into retained memory. Never an unbounded
  registry of limiters
- **No wait.** A request over its quota is rejected at once; a limiter that waits for a permit
  holds the request thread, the resource the limit protects
- **Per instance.** Each replica enforces the quota divided by the replica count, rounded up, and
  the replica count is recorded next to the quota. With an uneven balancer the result is
  approximate — a caller may pass its quota or be cut early. A quota exact across replicas is the
  edge's
- On REST the quota belongs to a handler, named after its use case. On GraphQL it belongs to the
  whole endpoint, per caller, checked before the document is executed — never per field and never
  per operation cost

## Concurrency cap

- **A semaphore with no wait**, one instance per use case, named `inbound-<use case>` — never a
  name an outbound instance uses: those are named after the dependency, and two callers of one
  name share its permits
- **On the adapter's handler method** (the REST handler, the GraphQL controller method), never on
  a use case, an aggregate or a port — `@.claude/rules/architecture-ddd.md` § Adapters
- **Sized below the scarcest resource the use case holds while it runs**, with the reason
  recorded: the request thread pool on platform threads, the database or client connection pool
  on virtual threads, where the request pool bounds nothing (`@.claude/rules/virtual-threads.md`).
  A cap as large as that resource protects nothing
- **One resilience library per project**, inbound and outbound — two libraries mean two
  configurations, two metric shapes and two meanings of "full"
- **The cap runs before the idempotency claim.** A rejected call never claims an idempotency key
  (`@.claude/rules/api-rest.md` § Idempotency): the idempotency aspect declares no precedence
  higher than the cap's

## Rejection

- **An admission rejection is neither a domain family nor an integration family**
  (`@.claude/rules/error-handling.md`): nothing failed in the domain and no dependency was called.
  It belongs to the inbound adapter. The quota throws its own exception from the limiter; the
  resilience library's full-cap exception is mapped as it arrives — an outbound full cap never
  reaches the inbound adapter, because the outbound adapter translates it
  (`@.claude/rules/http-client.md` § Failures)
- **REST**: quota → 429, `errorCode` `RATE_LIMITED`, `Retry-After` the window rounded up to the
  second; cap → 503, `errorCode` `SERVICE_BUSY`, `Retry-After` the project's recorded wait, with
  the 5xx `traceId` and generic `detail` (`@.claude/rules/api-rest.md` § Errors — 500 family). Both
  Problem Details
- **GraphQL**: quota → HTTP 429 with the same Problem Details body, before any GraphQL response
  exists; cap → a top-level error, `INTERNAL_ERROR`, `errorCode` `SERVICE_BUSY`, `retryable`, the
  wait in `retryAfterSeconds`, HTTP 200 (`@.claude/rules/api-graphql.md` § Errors)
- **`Retry-After` on every admission rejection**, never absent: without it every rejected client
  retries on its own clock, and they synchronize

## Observability

- **Rejections are read from what is already measured**: the server request metric by status (429,
  503) and the GraphQL operation outcome. No hand-written rejection counter
  (`@.claude/rules/observability.md` § Metrics)
- The cap's instances are bound to the metric registry — bounded by the number of use cases. A
  per-caller limiter never is: its name or key as a label is one series per client

## Contract

- A limited operation documents its 429 or 503, with `Retry-After`, in the OpenAPI document
- The recorded quota, replica count, key and wait live with the design; the configuration holds
  the values, and a change of either changes both

## Tests

- **Against a real server with the full context.** The cap's aspect is not part of a web slice
  test, and a slice test that passes there proves nothing about the cap
- Over quota → 429 with `Retry-After` and `RATE_LIMITED`; another caller keeps its quota; an
  unauthenticated request answers 401 and spends nothing; cap full → 503 with `Retry-After` and no
  idempotency key claimed; GraphQL over quota → HTTP 429 before execution; GraphQL cap full →
  `SERVICE_BUSY`

## How to verify

```bash
# No limiter registry holds a per-caller limiter. Zero lines is the expected result.
grep -rn "RateLimiterRegistry" --include='*.java' src/main

# Every inbound cap is named in the inbound namespace. Review any hit.
grep -rn "@Bulkhead(" --include='*.java' src/main | grep -E "/(rest|graphql)/" | grep -v 'name = "inbound-'

# Inbound caps never wait. Review any hit that is not 0.
grep -rnA3 "inbound-" src/main/resources/application*.yml | grep -i "max-wait-duration"

# The idempotency aspect claims no precedence over the cap. Review any hit.
grep -rn "@Order" --include='*.java' src/main | grep -i "idempot"

# Both rejections carry Retry-After. Expected in each handler that maps one.
grep -rn "RETRY_AFTER\|retryAfterSeconds" --include='*.java' src/main
```
