# 0146 · A design skill owns the inbound GraphQL adapter, chosen per use case in the Trigger row, over the same input port a REST adapter calls

- **Date:** 2026-10-13
- **Scenario:** "Criar a skill de design graphql-api-architect: partial 31-graphql.md, par do
  30-rest.md do rest-api-architect — o adapter de entrada GraphQL de um caso de uso já modelado,
  sobre Spring for GraphQL no stack servlet, schema-first, @Controller com @QueryMapping /
  @MutationMapping / @SchemaMapping / @BatchMapping chamando o port de entrada."
- **Decision:** Option 1 — Form 1 `.claude/skills/graphql-api-architect/SKILL.md` (class `design`,
  partial `31-graphql.md`) + Form 4 `.claude/rules/api-graphql.md` (territory `.graphql` and
  `src/main/resources/graphql/`); a closed adapter phrase ending `00-caso-de-uso.md`'s `Trigger` row;
  a `.graphql` package key in every blueprint and the GraphQL libraries forbidden in every inner layer
  that declares `forbidden_imports`; `/new-feature` runs it as step 3g, right after REST, and skips
  REST when the trigger names GraphQL alone.
- **State:** approved by Lucas Fernandes, on 2026-10-13 — option 1 with every planned decision of
  its table; authorization at each adapter's boundary (A), inferred from the maintainer's question
  and confirmed before approval; implemented on the branch `feat/graphql-api-architect`

## What the context showed

| Concern | Owner today |
|---|---|
| Choosing REST or GraphQL for a case | nobody — `use-case-design` § Out of scope says it is "fixed earlier: the blueprint's `packages.map` and `features`", and no blueprint has a `graphql` feature nor a GraphQL package key |
| The inbound adapter kind of a case | prose — the `Trigger` row of `use-case-spec.md.example` ends in `inbound REST adapter`, `inbound messaging adapter` or `inbound job adapter`, never read by any step |
| Whether `rest-api-architect` runs | nobody decides — `/new-feature` step 3 is unconditional, even for an event or schedule trigger |
| Who may call an operation | `security-architect`, which stops without `30-rest.md`; `authorization.md` § Boundary puts method security on the controller and keeps the security framework out of `application` |
| `graphql..` / `org.springframework.graphql..` reaching `application` | nobody — `application.forbidden_imports` lists `jakarta.persistence..`, `org.springframework.web..`, `org.springframework.ai..` |
| GraphQL dependency | Spring Initializr id `graphql` (read 2026-10-13, Boot default 4.1.1); absent from `dependency-catalog.md` |

**Found while designing, out of this decision:** `api-rest.md` § How to verify says
`openapi.json` is generated in CI and compared with the committed version, and
`rest-api-architect` § Contract says "the `openapi.json` diff job belongs to `project-bootstrap`" —
`ci.yml.example` and `ci-gradle.yml.example` have no such job. A claimed check that does not exist.
Recorded here and left to its own run, by the maintainer's answer; this design does not lean on it.

No `/new-feature` run has shown a GraphQL defect. What the model gets wrong without the piece comes
from the research in the scenario (Spring for GraphQL and Spring Boot references and source,
graphql-java, GraphQL over HTTP, Relay connections, graphql.org Learn, OWASP, Apollo, Shopify) —
re-verified against each page before `references/pitfalls.md` is written; versions are read at
write time and never written into a template (invariant 8).

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — symptom | None observed. A planned solution-design step will pick the inbound protocol per case; without a GraphQL designer that choice falls back to REST by omission. Anti-pattern 9 overridden on purpose, as in `0144` and `0145` | Create nothing (as the whole answer) |
| 2 — trigger | Model decision inside `/new-feature`, when the case's `Trigger` names GraphQL; standalone by description | Form 2 |
| 5 — nature | Multi-step procedure: inherit the port, design schema, operations, batching, errors, limits, access per operation and field, tests | Forms 4 and 5 as the whole answer |
| 6 — isolation | None — the interview is the task | Form 3 |
| 7 — mandatoriness | Persuasion, plus the guarantee already in place: `forbidden_imports` read by `check`/`doctor gate` and the ArchUnit test | Forms 7 and 8 as new pieces |
| 8 — destination | Both — a development skill, `export.skills.include` | — |
| — where the choice lives | The `Trigger` row, per case, as a closed vocabulary; no blueprint feature. The dependency is declared by the partial, as `ai-architect` and `cache-architect` do | Feature + Trigger; feature alone |
| — scope | Queries and mutations. Out, with the reason recorded: subscriptions (WebSocket/SSE), file upload (a signed URL from a mutation, or REST), federation, `@GraphQlRepository`/Querydsl (skips the input port), codegen, WebFlux and RSocket | An SSE subscription variant |
| — the norm | Its own `rules/api-graphql.md`, package territory `.graphql` | A section of `api-rest.md` |
| — authorization with two adapters | At each adapter's entry boundary: `@PreAuthorize` on the GraphQL `@Controller` method, the same named meta-annotation as the REST controller, ownership in the use case. `authorization.md` § Boundary unchanged. A decorator of the input port is recorded as the evolution for a third caller-facing adapter | `@PreAuthorize` on the application service; an authorizing decorator now |
| — errors | Errors as data on mutations: a business rule or conflict the client can fix returns in the payload as `userErrors { field, code, message }`, `code` = the domain `errorCode`; a not-found on a query returns `null` plus a `NOT_FOUND` error; shape validation, integration and unexpected failures are top-level errors with a classification mapped from the taxonomy | Result unions; top-level errors only |
| — N+1 | `31-graphql.md` lists each child field resolved by `@BatchMapping` and asks for the batch read port as a named requirement; consolidation carries it to the domain block with who asked; persistence reads the `IN` query and the batch ceiling in its first pass | `domain-modeling` declaring batch ports up front |
| — project setup | Decided by the first `31-graphql.md` of the project, with a reason per value, like the filter chain of the first secured case; later cases inherit and change a value only with a reason and an impact row | Fixed defaults in a template |
| — schema compatibility in the generated project's CI | A committed schema snapshot and a JUnit test (graphql-java, no Node) that fails when a type, field or argument disappears or changes type without a prior `@deprecated`; additive changes pass. Runs inside `./mvnw verify`, no new CI step. If graphql-java's diff API is not stable on the day, the test falls back to a textual snapshot comparison, recorded here | A textual snapshot only; deferring it |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | `skills/graphql-api-architect/` (Form 1, class `design`, partial `31-graphql.md`) + `rules/api-graphql.md` (Form 4, territory `.graphql`) + a closed adapter vocabulary in the `Trigger` row + a `.graphql` package key in every blueprint + `graphql..` and `org.springframework.graphql..` forbidden where the blueprint restricts `application`/`domain` | 7 | **Approved** |
| 2 | Same, with a `graphql` feature in every blueprint (asked by `/init-project` and `arch-adopt`, an `adapter-in-graphql` module in multi-module layouts) gating the `Trigger` choice | 5 | Rejected — the interview chose the `Trigger` alone |
| 3 | No new skill: GraphQL as a mode of `rest-api-architect`, norms as a section of `api-rest.md` | 4 | Rejected — the interview chose an own norm |
| 4 | Create nothing | 3 | Rejected — the maintainer chose to anticipate |

### Option 1 — skill + `rules/api-graphql.md` + Trigger vocabulary + `.graphql` package (score 7)

**Motivator:** axis 1 (the maintainer's decision to anticipate, for the planned solution-design
step) and the "where the choice lives" answer — the choice must be data a later step can write per
case, and GraphQL has a design half no REST interview reaches: schema shape, nullability, batching,
connections, errors as data, demand limits, field-level access.

**Pros:** one owner for the GraphQL contract, its schema and its project-wide limits; the choice per
case is one row a solution-design step can write; two adapters over one input port is a supported
case with no norm moving; the executor gets the norms by `paths` on a territory of its own; the
import boundary is data the existing guarantee already reads; the REST step gains the condition it
never had for a GraphQL-only case; same shape as `0087`, `0121`, `0144` and `0145`.

**Cons:** wide propagation — `use-case-design` (vocabulary, Access wording), `domain-modeling` (none
— the batch port arrives as a requirement), `new-feature` (step, short path, consolidation, gates,
precedence, spec block), `security-architect` (entry rule and per-operation/per-field rows),
`persistence-architect` (reads batch loads and connection order), `test-architect` (three test
templates), the executor (Block G), `new-feature-implement`, eight blueprints, `extensions.json`,
`authorization.md` and `observability.md` (one bullet each, and a `.graphql` suffix in their
`derived_paths`), routing and docs. Anticipation, not a symptom. Authorization is written once per
adapter, not once per port — a forgotten annotation on one adapter is caught by the per-adapter
contract test, not prevented.

**Planned decisions inside the option:**

| # | Question | Answer |
|---|---|---|
| 1 | The signal | The `Trigger` row ends in one of: `inbound REST adapter` · `inbound GraphQL adapter` · `inbound REST and GraphQL adapters` · `inbound messaging adapter` · `inbound job adapter`. `use-case-design` asks it for an HTTP trigger and records who chose; the solution-design step, when it exists, writes the same row. The choice criteria (for, against, both) live once, in `api-graphql.md` § Choosing the inbound protocol, a leaf that names no skill — the solution-design step cites it |
| 1b | Step conditions and position | Step 3 (REST) runs unless the `Trigger` names GraphQL alone — an older spec with no vocabulary keeps today's behaviour. New step **3g** (GraphQL) right after step 3, before 3b, when the `Trigger` names GraphQL. Messaging and job triggers unchanged. Short path: a GraphQL trigger is an unmet criterion (the short template has no GraphQL row) |
| 2 | Consolidated spec | `## 4 REST` reads `none — GraphQL only` when step 3 was skipped; a new `## 4.1 GraphQL` when step 3g ran, `none` otherwise. Precedence row: schema types, fields, arguments, nullability, operation names, connection shape, `userErrors` payloads, batch loads, project limits → `31-graphql.md`; 401/403 stay `32-seguranca.md`'s. The personal-data gate and the declared-dependency gate gain `31-graphql.md`'s blocks |
| 3 | Security | `security-architect` enters with `30-rest.md` **or** `31-graphql.md`. § 1 gets one row per operation per adapter (`POST /api/v1/…`, `Mutation.placeOrder`) and one per sensitive field (`Customer.taxId`), each path to a sensitive type listed (edge × node). GraphQL is always method security on the `@Controller` method — the chain sees one `POST /graphql`; the role is the same named meta-annotation both adapters use. § 6 records that Boot's `SecurityDataFetcherExceptionResolver` turns a denial into an `UNAUTHORIZED`/`FORBIDDEN` error with HTTP 200. `authorization.md` § Boundary gains one bullet: on a single-endpoint protocol the request rules cannot tell operations apart, so method security on the resolver is the check. The `Access` row's "when the trigger is HTTP" covers GraphQL — the template says so |
| 4 | The norm | `rules/api-graphql.md`: `derived_paths` suffix `.graphql`, optional, extra `**/src/main/resources/graphql/**`. Leaf; cites `error-handling.md`, `authorization.md`, `personal-data.md`, `observability.md`, `persistence.md`, `secrets.md` by path. `authorization.md` and `observability.md` gain `.graphql` in their `derived_paths` suffixes |
| 5 | Errors | The table in `api-graphql.md`: `NotFoundException` → `NOT_FOUND` on a query (field `null`); `ValidationException` and shape validation → `BAD_REQUEST`; `BusinessRuleViolationException` and `ConflictException` on a mutation → `userErrors` in the payload; the three integration families → `INTERNAL_ERROR` with `extensions.errorCode` and, for `DependencyUnavailableException`, the announced wait; anything else → `INTERNAL_ERROR` with `traceId` and no message. One resolver in the adapter; parse and validation errors never reach it and are covered by the limits and the test |
| 6 | N+1 and pagination | Every child field of a list is `@BatchMapping` over a batch port (keys → map), never a per-parent `@SchemaMapping`; the batch port arrives as a requirement (interview); `maxBatchSize` set by the project setup. Lists that grow are Relay connections over `ScrollSubrange`/`Window`, `first`/`last` capped by the setup's page ceiling, order stable with a unique tiebreaker — the index is a requirement to persistence, which reads `31-graphql.md` § Schema requirements as it reads `30-rest.md` block 4 |
| 7 | Project setup | First `31-graphql.md` § Project setup: max depth, max complexity, request timeout, page ceiling, `maxBatchSize`, introspection off outside development, GraphiQL only in development, CORS allowing `POST`, schema inspection failing startup on an unmapped field. Written once by the executor, inherited after |
| 8 | Transaction and threads | The port returns a complete read model (`record`), never an entity; no `@Transactional` on a GraphQL controller; each batch load is its own port call and transaction. With virtual threads the controller method may run off the request thread — nothing thread-bound is assumed |
| 9 | Ownership boundaries | Schema snapshot test: `test-architect` template, run by `./mvnw verify`, no CI step. Metrics: `observability.md` gains one bullet — a GraphQL metric is tagged by outcome, never by the client-supplied operation name. Personal data: `31-graphql.md` § Personal data, same shape as `30-rest.md` § 6, every output field walked through `logging.md` § Masking candidates. `test-architect`: a `@GraphQlTest` slice for mapping and errors, an `HttpGraphQlTester` IT through the real server and chain for the three-way access proof, the depth rejection and a query count proving no N+1, and the schema compatibility test. Executor: Block G (G1-G3) right after Block 3, before Block S; nothing new at `SubagentStart` |
| 10 | Templates | `schema.graphqls`, `GraphQlController` (`@QueryMapping`, `@MutationMapping`, `@BatchMapping`, a `@SchemaMapping` with `@PreAuthorize` on a sensitive field), input/payload records with `UserError`, `GraphQlExceptionResolver`, `GraphQlConfig` (limits, timeout, batch size, schema inspection), `application-graphql.yml`, `graphql-spec.md`; `references/pitfalls.md` and `references/graphql-reference-links.md` |
| 11 | Destination and migration | `export.skills.include`, `skill_classes.design`; a `graphql-adapter-boundary` migration for the blueprints whose `forbidden_imports` or dependency rules change — `.claude/forbidden-imports.txt` and module `AGENTS.md` are written once, at generation |
| 12 | Territory | A `packages.map` key ending in `.graphql` in every blueprint (hexagonal `adapter.in.graphql`, compiled into `bootstrap` like `adapter.in.scheduling`); `graphql..` and `org.springframework.graphql..` in `application.forbidden_imports` where the module exists, `graphql..` in `domain`'s (`org.springframework..` already covers the other); layered and vertical-slice gain dependency rules for the `graphql` package |

**Points cut in the rubric:** 1 (form fit — no signal, overridden by the maintainer) · 5
(maintenance: many files touched).

**CI:** `validate` › `design` › `frontmatter schema` already proves the skill's class, sections,
`export` entry and the rule's `paths` and `derived_paths`; the leaf, single-owner and "every norm
paths matches some blueprint" steps cover the rule. New: (a) `.claude/.ci/GraphQlTemplateTest.java`
in `validate` › `design` — fails by name when the config exemplar lacks a depth limit, a complexity
limit, a timeout, a page ceiling or `maxBatchSize`; when a child list field is resolved per parent
instead of batched; when the exception resolver is missing or echoes an unknown exception's message;
when a connection argument has no ceiling; when a GraphQL controller is `@Transactional`; when
`@GraphQlRepository` appears; when introspection or GraphiQL is on outside development; when CORS
omits `POST`; when a metric tag reads the operation name; when any exemplar outside the skill imports
GraphQL; when a blueprint lacks the `.graphql` key or lets `application` import GraphQL; (b)
`exemplar-imports` gains the Initializr dependency `graphql`; (c) `AgentInstructionsTest`'s
hexagonal fixture lists the new prefixes, and `MigrationsSchemaTest` covers the new entry.

**Planned shape:**

```
use-case-design ── Trigger: inbound REST adapter | inbound GraphQL adapter | inbound REST and GraphQL adapters
                                │
new-feature  2 domain ─► 3 REST (unless GraphQL alone) ─► 3g graphql-api-architect ─► 3b security ─► 3c ─► 3d ─► 4 ─► 4b ─► 5 persistence
                                                       │ reads 00, 10, 30 when present
                                                       │ writes 31-graphql.md: operations, schema, inputs and
                                                       │   payloads with userErrors, batch loads (requirements),
                                                       │   connections, error map, project setup (first case),
                                                       │   personal data, dependencies, test cases, design
                                                       │   patterns, deferred
                                                       ▼
security-architect ── one row per operation per adapter, per sensitive field
persistence-architect ── batch IN queries, connection order index
executor  Block 3 ─► Block G (G1-G3) ─► Block S   — rules/api-graphql.md by paths on the .graphql package
test-architect ── @GraphQlTest slice · HttpGraphQlTester IT (access, depth, N+1) · schema compatibility
```

### Option 2 — a `graphql` feature gating the Trigger (score 5)

Adds an `/init-project` question and an `adapter-in-graphql` module for a choice the solution-design
step makes per case, and a second place a GraphQL case can be refused. The dependency already has an
owner — the partial's declared list, as in `0144` and `0145`. Cut: 3 (one more interview question per
project), 5, 6.

### Option 3 — a mode of `rest-api-architect`, norms in `api-rest.md` (score 4)

One skill and one norm with two subjects. `api-rest.md`'s glob is derived from `.rest` and cannot
reach a `.graphql` package without the union `derived_paths` forbids ("no defensive union"); the
REST interview — verbs, status, `Idempotency-Key`, `ETag` — reaches none of batching, nullability,
connections or demand limits. Cut: 1, 2 (one norm, two subjects), 5.

### Option 4 — create nothing (score 3)

What the decision matrix recommends with no symptom (anti-pattern 9). A case the solution-design
step marks GraphQL would reach the pipeline with no designer, an unconditional REST step and no
import boundary.

## References

| Claim | Source |
|---|---|
| A pipeline design piece is Form 1, not Form 2 | decision matrix § 4 · `decisions/0007-pipeline-skills-invocation.md` |
| No agent: the interview is the task | decision matrix § 5 |
| No signal → create nothing, unless overridden | decision matrix § 3 and anti-pattern 9 |
| Norm in one owner, cited by path; a rule never names a skill | `@CLAUDE.md` invariants 1 and 2 |
| Boilerplate only in `templates/*.example` | `@CLAUDE.md` invariant 3 |
| Package territory is blueprint data, with an `export.derived_paths` entry; no defensive union | `@CLAUDE.md` invariants 7 and 9 · `extensions.json` `derived_paths.$comment` |
| Versions resolved at runtime; Initializr id `graphql` | `@CLAUDE.md` invariant 8 · Initializr metadata read 2026-10-13 |
| Technology choice "fixed earlier" by the blueprint | `skills/use-case-design/SKILL.md` § Out of scope |
| The Trigger already names the adapter in prose | `skills/use-case-design/templates/use-case-spec.md.example` · `use-case-diagram-variants.md.example` |
| Step 3 is unconditional | `skills/new-feature/SKILL.md` § Step 3 |
| Method security on the controller; no security framework in `application` | `rules/authorization.md` § Boundary |
| `security-architect` stops without `30-rest.md` | `skills/security-architect/SKILL.md` § Entry rule |
| A downstream partial asks, consolidation resolves | `skills/new-feature/SKILL.md` § Consolidation, step 1 |
| A first-case project setup decided in a partial | `skills/security-architect/SKILL.md` (project-wide setup, § 4) · `decisions/0112-security-architect-skill.md` |
| An inbound adapter compiled into `bootstrap` | `blueprints/hexagonal/hexagonal.yaml` `adapter.in.scheduling` · `decisions/0087-jobs-architect-skill.md` |
| Failure families | `rules/error-handling.md` § Taxonomy and § Integration families |
| A migration when `forbidden_imports` grows | `extensions.json` `migrations` `ai-adapter-boundary` · `decisions/0145-ai-architect-skill.md` |
| Design skill + own rule + package key, the precedent | `decisions/0087`, `0121`, `0144`, `0145` |

## Propagation

| File | Change |
|---|---|
| `.claude/skills/graphql-api-architect/SKILL.md` | New — entry rule (a GraphQL trigger and `10-dominio.md`), scope rule (queries and mutations; no public operation), inherit-first survey, interview, thirteen-block partial |
| `.claude/skills/graphql-api-architect/references/pitfalls.md` | New — 40 pitfalls in eight groups plus the out-of-scope six, each with the norm section and one source; nine marked **observed** |
| `.claude/skills/graphql-api-architect/references/graphql-reference-links.md` | New — the pages each decision is re-read against, every URL checked on 2026-10-13 |
| `.claude/skills/graphql-api-architect/templates/*.example` | New — `schema.graphqls`, `GraphQlController` (ports, read models, adapter records, mapper, `OrderCursors`), `GraphQlConfig` (`GraphQlLimits`), `GraphQlExceptionResolver`, `application-graphql.yml`, `graphql-spec.md` |
| `.claude/skills/test-architect/templates/GraphQlControllerTest`, `GraphQlHttpIT`, `GraphQlSchemaCompatibilityTest` `.java.example` + `SKILL.md` | New tests; design mode reads `31-graphql.md` § 10 |
| `.claude/rules/api-graphql.md` | New norm — choosing the protocol, boundary, schema, batching, pagination, errors, access, demand limits, evolution, observability, personal data, tests, How to verify |
| `.claude/rules/authorization.md` | Two bullets in § Boundary (single-endpoint protocol; two adapters, one meta-annotation); `paths` example gains the GraphQL package |
| `.claude/rules/observability.md` | § Metrics: no client-chosen value as a label, GraphQL measured by type and outcome; `paths` example |
| `.claude/rules/00-index.md` | `api-graphql.md` row; listed with the package-territory rules |
| `.claude/schemas/extensions.json` | `skill_classes.design.skills` (and its count), `export.skills.include`, `derived_paths["api-graphql.md"]`, `.graphql` in the `observability.md` and `authorization.md` suffixes, the schema glob among the exempt ones, the `graphql-adapter-boundary` migration |
| `.claude/blueprints/*/*.yaml` (8) | A `packages.map` key ending in `.graphql` (hexagonal `adapter.in.graphql`, clean `infrastructure.graphql`, onion and custom `app.graphql`, modular-monolith `adapter.graphql`, vertical-slice `config.graphql`, layered a top-level `graphql` with its dependency rules); `org.springframework.graphql..` and `graphql..` in `application`, `graphql..` in `domain`/`core` |
| `.claude/skills/use-case-design/SKILL.md` + `templates/use-case-spec.md.example` | The protocol asked for an HTTP trigger with the norm's criteria; the closed phrase ending the `Trigger` row; `Access` covers GraphQL; "REST or GraphQL" removed from what the blueprint fixes |
| `.claude/skills/new-feature/SKILL.md` + `templates/feature-spec.md.example` | Step 3 conditional, step 3g, short-path criterion 7, security, persistence and tests reading `31-graphql.md`, precedence row, consolidation gates (§ 8, § 11), spec block `4.1 GraphQL`, Block G recipient |
| `.claude/skills/security-architect/SKILL.md` | Entry with `30-rest.md` or `31-graphql.md`; one row per root field and per sensitive field; GraphQL failure rows; tests over HTTP |
| `.claude/skills/persistence-architect/SKILL.md` | Reads `31-graphql.md` § 9 — batch statements, the connection order's index |
| `.claude/skills/rest-api-architect/SKILL.md` | Boundary row: skipped for GraphQL alone; the GraphQL pair |
| `.claude/agents/java-spring-boot-developer.md` | Block G (G1-G3) after Block 3, before Block S; reads `api-graphql.md`; writes `[graphql]/**`, the schema files and the snapshot; Block 3 skipped for GraphQL alone; Block 4 GraphQL tests |
| `.claude/skills/new-feature-implement/SKILL.md` | `adapters` group lists G |
| `.claude/.ci/GraphQlTemplateTest.java` + `.github/workflows/validate.yml` | New test in `design`; `exemplar-imports` gains `graphql` and the `graphql`, `org.dataloader` prefixes |
| `.claude/.ci/AgentInstructionsTest.java` | The hexagonal fixture lists the new `domain` and `application` prefixes |
| `CLAUDE.md`, `project-bootstrap/templates/root.CLAUDE.md.example` | Routing rows |
| `README.md`, `claude-help.md`, `roadmap.md`, `docs/{en,pt-br}/*` | Pipeline, design-skill list, norm and skill counts, CI rows — the mechanical step 8, delegated with this record |

Goes to the generated project: **yes** — `graphql-api-architect` via `export.skills.include`,
`api-graphql.md` via `export.rules` with its glob derived from the blueprint's `.graphql` entry plus
the schema folder — verified by exporting into the seven blueprints: `**/adapter/in/graphql/**`,
`**/infrastructure/graphql/**` ×2, `**/app/graphql/**`, `**/graphql/**`,
`**/shared/internal/adapter/graphql/**`, `**/config/graphql/**`, each with
`**/src/main/resources/graphql/**`; the skill present and `schema` green inside each.

A `migrations` entry, `graphql-adapter-boundary` (hexagonal, clean-architecture-multi-module, onion,
layered): `.claude/forbidden-imports.txt` and each module `AGENTS.md` are written once, at generation,
so a project generated before this version would not refuse the GraphQL imports. The same shape as
`ai-adapter-boundary` (`0145`).

**Found while writing, recorded here:**

- **Running the exemplars changed four of them.** The complexity calculator multiplied every field by
  the default page and priced a two-order page at 344421 — only a field declaring `first` multiplies
  now. The schema snapshot sat under `src/test/resources/graphql/`, on the test classpath, where the
  compatibility test read it as the schema and Boot would load it in every `@SpringBootTest` — it
  moved to `src/test/graphql/`. Printing `#` comments made two runs over the same files differ — the
  printer drops them. The slice test needs `@WithMockUser`: without an authentication the
  controller's `Authentication` parameter fails as an internal error.
- **The N+1 proof is split by layer.** The planned "query count" became, in this adapter's tests, one
  batch port call per request with every parent key (the slice); the statement count of that call is
  the persistence integration test's, which owns the statement.
- **A public GraphQL operation is out of this version.** The endpoint stays authenticated in the
  chain, deny-by-default intact; opening `/graphql` moves deny-by-default onto every resolver and is a
  decision of its own. Recorded in the skill's scope rule and in `api-graphql.md` § Access.
- **A mutation's not-found is a `userError`.** The planned table named only a query's not-found; on
  a mutation an unknown target is something the client corrects in the same operation, so it is data.
- **`GraphQlLimits`, not `GraphQlProperties`** — the adapter's record would have shadowed Spring
  Boot's own `GraphQlProperties` in every import list.
- **Package keys where REST shares a package.** Onion's and layered's REST package (`presentation`,
  `controller`) would have pulled `api-rest.md` onto GraphQL files as a subpackage; GraphQL got
  `app.graphql` (onion, beside `app.scheduling`) and a top-level `graphql` (layered, with its own
  dependency rules). Vertical-slice keeps a slice's GraphQL controller in the slice and the wiring in
  `config.graphql`, the same compromise `authorization.md` has there (`0112`).
- **Boot 3 is not refused.** Unlike Spring AI, the design is the same on Boot 3; only the Boot-side
  package names in the templates are Boot 4's, and the partial records the line.
- **CORS origins default to none** (`${GRAPHQL_ALLOWED_ORIGINS:}`) — an origin list is not a secret,
  and empty allows no other origin.
- **Off the design, left to its own run:** the `openapi.json` diff `api-rest.md` § How to verify
  promises does not exist in `ci.yml.example` (§ What the context showed).

Goes to the generated project: **yes** — `graphql-api-architect` via `export.skills.include`,
`api-graphql.md` via `export.rules` with its glob derived from the blueprint's `.graphql` entry.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › GraphQL exemplars decide every limit and batch every child list` (`.claude/.ci/GraphQlTemplateTest.java`) | The GraphQL exemplars carry every limit `api-graphql.md` makes explicit, batch every child list, resolve only what they own; no other exemplar imports a GraphQL library or names `graphql.operation.name`; every blueprint has a `.graphql` key and keeps the libraries out of `application` and `domain` | green on the tree; red, by file and reason, on each of 22 injected defects: complexity multiplying every field; no depth limit; no batch size; an inspection that only logs; a per-parent `@SchemaMapping` list; `@Transactional`; no page ceiling (first injection passed — the check matched the name in a message; tightened to the comparison); a catch-all resolver; introspection on in the base yml; a missing `app.graphql` key; CORS `GET,POST`; a root plain list; `Float`; the snapshot on the classpath; an access case in the slice; a blueprint without `.graphql`; `application` allowing `graphql..` (first injection's anchor missed — re-injected); onion's domain allowing it; layered without its rule; a GraphQL import in the REST controller; `graphql.operation.name` in a yml |
| `validate · exemplar-imports` | Every import of the new exemplars resolves | reproduced locally against the Initializr's Boot 4.1.1 project with the job's full dependency list plus `graphql`: 380 checkable imports (35 GraphQL-related), 0 misses; red on a misspelt `MaxQueryDepthInstrumentations` |
| `validate · design › frontmatter schema`, `rules is a leaf`, `norm contains no code boilerplate`, `each norm has a single owner`, `every norm paths matches some blueprint`, `no hardcoded Spring/Java version outside decisions/`, `exemplar bodies carry no comment but Javadoc` | Skill class, sections, export entry, the rule's `paths` and `derived_paths`, the leaf, the globs, dated versions, Javadoc-only bodies | green; the version step first caught "Java 21+" and "Spring Boot 4.1.1" in `pitfalls.md` — reworded |
| `validate · export-determinism` + `ArchHook.java export` by hand | `api-graphql.md` travels with its glob derived from each blueprint | seven blueprints exported, globs above, `schema` exit 0 in each |
| `validate · hooks-cross-platform › … agent instruction files …` (`AgentInstructionsTest`) and `› schema blocks a migrations entry no project would see` (`MigrationsSchemaTest`) | A module `AGENTS.md` missing a blueprint prefix is named; the new migration entry is well-formed | `AgentInstructionsTest` red on the first run after the blueprint change, green with the fixture updated; `MigrationsSchemaTest` green; every other `.claude/.ci/*Test.java` green |
| Outside CI, on the day | The exemplars compile and run | split into a Boot 4.1.1 / Spring for GraphQL 2.0.5 project and run: 7 slice cases, 5 HTTP cases through a real JWT chain, the schema test's eight states (no snapshot; same; again; a removal; a deprecation and addition unrefreshed; refreshed; a deprecated removal; a non-null output field made nullable, reported breaking) as designed. Red, by the right test, on: a per-parent `lines`, no `@HasRole` on `taxId`, no depth bean, an unmapped `customer` field (startup failed by name), a catch-all resolver |
