---
paths:
  - "**/adapter/out/persistence/**"
  - "**/infrastructure/persistence/**"
  - "**/db/migration/**"
status: active
---

# Persistence — mapping, migrations, and data access

The store is an adapter detail: versioned, explicit, and invisible to the domain. Two families
of store are covered — relational, through JPA, and document, through MongoDB — and every
aggregate lives in exactly one of them. Which one is a fact of the use case, recorded before the
first field is mapped; nothing in this file chooses it. § Boundary, § Queries and § Translation
hold for both; each family's own section holds only for that family.

## Why this is a rule and not a `CLAUDE.md` section

It's a declarative fact with an identifiable territory — the globs in `paths` above —
and not a universal fact about the repository. It auto-loads when someone touches
persistence, and costs zero context in sessions that don't touch it. Decision recorded
in `@.claude/decisions/0005-persistence-rule-and-design.md`; the document family in
`@.claude/decisions/0147-document-storage-and-search-architect.md`; auditing in
`@.claude/decisions/0149-auditing-variant-of-persistence-architect.md`.

## Boundary

- The persistence entity (`@Entity`) or document (`@Document`) lives in the outbound adapter.
  Never in the domain
- Zero `jakarta.persistence`, `org.springframework.data.mongodb`, `com.mongodb`, `org.bson`,
  `org.hibernate.envers` and `org.springframework.data.history` outside the persistence adapter
- No persistence entity or document crosses the boundary, inward or outward: the domain ↔
  persistence mapping is explicit and lives in the adapter
- A framework repository interface (Spring Data) is an internal adapter detail. What the
  inner layers know is the outbound port they declare
- Zero `@Transactional` in the adapter. The transaction opens and closes in the
  application layer — `@.claude/rules/architecture-ddd.md`. **One exception, and this line
  owns it:** a write no use case's transaction covers — a relay's per-row mark after a
  broker send, one bounded batch of a prune — carries its own `@Transactional` on that
  adapter method and nowhere else. The application-layer pass that drives it opens none,
  since a transaction held across a broker call is the long transaction the outbox exists
  to avoid
- **An aggregate lives in one store, and a transaction spans one store.** Two families in the
  same project are allowed, one per aggregate; a use case that writes two stores commits the
  first and reaches the second through an event (`@.claude/rules/messaging.md` § Publication
  timing), never through one transaction across both — there is none, and two sequential
  commits are a lost write waiting for the second one to fail
- One repository per aggregate. A repository that returns pieces of two aggregates is a
  query, and a query has its own port
- **One subpackage per aggregate inside the persistence adapter**, always — including
  while the project has a single aggregate. Entity, mapper, Spring Data interface, and
  adapter of the `User` aggregate live in `…/persistence/user/`, the `Order` ones in
  `…/persistence/order/`. A single flat package holding every aggregate's classes breaks
  SRP at the package level and grows without bound: 3 aggregates are already 13 classes.
  The `test/` tree mirrors the same subpackages. The conditional variant — flat while
  there's one aggregate, split when the second arrives — is not the norm: it trades a
  level of nesting for a multi-file refactor whose trigger nobody notices in time
- **Visibility is package-private by default**, for entity, mapper, Spring Data
  interface, and adapter alike. Splitting by aggregate makes the default bite: a class of
  aggregate `A` is no longer visible from aggregate `B`'s subpackage. Only the specific
  class another aggregate genuinely consumes becomes `public` — one class at a time,
  never the whole subpackage, and never preemptively. Before opening one up, check
  whether the outbound port the adapter already implements covers the need: depend on the
  port, not on the concrete adapter. This holds inside an integration test's fixture
  setup too — a test that inserts a referenced row to satisfy a foreign key depends on
  that aggregate's port, the same as production code does
- No driver, JPA, or framework exception leaves the adapter: the adapter translates it
  into the taxonomy of `@.claude/rules/error-handling.md`, per § Translation
- Credentials come from the environment — `@.claude/rules/secrets.md`

## Queries

- The repository takes Spring Data's `Pageable` and returns `Page<Entity>` or `Slice<Entity>` —
  internal to the adapter, same as any other Spring Data interface (§ Boundary). The adapter
  maps the port's own pagination type into `PageRequest`/`Sort` going in, and the Spring Data
  result into the port's own page result going out; neither `Pageable` nor `Page` crosses into
  the application port (`@.claude/rules/architecture-ddd.md` § Application)
- Every listing is paginated. A query that returns an unbounded collection is a bug
- A page without a deterministic order returns an unstable result between runs: the order ends
  in a unique key
- Projection (`record` or interface) when the whole aggregate isn't needed
- Zero value concatenation in a query, in any query language. Bound parameter, always

## Translation

| Raised in the adapter | Becomes |
|---|---|
| A unique constraint or unique index violated (`DataIntegrityViolationException`, `DuplicateKeyException`) | `ConflictException` — duplicate |
| `OptimisticLockingFailureException` (any subclass) | `ConcurrentUpdateException` (`<AGGREGATE>_CONCURRENT_UPDATE`) — concurrent modification (`@.claude/rules/error-handling.md` § Shape of the base classes) |
| A document-store write conflict or transient transaction error (`TransientDataAccessException`) | Nothing in the adapter: it propagates so the transaction is retried whole where it opens (§ Document · Transactions); once the retries are spent, `ConcurrentUpdateException` — concurrent modification, never a duplicate |

A constraint the client can fix by changing its input is the use case's business rule, checked
before the write; the translation above is the backstop for the race the check cannot close.

## Relational (JPA)

### Writes

- Translating a constraint violation requires a **flush inside the adapter**
  (`saveAndFlush`). With `save`, the `INSERT` only reaches the database at the
  transaction's commit — outside the adapter's `try` — and the violation escapes
  translation: the client gets 500 where it should get 409. The translation covers both
  cases, constraint violation and optimistic-lock failure, because they arise at
  different moments
- An aggregate that doesn't carry `version` (`@.claude/rules/value-objects.md`'s
  counter-catalog) still needs the **row's real version** at update time. `save()` for
  an update must load-and-mutate the managed entity (`repository.findById`, then mutate
  the fetched instance), never construct a fresh detached one from the domain object via
  `mapper.toEntity`: the primitive `long` field defaults to `0`, so the first update
  succeeds and every one after it false-conflicts against the row's real version with
  `ObjectOptimisticLockingFailureException`, with zero real concurrency involved

### Mapping

- Explicit `fetch = FetchType.LAZY` on **every** `@ManyToOne` and `@OneToOne`: the
  default for these two is `EAGER` and produces silent N+1
- Zero `CascadeType.ALL` and `CascadeType.REMOVE` on an association that leaves the
  aggregate
- Bidirectional relation only when both directions are actually navigated
- The persistence entity has no business logic: fields, annotations, and access only
- `@Enumerated(EnumType.STRING)` always. `ORDINAL` is forbidden — reordering the enum
  corrupts already-stored rows
- Money in `BigDecimal` with explicit `precision` and `scale` on the column. `double`
  and `float` are forbidden for monetary values
- Instant in `Instant` or `OffsetDateTime`, stored in UTC, column with a timezone
  (`timestamptz` on Postgres). `LocalDateTime` only for a date-time that's naturally
  timezone-free
- A column whose type isn't Hibernate's default inference for the field's Java type
  carries `@JdbcTypeCode`: `String` infers `varchar` (so `char(n)` needs
  `SqlTypes.CHAR`), `Integer` infers `integer` (so `smallint` needs
  `SqlTypes.SMALLINT`). `ddl-auto: validate` compares type names and refuses to start
  on the mismatch
- `@Lob` never maps a Postgres `text` column: on a `String` it maps to `oid` (large
  object). A bare `String` field already maps to `text`
- `equals` and `hashCode` never over a database-generated id: while the entity is
  transient the id is `null` and the contract breaks in a `Set`
- Allowed Lombok annotations: `@.claude/rules/lombok.md`

### Identity and keys

- A technical primary key, with no business meaning. The business key gets its own
  `UNIQUE`
- `GenerationType.IDENTITY` turns off Hibernate's insert batching. Where volume
  justifies batching, use `SEQUENCE` with `allocationSize` equal to the sequence's
  increment
- UUID as primary key: time-ordered version (v7). UUIDv4 fragments the index
- **v7 comes from a library, never hand-written code.** On JDK 21 `UUID.randomUUID()`
  generates v4 and there's no v7 API; the form is
  `Generators.timeBasedEpochGenerator().generate()`
  (`com.fasterxml.uuid:java-uuid-generator`). Hand-implementing RFC 9562 is
  infrastructure code that needs auditing, and a bit-level bug doesn't break any test —
  the value is still a valid UUID, it just stops being sortable. From JDK 25 onward v7
  is in `java.util.UUID` itself and the library stops being necessary
- The id is generated where the aggregate is born — the application layer —, not by the
  database and not by the adapter. The aggregate never exists in a half-created state
  waiting for an id
- **An entity whose `@Id` is assigned (no `@GeneratedValue`) implements
  `Persistable<T>`**, with a `@Transient boolean isNew = true` flipped to `false` by
  `@PostLoad` and `@PostPersist`. The flag and both callbacks are written once per
  project, in one `@MappedSuperclass` every such entity extends — never copied into each
  entity, where they are the same 14 lines per table. It holds whether or not the entity
  has `@Version`: a primitive version counts as absent. Without it Spring Data sees a non-null id, calls
  `merge()` instead of `persist()`, pays a `SELECT` before every `INSERT`, and never
  raises the primary-key collision — code that relies on that collision for concurrency
  control is silently broken, with no compiler or test signal until the race happens
- Every foreign key has an index. The engine doesn't create one on its own on the
  referencing side, in Postgres or MySQL

### Migrations

- Every schema change is a versioned migration. No manual change, in any environment
- `spring.jpa.hibernate.ddl-auto: validate` in every environment. `update`, `create`,
  and `create-drop` are forbidden, including in development
- An applied migration is immutable. Fix it with a new migration; editing the old one
  breaks the checksum and blocks startup
- Name `V<N>__<verb>_<object>.sql`, all in snake_case
- A destructive change (`DROP COLUMN`, `DROP TABLE`, `NOT NULL` on an existing column)
  ships in a release after the one that stopped using it. Expand first, contract after
- On a table in production: `CREATE INDEX CONCURRENTLY` (Postgres), outside a
  transaction and in its own migration

### Queries

- N+1 is a bug, not tuning. A collection loaded inside a loop is fixed with
  `JOIN FETCH`, `@EntityGraph`, `@BatchSize`, or a dedicated query
- At most one collection per `JOIN FETCH`. Two multiply rows into a cartesian product
- Pagination and collection `JOIN FETCH` don't combine: the engine paginates in memory
  and reads the whole table. Use two queries or `@BatchSize`
- `LIMIT` without a deterministic `ORDER BY` returns an unstable result between runs
- A batch write query (`UPDATE`/`DELETE` in JPQL) bypasses the first-level cache: clear
  the persistence context afterward, or don't use it. It bypasses the audit history too
  (§ Auditing): on an audited entity it changes rows and leaves no revision, with no error

### Configuration

- `spring.jpa.open-in-view: false`. Spring Boot's default is `true` and keeps the
  session open until the response is serialized, which hides N+1 and holds connections
- Pool size fixed explicitly. An implicit default value in production is a configuration
  bug, not a decision
- Connection acquisition, validation, and query timeouts defined. Without them a stuck
  query holds the connection until the pool runs out
- `spring.jpa.show-sql` stays `false`. SQL is observed via the Hibernate logger, with a
  level configurable per environment

## Document (MongoDB)

### Mapping

- `@Document("<collection>")` names the collection on every document class. Without it the
  collection is named after the class, and renaming the class moves the data out of reach
- `@TypeAlias("<alias>")` on every document class. Without it `_class` stores the fully
  qualified class name, and moving the class to another package breaks every stored document
  that a polymorphic read needs
- **The aggregate is the document.** A child entity of the aggregate is embedded in the root's
  document; another aggregate is referenced by its id only. Zero `@DBRef` and
  `@DocumentReference` between aggregates: both load lazily through a proxy, one query per
  reference, and cascade nothing
- **An embedded array has a stated bound.** An array that grows with the aggregate's history
  (events, comments, line items without a business ceiling) reaches the 16 MiB document limit
  and fails every write after it. No bound the business can state → the items are their own
  collection, keyed by the root's id
- The persistence document has no business logic: fields, annotations, and access only
- `@Field` names a stored field whenever the Java name may change; a renamed Java field
  without it silently stops reading the old documents
- Enums are stored by name, never by ordinal
- Money in `BigDecimal` stored as `Decimal128`
  (`spring.data.mongodb.representation.big-decimal: decimal128`). A string compares and sorts
  lexically; a `double` is forbidden for monetary values
- Instants in `Instant`. `LocalDateTime` is converted through the JVM's zone on the way in and
  out, and two instances in two zones disagree on the stored value
- Allowed Lombok annotations: `@.claude/rules/lombok.md`

### Identity and versioning

- The id is generated where the aggregate is born — the application layer — as a UUIDv7
  (§ Relational · Identity and keys, the same library), stored as a standard UUID
  (`spring.mongodb.representation.uuid: standard`). Without the setting the driver refuses to
  encode a UUID at all
- An `ObjectId` is never the identity of an aggregate, and a `String` id is never filled with a
  24-hex value: Spring Data converts such a string to an `ObjectId` on write, and a lookup by
  the string finds nothing
- **`@Version` on every document**, a `Long` (wrapper, `null` while new). Without it, `save`
  is an upsert that replaces the whole stored document: a lost update on every concurrent
  write, a duplicate id overwriting the earlier aggregate with no error, and `isNew` false for
  an assigned id. With it, an insert of an existing id raises `DuplicateKeyException` and a
  stale update raises `OptimisticLockingFailureException`
- **An update loads, mutates and saves the stored document**, carrying its version — never a
  fresh document built from the domain object with a `null` version, which inserts a duplicate
  instead of updating
- `schemaVersion` on every document, an integer set by the adapter on write. A stored document
  older than the code is upcast on read in the mapper, and written back in the current shape on
  its next save

### Indexes and schema evolution

- **Every index is declared in code and ensured at startup**, idempotently, through
  `IndexOperations.createIndex` (the same name and options twice is a no-op) — never by `@Indexed` with
  `spring.data.mongodb.auto-index-creation` (off by default, and an unapplied `@Indexed(unique =
  true)` is a unique key that does not exist). A unique index that cannot be built, because
  duplicates already exist, fails the startup: that is the document family's
  `ddl-auto: validate`
- A collection that a transaction writes is created at startup, with its indexes — never
  implicitly inside the transaction
- A `$jsonSchema` validator with `validationAction: error` holds the fields every version of the
  document must carry; it is declared next to the indexes and applied the same way
- **An index is never changed in place.** A different key, option or uniqueness is a new index
  under a new name, ensured beside the old one; the old one is dropped in a release after the
  one that stopped using it. Expand first, contract after — the same rule as a destructive
  relational migration
- A TTL index has one field; on a compound index `expireAfterSeconds` is ignored. Expiry runs
  about once a minute: a TTL is retention, never a deadline the code relies on
- A shape change no upcast can absorb — a field a query filters or sorts on, a new unique key
  over old documents — is a backfill: a bounded pass over the collection, run as a job, never a
  loop in the startup

### Queries

- Every query that filters or sorts is served by a declared index. A derived `Containing`,
  `StartingWith` on a case-insensitive field, or `IgnoreCase`, compiles to a regular
  expression no index serves — a text search is not a repository method
- Pagination is keyset (`Window` / `ScrollPosition.keyset()`) over a sort that ends in `_id`.
  `skip` reads and discards every earlier document, and a `Page` counts the whole collection on
  every page; offset pagination only for a bounded admin listing, stated as such
- A projection reads only the fields it needs; the whole document is loaded only to change it

### Transactions

- A transaction needs a replica set, in every environment — local, test and production. A
  single-node replica set is a replica set; a standalone server is not
- The project declares one `MongoTransactionManager` bean. Spring Boot registers none, and
  without it `@Transactional` over a document write does nothing. With JPA in the same project,
  the JPA manager stays the primary one and the document manager is named and selected by name
  where a document write opens its transaction
- **The whole transaction is retried where it opens** on `TransientDataAccessException` — a
  transient transaction error or an unknown commit result — a bounded number of times, then
  translated per § Translation. Nothing inside the transaction may have a side effect outside
  it, since it can run more than once
- A transaction lives at most 60 seconds on the server: it covers one aggregate's write and the
  outbox row beside it, never a batch
- Write concern `majority`, never `unacknowledged`: an unacknowledged write swallows the
  duplicate key and the optimistic-lock failure this file relies on

### Configuration

- Connection in `spring.mongodb.*` (`uri`, `database`, `representation.uuid`, `ssl.bundle`).
  `spring.data.mongodb.uri` is not read by Spring Boot 4, and an application that sets only it
  connects to `localhost/test` without an error. `spring.data.mongodb.*` keeps only the mapping
  settings (`auto-index-creation`, `representation.big-decimal`)
- The client is adjusted through a `MongoClientSettingsBuilderCustomizer`, never a
  `MongoClientSettings` or `MongoCustomConversions` bean of the project's own: either one
  replaces Spring Boot's and silently drops the URI, the UUID representation and the SSL bundle
- Server selection, connect and socket timeouts set explicitly, and the pool size fixed. The
  driver's defaults are 30 seconds, 10 seconds and **never**: a stuck query holds its
  connection forever

## Auditing

What an aggregate keeps about its own changes is a fact of the use case, per aggregate: `none`,
`stamps` (when and by whom the stored row was created and last changed) or `history` (every
earlier version readable, with its actor and instant — the stamps included). Nothing in this
file chooses the level.

### Common

- **Audit data lives on the persistence entity or document, never on the aggregate.** A
  business rule that reads who or when created gets the value mapped into the aggregate on
  load; the aggregate never writes a stamp
- The four stamps (`createdAt`, `createdBy`, `lastModifiedAt`, `lastModifiedBy`) are one
  embeddable type per project, embedded in each audited entity or document — never fields on
  the shared assigned-id superclass, and never a second superclass. The two `created` fields are
  not updatable, none has a public setter, instants are `Instant` in UTC from the application's
  clock
- **The actor is never `null` and never guessed.** It is the authenticated principal's name; an
  anonymous caller is recorded as `anonymous`; a pass with no caller — a scheduled job, a message
  consumer — runs under a named system actor, `system:<name>`, set where the pass starts. A
  write with no authentication at all fails: a missing actor is a lost security context, not a
  default
- Auditing is enabled in its own configuration class, never on the application class: a test
  slice without the store loads the application class and fails on the missing store
- The shared technical tables — `outbox_events`, `idempotency_keys`, the scheduling tables — are
  never audited

### Relational — Envers

- Every entity of an audited aggregate is audited with it, children included. A relation to a
  non-audited entity is marked as such, and a historic read of it returns the target's
  **current** state
- A collection of an audited aggregate is a `Set` or carries an order column. A `List` without
  one is a bag: a duplicate element is written once in the history, with no error
- A field holding personal data stays out of the history (`@NotAudited`) unless the design
  records why the history needs it and for how long (`@.claude/rules/personal-data.md` § At rest)
- **The revision table and every `_aud` table come from a versioned migration** (§ Migrations),
  never from schema generation; `ddl-auto: validate` refuses to start on a missing table or
  column. Every non-key column of an `_aud` table is nullable: a delete revision keeps only the
  id, and a `NOT NULL` there passes validation and fails the first delete
- One revision entity per project: a `long` revision number, a `long` timestamp — the validity
  strategy below casts it to `Long` and fails every update's commit on an `Instant` — and the
  actor. Its sequence's increment equals the mapping's allocation size
- The audit strategy is the validity strategy with the revision-end timestamp stored, for the
  whole persistence unit. Each `_aud` table indexes its revision-end and revision-end timestamp
  columns
- The history is written at commit, in the same transaction: a rolled-back write leaves none,
  and a test that never commits sees none
- A bulk `UPDATE`/`DELETE` (JPQL, Criteria, native) on an audited entity leaves no revision. The
  write goes through the managed entity, or the gap is recorded where it is designed
- Retention is part of the design (`@.claude/rules/personal-data.md` § At rest): "forever" with
  its reason, or a window enforced by a job that deletes the versions whose revision-end
  timestamp is older than the window — never the current version, whose revision end is empty
  — and then the revisions no `_aud` row references

### Document — MongoDB

- `stamps` only, filled by the store's auditing on the document's `@Version` (§ Document ·
  Identity and versioning). A document store keeps no history of its own here; `history` on a
  document aggregate is not designed

## Admitted exception

The pure read query described in `@.claude/rules/architecture-ddd.md` may return a
projection directly from the adapter, without going through the domain, as long as it's
recorded in an ADR.

## How to verify

```bash
# No persistence type inside the domain. Zero lines is the expected result.
grep -rnE "jakarta.persistence|org.springframework.data.mongodb|com.mongodb|org.bson" --include=*.java . | grep -v "/persistence/"

# ddl-auto must be validate and open-in-view must be false, in every profile.
grep -rn "ddl-auto\|open-in-view" src/main/resources/

# On Postgres, @Lob on a String field is a `text` mapping bug. Review every hit.
grep -rn "@Lob" --include=*.java src/main/java

# Every MongoDB document names its collection, its alias and its version. Each file listed lacks one.
grep -rL "@Document(\"" $(grep -rl "data.mongodb.core.mapping.Document" --include=*.java src/main/java)
grep -rL "@TypeAlias\|@Version" $(grep -rl "data.mongodb.core.mapping.Document" --include=*.java src/main/java)

# The Spring Boot 4 connection prefix. Zero lines is the expected result.
grep -rn "spring.data.mongodb.uri" src/main/resources/

# Audit types stay in the persistence adapter. Zero lines is the expected result.
grep -rnE "org.hibernate.envers|org.springframework.data.history|org.springframework.data.annotation.(Created|LastModified)" --include=*.java src/main/java | grep -v "/persistence/"

# A bulk write on an audited entity leaves no history. Review every hit against the audited entities.
grep -rn "@Modifying\|CriteriaUpdate\|CriteriaDelete" --include=*.java src/main/java
grep -rln "@Audited" --include=*.java src/main/java

# Auditing enabled on the application class breaks every slice without the store. Zero lines is the expected result.
grep -lE "@Enable(Jpa|Mongo)Auditing" $(grep -rl "@SpringBootApplication" --include=*.java src/main/java)

# Assigned ids without Persistable, and @Lob on String, are also architecture-test rules.

# The rest — boundaries, N+1, indexes that exist — is the architecture test and the integration tests.
./mvnw -q test
```
