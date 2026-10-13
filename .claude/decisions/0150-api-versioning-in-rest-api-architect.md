# 0150 · API versioning enters `rest-api-architect`: Spring's native path-segment versioning behind an `/api/v{version}` prefix, a baseline per mapping, deprecation headers per version

- **Date:** 2026-10-13
- **Scenario:** "Estender o `rest-api-architect` (e o que mais o desenho exigir) para decidir
  versionamento de API e proteção de admissão inbound (bulkhead/limite de concorrência e rate
  limiting) dos endpoints expostos." Origin: the private strategy note of 2026-10-11, § 1.2 — an
  extension of an existing skill, all OSS. The interview cut the scope to **versioning only**.
- **Decision:** Option 1 — API versioning as part of `rest-api-architect` (Form 1, the existing
  skill: step 7b, a `§ 7 · Versioning` partial block, a `Version` column, two new templates) +
  `.claude/rules/api-rest.md` § Versioning (Form 4, the existing norm) + one rule in
  `.claude/rules/authorization.md` § Default access + the `api-version-path-prefix` migration +
  `.claude/.ci/ApiVersionTemplateTest.java`.
- **State:** approved by Lucas Fernandes, on 2026-10-13 — option 1 with every planned decision of its
  table, as corrected by the run in § Verified against a real project; implemented on the branch
  `feat/api-versioning`, release bump `minor`

## What the context showed

| Concern | Owner today |
|---|---|
| Where the version lives | `rules/api-rest.md` § Resources and URIs: "Major version at the start of the path: `/api/v1/orders`. Never in the query string, never in a header", plus what counts as breaking. Nothing on deprecation, sunset, supported versions, or the status of an unknown version |
| How a controller spells it | `rest-api-architect/templates/Controller.java.example:65` — `BASE_PATH = "/api/v1/users"`, read by the class mapping and by every `Location` (`0006` P5(g)); `rules/api-rest.md` § Success statuses: `Location` is relative, "built from the same path constant the class-level mapping reads" |
| Who decides a `/v2` | Nobody. `rest-api-architect` step 3 has the axis "client internal or public → weight of `operationId` stability and the cost of a `/v2`"; no step decides a version, a baseline or a deprecation |
| Path matchers | `security-architect/templates/SecurityConfig.java.example:103-104` — `requestMatchers(POST, "/api/v1/orders/*/cancellation")` then `anyRequest().authenticated()`. A `/v2` of a role-protected route falls to the catch-all and keeps only `authenticated` |
| Path sets | `rest-api-architect/templates/IdempotencyKeyInterceptor.java.example:94` — `PROTECTED_POST_PATHS = Set.of("/api/v1/users")`, compared with `getRequestURI()`. A `/v2` of the same `POST` would not require the key |
| Tests | `test-architect/templates/ControllerTest.java.example` builds every URI and the `Location` assertion from `UserController.BASE_PATH` |
| GraphQL | Evolves one schema with `@deprecated` and a snapshot test (`rules/api-graphql.md` § Evolution: "GraphQL has no `/v2`"); no URL version. Untouched by this record, and not reached by it: the prefix is scoped to `@RestController` classes of the REST adapter package (resolvers are `@Controller`), and the version is read only under `/api/` while the GraphQL endpoint is `/graphql`. `graphql-spec.md.example` cites REST paths as cross-references only, and their public URL does not change |

`/api/v1` appears in 24 files under `.claude/`. Two are Prometheus's own API (`docker-architect`
templates), five are history (`decisions/`, `lessons-learned/`), and every spec exemplar keeps
its public URL under the chosen strategy. What changes is the controller's path constant, the
`Location`, two method mappings in `security-architect` exemplars, the matcher, the interceptor's
path set, the controller test, `rest-spec.md.example`, step 4 of the skill, and the norm.

No `/new-feature` run has shown a versioning defect. What the model gets wrong without the
extension comes from the research behind the scenario, done on 2026-10-13 against the official
sources and re-checked the same day:

- Spring Framework reference, "API Versioning" and `@RequestMapping` § version
- `ApiVersionConfigurer`, `StandardApiVersionDeprecationHandler.VersionSpec`, `InvalidApiVersionException` Javadoc
- `WebMvcProperties` and `WebMvcAutoConfiguration` at Boot tag v4.1.1
- spring-framework #36395 and PR #36398
- springdoc #3354, #3232 and #2975
- RFC 9745 (`Deprecation`), RFC 8594 (`Sunset`)
- Zalando RESTful API Guidelines 106–115 and 185–191, Google AIP-185

Versions on the day:

| Component | Version |
|---|---|
| Spring Boot GA | 4.1.1, shipping Framework 7.0.9 |
| `usePathSegment(int, Predicate<RequestPath>)` | since 7.0.6 |
| springdoc | 3.1.1, targeting Boot 4.1 |

None of these is written into a norm or a template (invariant 8).

Facts the design rests on:

- A mapping with no `version` matches any version, at the lowest priority.
- `"N+"` is a baseline. It matches N and every supported version above it, until a higher mapping supersedes it.
- An unsupported version → `InvalidApiVersionException`, 400. `NotAcceptableApiVersionException` is its subclass, also 400, for a supported version with no mapping. No built-in 410.
- Boot has `spring.mvc.apiversion.{required, default, supported, detect-supported, use.*}`. There is no property for the path prefix or the predicate, so both need a `WebMvcConfigurer`.
- Boot injects a declared `ApiVersionDeprecationHandler` bean and creates none.
- `StandardApiVersionDeprecationHandler.configureVersion(v)` sets four values: deprecation date and link, sunset date and link.
- It writes `Deprecation: @<epoch>`, `Sunset` (RFC 1123) and `Link` with `rel="deprecation"` and `rel="sunset"`. It writes only when the request's version equals the configured one.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | None observed. Anticipation, overridden by the maintainer as in `0144`–`0149`. Specific to this case: the cost of waiting **grows** — every project generated today bakes `/api/v1` into its controllers, and changing the convention later needs a migration for each | Create nothing (as the whole answer) |
| 2 — trigger | Model decision inside `/new-feature`, in `rest-api-architect`'s interview; standalone by description | Form 2 |
| 5 — nature | Procedure (one interview axis, one partial block) plus declarative norms | Forms 4 and 5 as the whole answer |
| 6 — isolation | None — the interview is the task | Form 3 |
| 7 — mandatoriness | Persuasion. No hook: no failure observed (invariant 6) | New Forms 7 and 8 |
| 8 — destination | Both — the skill, its templates and the norm travel | — |
| — scope | **Versioning only.** Inbound admission (bulkhead, rate limit) has a fixed cost of waiting — it is additive per endpoint — and no business fact (a quota per client) was asked for. Deferred; § Out of scope keeps the intersections found | Versioning + bulkhead; versioning + bulkhead + rate limit |
| — container limits | `max-connections`, body and part size, graceful shutdown stay with `transport-security-setup`, which already owns `server.*` per project | Inside this design |
| — strategy | Path segment, native: `/api/{version}` prefix, version declared on each mapping. Header, media type and query parameter stay forbidden — § Why not a header | Literal `/api/v1` + native validation only; literal, no native; header |
| — setup | Decided in the project's first `30-rest.md`, inherited by every later case — the shape `graphql-api-architect` § 6 already uses | Generated by `project-bootstrap` on day zero |
| — after the sunset | 400, native: the version leaves the supported list, `InvalidApiVersionException` becomes a Problem Details body with its own `errorCode` | 410 with a kept list of removed versions |
| — deprecation window | Per deprecation, decided and recorded in the `30-rest.md` of the case that deprecates, justified by the usage-per-version metric | A fixed minimum in the norm |

## Why not a header

Asked by the maintainer during the interview; recorded so the question is not reopened without it.
The header strategy (`spring.mvc.apiversion.use.header`) was discarded for four reasons:

1. **The norm already forbids it, and every generated project exposes `/api/v1/...`.** Moving to a
   header changes the public URL — a break for every existing client. The chosen strategy keeps
   the URL byte for byte.
2. **springdoc.** With a header, one path carries every version: one operation with an `enum` of
   versions and `oneOf` bodies (maintainer quote in springdoc #2975). springdoc #3354 is still
   open: `/v3/api-docs` answers 500 when a default version is set and is missing from
   `allowableValues` — a workaround exists, but it is one more trap.
3. **Visibility.** In the path the version shows in access logs, `curl`, the browser and a
   gateway's routing. In a header an HTTP or CDN cache tells versions apart only through
   `Vary: API-Version`; a forgotten `Vary` serves a v1 body to a v2 client.
4. **Path matchers.** A security matcher can name the version in a path; it cannot in a header —
   which is also why the matcher rule below writes `/api/*/…`.

What the header had in its favour, and was weighed: a stable resource URL across versions (the
REST and Zalando position — Zalando prefers a media type), a `Location` that needs no version, and
no controller losing `/api/v1` from its constant. The controller code is identical under both
(`version = "1+"`); the difference is the configuration and the public URL.

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Versioning as part of `rest-api-architect` (Form 1, existing) + § Versioning in `rules/api-rest.md` (Form 4, existing) + one line in `rules/authorization.md` + templates + a migration | 7 | **Approved** |
| 2 | Same, with the norm in its own `rules/api-versioning.md` | 5 | Rejected — identical `paths`, always loaded together; GraphQL does not version by URL, so nobody else would cite it |
| 3 | New skill `api-versioning-architect`, its own partial | 3 | Rejected — two owners of one partial's endpoints |
| 4 | Create nothing | 3 | Rejected — the maintainer chose to anticipate |

### Option 1 — part of `rest-api-architect` + § Versioning (score 7)

**Motivator:** the version is one attribute of the same mapping `30-rest.md` already designs, at
the same moment (axis 2), and its project setup has a precedent in `31-graphql.md` § 6.

**Planned decisions inside the option:**

| # | Question | Answer |
|---|---|---|
| 1 | Configuration | One `ApiVersioningConfig` (`WebMvcConfigurer`) per project, in the REST adapter package, holding everything: `addPathPrefix("/api/v{version}", HandlerTypePredicate.forAnnotation(RestController.class).and(forBasePackageClass(ApiVersioningConfig.class)))` (corrected by the run — § Verified); `usePathSegment(1, path -> <under /api/>)` so `/v3/api-docs`, `/swagger-ui` and `/actuator` are never parsed; `setVersionRequired(true)` with no default; supported versions listed explicitly, `detectSupportedVersions(false)` — removing a version from the list is the sunset. No `spring.mvc.apiversion.use.*` property: Boot would register a second resolver next to the configurer's |
| 2 | Version values | Majors only: `1`, `2`, read from `v1`, `v2`. Whether the default `SemanticApiVersionParser` accepts the `v` is **not documented** — proven by the Phase 4 run; if it does not, the config sets a parser that strips it |
| 3 | Mapping | Class mapping relative to the prefix (`/users`); every handler method declares `version = "1+"`. A breaking change in a later case adds a mapping at the next version as a new baseline (`"2+"`); the old one stays until its sunset. A mapping with no `version` is never written — it would answer every version |
| 4 | `Location` | Still relative, now built from the request's own path plus the new id — never an absolute URI from the request, never a constant missing the version. `rules/api-rest.md` § Success statuses changes its "same path constant" sentence |
| 5 | What is breaking | The list already in `rules/api-rest.md` stays; the per-case interview axis becomes "does this case change an existing endpoint in a way that list calls breaking?", replacing "internal or public → cost of a `/v2`" |
| 6 | Project setup in the partial | A new `30-rest.md` block, `Versioning`: the first REST case of the project writes strategy, prefix, supported versions and the deprecation table (NEW); a later case writes `inherited from UC-NNN`, or the delta it introduces — a new version, a deprecation, a sunset (CHANGE of `ApiVersioningConfig`) |
| 7 | Deprecation | Per **version**, never per endpoint: one `StandardApiVersionDeprecationHandler` bean. `Deprecation` and `Sunset` both mandatory for a deprecated version; `setDeprecationLink` to the migration notes; `setSunsetLink` never written (Zalando 189). The window is per deprecation, with the usage metric that justifies it named in the case's partial |
| 8 | After the sunset | The version leaves the supported list and its mappings are deleted. `ApiExceptionHandler` maps `InvalidApiVersionException` (its 400 subclass included) to 400 `API_VERSION_UNSUPPORTED`, and `MissingApiVersionException` to 400 `API_VERSION_MISSING`. Both thrown during handler lookup, before any controller: the Phase 4 run proves the `@RestControllerAdvice` catches them; if it does not, the handler extends the framework's `ResponseEntityExceptionHandler` path for them. Two rows in `rules/api-rest.md`'s status table |
| 9 | Usage per version | With the prefix, the `uri` tag of `http.server.requests` reads `/api/{version}/users` and the version is gone. A `ServerRequestObservationConvention` adds a low-cardinality `api.version` tag, bounded by the supported list (`rules/observability.md` § cardinality, cited). Where the resolved version is read from — a request attribute — is checked in the Phase 4 run |
| 10 | Security | `rules/authorization.md` gains one line: a path matcher names every version of its route (`/api/*/orders/*/cancellation`), never one — a new version of a protected route otherwise falls to the catch-all. `SecurityConfig.java.example` follows; § How to verify gains a grep for `requestMatchers(… "/api/v[0-9]`. The two `@PostMapping("/api/v1/...")` in `security-architect` exemplars become relative paths with `version` |
| 11 | Idempotency | `IdempotencyKeyInterceptor.java.example` drops the literal path set for the handler-annotation lookup its own comment already documents; `IdempotencyKeyInterceptorTest.java.example` follows |
| 12 | Tests | `ControllerTest.java.example` requests the literal public URI (`/api/v1/users`) through a test constant, and asserts the `Location` with the version. `.apiVersion(...)` and `apiVersionInserter` are not used — a literal URI reaches the server's resolver unchanged (inferred from the Javadoc; proven by the Phase 4 run). New cases: an unsupported version → 400 `API_VERSION_UNSUPPORTED`; a deprecated version → `Deprecation` and `Sunset` present |
| 13 | OpenAPI | springdoc must list `/api/v1/users`, one path per version, never `/api/{version}/users`. **Not documented** by springdoc for a path prefix — proven by the Phase 4 run; if it renders the template, the fix is decided then, before anything ships |
| 14 | Existing projects | Migration `api-version-path-prefix`, every blueprint. Note: nothing breaks if left; it is required before the first `/v2` or deprecation, and `ApiVersioningConfig` must not be added before every controller drops `/api/v1` from its path — the prefix would double it (`/api/v1/api/v1/...`). Prompt: move the version out of every class mapping, add `version = "1+"` to every handler method, add the config, the exception rows and the observation convention, fix matchers and the interceptor, change no public URL, run `./mvnw verify` |

**Pros:** no new skill, no new pipeline step, no new partial; the public URL of every existing
project stays as it is; the per-case question is one axis; the setup follows the GraphQL shape.

**Cons:** propagation across three skills' exemplars (`rest-api-architect`, `security-architect`,
`test-architect`), two norms and a migration; five runtime facts are inferred or undocumented and
need a real-project run before the templates are final (planned decisions 2, 8, 9, 12, 13).

**Points cut in the rubric:** 1 (form fit — no signal, overridden) · 5 (maintenance: propagation
across three skills and a migration) · half of 6 (the native API is new; no precedent in the repo
for a `WebMvcConfigurer` exemplar).

**CI:**

- `validate` › `design` › `frontmatter schema`: the skill, its class and sections, `export` (unchanged — no new piece).
- `validate` › `exemplar-imports`: every import of the new `.java.example` files against a real Initializr project.
- `validate` › `design` › `MigrationsSchemaTest`: the migration entry.
- **New:** `.claude/.ci/ApiVersionTemplateTest.java`, a step of `validate` › `design` next to `GraphQlTemplateTest`, offline. It holds:
  - no `@*Mapping` in any exemplar carries `/api/v`;
  - every handler method of the controller exemplars declares `version`;
  - the config exemplar calls `addPathPrefix("/api/{version}"` and the two-argument `usePathSegment(1,`, with no `detectSupportedVersions(true)`;
  - no yml exemplar sets `spring.mvc.apiversion.use.`;
  - the deprecation exemplar never calls `setSunsetLink`;
  - no `requestMatchers` names a version;
  - the interceptor has no path set;
  - the controller test does not build URIs from `BASE_PATH`;
  - `ApiExceptionHandler` handles both version exceptions.

  Proven green on the tree and red with each defect injected (`0099`).

### Option 2 — own `rules/api-versioning.md` (score 5)

Its `paths` would be the REST adapter package, identical to `api-rest.md`'s: the two always load
together, and `api-rest.md` already owns the breaking-change list a versioning norm needs. GraphQL
does not version by URL, so no second citer exists. Points cut: 1, 3 (two files loaded for one
topic), 5.

### Option 3 — new skill `api-versioning-architect` (score 3)

A separate step would read and rewrite the mappings `30-rest.md` decides — two owners of one
partial's endpoints. Fails the `0005` split by moment and artifact.

### Option 4 — create nothing (score 3)

What the matrix recommends with no symptom (anti-pattern 9). The first `/v2` of a generated project
would be designed with no deprecation policy, no supported list, a `Location` and matchers bound to
v1, and a role check silently weakened on the new route.

## Verified against a real project

On 2026-10-13, an Initializr project (Boot 4.1.1, Framework 7.0.9, springdoc 3.1.1 resolved from
Maven Central's `maven-metadata.xml`) ran the planned shapes under `@SpringBootTest` and
`@WebMvcTest`. Five planned decisions rested on undocumented behaviour; the run settled each, and
three changed the design:

| Planned decision | Found | Shipped |
|---|---|---|
| 1 — prefix `/api/{version}` scoped by `@RestController` | springdoc lists `/api/1/users`; and springdoc's own endpoint is a `@RestController`, receives the prefix, and `/v3/api-docs` answers 404 | Prefix `/api/v{version}`, scoped by `forAnnotation(RestController.class).and(forBasePackageClass(ApiVersioningConfig.class))` — springdoc then lists `/api/v1/users` and `/api/v2/users/{id}` |
| 2 — does the parser accept `v1` | Yes: `v1` resolves to `1.0.0` | Supported versions written as `"1"` |
| 8 — `errorCode` `API_VERSION_UNSUPPORTED`, and `MissingApiVersionException` | The advice catches `InvalidApiVersionException` thrown during handler lookup. A path with no version segment matches no mapping and answers 404 (`NoResourceFoundException`) — `MissingApiVersionException` never surfaces with this strategy. `rules/api-rest.md` § Error body already makes a structural 400 carry `violations` and never `errorCode` | One handler, `InvalidApiVersionException` → 400 with `violations` on `version`, no `errorCode`; no handler for the missing case |
| 9 — the resolved version for the metric | `HandlerMapping.API_VERSION_ATTRIBUTE` holds an `ApiVersionHolder`, not the version; `/api/v999` and `/api/vabc` carry no version in it | The convention reads `holder.hasVersion()` → `1.0.0`, `2.0.0`; `none` otherwise — bounded |
| 12 — literal URI in tests | `/api/v1/users` reaches the resolver unchanged in both test slices; the `WebMvcConfigurer` loads in `@WebMvcTest` | Tests call the literal URI through a constant; no `.apiVersion(...)` |
| 13 — OpenAPI paths | One path per version once the prefix is `/api/v{version}`; but each operation is listed only under its mapping's starting version — `GET /api/v2/users`, served through the `1+` baseline, is not listed | Recorded in the norm and in the migration notes' content; not worked around |
| 4 — `Location` without a new parameter | `ServletUriComponentsBuilder.fromCurrentRequestUri().build().getPath()` + `pathSegment(id)` answers `/api/v2/users/u%201` on a v2 call | `locationOf(id)` in the controller; `UserApi`'s signature unchanged |

Also proven: `Deprecation: @1790812800`, `Sunset` in RFC 1123 and `Link …; rel="deprecation"` on a v1
response once v1 is configured deprecated, none on v2; `/actuator/health` untouched; the
`IdempotencyKeyInterceptor` exemplar and its rewritten test, compiled and run (10 green).

## Out of scope — inbound admission, deferred

Kept here so the next design starts from it, not from zero. Nothing below is decided.

- `rules/api-rest.md` lists `429 + Retry-After`, and nothing decides who emits it. 503 exists only
  through `DependencyUnavailableException` (outbound).
- Intersections with `http-client-architect` found on 2026-10-13:
  1. an inbound rejection is not `DependencyUnavailableException` (there is no dependency);
  2. one bulkhead mechanism per project — `@ConcurrencyLimit(policy = REJECT)` vs the Resilience4j semaphore the outbound side already brings — and an instance namespace that cannot collide with `<dependency>`;
  3. who adds 429 and admission's 503 to `ApiExceptionHandler`;
  4. the limiter runs before `IdempotencyAspect` claims the key, and after authentication when the key is the principal;
  5. 429/503 always with `Retry-After`, coherent with `rules/http-client.md` § Retry.
- Generated projects do not enable virtual threads, so Tomcat's pool is still the scarce resource.
- A quota per client is a business fact — a row of `00-caso-de-uso.md` like `Freshness`, if it ever
  comes.
- Container limits stay with `transport-security-setup`.

## References

| Claim | Source |
|---|---|
| A mapping with no version matches any version, lowest priority; `"N+"` baseline | Spring Framework reference, `@RequestMapping` § API Version |
| `usePathSegment(int, Predicate<RequestPath>)` since 7.0.6 | `ApiVersionConfigurer` Javadoc; spring-framework PR #36398 |
| No Boot property for prefix or predicate; a declared deprecation handler bean is injected | `WebMvcAutoConfiguration` at v4.1.1 |
| Unsupported version → `InvalidApiVersionException`, 400; no 410 | Spring Framework reference "API Versioning"; exception source at v7.0.9 |
| Header formats and per-version scope | `StandardApiVersionDeprecationHandler` source at v7.0.9; RFC 9745; RFC 8594 |
| springdoc #3354 open, header strategy only | github.com/springdoc/springdoc-openapi/issues/3354 |
| `Link rel="sunset"` discouraged | Zalando RESTful API Guidelines, rule 189 |
| Project setup decided by the first case, inherited after | `graphql-api-architect/SKILL.md` step 8, `31-graphql.md` § 6 |
| Extension of an existing skill and norm over a new piece | `0147`, `0149` |
| Migration entries: shape and when `arch-adopt` prints them | `extensions.json` › `migrations`; `0104` |
| Per-skill exemplar test, offline, in `validate` › `design` | `.claude/.ci/GraphQlTemplateTest.java` (`0146`) |
| Anticipation overridden by the maintainer | `0144`–`0149` |

## Propagation

| File | Change |
|---|---|
| `.claude/rules/api-rest.md` | § Versioning (new); § Resources and URIs points to it; `Location` from the request's own path; a 400 row for an unsupported version; the structural-400 sentence names it; § How to verify gains two greps |
| `.claude/rules/authorization.md` | § Default access: a path rule names every API version; § How to verify: a grep for a version-bound `requestMatchers` |
| `.claude/rules/00-index.md` | `api-rest.md` row |
| `.claude/skills/rest-api-architect/SKILL.md` | `description`; step 2 survey grep; step 3 breaking-change axis; step 4 public path + `version`; step 7b (new); seven numbered blocks; the partial table's Versioning row; Contract reads |
| `.claude/skills/rest-api-architect/templates/ApiVersioningConfig.java.example` | new |
| `.claude/skills/rest-api-architect/templates/ApiVersionObservationConvention.java.example` | new |
| `.claude/skills/rest-api-architect/templates/Controller.java.example` | class mapping `/users`, `version = "1+"` on every handler, `locationOf` from the request path; `BASE_PATH` gone |
| `.claude/skills/rest-api-architect/templates/ApiExceptionHandler.java.example` | `InvalidApiVersionException` → 400 `violations` on `version` |
| `.claude/skills/rest-api-architect/templates/IdempotencyKeyInterceptor.java.example` | protected routes by `@Idempotent`; the literal path set and the stale "variant" block gone |
| `.claude/skills/rest-api-architect/templates/rest-spec.md.example` | `Version` column, § 7 · Versioning, the error row and the test case |
| `.claude/skills/rest-api-architect/templates/Api.java.example` | one comment line |
| `.claude/skills/security-architect/templates/SecurityConfig.java.example` | `/api/*/orders/*/cancellation`; the reason in the header |
| `.claude/skills/security-architect/templates/MethodSecurityAnnotations.java.example`, `CurrentActorResolution.java.example` | relative mappings with `version = "1+"` |
| `.claude/skills/test-architect/templates/ControllerTest.java.example` | literal public URI constant, the unsupported-version case |
| `.claude/skills/test-architect/templates/IdempotencyKeyInterceptorTest.java.example` | handlers as `HandlerMethod` over a fixture, no paths |
| `.claude/skills/new-feature/SKILL.md`, `templates/feature-spec.md.example` | seven blocks; the Versioning row and step 14 |
| `.claude/agents/java-spring-boot-developer.md` | Block 3: the two config classes, NEW/CHANGE from § 7 |
| `.claude/schemas/extensions.json` | migration `api-version-path-prefix`, every blueprint |
| `CLAUDE.md`, `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | REST routing row |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` | the versioning pitfall |
| `.claude/.ci/ApiVersionTemplateTest.java`, `.github/workflows/validate.yml`, `docs/*/07-ci-validate.md` | the CI check below |

Goes to the generated project: **yes** — the norms, the skill and its templates travel through
`export` as before; no new piece, so `export` and `skill_classes` are unchanged. The migration
reaches projects generated earlier through `arch-adopt`.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › REST exemplars version every handler from one configuration` (`.claude/.ci/ApiVersionTemplateTest.java`, new) | No exemplar binds a mapping, a request rule, a `BASE_PATH` or a path set to one API version; every `@RestController` handler declares `version`; the config's prefix, scope, predicate, required, no detection, no default, no sunset link; no `spring.mvc.apiversion.*` in a yml; the handler's structural 400; the convention reads the holder; `Location` from the request path; the controller test's literal URI and unsupported-version case | Green on the tree. Red, each by name, with ten defects injected one at a time: class mapping `/api/v1/users`, a handler with no `version`, `requestMatchers` on `/api/v1/…`, an interceptor path set, prefix `/api/{version}`, prefix scoped by annotation alone, the convention reading the attribute raw, the handler without the `version` violation, the test built from `BASE_PATH`, `spring.mvc.apiversion` in a yml exemplar. Two false positives on the first run — an outbound Feign interface's `@GetMapping` and `spring.http.serviceclient.<group>.apiversion` — narrowed to `@RestController` files and the `mvc` key |
| `validate · exemplar-imports` | The new imports (`org.springframework.web.accept`, `HandlerTypePredicate`, `ApiVersionConfigurer`, `org.springframework.http.server.observation`, `io.micrometer.common`, `ServletUriComponentsBuilder`) exist in the reference project's JARs | Runs in CI; the same imports compiled in the verification project |
| `validate · design › frontmatter schema`, `MigrationsSchemaTest`, `TemplateCommentsTest` | The skill's frontmatter and class, the migration entry, no body comment in an exemplar | Green; `TemplateCommentsTest` caught two `//` lines in `SecurityConfig.java.example`, moved to the header |
