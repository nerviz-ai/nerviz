# 0151 · Inbound admission enters `rest-api-architect` and `graphql-api-architect`: a per-client quota and a concurrency cap, both Resilience4j, both only when the use case states them

- **Date:** 2026-10-13
- **Scenario:** "Estender o `rest-api-architect` e o `graphql-api-architect` (e o que mais o desenho
  exigir) para decidir a resiliência inbound dos endpoints expostos: limite de concorrência/bulkhead,
  rate limiting (Bucket4j, nota de estratégia 2026-10-11 § 1.2) e o que a 429/503 de admissão vira no
  `ApiExceptionHandler` e nos erros GraphQL. Ponto de partida: `0150` § Out of scope — inbound
  admission, deferred. Tudo OSS."
- **Decision:** Option 1 — inbound admission as part of `rest-api-architect` and
  `graphql-api-architect` (Form 1, existing: step 7c, `30-rest.md` § 8, step 8b, the `Admission`
  rows of `31-graphql.md` § 6), an `Admission` row recorded by `use-case-design` (Form 1, existing),
  the new shared rule `.claude/rules/inbound-admission.md` (Form 4), three new templates, and
  `.claude/.ci/AdmissionTemplateTest.java`.
- **State:** approved by Lucas Fernandes, on 2026-10-13 — option 1 with its 14 planned decisions,
  as corrected by the run in § Verified against a real project; implemented on the branch
  `feat/inbound-admission`, release bump `minor`

## What the context showed

| Concern | Owner today |
|---|---|
| 429 | `rules/api-rest.md` § Errors — 400 family lists "Request rate limit exceeded → 429 + `Retry-After`". Nothing emits it: no skill decides a quota, no template rejects a request, `ApiExceptionHandler` has no row for it |
| 503 | `rules/api-rest.md` § Errors — 500 family: "Dependency unavailable, circuit breaker open, shutdown in progress". Reached only through `DependencyUnavailableException` (`rules/error-handling.md` § Integration families) — an outbound fact. An inbound rejection has no dependency |
| Bulkhead | Outbound only: `rules/http-client.md` § Resilience, Resilience4j through `resilience4j-spring-boot4`, instances named after the dependency |
| GraphQL | `rules/api-graphql.md` § Demand limits: depth, complexity, request timeout, page and batch size. No rate limit; § Choosing the inbound protocol already names "strict per-endpoint rate limiting" as a reason against GraphQL |
| Business quota | None. `use-case-design` records `Freshness` (always asked when reading) and `Audit` (recorded only when the request asks) — the shape a quota would take |
| Container limits | `max-connections`, body size, graceful shutdown: `transport-security-setup` (`0150` interview, unchanged here) |
| Threads | Generated projects do not enable virtual threads; Tomcat's pool is the scarce resource |

The five intersections `0150` § Out of scope found with `http-client-architect` are the
planned decisions 4, 5, 6, 8 and 9 below.

Facts verified on 2026-10-13:

- Resilience4j 2.4.0 (current on Maven Central): `RateLimiter.of(String, RateLimiterConfig)`, `acquirePermission()`, `reservePermission()`. The internal `AtomicRateLimiter.AtomicRateLimiterMetrics#getNanosToWait()` exists. `RegistryStore` is pluggable, and `InMemoryRegistryStore` is the default. The state is per JVM, with no shared backend. Source: `javap` on the 2.4.0 JARs; resilience4j.readme.io "RateLimiter".
- `RateLimiterConfig` defaults: `limitForPeriod` 50, `limitRefreshPeriod` 500 ns, `timeoutDuration` 5 s. A denied call fails with `RequestNotPermitted`. Source: resilience4j.readme.io "RateLimiter".
- Spring Framework `@ConcurrencyLimit` exists since 7.0, and `policy` since 7.0.3. `ThrottlePolicy.REJECT` throws `InvocationRejectedException`, which extends `RejectedExecutionException`. Source: Javadoc 7.0.9. Weighed and not chosen; see the interview.
- `bucket4j-spring-boot-starter` supports Boot 4.0 from 0.14.0. Its headers are `x-rate-limit-remaining` and `x-rate-limit-retry-after-seconds`, and it sends no `Retry-After`. Source: github.com/MarcGiffing/bucket4j-spring-boot-starter README. Weighed and not chosen.

None of these versions is written into a norm or a template (invariant 8).

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | None observed. Anticipation, overridden by the maintainer as in `0144`–`0150` | Create nothing (as the whole answer) |
| 2 — trigger | Model decision inside `/new-feature`: the use case's row decides whether either designer writes anything | Form 2 |
| 5 — nature | Procedure (one interview step per designer, one partial block each) plus a shared norm | Forms 4 and 5 as the whole answer |
| 6 — isolation | None | Form 3 |
| 7 — mandatoriness | Persuasion. No hook: no failure observed (invariant 6) | Forms 7 and 8 |
| 8 — destination | Both: the skills, their templates and the new rule travel | — |
| — scope | **Both** mechanisms: a per-client quota (429) and a concurrency cap (503) | Quota only; cap only |
| — where the quota is enforced | **Per project**: in the application, or at the edge (ingress, API gateway). The application then records the number the edge enforces and writes no limiter | Always in the application; always at the edge |
| — where the quota comes from | **A business row, recorded only when the request states one explicitly.** Never asked, never inferred | Asked by `use-case-design`; a project default; a number per endpoint chosen by a designer |
| — when a cap exists | **Only when the request states one**, in the same row | Every handler that reaches a slow port; every handler |
| — quota mechanism | **Resilience4j `RateLimiter`**, maintainer's choice over the recommended `bucket4j-core` | `bucket4j-core` with an own filter; the Giffing starter |
| — replicas | **Per replica.** The first case records the replica count, and each replica enforces `ceil(quota / replicas)`. Exactness across replicas is the edge's job | Resilience4j plus Bucket4j for exactness; back to Bucket4j |
| — cap mechanism | **Resilience4j `Bulkhead`**, semaphore, no wait. One resilience library per project, shared with the outbound side | `@ConcurrencyLimit(policy = REJECT)` |
| — GraphQL | **Per HTTP request** on the GraphQL endpoint, rejected before execution. A cost-based quota is deferred | A cost-based quota now |
| — norm | **New shared rule**, `rules/inbound-admission.md`, cited by `api-rest.md` and `api-graphql.md` | A section in `api-rest.md` |
| — project setup | **The first case with an `Admission` row** writes an `Admission` block in its `30-rest.md` or `31-graphql.md`: where the quota is enforced, replicas, client key, 503 wait. Later cases write `inherited from UC-NNN`. This is the shape of `0150` and of `31-graphql.md` § 6 | A new skill and a `33-admissao.md` partial |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Admission as part of `rest-api-architect` and `graphql-api-architect` (Form 1, existing), an `Admission` row in `use-case-design` (Form 1, existing), new rule `rules/inbound-admission.md` (Form 4, new), templates, CI test | 7 | **Approved** |
| 2 | New skill `inbound-admission-architect` with its own partial `33-admissao.md` | 5 | Rejected — a pipeline step and a partial for two attributes of a handler; the interview answered "first case" |
| 3 | Option 1 with the norm as § Admission in `rules/api-rest.md` | 5 | Rejected — the GraphQL adapter would load a REST norm; the interview answered "new shared rule" |
| 4 | Create nothing | 3 | Rejected — the maintainer chose to anticipate |

### Option 1 — part of both designers, new shared rule (score 7)

**Motivator:** the quota and the cap are attributes of the same handler or root field that
`30-rest.md`/`31-graphql.md` already design, decided at the same moment (axis 2). The project
setup follows the precedent of `0150` and `31-graphql.md` § 6. The norm is shared by two
adapters, so it has one owner neutral to the protocol (invariant 2).

**Planned decisions inside the option:**

| # | Question | Answer |
|---|---|---|
| 1 | Business row | `00-caso-de-uso.md` gains `Admission`, **recorded only when the request states it**: a quota per caller ("at most 100 requests per minute per client") and/or a cap ("at most 20 at the same time"). `use-case-design` never asks it, and its checklist gains a row: a rate limit, a quota, 429 or a bulkhead the request never mentioned → don't ask. Shape of `Audit` (`0149`) |
| 2 | Project setup | The `Admission` block (`30-rest.md` § 8, `31-graphql.md` § 6 gains the rows) is written by the first case whose row names a quota or a cap. It holds where the quota is enforced (`application` or `edge: <name>`), the replica count, the client key, and the 503 wait. Later cases write `inherited from UC-NNN` or the delta. The first case that needs a row the block lacks (a quota after a cap-only first case) adds it as a CHANGE |
| 3 | Client key | The authenticated principal's name when the endpoint requires authentication. The API key's id when the case authenticates by key. The client address only for a public endpoint, read through the forwarded headers only when `transport-security-setup` configured trusted proxies, otherwise the remote address. Never a raw header the client controls |
| 4 | Quota mechanism | One Resilience4j `RateLimiter` per (use case, client key), created with `RateLimiter.of(name, config)` and held in a bounded Caffeine cache (`maximumSize`, `expireAfterAccess` ≥ the refresh period). Never a `RateLimiterRegistry`: its store is unbounded, and the Micrometer binding would tag a metric per client. `timeoutDuration: 0` rejects without waiting. Per-replica limit `ceil(quota / replicas)`, with the replica count and the division written in the block |
| 5 | Where the quota runs | A `HandlerInterceptor` (`InboundQuotaInterceptor`). It runs after the Spring Security filter chain, so the principal is known and a 401 never spends a quota. It runs before the controller, so before `IdempotencyAspect` claims a key. REST: the quota of the handler is found through an annotation on the handler method that names its limiter. GraphQL: the same interceptor on the GraphQL endpoint, one quota for the whole endpoint per client. **Unproven:** whether `RouterFunctionMapping` applies a `WebMvcConfigurer` interceptor to `/graphql` — proved by the Phase 4 run; if not, a servlet filter path for that endpoint is decided then |
| 6 | Cap mechanism | Resilience4j `Bulkhead`, `SEMAPHORE`, `maxWaitDuration: 0`, `@Bulkhead(name = "inbound-<uc-slug>")` on the controller method or the GraphQL controller method. The instance namespace `inbound-` never collides with outbound instances, which are named after the dependency. Needs `spring-boot-starter-aspectj`. The cap is sized below the request thread pool, with the reason recorded. **Unproven:** the Resilience4j aspect runs before `IdempotencyAspect`, so a rejected call claims no key (`bulkhead-aspect-order` vs `@Order`). Proved by the run |
| 7 | Exceptions | An inbound rejection is neither a domain family nor an integration family. The library exceptions (`RequestNotPermitted`, `BulkheadFullException`) are caught only where the inbound limiter raised them and become one adapter-owned `AdmissionRejectedException`, with reason `RATE_LIMITED` or `BUSY` and the wait. An outbound `BulkheadFullException` is already translated to `DependencyUnavailableException` by the outbound adapter (`rules/http-client.md`), so the two never meet. `rules/error-handling.md` gains one line: admission is the adapter's, never a family |
| 8 | REST responses | `ApiExceptionHandler`: `RATE_LIMITED` → 429, `BUSY` → 503, both Problem Details with `errorCode` and `Retry-After`. The 429 wait is the ceiling of the refresh period, an upper bound read from the configuration and not from the internal `getNanosToWait()`. The 503 wait is the block's fixed value. Two rows in `rules/api-rest.md` cite the new rule |
| 9 | GraphQL responses | The quota is rejected before execution: HTTP 429 with `Retry-After`, before any GraphQL response exists. Whether the body is Problem Details or a GraphQL `errors` document is proved by the run, against the GraphQL-over-HTTP rule of `api-graphql.md` § Errors. A cap rejection inside execution is a top-level error, `INTERNAL_ERROR`, with `errorCode: SERVICE_BUSY`, `retryable` and the wait, the shape the integration row already uses, and HTTP 200 |
| 10 | Edge | `edge: <name>` writes no limiter. The block records the number and the key the edge enforces, and the IT cannot prove it, which is recorded. Caddy, the local edge of `docker-architect`, has no rate limit in its stock build: the edge is the deployment's ingress or gateway, outside this repository's templates. The cap still lives in the application |
| 11 | Metrics | The bulkhead instances bind to Micrometer, bounded by the number of use cases. The per-client limiters never do. One counter of rejections is tagged with the limiter name and the reason, never with the client key (`rules/observability.md` § cardinality) |
| 12 | Tests | Over quota → 429, `Retry-After`, Problem Details. Cap full → 503. A rejected call claims no idempotency key. Another client keeps its quota. GraphQL over quota → HTTP 429 before execution. In `test-architect`'s controller and GraphQL test templates |
| 13 | OpenAPI | A limited operation documents 429 or 503 with `Retry-After` |
| 14 | Existing projects | No migration: additive, per case, nothing breaks when absent |

**Pros:** no new skill, no new pipeline step, no new partial. A case without the row pays
nothing. One resilience library per project, in and out. The 429 the norm already lists gets an
emitter.

**Cons:** one new rule, and a `derived_paths` entry for it. Propagation across four skills'
templates (`use-case-design`, `rest-api-architect`, `graphql-api-architect`, `test-architect`)
and the executor's block. Per-replica quotas are approximate: an uneven balancer lets a client
pass its quota or cuts it early. Three runtime facts are unproven before the Phase 4 run
(planned decisions 5, 6 and 9).

**Points cut in the rubric:** 1 (form fit — no signal, overridden) · 5 (maintenance: four
skills and a new rule) · half of 6 (a per-key limiter cache has no precedent here).

**CI:**

- `validate` › `design` › `frontmatter schema`: the new rule's `paths` and its `derived_paths` entry; the skills' classes and sections. `export` is unchanged, since there is no new skill.
- `validate` › `design` › `rules is a leaf of the graph` and `norm contains no code boilerplate`: the new rule.
- `validate` › `exemplar-imports`: every import of the new `.java.example` files (`io.github.resilience4j.ratelimiter`, `.bulkhead`, `com.github.benmanes.caffeine`).
- **New:** `.claude/.ci/AdmissionTemplateTest.java`, a step of `validate` › `design` next to `ApiVersionTemplateTest`, offline. It holds:
  - no exemplar uses a `RateLimiterRegistry` for a per-client limiter;
  - the per-client cache is bounded;
  - `timeoutDuration` and `maxWaitDuration` are zero;
  - bulkhead names start with `inbound-`;
  - `ApiExceptionHandler` maps both reasons with `Retry-After`;
  - the GraphQL resolver maps `BUSY`;
  - no metric tag carries the client key.

  Proven green on the tree and red with each defect injected (`0099`).

### Option 2 — new skill `inbound-admission-architect`, partial `33-admissao.md` (score 5)

It is one owner for admission, but it adds a pipeline step, a class entry, an `export` entry
and a partial for two attributes of a handler that `30-rest.md` already lists. A case would be
designed in two places by moment, which is the split `0005` rejects. The interview answered
"first case". Points cut: 1, 3, 5, 7.

### Option 3 — option 1 with § Admission in `rules/api-rest.md` (score 5)

The GraphQL adapter would load a REST norm for a filter both protocols share, or restate it
(invariant 2). The interview answered "new shared rule". Points cut: 2 (strain on 2), 3, 5.

### Option 4 — create nothing (score 3)

This is what the matrix recommends with no symptom (anti-pattern 9). The 429 of
`rules/api-rest.md` stays without an emitter. A request that names a quota would be designed
with no key rule, no per-replica arithmetic, a registry tagging every client, and a rejection
that may claim an idempotency key.

## Verified against a real project

On 2026-10-13, an Initializr project ran the planned shapes under `@SpringBootTest` on a random
port, with real HTTP requests through Spring Security's basic authentication. The project had
`web`, `security`, `graphql`, `validation` and `actuator`, on Boot 4.1.1, plus
`resilience4j-spring-boot4` 2.4.0 (the `<release>` of Maven Central's `maven-metadata.xml`),
`spring-boot-starter-aspectj` and Caffeine. Three planned decisions rested on undocumented
behaviour, and the run settled each. Four planned decisions changed:

| Planned decision | Found | Shipped |
|---|---|---|
| 5 — the interceptor on the GraphQL endpoint | Boot's GraphQL route is a `RouterFunction`, and its mapping receives the `WebMvcConfigurer`'s interceptors: `preHandle` runs with a `HandlerFunction` (`GraphQlWebMvcAutoConfiguration$$Lambda`), dispatch `REQUEST`. A `QuotaExceededException` thrown there reaches the `@RestControllerAdvice` like one from a controller | One interceptor for both; the GraphQL route keyed by its path to the reserved `graphql` entry |
| 6 — bulkhead aspect before `IdempotencyAspect` | Resilience4j's `bulkheadAspectOrder` defaults to `Integer.MAX_VALUE - 1` (`javap` on `resilience4j-spring6` 2.4.0); `IdempotencyAspect` declares no `@Order`, so it sorts last. A full cap rejected the second call with one idempotent execution (`delta=1`). Inverse proof: `@Order(HIGHEST_PRECEDENCE)` on the idempotency aspect → `delta=2`, the rejected call claimed | The order is a norm: no `@Order` on `IdempotencyAspect`, `bulkhead-aspect-order` never set — rule, yml exemplar, executor, CI |
| 7 — one adapter exception with two reasons | `BulkheadFullException` reaches the advice raw: the library's aspect raises it, and wrapping it would need a `fallbackMethod` per handler | **Changed.** The quota throws its own `QuotaExceededException`; `BulkheadFullException` is mapped as it arrives — safe because the outbound adapter translates its own full bulkhead (`rules/http-client.md` § Failures). `getBulkheadName()` names the cap in the log |
| 9 — GraphQL quota response | HTTP 429, `application/problem+json`, `Retry-After: 60`, before execution: no GraphQL response exists yet. A full cap on a `@QueryMapping` → `errors[0].extensions` `SERVICE_BUSY`, `retryable`, `retryAfterSeconds`, `INTERNAL_ERROR`, HTTP 200 | As planned |
| 11 — a rejection counter | `rules/observability.md` § Metrics forbids a hand-written metric duplicating what is already measured. `http.server.requests` carries `status=429/503` per `uri`, and the GraphQL observation the outcome | **Changed.** No counter. The bulkhead's own gauges stay bound, bounded by the use cases; per-caller limiters are never bound |
| 12 — tests in the controller and GraphQL test templates | `@WebMvcTest` holds zero `BulkheadAspect` and zero `BulkheadRegistry` beans: a slice test of the cap passes with the cap absent | **Changed.** One `AdmissionIT` on a real server, in `test-architect` |
| 2 — the setup only for a quota | A cap alone still needs `busy-retry-after`, read by both exception mappers | **Changed.** `AdmissionProperties` and `AdmissionConfig` are written for any limit; with no quota the map is empty and the interceptor passes everything |

Also proven: per caller, alice's third call → 429 `RATE_LIMITED` while bob's first → 200 (quota 4 /
2 replicas → 2 per replica). An anonymous call → 401 from the chain. A full cap → 503
`SERVICE_BUSY` with `traceId` and `Retry-After: 1`.

The `export` run found one more thing. `derived_paths` with the suffixes `.rest` and `.graphql`
left vertical-slice without its REST slices: the fallback by architecture path applies only when
no suffix matches, and `config.graphql` did. Shipped: the suffix `.rest` with the slice fallback,
like `api-rest.md`, plus the fixed glob `**/graphql/**` in `extra`. Every blueprint now derives
both.

## Out of scope

- A cost-based GraphQL quota, where an operation's complexity consumes points.
- A quota exact across replicas inside the application. That needs a shared store (Bucket4j with PostgreSQL or Redis), and is the edge's job until a case shows otherwise.
- Per-plan quotas read from a subscription or a tenant. That is a business model, and touches `multi-tenancy-setup` (strategy note § 1.1, item 5).
- Container limits, which stay with `transport-security-setup`.
- Load shedding by an adaptive concurrency limit, with no fixed number.

## References

| Claim | Source |
|---|---|
| Per-JVM state, `RateLimiter.of`, `RegistryStore` pluggable, `getNanosToWait` internal | `javap` on `resilience4j-ratelimiter-2.4.0.jar`, `resilience4j-core-2.4.0.jar` |
| `RateLimiterConfig` defaults, `RequestNotPermitted` | resilience4j.readme.io/docs/ratelimiter |
| `@ConcurrencyLimit` `policy` since 7.0.3, `InvocationRejectedException` | Spring Framework 7.0.9 Javadoc |
| Giffing starter Boot 4 from 0.14.0, non-standard headers | github.com/MarcGiffing/bucket4j-spring-boot-starter |
| A row recorded only from the request | `use-case-design/SKILL.md` `Audit` row; `0149` |
| Project setup decided by the first case, inherited after | `0150` planned decision 6; `31-graphql.md` § 6 |
| Intersections with the outbound side | `0150` § Out of scope — inbound admission, deferred |
| Outbound bulkhead translated to `DependencyUnavailableException` | `rules/error-handling.md` § Integration families; `rules/http-client.md` |
| A shared norm across REST and GraphQL packages | `derived_paths` of `observability.md` (`.rest`, `.graphql`) in `extensions.json` |
| Per-skill exemplar test, offline | `.claude/.ci/ApiVersionTemplateTest.java` (`0150`), `GraphQlTemplateTest.java` (`0146`) |
| Anticipation overridden by the maintainer | `0144`–`0150` |

## Propagation

| File | Change |
|---|---|
| `.claude/rules/inbound-admission.md` | new: when a limit exists, the caller, quota, cap, rejection, observability, contract, tests, § How to verify |
| `.claude/rules/api-rest.md` | the 429 and 503 rows cite the rule; an admission rejection carries `errorCode` |
| `.claude/rules/api-graphql.md` | § Demand limits: quota and cap only when stated, the quota for the whole endpoint |
| `.claude/rules/error-handling.md` | admission is neither a domain nor an integration family |
| `.claude/rules/00-index.md` | the new row; the rule in the package-territory list |
| `.claude/schemas/extensions.json` | `derived_paths` entry for `inbound-admission.md` |
| `.claude/skills/use-case-design/SKILL.md`, `templates/use-case-spec.md.example` | the `Admission` row, recorded only from the request; a checklist row |
| `.claude/skills/rest-api-architect/SKILL.md` | `description`; step 2 survey; step 7c (new); step 8 dependencies; eight numbered blocks; the Admission row of the partial table; Contract reads |
| `.claude/skills/rest-api-architect/templates/rest-spec.md.example` | § 8 · Admission |
| `.claude/skills/rest-api-architect/templates/AdmissionConfig.java.example` | new — `InboundQuota`, `QuotaExceededException`, `AdmissionProperties`, `InboundQuotaInterceptor`, `AdmissionConfig` |
| `.claude/skills/rest-api-architect/templates/application-admission.yml.example` | new |
| `.claude/skills/rest-api-architect/templates/ApiExceptionHandler.java.example` | 429 `RATE_LIMITED`, 503 `SERVICE_BUSY`, `Retry-After`, the constructor |
| `.claude/skills/graphql-api-architect/SKILL.md` | `description`; step 2 survey; step 8b (new); § 6 row; Contract |
| `.claude/skills/graphql-api-architect/templates/graphql-spec.md.example` | § 6 Admission rows; two § 7 rows |
| `.claude/skills/graphql-api-architect/templates/GraphQlExceptionResolver.java.example` | `SERVICE_BUSY` |
| `.claude/skills/test-architect/SKILL.md`, `templates/AdmissionIT.java.example` | the admission cases; the new IT exemplar |
| `.claude/skills/new-feature/SKILL.md`, `templates/feature-spec.md.example` | eight REST blocks; the Admission row |
| `.claude/agents/java-spring-boot-developer.md` | Block 3 and G1: the admission pieces |
| `CLAUDE.md`, `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | routing row |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` | the admission pitfall |
| `.claude/.ci/AdmissionTemplateTest.java`, `.github/workflows/validate.yml`, `docs/*/07-ci-validate.md` | the CI check below |

Goes to the generated project: **yes**. The rule, the skills and their templates travel through
`export` as before. There is no new skill or agent, so `export.include` and `skill_classes` are
unchanged. No migration: the change is additive per case, and nothing breaks when it is absent
(planned decision 14).

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › admission exemplars keep one bounded limiter per caller and an inbound cap ahead of idempotency` (`.claude/.ci/AdmissionTemplateTest.java`, new) | See the list below | Green on the tree. Red, each by name, with 14 defects injected one at a time (list below). The first run had one false positive (`bulkhead-aspect-order` named in a yml comment) and one false negative (a fully qualified `@…Bulkhead(` the pattern missed); both were fixed and re-proven |
| `validate · design › frontmatter schema`, `rules is a leaf`, `norm contains no code boilerplate`, `each norm has a single owner`, `every norm paths matches some blueprint`, `no hardcoded version`, `TemplateCommentsTest`, `ApiVersionTemplateTest` | The rule's `paths` and `derived_paths`; no skill named in a rule; the skills' frontmatter and classes; no body comment in the new exemplars | Green, run locally |
| `validate · export-determinism` | The new rule exported with derived `paths` for every blueprint; `schema` green inside the export | Green locally for all seven blueprints; it caught the vertical-slice gap above |
| `validate · exemplar-imports` | The new imports (`io.github.resilience4j.ratelimiter`, `.bulkhead`, `com.github.benmanes.caffeine`, `AnonymousAuthenticationToken`, `DefaultValue`, `MockitoSpyBean`, `Jwt`) exist in the reference project's JARs, which already carry Resilience4j and Caffeine | Runs in CI; the same imports compiled in the verification project |

`AdmissionTemplateTest` proves that:

- no exemplar uses a `RateLimiterRegistry`;
- the admission exemplar keeps one `RateLimiter.of` per caller in a bounded, expiring Caffeine cache;
- the limiter never waits;
- the stated quota is divided per replica;
- the caller key is the principal (anonymous excluded) or the address, never a raw header;
- the exemplar counts nothing of its own;
- every REST, GraphQL and test `@Bulkhead` is named `inbound-…`, and every inbound yml instance waits zero;
- no yml sets `bulkhead-aspect-order`, and `IdempotencyAspect` declares no `@Order`;
- the REST handler answers 429 `RATE_LIMITED` and 503 `SERVICE_BUSY` with `traceId`, both with `Retry-After`;
- the GraphQL resolver answers a retryable `SERVICE_BUSY` with the wait;
- `AdmissionIT` runs on a real server and proves 429, 503 and 401.

The 14 defects injected for the red runs:

1. a limiter that waits;
2. an unbounded cache;
3. no per-replica division;
4. a registry;
5. a raw-header key;
6. a fully qualified bulkhead with an outbound name;
7. a simple bulkhead with an outbound name;
8. `@Order` on `IdempotencyAspect`;
9. a cap that waits;
10. `bulkhead-aspect-order` set;
11. a 503 without `Retry-After`;
12. the quota answered as 503;
13. GraphQL not retryable;
14. the IT as a `@WebMvcTest`.
