# 0162 · The asynchronous contract is generated from the code by Springwolf and compared with a committed AsyncAPI snapshot

- **Date:** 2026-10-15
- **Scenario:** "Incluir documentação AsyncAPI do contrato assíncrono na skill messaging-architect, via
  Springwolf" — the generated project publishes and consumes Kafka events, the contract (channels,
  operations, payloads, headers, partition key) exists only in `25-messaging.md` and in the code, no
  external consumer has an AsyncAPI document to read, and nothing detects when the code diverges from it
- **Decision:** Option 1 — extend `messaging-architect`, `rules/messaging.md`, `test-architect` and `security-architect`; no new skill, agent, rule or hook
- **State:** approved by Lucas Fernandes, on 2026-10-15 ("aprovo opção 1"); implemented on the branch `feat/asyncapi-springwolf`

## What the code and the network showed

| Fact | Where |
|---|---|
| Springwolf 2.x targets Spring Boot 4; latest 2.8.0 (2026-09-28), its POM imports `spring-boot-dependencies` 4.1.1; modules `springwolf-kafka`, `-amqp`, `-sqs`, `-sns`, `-jms`, `-stomp`, `-cloud-stream`, `springwolf-ui` | Maven Central + GitHub releases, verified by the requester on 2026-10-15 |
| Springwolf 1.x targets Boot 3; latest 1.21.0, critical fixes only | same |
| The core depends on `spring-boot-jackson2` — Jackson 2 beside Boot 4's Jackson 3 | same |
| It generates AsyncAPI 3.0 at runtime from `@KafkaListener`; a producer needs `@AsyncPublisher`/`@AsyncOperation`; payloads as JSON Schema of the classes | same |
| A Boot 3.x project never receives this `.claude/`: `doctor.platform.min_line` is `4.0` and `export` exits 2 below it — so Springwolf 1.x has no reader, and the line choice is not a question | `.claude/schemas/extensions.json` `doctor.platform`, `@.claude/decisions/0161-spring-boot-3-5-line-for-adopted-projects.md` (option C) |
| The wire is JSON **text**: `StringSerializer`, the payload converted by the Form A publisher or Form B's `OutboxAppender`, the relay sending the text untouched. A producer annotation inferring from `KafkaTemplate<String,String>` documents `string` | `@.claude/rules/messaging.md` § Topics and serialization, `messaging-architect/templates/application-kafka.yml.example` |
| REST's contract is already code-first (springdoc) and its rule asks for the generated document to be compared with the committed one — a rule line with no exemplar behind it | `@.claude/rules/api-rest.md` § OpenAPI, § How to verify |
| GraphQL's committed-snapshot gate exists as a `test-architect` exemplar, run by `./mvnw verify`, so CI gates it with no workflow step | `test-architect/templates/GraphQlSchemaCompatibilityTest.java.example`, `@.claude/rules/api-graphql.md` § Evolution |
| `SecurityConfig` already keeps one `API_DOCS` list public by default | `security-architect/templates/SecurityConfig.java.example`, `@.claude/rules/authorization.md` |
| `exemplar-imports` resolves only allow-listed import prefixes; a non-Initializr library enters with its version read from `maven-metadata.xml` (the Resilience4j precedent) | `.github/workflows/validate.yml` job `exemplar-imports` |
| `messaging.md` already says a second broker is a new section there, not a new file | `@.claude/rules/messaging.md` header |

## Interview

| Axis | Answer | Forms it eliminated |
|---|---|---|
| 1 — Symptom | No AsyncAPI document for external consumers; no check catches code drifting from the contract | create nothing |
| Approach | Code-first with Springwolf; spec-first (ZenWave SDK) recorded in the norm with when / when-not criteria **and** its own template | spec-first as the default |
| 9 — Who writes what | The design adds rows to § 1–3 of `25-messaging.md` (stable `operationId`, description, headers) — no new section restating topic, key and payload; the executor annotates producer and consumer | a separate contract section (invariant 2) |
| 7 — Guarantee | A snapshot test owned by `test-architect` (`40-tests.md`), run by `verify` — a CI gate with no new workflow step | export + `git diff` step in `ci.yml.example` |
| Configuration | A `springwolf` block inside `application-kafka.yml.example`, written by the project's first Kafka case | a new yml template |
| Exposure | Same rule as OpenAPI and Swagger UI: public by default unless the project decides otherwise | profile-gated docs, operator role |
| UI publishing | Off by default in every profile; only the local profile may turn it on | always on in dev |
| Scope | Kafka only, `springwolf-kafka`; other modules when the skill covers that broker | AMQP/SQS/JMS exemplars now (anti-pattern 9) |
| Platform line | 2.x only, version resolved at runtime — settled by `0161`, not asked | 1.x branch |
| 8 — Destination | Both: norm, exemplars and a `migrations` entry travel | meta-repo-only |
| Existing projects | A `migrations` entry `asyncapi-springwolf` for `arch-adopt` | — |

## Options evaluated

| # | Option | Score | Verdict |
|---|---|---|---|
| 1 | Extend `messaging-architect` + a section of `rules/messaging.md` + `test-architect` exemplar + `migrations` entry | 8 | **Approved** |
| 2 | Same, with a new rule `rules/asyncapi.md` | 5 | Rejected — `messaging.md` already says a broker's concerns stay in it, and two rules with one glob load together anyway |
| 3 | New skill `asyncapi-architect` in the pipeline | 3 | Rejected — fails invariant 2 and adds a `/new-feature` step for rows `messaging-architect` already owns |
| 4 | create nothing | 2 | Rejected — the gap is observed: no document for consumers, no check on drift |

### Option 1 — extend the existing pieces (score 8)

**Motivator:** axis 9 — topic, key and payload already have one owner, `messaging-architect`'s
partial; the contract document is a view of them, so its norm and its design rows belong where they are.

**Pros:** no new piece in context; one owner per fact; the snapshot rides `verify` like GraphQL's;
`authorization.md`'s public-docs line gains paths, not a second policy; the spec-first alternative lives
next to the default it was weighed against (`@.claude/rules/messaging.md`, pattern of `http-client-architect`'s
OpenAPI Generator).

**Cons:** `messaging.md` and the skill body grow; Jackson 2 lands on the classpath of every Kafka project
(effect on Boot 4's own JSON autoconfiguration to be proven against a real Initializr project before
commit); the snapshot is exact text, it does not classify a breaking change the way `SchemaDiff` does
for GraphQL.

**Points cut in the rubric:** context cost (§ 8) — the rule section loads on every messaging adapter edit.

**CI:** `validate` › `schema` (export, classes, frontmatter); `validate` › `exemplar-imports` gains
`springwolf-kafka` (version from `maven-metadata.xml`) and the `io\.github\.springwolf` prefix; a new
`.claude/.ci/AsyncApiTemplateTest.java` in `validate` next to `MessagingRetryTemplateTest` — publishing
off in the yml exemplar, `@AsyncPublisher` with an explicit payload type on the converting class and
never on the relay sender, the snapshot path `docs/asyncapi.json`, the springwolf paths in `API_DOCS`;
`MigrationsSchemaTest` covers the new entry. No `ci.yml.example` change: the test runs in `verify`.

```
rules/messaging.md ── § Contract documentation (AsyncAPI) ── cites ─ authorization.md (public docs)
      ↑ cites
messaging-architect ── 25-messaging.md § 1–3 rows ──→ executor annotates
   templates/: application-kafka.yml (springwolf block) · KafkaProducerAdapter · OutboxRelayPublisher
               · KafkaConsumerAdapter · zenwave-sdk-maven.xml (rejected alternative)
test-architect ── 40-tests.md ── templates/AsyncApiContractIT.java ── docs/asyncapi.json
security-architect ── SecurityConfig API_DOCS += springwolf paths
extensions.json ── migrations: asyncapi-springwolf
```

### Option 2 — a new rule `rules/asyncapi.md` (score 5)

Same files, the norm in its own file with the same `paths`. Rejected candidate: `messaging.md` states a
broker's concerns stay in it, two rules with identical globs load together anyway, and the alternative's
criteria would sit away from the transport choices they depend on. **CI:** as option 1, plus a
`00-index.md` row.

### Option 3 — a skill `asyncapi-architect` (score 3)

A pipeline step for three rows that `messaging-architect` already owns the facts of — fails invariant 2
and adds a step to `/new-feature`. **CI:** `schema` for the skill, plus everything of option 1.

### Option 4 — create nothing (score 2)

The gap is observed, not anticipated: no document for consumers, and REST's comparison rule has no
exemplar either. **CI:** nothing.

## References

| Claim | Source |
|---|---|
| A norm has one owning file | `@CLAUDE.md` invariant 2 |
| Boilerplate lives in the emitting skill's `templates/` | `@CLAUDE.md` invariant 3 |
| Versions never from memory | `@CLAUDE.md` invariant 8; Resilience4j precedent in `validate.yml` › `exemplar-imports` |
| What travels is the `export` block; convention changes reach old projects through `migrations` | `@CLAUDE.md` invariant 9; `extensions.json` `migrations.$comment` |
| Boot 3.x projects never receive this `.claude/` | `@.claude/decisions/0161-spring-boot-3-5-line-for-adopted-projects.md` |
| Code-first contract precedent | `@.claude/rules/api-rest.md` § OpenAPI |
| Snapshot gate precedent | `test-architect/templates/GraphQlSchemaCompatibilityTest.java.example`, `@.claude/decisions/0146-graphql-api-architect-skill.md` |
| Rejected mechanism with criteria and template | `http-client-architect/SKILL.md` (OpenAPI Generator as a recorded choice beside `@HttpExchange`), `http-client-architect/templates/openapi-generator-maven.xml.example` |

## Verified before committing

The draft above recorded some of this from the requester's notes. Each row below was run on
2026-10-15 against a Boot 4.1.1 project from the Initializr with `springwolf-kafka` 2.8.0, and it
corrected the draft where they differ. The scratch project and its probes live outside the repository.

| What was run | Result | What it changed |
|---|---|---|
| `@AsyncPublisher` on a package-private `@Component`, a listener with `@KafkaListener` only, context started with the listener containers off | Document served at `/springwolf/docs`, **AsyncAPI 3.1.0**; the `JsonMapper` bean stayed `tools.jackson.databind.json.JsonMapper`; `.yaml` path answered 404; two runs produced identical bytes | The draft said 3.0: no norm writes a spec version. Jackson 2 beside Jackson 3 does not displace Boot's mapper |
| The same listener found by Springwolf's own `@KafkaListener` scanner | A `__TypeId__` header documented with a constant value that this String wire never carries; message named after the payload class | Both sides annotated explicitly and `kafka-listener.enabled: false` |
| `@AsyncListener` added beside `@KafkaListener`, scanner still on | The `receive` operation listed two messages and lost the consumer group binding | Same |
| `@AsyncListener` + `@KafkaAsyncOperationBinding(groupId)`, scanner off | One message, group id rendered | The consumer exemplar |
| `@KafkaAsyncOperationBinding(messageBinding = …key…)` on the producer | Accepted, **not rendered** | The partition key is written in the operation's `description` |
| `javap` on `AsyncOperation` and a read of `OperationIdHelper` | No operation-id attribute; the id is `<channel>_<send\|receive>_<method name>` | The draft proposed a "stable `operationId`" row: it is derived, so the port and listener method names are the contract |
| Property metadata of the 2.8.0 jars | `springwolf.plugin.kafka.publishing.enabled` defaults to `false`; `springwolf-ui` is a separate artifact; `base-package` has no default | Publishing written explicitly off; no UI dependency declared; `base-package` in the yml block |
| The contract IT exemplar adapted into that project: no snapshot → copy → green → port method renamed → red | Red naming the copy step; green; red with `…_send_publish` → `…_send_emit` as the whole diff; green restored | The snapshot test is the gate, and it catches the rename |
| ZenWave SDK `zenwave-sdk-maven-plugin` run on the generated document with `generatorName` `spring-cloud-streams3` | Generated Spring Cloud Stream producer and consumer classes and in-memory doubles; method names built from dotted operation ids did not compile as Java | The rejected alternative's "when not": it replaces the `KafkaTemplate` and `@KafkaListener` adapters, and its template says so |

Corrections to the draft's plan: the snapshot is at `src/test/asyncapi/asyncapi.snapshot.json`, not
`docs/asyncapi.*` — the executor's write territory is `src/**`, `pom.xml` and the spec, and widening it
for one file is a schema change this record does not need, while the GraphQL snapshot already sits under
`src/test/`; the external consumer reads the served `/springwolf/docs` or that file. The first draft's
`com.fasterxml` prohibition was too wide — Jackson 3 keeps `com.fasterxml.jackson.annotation` — and the
grep, the test and the norm say `databind`/`core`.

## Propagation

| File | Change |
|---|---|
| `.claude/rules/messaging.md` | § Contract documentation (AsyncAPI) — code-first, annotation placement, derived operation ids, no `servers`, configuration, exposure, publishing off, Jackson, snapshot; § Spec-first with when / when-not criteria; five lines in § How to verify |
| `.claude/rules/authorization.md` | The public-by-default line names the AsyncAPI document beside OpenAPI and Swagger, one decision for all |
| `.claude/rules/00-index.md` | `messaging.md` row |
| `.claude/skills/messaging-architect/SKILL.md` | Description, axis "Who owns the contract" with the cost in the question, step 7c (the contract rows), contents table, contract section |
| `.claude/skills/messaging-architect/templates/` | `KafkaProducerAdapter`, `OutboxRelayPublisher`, `KafkaConsumerAdapter` annotated; `application-kafka.yml` `springwolf` block; `messaging-spec.md` four rows, § 7 dependency row; **new** `zenwave-sdk-maven.xml.example` (the rejected alternative) |
| `.claude/skills/messaging-architect/references/springwolf-reference.md` | **New** — what was verified, and where the version comes from |
| `.claude/skills/test-architect/` | SKILL.md paragraph and table row; **new** `templates/AsyncApiContractIT.java.example` |
| `.claude/skills/security-architect/` | `SecurityConfig` `API_DOCS` and its header; `application-security.yml` comment; the API-docs decision row |
| `.claude/agents/java-spring-boot-developer.md` | Block M annotations, `springwolf` block and dependency with its version source, validation and report lines; Block 4 contract test and snapshot creation |
| `.claude/schemas/extensions.json` | `migrations` entry `asyncapi-springwolf` |
| `.claude/.ci/AsyncApiTemplateTest.java` | **New** |
| `.github/workflows/validate.yml` | `exemplar-imports`: `springwolf-kafka` (latest stable 2.x from `maven-metadata.xml`) and the `io.github.springwolf` prefix; a step for the new test, in the `design` job beside `MessagingRetryTemplateTest` |
| `CLAUDE.md`, `README.md`, `docs/{en,pt-br}/03-new-feature.md`, `14-rules.md`, `07-ci-validate.md`, `11-pitfalls.md` | Routing and descriptions; the CI row, node and command; one pitfall |

Goes to the generated project: **yes, with no `export` edit** — `messaging-architect` and `test-architect`
are in `export.skills.include` and `export.skills.keep` carries `templates` and `references`, the rule and
the agent travel already, and the `migrations` entry reaches projects generated before this. `schema` passes.
Not done, on purpose: `ci.yml.example` and `ci-gradle.yml.example` — the contract test is an `IT` and runs in
`./mvnw verify`, which both already run.

## CI coverage

| Pipeline · job › step | What it proves | Run |
|---|---|---|
| `validate · design › AsyncAPI exemplars annotate both sides explicitly and gate the served document against a snapshot` (`AsyncApiTemplateTest.java`) | The consumer and both producer exemplars carry the annotations the norm decides, the relay's sender carries none, the yml turns the listener scanner and UI publishing off and declares no `servers` or `info.version`, the contract IT reads `/springwolf/docs` and compares trees with the snapshot off the classpath, no version is pinned, and the migration, the agent and the norm name one snapshot path | green on the tree · red, each by file name, with the scanner turned on in the yml, an annotation on `KafkaOutboxEventSender`, a pinned ZenWave version, the publisher without `@Component`, and the snapshot moved to `src/test/resources` |
| `validate · exemplar-imports › every exemplar import exists in a JAR` | `io.github.springwolf.core…` and `…bindings.kafka…` resolve in the reference project once `springwolf-kafka` is on it | the four changed exemplars' imports resolved against a Boot 4.1.1 classpath by hand (`javap`); the CI run is the real one |
| `validate · design › frontmatter schema` | The migration entry's id and fields, the skills' export and class entries, the executor's sections | green on the tree · red with the id spelled `Asyncapi_Springwolf` |
| `validate · design › no hardcoded Spring/Java version` and `rules is a leaf of the graph` | No version written from memory; the norm mentions no skill or agent | green on the tree |
| `./mvnw verify` of a generated project (`AsyncApiContractIT`) | The served document equals the committed snapshot | proven in the reference project: red with no snapshot, green, red on a renamed method, green |
