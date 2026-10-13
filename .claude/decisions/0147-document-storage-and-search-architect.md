# 0147 · MongoDB enters `persistence-architect` as a storage variant, a search engine index gets its own design skill, and the storage family is a datum of the use case that no pipeline step weighs

- **Date:** 2026-10-13
- **Scenario:** "Estender o persistence-architect para MongoDB e para índice de busca
  (Elasticsearch e OpenSearch), com Spring Data no adapter de saída." The request fixes, and the
  interview does not reopen, who decides between SQL and NoSQL: neither `/new-feature` nor
  `persistence-architect` weighs it; the request states it, explicitly or by unambiguous
  inference, and `use-case-design` records it; only when it is unclear does `use-case-design` ask
  a bare factual question, with no criteria and no recommendation. The decision interview
  (scenario, trade-offs, recommendation) belongs to a planned solution-design skill, not designed
  here.
- **Decision:** Option 1 — MongoDB as the document family of `persistence-architect` (Form 1, the
  existing skill) + new `.claude/skills/search-architect/SKILL.md` (Form 1, class `design`, partial
  `23-busca.md`) + `.claude/rules/persistence.md` restructured into a common boundary, `Relational
  (JPA)` and `Document (MongoDB)` (Form 4) + new `.claude/rules/search.md` (Form 4, territory
  `.search`) + the `Storage` and `Search index` rows of `00-caso-de-uso.md` + the `search index` port
  kind + a `.search` key and the new forbidden prefixes in every blueprint + `compose.datasource_pairs`
  reshaped to a list with a MongoDB pair.
- **State:** approved by Lucas Fernandes, on 2026-10-13 — option 1 with every planned decision of its
  table; implemented on the branch `feat/persistence-nosql`, release bump `minor`

## What the context showed

| Concern | Owner today |
|---|---|
| Whether an aggregate lives in SQL or in a document store | nobody — `use-case-design/SKILL.md:70` says "SQL or NoSQL" is "fixed earlier: the blueprint's `packages.map` and `features`", and no blueprint has a NoSQL feature; `:31` says it "Chooses no technology" |
| Persistence design | `persistence-architect`, SQL only — engine asked among Postgres, MySQL, Oracle, H2; Flyway migration inside the partial; `idempotency_keys` and `outbox_events` as tables |
| The persistence norm | `rules/persistence.md`, JPA from top to bottom (`ddl-auto`, `open-in-view`, `@Lob`, `saveAndFlush`, `CREATE INDEX CONCURRENTLY`) |
| A search engine index (Elasticsearch/OpenSearch) | nobody. kNN over a vector belongs to `ai-architect` as a `vector store` port (`rules/ai.md`) |
| Port kinds in `10-dominio.md` | `persistence` · `messaging` · `external HTTP` · `LLM` · `vector store` (`domain-modeling/SKILL.md:271`) |
| `org.springframework.data.mongodb..`, `com.mongodb..`, `org.bson..`, `co.elastic..`, `org.opensearch..` reaching `application` | nothing forbids them — `application.forbidden_imports` lists `jakarta.persistence..`, `org.springframework.web..`, `org.springframework.ai..`, `org.springframework.graphql..`, `graphql..` |
| Compose login check | `compose.datasource_pairs`: one top-level `url_property` (`spring.datasource.url`), engines postgres and mysql; read by `ArchHook.java:1762` |
| Compose and Testcontainers templates | Postgres, MySQL, Redis, Kafka; no Mongo, Elasticsearch or OpenSearch. `testImagePins()` matches only `DockerImageName.parse("…")`, never a `new …Container("…")` literal |
| Dependencies | `dependency-catalog.md` has `data-jpa` and `flyway`. Spring Data OpenSearch is in neither the Boot BOM nor the Initializr |

No `/new-feature` run has shown a NoSQL defect. What the model gets wrong without the extension
comes from the research behind the scenario (Spring Data MongoDB 5.x, Spring Data Elasticsearch
6.x, Spring Data OpenSearch, Spring Boot 4, MongoDB manual and Java driver, Elastic and OpenSearch
docs, Testcontainers) — 50 pitfalls, re-verified against each page before the `references/` files
are written; versions are read at write time and never written into a template (invariant 8).

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | None observed. The planned solution-design step will be able to choose a document store or a search index for a case; every choice it can write needs a designer, or it falls back to JPA by omission. Anti-pattern 9 overridden on purpose, as in `0144`–`0146` | Create nothing (as the whole answer) |
| 2 — trigger | Model decision inside `/new-feature`, read from `00-caso-de-uso.md`; standalone by description | Form 2 |
| 5 — nature | Multi-step procedures (survey, interview, design, partial) plus declarative norms | Forms 4 and 5 as the whole answer |
| 6 — isolation | None — the interview is the task | Form 3 |
| 7 — mandatoriness | Persuasion, plus the guarantee already in place: `forbidden_imports` read by `check`/`doctor gate` and ArchUnit; the compose check is a warning by design (`0124`) | New Forms 7 and 8 |
| 8 — destination | Both — development skills and norms travel | — |
| — who decides the family | Fixed by the request, not asked: the request states it or it is inferred without ambiguity; `use-case-design` records it; a bare factual question only when unclear | Any criterion or recommendation inside this extension |
| — shape of the search piece | Its own design skill for a **separate** search engine only. Database indexes (B-tree, Mongo indexes) and in-database full-text (`tsvector`, `pg_trgm`, a Mongo text index) stay with `persistence-architect`: same artifact as the aggregate's storage. Split by artifact, as `0005` split by "moment and artifact" | A variant inside `persistence-architect`; a "search capability" skill across engines; one "indexes" skill for everything |
| — when the search skill runs | Only when the `Search index` row is not `none` — never always. It runs before `persistence-architect`, which reads its requirements; it is never loaded by it | — |
| — the datum | Two rows in `00-caso-de-uso.md`: `Storage` (per aggregate, `relational` or `document (MongoDB)`) and `Search index` (`none` or `<aggregate> → <engine> projection`). The search engine never appears as storage, which encodes "a projection, never the source of truth" | One combined row; the family carried only by the port kind |
| — the factual question | "SQL or NoSQL?" with `SQL (relational)` · `NoSQL (document, MongoDB)`. No NoSQL engine question: MongoDB is the only document engine in scope, and a one-option question is forbidden — recorded instead. Search enters only when the request names it; with no engine named, one question `Elasticsearch` · `OpenSearch`. `use-case-design` never asks whether a case wants a search index | Three options mixing storage and projection |
| — blueprint features | No new feature. The partial declares the dependency in § Declared dependencies and the service in a compose row, as `0145` and `0146` did; `persistence-jpa` means only "JPA installed at bootstrap". `/init-project` and `arch-adopt` unchanged. Two engines in one project are supported, per aggregate | A mandatory `persistence-mongodb` / `search-elasticsearch` feature (`0146` option 2 again) |
| — Mongo migration | Indexes and the `$jsonSchema` validator declared in code and ensured at startup (idempotent); a unique index that cannot be built fails the startup — the `ddl-auto: validate` equivalent. A changed index ships under a new name and the old one is dropped in a later release (expand/contract, `persistence.md` § Migrations). Shape evolves by `schemaVersion` with upcast on read; a backfill is a job (`jobs-architect`). No control collection | An own versioned runner; Flamingock (unverified on Spring Data MongoDB 5); a hybrid with versioned data changesets |
| — outbox and idempotency on Mongo | Supported, with a replica set mandatory (compose and Testcontainers included): `MongoTransactionManager` registered and qualified when JPA coexists; `outbox_events` collection with a `findAndModify` lease claim; `idempotency_keys` with a unique index and a single-field TTL; the prune stays `jobs-architect`'s job | Form B forbidden on Mongo; change streams as the relay (out of scope) |
| — eventual consistency | `Freshness` stays the single business budget. `23-busca.md` declares the lag it consumes (refresh plus indexer); `cache-architect` reads it and bounds its expiry by the remainder — remainder ≤ 0 means no cache | `Freshness` read twice without a split (the reader sees up to twice the tolerance); a separate `Index lag` row; the lag declared by the indexer case |
| — the indexer | Its own use case: the boundary test already separates it (another transaction). `Trigger` `inbound messaging adapter`, consumer designed by `messaging-architect`, document and write by `search-architect`; the reindex is another case, a job trigger, `jobs-architect`. The write case only publishes the change event; approved write cases receive impact rows | The indexer designed inside the write case |
| — port kind | `search index`, peer of `vector store`: returns ids or a read projection, never the aggregate | Reusing `persistence` |
| — the norm | One file `persistence.md`: a common boundary plus `Relational (JPA)` and `Document (MongoDB)` sections — same package, same `paths`, so two files would always load together. Search gets `rules/search.md`, territory `.search`; "a projection, never the source of truth" lives there | A file per technology with identical `paths` |
| — OpenSearch | In scope. Its version is resolved at design time from Maven Central's `maven-metadata.xml`, cross-checked with the compatibility matrix in the spring-data-opensearch README; no network, or no pair compatible with the project's Boot → ask | Elasticsearch only; version always asked |
| — names | `search-architect`, partial `23-busca.md` | `search-index-architect` |
| 17 — CI | Textual template tests in `validate` › `design`, plus the Java exemplars compiled in a real Initializr project by `templates.yml` | Textual only |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | MongoDB as a variant of `persistence-architect` + new `skills/search-architect/` (Form 1, class `design`, partial `23-busca.md`) + `persistence.md` restructured (common + relational + document) + new `rules/search.md` (Form 4, territory `.search`) + `Storage` / `Search index` rows + `search index` port kind + a `.search` key and the new forbidden prefixes in every blueprint | 7 | **Approved** |
| 2 | Same, with search as a conditional block of `20-persistencia.md` inside `persistence-architect` | 5 | Rejected — the projection is another moment and artifact, and its requirements must reach messaging and jobs, which run before persistence |
| 3 | MongoDB only now; search left for its own run | 5 | Rejected — the solution-design step could not write a search index, the stated goal |
| 4 | Create nothing | 3 | Rejected — the maintainer chose to anticipate |

### Option 1 — Mongo variant + `search-architect` + two norms + two rows (score 7)

**Motivator:** axis 1 (anticipation for the solution-design step, overridden as in `0144`–`0146`)
and the "shape of the search piece" answer — a document store is the same moment and artifact as
JPA (the aggregate's port, `20-persistencia.md`), and `0005` named the skill without technology
precisely so it would not need another; a search engine index is another artifact (a projection,
its own mapping, alias and reindex, fed by events, eventually consistent).

**Pros:** every family the solution-design step may write has a designer; the family is one datum
per aggregate that step writes later and `use-case-design` writes today; no criterion of choice
enters any file of this extension; "projection, never source of truth" is structural (search is
never a `Storage` value) and normative (`search.md`); the indexer and reindex reuse existing
owners (`messaging-architect`, `jobs-architect`) through the boundary test the pipeline already
applies; the import boundary is data the existing guarantee reads; same shape as `0087`, `0121`,
`0144`–`0146`; `persistence-architect` grows only by the Mongo variant.

**Cons:** the widest propagation of the series — `use-case-design` (two rows, the question, its
Out of scope), `domain-modeling` (port kind), `new-feature` (step 3e, short path, consolidation,
precedence, spec block), `persistence-architect` (variant, templates, references),
`cache-architect` (reads the lag), `messaging-architect` (indexer consumer, outbox on Mongo),
`jobs-architect` (reindex and alias swap, backfill), `docker-architect` (three service templates,
compose-service-decision rows), `test-architect` (Mongo and search ITs), the executor (Block 2
variant, Block I), `new-feature-implement`, eight blueprints, `extensions.json` (classes, export,
derived_paths, migration, a reshaped `datasource_pairs`), the `compose` mode's Java and its test,
routing and docs. Anticipation, not a symptom.

**Planned decisions inside the option:**

| # | Question | Answer |
|---|---|---|
| 1 | The datum | `00-caso-de-uso.md` gains `Storage` — one entry per aggregate the case writes or reads as its source: `relational` or `document (MongoDB)`, plus who chose (`request` · `answer`) — and `Search index` — `none` or `<aggregate> → Elasticsearch projection` / `OpenSearch projection`. A REUSE aggregate's storage is read from the code, never asked. Unambiguous inference: the request names the engine; the aggregate already lives in a collection or a table; the case extends already-persisted data; the request names full-text search over named fields. Never inference: a loose word — "documento", "flexível", "rápido", "escalável", "schemaless". The planned solution-design step writes the same rows |
| 1b | `use-case-design` wording | "Chooses no technology" becomes "weighs no technology: it records the storage family and the search engine the request states or the user answers, and nothing else". The Out of scope row "SQL or NoSQL" moves to "weighing SQL, document or search on merit — the planned solution-design step"; "Kafka or SQS" stays the blueprint's. The checklist gains a row: a question offering criteria, pros, cons or a recommendation between families → don't ask |
| 2 | `persistence-architect` | Reads `Storage`; stops without the row and tells the caller to run `use-case-design`, as it stops without `10-dominio.md`. Never asks the family; still asks the relational engine. Reads `23-busca.md`'s requirements (a version field on the source, the change event) when present. The document variant: § 1 becomes collections (document shape, embedded vs referenced inside the aggregate, bounded arrays, indexes, validator), § 4 becomes the index and validator declaration plus `schemaVersion` and any backfill asked of `35-jobs.md`, § 5 `spring.mongodb.*`. Step 9 looks for `mongo`. `references/pitfalls-mongodb.md` |
| 3 | `search-architect` | Form 1, class `design`, `opus`, partial `23-busca.md`. Entry: `Search index` ≠ none plus `10-dominio.md`. Owns the projection document (fields, `text`/`keyword`, analyzers, `nested`), mapping and settings as versioned JSON, the index name behind an alias, the refresh policy, the query shape (`search_after` + PIT, a page ceiling, no leading wildcard), the result (ids or a read projection), the lag it consumes from `Freshness`. Requirements out: to `10-dominio.md` (the change event with the source version), to `20-persistencia.md` (the version field), to `25-mensageria.md` (an indexer consumer ordered by key, `version_type=external`, `VersionConflictException` acknowledged), to `35-jobs.md` (reindex from the source and the atomic alias swap). Three case kinds: the read case, the indexer case, the reindex case. `references/pitfalls-search.md` |
| 3b | Step position | `/new-feature` step **3e**, after 3d and before 4, when `Search index` ≠ none and `23-busca.md` is missing (depends on 1, 2). Step 6 depends on 3e. Short path: a `document` storage with a new collection, field or index, or a `Search index` ≠ none, is an unmet criterion — "no new table, column, or migration" generalizes to "no new table, collection, column, field, index or migration" |
| 4 | Norms | `persistence.md`: § Boundary common to both families (port, explicit mapping, nothing of the framework inward, one repository per aggregate, subpackage per aggregate, exception translation, pagination mandatory); the current JPA content under `Relational (JPA)`; a `Document (MongoDB)` section — `@Document("<name>")` and `@TypeAlias`, `@Version` on every document, UUID `standard` and `decimal128`, no `@DBRef`/`@DocumentReference` across aggregates, bounded arrays, indexes declared and ensured at startup, expand/contract for an index change, `schemaVersion`, keyset pagination with an `_id` tiebreaker, transactions only on a replica set with `MongoTransactionManager`, one store per transaction, the whole transaction retried on `TransientClientSessionException` where it opens, `spring.mongodb.*`, a customizer never a settings bean, explicit timeouts, never an unacknowledged write concern. `rules/search.md`: territory `.search` + `**/src/main/resources/search/**`; the projection invariant; `createIndex=false`; explicit mapping and `dynamic: strict`, never `FieldType.Auto`; versioned index behind an alias; reindex from the source; external version from the source; no dual write in the request; no refresh on write unless read-your-write is declared; pagination and query shape; `_source` filtering; kNN stays a vector store (`ai.md`). Neither names a skill or a criterion of choice |
| 5 | Port kind | `domain-modeling` § 4f: a search over words, filters or facets becomes a port of kind `search index`, returning ids or a read projection; a search by meaning stays `vector store` |
| 6 | Dependencies | No feature. Initializr ids `data-mongodb` and `data-elasticsearch` resolved as `ai-architect` step 10 does; Spring Data OpenSearch and `spring-data-opensearch-testcontainers` from Maven Central `maven-metadata.xml` cross-checked with the README's compatibility matrix; no network or no compatible pair → ask. Only the partial's § Declared dependencies entitles the executor to write `pom.xml`. To verify at writing: the multi-module persistence module is gated by `feature: persistence-jpa` (`custom.template` sets it `false`) |
| 7 | Mongo migration | As the interview answered; the index declaration is a template (`MongoIndexes` run at startup), and the unique-index existence is proven by an IT |
| 8 | Shared collections | `outbox_events` and `idempotency_keys` as collections, replica set mandatory, lease claim by `findAndModify`, prune by the existing job. Change streams out |
| 9 | Errors | In `persistence.md` (Document) and `search.md`: `DuplicateKeyException`, `OptimisticLockingFailureException` and a write conflict → `ConflictException`; a search engine unreachable → `DependencyUnavailableException` (the read may degrade); `VersionConflictException` in the indexer → acknowledged, never an error. Store-down on Mongo is treated as JPA's is today |
| 10 | Compose | `docker-architect` templates `mongo-service` (single-node replica set, `rs.initiate()` in the healthcheck; whether auth with a replica set needs a keyfile is verified at writing), `elasticsearch-service` (`ELASTIC_PASSWORD`, never `_FILE`; `single-node`; replicas 0; `vm.max_map_count` note), `opensearch-service` (`OPENSEARCH_INITIAL_ADMIN_PASSWORD`); rows in `compose-service-decision.md`. `datasource_pairs` reshaped to a list of pairs, each with its own properties and engines (invariant 10), plus a Mongo pair on `spring.mongodb.uri` and `MONGO_INITDB_ROOT_*` with no database comparison (Mongo creates it lazily); the `compose` mode's Java follows the shape |
| 11 | Tests | `test-architect`: `MongoPersistenceIT` (`MongoDBContainer` from `DockerImageName.parse(…)` with `.withReplicaSet()`, a test that the unique index exists), `SearchIndexIT` (`@Ssl @ServiceConnection ElasticsearchContainer`; `OpenSearchContainer` via `spring-data-opensearch-testcontainers`; `IMMEDIATE` refresh only in the fixture), an indexer IT proving an out-of-order event is acknowledged. `DockerImageName.parse` everywhere, so the compose × `src/test` tag comparison covers them |
| 12 | Executor | Block 2 reads `Storage` and follows the relational or document exemplars; new Block I (search) in the `adapters` group. `new-feature-implement` lists it |
| 13 | Destination and migration | `export.skills.include` + `skill_classes.design` for `search-architect`; `derived_paths["search.md"]` (suffix `.search`, optional, extra the mapping folder); a `.search` key in all eight blueprints; `org.springframework.data.mongodb..`, `com.mongodb..`, `org.bson..`, `org.springframework.data.elasticsearch..`, `co.elastic..`, `org.opensearch..` in `application` (and `domain` where `org.springframework..` does not already cover them); a `nosql-adapter-boundary` migration, the shape of `ai-adapter-boundary` |
| 14 | Out of scope, with the reason | Reactive stack; Cassandra, Neo4j and Redis as a store; Atlas Search; GridFS; sharding; CSFLE and Queryable Encryption; a search engine as the aggregate's source of truth; data streams and ILM for logs; change streams as the outbox relay; Envers and auditing; any criterion or interview of choice between SQL, document and search |

**Points cut in the rubric:** 1 (form fit — no signal, overridden by the maintainer) · 5
(maintenance: the widest set of files touched in the series) · 6 (half a point — the first
reshape of a `compose` data block after `0124`).

**CI:** `validate` › `design` › `frontmatter schema` already proves the new skill's class,
sections, `export` entry and the rule's `paths` and `derived_paths`; the leaf, single-owner and
"every norm paths matches some blueprint" steps cover the norms; `compose service templates merge
and parse` covers the three service templates. New: (a) `.claude/.ci/PersistenceTemplateTest.java`
in `validate` › `design` — fails by name on a document exemplar without `@Version`, without
`@Document("<name>")` or without `@TypeAlias`; a Mongo IT without `.withReplicaSet()` or with a
container literal instead of `DockerImageName.parse`; a Mongo config declaring a
`MongoClientSettings` or `MongoCustomConversions` bean, or `spring.data.mongodb.uri`; a blueprint
whose `application` allows a Mongo prefix; (b) `.claude/.ci/SearchTemplateTest.java` in the same
job — fails on `createIndex = true` or no `createIndex = false`, `FieldType.Auto`, a refresh policy
on the write path, a `from`/`size` page without a ceiling, a leading wildcard, an exemplar outside
the search skill importing a search client, a blueprint without the `.search` key or allowing a
search prefix in `application`; (c) `JavaTemplatesTest` in `templates.yml` compiles the Mongo and
search exemplars in a fresh Initializr project (OpenSearch from Maven Central), paths in both
trigger lists; (d) `exemplar-imports` gains `data-mongodb` and `data-elasticsearch`; (e)
`ComposeGateTest` gains the Mongo pair (a diverging user warns, a matching one passes, the
password never printed); (f) `AgentInstructionsTest` fixture and `MigrationsSchemaTest` for the
new prefixes and entry.

**Planned shape:**

```
use-case-design ── Storage: <agg> → relational | document (MongoDB)   (request, or one factual question)
                └─ Search index: none | <agg> → Elasticsearch | OpenSearch projection
                                │
new-feature  2 domain ─► 3/3g ─► 3b ─► 3c ─► 3d ─► 3e search-architect ─► 4 messaging ─► 5 jobs ─► 6 persistence ─► 6b cache ─► 7 tests
                                                   │ only when Search index ≠ none
                                                   │ writes 23-busca.md: projection, mapping JSON, alias,
                                                   │   query shape, lag consumed, requirements out
                                                   ▼
             10-dominio (change event, version) · 20-persistencia (version field) · 25-mensageria (indexer)
             35-jobs (reindex + alias swap) · 22-cache (expiry ≤ Freshness − lag)
persistence-architect ── reads Storage: relational (asks engine) | document (MongoDB variant)
executor  Block 2 (relational | document) ─► … ─► Block I (search)
rules     persistence.md (common · Relational (JPA) · Document (MongoDB)) · search.md (.search)
```

### Option 2 — search as a block of `20-persistencia.md` (score 5)

One skill for all persistence. The skill passes ~600 lines, mixes the aggregate's transactional
moment with the projection's eventual one, and buries the coordination with messaging and jobs in
an optional block; the step that must run before messaging would be the persistence step that runs
after it. Cut: 1, 5, 7 (the step order cannot express "before messaging" for half a skill).

### Option 3 — MongoDB only, search later (score 5)

Half the propagation and no new skill. The solution-design step could not write a search index,
the stated goal; the norm split and the datum would be designed twice. Cut: 1, 5 (a second pass
over the same rows), 7.

### Option 4 — create nothing (score 3)

What the decision matrix recommends with no symptom (anti-pattern 9). A case whose request names
MongoDB would be designed in JPA, and `use-case-design` would keep sending a technology choice to
a blueprint feature that does not exist.

## References

| Claim | Source |
|---|---|
| The skill's name was chosen without technology so MongoDB needs no other skill | `decisions/0005-persistence-rule-and-design.md` § Name |
| Division by moment and artifact | `skills/persistence-architect/SKILL.md` § Boundary with neighboring skills |
| A pipeline design piece is Form 1 | decision matrix § 4 · `decisions/0007-pipeline-skills-invocation.md` |
| No agent: the interview is the task | decision matrix § 5 |
| No signal → create nothing, unless overridden | decision matrix § 3 and anti-pattern 9 |
| Norm in one owner; a rule never names a skill | `@CLAUDE.md` invariants 1 and 2 |
| Boilerplate only in `templates/*.example` | `@CLAUDE.md` invariant 3 |
| Package territory is blueprint data with a `derived_paths` entry | `@CLAUDE.md` invariants 7 and 9 · `extensions.json` `export.derived_paths` |
| Versions resolved at runtime; no network → ask | `@CLAUDE.md` invariant 8 · `skills/ai-architect/SKILL.md` step 10 |
| A datum per case, written by `use-case-design`, read by a conditional step | `decisions/0146-graphql-api-architect-skill.md` (Trigger) · `0144` (Freshness) |
| A feature is a dependency, not a decision | `blueprints/_schema.md` § features |
| A dependency declared by the partial, not by a feature | `decisions/0145`, `0146` |
| One use case, one side effect | `skills/use-case-design/references/scope-boundary.md` |
| Compose data is read, never hardcoded | `@CLAUDE.md` invariant 10 · `extensions.json` `compose.datasource_pairs.$comment` · `decisions/0124` |
| Only `DockerImageName.parse` is compared | `ArchHook.java` `testImagePins()` |
| A migration when `forbidden_imports` grows | `extensions.json` `migrations` `ai-adapter-boundary`, `graphql-adapter-boundary` |
| Expand/contract | `rules/persistence.md` § Migrations |
| The outbox has three owners | `docs/pt-br/11-pitfalls.md` § Donos dentro de uma rodada de feature |
| `cache-architect` may conclude no cache | `skills/cache-architect/SKILL.md:37` |

## Propagation

| File | Change |
|---|---|
| `.claude/rules/persistence.md` | Restructured: common Boundary (an aggregate in one store, a transaction in one store), Queries and Translation; the JPA content under `Relational (JPA)`, unchanged; new `Document (MongoDB)` — Mapping, Identity and versioning, Indexes and schema evolution, Queries, Transactions, Configuration; How to verify extended |
| `.claude/rules/search.md` | New norm — the projection invariant, index lifecycle, mapping, writes, queries, failure, configuration, tests; territory `.search` + `src/main/resources/search/` |
| `.claude/rules/00-index.md` | `persistence.md` row rewritten; `search.md` row; listed with the package-territory rules |
| `.claude/skills/persistence-architect/SKILL.md` | Family rule (the `Storage` row read, never chosen), entry on `Storage` and on `23-busca.md`, document variant in steps 1–9, partial table note |
| `.claude/skills/persistence-architect/templates/` | New: `MongoDocument`, `MongoRepositoryAdapter`, `MongoCollections`, `MongoConfig` (both transaction shapes), `DocumentTransactions`, `MongoSharedCollections` (`.java.example`), `application-mongodb.yml.example`, `persistence-spec-document.md.example`; `persistence-spec.md.example` gains the `Storage family` row |
| `.claude/skills/persistence-architect/references/pitfalls-mongodb.md` | New — 34 pitfalls, four observed |
| `.claude/skills/search-architect/**` | New skill: `SKILL.md`; templates `SearchDocument`, `SearchIndexBootstrap`, `SearchAdapter`, `SearchRebuild` (`.java.example`), `search-index.json.example`, `application-search.yml.example`, `search-spec.md.example`; references `pitfalls-search.md` (36, six observed) and `search-reference-links.md` |
| `.claude/skills/use-case-design/SKILL.md` + `templates/use-case-spec.md.example` | "Weighs no storage technology"; Out of scope row for weighing families; the `Storage` interview block with its inference rules and the factual question; two checklist rows; `Storage` and `Search index` rows in the template; `Freshness` as the whole budget |
| `.claude/skills/use-case-design/examples/*/00-caso-de-uso.md` (13) | `Storage` and `Search index` rows |
| `.claude/skills/domain-modeling/SKILL.md` + `templates/domain-spec.md.example` | Step 4f, port kind `search index` |
| `.claude/skills/new-feature/SKILL.md` + `templates/feature-spec.md.example` | Step 3e, dependencies, short-path criterion 8, Step 3e body, cache bound minus the lag, consolidation (search block, dependency and compose gates, two precedence rows); spec block `3.8 Search`, `Storage family` row |
| `.claude/skills/cache-architect/SKILL.md` | Reads `23-busca.md` § 6; expiry bounded by the remainder |
| `.claude/skills/messaging-architect/SKILL.md` | Reads `23-busca.md` § 8 for an indexer consumer; the document family's outbox exemplar |
| `.claude/skills/jobs-architect/SKILL.md` | Two entry sources: a search rebuild, a document backfill |
| `.claude/skills/docker-architect/**` | Templates `mongo-service`, `elasticsearch-service`, `opensearch-service`; three catalog rows; reads `23-busca.md`; `compose-service-decision.md` names `search-architect` |
| `.claude/skills/test-architect/**` | Templates `MongoPersistenceIT`, `SearchIndexIT`; design mode for both; exemplar list |
| `.claude/agents/java-spring-boot-developer.md` | Block 2 document family; Block I (I1–I3); reads `search.md`; writes `[search]/**` and `src/main/resources/search/`; group `adapters` lists I |
| `.claude/skills/new-feature-implement/SKILL.md` | Group `adapters` lists I |
| `.claude/blueprints/*/*.yaml` (8) | A `.search` packages.map key; MongoDB and search prefixes forbidden in `application` and `domain`/`core`; layered and vertical-slice dependency rules for `search` |
| `.claude/schemas/extensions.json` | `skill_classes.design`, `export.skills.include`, `derived_paths["search.md"]` and its exempt glob, `compose.datasource_pairs` as a list with the MongoDB pair, the `nosql-adapter-boundary` migration |
| `.claude/hooks/ArchHook.java` + `ArchHook.jar` | `datasourceLoginWarnings` per pair; `pairLoginWarnings`, `urlDefaults` (a no-default placeholder inside a URL kept as a marker), `pattern`, `group`; the warnings' variable names from data |
| `.claude/.ci/PersistenceTemplateTest.java`, `SearchTemplateTest.java` | New, `validate` › `design` |
| `.claude/.ci/ComposeGateTest.java`, `AgentInstructionsTest.java`, `JavaTemplatesTest.java` | MongoDB login cases; the fixture's new prefixes; five new block templates compiled |
| `.github/workflows/validate.yml`, `templates.yml` | Two `design` steps; the compose step's two passwords; `exemplar-imports` gains `data-mongodb`, `data-elasticsearch` and three prefixes, and the Testcontainers 1.x `MongoDBContainer` in the deny list; `templates.yml` path filters |
| `CLAUDE.md` | Persistence routing row; `search-architect` row |
| `README.md`, `claude-help.md`, `roadmap.md`, `docs/{en,pt-br}/*`, `project-bootstrap/templates/root.CLAUDE.md.example` | Pipeline, design-skill lists and counts, CI rows, pitfalls — the mechanical step 8, delegated with this record |

Goes to the generated project: **yes** — `search-architect` via `export.skills.include`, `search.md`
via `export.rules` with its glob derived from the blueprint's `.search` entry plus the mapping
folder; `persistence.md` and the `persistence-architect` templates travel as before. A
`nosql-adapter-boundary` migration for the blueprints whose `forbidden_imports` or dependency rules
change.

**Found while writing, recorded here:**

- **The exemplars ran, and six of them changed.** A strict mapping refused Spring Data's own `_class`,
  id and version in `_source` (`strict_dynamic_mapping_exception`) — `writeTypeHint = FALSE`,
  `storeIdInSource = false`, `storeVersionInSource = false`. A rebuild bulk built with
  `IndexQueryBuilder.withObject` and no `withVersion` overwrote the event the indexer wrote meanwhile —
  the first IT passed because it only counted hits; the strengthened one failed, and the fix is
  `withVersion` plus a 409-only `BulkFailureException` read as stale. The query's cursor cast
  `getSortValues()` to `Number`: fine on Elasticsearch, `ClassCastException` on OpenSearch, which
  returns its own `FieldValue` — the cursor is now the last item's fields. The startup schema
  initializer and the search bootstrap were package-private, unimportable by a test in an aggregate's
  subpackage — public, like `AssignedIdEntity`.
- **Elasticsearch answers 401 for a moment after its container is ready.** A startup component that
  calls the engine during context refresh met it every run; a probe calling after the context passed.
  The test waits for an authenticated `_cluster/health` on 9200 (the default strategy probed 9300).
- **Declaring a `MongoTransactionManager` removes Spring Boot's JPA transaction manager** — proved with
  an `ApplicationContextRunner`: `mongoTransactionManager` present, no `JpaTransactionManager`. Hence
  the JPA-coexisting shape declares both, JPA's primary.
- **`replicaSet=rs0` in the host URI breaks every call** against the compose replica set (the member is
  advertised as `mongo:27017`); `directConnection=true` works and keeps transactions.
- **The compose check would have skipped MongoDB in silence.** `springDefault` returns null for a URL
  with a no-default placeholder inside — every MongoDB URI, whose password has none. `urlDefaults`
  keeps it as a marker; the red run of `ComposeGateTest` shows the case it would have missed.
- **Local engines run with authentication and without HTTP TLS.** Both compose services came up
  healthy that way and answered 401 without credentials; production keeps TLS through the SSL bundle
  the yml activates outside `local`, `docker` and `test`.
- **One adapter for both engines.** Spring Data OpenSearch implements Spring Data Elasticsearch's API:
  the same document, adapters and IT body ran 6/6 on Elasticsearch 9.4.8 and OpenSearch 3.9.0, only
  the dependency, the container class and the slice differing. `NativeQuery` would have broken that —
  it belongs to Elasticsearch's client — and is refused by `SearchTemplateTest`.
- **Versions resolved on the day, none written into a norm.** Initializr default Spring Boot 4.1.1
  (Spring Data MongoDB 5.1.1, driver 5.8.1, Spring Data Elasticsearch 6.1.1, client 9.4.5,
  Testcontainers 2.0.5); Spring Data OpenSearch 3.1.4 from the README's matrix row for 4.1.x and Maven
  Central, `opensearch-java` 3.10.0 (the matrix asks 3.0.x and above), `opensearch-testcontainers`
  4.1.0. Image tags `mongo:8.0`, `docker.elastic.co/elasticsearch/elasticsearch:9.4.8`,
  `opensearchproject/opensearch:3.9.0`, each checked in its registry.
- **Not verified, left as stated risks:** the multi-module persistence module is gated by
  `feature: persistence-jpa` (`custom.template` sets it false), so a MongoDB-only project generated
  from it has no persistence module until the feature is on; and a full application context with the
  OpenSearch starter, whose README advises excluding Spring Boot's Elasticsearch data
  auto-configuration — the slice test ran without it.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › document-family exemplars name collection, alias and version, and run a replica set` (`.claude/.ci/PersistenceTemplateTest.java`) | Every MongoDB document exemplar names its collection, alias and `@Version Long`; no `@DBRef`/`@DocumentReference`, `@Indexed`, derived `Containing`/`IgnoreCase`, `Page` on a MongoDB repository, settings or conversions `@Bean`, compound TTL; the yml's prefix, representations, index creation, timeouts and password; `DocumentTransactions` free of MongoDB; the IT's replica set, `DockerImageName.parse` and initializer; the compose service's replica set, keyfile, initiation and required password; no MongoDB import elsewhere; the blueprints' inner layers | green on the tree; red, by file and reason, on each of 8 injections: no `@Version`, no collection name, no `@TypeAlias`, no `.withReplicaSet()`, UUID `java-legacy`, a compound TTL, no `--replSet`, hexagonal `application` without `org.springframework.data.mongodb..` (the first two runs of two injections hit the header comment instead of the code — re-aimed at the code line, red) |
| `validate · design › search exemplars keep the index a strict, versioned projection` (`.claude/.ci/SearchTemplateTest.java`) | Every search document turns index creation off, versions externally, writes no type hint and keeps id and version out of `_source`; no untyped `@Field`, `FieldType.Auto`, repository, `NativeQuery`, refresh policy, `getSortValues()` cast or `IndexQueryBuilder` without `withVersion`; a strict mapping with no fixed replica count; the yml's password and timeouts; the IT's image pin, `@Ssl` and authenticated wait; both compose services' required password and single node; no search import elsewhere; every blueprint's `.search` key, inner layers and `search` dependency rules | green; red by file and reason on each of 9 injections: `createIndex = true`, an untyped `@Field`, `storeIdInSource` removed, `withVersion` removed, `"dynamic": true`, `@Ssl` removed, a default password in the Elasticsearch service, layered without `.search`, vertical-slice without a `search` rule |
| `validate · hooks-cross-platform › … compose …` (`ComposeGateTest`) | The MongoDB pair: the template's convention quiet, a host default naming another user warned, a URI with no password against a service that requires one warned, never in the gate; every JDBC case unchanged | green; red with `urlDefaults` reverted to `springDefault` — "a host default naming another user … warned" absent, the silent skip the reshape fixed |
| `validate · hooks-cross-platform › … agent instruction files …` (`AgentInstructionsTest`) | A module `AGENTS.md` missing a blueprint prefix is named | red on the first run after the blueprint change, green with the fixture listing the ten new prefixes |
| `templates › JavaTemplatesTest` | `MongoDocument`, `MongoCollections`, `MongoConfig`, `SearchDocument` and `SearchIndexBootstrap` compile in a fresh Initializr project with `data-mongodb` and `data-elasticsearch` | green, 34 files; red on `MongoClientSettingsBuilderCustomizer` from the Spring Boot 3 package — `MongoClientConfig.java: package org.springframework.boot.autoconfigure.mongo does not exist` |
| `validate · exemplar-imports` | Every import of the new exemplars resolves against the Initializr's classpath | the job gains `data-mongodb`, `data-elasticsearch` and the `com.mongodb`, `org.bson`, `co.elastic` prefixes; the same imports resolved in the proof project below |
| `validate · design › compose service templates merge and parse` | The three new service templates merge into the base compose | green locally with the step's script, 13 templates, after adding `MONGO_PASSWORD` and `SEARCH_PASSWORD` to its environment (without them `:?` fails the step) |
| `validate · design › frontmatter schema`, `rules is a leaf`, `norm contains no code boilerplate`, `each norm has a single owner`, `every norm paths matches some blueprint`, `no hardcoded Spring/Java version outside decisions/`, `blueprint-declared templates exist on disk` | Skill class, sections, export entry, the rule's `paths` and `derived_paths`, the leaf, the globs, no dated version | green locally; `new blueprint doesn't touch prompts` reads `git status` and is red only on an uncommitted tree |
| `validate · export-determinism` + `ArchHook.java export` by hand | `search.md` travels with its glob derived from each blueprint, the skill with it | seven blueprints exported: `**/adapter/out/search/**`, `**/infrastructure/search/**` ×2, `**/app/search/**`, `**/search/**` ×2, `**/shared/internal/adapter/search/**`, each with `**/src/main/resources/search/**`; `schema` exit 0 in each |
| Outside CI, on the day | The exemplars run | a Spring Boot 4.1.1 Initializr project: `MongoPersistenceIT` 6/6 against `mongo:8.0` as a replica set; `SearchIndexIT` 6/6 against Elasticsearch 9.4.8 and 6/6 against OpenSearch 3.9.0 with the same adapters; the three compose services up and healthy, the MongoDB one as `PRIMARY` with a transaction committed from the host URI; the JPA back-off proved by `ApplicationContextRunner` |
