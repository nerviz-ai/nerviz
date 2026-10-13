# Pitfalls — MongoDB with Spring Data, the document family

Reference for the `persistence-architect` skill, document family. What a model writes wrong
without the norm, each with the section of `@.claude/rules/persistence.md` that prevents it and
one source. **Observed** marks the ones reproduced while the exemplars were written and run
against a real replica set (decision `0147`, 2026-10-13); the rest come from the official
documentation and the libraries' source, re-read on that date.

Versions are never written here: resolve the project's own (`./mvnw dependency:tree`) before
applying a line that depends on one (`@CLAUDE.md` invariant 8).

## Mapping and identity

| # | Pitfall | Norm | Source |
|---|---|---|---|
| 1 | `save()` on a document with no `@Version` is a whole-document upsert: concurrent writes lose each other, and an insert of an existing id overwrites it with no error | § Document · Identity and versioning | Spring Data MongoDB, *Template CRUD*; `MongoTemplate.doSave` |
| 2 | An assigned UUID id without `@Version` makes `isNew` always false: `@CreatedDate` stays null and the duplicate is silent | § Document · Identity and versioning | Spring Data Commons, *Object Mapping* — entity state detection |
| 3 | An update built from the aggregate as a fresh document carries a `null` version and is inserted as a duplicate instead of updating | § Document · Identity and versioning | `MongoTemplate` version handling |
| 4 | No `@TypeAlias`: `_class` holds the fully qualified class name, and moving the class breaks polymorphic reads of every stored document | § Document · Mapping | Spring Data MongoDB, *Mapping* — type mapping |
| 5 | No `@Document("<name>")`: the collection is named after the class, and a rename reads an empty collection | § Document · Mapping | Spring Data MongoDB, *Mapping* |
| 6 | A `String` id that looks like 24 hex characters is stored as an `ObjectId`; a lookup by the string finds nothing | § Document · Identity and versioning | Spring Data MongoDB, *Mapping* — `_id` field handling |
| 7 | `@DBRef` / `@DocumentReference` between aggregates: one query per reference, a lazy proxy, no cascade | § Document · Mapping | Spring Data MongoDB, *Document References* |
| 8 | An embedded array that grows with the aggregate's history reaches the 16 MiB document limit, and every later write fails | § Document · Mapping | MongoDB Manual, *Limits*; *Unbounded arrays* antipattern |
| 9 | `LocalDateTime` converted through the JVM's zone; two instances in two zones disagree | § Document · Mapping | Spring Data MongoDB, *Custom Conversions* |
| 10 | A UUID with no representation configured: the driver refuses to encode it ("The uuidRepresentation has not been specified") | § Document · Identity and versioning | Spring Boot 4 Migration Guide; MongoDB Java driver, UUID representation |
| 11 | `BigDecimal` stored as a string by default in older versions, compared and sorted lexically | § Document · Mapping | Spring Data MongoDB 5 migration guide — `BigDecimal` representation |

## Indexes, schema and migration

| # | Pitfall | Norm | Source |
|---|---|---|---|
| 12 | `@Indexed(unique = true)` with `auto-index-creation` off (the default): the unique index never exists and duplicates go in | § Document · Indexes and schema evolution | Spring Data MongoDB, *Index management* |
| 13 | An index changed in place: same name, other options → `IndexOptionsConflict` at startup | § Document · Indexes and schema evolution | MongoDB Manual, `createIndexes` |
| 14 | A TTL on a compound index: `expireAfterSeconds` ignored; TTL runs about once a minute | § Document · Indexes and schema evolution | MongoDB Manual, *TTL indexes* |
| 15 | A collection created implicitly inside a transaction — not safe under concurrency | § Document · Indexes and schema evolution | MongoDB Manual, *Transactions — create collections and indexes* |
| 16 | Flyway for MongoDB does not exist; Mongock has no module for the current Spring Data and Spring Boot generation and reaches end of life in 2026; Flamingock is unverified on them | § Document · Indexes and schema evolution (declared and ensured at startup) | Mongock legacy repository; Flamingock documentation |
| 17 | **Observed.** The startup schema initializer left package-private: every aggregate's persistence test in another subpackage cannot import it, and its slice runs with no index at all | `MongoCollections.java.example` (public) | run of `MongoPersistenceIT`, 2026-10-13 |

## Queries

| # | Pitfall | Norm | Source |
|---|---|---|---|
| 18 | `skip` pagination over a large collection, and a `Page` counting the whole collection on every page | § Document · Queries | MongoDB Manual, `cursor.skip`; Spring Data, *Scrolling* |
| 19 | A derived `Containing` / `IgnoreCase` compiles to a regular expression no index serves | § Document · Queries | Spring Data MongoDB, *Query methods* |
| 20 | A sort without `_id` as the last key: an unstable order between pages | § Queries | MongoDB Manual, `cursor.sort` — sort consistency |
| 21 | A huge `$in` list: the query document itself is limited to 16 MiB | § Queries | MongoDB Manual, *Limits* |

## Transactions

| # | Pitfall | Norm | Source |
|---|---|---|---|
| 22 | No `MongoTransactionManager`: Spring Boot registers none, and `@Transactional` over a document write does nothing | § Document · Transactions | Spring Data MongoDB, *Sessions & Transactions* |
| 23 | **Observed.** Declaring a `MongoTransactionManager` in a project with JPA makes Spring Boot's JPA manager back off — the context holds `mongoTransactionManager` and no `JpaTransactionManager`, and every JPA `@Transactional` then runs on MongoDB's | § Document · Transactions | `ApplicationContextRunner` over the JPA auto-configuration, 2026-10-13 |
| 24 | A transaction on a standalone server fails; Testcontainers' `MongoDBContainer` is standalone without `.withReplicaSet()`, and Spring Boot's compose support builds no replica set | § Document · Transactions | MongoDB Manual, *Transactions*; Testcontainers, *MongoDB* |
| 25 | No retry of the whole transaction on a transient error or an unknown commit result: a primary step-down or a write conflict becomes a sporadic 500 | § Translation; § Document · Transactions | MongoDB Manual, *Transactions in applications*; Spring Data `TransientClientSessionException` |
| 26 | A write conflict mapped as "invalid input" or as a duplicate, when it is concurrency | § Translation | Spring Data `MongoExceptionTranslator` — error code 112 |
| 27 | A transaction held over 60 seconds is aborted by the server | § Document · Transactions | MongoDB Manual, *Production considerations* |
| 28 | `WriteConcern.UNACKNOWLEDGED` swallows the duplicate key and the optimistic-lock failure | § Document · Transactions | MongoDB Java driver, write concern |
| 29 | **Observed.** `replicaSet=rs0` in the host URI of a compose replica set: the driver discovers the member by its compose name, which the host cannot resolve, and every call times out | `application-mongodb.yml.example` (`directConnection=true`) | run against `mongo-service.yml.example`, 2026-10-13 |

## Configuration

| # | Pitfall | Norm | Source |
|---|---|---|---|
| 30 | `spring.data.mongodb.uri` set and ignored by Spring Boot 4 — the application connects to `localhost/test` without an error | § Document · Configuration | Spring Boot 4 Migration Guide |
| 31 | A `MongoClientSettings` or `MongoCustomConversions` bean of the project's own silently drops Spring Boot's URI, UUID representation and SSL bundle | § Document · Configuration | Spring Boot, *Working with NoSQL* — MongoDB |
| 32 | The driver's defaults left in place: 30 s to select a server, no socket timeout at all | § Document · Configuration | MongoDB Java driver, connection options |
| 33 | A credential written into the URI inside a versioned `application.yml` | § Boundary; `@.claude/rules/secrets.md` | — |
| 34 | `MONGO_INITDB_*_FILE` in the compose file: Spring Boot's compose support rejects it | — (`docker-architect`'s `mongo-service.yml.example`) | Spring Boot, *Dev services* — MongoDB |
