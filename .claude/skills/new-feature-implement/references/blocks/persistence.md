# Block 2: Persistence (steps 8-12, conditional)

§ 3 of spec.md reads `none — <reason>` → skip Block 2; the checklist has no steps 8-12.
Read § 3 of spec.md, then the partial it cites for the step at hand. Generates:
- `[Entity]JpaEntity.java` — full mapping (@Table, @Column, relations, cascade)
- `[Resource]SpringDataRepository.java` — Spring Data interface
- `[Resource]RepositoryAdapter.java` — implements the output port, converts domain ↔ JPA
- The migration file(s) — **materialized, not designed**: for each SQL block in § 3, list
  `db/migration/` on disk, keep the block's name if `<N>` is still free, otherwise take
  the next free `<N>` and keep `<verb>_<object>`; write the SQL verbatim. Name per
  `.claude/rules/persistence.md` § Migrations.
- `application-[feature].yml` — datasource, JPA properties (hibernate.dialect, batch_size, etc)

Rules: `.claude/rules/naming.md`, `.claude/rules/code-quality.md`, `.claude/rules/lombok.md`,
`.claude/rules/architecture-ddd.md` § Adapters (translate, no business logic), and
`.claude/rules/logging.md`: `[Resource]RepositoryAdapter` logs query identifier + duration
only past the slow-query threshold `persistence.md` sets — silent otherwise.

Validation: identity maps, relations have cascade, no N+1, migration is idempotent, and the
adapter uses `saveAndFlush` wherever it translates a constraint violation — with `save` the
INSERT only reaches the database on commit, outside the `try`, and the violation escapes
translation (`.claude/rules/persistence.md` § Boundary). Every entity whose `@Id` has no
`@GeneratedValue` extends the project's shared `AssignedIdEntity` — created once, from the
last block of `.claude/skills/persistence-architect/templates/JpaEntity.java.example`, when
the first such entity appears — and never implements `Persistable` itself (§ Identity and
keys); every column whose SQL
type isn't Hibernate's default inference carries `@JdbcTypeCode`, and no `@Lob` maps a
`text` column (§ Mapping) — `ddl-auto: validate` only reports these at context startup.
Every class of the aggregate goes in its own subpackage of the persistence adapter and
stays package-private (§ Boundary); the test tree mirrors it. A cross-aggregate fixture
depends on the other aggregate's outbound port — widening a class to `public` to make a
test compile is the wrong fix.

**Document family** — an aggregate whose `Storage` is `document (MongoDB)`, read from § 3 and
`20-persistence.md`. The exemplars are `.claude/skills/persistence-architect/templates/Mongo*.java.example`,
`DocumentTransactions.java.example` and `application-mongodb.yml.example`, in place of the JPA ones:
- `[Aggregate]Document.java` with `@Document("<collection>")`, `@TypeAlias`, `@Version Long` and
  `schemaVersion`; the embedded children as records; `[Aggregate]MongoRepository`; the adapter with
  the two write paths (`create` inserts, `save` loads-mutates-saves) and the mapper with its upcast
- `[Aggregate]Collection` — the schema declaration of § 4, verbatim: named indexes and the
  validator. Once per project, from the exemplars: `MongoCollectionSchema`, `MongoSchemaInitializer`
  (public), `MongoClientConfig`, the transaction manager — `MongoTransactionConfig`, or
  `TransactionManagersConfig` when the project also has JPA, never both — and, in the application
  layer, `DocumentTransactions`, which every use case writing a document opens its transaction
  through, instead of `@Transactional`
- No migration file, no `ddl-auto`. Properties of § 5 (`spring.mongodb.*`, the UUID and
  `Decimal128` representations, `app.mongodb.*`)

Validation, document family: no `@DBRef`/`@DocumentReference` across aggregates, no derived
`Containing`/`IgnoreCase`, no `Page` on a listing (keyset `Window`), no `MongoClientSettings` or
`MongoCustomConversions` bean, no `spring.data.mongodb.uri`, the `outbox_events` and
`idempotency_keys` of this family from `MongoSharedCollections.java.example`.

**Audit** — when `20-persistence.md` § Audit is not `none`. The exemplars are
`.claude/skills/persistence-architect/templates/Audit*.java.example`,
`SystemActor.java.example`, `V1__create_audit_tables.sql.example` and
`application-audit.yml.example`:
- every entity § Audit lists gets `@EntityListeners(AuditingEntityListener.class)` and an embedded
  `AuditStamps`; for `history`, `@Audited` too, and `@NotAudited` exactly on the fields § Audit
  keeps out. Once per project, when step 2 of the partial found them missing: `AuditStamps`,
  `AuditingConfig` with `AuditorResolver` (never on the application class), and for `history`
  `AuditRevision` with its listener; `SystemActor` in the package `packages.map` names
  `commons.audit`
- the `_aud` tables and, the first time, `revinfo` and its sequence — materialized from § 4 like
  any migration; the Envers properties of § 5
- the history adapter over `RevisionRepository` when § Audit names a read port, and the
  `AuditHistoryRetentionGateway` implementation with this case's `_aud` tables added to its list
- § 6's `spring-data-envers` and, in a project without Spring Security, `spring-security-core` —
  never the security starter

Validation, audit: no stamp on `AssignedIdEntity`, no `List` child without `@OrderColumn` in an
audited aggregate, no `NOT NULL` on a non-key `_aud` column, a `long` revision timestamp, no
`@Modifying` query over an audited entity that § Audit does not list, `@EnableJpaAuditing` absent
from the `@SpringBootApplication` class (`.claude/rules/persistence.md` § Auditing).

Compilation: `./mvnw -q -pl <persistence-module> test-compile` in multi-module, `./mvnw -q test-compile` in single-module ✅

**Line in the final report:**
```
✅ Block 2: Persistence complete (steps 8-12)
- Entity: OrderJpaEntity with @ManyToOne + @OneToMany
- Repository: Spring Data + converting adapter
- Migration: V3__create_orders (order, order_items, constraints)
- YAML: hibernate.dialect, batch_size=20, fetch_size=50
- Files: 5 classes + 1 .sql, 0 errors
```
