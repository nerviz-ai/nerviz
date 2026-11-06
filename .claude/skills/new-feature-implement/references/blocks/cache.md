# Block C: Cache (steps C1-C3, conditional)

**Runs only when the spec carries the cache block** — `grep -m1 -E '^## [0-9.]+ Cache' <spec>`,
and the block is not `none`. No match → skip to Block 4. Read the cache block of spec.md, then
`22-cache.md`. The provider in its § 2 decides which exemplar is the shape — never a second
provider because one read looked easier with it:

| `22-cache.md` § 2 | Exemplar in `.claude/skills/cache-architect/templates/` |
|---|---|
| Caffeine | `CaffeineCacheConfig.java.example` — `CacheSettings`, `CacheNames`, `CountingCacheErrorHandler`, the manager |
| Redis | `RedisCacheConfig.java.example` + the three shared files of the row above |
| Every cached read | `CachedPortAdapter.java.example` — the decorator and its `@Primary` bean configuration |
| A read § 1 marks refresh-ahead | `RefreshAheadCache.java.example` |

- **C1 · Wiring** — in the `[cache]` package: the project's single `@EnableCaching`, the
  manager with one entry per cache of § 3, the error handler, `CacheSettings` and `CacheNames`
  when the project has none yet. The dependencies of § 8 in `pom.xml` — exactly those rows
- **C2 · Decorators** — one per port § 1 names: implements the port, depends on the port only,
  keys exactly as § 1 writes them, `unless` and `sync` as § 1 says. Every write method of that
  port that § 4 lists evicts under the read's key expression. Its configuration registers it as
  the port's `@Primary` bean, delegating to the adapter **by bean name** — never an import of the
  persistence or HTTP adapter class
- **C3 · Configuration** — the properties of § 9 in `application.yml`: one
  `app.cache.caches.<name>` entry per cache, the format version, and for Redis the connection
  with its short timeouts and the password placeholder with no default

A `@Cacheable` already outside the cache package is never left next to a decorator over the same
read. The spec's impact section says whether this run moves it behind the decorator or leaves it
for a backlog row; with neither, it is a spec defect: stop and report.

Rules: `.claude/rules/caching.md`, `.claude/rules/architecture-ddd.md` § Adapters,
`.claude/rules/secrets.md`, `.claude/rules/observability.md` § Metrics.

Validation, each item checkable:

- the cache-annotation grep of `.claude/rules/caching.md` § How to verify returns nothing outside
  the cache package, and there is exactly one `@EnableCaching`, not on the main class;
- every `@Cacheable` names its `key`, and no decorator imports a class of another adapter;
- every cache name in `CacheNames` has its `app.cache.caches` entry, and every expiry there is at
  most the tolerance § 3 records next to it;
- for Redis: one typed serializer per cache, `disableCachingNullValues`, `transactionAware`, a
  `SCAN` batch strategy, and the password placeholder with no default.

Compilation: `./mvnw -q test-compile` (or `-pl <module that holds the cache package>` in
multi-module) ✅

**Line in the final report:**
```
✅ Block C: Cache complete (steps C1-C3)
- Provider: Redis (transaction-aware, format v1)
- Caches: account-balance (PT30S, key ownerId:accountId) — decorator over AccountRepositoryPort
- Evicting writers through the port: save · bypassing, expiry-bounded: InterestAccrualJob
- Files: 6 classes, 0 errors
```
