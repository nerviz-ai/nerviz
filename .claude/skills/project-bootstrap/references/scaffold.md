# `project-bootstrap` — scaffold (steps 4 to 4.10)

Read at step 4 of `SKILL.md`, after the build-tool reference (`build-maven.md` or
`build-gradle.md`) — this file holds only what both tools share. `templates/…` paths are
relative to the skill folder (`${CLAUDE_SKILL_DIR}`).

## 4 · Restructure — both tools

The files in `templates/` are **real, compilable exemplars**, not molds to be
mechanically substituted. Read them, understand the shape, and write the equivalent for
this project. A `domain` module doesn't carry `spring-boot-starter-web` even if the
exemplar shows it in another module.

<a id="andaime"></a>**The exemplar's scaffolding doesn't go into the project.** This
applies to every step that copies from `templates/` — this one, 4.6, 4.7, 4.8, and 4.10. The
top comment block mixes two things:

| Stays | Goes |
|---|---|
| Why the file is shaped this way (e.g. "these two versions are distinct on purpose") | The word `EXEMPLAR` and anything describing the file as a template |
| Citations to rules (`.claude/rules/...`) | "Compilable as-is", "not pseudo-code" |
| Business rule or invariant the reader needs | "role `X` from `packages.map`", generation instructions, references to other `.example` files |

A comment that describes the template instead of the code is exactly what
`@.claude/rules/code-quality.md` forbids — in the generated project there's no template
to refer to.

## 4.5 · Domain exception family — not here

The six exemplars (`DomainException`, the four typed ones and `ConcurrentUpdateException`) live in
`.claude/skills/domain-modeling/templates/`. `domain-modeling` owns the shape of the
domain, and the `10-domain.md` partial names the exceptions for each invariant; the
code comes from the executor, with the first feature.

The bootstrap doesn't write them because it has no invariant to tie them to: five
exception classes in a project with no domain are dead code the user either deletes or,
worse, keeps by mistake. `@.claude/rules/error-handling.md` is still copied in step
6.6, and it's the one that sets the taxonomy — in particular the prohibition on
throwing generic `RuntimeException`/`Exception`/`IllegalArgumentException` in any
module.

`ApiExceptionHandler` isn't from here either — transport-specific, an exemplar of
`rest-api-architect` (see `@.claude/rules/api-rest.md`).

## 4.6 · Generate the Checkstyle config

The limits from `@.claude/rules/code-quality.md` — method length, parameter count,
cyclomatic complexity, nesting, magic numbers — stay prose without Checkstyle. It's the
only automatic check the bootstrap installs. The config file itself
(`config/checkstyle/checkstyle.xml`) is identical either way — only where the version
numbers and the wiring go differs by build tool.

Items 1 and 2 are in the build-tool reference, § 4.6. What both tools share:

3. **Don't change the exemplar's numbers.** Each one mirrors a limit from
   `code-quality.md`; changing one side just makes the rule and the build disagree. If
   the limit has to change, change it in both files, in the same pass.
4. Don't add formatting checks (imports, braces, spacing): Spotless handles that,
   already configured in the root build file. A duplicate check breaks the build over
   something the formatter would have fixed on its own.

Runs before compiling in both build tools — Maven's `validate` phase, Gradle's `check`
task (wired into `build`, so it can't be skipped by running `build` alone) — one
violation stops the build immediately. If the freshly generated project already fails
here, the error is in the translated exemplar or in a class coming from the Initializr,
not in the limit: fix it before continuing.

**Architecture tests (ArchUnit) are not generated here.** The bootstrap delivers a
project with no business code, and a `classes().that()...should()` rule over zero
classes fails vacuously — the build would be born red for having nothing to check.
Writing them is the job of the `test-architect` skill, once classes exist for them to
apply to. The bootstrap only records this in the output contract. The blueprint's
`archunit` feature is still valid data: it's the `test-architect` skill that reads it,
not this step.

**The coverage gate goes out the same door, for the same reason.** The
`pom.parent.xml.example`/`build.gradle.parent.example` exemplar brings JaCoCo with
report generation (`prepare-agent`+`report` in Maven, `jacocoTestReport` in Gradle), and
**without** a coverage gate (Maven's `check` execution, Gradle's
`jacocoTestCoverageVerification`): report yes, gate no. The limits from
`@.claude/rules/testing.md` (80% lines / 70% branches) only make sense over code that
exists, and it's `test-architect`'s setup mode that wires them — in the same pass that
installs ArchUnit. Don't add the gate yourself: a project with no business classes
either passes it vacuously, which proves nothing, or breaks it, which is a false
negative.

## 4.7 · Materialize the packages and feature configuration

The step that replaced example generation. Two parts: the packages come to exist on
disk, and the features that have configuration receive it.

**a) One `package-info.java` per role in `packages.map`.**

An empty directory doesn't survive git, and a `packages.map` that only exists in the
YAML isn't a boundary at all: `ArchHook check` matches by path prefix, and a path that
doesn't exist never matches. For each role declared in `packages.map`, write
`<module>/src/main/java/<package>/package-info.java` with one sentence — the role, and
what that layer may import, derived from the module's `depends_on` and
`forbidden_imports`:

```java
/**
 * Domain. Aggregates, value objects, and invariants.
 *
 * <p>No framework: no {@code org.springframework}, {@code jakarta.*},
 * {@code com.fasterxml.jackson}, or {@code tools.jackson}. See {@code .claude/rules/architecture-ddd.md}.
 */
package com.example.demoapp.domain;
```

One sentence for the role, and the boundary line only when the module declares
`forbidden_imports`. Don't write more: `package-info.java` isn't the place to reproduce
a rule — cite it by path, invariant 2.

**`commons` (or the blueprint's equivalent, e.g. `modular-monolith`'s `shared.logging`)
is the one role that stays empty on purpose.** Its `package-info.java` still gets
written here, same as every other role — but the logging/masking annotations and AOP
aspects that belong in it are `.java` beyond `package-info.java`, which this skill never
writes (`SKILL.md` § Contract). They're installed later, once, by the
`commons-logging-installer` agent, triggered from `/new-feature-implement`'s pre-flight check —
not from here. Don't write them, and don't skip the empty `package-info.java` either:
without the package existing on disk, the installer has nowhere to write into.

```java
/**
 * Cross-cutting logging and masking infrastructure (annotations + AOP aspects). See
 * {@code .claude/rules/logging.md}. Empty until `commons-logging-installer` runs.
 */
package com.example.demoapp.commons.logging;
```

**b) Configuration for active features.**

| Feature | What this step does |
|---|---|
| `actuator` | Merges `templates/features/actuator/application-actuator.yml.example` into the `application.yml` of the module with `contains_main: true` (the same file from step 6, not a new one) |
| `observability` | Merges `templates/features/observability/application-observability.yml.example` into the same `application.yml` — the tracing bridge's export destination. Owned exemplar, same mechanism as `actuator`'s; the container it points at is provisioned in step 4.10, not here. Also adds the method in `templates/features/observability/ApplicationTests-tracer.java.example` to the generated `*ApplicationTests`: a context that starts without a `Tracer` bean is a generation gap, and nothing else catches it before the first use case |
| `flyway` | Creates `src/main/resources/db/migration/` in the module with the `infrastructure.persistence` role, **empty**. No `.gitkeep` and no `V1__`: `spring.flyway.fail-on-missing-locations` defaults to `false` (verified in `spring-boot-flyway`'s metadata), so a missing or empty location doesn't break startup. The first migration comes from `java-spring-boot-developer`, materialized from the SQL `persistence-architect` fixed in the partial |
| `persistence-jpa`, `rest`, `openapi`, `testcontainers`, `archunit` | Nothing here. They're dependencies (step 3) and POM configuration (step 4). The code that uses them comes from the first feature |
| `spring-modulith` | Writes `<main-module>/src/test/java/**/ModularityTests.java`, from `templates/features/spring-modulith/ModularityTests.java.example`, adjusting only the package and the `@SpringBootApplication` class reference. Safe to write now, unlike ArchUnit — `ApplicationModules.of(...).verify()` passes meaningfully over zero modules; it isn't gated behind business code existing |

**No business classes.** No entity, no controller, no use case, no migration with a
table. Each one's shape has an owner — `domain-modeling`, `persistence-architect`,
`rest-api-architect`, `test-architect` — and writing it here creates a second exemplar
of the same role, which diverges from the first. That's what happened:
`@.claude/decisions/0011-bootstrap-without-business-code.md`.

## 4.8 · Generate the `lombok.config`

The root build file declares `org.projectlombok:lombok` as `optional` (Maven) /
`compileOnly`+`annotationProcessor` (Gradle), inherited by all modules either way.
Without this file, `@Data` and `@Setter` compile — and `@.claude/rules/lombok.md` stays
prose, the same way `code-quality.md` stayed without Checkstyle.

1. Write `<project>/lombok.config` with the shape of `templates/lombok.config.example`
   — same file for both build tools, Lombok itself doesn't distinguish Maven from
   Gradle. **One only, at the root, next to the root `pom.xml`/`build.gradle`.** Lombok
   climbs the folder tree until `config.stopBubbling = true`, so the root covers every
   module. Copying the file into each module adds nothing and creates four places to
   diverge.
2. Don't remove lines from it. Each `flagUsage = ERROR` mirrors a rule's prohibition;
   removing one makes the rule and the build disagree, same as in step 4.6.
3. Don't add `lombok.fieldDefaults.defaultPrivate = true`. It would make implicit what
   the rule wants explicit — the `@FieldDefaults(level = AccessLevel.PRIVATE)`
   annotation at the top of the class is what's read in the file, and a global default
   hides it.

There's no version to resolve: `spring-boot-starter-parent` pins Lombok's in Maven, and
the imported `spring-boot-dependencies` BOM pins it the same way in Gradle
(`dependencyManagement { imports { mavenBom "org.springframework.boot:spring-boot-dependencies:..." } }`,
already in `templates/build.gradle.parent.example`). If you write a version yourself, in
either build file, it's the same mistake as step 3 — a number written from memory.

## 4.9 · Generate the `logback-spring.xml`

Without this file the project still logs — Spring Boot's default logback config is
enough to run — but the default pattern has no `traceId` field, and
`@.claude/rules/logging.md`'s per-line contract stays prose nobody's config actually
produces.

1. Write `<module>/src/main/resources/logback-spring.xml` with the shape of
   `templates/logback-spring.xml.example`, where `<module>` is the module with
   `contains_main: true`. **Unlike `lombok.config` (step 4.8), this file cannot sit at
   the project root** — logback only resolves its config from the classpath root, and a
   root-level file is never on it. It has to be under `src/main/resources`, in the
   module that actually gets packaged, and named `logback-spring.xml` (not plain
   `logback.xml`) so Spring Boot's own initialization picks it up.
2. Don't change the pattern line. It's `logging.md`'s contract in executable form; a
   project that needs structured JSON output changes the encoder and the rule's pattern
   line in the same pass, not this file alone.

## 4.10 · Generate the base `Dockerfile` and `docker-compose.yml`

The base pair, plus a container for every feature that's **already active in the
blueprint** and needs one to run. A container is not business code: provisioning the
Postgres that `application.yml`'s own datasource URL already points at (or the OTLP
collector its tracing endpoint already points at) invents nothing — the config from
step 4.7.b already committed to that dependency existing. What *does* stay deferred to
`docker-architect`, invoked later by hand — a design skill records the need and never chains
that skill, `@.claude/decisions/0058-skill-classes-territory-schema.md` — is anything a
**use case** decides that isn't
already implied by an active `features:` flag: a non-default engine, a broker, an
extra datastore. `@.claude/decisions/0011-bootstrap-without-business-code.md` governs
that second category — a made-up aggregate competing with a real spec — not this one.

1. Write `<project>/Dockerfile` from **either** `templates/Dockerfile.example`
   (`build.tool: maven`) **or** `templates/Dockerfile-gradle.example` (`build.tool:
   gradle`) — never both, same exemplar the resolved build tool has used since step 3.
   Fill `{{JAVA_VERSION}}` with the version resolved in step 3 and
   `{{MAIN_MODULE_JAR_PATH}}` with the jar path of the module with `contains_main:
   true`:
   - Maven: `target/<artifactId>-<version>.jar` in `single-module`,
     `<module-path>/target/<artifactId>-<version>.jar` in `multi-module`. In
     `multi-module`, replace `{{MODULE_POM_COPIES}}` with one `COPY <module>/pom.xml
     <module>/` line per module, so the dependency-resolution layer caches correctly;
     in `single-module`, delete that placeholder line — there's nothing to copy beyond
     the root `pom.xml` already copied above.
   - Gradle: `build/libs/<artifactId>-<version>.jar` in `single-module`,
     `<module-path>/build/libs/<artifactId>-<version>.jar` in `multi-module`. In
     `multi-module`, replace `{{MODULE_BUILD_GRADLE_COPIES}}` with one `COPY
     <module>/build.gradle <module>/` line per module, same caching reason; in
     `single-module`, delete that placeholder line — there's nothing to copy beyond the
     root `build.gradle`/`settings.gradle` already copied above.
2. Write `<project>/docker-compose.yml` with the shape of
   `templates/docker-compose.yml.example`, verbatim — no substitution needed, it has no
   `{{...}}` placeholders. This is the base `app` service only.
3. **For each active feature with a matching service template, apply
   `docker-architect`'s own merge procedure (its `SKILL.md` steps 3-5) against the
   `docker-compose.yml` just written** — `docker-architect` stays the single owner of
   every service block, this step only decides *when* to call it for features the
   blueprint already turned on:
   - `persistence-jpa` → `docker-architect/templates/postgres-service.yml.example`.
     Postgres, not a placeholder: it's the engine `application.yml.example`'s
     `datasource.url` already assumes, so this doesn't introduce a new decision, it
     makes the two files agree. If `persistence-architect` later designs a different
     engine for a real use case, that's a `docker-architect` re-invocation like any
     other, swapping the service the normal way.
   - `observability` → `docker-architect/templates/otel-collector-service.yml.example`,
     plus its init script `templates/otel-collector-config.yml.example` mounted per
     `docker-architect/SKILL.md` step 6. **Skip its step 2.5**: generation stays
     non-interactive, so the collector is born exporting to `debug` and no
     visualization backend is chosen here. Naming that gap is step 8's job (`verify-and-report.md`).
   - For `postgres`, also apply `docker-architect/SKILL.md` step 4.5: `{{DB_NAME}}` is the
     artifact with `-` replaced by `_` — the same literal step 3 wrote into
     `application.yml`'s datasource defaults (`${DB_NAME:<it>}`, `${DB_USERNAME:<it>}`) —
     and `.env` (random `DB_PASSWORD`), `.env.example`, and the `.env` line of `.gitignore`
     and `.dockerignore`. Two literals derived twice drift; one literal, written by the
     same run into both files, cannot (`lessons-learned-021.md`).
   - Wire the `app` service's environment for each service added, same as
     `docker-architect/SKILL.md` step 5 — `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` (the
     names `application.yml` reads, never `SPRING_DATASOURCE_*`) pointing at
     `postgres`'s compose hostname, and **both** OTLP variables pointing at
     `otel-collector`'s: `OTLP_ENDPOINT` (`/v1/traces`) and `OTLP_METRICS_ENDPOINT`
     (`/v1/metrics`). Two, not one: the observability fragment declares a placeholder
     per signal, and an unwired metrics endpoint falls back to the app container's own
     `localhost`, silently.
   - A feature with no service template (`rest`, `openapi`, `testcontainers`, `flyway`
     — Flyway rides on the same Postgres connection, `archunit`) adds nothing here.
4. Same rule as § andaime (step 4): the exemplar's top comment explaining *why* stays;
   anything describing the file as a template goes.

