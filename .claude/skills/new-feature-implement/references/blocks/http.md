# Block H: Outbound HTTP (steps H1-H3, conditional)

**Runs only when the spec carries the outbound HTTP block** — `grep -m1 -E '^## [0-9.]+ Outbound HTTP' <spec>`,
and the block is not `none`. No match, or `none` → skip to Block A. Read the outbound HTTP block of
spec.md, then `28-http-client.md`. It runs right after Block 2 because an adapter compiles against
the outbound port and the domain types, and nothing upstream compiles against it. § 2 decides the
client exemplar of each dependency in `.claude/skills/http-client-architect/templates/` — never a
second client for a provider because a call looked easier with it:

| `28-http-client.md` § 2 | Exemplar |
|---|---|
| HTTP interface (`@HttpExchange`) | `HttpExchangeAdapter.java.example` |
| `RestClient` direct | `RestClientAdapter.java.example` |
| OpenAPI Generator | `OpenApiGeneratedAdapter.java.example` + `openapi-generator-maven.xml.example` (or `-gradle.kts.example`) |
| `WebClient` (stream) | `WebClientStreamAdapter.java.example` + `ReactorNettyConfig.java.example` |
| OpenFeign (legacy) | `FeignClientAdapter.java.example` |

- **H1 · Shared setup** — every component § 6 marks NEW or CHANGE: the integration families in
  `[domain]` (`IntegrationExceptions.java.example`), the `shared` subpackage of `[http]`
  (`HttpFailureTranslator.java.example` — its engine variant follows § 3), the engine customizer
  of § 3, the outbound authentication wiring of § 4, and the three 5xx handlers in
  `ApiExceptionHandler` (`rest-api-architect/templates/ApiExceptionHandler.java.example`) when
  § 6 marks them. The dependencies of § 8 in `pom.xml` — exactly those rows, versions as the row
  says, the Resilience4j BOM import where § 8 says so. The OpenAPI Generator plugin and its
  versioned spec file under `src/main/resources/openapi/` only when § 2 chose it. A REUSE component
  is not rewritten
- **H2 · Adapters** — per dependency, its subpackage of `[http]`: remote DTOs (tolerant), the
  client (interface and group configuration, or the qualified `RestClient`), the mapper, and the
  adapter implementing the port `10-domain.md` declared. Per row of § 1: the domain outcomes it
  names, `@IdempotentCall` exactly on the methods § 1 marks idempotent, `@Retryable` with the
  shared predicate and § 5's numbers only when § 5 names the adapter as the retrying layer, and
  `ResilientHttpCall` only when § 5 names a breaker or a bulkhead. The idempotency key comes from
  the domain value the port receives — never generated here
- **H3 · Configuration** — the properties of § 7 in `application.yml` (shape in
  `application-http-client.yml.example`), the engine pinned, every secret as an environment
  placeholder with no default, never a literal

Rules: `.claude/rules/http-client.md`, `.claude/rules/error-handling.md` § Integration families,
`.claude/rules/architecture-ddd.md` § Adapters, `.claude/rules/logging.md` (outcome and latency,
never a payload, a token or a key), `.claude/rules/observability.md` (URI templates).

Validation, each item checkable:

- the greps of `.claude/rules/http-client.md` § How to verify return what that section says;
- no class outside `[http]` imports a client type (`RestClient`, `WebClient`, `@FeignClient`,
  `org.apache.hc`), and no port signature carries one;
- every `@Retryable` names `DependencyRetryPredicate`, and `@EnableResilientMethods` exists once;
- every adapter method that sends an `Idempotency-Key` takes it from its parameters.

Compilation: `./mvnw -q test-compile` (or `-pl <module that holds [http]>` in multi-module) ✅

**Line in the final report:**
```
✅ Block H: Outbound HTTP complete (steps H1-H3)
- payment-gateway: HTTP interface, Apache HttpClient 5 (NEW), OAuth2 client credentials (NEW)
- Retry: adapter, 3 attempts; breaker and bulkhead: payment-gateway
- Shared: integration families, translator, retry predicate (NEW)
- Files: 11 classes, 0 errors
```
