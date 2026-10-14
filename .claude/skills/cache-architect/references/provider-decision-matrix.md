# Cache provider decision matrix

Read by `SKILL.md` step 4. Researched on 2026-10-12 against Spring Framework 7.0.9, Boot
4.1.1 and Spring Data Redis 4.1.1 — facts as read that day, sources in `pitfalls.md`. No version
here is a version to write into a project: the Boot parent manages Caffeine and Spring Data
Redis, and an image tag is verified in the registry (`@CLAUDE.md` invariant 8).

## 1 · What each option is

| Option | Where the copy lives | Shared across instances | Survives a restart | Template |
|---|---|---|---|---|
| Caffeine (`CaffeineCacheManager`) | the instance's heap | no | no | `CaffeineCacheConfig.java.example` |
| Redis (`RedisCacheManager`, Spring Data Redis) | a Redis server | yes | yes, while the server runs | `RedisCacheConfig.java.example` |
| Caffeine programmatic (`LoadingCache` with refresh) | the instance's heap | no | no | `RefreshAheadCache.java.example` |
| Two-level (Caffeine over Redis, invalidation broadcast) | both | the Redis half | the Redis half | none — reference only |
| Hazelcast, Infinispan, any JCache provider | an embedded or remote grid | yes | depends | none — reference only |
| Hibernate second-level and query cache | the persistence provider | depends on its provider | depends | none — out of scope |
| In-memory map (`ConcurrentMapCacheManager`, the Simple provider) | the heap | no | no | none — no expiry, no size; never in production |

## 2 · Which one

Apply the rows in order; the first that matches decides.

| # | When | Choose | Why |
|---|---|---|---|
| 1 | No freshness tolerance stated in `00-use-case.md` | nothing — stop | `@.claude/rules/caching.md` § Boundary: no tolerance, no cache |
| 2 | More than one instance, and a write must reach every instance before the tolerance runs out | Redis | A local cache evicts on the writing instance only |
| 3 | More than one instance, and the tolerance covers a whole expiry of drift per instance — or nothing in this service writes the data (an external rate, a reference table loaded once) | Caffeine | No network hop, no server to run; each instance converges within the expiry |
| 4 | One instance, by written deployment constraint | Caffeine | Same, with no drift at all |
| 5 | Any row above chose Caffeine, and one read is hot enough that a miss on it is felt, and the tolerance covers serving the old value for one refresh | Caffeine programmatic, for that read only | `@Cacheable` cannot refresh ahead; the rest stay annotated |
| 6 | An earlier `22-cache.md` or `pom.xml` already chose a provider, and this case's rows do not contradict it | that one | One provider per project (`caching.md` § Boundary) |

A tie goes to the provider already in `pom.xml`, then to Caffeine — no infrastructure.

**Replicas** come from the same places `jobs-architect` reads them: an earlier `22-cache.md`
§ 2, `35-jobs.md` § 3, `20-persistence.md` § 1 `Claim strategy`. Asked only when none exists.

## 3 · What each choice costs

| Choice | Adds to the project | Operational cost | Failure mode to design for |
|---|---|---|---|
| Caffeine | `spring-boot-starter-cache` + `com.github.ben-manes.caffeine:caffeine` (managed) | heap per instance — `maximumSize` × entry size | per-instance drift; a deploy empties every instance at once |
| Redis | `spring-boot-starter-cache` + `spring-boot-starter-data-redis` (managed); a `redis` compose service; a password | a server to run, monitor and secure; a network hop per read | server down — the error handler sends reads to the source; serialization across deploys |
| Caffeine programmatic | as Caffeine | as Caffeine, plus a background reload per refresh | a refresh failing forever keeps serving the old value until expiry |

## 4 · Why not the others

- **Two-level.** Two copies, two expiries and a broadcast channel to keep the local half
  honest. Worth it only for a read so hot that the Redis hop itself is the bottleneck — no case
  has shown one. Designed by hand, recorded in `## Deferred` with its trigger, when one does.
- **Hazelcast, Infinispan, JCache.** A data grid where a cache is needed. A project that
  already runs one may declare it as its provider in an earlier `22-cache.md`; the rule still
  applies.
- **Hibernate second-level cache.** It caches entities by id and invalidates for writes made
  through the same session factory, with soft locks around the commit. It never sees another
  service writing the database, a bulk statement invalidates a whole region, and the query
  cache stores ids — the N+1 its documentation warns about — invalidated by any write to its
  tables. The reads this layer exists for are query-shaped or come from another system, where
  it does not help. It would win only for read-mostly reference data loaded by id, with one
  writer and every write through the persistence provider — not this skill's case, and kept off
  by `caching.md` § Boundary.
- **HTTP caching** (`Cache-Control`, `ETag`). A transport concern, not a copy inside the
  service.
