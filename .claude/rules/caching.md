---
paths:
  - "**/adapter/out/cache/**"
  - "**/infrastructure/cache/**"
status: active
---

# Caching — reads served from a copy, and how the copy stays honest

Code that answers a read from a stored copy instead of from the source: a query result, an
aggregation, the answer of another system. A cache trades freshness for latency, and every
default of the cache library makes that trade silently — no expiry, no size bound, a serializer
nobody chose, an exception that turns a slow read into a failed one. A copy that is wrong raises
no error; it is served. This norm makes each of those choices explicit.

## Boundary

- **A cache is a decorator of an outbound port**, in the architecture's cache package: it
  implements the same port, answers from the cache, and delegates to the real implementation on a
  miss. The domain and the application layer never see it — no cache annotation, no cache type,
  no `CacheManager` in a use case, an aggregate or a port — `@.claude/rules/architecture-ddd.md`
  § Adapters
- **The decorator depends on the port, never on the class it wraps.** Which implementation it
  delegates to is wiring, decided by bean name in the cache package's configuration; an import of
  another adapter's class is an adapter talking to an adapter
- **Cache wiring lives with its decorators**: `@EnableCaching`, the cache manager, its per-cache
  settings and the error handler sit in the cache package, never on the application's main
  class. On the main class, caching becomes mandatory in every test context, including the ones
  that assert something else
- **One cache provider per project**, plus at most a second one recorded as a decision with its
  reason. Two providers mean two places a stale value can come from
- Application caching is this layer's. The persistence provider's second-level and query caches
  stay off, and HTTP response caching is the transport's own concern — three caches over one
  read are three invalidation paths nobody keeps in step
- **A read is cached only with a freshness tolerance stated by the business**: how old an answer
  may be when served. No tolerance stated, no cache — "the query looks slow" is a reason to
  measure, not a tolerance

## Annotations and proxies

- **Cache annotations go on public methods of the decorator only.** A non-public method, or a
  call from another method of the same class, never goes through the proxy: no error is raised,
  and nothing is cached or evicted
- **`@Cacheable` and `@CachePut` never on the same method**, and `@CachePut` only where the
  method's return value is exactly what a later read must see
- A cache is never read or written in `@PostConstruct` or a constructor — the proxy is not ready
  there. Warming runs after the application is ready, or from a scheduled job

## Keys

- **The key names every input that changes the result**, written explicitly in the annotation's
  `key` expression — never the default key over all parameters. That includes what the method
  reads from somewhere other than its parameters: the authenticated principal, the tenant, the
  locale, a role. A result that depends on who asks and a key that does not say who asked serves
  one caller's data to another, and the hit skips whatever check sat below the cache
- **A cache sits below the authorization check, never above it.** A hit returns before any code
  under it runs
- **One cache name per kind of value.** Two methods that share a cache name share a key space;
  the second reads the first's value
- A key holds identifiers only: never a token, a secret or a personal-data value in clear. Keys
  are listed by every administration tool of a distributed cache —
  `@.claude/rules/personal-data.md` § At rest
- A key type is immutable and has value equality — a record, a string, a number. A mutable key
  changes after insertion and its entry is never found again

## Values

- **A cached value is an immutable record**, built inside the transaction that read it. Never a
  persistence entity: a lazy association becomes an exception on a later read, and the
  provider's proxy class enters the serialized form. Never a mutable object: a local cache hands
  the same instance to every caller, and one caller's change is everyone's
- **The serializer of a distributed cache is chosen, never the default.** Java serialization
  couples the stored bytes to the class version and opens the deserializer to whatever type the
  bytes name. JSON with the value type fixed per cache, or type information restricted to the
  application's own packages
- **A stored format carries a version** — in the cache key prefix — and a change to a cached
  record's shape changes it. During a rolling deploy both versions read the same store
- **Whether an absent result is cached is a decision**, with its own expiry when it is. A cached
  absence shields the source from repeated misses; a forgotten one hides a record created a
  second later

## Expiry and size

- **Every cache has an expiry, and it is never longer than the freshness tolerance it serves.**
  No expiry is a copy that never refreshes; a distributed cache stores without one unless told
- **Every local cache has a maximum size.** An unbounded in-memory cache grows until the heap
  does
- **Expiry and size are set per cache**, not once for all: one global specification gives a
  reference table and a balance the same freshness
- The in-memory map provider without expiry or size is not used outside a prototype
- Entries loaded together expire together: when many are loaded at once — a warm-up, a deploy —
  the expiry carries jitter, so the source is not asked for all of them in the same second

## Invalidation

- **Every write to cached data evicts or refreshes it — every writer, not only the main one.**
  A job, a message consumer, a bulk statement, another use case writing the same rows: each is
  listed, and each goes through the decorated port or evicts explicitly. A writer that bypasses
  the cache leaves the expiry as the only bound on staleness, and that is accepted in writing or
  fixed
- **Eviction takes effect after the commit**, never before: a cache manager aware of the
  transaction, or an eviction on the after-commit phase. Evicted before the commit, a concurrent
  read finds the old row and stores it again
- Even after the commit, a read that started before the write can store the old value after the
  eviction. The expiry bounds that window, which is why it never exceeds the tolerance
- **A local cache on more than one instance evicts on one instance only.** It is a legitimate
  choice only when the tolerance covers the expiry; otherwise the cache is distributed, or the
  eviction is broadcast to every instance
- Clearing a whole distributed cache never scans the keyspace with a blocking command; the
  provider's incremental scan is configured

## Concurrency and load

- **A miss on a hot key is computed once.** Concurrent misses on one key wait for one
  computation in the instance (`sync`), and a distributed cache shared by many instances accepts
  that each instance may still compute it once
- **The source survives an empty cache.** A deploy, a flush or a cache outage sends every read to
  the source at once; either the source holds that load, or the case records how load is shed
- Refreshing ahead of expiry, serving the old value while a new one loads, is a decision with
  its tolerance, never a default

## Failure

- **A cache failure never fails a read the source can answer.** The cache error handler logs the
  failure and the read goes to the source; a cache error reaching the caller turns a degraded
  cache into an outage
- **The cache client times out faster than the source answers.** A cache that waits longer than
  the read it saves is slower than no cache
- A distributed cache's connection carries a password from the environment and encryption in
  transit outside a local environment — `@.claude/rules/secrets.md`

## Observability

- **Every cache reports hits, misses, puts and evictions**, tagged by cache name. A cache
  created after startup is registered with the metrics registry explicitly; a distributed
  cache's statistics are enabled. Cardinality follows `@.claude/rules/observability.md`
  § Metrics — the cache name is a tag, a key never is
- A cache failure the error handler swallowed is counted, not only logged: degraded caching is
  only visible as a rate

## Tests

- **A test proves the cache, not only the result**: a second read does not reach the source, a
  write through every listed writer evicts, and two callers with different identities do not
  share an entry. A test that disables caching proves none of the three
- **A distributed cache is tested against the real server**, in a container — never against the
  in-memory map provider, which hides every serialization defect —
  `@.claude/rules/testing.md` § Slices and context
- Caches are cleared between tests that share an application context; one test's entry is
  another test's wrong answer

## How to verify

```bash
# Cache annotations live in the cache package only. Zero lines is the expected result.
grep -rnE "@(Cacheable|CachePut|CacheEvict|Caching)\b" --include=*.java src/main | grep -v "/cache/"

# Exactly one @EnableCaching, and never next to @SpringBootApplication.
grep -rln "@EnableCaching" --include=*.java src/main | wc -l
grep -rl "@SpringBootApplication" --include=*.java src/main | xargs grep -l "@EnableCaching"

# Every @Cacheable names its key. Zero lines is the expected result — review multi-line ones.
grep -rn "@Cacheable" --include=*.java src/main | grep -v "key *="

# No Java serialization of cached values. Zero lines is the expected result.
grep -rn "JdkSerializationRedisSerializer\|implements Serializable" --include=*.java src/main | grep "/cache/"

# The in-memory map provider is not the production cache. Zero lines is the expected result.
grep -rnE "ConcurrentMapCacheManager|cache\.type: *simple" --include=*.java --include=*.yml src/main
```
