---
paths:
  - "**/adapter/out/search/**"
  - "**/infrastructure/search/**"
  - "**/src/main/resources/search/**"
status: active
---

# Search — a search engine index fed from a source of truth

Code that keeps a copy of the project's records in a search engine — Elasticsearch or
OpenSearch — and queries it by words, filters and facets. The engine is fast at exactly what a
relational or document store is slow at, and it pays for that with every guarantee the code
usually leans on: no transaction, a write visible about a second later, a field type that can
never change in place, a backup that is only a snapshot. This norm keeps the index a copy and
makes each of those costs explicit.

Scope: full-text and faceted search over a projection of aggregates. A search by meaning — a
`dense_vector` or `knn_vector`, nearest neighbours — is a vector store,
`@.claude/rules/ai.md` § Semantic search, even on the same engine. Full-text inside the
aggregate's own database (`tsvector`, a MongoDB text index) is that database's index,
`@.claude/rules/persistence.md`. An engine used as the store of log or metric data streams is
outside this norm.

## Why this is a rule and not a `CLAUDE.md` section

A declarative fact with a territory — the search package and its mapping files — loaded only
when one of them is touched. Decision recorded in
`@.claude/decisions/0147-document-storage-and-search-architect.md`.

## Boundary

- **The index is a projection, never the source of truth.** Every document in it is rebuilt
  from a source the project owns — a relational table or a document collection — and an
  aggregate is never written to the index first, or only. The engine has no transaction, a
  field type changes only by rebuilding the index from somewhere, and its backup is a snapshot:
  data that lives only there is data that a mapping change or a lost snapshot destroys
- The search client is an adapter detail. `org.springframework.data.elasticsearch`,
  `co.elastic` and `org.opensearch` appear only in the architecture's search package; the domain
  and the application see an outbound port in business words (`OrderSearch`) that takes a query
  in domain terms and returns identifiers or a read projection — never an aggregate, which is
  read from its own repository when the caller acts on it. A blueprint forbids the imports in
  every layer above the adapters
- **The index is written by an indexer, never by the request that changes the source.** The
  source commits its change and publishes an event through the outbox
  (`@.claude/rules/messaging.md` § Publication timing); a consumer of that event writes the
  index. A write to the source and to the index in the same request is two writes with no
  transaction between them, and the first failure between the two leaves them apart for good
- **A rebuild from the source exists from the first index.** A pass that reads the whole source
  and writes a fresh index is how a mapping changes, how a lost event is recovered and how a
  restored snapshot catches up; an index with no rebuild is an index nobody can change
- The search document has no business logic: fields, annotations, and access only. It lives in
  its own subpackage per projected aggregate, package-private by default, as
  `@.claude/rules/persistence.md` § Boundary sets for the persistence adapter

## Index lifecycle

- **The mapping and the settings are versioned files**, under `src/main/resources/search/`, one
  per index version — never inferred. `createIndex = false` on every `@Document`: the default
  `true` makes the repository create the index and push a mapping derived from the class at
  startup, and that index then occupies the name the alias needs
- `dynamic: strict` in every mapping. A field the mapping does not name is refused at write
  time, not guessed: dynamic mapping infers types from the first document that carries a value,
  turns a date-shaped string into a date, and grows the field count to the engine's limit
- **Every index is versioned behind an alias.** The code reads and writes through the alias
  (`orders`); the index behind it carries the version (`orders-v1`). A mapping change is a new
  index (`orders-v2`), filled by the rebuild from the source, then the alias swapped to it in one
  atomic action, then the old index deleted — a field type never changes in place, and an
  application that writes to the index name directly cannot be migrated without downtime
- The startup creates the current index version and its alias only when the alias is absent;
  it never deletes, recreates or remaps an index that exists
- One primary shard per index until a measured size says otherwise, and the replica count
  stated per environment: a single-node cluster with one replica never turns green

## Mapping

- Every field declares its type. `FieldType.Auto` — the default of `@Field` — writes no mapping
  and hands the type to dynamic inference, which `dynamic: strict` then refuses
- A field searched by words is `text`; a field filtered, sorted or aggregated is `keyword`
  (or a number or date). A field that needs both is a `text` field with a `keyword` sub-field.
  Sorting or aggregating on `text` fails with "fielddata is disabled"
- An array of objects whose fields are queried together is `nested`. A plain `object` array
  flattens every item into one bag of values, and a query for "colour red and size L" matches a
  document holding a red M and a blue L
- A custom date pattern uses `uuuu`, never `yyyy`: `yyyy` is year-of-era in the Java
  formatter Spring Data converts with, and a pattern that disagrees with the engine's reading
  of it fails on dates the engine accepted
- The document carries only what a search shows, filters or sorts by — never the aggregate
  whole. A field nobody queries or displays is a field to reindex on every change

## Writes

- **Every write carries the source's version** as an external version
  (`versionType = EXTERNAL`, the version of the source row or document that produced it). The
  engine keeps the higher one, so an event that arrives late or twice cannot overwrite a newer
  copy
- A version conflict on an external-version write means "the index already holds a newer copy":
  the indexer acknowledges it and moves on. It is never retried, and never reported to anybody
  as a failure
- **A delete is a versioned write, not a delete.** The indexer writes a tombstone — the document
  marked deleted, with the source's version — that every query excludes and the next rebuild
  leaves out. An external-version delete keeps its own tombstone only for a bounded time, and a
  write that arrives after it, older or not, brings the document back
- **No refresh on a write.** The repository refreshes after every `save` unless a refresh
  policy is set, and throughput collapses under load: the policy is `NONE`. `WAIT_UNTIL` only
  where a read-your-own-write is a stated requirement of the use case, and `IMMEDIATE` only in a
  test fixture
- A batch of writes is one bulk request with a bounded size, and each item's result is read:
  a bulk request succeeds as a whole while items inside it fail

## Queries

- **Pagination is `search_after` over a point in time**, with a sort that ends in a unique
  field. `from` + `size` stops at 10 000 hits and re-reads every earlier page; it is allowed only
  with a stated page ceiling far below that. A scroll is not pagination
- A user's text reaches the engine as the value of a `match`-family query, never interpolated
  into `query_string` syntax, and never with a leading wildcard — a leading `*` reads every term
  of the field. A derived repository method `Containing` produces exactly that
- The result is read through `_source` filtering or stored fields: only what the port returns
  leaves the engine
- The freshness a reader may observe is the time an event takes to reach the index plus the
  index's refresh interval; the read side states it, and nothing on the read path pretends to
  read its own write

## Failure

- A search engine that cannot be reached, times out or answers a server error is
  `DependencyUnavailableException` (`@.claude/rules/error-handling.md` § Integration families):
  the read fails or degrades as the use case states, and the source is never queried as a
  hidden fallback
- A missing index or alias at query time is a deployment defect, reported as unavailable, never
  answered as an empty result
- The indexer retries a transient failure with a bound and then sends the event to its dead
  letter; the rebuild is what reconciles what was dead-lettered

## Configuration

- Connection, credentials and TLS through the engine's own properties —
  `spring.elasticsearch.*` for Elasticsearch, `opensearch.*` for OpenSearch — with the
  credentials from the environment (`@.claude/rules/secrets.md`) and a TLS bundle wherever the
  engine serves TLS. The Elasticsearch client speaks to Elasticsearch only; an OpenSearch server
  needs the OpenSearch client
- Connect and socket timeouts set explicitly, and a client adjusted through its builder
  customizer, never a client bean of the project's own that replaces the auto-configured one
- The client's major version matches the server's: a client one major ahead is refused by the
  server

## Tests

- An integration test runs against the real engine in a container, the same image and tag the
  local environment runs, over TLS where the image enables it
- A test that writes and then reads refreshes in its fixture, never in production code
- The indexer's tests include an event that arrives out of order and is acknowledged, and a
  delete followed by a late write
- The rebuild's test fills a new index from the source and swaps the alias with readers active

## How to verify

```bash
# No search client outside the search adapter. Zero lines is the expected result.
grep -rnE "org.springframework.data.elasticsearch|co.elastic|org.opensearch" --include='*.java' src/main/java | grep -v "/search/"

# Every search document turns index creation off. Each file listed lacks it.
grep -rL "createIndex = false" $(grep -rl "data.elasticsearch.annotations.Document" --include='*.java' src/main/java)

# No field left to dynamic inference, and no refresh forced on a write path.
grep -rn "FieldType.Auto\|RefreshPolicy.IMMEDIATE" --include='*.java' src/main/java

# Every mapping file is strict.
grep -rL '"dynamic": *"strict"' src/main/resources/search/

# The rest — the alias, the external version, out-of-order events — is the integration tests.
./mvnw -q verify
```
