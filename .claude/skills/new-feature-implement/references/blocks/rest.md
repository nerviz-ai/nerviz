# Block 3: REST (steps 13-15, conditional)

§ 4 of spec.md reads `none` (GraphQL only, or no HTTP adapter) → skip Block 3; the checklist has no
steps 13-15. Otherwise read § 4 of spec.md, then the partial it cites for
the step at hand. Generates:
- `[Resource]Controller.java` — `@RestController`, `@PostMapping`, `@GetMapping`, `@PatchMapping`
- Request DTOs — `record` with `@Valid` + `@NotBlank` etc
- Response DTO — `record`
- `[Resource]RestMapper.java` — domain ↔ DTO, in the project's mechanism (§ Mappers): `RestMapper.java.example` or `RestMapStructMapper.java.example`
- `ApiExceptionHandler.java` — `@RestControllerAdvice`, 5 error shapes (422, 404, 409, 400, 500 with `traceId`)
- `ApiVersioningConfig.java` and `ApiVersionObservationConvention.java` — the first REST case of
  the project, when `30-rest.md` § 7 · Versioning says NEW; CHANGE only for the version, deprecation
  or sunset that section lists. Every handler method carries the `version` the partial's endpoint
  table gives (`1+`), and no class mapping spells `/api/v1` — the prefix is the config's
  (`.claude/rules/api-rest.md` § Versioning)
- Admission, only when `30-rest.md` § 8 (or `31-graphql.md` § 6's `Admission` rows) is not `none`
  — exemplars `.claude/skills/rest-api-architect/templates/AdmissionConfig.java.example` and
  `application-admission.yml.example`. When the setup is NEW: the `admission` subpackage
  (`InboundQuota`, `QuotaExceededException`, `AdmissionProperties`, `InboundQuotaInterceptor`,
  `AdmissionConfig`), `app.admission` in `application.yml`, and the two admission handlers of
  `ApiExceptionHandler` (the `SERVICE_BUSY` case of `GraphQlExceptionResolver` too, when the
  project has GraphQL). Then the case's limits: `@InboundQuota("<slug>")` on the handler and its
  `app.admission.quotas` entry with the **stated** quota (the interceptor divides it), and
  `@Bulkhead(name = "inbound-<slug>")` with its instance, `max-wait-duration: 0`. Never a
  `RateLimiterRegistry`, never an `@Order` on `IdempotencyAspect`, never `bulkhead-aspect-order`
  (`.claude/rules/inbound-admission.md`). With `Quota enforced: edge`, no quota entry and no
  `@InboundQuota`
- `IdempotencyKeyInterceptor.java` (if needed) — and, the first time, the transactional half the
  partial cites: `IdempotencyKeyStore`, `IdempotentExecution`. The controller calls
  `IdempotentExecution`; the command never carries the key (`.claude/rules/api-rest.md`
  § Idempotency — the transaction shape is decided, not yours to redesign)
- YAML additions (springdoc, default pagination, tracing)

Rules: `.claude/rules/naming.md`, `.claude/rules/code-quality.md`, `.claude/rules/lombok.md`,
`.claude/rules/error-handling.md` (the exception-family → status mapping
`ApiExceptionHandler` implements), and `.claude/rules/logging.md`: `[Resource]Controller`
logs method + path + response status + duration — `INFO` on success, `WARN`/`ERROR` on
failure per `error-handling.md`'s mapping.

Jackson on Spring Boot 4 is Jackson 3: `tools.jackson.databind.ObjectMapper`, unchecked
`JacksonException`. Only the annotations stay in `com.fasterxml.jackson.annotation`. Check the
`pom.xml` parent version before importing from memory.

Validation: correct statuses, `ProblemDetail`, mapper with no business logic, immutable DTOs, and the
right variant of `ApiExceptionHandler` — the one that injects `Tracer` only if the bridge is on
the classpath **and** something registers the bean. Confirm with
`./mvnw dependency:tree | grep -E 'micrometer-tracing|starter-opentelemetry'` before choosing: on
Spring Boot 4 the bridge alone registers no `Tracer`; without the bean the context doesn't start and the failure appears far from the cause
(`.claude/rules/observability.md` § Correlation).

**A missing dependency isn't resolved here.** If a `@NotBlank` doesn't compile or a bean doesn't
exist, the project's `pom.xml` is incomplete and that's a generation decision, not an
implementation one: stop and report which one is missing. Editing `pom.xml` mid-use-case is
making an infrastructure decision in the wrong place.

Compilation: `./mvnw -q -pl <rest-adapter-module> test-compile` in multi-module, `./mvnw -q test-compile` in single-module ✅

**Line in the final report:**
```
✅ Block 3: REST complete (steps 13-15)
- Controller: 3 endpoints (POST 201, GET 200, PATCH 200)
- DTOs: CreateOrderRequest, UpdateOrderRequest, OrderResponse
- Mapper: domain → REST (manual | MapStruct)
- Exception handler: 5 error shapes (422 validation, 404, 409, 400, 500+traceId)
- Interceptor: Idempotency-Key + If-Match
- Files: 5 classes, 0 errors
```
