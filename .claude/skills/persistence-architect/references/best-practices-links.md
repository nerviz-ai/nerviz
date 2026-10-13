# External sources — persistence, JPA, and SQL

Reference for the `persistence-architect` skill. Each row has the source and what to go
fetch there. Official documentation first; where it diverges from any summary, the
official one wins.

**Freshness warning.** This list was assembled on 2026-09-07. URLs change and versions
ship. If a link breaks or the cited version is stale, resolve the project's actual
version (`./mvnw dependency:tree`) before applying what's written there — never write
versions from memory (`@CLAUDE.md`, invariant 8).

## Official

| Source | Go fetch |
|---|---|
| <https://docs.spring.io/spring-boot/reference/data/sql.html> | Datasource configuration, pool, schema initialization |
| <https://docs.spring.io/spring-data-jpa/reference/> | Query name derivation, `@EntityGraph`, `Pageable`, projections |
| <https://docs.spring.io/spring-framework/reference/data-access/transaction.html> | `@Transactional` semantics, propagation, isolation |
| <https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html> | Batch, fetch, cache, identifier generation |
| <https://jakarta.ee/specifications/persistence/> | The JPA specification. Ultimate source for what's portable across engines |
| <https://documentation.red-gate.com/fd/flyway-documentation-138346877.html> | File names, checksums, `out-of-order`, transactions per migration |
| <https://www.postgresql.org/docs/current/using-explain.html> | How to read `EXPLAIN`; meaning of each node |
| <https://www.postgresql.org/docs/current/indexes.html> | Index types, composite indexes, functional and partial indexes |
| <https://java.testcontainers.org/modules/databases/> | Testing against the real engine instead of H2 |
| <https://raw.githubusercontent.com/hibernate/hibernate-orm/7.1/documentation/src/main/asciidoc/userguide/chapters/envers/Envers.adoc> | Envers (read 2026-10-13): `@Audited`, `@NotAudited`, the revision entity, every `org.hibernate.envers.*` property and default, the validity strategy, partitioning by revision end, bulk writes and bags unsupported |
| <https://docs.hibernate.org/orm/7.4/javadocs/org/hibernate/annotations/Audited.html> | Hibernate's own `@Audited` (read 2026-10-13): incubating since 7.4, out of scope here until it is not |
| <https://docs.spring.io/spring-data/jpa/reference/auditing.html> | `@CreatedBy`/`@LastModifiedDate` and friends, `@EnableJpaAuditing`, `AuditingEntityListener` (read 2026-10-13) |
| <https://docs.spring.io/spring-data/commons/reference/auditing.html> | `AuditorAware`, `DateTimeProvider`, auditing metadata in an embedded type (read 2026-10-13) |
| <https://docs.spring.io/spring-data/jpa/reference/envers.html> | `@EnableEnversRepositories`, `RevisionRepository`, `Revision` and its metadata (read 2026-10-13) |
| <https://docs.spring.io/spring-data/mongodb/reference/mongodb/auditing.html> | `@EnableMongoAuditing` (read 2026-10-13) |
| <https://hibernate.atlassian.net/browse/HHH-3554> · <https://hibernate.atlassian.net/browse/HHH-10318> | Why a bulk or Criteria write leaves no revision (read 2026-10-13) |

## Secondary reference

| Source | Go fetch | Caveat |
|---|---|---|
| <https://vladmihalcea.com/tutorials/hibernate/> | N+1 catalog, batch, id strategies, mapping cases | Single author; confirm against the official docs before fixing it as a rule |
| <https://use-the-index-luke.com/> | How a B-tree index is actually used; column order | Engine-agnostic; specific syntax comes from the engine's docs |
| <https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing> | Why a bigger pool usually worsens latency | Heuristic, not formula |
| <https://martinfowler.com/bliki/ParallelChange.html> | Expand/contract — the pattern behind three-step migrations | Conceptual |
| <https://vladmihalcea.com/the-best-way-to-implement-an-audit-log-using-hibernate-envers/> | Envers with the validity strategy and migrations for the audit tables (read 2026-10-13) | Single author; the rules here were proved against a real engine instead |

## What these sources don't decide

Names, aggregate boundaries, and what counts as a value object aren't resolved here:
they're `@.claude/rules/naming.md`, `@.claude/rules/architecture-ddd.md`, and
`@.claude/rules/value-objects.md`. An article that contradicts a rule of this repository
doesn't win by being external — if the rule is wrong, the rule gets changed, with a
record.
