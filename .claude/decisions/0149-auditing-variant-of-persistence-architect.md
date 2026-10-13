# 0149 · Auditing enters `persistence-architect` as a variant: stamps or an Envers history per aggregate, read from an `Audit` row of the use case

- **Date:** 2026-10-13
- **Scenario:** "Dar ao `persistence-architect` a capacidade de desenhar AUDITORIA de um agregado —
  quem mudou, quando, e (quando o caso pedir) o valor anterior. É extensão de skill existente, não
  skill nova." The audit level is a datum of the use case, read by `persistence-architect` and never
  weighed by it — the shape `0147` gave the storage family. `0147` § 14 had listed "Envers and
  auditing" out of scope; this record takes it in.
- **Decision:** Option 1 — auditing as a variant of `persistence-architect` (Form 1, the existing
  skill: step 4c, an `## Audit` partial block, seven new templates, `references/pitfalls-audit.md`)
  + `.claude/rules/persistence.md` § Auditing (Form 4, the existing norm) + the `Audit` row of
  `00-caso-de-uso.md` + `org.hibernate..` and the Envers and Spring Data history prefixes forbidden
  in the inner layers of the blueprints that declare `forbidden_imports` + a `commons.audit` key in
  every blueprint's `packages.map` + the `audit-adapter-boundary` migration.
- **State:** approved by Lucas Fernandes, on 2026-10-13 — option 1 with every planned decision of its
  table; implemented on the branch `feat/persistence-auditing`, release bump `minor`

## What the context showed

| Concern | Owner today |
|---|---|
| Who changed an aggregate, when, and its earlier values | nobody — `rules/persistence.md` has no auditing section; `0147` § 14 put Envers and auditing out of scope |
| Where an audit column could live | `persistence-architect/templates/JpaEntity.java.example` — `AssignedIdEntity`'s Javadoc forbids it there: "an id, a version or an audit column here would couple every table to one shape" |
| The caller's identity crossing into the use case | `security-architect/templates/CurrentActorResolution.java.example` — an explicit `Actor` in the command; `SecurityContextHolder` rejected in the use case because it is "silently anonymous when a job or a listener calls it". The principal name is `sub` (`security-spec.md.example`) |
| Bulk writes | `rules/persistence.md` § Relational › Queries: "A batch write query (`UPDATE`/`DELETE` in JPQL) bypasses the first-level cache" — and, unstated, Envers too |
| Bulk deletes that exist today | the outbox prune (`OutboxRetentionGateway.deletePublishedBefore`) and the idempotency prune — both shared tables, never aggregates |
| Prohibited imports | the eight `blueprints/*/*.yaml` `forbidden_imports`. `domain` forbids `org.springframework..` but no blueprint forbids `org.hibernate..` anywhere |
| A prune job requested by persistence | `jobs-architect` entry table already accepts `20-persistencia.md` (document backfill) |
| Personal data copied into another store | `rules/personal-data.md` § At rest — named where designed, retention part of the design |

No `/new-feature` run has shown an auditing defect. What the model gets wrong without the
extension comes from the research behind the scenario — the Hibernate 7.1 user guide's Envers
chapter, the Hibernate 7.4 `@Audited` Javadoc and Introduction § 9.2–9.3, Spring Data JPA 4.0
auditing and Envers, Spring Data Commons and MongoDB auditing, HHH-3554 and HHH-10318. Versions on
the day, from start.spring.io and Maven Central: Initializr default Spring Boot 4.1.1 (Hibernate
7.4.5.Final), Boot 4.0.8 (Hibernate 7.2.24.Final); `hibernate-envers` managed by the BOM. None of
them is written into a norm or a template (invariant 8).

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | None observed. Anticipation, overridden by the maintainer as in `0144`–`0147` | Create nothing (as the whole answer) |
| 2 — trigger | Model decision inside `/new-feature`, read from `00-caso-de-uso.md`'s `Audit` row | Form 2 |
| 5 — nature | Procedure (design steps, partial blocks) plus declarative norms | Forms 4 and 5 as the whole answer |
| 6 — isolation | None — the interview is the task | Form 3 |
| 7 — mandatoriness | Persuasion, plus the guarantee already in place: blueprint `forbidden_imports` read by `check`/`doctor gate` and ArchUnit. No new hook: no failure observed (invariant 6). The silent bulk write gets a `grep` in § How to verify and a test, not a hook | New Forms 7 and 8 |
| 8 — destination | Both — the skill, its templates and the norm travel | — |
| — the datum | `Audit` row in `00-caso-de-uso.md`, one entry per aggregate the case writes: `none` · `stamps` · `history`, with who chose. **Recorded only when the request asks for it** (who changed, when, earlier values); default `none`, never asked otherwise. When the request asks for auditing without saying whether earlier values must be readable, one factual question: `stamps` or `history`. A REUSE aggregate's level is read from the code (`@Audited`, the stamps embeddable). A missing row in an older case reads as `none`, never a stop | Asked for every new aggregate (rejected by the maintainer: one more question per case) |
| — levels | Cumulative: `history` = `stamps` on the live row + `_AUD` rows + the actor on the revision | `history` without stamps |
| — engine | Envers, always. Hibernate 7.4's native `@Audited` is `@Incubating`, exists only on Boot ≥ 4.1, and its limitations are undocumented; its log is Envers-compatible (`REVINFO`, `REV`, `REVTYPE`), so a later switch keeps the history — a new decision, not a propagation | Native when Hibernate ≥ 7.4; native always |
| — reading history | Spring Data `RevisionRepository<E, ID, Long>` on the aggregate's Spring Data repository, inside the adapter, mapped to a read projection. Accepted cost: `@EnableEnversRepositories` sets the repository factory of every JPA repository it scans, and the custom revision's actor is read through `Revision.getMetadata().getDelegate()` | `AuditReader` in the adapter |
| — the actor | Strict thread-local: one resolver in the persistence adapter reads the security context, for both `AuditorAware` and the `RevisionListener` (which receives no argument). A pass with no caller — a job, a consumer — runs under a named `system:<name>` authentication set by its inbound adapter. No authentication at all → the write fails; `null` is never written. The anonymous caller of a public endpoint is recorded as such | A `system` fallback (hides a lost context); the actor through every port signature (the `RevisionListener` would still need a thread-local) |
| — stamps placement | One `@Embeddable AuditStamps` per project (`createdAt`, `createdBy`, `lastModifiedAt`, `lastModifiedBy`; `Instant` UTC; `updatable = false` on the two created ones; no public setter) embedded in each audited entity. Never on `AssignedIdEntity` | Fields per entity; a `@MappedSuperclass` (single inheritance collides with `AssignedIdEntity`) |
| — stamps vs domain | Adapter metadata by default. A business rule that reads who or when created → the adapter maps the stamp into the aggregate on load; the aggregate never writes one | — |
| — Envers strategy | `ValidityAuditStrategy` with `audit_strategy_validity_store_revend_timestamp=true`, global per persistence unit, fixed by the project's first `history` case. Range reads, a safe prune by age (only versions already ended), the only partition base the guide accepts | `DefaultAuditStrategy` |
| — document family | `stamps` only (`@EnableMongoAuditing`). `history` on a `document (MongoDB)` aggregate → `persistence-architect` stops and sends the case back to `use-case-design` with the reason | A `<agg>_history` collection written by the adapter |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Auditing as a variant of `persistence-architect` (Form 1, existing) + § Auditing in `rules/persistence.md` (Form 4, existing) + `Audit` row + blueprint prefixes and a migration | 7 | **Approved** |
| 2 | Same, with the norm in its own `rules/auditing.md` | 5 | Rejected — identical `paths`, the two would always load together |
| 3 | New skill `audit-architect`, partial `24-auditoria.md` | 4 | Rejected — two owners of one schema and one migration |
| 4 | Create nothing | 3 | Rejected — the maintainer chose to anticipate |

### Option 1 — variant of `persistence-architect` + § Auditing (score 7)

**Motivator:** the auditing artifact is the aggregate's own tables and migration — same moment and
artifact as `20-persistencia.md`, the split `0005` and `0147` made. The datum axis puts the level in
the use case, read and never weighed.

**Pros:** no new skill, no new pipeline step, no new partial; the level is one datum per aggregate
a planned solution-design step can write later; the import boundary is data the existing guarantee
reads; same shape as `0147`.

**Cons:** wide propagation — `use-case-design`, `domain-modeling` (history read port),
`persistence-architect` (variant, six templates, a pitfalls reference), `jobs-architect` (history
prune as an entry source, the system actor on a job pass), `messaging-architect` (the system actor
on a consumer), `test-architect`, the executor, `new-feature` (short-path criterion, spec block),
eight blueprints, `extensions.json` (a migration), CI tests, routing and docs. Anticipation, not a
symptom. `persistence-architect` grows past ~550 lines.

**Points cut in the rubric:** 1 (form fit — no signal, overridden) · 5 (maintenance: wide
propagation) · 3 (half a point — `persistence.md` grows, and it loads on every persistence file).

**CI:** `validate` › `design` › `frontmatter schema` already covers the skill and the rule.
New: (a) audit cases in their own `.claude/.ci/AuditTemplateTest.java` (planned inside `PersistenceTemplateTest`; written apart at Phase 4, as `SearchTemplateTest` was, since that test is the document family's) — an audited exemplar with a `List` child and
no `@OrderColumn`/`@CollectionId`, a revision number that is not `long`, `AuditStamps` without
`updatable = false` on the created pair or with a public setter, `@EnableJpaAuditing` on a
`@SpringBootApplication`, the yml without the validity strategy or the revend timestamp, the
migration without `revinfo`, an `_aud` table, `revend` or its index, an `@Audited` outbox or
idempotency entity, a MongoDB history exemplar; (b) `JavaTemplatesTest` in `templates.yml` compiles
the new exemplars with `hibernate-envers`; (c) `exemplar-imports` gains the dependency and the
`org.hibernate.envers` prefix; (d) `AgentInstructionsTest` fixture and `MigrationsSchemaTest` for the
new prefixes and the `audit-adapter-boundary` entry.

**Planned decisions inside the option:**

| # | Question | Answer |
|---|---|---|
| 1 | The datum | As the interview's datum row. The template's `Audit` row sits beside `Storage`; `use-case-design` gains the recording rule, the factual question, a checklist row (a question offering auditing unasked → don't ask) |
| 2 | `persistence-architect` | Family rule extended: the `Audit` row read, never chosen. Step 1 reads it; `history` on a document aggregate stops. Step 2 surveys `@Audited`, `AuditStamps`, `revinfo`. Step 3 asks the history's retention (default first, real alternatives; "forever" with its reason) and which personal fields enter it. New step 4c designs the audited entities, `@NotAudited` fields with their reason, the `_aud` tables, the revision table and the migration order. Step 7 adds the Envers properties. New partial block `Audit`. Contract and Boundary updated |
| 3 | Norm | `rules/persistence.md` § Auditing — common (the level comes from the case; stamps on the persistence entity, never the aggregate; never null actor; system actor for jobs and consumers), Relational (Envers: every child entity of an audited aggregate audited with it; no bag; revision number `long`; `revinfo` and `_aud` by migration, `ddl-auto: validate` the net; validity strategy; `@NotAudited` on personal fields without a recorded reason; outbox, idempotency and scheduling tables never audited), Document (stamps only). § Relational › Queries' bulk line extended: a bulk write on an audited entity leaves no history. § How to verify gains the greps. No skill named (invariant 1). Stays in `persistence.md` while under ~60 lines; past that, option 2 |
| 4 | Templates | `AuditedEntity.java.example` (entity + `AuditStamps`), `AuditingConfig.java.example` (`@EnableJpaAuditing` and `@EnableEnversRepositories` in their own `@Configuration`, the `AuditorAware` and a `DateTimeProvider` on the clock), `AuditRevision.java.example` (`@RevisionEntity`, `long` number, actor, listener), `V1__create_audit_tables.sql.example`, `application-audit.yml.example`, `AuditHistoryAdapter.java.example` (port served by `RevisionRepository`), `SystemActor.java.example` (the system authentication around a pass); spec templates gain the `Audit` block |
| 5 | References | `references/pitfalls-audit.md` (shape of `pitfalls-mongodb.md`); `best-practices-links.md` rows, official and secondary apart, dated |
| 6 | History read port | `domain-modeling`: a case that reads history declares an output port of kind `persistence` returning a read projection (`<Aggregate>Change`: revision, instant, actor, type, the fields the case shows), never the aggregate rehydrated |
| 7 | System actor location | A `commons.audit` role in every blueprint's `packages.map` — `commons.audit` (hexagonal, onion, both clean architectures, layered), `shared.audit` (modular-monolith), `config.audit` (vertical-slice), `app.audit` (custom template) — beside `commons.logging` where the blueprint has it. A convention is blueprint data (invariant 7), and adapters never import each other |
| 8 | Prune | The exact mirror of the outbox prune. `jobs-architect` runs before persistence, so it reads the `Audit` row itself: a `history` with no history prune job in the project → `AuditHistoryPruneJob`, once per project, with `AuditHistoryRetentionGateway.deleteEndedBefore(cutoff, limit)` handed to persistence in § 6. `persistence-architect` asks the window (`app.audit.history.retain-for`), writes the statement — per audited table, versions whose `revend_tstmp` is older than the cutoff and delete rows older than it, then the unreferenced revisions — and turns the job's switch off with the reason when the answer is "forever". Never through a `Deferred` row. The document-backfill route was not copied: it lists `20-persistencia.md` as a source of a skill that runs before it |
| 9 | Boundary data | `domain`: `org.hibernate..`. `application`: `org.hibernate.envers..`, `org.hibernate.annotations..`, `org.springframework.data.history..`, `org.springframework.data.envers..`, `org.springframework.data.annotation..` — in all eight blueprints, plus an `audit-adapter-boundary` migration |
| 10 | Short path | New criterion: an `Audit` row other than `none` on an aggregate whose level is new or changes — unmet |
| 11 | Delete rows | `store_data_at_delete=false` (the default): the previous row holds the state, the revision holds the actor; no second copy of personal data |
| 12 | Unverified, proved before a norm states it | All proved in a real Initializr project — see § Found while writing |
| 14 | An HTTP write with no security chain | A project without Spring Security has no authentication on any request, so the strict resolver fails every audited write reached by HTTP. `persistence-architect` stops on that combination and sends the case back; a job- or consumer-only project declares `spring-security-core` alone |
| 13 | Out of scope, with the reason | Hibernate's native `@Audited` (incubating) and `@Temporal` (business validity, not audit); CDC/Debezium and triggers (outside the application); event sourcing (another model); conditional auditing (custom listeners); a read-access log — who viewed a record — another problem; `history` on MongoDB |

### Option 2 — the norm in its own `rules/auditing.md` (score 5)

The same, with the norm in a separate file. Its `paths` would equal `persistence.md`'s — same
package — so the two always load together; `0147` rejected a file per technology for that reason.
Cut: 3 (two files loaded where one would do), 5, 1.

### Option 3 — a new `audit-architect` skill (score 4)

Its own step and partial. The artifact is the aggregate's tables and migration, designed in the same
pass; a second skill would write into `20-persistencia.md`'s territory or duplicate its migration
block, and `0005` named the persistence skill without technology so variants need no new skill.
Strains invariant 2 (two owners of one schema). Cut: 1, 2, 5, 6.

### Option 4 — create nothing (score 3)

What the matrix recommends with no symptom. A case asking "who changed this" would be designed with
ad hoc columns, or with Envers and every pitfall above.

## References

| Claim | Source |
|---|---|
| The datum is read, never weighed | `decisions/0147-document-storage-and-search-architect.md` § Planned decisions 1, 2 |
| Division by moment and artifact | `skills/persistence-architect/SKILL.md` § Boundary with neighboring skills · `decisions/0005-persistence-rule-and-design.md` |
| A file per technology with identical `paths` was rejected | `0147` interview, "the norm" |
| No audit column on the shared superclass | `skills/persistence-architect/templates/JpaEntity.java.example` (`AssignedIdEntity` Javadoc) |
| A thread-local actor is silently anonymous in jobs and listeners | `skills/security-architect/templates/CurrentActorResolution.java.example` |
| Prohibited imports are blueprint data with a migration | `blueprints/*/*.yaml` · `extensions.json` `migrations` `nosql-adapter-boundary` |
| No hook without an observed failure | `@CLAUDE.md` invariant 6 · decision matrix anti-pattern 16 |
| Versions resolved at runtime | `@CLAUDE.md` invariant 8 |
| Envers: bulk and Criteria writes unaudited, bags unsupported, `long` revision, validity strategy, partitioning on revend | Hibernate 7.1 user guide, Envers chapter · HHH-3554 · HHH-10318 |
| Native `@Audited` is incubating, since 7.4, Envers-compatible log | Hibernate 7.4 `org.hibernate.annotations.Audited` Javadoc · Introduction 7.4 § 9.3 |
| `RevisionRepository`, `@EnableEnversRepositories` | Spring Data JPA 4.0 reference, Envers configuration and usage |
| `AuditorAware` type matches the field; embedded audit metadata | Spring Data Commons reference, Auditing |

## Propagation

| File | Change |
|---|---|
| `.claude/rules/persistence.md` | New § Auditing — Common, Relational (Envers), Document (MongoDB); the bulk line of § Relational · Queries extended; the boundary list names Envers and Spring Data history; three greps in § How to verify |
| `.claude/rules/00-index.md` | `persistence.md` row |
| `.claude/skills/persistence-architect/SKILL.md` | Description; the audit rule (read, never chosen; the two stops); step 1 extracts the level; step 2 surveys audit pieces and the security chain; step 3 asks the history's retention and its personal fields; new step 4c; step 7 the Envers properties; step 8 the `## Audit` block and the two declared dependencies; partial table row; Contract and Boundary |
| `.claude/skills/persistence-architect/templates/` | New: `AuditedEntity`, `AuditingConfig`, `AuditRevision`, `AuditHistoryAdapter`, `SystemActor` (`.java.example`), `V1__create_audit_tables.sql.example`, `application-audit.yml.example`; both spec templates gain the `Audit level` row and the `## Audit` block |
| `.claude/skills/persistence-architect/references/` | New `pitfalls-audit.md` (32 pitfalls, 15 observed); `best-practices-links.md` gains eight official rows and one secondary, dated |
| `.claude/skills/use-case-design/SKILL.md` + `templates/use-case-spec.md.example` + 13 `examples/*/00-caso-de-uso.md` | The `Audit` row, recorded only from the request; the factual question on the level; a checklist row; `Audit | none` in every example |
| `.claude/skills/domain-modeling/SKILL.md` | Step 4g — the history read port, kind `persistence`, a sealed read projection |
| `.claude/skills/jobs-architect/SKILL.md` | Description; entry row for a `history`; the history prune's shape; a job writing an audited aggregate runs under `SystemActor`; § 6 hands the prune's operation |
| `.claude/skills/messaging-architect/SKILL.md` | A consumer calling an audited write runs under `SystemActor` |
| `.claude/skills/test-architect/SKILL.md` + `templates/AuditHistoryIT.java.example` | The audited aggregate's cases, against the real engine and committing; the exemplar list |
| `.claude/skills/new-feature/SKILL.md` + `templates/feature-spec.md.example` | Step 4b also runs for a first `history`; short-path criterion 9; a precedence row for the audit level; the `Audit level` row of the spec |
| `.claude/agents/java-spring-boot-developer.md` | Block 2 audit generation and validation; Block M and Block J wrap an audited write in `SystemActor`; `AuditHistoryPruneJob` |
| `.claude/blueprints/*/*.yaml` (8) | `org.hibernate..` in `domain`/`core` (hexagonal, onion, clean-architecture-multi-module, custom template); five Envers and Spring Data prefixes in `application` (hexagonal, clean-architecture-multi-module); the `commons.audit` key in all eight |
| `.claude/schemas/extensions.json` | The `audit-adapter-boundary` migration. No new skill, rule or class: `export`, `derived_paths` and `skill_classes` unchanged |
| `.claude/.ci/AuditTemplateTest.java` | New, `validate` › `design` |
| `.claude/.ci/JavaTemplatesTest.java`, `AgentInstructionsTest.java` | Four audit exemplars compiled with `spring-data-envers` and `spring-security-core`; the fixture's six new prefixes |
| `.github/workflows/validate.yml`, `templates.yml` | The design step; `spring-data-envers` in `exemplar-imports`' reference project; four template paths in both trigger lists |
| `CLAUDE.md`, `project-bootstrap/templates/root.CLAUDE.md.example` | Persistence routing row names auditing |
| `README.md`, `docs/{en,pt-br}/03-new-feature.md`, `05-blueprints.md`, `07-ci-validate.md`, `11-pitfalls.md` | The persistence step, the `commons.audit` role, the CI rows and diagram node, the silent bulk write — written in this thread, no delegation: eleven lines |

Goes to the generated project: **yes** — the templates, references and SKILL.md of every touched
skill through `export.skills.include`, `persistence.md` through `export.rules`, the blueprint
through `export.blueprint_copy`; the `audit-adapter-boundary` migration for the three blueprints
whose `forbidden_imports` grew.

## Found while writing

Every claim of the planned decisions marked unverified was run in a Spring Boot project from the
Initializr (the default on the day: Spring Boot 4.1.1, Hibernate 7.4.5.Final, Spring Data 2026.0.1,
`spring-data-envers` 4.1.1 managed by the BOM) against `postgres:17` and `mongo:8.0`, first as
hand-written code, then as the exemplars themselves extracted from the templates:

- **An `Instant` revision timestamp breaks every update** under the validity strategy with the
  revision-end timestamp: "class java.time.Instant cannot be cast to class java.lang.Long" at
  `beforeTransactionCompletion`. The timestamp is a `long`; the accessor returns an `Instant`.
- **A bag does not throw — it loses.** A `List<String>` element collection holding `"a", "a"`: two
  rows live, one in `_aud`. The research had said "an exception with a duplicate"; the norm says
  what was observed.
- **A `NOT NULL` on an `_aud` data column passes `ddl-auto: validate`** and fails the first delete
  ("null value in column "status" of relation "orders_aud""). A sequence increment that differs
  from `allocationSize` and a missing `revend` fail the startup, by name.
- **The schema export, not memory, gave the `_aud` shape**: no `version` column, no column for a
  `@NotAudited` field, the `mappedBy` child's order column in the child's `_aud` with no join audit
  table, a primary key `(rev, id)`, and `revend_tstmp` as `timestamp(6)` — `timestamptz`, as the
  norm requires, validated and ran 12/12.
- **History rows are written at commit**: a count inside the writing transaction, after
  `saveAndFlush`, is 0; after commit, 1; a rolled-back transaction leaves 0.
- **Bulk JPQL leaves no revision** — the rows changed, the revision count stayed 1.
- **`@EnableJpaAuditing` on the application class** fails `@WebMvcTest` with "JPA metamodel must not
  be empty"; in its own configuration class, green.
- **`@EnableEnversRepositories` takes over Spring Boot's repository scanning**: with a base package
  narrower than the persistence adapter, "No qualifying bean of type '…OrderSpringDataRepository'".
  A MongoDB repository in the same tree kept its own store.
- **The revision listener is a Spring bean**: constructor injection worked, package-private class
  included.
- **`spring-security-core` alone leaves every endpoint open** (a `GET` answered 200 with no
  credentials) and auditing works — but no request carries an authentication then, which is the
  stop of planned decision 14.
- **MongoDB stamps in a nested object** filled by `@EnableMongoAuditing` keyed on `@Version Long`:
  created kept on the second save, last modified by the second actor. The UUID representation
  setting the norm already requires was needed for the test to write at all.
- **A merge of a detached copy with a `null` `@Version` is an INSERT** (duplicate key), not an update
  that could null the created stamps — the load-and-mutate rule already forbids that path.
- **The prune by the revision's own timestamp deleted the version in force** ("Expected size: 1 but
  was: 0"); by `revend_tstmp`, plus the old delete rows and the unreferenced revisions, it kept it.
- **The research was wrong once more**: `exemplar-imports` already checks every `org.hibernate`
  import, so without `spring-data-envers` in its reference project every Envers import would have
  turned that job red.
- **Not verified, left as stated risks:** MySQL and Oracle DDL for the `_aud` tables (Postgres
  only); Envers with a JPA-coexisting MongoDB transaction manager; the history prune's statement on
  an engine without row-value `IN`.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › auditing exemplars keep the history whole, its actor named and its prune safe` (`.claude/.ci/AuditTemplateTest.java`) | No bag in an audited entity; the created stamps not updatable and no setter; no stamp on `AssignedIdEntity`; a `Long` revision number, a `long` revision timestamp, `allocationSize` = `INCREMENT BY`; the resolver refuses a missing authentication, no `Optional.empty()` auditor; no auditing on an application class, no Hibernate-native `@Audited`, no audited outbox or idempotency store; the validity strategy with the revision-end timestamp, no data stored at delete; `revinfo`, nullable non-key `_aud` columns, `revend`/`revend_tstmp` and their indexes; the prune by `revend_tstmp`; the IT commits and pins its image; no Envers import outside the owners; every blueprint's inner layers | green on the tree; red by file and reason on each of 15 injections: `@OrderColumn` removed, `updatable = false` removed, `Instant` timestamp, `Integer` revision, `INCREMENT BY 1`, `NOT NULL` on `orders_aud.status`, the `revend_tstmp` index removed, `store_revend_timestamp: false`, a `"system"` fallback actor, the prune by `rev`, `@Audited` on the outbox, `@Transactional` on the IT, hexagonal `application` without `org.springframework.data.history..`, onion `domain` without `org.hibernate..`, an Envers import in the REST controller exemplar |
| `templates › JavaTemplatesTest` | `AuditedEntity`, `AuditRevision`, `AuditingConfig` and `SystemActor` compile with the rest in a fresh Initializr project | green, 45 files; red with `spring-data-envers` not added — "package org.hibernate.envers does not exist" |
| `validate · exemplar-imports` | Every import of the new exemplars resolves | the reference project gains `spring-data-envers`; the same imports resolved against the proof project's classpath, `EnableMongoAuditing` aside (the reference project has `data-mongodb`) |
| `validate · hooks-cross-platform › … agent instruction files …` (`AgentInstructionsTest`) | A module `AGENTS.md` missing a blueprint prefix is named | red on the first run after the blueprint change, green with the fixture listing the six new prefixes |
| `validate · design › frontmatter schema`, `rules is a leaf`, `norm contains no code boilerplate`, `no hardcoded Spring/Java version outside decisions/`, `exemplar bodies carry no comment but Javadoc` | Skill bodies, the migration entry, the norm, no dated version, no body comment | green locally; the version step caught "observed on Spring Boot 4.1" in `AuditRevision.java.example`, removed; `schema` red on a migration naming `onionx`, green restored |
| `validate · export-determinism` + `ArchHook.java export` by hand | The new templates travel and the exported tree passes its own `schema` | hexagonal, vertical-slice and clean-architecture-multi-module exported, `schema` exit 0 in each |
| Outside CI, on the day | The exemplars run | `AuditBehaviourTest` 10/10, `MongoStampsTest` 1/1, the slice test, then the templates themselves: `TemplateAuditTest` 4/4 and the `AuditHistoryIT` exemplar 5/5 against `postgres:17` |
