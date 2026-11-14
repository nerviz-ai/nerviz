---
paths:
  - "**/adapter/out/http/**"
  - "**/infrastructure/http/**"
status: active
---

# HTTP clients — calling another service over HTTP

Code that sends a request to a system this service does not own and waits for the answer: a
payment provider, a partner API, another internal service. The other side fails in ways this code
cannot see — slowly, partially, after doing the work — so every default of an HTTP library is a
decision someone did not make. This norm makes them.

## Boundary

- **An outbound HTTP adapter implements an outbound port** of the application layer and nothing
  else calls the remote system. The port speaks domain types only; the client, the remote DTOs and
  the mapping live in the adapter — the anti-corruption layer of `@.claude/rules/architecture-ddd.md`
  § Adapters
- **One subpackage per remote system** under the architecture's HTTP-client package, its types
  package-private. Shared outbound pieces (failure translation, retry predicate, engine
  configuration) live in a `shared` subpackage, never inside one provider's
- **Remote DTOs never leave the adapter**, generated ones included: a type generated from the
  provider's OpenAPI document lives in a subpackage of that provider's adapter
- **Tolerant reader.** A remote DTO ignores fields it does not know, declared on the type itself,
  never relying on a global serializer setting. An enum value it does not know maps to an explicit
  `UNKNOWN` of the domain, never an exception
- The provider's API version is pinned in configuration (a version header, a path segment, a media
  type parameter) — never "whatever the provider serves today"

## Client choice

- **One client per remote system.** Two clients for the same provider is a divergence, never a
  convenience
- New code uses Spring's HTTP interfaces (`@HttpExchange`) over `RestClient`, or `RestClient`
  directly where the URL comes from data, a collection pages by `Link`, or a body is streamed.
  `RestTemplate` is not used in new code. Spring Cloud OpenFeign is kept only where it already
  exists, under every section of this norm
- `WebClient` only to consume a stream the provider pushes, bounded by an item count, an idle
  timeout and a time window — never as a general client, and never blocking on an event-loop
  thread
- **Every client is built from the framework's builder**, so observations, trace propagation and
  customizers apply. A client created bare (`RestClient.create()`) has none of them

## Engine and pool

- **The engine is pinned** by configuration. Without the pin, the framework picks the first engine
  on the classpath, and a new transitive dependency changes it — together with every pool setting
  written for the old one — with no error
- **One engine per project.** Pool limits are explicit: per route sized by Little's law (request
  rate × p99 latency, with headroom), a finite total, and a finite wait for a pooled connection
- **Idle connections are evicted** before the idle timeout of the server or load balancer in front
  of the provider, and every connection has a maximum lifetime — which also bounds how long a DNS
  answer is reused
- **The engine's own retry is off.** Retry has exactly one layer, below

## Timeouts

- **Every call has an explicit connect timeout and read timeout.** No library default is accepted:
  some wait forever
- A timeout is derived from a number — the provider's measured p99.9, or its published latency —
  plus margin, and the number is written next to the value in configuration
- Timeouts live in configuration, per remote system, never in code
- An outbound call made while serving a request never waits longer than that request's own
  deadline allows

## Retry

- **Exactly one layer retries.** When the trigger already retries — a message listener's error
  handler (`@.claude/rules/messaging.md`), a job's next run (`@.claude/rules/scheduling.md`) — the
  adapter does not. Three attempts in each of five layers is 243 calls on the deepest service
- **The retry decision is an allowlist**, never "any exception":

  | Failure | Retried |
  |---|---|
  | The request never left: refused connection, unknown host, connect timeout, no pooled connection in time | yes, any method |
  | 429 or 503 without `Retry-After` | yes |
  | 429 or 503 with `Retry-After` | no — the wait is surfaced to the caller (`@.claude/rules/api-rest.md` § Errors — 500 family) |
  | Read timeout, connection lost after sending, a gateway's 502 or 504 | only when the call is idempotent |
  | Any other 4xx, a TLS failure, an unreadable body | never |

- **A call is idempotent** when its method is safe or idempotent, or when it is a POST carrying an
  `Idempotency-Key` that is fixed per operation — a value the domain created with the operation and
  persisted with it, never generated at call time. The adapter method declares which calls are
  idempotent; nothing infers it
- Backoff is exponential, with jitter and a maximum delay. The number of attempts is small and fixed
- An open circuit is never retried in-process

## Failures

- **No transport type and no HTTP status leaves the adapter.** Every failure becomes either a
  domain family the operation defines (a 404 that means absent, a 409 that means duplicate, a 422
  that means the provider refused on business grounds) or one of the three integration families of
  `@.claude/rules/error-handling.md` § Integration families
- The domain outcomes are decided per operation and read from the provider's Problem Details
  `type` when it sends one — never by parsing `detail`
- Which integration family follows from one question — was the request sent?

  | Situation | Family |
  |---|---|
  | Not sent, or the provider says it did not process it (429, 503), or the circuit is open, or no slot is free | dependency unavailable |
  | Sent, and no answer is known (read timeout, connection lost, a gateway's 502 or 504) | outcome unknown |
  | Answered, and the answer cannot be used (an unexpected 4xx, another 5xx, an unreadable body, a TLS failure) | dependency response |

- **An unknown outcome of a write is a state, not a retry.** After the retries, the use case
  records that the result is unknown, and a later pass reconciles it by reading the provider — or
  the use case explicitly accepts failing there
- A fallback is a business decision written in the use case's design and never reports success
  for a call that did not succeed

## Resilience

- A circuit breaker counts only integration failures; a business answer of the provider (a
  decline, a not-found) never opens it
- A bulkhead per slow dependency is never larger than the pool's per-route limit
- Calls stay below the provider's published quota

## Outbound authentication

- **Every credential comes from the environment**, never from a versioned file —
  `@.claude/rules/secrets.md`
- **A token is obtained, cached and renewed by the framework**, per client registration — never
  fetched on each call, never cached by hand
- A user's token is relayed only to a provider in the same trust domain that accepts that token's
  audience. Otherwise the call uses this service's own identity, or a token exchanged for that
  audience. A user's token never reaches a third party
- A client certificate is presented only to the provider it was issued for — one SSL bundle per
  dependency, never a project-wide client certificate
- HTTP Basic and API keys travel over TLS only. A key in a query string ends up in access logs:
  a header is preferred whenever the provider accepts one
- A signed request follows the provider's signing specification exactly; the specification and its
  link are part of the design

## Security

- **TLS is always verified.** No trust-all manager, no hostname verifier that accepts everything;
  a certificate error is answered with the provider's CA in a trust store
- **Redirects are not followed** from a third party
- **A URL taken from data** — a customer's webhook endpoint, a provider-supplied `next` link —
  reaches only allowed destinations: public addresses checked after DNS resolution, or the
  configured host. A refused destination is a business rejection, never retried
- **A third-party response is untrusted input**: its size and its read time are bounded, and it is
  validated before it reaches the domain

## Consumption

- Pagination follows the provider's cursor or `Link: rel="next"`, with a maximum number of pages
- A large body is streamed to its destination with a byte limit and the stream always closed —
  never loaded whole into memory
- A conditional GET (`ETag`) and compression are used when the provider supports them; a
  compressed body has a limit on its uncompressed size

## Observability

- Request URIs are built from templates with variables, never by concatenation, so the metric's
  URI dimension stays one value per route — `@.claude/rules/observability.md`
- Trace context propagates on every outbound call (W3C `traceparent`)
- One log line per call with the remote system, the outcome and the latency, never the payload —
  `@.claude/rules/logging.md`

## Tests

The adapter is tested through its outbound port against a real socket (an HTTP stub server), never
with a mock that replaces the client — a mock proves nothing about timeouts, the pool, or the bytes
on the wire. Mandatory cases, each that applies to the operation:

| Case | Proves |
|---|---|
| Success | The mapped result, and the request: idempotency key, body, URI |
| Unknown field and unknown enum value | The tolerant reader |
| 404 on a lookup | An empty result |
| The provider's business rejection | The domain family, from the Problem Details `type` |
| 503 | Exactly the configured number of attempts |
| 503 with `Retry-After` | Exactly one attempt; the wait on the exception |
| 400 | Exactly one attempt |
| Delay beyond the read timeout | The timeout fires; outcome unknown |
| Connection reset | Outcome unknown, never a client exception |

## How to verify

```bash
# No RestTemplate in new code. Zero lines is the expected result.
grep -rn "RestTemplate" --include='*.java' src/main

# No client built bare — RestClient.create()/WebClient.create() skip observations. Zero lines expected.
grep -rnE "(RestClient|WebClient)\.create\(" --include='*.java' src/main

# No trust-all TLS. Zero lines is the expected result.
grep -rnE "TrustAllStrategy|NoopHostnameVerifier|InsecureTrustManagerFactory|trustAll" --include='*.java' src/main

# The engine is pinned.
grep -rn "imperative.factory\|imperative:" src/main/resources/

# Every @Retryable names its predicate. Review any hit without it.
grep -rn "@Retryable" --include='*.java' src/main | grep -v "predicate"

# No transport type leaks past the adapters. Zero lines is the expected result.
grep -rlnE "RestClientException|ResourceAccessException|HttpStatusCodeException|FeignException|WebClientResponseException" --include='*.java' src/main | grep -vE "/(http|client)/"

# The adapter tests open a real socket. Zero lines is the expected result.
grep -rln "MockRestServiceServer" --include='*.java' src/test
```
