# Lessons learned 025 — `/init-project` for `defy-sentinel`: a Gradle test filter that skips `*Tests`, a Modulith starter set the catalog does not know, and a shared kernel nobody marks `OPEN`

Run date: 2026-10-21. Target repository for these recommendations: `nerviz-ai/nerviz`.

## Run summary

| Item | Value |
|---|---|
| Command | `/init-project --blueprint modular-monolith --build gradle --name defy-sentinel --bounded-context defy-sentinel` |
| Source | this repository's working tree, commit `a3a3343` |
| Coordinates | `io.defysentinel:defy-sentinel`, base package `io.defysentinel.defysentinel` |
| Resolved by the Initializr | Java 21 · Spring Boot 4.1.1 · Spring Modulith 2.1.1 · Gradle 9.8.1 (Groovy DSL) |
| Features | blueprint defaults + `messaging-kafka` (checked by the user) |
| Transport · Sonar | topology A, no local edge · local SonarQube container, anonymous |
| Outcome | Build `PASSED` (`./gradlew build`, ITs with Testcontainers green) |
| Wall time (GENESIS) | 2026-10-21T10:03:37Z → 10:14:49Z, about 11 minutes |
| `project-initializer` | 105 tool uses, 221,651 tokens, 583 s |
| Session tokens (GENESIS) | 102 input · 33,685 output · 6,940,575 cache read · 233,644 cache write — 50 requests |
| git | not initialized — the user declined gate 1 of `git-publish` |

The input was a design summary for a crypto/DeFi paper-trading simulator: six bounded contexts,
Kafka, TimescaleDB, Redis, EVM and Solana forks. The run produced a green skeleton in about a fifth
of the time `lessons-learned-020` measured. What follows is every place where the agent had to
correct the procedure or where the procedure gave a wrong answer, checked against `HEAD`.

## What worked as designed

1. **The interview stayed in the main session** (decision 0123). Two `AskUserQuestion` calls
   covered Kafka, coordinates, project name, transport, the local-edge follow-up and both Sonar
   questions. The agent asked nothing and never handed back.
2. **The single-`false`-feature rule** turned Kafka into a two-option single-select, as step 2.3
   of `init-project` says.
3. **`ArchHook compose` caught a real tag mismatch.** The Initializr writes
   `postgres:latest` in `TestcontainersConfiguration.java`; compose pins `postgres:16-alpine`.
   The agent aligned the Java side.
4. **`audit genesis`** filled Started, Finished and tokens on the first try, start before finish
   (the § 4 defect of `lessons-learned-020` did not recur).
5. **The Docker guard and the `Tracer` test** in `*ApplicationTests` came from
   `features/observability/ApplicationTests-tracer.java.example` and
   `verify-and-report.md` line 35, as designed. The agent's report listed them as deviations;
   they are not.
6. **The compose services were merged from `docker-architect`'s templates inside step 4.10**, as
   `scaffold.md` lines 228-250 prescribe. The agent's report listed "merged by hand, not via Skill
   invocation" as a deviation; that is the documented procedure.

## § 1 · The Gradle exemplar runs only `*Test`, and the generated project has two `*Tests`

`templates/build.gradle.parent.example` line 112:

```groovy
tasks.named('test') {
    useJUnitPlatform()
    include '**/*Test.class'
    exclude '**/*IT.class'
```

The project the same procedure generates has two classes this filter skips:

- `DefySentinelApplicationTests`, from the Initializr, which also carries the `Tracer` test step
  4.7 adds;
- `ModularityTests`, from `templates/features/spring-modulith/ModularityTests.java.example`, which
  runs `ApplicationModules.verify()`, the modular-monolith blueprint's reason to exist.

The agent saw it and widened the filter to `*Tests.class` as well. Followed literally, the
exemplar gives a Gradle project whose module verification and context-load test never run, with
`./gradlew build` green. Maven does not have the defect: Surefire's default includes already
cover `**/*Tests.java`, so the two build tools disagree on what "the unit tests" means.

The `rules/naming.md` suffix (`<Class>Test`) and the names this repository's own exemplars and the
Initializr produce (`*Tests`) disagree. Either side can move, but today the gap is silent.

*Recommendation:* add `include '**/*Tests.class'` to the exemplar, or rename
`ModularityTests.java.example` and have step 4.7 rename `*ApplicationTests`, and record which.
A check that every test class under `src/test/java` matches a filter of some task would turn the
next case from silent to loud.

## § 2 · The Modulith dependency set the Initializr serves is not the one the catalog describes

`references/dependency-catalog.md` line 18 says `spring-modulith` "adds
`spring-modulith-starter-core` (compile)". With `modulith` + `data-jpa` + `kafka` selected, the
Initializr for Boot 4.1.1 returned more:

- `spring-modulith-events-api`, `spring-modulith-observability-api`, `spring-modulith-starter-core`
- runtime: `spring-modulith-actuator`, `spring-modulith-observability-core`,
  `spring-modulith-runtime`
- `spring-modulith-starter-jpa` and `spring-modulith-events-kafka` — the event publication
  registry on JPA and the Kafka externalization

The last two made the context fail: the JPA registry needs an `event_publication` table,
`ddl-auto=validate` refuses to start without it, and no migration exists because step 4 writes no
business code. Setting `schema-initialization` did not help (that property belongs to the JDBC
registry, not the JPA one). The agent removed both starters, and the build went green.

The removal is defensible under decision 0011 (no business code, so no events yet). But the
procedure never said it. The next run could keep them and write a Flyway migration, or remove a
different set. Nothing in `HEAD` mentions `event_publication`, `starter-jpa` or `events-kafka`.

*Recommendation:* the catalog row for `spring-modulith` should name the combination: which
artifacts the Initializr adds next to `data-jpa` and `kafka`, and the decision to remove the
registry and externalization at bootstrap. The first messaging use case that needs the outbox
(`persistence-architect` and `messaging-architect`) would then own the
`event_publication` migration and the starters together. Today neither skill knows they were
removed.

## § 3 · The blueprint says `shared` is `OPEN`, and no step writes the marker

The `packages.map` comment of `modular-monolith.yaml` says `shared` is a Shared Kernel "marked
`@ApplicationModule(type = OPEN)` so other modules may reference it freely", and the
`commons.logging` comment relies on it. `scaffold.md` § a) writes one `package-info.java` per
`packages.map` role, all inside `shared.internal.*` or `shared.logging`. Nothing writes
`shared/package-info.java` with the annotation, and `grep -rn "ApplicationModule("` over
`project-bootstrap` returns nothing.

The agent added it, as the 16th `package-info.java`. Without it, the first module to import a
`shared` type fails `ModularityTests`, but only once § 1 is fixed, since today that test does not
run under Gradle.

*Recommendation:* step 4.7's `spring-modulith` row should write `shared/package-info.java` with
`@ApplicationModule(type = ApplicationModule.Type.OPEN)`, from an exemplar under
`templates/features/spring-modulith/`. The blueprint already describes it, so this is a gap in the
skill, not new data.

## § 4 · `init-project` says GENESIS has four placeholders; the procedure leaves three

`skills/init-project/SKILL.md` line 129: "`audit genesis` replacing four placeholders". 
`verify-and-report.md` § 8.6 and `templates/GENESIS.md.example` leave three literally:
`{{startIso}}`, `{{endIso}}`, `{{tokens}}`. The agent spent effort looking for a fourth and handed
the question back in its report ("check the file for any remaining fourth GENESIS placeholder").

*Recommendation:* change "four" to "three" in the Contract section. A number written in two files
has diverged, as invariant 2 predicts.

## § 5 · The existence check reads a listing cut at 30 lines

Step 1 of `init-project` checks the `--name` against the injected
`` !`ls -a "${CLAUDE_PROJECT_DIR:-.}/.." | head -30` ``. The parent directory in this run has 38
entries; the listing stopped at `pluginrail`, so any name after it alphabetically was invisible.
`defy-sentinel` sorts early, so the run was right by luck, not by check.
`project-bootstrap`'s own precondition (an existing `pom.xml` or `build.gradle` stops it) is the
backstop, but it fires inside the background agent, after the whole interview.

*Recommendation:* when `--name` is given, step 1 should run `ls -a ../<name>` directly. It already
says to do that "if the listing shows that name". Drop that condition, so the check never depends
on the listing's length.

## § 6 · Initializr test images left on `latest`

The Initializr writes `apache/kafka-native:latest` and `postgres:latest` in
`TestcontainersConfiguration.java`. The compose check fixed Postgres (§ "What worked" 3), because
compose has a Postgres service to compare with. Kafka has no compose service until the first
messaging use case, so nothing compares it, and `latest` stays. Every context test also starts a
Kafka container in a project that has no producer or consumer yet.

*Recommendation:* step 4 should replace every `:latest` the Initializr writes with a tag resolved
the same way the compose templates resolve theirs, whether or not a compose service exists. Or it
should record the leftover in the Output contract, so the person sees it. Whether a Kafka
container belongs in the bootstrap context test at all is an open question for the designer.

## § 7 · My own slip before the run: "Maven only"

Asked which parameters to use, I answered that `--build maven` was "the only build the blueprint
declares", and the user had to ask whether Gradle existed. It does: `_schema.md` marks `build.tool`
as "Overridable at initialization", and `project-bootstrap` § 151 says "a default, not a
lock-in". Two cues in `modular-monolith.yaml` misled me:

- `build.tool: maven` with no comment saying it is a default;
- the `templates:` block lists only `pom.parent.xml.example` and `pom.module.xml.example`. The
  Gradle exemplars live in `project-bootstrap/templates/`, not in the blueprint.

The interview's question 2 would have asked anyway, so no generation was affected. But an answer
given before `/init-project` is advice, and it was wrong.

*Recommendation:* a one-line comment next to `build.tool` in every blueprint (or in the blueprint
README's quick table, whose `Build` column shows only single- or multi-module) saying that both
tools are offered at initialization.

## § 8 · Smaller notes

- **No git.** The user declined gate 1. `doctor`'s `git HEAD` line stays red until the first
  commit. That is expected, but the closing message of `init-project` (step 7) does not mention
  it. A person who skips the commit learns about it from `/arch-doctor` in the new session.
- **Foreign containers on the project's ports.** `doctor`'s Compose line warned that
  `bank-app-otel-collector-1` holds 4318 and `darkforge-postgres` holds 5432. That is correct
  behaviour (warning, not block, while no container of the project exists). The first
  `docker compose up` in the new project will fail on those ports until they are freed.
- **Shell in the main session.** An `echo =======` I ran in zsh failed with `====== not found`
  (zsh expands a word starting with `=` as a command path). It was harmless and was my tooling,
  not the procedure.

## Prioritized recommendations

1. **§ 1** — Make the Gradle `test` filter run `*Tests`, or rename the two classes; today
   `ModularityTests` never runs in a Gradle modular monolith.
2. **§ 3** — Write `shared/package-info.java` with `@ApplicationModule(type = OPEN)` in step 4.7.
3. **§ 2** — Name the Modulith + JPA + Kafka starter set in `dependency-catalog.md`, and the
   decision to remove the registry and externalization at bootstrap, with the skill that brings
   them back.
4. **§ 5** — Check `../<name>` directly, not through a listing cut at 30 lines.
5. **§ 6** — Pin or report every Initializr `:latest` test image.
6. **§ 4, § 7** — Fix "four placeholders"; say `build.tool` is a default where a reader picks a
   blueprint.

## Open questions for `/claude-code-architect-designer`

- § 1: which side moves, the `Test` suffix of `rules/naming.md` or the names of the exemplars?
- § 2: should the bootstrap keep the event publication registry (with its migration) when
  `spring-modulith` and `persistence-jpa` are both on, since the blueprint relies on module events?
- § 6: should a bootstrap context test start a broker the project does not use yet?
