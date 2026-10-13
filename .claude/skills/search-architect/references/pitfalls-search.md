# Pitfalls — a search engine index with Spring Data Elasticsearch / OpenSearch

Reference for the `search-architect` skill. What a model writes wrong without the norm, each with
the section of `@.claude/rules/search.md` that prevents it and one source. **Observed** marks the
ones reproduced while the exemplars were written and run against real Elasticsearch and OpenSearch
containers (decision `0147`, 2026-10-13); the rest come from the official documentation and the
libraries' source, re-read on that date.

Versions are never written here: resolve the project's own before applying a line that depends on
one (`@CLAUDE.md` invariant 8).

## The projection

| # | Pitfall | Norm | Source |
|---|---|---|---|
| 1 | The index used as the aggregate's source of truth: no transaction, a type change needs a rebuild *from* somewhere, the backup is a snapshot | § Boundary | Elastic, *Update mappings*; *Snapshots* |
| 2 | A dual write in the request — database and index — out of step on the first partial failure | § Boundary | Debezium, *Outbox Event Router* |
| 3 | No rebuild from the source: a mapping can never change, a lost event is never recovered | § Boundary | Elastic, *Reindex* |
| 4 | A search by meaning (`dense_vector`, `knn_vector`) designed here instead of as a vector store | Scope | `@.claude/rules/ai.md` § Semantic search |

## Index lifecycle and mapping

| # | Pitfall | Norm | Source |
|---|---|---|---|
| 5 | `createIndex = true` (the default): the repository creates the index with a mapping derived from the class at startup, and occupies the alias's name | § Index lifecycle | Spring Data Elasticsearch, *Object Mapping*; `SimpleElasticsearchRepository` |
| 6 | `FieldType.Auto` (the default of `@Field`) plus dynamic mapping: types inferred from the first document, date detection, the 1 000-field limit | § Index lifecycle; § Mapping | Elastic, *dynamic*; *Mapping limits* |
| 7 | **Observed.** A strict mapping refusing Spring Data's own fields: `_class` (type hint), the id and the version written into `_source` by default → `strict_dynamic_mapping_exception`. `writeTypeHint = FALSE`, `storeIdInSource = false`, `storeVersionInSource = false` | § Index lifecycle | run of `SearchIndexIT`, 2026-10-13 |
| 8 | A field type changed in place instead of a new index version, a rebuild and an atomic alias swap | § Index lifecycle | Elastic, *Update mappings*; *Aliases* |
| 9 | `text` used to sort or aggregate — "fielddata is disabled" — or `keyword` used for words | § Mapping | Elastic, *text* |
| 10 | An array of objects without `nested`: a query matches across two different items | § Mapping | Elastic, *nested* |
| 11 | A custom date pattern with `yyyy` instead of `uuuu` | § Mapping | Spring Data Elasticsearch, *Object Mapping* — date formats |
| 12 | `_source` holding the whole aggregate instead of what a search shows, filters or sorts by | § Mapping | Elastic, *_source* |
| 13 | Many small indexes (per tenant, per day) — oversharding | § Index lifecycle | Elastic, *Size your shards* |
| 14 | One replica on a single-node cluster: never green | § Index lifecycle | Elastic, *Docker* — single node |

## Writes

| # | Pitfall | Norm | Source |
|---|---|---|---|
| 15 | Writes without the source's version: a late or repeated event overwrites a newer copy | § Writes | Elastic, *Index API* — `version_type=external` |
| 16 | **Observed.** A bulk built with `IndexQueryBuilder.withObject(document)` and no `withVersion(…)`: the builder does not take the version from the document, the bulk overwrites what the indexer wrote meanwhile, and the newer event is lost | § Writes | run of `SearchIndexIT`, 2026-10-13 |
| 17 | `VersionConflictException` (an external-version 409) treated as an optimistic-lock failure and answered as a 409 to a client, when in the indexer it means "already newer" | § Writes | `ElasticsearchExceptionTranslator` |
| 18 | A bulk read as a whole: it succeeds while items inside it fail (`BulkFailureException.getFailedDocuments()`) | § Writes | Elastic, *Bulk*; Spring Data `BulkFailureException` |
| 19 | A delete with an external version, then a late write: the tombstone expires and the document comes back | § Writes | Elastic, *Index API* — `index.gc_deletes` |
| 20 | `ElasticsearchRepository.save` refreshes after every call when no refresh policy is set — indexing throughput collapses | § Writes | `SimpleElasticsearchRepository`, `ElasticsearchConfigurationSupport` |
| 21 | The opposite: a read-your-own-write expected from `ElasticsearchOperations`, which does not refresh; an idle shard only refreshes on the next search | § Queries; § Writes | Elastic, *refresh parameter*; *Index modules* — `search.idle.after` |

## Queries

| # | Pitfall | Norm | Source |
|---|---|---|---|
| 22 | `from` + `size` pagination past 10 000 hits, or `max_result_window` raised | § Queries | Elastic, *Paginate search results* |
| 23 | A derived `Containing` method: a leading-wildcard `query_string` | § Queries | Spring Data Elasticsearch, *Repository queries* |
| 24 | A user's text concatenated into `query_string` or a `@Query` | § Queries | Elastic, *query_string* — reserved characters |
| 25 | A `NativeQuery` in an adapter meant for both engines — it belongs to the Elasticsearch client package; `CriteriaQuery` runs on both | § Configuration | Spring Data Elasticsearch, *Template*; spring-data-opensearch README |
| 26 | A sort on `_id`, refused by default; the tiebreaker is a `keyword` copy of the id | § Queries | Elastic, *_id field* |

## Connection, engines and tests

| # | Pitfall | Norm | Source |
|---|---|---|---|
| 27 | A client one major ahead of the server: every call answered 400 | § Configuration | Elastic, *REST API compatibility* |
| 28 | The Elasticsearch client against OpenSearch, or the reverse; OpenSearch versions left unpinned since Spring Boot manages none; `spring.elasticsearch.*` used instead of `opensearch.*` | § Configuration | OpenSearch, *Clients*; spring-data-opensearch README |
| 29 | Elasticsearch's container without `@Ssl` on the service connection: it serves HTTPS, the client speaks HTTP | § Tests | Spring Boot, *Testcontainers* — service connections |
| 30 | **Observed.** A startup component that calls the engine during context refresh, against a container just reported ready: Elasticsearch's security answers 401 until the `elastic` password is set up. The test container waits for an authenticated `_cluster/health` on port 9200 (the default wait strategy may probe 9300) | § Tests | run of `SearchIndexIT`, 2026-10-13 |
| 31 | `ELASTIC_PASSWORD_FILE` in the compose file: Spring Boot's compose support refuses it | — (`docker-architect`'s `elasticsearch-service.yml.example`) | Spring Boot, *Dev services* — Elasticsearch |
| 32 | `OPENSEARCH_INITIAL_ADMIN_PASSWORD` missing or weak: the container exits on start | — (`opensearch-service.yml.example`) | OpenSearch, *Docker* |
| 33 | `@DataOpenSearchTest` taking `properties` like Spring Boot's slices — it has no such attribute; the property goes through `@TestPropertySource` | § Tests | spring-data-opensearch-test-autoconfigure |
| 34 | The Elasticsearch client's OpenTelemetry instrumentation is on but uses the global SDK; slow log off by default | § Configuration | Elastic, *Java client — OpenTelemetry*; *Slow log* |
| 35 | License: Elasticsearch's default distribution is under ELv2/SSPL/AGPLv3, its clients Apache 2.0; OpenSearch is Apache 2.0. A fact for the person choosing, never a criterion this skill applies | Scope | elastic.co licensing FAQ; opensearch.org FAQ |
| 36 | **Observed.** The cursor read from `SearchHit.getSortValues()` with a cast: Elasticsearch's client returns numbers and strings, OpenSearch's its own `FieldValue` — a `ClassCastException` on one engine only. The cursor is built from the last item's own fields | § Queries | runs of `SearchIndexIT` on both engines, 2026-10-13 |
