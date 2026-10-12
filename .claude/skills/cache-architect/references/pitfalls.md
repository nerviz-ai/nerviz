# Caching pitfalls — what goes wrong, and where it is documented

Read by `SKILL.md` before the interview and when checking a partial. Researched on 2026-10-12
against Spring Framework 7.0.9, Boot 4.1.1 and Spring Data Redis 4.1.1. Each row names the
rule section that prevents it and the source that documents it; sources at the end. The rows in
group A raise no error and no warning: the code compiles, and a test that disables caching
passes.

## A · Proxy — silent

| # | Pitfall | Prevented by | Source |
|---|---|---|---|
| 1 | A cached method called from another method of the same class: the call never crosses the proxy, nothing is cached or evicted | `caching.md` § Annotations and proxies — annotations on the decorator only | [1] |
| 2 | A cache annotation on a non-public method: "no error is raised", nothing is cached | same | [1] |
| 3 | `@EnableCaching` missing: nothing is cached. On the main class: caching is mandatory in every test context | `caching.md` § Boundary | [1], [2] |
| 4 | Reading or warming a cache in `@PostConstruct`: the proxy is not ready | `caching.md` § Annotations and proxies | [1] |
| 5 | `sync = true` next to `unless`, a second cache name or another cache operation: refused at startup ("A sync=true operation does not support the unless attribute") | `CachedPortAdapter.java.example` | [1], [13] |

## B · Key

| # | Pitfall | Prevented by | Source |
|---|---|---|---|
| 6 | The default key is every parameter; an input read from the security context, a tenant holder or the locale is missing from it — one caller's result served to another | `caching.md` § Keys | [1], [10], [11] |
| 7 | A cache above the authorization check: a hit returns before the check runs | `caching.md` § Keys | [10], [11] |
| 8 | Two methods share a cache name and their keys collide: one reads the other's value, or a `ClassCastException` | `caching.md` § Keys — one name per kind of value | [1] |
| 9 | A key type without value equality, or mutable after insertion | `caching.md` § Keys | [1] |
| 10 | A token or personal data in a key: keys are listed by every administration tool of a distributed cache | `caching.md` § Keys · `personal-data.md` | [10] |

## C · Value

| # | Pitfall | Prevented by | Source |
|---|---|---|---|
| 11 | A persistence entity cached: lazy associations fail outside the session, the provider's proxy class enters serialization | `caching.md` § Values | [12] |
| 12 | A mutable value in a local cache: every caller gets the same instance, one caller's change is everyone's | `caching.md` § Values | [5] |
| 13 | Java serialization, Spring Data Redis's default value serializer: bytes bound to the class version, a reader that instantiates whatever the bytes name | `caching.md` § Values · `RedisCacheConfig.java.example` | [6] |
| 14 | The Jackson 3 serializer enables no default typing; enabling it needs a type validator. The Jackson 2 serializers are deprecated for removal | `RedisCacheConfig.java.example` — typed serializer per cache | [7] |
| 15 | A stored format without a version: during a rolling deploy each version reads the other's bytes | `caching.md` § Values — version in the key prefix | [9] |
| 16 | Nulls cached by default (Redis, and `spring.cache.redis.cache-null-values=true`); an empty `Optional` stores null | `caching.md` § Values | [1], [3], [6] |

## D · Expiry and size

| # | Pitfall | Prevented by | Source |
|---|---|---|---|
| 17 | No expiry: Redis stores without one by default; the in-memory map provider has neither expiry nor size ("not really recommended for production") | `caching.md` § Expiry and size | [2], [6] |
| 18 | Caffeine without `maximumSize`: grows until the heap does. A dynamic `CaffeineCacheManager` creates an unbounded cache for any unknown name | same · `CaffeineCacheConfig.java.example` (static mode) | [4], [13] |
| 19 | `spring.cache.caffeine.spec` / `spring.cache.redis.time-to-live`: one setting for every cache | `caching.md` § Expiry and size — per cache | [2] |
| 20 | Entries loaded together expire together | `caching.md` § Expiry and size — jitter (`TtlFunction`) | [6], [9] |
| 21 | Caffeine expires lazily; an eviction may be deferred — `evictIfPresent` removes at once | — (behaviour to know when testing) | [5] |

## E · Invalidation

| # | Pitfall | Prevented by | Source |
|---|---|---|---|
| 22 | A writer that does not evict: a job, a consumer, a bulk `UPDATE`, another service | `caching.md` § Invalidation · `22-cache.md` § 4 | [9] |
| 23 | Eviction before the commit: `@CacheEvict` runs after the method, inside the caller's transaction; the transaction-aware wrapping is off by default | `caching.md` § Invalidation | [1], [6], [8] |
| 24 | A read that started before the write stores the old value after the eviction (*stale set*) | `caching.md` § Invalidation — the expiry bounds it | [14] |
| 25 | `@CacheEvict` skipped when the method throws; `allEntries = true` ignores the key | `caching.md` § Invalidation | [1] |
| 26 | `allEntries = true` on Redis runs `KEYS` by default | `caching.md` § Invalidation · `BatchStrategies.scan` | [6] |
| 27 | A local cache on N instances: the eviction reaches one | `caching.md` § Invalidation · `provider-decision-matrix.md` § 2 | [9] |

## F · Concurrency and load

| # | Pitfall | Prevented by | Source |
|---|---|---|---|
| 28 | No lock by default: concurrent misses on one key all hit the source. `sync` is per instance; the default Redis writer takes no lock, and the locking one locks the whole cache | `caching.md` § Concurrency and load | [1], [6] |
| 29 | A deploy or a flush empties every local cache at once; load on the source grows with the instance count | `caching.md` § Concurrency and load | [9] |
| 30 | Refresh ahead is not reachable from `@Cacheable`; it needs a loader | `RefreshAheadCache.java.example` | [5] |

## G · Failure

| # | Pitfall | Prevented by | Source |
|---|---|---|---|
| 31 | A cache exception reaches the caller by default: Redis down fails a read the database could answer | `caching.md` § Failure · `LoggingCacheErrorHandler` | [1], [13] |
| 32 | A service that cannot run without its cache: the source collapses when the cache goes | `caching.md` § Concurrency and load | [9] |
| 33 | The Redis client's default command timeout, far above any read it saves | `caching.md` § Failure · `application-cache.yml.example` | [6] |

## H · Observability and tests

| # | Pitfall | Prevented by | Source |
|---|---|---|---|
| 34 | Caches created after startup are not bound to the metrics registry; Redis statistics are off by default; Caffeine reports zeros without `recordStats()` | `caching.md` § Observability | [3], [6] |
| 35 | Tests with caching disabled hide pitfalls 1, 2, 6 and 22; tests on the in-memory map provider hide 11, 13 and 15 | `caching.md` § Tests | [2] |
| 36 | One test's entry is another test's answer when the context is shared | `caching.md` § Tests | — |

## Sources

1. Spring Framework 7.0.9, *Declarative Annotation-based Caching* —
   <https://docs.spring.io/spring-framework/reference/integration/cache/annotations.html>
2. Boot 4.1.1, *Caching* — <https://docs.spring.io/spring-boot/reference/io/caching.html>
3. Boot 4.1.1, *Metrics* § Cache Metrics —
   <https://docs.spring.io/spring-boot/reference/actuator/metrics.html>
4. Spring Framework javadoc, `CaffeineCacheManager` —
   <https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/cache/caffeine/CaffeineCacheManager.html>
5. Caffeine wiki, *Refresh* — <https://github.com/ben-manes/caffeine/wiki/Refresh>; Spring
   `CaffeineCache` javadoc on deferred eviction
6. Spring Data Redis 4.1.1, *Redis Cache* —
   <https://docs.spring.io/spring-data/redis/reference/redis/redis-cache.html>
7. Spring Data Redis 4.0, *Upgrading* —
   <https://docs.spring.io/spring-data/redis/reference/4.0/upgrading.html>
8. Spring Framework javadoc, `org.springframework.cache.transaction` —
   <https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/cache/transaction/package-summary.html>
9. Amazon Builders' Library, *Caching challenges and strategies* —
   <https://builder.aws.com/content/3K9TeXp6strMiJ4vhakpRD7BQWO/caching-challenges-and-strategies>
10. OWASP, *Web Cache Security Cheat Sheet* —
    <https://cheatsheetseries.owasp.org/cheatsheets/Web_Cache_Security_Cheat_Sheet.html>
11. Sourcery, *Tenant-Aware Caching Bugs* —
    <https://sourcery.ai/security/categories/tenant_aware_caching_bugs>
12. Hibernate ORM User Guide (fetching, proxies) —
    <https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html>
13. Spring Framework source, `CacheAspectSupport` (sync validation) and
    `LoggingCacheErrorHandler` (since 5.3.16) — <https://github.com/spring-projects/spring-framework>
14. Nishtala et al., *Scaling Memcache at Facebook*, NSDI 2013 — leases against stale sets and
    thundering herds — <https://www.usenix.org/conference/nsdi13/technical-sessions/presentation/nishtala>
