---
paths:
  - "**/adapter/in/rest/**"
# --- below: repo convention, ignored by the runtime ---
# Who verifies this rule: see `@.claude/rules/00-index.md`
status: active
---

# REST API — resources, verbs, statuses, errors, versioning, pagination, idempotency

The inbound adapter translates HTTP into the use case and the result back again. It does
not decide business rules (`@.claude/rules/architecture-ddd.md`), does not invent an
error taxonomy (`@.claude/rules/error-handling.md`), does not choose class names
(`@.claude/rules/naming.md`). This rule decides only the shape of the exposed contract.

Principle: **the client distinguishes what happened by the status; the body explains,
never replaces.** Wrong status with a pretty body is a bug.

## Resources and URIs

- Plural noun, `kebab-case`, no verb: `/orders`, `/orders/{orderId}/items`.
- Opaque identifier in the URI. Never a technical database key exposed as contract.
- Nesting ≤ 2 levels. Deeper than that, the sub-resource becomes a top-level resource.
- An action that isn't CRUD and doesn't model a resource becomes an action sub-resource:
  `POST /orders/{orderId}/confirm`. It's the only verb form allowed in a URI.
- A query with criteria too large for the query string: `POST /orders/search`, with an
  ADR recording the exception — it's a read via `POST`, which contradicts the table
  below's semantics.
- Major version at the start of the path: `/api/v1/orders` — § Versioning.

## Verbs

| Verb | Semantics | Body in request | Safe | Idempotent |
|---|---|---|---|---|
| `GET` | Reads resource or collection | No | Yes | Yes |
| `POST` | Creates a resource or triggers an action | Yes | No | Not by default — see Idempotency |
| `PUT` | Replaces the whole representation | Yes, complete | No | Yes |
| `PATCH` | Changes part of the representation | Yes, partial | No | Yes |
| `DELETE` | Removes the resource | No | Yes for the final state | Yes |

- `GET` never changes state, nor a counter, nor read state. If it changes something, it's
  `POST`.
- `PUT` receives the complete representation: a missing field is a deleted field. Whoever
  wants to send only what changed uses `PATCH`.
- `PATCH` uses JSON Merge Patch (RFC 7386), `Content-Type: application/merge-patch+json`.
  `null` deletes the field, an absent field stays intact. JSON Patch (RFC 6902) only with
  an ADR: relative operations break the idempotency in the table.
- Repeated `DELETE` must give the same final state. A second call is not a business
  error.

## Success statuses

| Scenario | Status | Required in the response |
|---|---|---|
| `GET` of an existing resource | 200 | Representation |
| `GET` of a collection, including empty | 200 | Page envelope — never 204 |
| `POST` that creates a resource | 201 | `Location` header + created representation |
| `POST` of an action completed within the request | 200 | New state of the resource |
| `POST` of an action accepted for later processing | 202 | `Location` of the status resource |
| `POST` repeated with the same idempotency key | Same as original | Stored body |
| `PUT` that replaces an existing resource | 200 | Resulting representation |
| `PUT` that replaces and there's nothing to return | 204 | No body |
| `PUT` that creates (only when the id comes from the client) | 201 | `Location` |
| `PATCH` applied | 200 | Resulting representation |
| `DELETE` executed, or resource already nonexistent | 204 | No body |

`Location` is a relative URI (RFC 9110 allows it), built from the path the request itself
arrived on plus the new identifier — so it carries the version the client called, and
never a second spelling of the path. A relative URI needs no forwarded-header
configuration behind a proxy; an absolute one built from the request leaks the internal
host without it.

`DELETE` of a nonexistent resource is 204, not 404: the choice applies to the whole API,
not to each endpoint. An endpoint that returns 200 where its neighbor returns 204 in the
same scenario is a contract bug.

## Errors — 400 family

Distinction that decides almost every case: **400 is shape, 422 is rule.** A body that
doesn't parse, has the wrong type, or is missing a required field never reaches the
domain — it's 400. A valid body that the aggregate rejects is 422.

| Scenario | Status |
|---|---|
| Malformed JSON, incompatible type, unknown enum | 400 |
| Bean validation on the DTO, invalid query or path param | 400 |
| Required `Idempotency-Key` missing or malformed | 400 |
| API version not supported — never offered, or past its sunset | 400 |
| No credential, invalid or expired credential | 401 |
| Authenticated, no permission, and the resource's existence isn't sensitive | 403 |
| Resource doesn't exist — or exists and revealing it would be an information leak | 404 |
| Route exists, verb isn't supported on it | 405 |
| `Accept` that no representation satisfies | 406 |
| Duplicate, invalid state transition, outdated version | 409 |
| Resource permanently removed and the version still exists | 410 |
| `If-Match` present and outdated | 412 |
| Unsupported `Content-Type` | 415 |
| Well-formed input, violates a business rule | 422 |
| Write that requires `If-Match` and arrived without it | 428 |
| Request rate limit exceeded — the caller's quota for the operation is spent (`@.claude/rules/inbound-admission.md`) | 429 + `Retry-After` |

Prefer 404 over 403 when the resource's existence is sensitive information. The choice
applies to the whole resource, not to the request — alternating between the two reveals
the existence all the same.

## Errors — 500 family

| Scenario | Status |
|---|---|
| Unmapped exception — our bug | 500 |
| Downstream dependency responded, response invalid or incomprehensible | 502 |
| Dependency unavailable, circuit breaker open, shutdown in progress, the operation's concurrency cap full (`@.claude/rules/inbound-admission.md`) | 503 + `Retry-After` |
| Timeout waiting for a dependency | 504 |

- A business rejection is never 5xx. If a 5xx is expected in the flow, the mapping is
  wrong.
- Generic `detail` on every 5xx. The real message and stack trace live in the log — level
  per category in `@.claude/rules/error-handling.md`.
- Every 5xx carries a correlation identifier in the body (`traceId`), the same one that's
  in the log. Without it, the user has no way to report the incident.

## Error body (Problem Details)

`ApiExceptionHandler` (name from `@.claude/rules/naming.md`) in the `adapter-in-rest`
module or the active blueprint's equivalent, `@RestControllerAdvice`, returns Spring's
native `ProblemDetail` — never a custom error DTO. Boot 4 ships `ProblemDetail` and
`ErrorResponseException`; reinventing one diverges from RFC 7807 by accident.

Domain types to statuses:

| Type | Status |
|---|---|
| `NotFoundException` | 404 |
| `ValidationException` | 400 |
| `BusinessRuleViolationException` | 422 |
| `ConflictException` | 409 |
| `DependencyResponseException` | 502 |
| `DependencyUnavailableException` | 503, with `Retry-After` from the exception's announced wait when present |
| `OutcomeUnknownException` | 504 |
| Unmapped | 500 |

The three integration families (`@.claude/rules/error-handling.md` § Integration families) are
where the rows of § Errors — 500 family come from: generic `detail`, `traceId`, and `errorCode` like
every other error coming from the domain package. The `dependency` never appears in the body — which
system failed is the log's business, not the caller's.

Body fields: the five from RFC 7807 (`type`, `title`, `status`, `detail`, `instance`)
plus three extensions, and no others:

| Field | Required for | Value |
|---|---|---|
| `errorCode` | Every error coming from the domain | The thrown exception's `errorCode()` |
| `traceId` | Every 5xx | The same identifier that's in the log |
| `violations` | Bean validation in the adapter | `[{field, message}]` per rejected field |

Bean validation is structural, it doesn't come from the domain — so it carries
`violations` and **never** `errorCode`. So do a missing `Idempotency-Key` (the header
name as `field`) and an unsupported API version (`version` as `field`). An admission rejection
is not structural — the request was well formed and refused for load — and carries `errorCode`
`RATE_LIMITED` or `SERVICE_BUSY` (`@.claude/rules/inbound-admission.md` § Rejection).

## Versioning

- **The major version is a path segment, `/api/v<N>/…`, and nowhere else.** Never in the
  query string, a header, or a media type parameter: the version must show in access logs
  and in a path matcher, and a header splits one cached URL into several representations
  that only `Vary` tells apart.
- **The framework's API versioning resolves it, from one configuration per project.** The
  `/api/v{version}` prefix is added by configuration to the REST adapter package's
  controllers alone, never spelled in a class mapping — a framework's own controller
  (the OpenAPI endpoint) must not receive it. The version is read only from paths under
  `/api/`, so `/actuator` and the OpenAPI endpoints are never parsed as a version.
- **A version is required under `/api/`, and the supported versions are an explicit list.**
  The prefix makes the segment part of every project mapping, so a request names a version
  or matches no mapping. The framework's own "version required" flag stays **off**: it
  reaches every handler of the mapping, not only the paths the resolver reads, and a
  library controller outside `/api/` (the OpenAPI endpoint, the AsyncAPI document at
  `/springwolf/docs`) would answer 500. No default version, no version detected from the
  mappings: removing a version from the list is what retires it.
- **Every handler method declares the version it starts at, as a baseline** (`"1+"`): it
  serves that version and every later one until a mapping at a higher version supersedes
  it. A handler with no version is never written — it answers every version, including
  ones that do not exist yet.
- **What is breaking.** Adding an optional field or a new status is not. Removing or
  renaming a field, changing a type, tightening validation, or changing the returned status
  is — and is served by a new mapping at the next version, as its baseline, beside the old
  one. Nothing else in the API moves: an endpoint without a breaking change is reached at
  the new version through its baseline.
- **Deprecation is per version, never per endpoint**, and a deprecated version answers with
  both `Deprecation` (RFC 9745) and `Sunset` (RFC 8594) on every response, plus a `Link`
  with `rel="deprecation"` to the migration notes. Never `rel="sunset"`. The window between
  the two dates is decided per deprecation and justified by measured use of the version,
  not by a fixed number.
- **After the sunset** the version leaves the supported list and its mappings are deleted
  in the same change: a request to it is 400, structural.
- **Use is measured per version.** The request metric carries the resolved version as a
  tag, bounded by the supported list (`@.claude/rules/observability.md` § Metrics) — with
  the version in a path variable, the URI tag no longer shows it.
- **The OpenAPI document lists each operation under the version its mapping starts at.**
  An operation reached at a later version through its baseline is not repeated there; the
  migration notes the deprecation link points to say so.

## Pagination

- Every collection that grows without bound is paginated from the first endpoint.
  "Today it's ten records" is not an argument.
- Offset: `page` (0-based), `size`, `sort=field,asc`. `size` defaults to 20, max 100.
  Above the max is 400 — silently truncating makes the client believe it saw everything.
- The controller binds the query string with Spring Data's `Pageable` instead of three
  separate primitives — REST and persistence sit in the same outer ring
  (`@.claude/rules/persistence.md` § Queries), so depending on Spring Data here isn't a
  boundary violation. `@PageableDefault` fixes the default size and sort;
  `spring.data.web.pageable.max-page-size` enforces the 400-above-max contract above —
  Spring Data doesn't reject an oversized `size` on its own. springdoc needs
  `@ParameterObject` on the parameter or it documents `Pageable` as a request body
  instead of query params. The application port on the other side of the controller
  never sees `Pageable`: convert to the port's own pagination type before the call
  (`@.claude/rules/architecture-ddd.md` § Application).
- Own envelope: `content` with the elements and `page` with `number`, `size`,
  `totalElements`, and `totalPages`. Nothing else at the root. Never Spring Data's `Page`
  serialized directly — the format changes between versions and exposes repository
  internals.
- Stable ordering is mandatory: every `sort` ends with a tiebreak by a unique key.
  Without it, page 2 repeats and skips records.
- High volume or a set that changes while paging uses a cursor: `cursor` + `limit`,
  response with an opaque `nextCursor` absent on the last page. A cursor is not an
  offset encoded in base64 — it carries the ordering key.
- `totalElements` only when the count is cheap. With a cursor it doesn't exist.

## Idempotency

- `GET`, `PUT`, and `DELETE` are idempotent by contract: repeating with the same input
  gives the same final state. Guaranteeing this is the code's job, not the verb's.
- `POST` that creates a resource, moves money, or touches an external system requires
  the `Idempotency-Key` header (a UUID generated by the client). Missing, 400.
- The key is stored with the route, the caller's identity, the body hash, the status,
  and the response body. TTL ≥ 24 h.
- Two transactions, in this order, and never another shape:
  1. **Claim** — the key is inserted `IN_PROGRESS` and committed on its own, before the
     business effect starts. A concurrent request with the same key collides on the
     primary key here. The claim runs with no transaction active: a constraint violation
     aborts the transaction it happens in, and the winner's row has to be read after it.
  2. **Effect + completion** — the business effect and the stored response commit in the
     same transaction. Without that, there's a window where the effect happens and the
     response doesn't stick — and the retry duplicates.
- The transaction shape lives in the application layer, in one component shared by every
  protected route. The use case's command doesn't carry the key; the inbound adapter
  supplies the effect and serializes the response to store.
- When transaction 2 rolls back, the claim is deleted: the retry executes again.
- A process crash between 1 and 2 leaves the key `IN_PROGRESS`. Retries get 409 +
  `Retry-After` until an in-progress lease expires — longer than the slowest protected
  request — and the first request after it reclaims the key, with optimistic locking
  deciding between two reclaimers. This availability window is accepted; a duplicate
  effect is not.

| Scenario | Response |
|---|---|
| New key | Executes, stores the result |
| Same key, same body, request already completed | Stored response, original status |
| Same key, same body, request still in progress | 409 + `Retry-After` |
| Same key, same body, `IN_PROGRESS` past the lease | Reclaims the key, executes |
| Same key, different body | 409, `errorCode` `IDEMPOTENCY_KEY_REUSED` |

- Concurrent writes on an existing resource are resolved with `ETag` and `If-Match`:
  outdated is 412, missing where required is 428. Silent last-write-wins is data loss,
  not policy.

## OpenAPI

- The contract is **generated from the code** (springdoc). A hand-written file in
  parallel diverges on day one and lies from day two.
- Annotations on controllers and DTOs only. Annotating an aggregate, value object, or
  JPA entity gives the domain a framework dependency — forbidden by
  `@.claude/rules/architecture-ddd.md`.
- Required on every operation:
  - `@Operation` with `summary` and a stable `operationId` — `operationId` becomes a
    method name in the generated client; changing it breaks consumers.
  - `@ApiResponse` for every status the endpoint returns, the success one and **all**
    error ones the tables above impose. An error always points to the `ProblemDetail`
    schema.
  - `@Parameter` with `description` on every path, query, or header parameter.
  - `@Schema` with `description` and `example` on every DTO field, and an explicit
    `requiredMode` — never inferred from the presence of `@NotNull`.
  - `@RequestBody` (the `@Operation` attribute, not Spring's parameter annotation) on
    every operation with a request body: `description` + `content` with `@Content`
    holding `@Schema` and `@ExampleObject` with `value`.
- No `@ApiResponse` for a status the endpoint doesn't return. Too much documentation is
  as wrong as too little.
- An endpoint whose errors aren't documented is incomplete, even if it works.

## How to verify

- Contract test per status, with `MockMvc` or `WebTestClient`: asserts status,
  `errorCode`, and body shape. The number alone isn't enough — a wrong `errorCode`
  passes.
- Idempotency: two `POST`s with the same key produce a single effect and two identical
  responses, including the status.
- Pagination: ordering without a unique tiebreak is caught with a test that requests the
  first two pages and asserts the intersection is empty.
- OpenAPI: `openapi.json` generated in CI and compared with the committed version. An
  unintended difference fails the build.
- Versioning: a request to a version outside the supported list is 400 with `violations`
  on `version`; a deprecated version carries `Deprecation` and `Sunset`.

```bash
# No version spelled in a mapping — the prefix is configuration. Zero lines expected.
grep -rnE "@(Request|Get|Post|Put|Patch|Delete)Mapping\(.*\"/api/v[0-9]" --include=*.java src/main

# Every handler method declares its baseline. Review any hit: a mapping with no `version`.
grep -rnE "@(Get|Post|Put|Patch|Delete)Mapping" --include=*.java src/main | grep -v "version"
```
