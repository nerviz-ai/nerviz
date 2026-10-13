---
paths:
  - "**/adapter/in/rest/**"
  - "**/infrastructure/rest/**"
  - "**/adapter/in/graphql/**"
  - "**/infrastructure/graphql/**"
  - "**/infrastructure/config/**"
  - "**/adapter/out/messaging/**"
  - "**/infrastructure/messaging/**"
  - "**/adapter/in/scheduling/**"
  - "**/infrastructure/scheduling/**"
  - "**/adapter/out/http/**"
  - "**/infrastructure/http/**"
status: active
---

# Observability — vendor integration for tracing and metrics

Single principle: **the vendor's plumbing decides where a value comes from; it never
decides what gets written or how.** What gets logged, at what level, and in what shape
is `@.claude/rules/logging.md`'s call — this file only owns the wiring to whatever
tracing/metrics vendor the project has on its classpath.

## Why this is a rule and not a `CLAUDE.md` section

It's a declarative fact with a territory — the globs above — and not a universal fact
about the repository: a project without an inbound adapter isn't touched by it. The
origin of `traceId` used to be decided case by case during implementation, sometimes by
injecting a `Tracer` the project might not have, sometimes by a hand-generated
identifier, and each project ended up with a different answer to the same question.
Decision recorded in
`@.claude/decisions/0024-lessons-learned-001-remediation.md`. Narrowed to vendor
wiring only, splitting off log content and format, in
`@.claude/decisions/0028-logging-rule-and-bootstrap-template.md`.

## Correlation identifier — origin

- **The identifier's origin is the tracing bridge when the project has one**, and only
  in that case. With the bridge on the classpath, the identifier is the active span's,
  and the response is correlatable with the entire distributed trace.
- **Without a bridge on the classpath, the identifier is generated at the point where
  the failure is translated** — a random `UUID` will do. It's a deliberate degradation:
  it correlates the response with this service's log line, and with nothing else.
- **The degradation is written down, not inferred.** The file that generates the
  identifier states in a comment which of the two origins is in use and why. Without
  that, nobody can distinguish a project that chose the degraded path from one that's
  missing configuration.
- **A tracing dependency the project doesn't declare is never injected.** Without the
  bean, the context doesn't start — the application doesn't come up, and the failure
  appears far from the cause.
- The identifier that arrives via a client's header propagates; it's not replaced with a
  new one. Replacing it breaks the chain exactly where it's useful.
- A service without an inbound adapter — message processor, scheduled task — has no
  client to hand the identifier back to. It still uses the message's or the execution's
  identifier as the correlation value; `logging.md`'s admitted exception covers that it
  still gets logged.

This decides where the value in `api-rest.md`'s `traceId` response field and
`logging.md`'s log field comes from — it doesn't decide the response shape or the log
line's format.

## Metrics

- What's measured is what's promised: latency and error rate per exposed operation. A
  counter with no associated alarm is noise with a storage cost.
- Limited cardinality: no resource, user, or request identifier as a metric label — nor a
  value the client chooses, such as a GraphQL operation name. A GraphQL operation is measured by
  its type and outcome; its HTTP status says nothing (`@.claude/rules/api-graphql.md` § Errors).
- The health endpoint is separate from the business endpoint, and neither is
  authenticated with the application's own credentials.
- Vendor-specific annotation (Micrometer's `@Timed`, `@Counted`, a tracing SDK's own
  span annotation) is the only place this rule reaches into application code — never a
  hand-rolled metric that duplicates what the vendor's annotation already gives for
  free.
- **A delivery the design declared must not be lost carries two metrics, and they are not
  optional.** Whenever a component defers an effect to be retried later — a stored intent
  swept by a background pass — the point at which it gives up is a counter, and the age of
  the oldest not-yet-delivered item is a gauge. A give-up path that writes only a log line
  makes the loss silent and permanent, which is precisely the property the deferral was
  bought to avoid; and the counter alone still cannot say delivery is falling behind before
  something is lost. Both belong to the sweeping component, which is the only place that
  knows either number.

## How to verify

```bash
# The correlation identifier reaches the error body and the log — contract test.
./mvnw -q verify

# No tracing injection without the dependency declared: if the bean doesn't exist, the
# context doesn't start, and this command shows it before it reaches production.
./mvnw -q test -Dtest='*ApplicationTests'
```
