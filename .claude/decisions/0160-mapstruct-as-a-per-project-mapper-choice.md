# 0160 · MapStruct as a per-project mapper choice, installed on demand by `mapstruct-installer`

- **Date:** 2026-10-14
- **Scenario:** "Sugestao de installer de mapstruct para uso de lib consolidada para tratar mappers,
  tambem deve ser criados templates de exemplos, pode usar esse artigo como referencia
  https://medium.com/gitconnected/mapstruct-from-basics-to-advanced-7df239beaaeb"
- **Decision:** Form 3 + Form 4 — `.claude/agents/mapstruct-installer.md` (installer class, invoked
  from `/new-feature-implement`'s pre-flight) and `.claude/rules/mapping.md`, with a MapStruct exemplar
  beside the manual one in each mapping skill, the build fragments in
  `new-feature-implement/templates/mapstruct/`, `org.mapstruct..` forbidden in domain and application,
  and the `mapstruct-per-project` migration
- **State:** approved by Lucas Fernandes, on 2026-10-14 ("Opção 1"); implemented on the branch
  `feat/mapstruct-installer`

Supersedes, if approved, **P5(f) of `@.claude/decisions/0006-rest-api-architect-design.md`**
("DTO↔domain mapper — manual, in static methods. MapStruct brings an annotation processor and hides
the mapping"). The manual mapper stays the default; MapStruct becomes the alternative a project opts
into once, and the record of when each holds moves into a norm.

## What the code showed

| Fact | Where |
|---|---|
| Every mapper is manual: static methods in REST, a `@Component` class elsewhere | `rest-api-architect/templates/RestMapper.java.example`, `persistence-architect/templates/RepositoryAdapter.java.example` and `MongoRepositoryAdapter.java.example`, `graphql-api-architect/templates/GraphQlController.java.example`, `http-client-architect/templates/HttpExchangeAdapter.java.example` and `OpenApiGeneratedAdapter.java.example` |
| MapStruct's default accessor strategy reads only `getX()`/`isX()` and record components — a fluent `order.lines()` on a class is not a property | `mapstruct/processor/.../spi/DefaultAccessorNamingStrategy.java#isGetterMethod` |
| VOs, commands, domain events, DTOs and the immutable aggregate exemplar are records; a mutable aggregate is a class with fluent accessors; a persistence entity has no setters | `domain-modeling/templates/{ValueObject,Command,DomainEvent,Aggregate}.java.example`, `DomainGuards.java.example`, `@.claude/rules/lombok.md` § Forbidden |
| Reconstitution goes through a named factory (`User.rehydrate(...)`, `Email.of(...)`) | `RepositoryAdapter.java.example` § `UserPersistenceMapper` |
| Declaring `annotationProcessorPaths` on `maven-compiler-plugin` turns classpath discovery off: Lombok and `spring-boot-configuration-processor` (which `commons-logging-installer` adds) stop running unless listed too | Maven Compiler Plugin, `annotationProcessorPaths`; MapStruct reference guide § Lombok |
| MapStruct's version is not managed by the Spring Boot parent — 1.6.3 and `lombok-mapstruct-binding` 0.2.0 on Maven Central today, resolved at install time, never written | `search.maven.org`, 2026-10-14; invariant 8 applied as for springdoc in `0006` P7 |
| `/new-feature-implement` writes nothing itself; the pre-flight invokes installers | `@.claude/decisions/0135-new-feature-split-spec-and-implement.md`, `skill_classes.orchestrator.overrides` |
| The two Java installers write `pom.xml` only — no Gradle | `@.claude/agents/archunit-installer.md`, `@.claude/agents/commons-logging-installer.md` |

The reference article (pasted by the user — Medium refuses non-browser clients) was read technique by
technique against the norms; its verdicts are the § Article techniques table below.

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | Repetitive manual mapper boilerplate; teams adopting a project expect MapStruct | create nothing |
| Policy | Templates for both shapes. Installing MapStruct makes it the project's standard; a project that does not install it keeps the manual default | a repository-wide default either way |
| 2 — Trigger | On demand: `/new-feature-implement`'s pre-flight, like `commons-logging-installer` and `archunit-installer` | `project-bootstrap` at generation |
| Layers | REST, persistence, GraphQL, messaging, outbound HTTP | — |
| Declined | Recorded in the project (`**Mappers:** manual` in the root `CLAUDE.md`), never asked again | asking on every run |
| Existing mappers | The installer migrates every manual mapper when it installs | a mixed project; a `migrations` entry |
| `expression` / `defaultExpression` | Forbidden — a conversion is a `@Named` method or a `uses=` class | — |
| Tests | No dedicated mapper test: `unmappedTargetPolicy = ERROR` fails the compile on a forgotten field, and the adapter tests already cover conversions | a new test template |
| 5 — Nature | A code-shape norm (rule) plus a one-shot setup with a migration (agent) | Forms 5, 6, 9 |
| 6 — Isolation | Build logs and a migration across every mapper and its callers — reason 1, preserve context | Form 1/2 alone |
| 7 — Mandatoriness | The forgotten-field check is the compiler's, set by the installer; `org.mapstruct..` stays out of domain and application through each blueprint's `forbidden_imports`, which `ArchHook.java check` already enforces | Forms 7c, 8 |
| 8 — Destination | The generated project (and adopted ones — the agent travels) | — |

## Article techniques

| Technique | Verdict | Why |
|---|---|---|
| `@Mapping(source, target)`, `ignore`, several source parameters | adopted | — |
| `componentModel = "spring"` | adopted, as processor options with constructor injection | `@.claude/rules/architecture-ddd.md` § Composition |
| `Mappers.getMapper` / `INSTANCE` | rejected | hides the dependency from the container |
| `@Named` + `qualifiedByName`, `uses=` | adopted — the only route to a value object | the VO's factory validates |
| Nested target into a VO (`phone.phoneNumber`) | rejected for a VO | builds it without its factory |
| `@MappingTarget` merge | adopted where the target can be written | — |
| `NullValuePropertyMappingStrategy.IGNORE` | only for a partial update (PATCH); never aggregate → entity | a null that must clear a column is silently kept |
| `@BeforeMapping` / `@AfterMapping` with `instanceof` | rejected → `@SubclassMapping` | `@.claude/decisions/0156-branching-on-a-closed-set.md`; a record DTO has no setter |
| `defaultValue` into a domain type | rejected | a default is a business rule |
| `@Context` carrying a repository | rejected | I/O inside a mapper; the lookup is the adapter's |
| `Map` → bean, `java.util.Date` / `SimpleDateFormat` | out of scope | untyped / `java.time` only |
| Fixed `1.5.5.Final`, no `annotationProcessorPaths` | corrected | invariant 8; Lombok breaks without the binding |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `mapstruct-installer` agent (installs + migrates) + `rules/mapping.md` + a MapStruct exemplar per mapping skill + a third pre-flight gap | 8 | **Approved** |
| 2 | Option 1 with an install-only installer; existing mappers migrate when a feature touches them | 7 | Rejected — contradicts the Existing-mappers answer; projects stay mixed |
| 3 | `/mapstruct-setup` manual skill (Form 2), run in the main thread whenever the user asks | 6 | Rejected — the migration's output lands in the main context; the pre-flight would still need the gap |
| 4 | create nothing — `0006` P5(f) stands | 3 | Rejected — an observed cost and an adoption expectation |

### Option 1 — `.claude/agents/mapstruct-installer.md` + `.claude/rules/mapping.md` (score 8)

**Motivator:** axes 1, Policy, 2 and Existing mappers.

**Pros:** follows the two installer precedents; a project is homogeneous after the install; the
forgotten-field guarantee is the compiler's, with no hook; the design skills keep writing *what* maps
to *what* and never learn which mechanism the project chose.

**Cons:** the migration needs `src/main/java/**` and `src/test/java/**` in the agent's territory
(a static `RestMapper.toX(...)` becomes an injected bean, so its callers and their tests change) —
the widest installer territory in the repo; five new exemplars to keep beside the manual ones; Gradle
support is new for an installer.

**Points cut in the rubric:** 5 (maintenance — two exemplars per mapping site); 8 (trust surface — a
`src/**` territory for an installer).

**CI:** `validate › schema` (agent class, frontmatter, export entry, rule index); `AgentTerritoryTest`
gains the new agent; `TemplateLombokTest` already reads the new exemplars; new
`MapStructWiringTest.java` in `templates.yml` (fresh Initializr project, Maven and Gradle, Lombok +
configuration processor + MapStruct from the installer's snippets: generated bean with constructor
injection, Lombok still applied, configuration metadata still written, and an unmapped target field
failing the compile); new `MapperTemplateTest.java` in `validate.yml` (every MapStruct exemplar free
of `expression =`, `defaultExpression`, `Mappers.getMapper`, `@Context`, `IGNORE` outside a PATCH,
and every manual mapper site paired with a MapStruct exemplar).

### Option 2 — install-only installer (score 7)

Same pieces; the installer writes build files and the `**Mappers:**` line only, and existing manual
mappers stay until a feature touches them. Narrow territory, small diff — but contradicts the
Existing-mappers answer and leaves projects mixed for an unbounded time.

### Option 3 — `/mapstruct-setup`, Form 2 (score 6)

Lets the user switch at any time, not only at an implement run. Rejected on axis 6: the migration's
build output and file list land in the main conversation, and nothing detects the choice at the one
point where it matters — the pre-flight would still need the gap.

### Option 4 — create nothing (score 3)

`0006` P5(f) stands. Axis 1 names an observed cost and an adoption expectation; the article's
concern ("hides the mapping") is answered by the forbidden list and `unmappedTargetPolicy = ERROR`,
not by refusing the library.

## References

| Claim | Source |
|---|---|
| An agent exists only to preserve context, restrict tools or change model | `@CLAUDE.md` invariant 5; decision matrix § 5 |
| Installer precedent, triggered from the pre-flight | `@.claude/agents/commons-logging-installer.md`, `new-feature-implement` § 2 step 1 |
| A norm never names a skill or agent | `@CLAUDE.md` invariant 1 |
| Versions resolved at runtime | `@CLAUDE.md` invariant 8; `0006` P7 |
| Boundary imports are blueprint data | `@CLAUDE.md` invariant 7; `hexagonal/hexagonal.yaml` `forbidden_imports` |
| A rejected mechanism keeps when/when-not criteria in the norm and its own exemplar | user preference, recorded in this session's memory |

## Found while implementing

| Finding | Consequence |
|---|---|
| The Initializr (Boot 4.1.1) already declares `annotationProcessorPaths` **inside the `default-compile` execution** (Lombok + configuration processor) and `default-testCompile` (Lombok), with no versions | The fragment goes into that execution — a plugin-level list would be replaced by it and ignored. Written into the fragment's header, the agent's step 5, `mapping.md` § Build and the pitfalls page |
| `maven-metadata.xml`'s `<release>` for MapStruct was `1.7.0.Beta2` while `1.6.3` was the latest stable | The installer and the test take the last version with no pre-release qualifier. `archunit-installer` and `exemplar-imports` still read `<release>` — out of this decision's scope, reported |
| MapStruct reads no fluent accessor on a class (`DefaultAccessorNamingStrategy#isGetterMethod`), and picks a JPA entity's protected no-args constructor, finding no writable property — an empty entity `ERROR` does not catch | `mapping.md` § What MapStruct generates and what stays written; the persistence exemplar keeps `toEntity` and `toDomain` as `default` methods and generates only the enum translation |
| Running the MapStruct install after the other two installers would always find `src/` dirty | The pre-flight runs it first and alone |

## Propagation

| File | Change |
|---|---|
| `.claude/agents/mapstruct-installer.md` | New — installer class, modes `manual` and `install` |
| `.claude/rules/mapping.md` | New — one mechanism per project, MapStruct build, generated vs written, forbidden constructs |
| `.claude/rules/00-index.md`, `.claude/rules/naming.md` | Index row; the mapper row cites the norm |
| `.claude/skills/new-feature-implement/SKILL.md` | Third pre-flight gap (`**Mappers:`), invocation order, reads/writes/integrates, references |
| `.claude/skills/new-feature-implement/templates/mapstruct/` | `mapstruct-pom.xml.example`, `mapstruct-build.gradle.example` |
| `.claude/skills/{rest-api,persistence,graphql-api,http-client,messaging}-architect/templates/*MapStructMapper.java.example` | New exemplars, one per mapping skill |
| `.claude/skills/{rest-api,persistence,graphql-api,http-client,messaging}-architect/SKILL.md` | The paired-exemplar paragraph; REST step 5 and DTO row; GraphQL template row |
| `.claude/skills/rest-api-architect/templates/RestMapper.java.example` | Header: the default mechanism, no longer "MapStruct rejected" |
| `.claude/agents/java-spring-boot-developer.md` | § Mappers; the REST mapper line and its report line |
| `.claude/blueprints/{hexagonal,clean-architecture-multi-module,onion,custom-template}` | `org.mapstruct..` in `forbidden_imports` of domain (and application where it is a module) |
| `.claude/schemas/extensions.json` | `agent_classes.installer` list and override; `export.agents.include`; four exempt globs; `migrations` entry `mapstruct-per-project` |
| `.claude/skills/project-bootstrap/templates/root.CLAUDE.md.example`, `CLAUDE.md` | Routing row |
| `docs/pt-br/11-pitfalls.md`, `docs/en/11-pitfalls.md` | The mechanism paragraph and its three silent traps |
| `docs/pt-br/07-ci-validate.md`, `docs/en/07-ci-validate.md` | Rows for the two new checks |
| `.claude/decisions/0006-rest-api-architect-design.md` | P5(f) marked superseded by this record |

Goes to the generated project: **yes** — the agent, the rule, the fragments and the exemplars
through `export` (checked with `export --dry-run --blueprint hexagonal`); this record does not.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `templates · mapstruct-wiring › build fragments + every MapStruct exemplar, green then an unmapped target red` (`MapStructWiringTest`) | Both fragments wire Lombok, the configuration processor and MapStruct on Maven and Gradle; every `*MapStructMapper.java.example` compiles; a generated mapper is a constructor-injected `@Component`; an unmapped target fails the compile | green (mapstruct 1.6.3, binding 0.2.0) · red with the configuration processor dropped from the fragment ("drops it — it would stop running with no error") · red with `unmappedTargetPolicy=ERROR` removed ("is not on") |
| `validate › MapStruct mapper exemplars hold mapping.md § Forbidden and pair every manual one` (`MapperTemplateTest`) | No forbidden construct in a MapStruct exemplar, none `public`; every skill with a manual mapper has its MapStruct sibling | green · red with `expression = "java(...)"` injected (named by file and line) · red with the REST exemplar removed |
| `validate › AgentTerritoryTest` | `mapstruct-installer` writes build files, the root `CLAUDE.md` and both source trees; refused a spec, the compose file and `application.yml` | green |
| `validate › AgentInstructionsTest` | The hexagonal fixture lists `org.mapstruct..` for domain and application | red before the fixture was updated, green after |
| `validate › schema` | Agent class, frontmatter, export entry, rule index, migration entry | green |
