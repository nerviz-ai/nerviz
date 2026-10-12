# 0144 · A design skill owns application caching: freshness asked as a business fact, Spring Cache in an outbound cache adapter

- **Date:** 2026-10-12
- **Scenario:** "Criar a skill de design cache-architect: partial 22-cache.md, passo condicional
  do /new-feature entre persistência (passo 5) e testes (passo 6). Escopo: Spring Cache
  (spring-boot-starter-cache) sobre Caffeine (local) e Redis (distribuído, Spring Data Redis),
  cache-aside via @Cacheable / @CacheEvict / @CachePut no adapter de saída ou num decorator do
  port de saída — nunca no use case nem no domínio. Fora de escopo: cache HTTP
  (Cache-Control/ETag, é do rest-api-architect) e WebFlux."
- **Decision:** Option 1 — Form 1 `.claude/skills/cache-architect/SKILL.md` (class `design`,
  partial `22-cache.md`) + Form 4 `.claude/rules/caching.md` (territory `.cache`); a `Freshness` row
  in the use case header; a `.cache` package key in every blueprint; `/new-feature` runs it as step
  5b, after persistence and before tests.
- **State:** approved by Lucas Fernandes, on 2026-10-12 — option 1, with templates for several
  cache scenarios (Caffeine, Redis), the rule, the blueprints, the propagation and the CI test
  proven green and red

## What the context showed

Nothing in this repository owns application caching today:

| Concern | Owner today |
|---|---|
| Whether a read may be served stale, and how stale | nobody — `use-case-design`'s header has no row for it |
| `@Cacheable` / `@CacheEvict`, cache manager, TTL, size | nobody — no norm, no template, no executor block |
| A Redis service in the compose file | nobody — `docker-architect` has no Redis template |
| Hibernate second-level cache | nobody — `persistence.md` mentions only the first-level cache (batch `UPDATE` bypasses it) |
| HTTP caching (`Cache-Control`, `ETag`) | `rest-api-architect` — out of this scope |

No `/new-feature` run has shown a cache defect yet. What the model gets wrong without a piece is
taken from the official documentation, not from a run — the research below.

## Research — Spring caching pitfalls (2026-10-12)

Condensed; the full list, one source per item, becomes `cache-architect/references/pitfalls.md`.
Read against Spring Framework 7.0.9, Spring Boot 4.1.1 and Spring Data Redis 4.1.1 — versions as
read that day, never written into a template (invariant 8).

| Group | Pitfall | Source |
|---|---|---|
| Proxy — silent | Self-invocation and non-public methods are never cached, with no error | Framework, *Declarative Annotation-based Caching* |
| Proxy — silent | `@EnableCaching` missing caches nothing; on the main class it forces caching in every test | Framework · Boot, *Caching* |
| Key | The default key is every parameter; an input read from the `SecurityContext` or a `ThreadLocal` is left out, and one user's result is served to another — the hit also skips the authorization code above it | Framework · OWASP *Web Cache Security Cheat Sheet* |
| Value | A JPA entity in the cache carries lazy proxies (`LazyInitializationException`) and Hibernate proxy classes into serialization; a local cache hands every caller the same mutable instance | Framework · Hibernate docs |
| Value | Spring Data Redis serializes values with `JdkSerializationRedisSerializer` by default; the Jackson 3 serializer enables no default typing, and enabling it needs a `PolymorphicTypeValidator`; a format without a version breaks a rolling deploy | Data Redis, *Redis Cache* and *Upgrading* · Builders' Library |
| Expiry | Redis entries never expire by default; the Simple provider has no TTL and no size and is "not really recommended for production"; Caffeine without `maximumSize` grows until the heap does; `spring.cache.caffeine.spec` is one spec for every cache | Data Redis · Boot |
| Consistency | Eviction runs after the method, before the commit, unless the cache manager is transaction-aware (off by default); a concurrent reader can still repopulate the old value (*stale set*); every other writer — a job, a consumer, a JPQL `UPDATE` — must evict too; a local cache evicts on one replica only | Framework, `TransactionAwareCacheManagerProxy` · Data Redis · *Scaling Memcache at Facebook* |
| Consistency | `allEntries = true` on Redis runs `KEYS` by default | Data Redis |
| Load | Nothing locks by default; `sync = true` is per JVM; the default Redis writer takes no lock; a deploy or flush empties every local cache at once | Framework · Data Redis · Builders' Library |
| Failure | A cache exception reaches the caller by default — Redis down fails a read the database could serve; `LoggingCacheErrorHandler` logs and continues | Framework, `LoggingCacheErrorHandler` |
| Test | `spring.cache.type=none` or the Simple provider in tests hides the silent proxy failures, the missing evict and every serialization defect | Boot · this record |

**Second-level cache, weighed and left out.** Hibernate's L2 invalidates on its own for writes
through the same session factory and handles commit timing with soft locks, but caches only
entities by id; a query needs the query cache, which stores ids (the N+1 Vlad Mihalcea
documents) and is invalidated by any write to its tables. It never sees another service writing
the database, nor a non-JPA source. The reads this skill exists for — a statement, a balance, an
expensive outbound call — are query-shaped, where L2 does not help.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | None observed in a run. The maintainer decides to anticipate, on the documented pitfalls — anti-pattern 9 overridden on purpose, and recorded as such | Create nothing (as the whole answer) |
| 2 — trigger | Model decision inside `/new-feature`, and standalone by description | Form 2 |
| 5 — nature | Multi-step procedure: inherit freshness and replicas, choose provider, list every writer, write the partial | Forms 4 and 5 as the whole answer |
| 6 — isolation | None — the interview is the task | Form 3 |
| 7 — mandatoriness | Persuasion; no hook without an observed failure (anti-pattern 16) | Forms 7 and 8 |
| 8 — destination | Both — a development skill, travels in `export.skills.include` | — |
| — freshness | A business fact: a new `Freshness` row in `00-caso-de-uso.md`'s header, asked by `use-case-design`; the cache skill only inherits it | Asking it inside `22-cache.md` |
| — trigger condition | The step runs only when `Freshness` holds a tolerance; `none` or no row skips it. No cache born from "this query looks expensive" | Triggering on a slow query in `20-persistencia.md` |
| — norm | Cache code norms become their own rule, loaded by `paths` | A section of `persistence.md`; the skill's `references/` only |
| — package | A new `packages.map` key ending in `.cache` (hexagonal: `adapter.out.cache`) in every blueprint | Cache inside the persistence adapter; config in `.config` with annotations spread over existing adapters |
| — providers | Templates for Caffeine and Redis only; two-level, Hazelcast, JCache in the reference matrix | A two-level template |
| — cache down | Degrade: `LoggingCacheErrorHandler`, read goes to the source, short client timeout. No new family in `error-handling.md` | A 503 family |
| — Hibernate L2 | Out of scope; the reference matrix records when it would win (read-mostly data by id, one writer, every write through Hibernate) | `cache-architect` or `persistence-architect` emitting it |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `skills/cache-architect/` (Form 1, class `design`) + `rules/caching.md` (Form 4, territory `.cache`) + a `Freshness` row in the use case header + a `.cache` package key in every blueprint | 7 | **Approved** |
| 2 | Same skill and rule, no package key: the decorator lives in the adapter it wraps (`.persistence`, `.http`), the manager in `.config` | 5 | Rejected — the rule would load on every persistence and HTTP file, cache or not |
| 3 | `skills/cache-architect/` alone, cache norms in its `references/` | 4 | Rejected — the executor never loads a design skill's `references/` |
| 4 | Create nothing | 3 | Rejected — the maintainer chose to anticipate (axis 1) |

### Option 1 — skill + `rules/caching.md` + `Freshness` + `.cache` package (score 7)

**Motivator:** axis 1 (the maintainer's decision to anticipate, on documented pitfalls), the
freshness answer and the norm answer — a cache decision has a business half nobody asks today
and a code half no norm covers.

**Pros:** one owner for "may this read be stale, and how is it kept honest"; the business half
asked where business facts are asked, so no cache is born without a stated tolerance; the
executor gets the norms by `paths` on a territory of their own, never by remembering a skill's
reference; a decorator of the outbound port wraps a persistence or an HTTP adapter alike, and
the domain and application never see the cache; same shape as `0087`, the closest precedent.

**Cons:** the widest propagation of the four — `use-case-design`'s header and question list,
`new-feature` (step 5b, consolidation block, short path), the executor (a Block C), the
`implement` group table, `test-architect`, `docker-architect` (a Redis service template and its
decision row), eight blueprints, `extensions.json`, routing and docs. Approved specs keep no
`Freshness` row, so a read case written before this version never triggers the step until a
new case touches it. Anticipation, not a symptom: if no run ever needs it, it is paid in
maintenance for nothing.

**Points cut in the rubric:** 1 (form fit — § 3 of the decision matrix asks for a signal, and
there is none; overridden by the maintainer) · 5 (maintenance: many files touched).

**CI:** `validate` › `design` › `frontmatter schema` already proves the skill's class, sections,
`export` entry and the rule's `paths` and `derived_paths`; the `design` job's leaf, single-owner
and "every norm paths matches some blueprint" steps cover the rule. New: (a)
`.claude/.ci/CacheTemplateTest.java` in `validate` › `design` — fails by name when a cache
manager exemplar leaves a cache without a TTL or (Caffeine) a `maximumSize`, keeps the JDK
serializer, registers no `CacheErrorHandler`, or when any exemplar in `.claude/skills/**/templates`
outside `cache-architect` carries `@Cacheable`/`@CachePut`/`@CacheEvict`, or puts
`@EnableCaching` on a `@SpringBootApplication`; (b) `exemplar-imports` gains the `cache` and
`data-redis` Initializr dependencies and Caffeine, so the new exemplars' imports resolve; (c) the
Redis compose template is covered by `design` › `compose service templates merge and parse`
with no change.

**Planned shape:**

```
use-case-design ── Freshness row (business fact) ──┐
                                                   ▼
new-feature  step 5 persistence ─► step 5b cache-architect ─► step 6 tests
                                     │ reads 00 (Freshness), 10 (ports), 20/28 (adapters),
                                     │       35/20 (replica count)
                                     │ writes 22-cache.md: cached reads + keys, provider,
                                     │       TTL and size per cache, every writer that evicts,
                                     │       failure, serialization, metrics, compose service,
                                     │       declared dependencies, configuration, deferred
                                     ▼
executor  group adapters: H, 3, S, M, J, C ── Block C loads rules/caching.md by paths
                                               on **/adapter/out/cache/** (blueprint-derived)
test-architect ── hit, evict on every write, key per user — against a real Redis
docker-architect ── redis-service.yml.example, decision row exists/external/materialize/pending
```

### Option 2 — no package key (score 5)

No blueprint edits, but the rule's territory becomes the persistence and HTTP adapter packages:
it loads on every repository and client file whether or not a cache exists there (criterion 3),
and the decorator shares a package with the class it wraps, which hides the boundary the rule
draws. Cut: 3, 5, 6 (`0087` and `0121` gave each new adapter kind its own key).

### Option 3 — skill only, norms in `references/` (score 4)

The executor never loads a design skill's `references/`, so the norms reach code only through
each partial, restated per use case — the option `0087` rejected for the same reason. A norm
owned by a procedure file strains invariant 2. Cut: 2, 4, 6.

### Option 4 — create nothing (score 3)

What the decision matrix recommends with no symptom (anti-pattern 9). Not chosen: the maintainer
decided to anticipate (axis 1), and with nothing in place a case asking for latency gets a cache
designed by no one — no stated tolerance, no evict list, no TTL.

## References

| Claim | Source |
|---|---|
| A pipeline design piece is Form 1, not Form 2 | decision matrix § 4 · `decisions/0007-pipeline-skills-invocation.md` |
| No agent: the interview is the task | decision matrix § 5 |
| No signal → create nothing, unless overridden | decision matrix § 3 and anti-pattern 9 |
| Norm in one owner, cited by path; a rule never names a skill | `@CLAUDE.md` invariants 1 and 2 |
| Boilerplate only in `templates/*.example` | `@CLAUDE.md` invariant 3 |
| Package territory is blueprint data, with an `export.derived_paths` entry | `@CLAUDE.md` invariants 7 and 9 · `derived_paths["scheduling.md"]` |
| Versions and coordinates resolved at runtime | `@CLAUDE.md` invariant 8 |
| Design skill + own rule + package key, the precedent | `decisions/0087-jobs-architect-skill.md` · `decisions/0121-http-client-architect-skill.md` (`adapter.out.http`) |
| A compose service decided at design, materialized at implement | `decisions/0137-pending-compose-service-decided-at-design-materialized-at-implement.md` |
| Executor blocks run in groups; a new block joins `adapters` | `decisions/0130-executor-split-by-block-group-implement-in-clean-session.md` |
| A design skill's `references/` never reach the executor | `decisions/0087-jobs-architect-skill.md` option 2 |
| Self-invocation, visibility, `sync`, default key, eviction timing | Spring Framework 7.0.9, *Declarative Annotation-based Caching* |
| Provider order, `spring.cache.*`, no `@EnableCaching` on the main class, `none` in tests | Spring Boot 4.1.1, *Caching* |
| Redis defaults: JDK serializer, no TTL, nulls cached, lock-free writer, `KEYS` on clear, `transactionAware` off | Spring Data Redis 4.1.1, *Redis Cache* |
| Jackson 3 serializer without default typing | Spring Data Redis 4.0, *Upgrading* |
| `LoggingCacheErrorHandler` exists since 5.3.16 | Spring Framework javadoc |
| Cache data versioned like persisted data; request coalescing; cache-down load | Amazon Builders' Library, *Caching challenges and strategies* |
| Cache key without the requester's identity serves one user's data to another | OWASP, *Web Cache Security Cheat Sheet* |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/cache-architect/SKILL.md` | New — entry rule (a stated `Freshness` tolerance and `10-dominio.md`), inherit-first survey, interview, matrix-driven provider, ten-block partial, `redis` compose row |
| `.claude/skills/cache-architect/references/pitfalls.md` | New — 36 pitfalls in eight groups, each with the rule section that prevents it and its source |
| `.claude/skills/cache-architect/references/provider-decision-matrix.md` | New — Caffeine, Redis, refresh-ahead; two-level, grids and Hibernate L2 recorded as reference only |
| `.claude/skills/cache-architect/templates/*.example` | New — `CaffeineCacheConfig` (+ `CacheSettings`, `CacheNames`, `CountingCacheErrorHandler`), `RedisCacheConfig`, `CachedPortAdapter` (persistence port read + evicting write, HTTP port read), `RefreshAheadCache`, `application-cache.yml`, `cache-spec.md` |
| `.claude/rules/caching.md` | New norm — boundary, annotations and proxies, keys, values, expiry and size, invalidation, concurrency and load, failure, observability, tests, How to verify |
| `.claude/rules/00-index.md` | `caching.md` row; listed with the package-territory rules |
| `.claude/schemas/extensions.json` | `skill_classes.design.skills` (and its count), `export.skills.include`, `derived_paths["caching.md"]` (optional, suffix `.cache`) |
| `.claude/blueprints/*/*.yaml` (8) | A `packages.map` key ending in `.cache`; layered also gains the `cache` glob and its dependency rules (nobody but the service reaches it, it reaches only the repository or client) |
| `.claude/skills/use-case-design/SKILL.md` + `templates/use-case-spec.md.example` | `Freshness` row and interview block (seven blocks); a cache, Redis or TTL question is never asked there |
| `.claude/skills/new-feature/SKILL.md` + `templates/feature-spec.md.example` | Step 5b, order and its reason, short-path criterion, precedence row (an expiry above the tolerance stops), declared-dependency and compose gates on `22-cache.md`, spec block `3.4 Cache`, `Freshness` row |
| `.claude/agents/java-spring-boot-developer.md` | Block C (C1-C3) in the `adapters` group after Block J; reads `caching.md`; writes `[cache]/**`; cache dependencies from § 8; Block 4 cache tests |
| `.claude/skills/new-feature-implement/SKILL.md` | `adapters` group lists C |
| `.claude/skills/test-architect/SKILL.md` + `templates/CachedAdapterIT.java.example` | Reads `22-cache.md`; three cases per cached read against the real provider |
| `.claude/skills/docker-architect/SKILL.md` + `templates/redis-service.yml.example` + `references/compose-service-decision.md` | Redis as a cache service (no volume, `allkeys-lru`, password `:?`), `.env` key, `app` variables, catalog row; the decision reference names its new reader |
| `.claude/skills/gof-design-patterns/SKILL.md`, `references/pattern-catalog.md`, `templates/Decorator.java.example` | The Decorator exemplar was a hand-rolled cache inside the persistence package — aggregate as value, no expiry, eviction before the commit, a second norm for caching injected into the executor. Rewritten as an audit decorator; cache now named a "when not" that `caching.md` owns. Found by `CacheTemplateTest` on its first run |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example` | Routing row for the generated project |
| `CLAUDE.md` | Routing row |
| `README.md`, `claude-help.md`, `roadmap.md` | Pipeline, design-skill list, norm count 19 → 20, skill count 24 → 26 (it already trailed the disk by one) |
| `docs/{en,pt-br}/00,01,02,03,05,07,09,11` | Overview graph, skill lists, export list, pipeline diagram and tables, `.cache` role, CI node and row, the ownership pitfall |
| `.claude/.ci/CacheTemplateTest.java` + `.github/workflows/validate.yml` | New test in `design`; `exemplar-imports` gains `cache`, `data-redis` and Caffeine; two Jackson 2 serializers on the denylist; `REDIS_PASSWORD` for the compose merge step |

Goes to the generated project: **yes** — `cache-architect` via `export.skills.include`, `caching.md`
via `export.rules` with its glob derived from the blueprint's `.cache` entry. Checked with
`ArchHook.java export` into seven blueprints, each `paths` derived right
(`**/adapter/out/cache/**`, `**/cache/**`, `**/config/cache/**`, `**/infrastructure/cache/**` ×2,
`**/app/cache/**`, `**/shared/internal/adapter/cache/**`) and `schema` green inside each.

Not changed, on purpose: no `migrations` entry. A project generated before this version has no
cache this rule governs, and its approved specs carry no `Freshness` row, so nothing is left
behind; a project that cached by hand is caught by the skill's survey (step 2) when a case
touches that read.

**Found while writing, recorded here:** `sync = true` is refused at startup next to `unless`, a
second cache name or another operation on the method (`CacheAspectSupport`) — so a read that may
return absence cannot use it; the hexagonal and modular-monolith rule "adapters do not talk to
each other" is why the decorator depends on the port and gets its delegate by bean name instead
of importing the adapter class.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › cache exemplars decide every library default, and only they cache` (`.claude/.ci/CacheTemplateTest.java`) | The cache exemplars carry every decision `caching.md` makes explicit, and no other exemplar caches | green on the tree (4 exemplars, 3 caches); red, by file and reason, on each of 13 injected defects: Caffeine without `maximumSize`; Redis with the JDK serializer; Redis without `transactionAware`; a `@Cacheable` without `key`; `sync` with `unless`; a decorator importing the persistence adapter; Redis without `CacheErrorHandler`; a yml cache without `ttl`; a password with a default; a `CacheNames.X` used and not declared; a `@Cacheable` in `persistence-architect`'s adapter, plain and fully qualified; `@EnableCaching` on the main class. The fully qualified annotation passed green on the first try — a regex gap in the test, fixed before this run; the main-class injection first landed in a header comment, re-injected on the annotation line |
| `validate · design › compose service templates merge and parse` | `redis-service.yml.example` merges into the base compose | green with `REDIS_PASSWORD` set; red without it, which is why the step's `env` gained the line |
| `validate · exemplar-imports` | Every import of the new exemplars resolves | reproduced locally against a Boot 4.1.1 Initializr project with `cache,data-redis` and Caffeine: every cache import resolves (Caffeine, Spring Data Redis, `org.springframework.cache.*`, `CaffeineCacheMetrics`, `MockitoBean`, `GenericContainer`, `ServiceConnection`); the only misses were the tracing, HttpClient 5, Resilience4j and WireMock jars the CI step adds to its pom and the local run did not |
| `validate · design › frontmatter schema`, `rules is a leaf`, `every norm paths matches some blueprint`, `no hardcoded Spring/Java version` | Skill class, sections, export entry, rule `paths` and `derived_paths`, the leaf, the globs, the dated versions in the references | green; the version check first caught "Spring Boot 4.1.1" in `pitfalls.md`, reworded to "Boot 4.1.1" |
